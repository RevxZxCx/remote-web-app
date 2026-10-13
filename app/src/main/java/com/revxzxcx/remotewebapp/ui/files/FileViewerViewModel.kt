package com.revxzxcx.remotewebapp.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.revxzxcx.remotewebapp.core.changes.ChangesDiff
import com.revxzxcx.remotewebapp.core.changes.ChangesRepository
import com.revxzxcx.remotewebapp.core.connection.DshConnection
import com.revxzxcx.remotewebapp.core.files.FileBytes
import com.revxzxcx.remotewebapp.core.files.FileKind
import com.revxzxcx.remotewebapp.core.files.WorkspaceFileError
import com.revxzxcx.remotewebapp.core.files.WorkspaceFileRepository
import com.revxzxcx.remotewebapp.core.protocol.TypertException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 文件查看页的两个标签页。 */
enum class FileTab {
    /** 本轮 diff（只有从改动卡片进来才有）。 */
    DIFF,

    /** 当前全文。 */
    CONTENT,
}

/**
 * 文件查看页的状态。
 *
 * 设计要点：**diff 与全文是两条独立的状态**。diff 来自插件内存（随时可能 404），
 * 全文来自 `workspaceFiles`（只读磁盘）。把它们塞进一个 `loading` 会让「diff 取不到」
 * 被误显示成「文件读不出来」——这是两种完全不同的失败。
 */
data class FileViewerState(
    val target: FileTarget,
    val kind: FileKind,
    val tab: FileTab,
    /** 全文首屏是否还在加载。 */
    val loading: Boolean = true,
    /** 全文相关的错误（已翻译成中文）。 */
    val error: String? = null,
    /** diff 是否在加载。 */
    val diffLoading: Boolean = false,
    /** diff 结果；[ChangesDiff.Binary]/[ChangesDiff.Oversized] 也是有效结果。 */
    val diff: ChangesDiff? = null,
    /**
     * diff **取不到**（`changes.diff` 返回 404：桌面端重启后记录已不在内存）。
     *
     * 与 `diff == null && !diffLoading` 的区别：那个也可能是「还没开始拉」。
     */
    val diffMissing: Boolean = false,
    /** diff 读取失败（不是 404：网络/解析问题），可重试。 */
    val diffError: String? = null,
    /** 已经拿到的文本行（按文件行号顺序，第一行是 [startLine]）。 */
    val lines: List<String> = emptyList(),
    /** [lines] 第一行对应的文件行号（1 起）。 */
    val startLine: Int = 1,
    /** 已经读到文件末尾。 */
    val eof: Boolean = true,
    val loadingMore: Boolean = false,
    /** 当前文件的绝对路径（`stat` 或 `read` 回的，比工具参数更权威）。 */
    val absolutePath: String? = null,
    val bytes: Long? = null,
    /** 图片 / PDF 的原始字节。 */
    val media: ByteArray? = null,
    /** 「在电脑上打开」的进行状态与结果。 */
    val openingOnDesktop: Boolean = false,
    /** 一次性提示（Snackbar 用，消费后清空）。 */
    val message: String? = null,
) {
    /** 有没有可指的 diff 入口。 */
    val canShowDiff: Boolean get() = target.hasDiff
}

/**
 * 文件查看页的 ViewModel（需求 FR-5.5 / FR-5.6 / FR-5.7）。
 *
 * 三种内容形态，按 [FileKind] 分流：
 *  - [FileKind.MARKDOWN]/[FileKind.CODE]/[FileKind.TEXT] → `workspaceFiles` 的 read 分页文本；
 *  - [FileKind.IMAGE]/[FileKind.PDF] → readBytes 整块字节；
 *  - 其余（含未知扩展名）先按文本试，服务端回 `workspace-file/not-text` 就如实报错——
 *    这比客户端猜二进制更准，因为判据只有一个（服务端真正读到的字节）。
 *
 * 没有文件编辑：`dsh-api-workspace-files` 不提供任何写接口（设计决定 D15）。
 */
class FileViewerViewModel(
    private val connection: DshConnection,
    private val sessionId: String,
) : ViewModel() {

    private val files = WorkspaceFileRepository(connection)
    private val changes = ChangesRepository(connection)

    /**
     * 初始状态指向「没有文件」。真正的目标由 [open] 设置。
     *
     * 为什么不在构造函数里收 target（R005 P1）：`viewModel(key=…)` 挂在 Activity 的
     * ViewModelStore 上，`openFile = null` 只切换组合、**不会** clear。早前按
     * `file-$seq-$index-$path` 建 key，于是每看一个文件就多一个永不回收的 ViewModel，
     * 其中 `lines`（分页累积的全文）与 `media`（最大 32 MiB 的图片字节）常驻内存 ——
     * 连看几张照片就够 OOM。现在每个会话只有一个实例，换文件走 [open] 就地重置。
     */
    private val _state = MutableStateFlow(
        FileViewerState(
            target = FileTarget(path = ""),
            kind = FileKind.TEXT,
            tab = FileTab.CONTENT,
            loading = false,
        ),
    )
    val state: StateFlow<FileViewerState> = _state.asStateFlow()

    private var bodyJob: Job? = null
    private var diffJob: Job? = null

    /**
     * 请求代数（R005 P1）。用于识别「已经不作数的旧请求」。
     *
     * 为什么必须有：`loadPage` 先 `bodyJob?.cancel()` 再同步起新 job，而
     * `viewModelScope` 跑在 `Main.immediate` 上，被取消那个协程的
     * `catch (CancellationException)` **会在新 job 启动之后**才执行，于是它写的
     * `loading = false` 会把新请求刚设的 `loading = true` 擦掉（转圈闪断，守卫误判成
     * 「没在加载了」，同一 offset 还能被重复请求、页被重复追加）。
     * 每次发起请求前自增，回写前比对，只有最新一代才能落到状态上。
     */
    private var bodyGeneration = 0

    /** diff 的请求代数，理由同 [bodyGeneration]。 */
    private var diffGeneration = 0

    /**
     * 打开一个文件（R005 P1）。换文件时把状态整体重置，并重新起加载。
     *
     * 幂等：目标相同时不做任何事，避免重组把已翻的页、已选的标签页冲掉。
     */
    fun open(target: FileTarget) {
        if (_state.value.target == target) return
        // 旧目标的所有在途请求全部作废。
        bodyGeneration++
        diffGeneration++
        bodyJob?.cancel()
        diffJob?.cancel()
        _state.value = FileViewerState(
            target = target,
            kind = FileKind.of(target.path),
            // 从改动卡片进来默认看 diff；否则直接看全文。
            tab = if (target.hasDiff) FileTab.DIFF else FileTab.CONTENT,
        )
        if (target.hasDiff) loadDiff()
        loadContent()
    }

    /** 当前目标（随 [open] 变；早前是构造函数参数，见 [open] 的 KDoc）。 */
    private val target: FileTarget get() = _state.value.target

    /** 切标签页。 */
    fun selectTab(tab: FileTab) {
        if (_state.value.tab == tab) return
        _state.value = _state.value.copy(tab = tab)
        // 从 diff 切到全文而全文还没成功过时补拉一次（首屏可能失败过）。
        if (tab == FileTab.CONTENT && _state.value.lines.isEmpty() && _state.value.error != null) {
            loadContent()
        }
    }

    /** 重试全文；若还没成功过 diff（含失败过）也一并重试。 */
    fun retry() {
        if (target.hasDiff && (_state.value.diff == null || _state.value.diffError != null)) loadDiff()
        loadContent()
    }

    /** 加载下一页（FR-5.7）。 */
    fun loadMore() {
        val current = _state.value
        if (current.loadingMore || current.eof || current.loading) return
        // 服务端 `range.offset` 是 **1 起** 的行号，下一页从「已读行数 + 1」继续。
        // （实测 `offset:0` 被拒：`offset must be a safe integer of at least 1`。）
        val nextOffset = current.startLine + current.lines.size
        loadPage(nextOffset, append = true)
    }

    /** 在桌面端打开/定位这个文件（FR-5.10）。只有确实有改动记录时可用。 */
    fun openOnDesktop() {
        val seq = target.seq ?: return
        val index = target.index ?: return
        if (_state.value.openingOnDesktop) return
        _state.value = _state.value.copy(openingOnDesktop = true, message = null)
        viewModelScope.launch {
            val message = try {
                changes.openOnDesktop(sessionId, seq, index)
                "已让电脑打开这个文件"
            } catch (e: CancellationException) {
                _state.value = _state.value.copy(openingOnDesktop = false)
                throw e
            } catch (e: Exception) {
                "打开失败：${describeFileFailure(e)}"
            }
            _state.value = _state.value.copy(openingOnDesktop = false, message = message)
        }
    }

    /** 清掉一次性提示（Snackbar 用完就丢）。 */
    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    // ---------------------------------------------------------------- 加载

    private fun loadContent() {
        // [open] 之前没有目标（初始状态的 path 是空串），不能拿它去请求。
        if (target.path.isEmpty()) return
        when (_state.value.kind) {
            FileKind.IMAGE -> loadMedia()
            // PDF 只取 stat（R005 P3）：App 里没有 PDF 渲染器，整块下载（最大 32 MiB）
            // 只为了显示一行「暂不支持预览」，纯属浪费。大小从 `stat` 就够。
            FileKind.PDF -> loadPdfStat()
            else -> {
                _state.value = _state.value.copy(
                    lines = emptyList(),
                    startLine = 1,
                    eof = true,
                    error = null,
                    loading = true,
                )
                loadPage(1, append = false)
            }
        }
    }

    private fun loadDiff() {
        val seq = target.seq ?: return
        val index = target.index ?: return
        diffJob?.cancel()
        val generation = ++diffGeneration
        _state.value = _state.value.copy(diffLoading = true, diffMissing = false, diffError = null)
        diffJob = viewModelScope.launch {
            try {
                val diff = changes.diff(sessionId, seq, index)
                // 代数不等 = 这次结果已被 [open] 或新的 loadDiff 作废，丢弃。
                if (generation != diffGeneration) return@launch
                val missing = diff == null
                val entry: ChangesDiff? = diff
                _state.value = _state.value.copy(
                    diffLoading = false,
                    diff = entry,
                    diffMissing = missing,
                    diffError = null,
                )
            } catch (e: CancellationException) {
                if (generation == diffGeneration) {
                    _state.value = _state.value.copy(diffLoading = false)
                }
                throw e
            } catch (e: Exception) {
                if (generation != diffGeneration) return@launch
                // 只有 404（[ChangesRepository.diff] 折成 null）才是「记录没了」；
                // 走到这里的是网络/解析失败，必须如实说、并且给出重试（R005 P2）。
                _state.value = _state.value.copy(
                    diffLoading = false,
                    diff = null,
                    diffMissing = false,
                    diffError = describeFileFailure(e),
                )
            }
        }
    }

    private fun loadPage(offset: Int, append: Boolean) {
        bodyJob?.cancel()
        val generation = ++bodyGeneration
        _state.value = _state.value.copy(
            loading = !append,
            loadingMore = append,
            error = if (append) _state.value.error else null,
        )
        bodyJob = viewModelScope.launch {
            try {
                val page = files.read(sessionId, target.path, offset = offset)
                if (generation != bodyGeneration) return@launch
                val newLines = splitPageLines(page.text)
                // 进度校验（R005 P3）：服务端若回了一页空内容却说「还没到末尾」，
                // 续读会拿同样的 offset 反复请求同一页（屏上没变化，流量一直涨）。
                // 只要这一页没带来任何新行，就认定到头了。
                val reachedEnd = page.eof || newLines.isEmpty()
                _state.value = _state.value.copy(
                    lines = if (append) _state.value.lines + newLines else newLines,
                    // 分页时必须保留**第一页**的起点：接着读的页码按它算。
                    // 服务端回显的 `offset` 是本次请求的起始行，只有 append=false 时才是全文起点。
                    startLine = if (append) _state.value.startLine else page.offset,
                    eof = reachedEnd,
                    loading = false,
                    loadingMore = false,
                    error = null,
                    absolutePath = page.absolutePath,
                    bytes = page.bytes,
                )
            } catch (e: CancellationException) {
                if (generation == bodyGeneration) {
                    _state.value = _state.value.copy(loading = false, loadingMore = false)
                }
                throw e
            } catch (e: Exception) {
                if (generation != bodyGeneration) return@launch
                _state.value = _state.value.copy(
                    loading = false,
                    loadingMore = false,
                    error = describeFileFailure(e),
                )
            }
        }
    }

    /**
     * PDF 只看 `stat`（R005 P3）：不下载整块字节。
     *
     * [FileViewerScreen] 对 PDF 只渲染一行「暂不支持预览」，`stat` 给出的
     * `absolutePath` 与 `bytes` 已经够用；最大的 PDF 有 32 MiB，为了一行字把它拉下来
     * 既费流量又占内存。
     */
    private fun loadPdfStat() {
        bodyJob?.cancel()
        val generation = ++bodyGeneration
        _state.value = _state.value.copy(loading = true, error = null, media = null)
        bodyJob = viewModelScope.launch {
            try {
                val stat = files.stat(sessionId, target.path)
                if (generation != bodyGeneration) return@launch
                _state.value = _state.value.copy(
                    loading = false,
                    error = null,
                    absolutePath = stat.absolutePath,
                    bytes = stat.bytes,
                )
            } catch (e: CancellationException) {
                if (generation == bodyGeneration) {
                    _state.value = _state.value.copy(loading = false)
                }
                throw e
            } catch (e: Exception) {
                if (generation != bodyGeneration) return@launch
                _state.value = _state.value.copy(loading = false, error = describeFileFailure(e))
            }
        }
    }

    private fun loadMedia() {        // 与 [loadPage] 一致：重试时必须先取消在途的那次，否则两次整块读取会并发，
        // 后回来的那个覆盖先回来的（图片大时能到 32 MiB，白白拉两遍）（R005 P3）。
        bodyJob?.cancel()
        val generation = ++bodyGeneration
        _state.value = _state.value.copy(loading = true, error = null, media = null)
        bodyJob = viewModelScope.launch {
            try {
                val result: FileBytes = files.readBytes(sessionId, target.path)
                if (generation != bodyGeneration) return@launch
                val bytes = (result as FileBytes.Multipart).data
                // stat 只是为了让标题显示绝对路径与真实大小；它失败不影响看图。
                val stat = runCatching { files.stat(sessionId, target.path) }.getOrNull()
                if (generation != bodyGeneration) return@launch
                _state.value = _state.value.copy(
                    loading = false,
                    error = null,
                    media = bytes,
                    absolutePath = stat?.absolutePath ?: target.path,
                    bytes = stat?.bytes ?: bytes.size.toLong(),
                )
            } catch (e: CancellationException) {
                if (generation == bodyGeneration) {
                    _state.value = _state.value.copy(loading = false)
                }
                throw e
            } catch (e: Exception) {
                if (generation != bodyGeneration) return@launch
                _state.value = _state.value.copy(loading = false, error = describeFileFailure(e))
            }
        }
    }

    override fun onCleared() {
        bodyJob?.cancel()
        diffJob?.cancel()
        super.onCleared()
    }

    companion object {
        fun factory(
            connection: DshConnection,
            sessionId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { FileViewerViewModel(connection, sessionId) }
        }
    }
}

/**
 * 把 `workspaceFiles` 的 read 回的一页文本切成行。
 *
 * 结尾的换行**不**产生一个额外的空行——否则每翻一页都会多出一个空行，而它并不是
 * 文件里真实存在的一行。抽成顶层 internal 函数是为了能脱离网络单测：分页的行数算错
 * 会让「加载更多」永远取不到下一页（或跳过一行），而且很难在 UI 上看出来。
 */
internal fun splitPageLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val parts = text.split('\n')
    return if (text.endsWith('\n')) parts.dropLast(1) else parts
}

/**
 * 把异常翻成中文提示；文件相关错误码用 [WorkspaceFileError.describe]。
 *
 * 两个必须处理的细节（R005 审查发现，均有回归测试）：
 *  - **要沿 `cause` 链找 [TypertException]**：调用方常把协议错误包一层再抛
 *    （例如 `RuntimeException(cause = TypertException(...))`），只看最外层会丢掉
 *    `workspace-file/not-found` 这类真正的成因，把中文文案降级成一句英文 `message`。
 *  - **空 `message` 要兜底**：`message ?: 类名` 只在 null 时生效，`message == ""` 会
 *    返回空串，UI 上就是一条没有文字的报错。
 */
internal fun describeFileFailure(cause: Throwable): String {
    var current: Throwable? = cause
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        val typert = current as? TypertException
        if (typert != null) {
            val known = WorkspaceFileError.describe(typert.code)
            return if (known == typert.code) "${typert.code}：${typert.message}" else known
        }
        current = current.cause?.takeIf { it !== current }
        depth++
    }
    return cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName
}

/** `cause` 链的深度上限，防止畸形异常形成环。 */
private const val MAX_CAUSE_DEPTH = 16
