package com.revxzxcx.remotewebapp.core.connection

import com.revxzxcx.remotewebapp.core.net.HostPolicy
import com.revxzxcx.remotewebapp.core.pairing.PairLinkParser
import com.revxzxcx.remotewebapp.core.pairing.PairingApi
import com.revxzxcx.remotewebapp.core.pairing.PairStatus
import com.revxzxcx.remotewebapp.core.protocol.DshEndpoints
import com.revxzxcx.remotewebapp.core.protocol.TypertClient
import com.revxzxcx.remotewebapp.core.protocol.TypertException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

/**
 * 一次「测试连接」的结果。
 *
 * R054 的探针只能回答「地址通不通」——那对**非激活主机**是对的（它们没有 DshConnection），
 * 但它会把「凭证早已被桌面端撤销」谎报成「连得上」，用户切过去才吃 403。
 *
 * M57 起探针给四种结论：`Ok`（通）、`Unauthorized`（凭证失效）、
 * `Unreachable`（没通，或者通了但响应不对）、`Rejected`（本地就拒了，一个请求都没发）。
 * 「网络」与「凭证」是两件事，必须让用户看见区别。
 *
 * 前三者是一次真实请求的三种结果；`Rejected` 不是——它在请求发出**之前**就被本地挡掉了
 * （地址格式错 / HostPolicy 不允许），拿不到任何网络结论。这一态必须独立存在，否则
 * 「地址格式错」会被显示成「连不上」，用户就跑去查网络、查桌面端开没开，排查方向从
 * 一开始就是错的。
 */
sealed interface HostProbe {

    /** 地址可达、且这台桌面端仍然接受我们的凭证。 */
    data object Ok : HostProbe

    /**
     * 地址可达，但鉴权被拒。
     *
     * [detail] 带上服务端码（`unpaired` / `unauthorized`），便于诊断页与日志区分
     * 「配对被撤销」与「harness 围栏拦了」。
     */
    data class Unauthorized(val detail: String) : HostProbe

    /**
     * 探测失败，但不是凭证问题。
     *
     * [detail] 是给诊断用的技术原因（含服务端码）；[reachable] 表示 TCP/HTTP 层是否到达。
     * 这两种故障对用户意味着完全不同的下一步，所以**必须**分别措辞：
     * - 没到达 → 查地址、查网络、查桌面端有没有开；
     * - 到达了但响应不对 → 网络没问题，是桌面端版本或协议不匹配。
     *
     * 两种情形往 [detail] 里装的东西**不一样**，构造点别再往里塞 UI 措辞：
     * - `reachable = true` → **裸错误码**（如 `bad-envelope`）。面向用户的「桌面端响应异常」
     *   前缀由 [message] 加，构造点只给码。早期写法在构造点先拼了一遍前缀，[message] 又
     *   套一层，用户看到的是「桌面端响应异常（桌面端响应异常（bad-envelope））」，括号还嵌套。
     * - `reachable = false` → 人类可读短句（如「连不上，请检查地址与网络」）。传输层失败没有
     *   服务端码可给，这一支是结论本身；[message] 对该分支用固定文案、**不**引用 [detail]，
     *   所以不存在重复拼接，[detail] 仅留给日志与诊断读取。
     *
     * （M57 复审 P2：这个字段最初只有写入、没有任何读取，`message()` 对两种情形给出同一句
     * 话，等于 [Unreachable] 又塌回单一结论，正是本次改动想消除的那类误导。）
     */
    data class Unreachable(val detail: String, val reachable: Boolean) : HostProbe

    /** 地址本身在本地就被拒了（格式错 / HostPolicy 不允许）。没发过任何请求。 */
    data class Rejected(val detail: String) : HostProbe

    /**
     * 面向用户的完整提示。
     *
     * 文案集中在这里而不是散在 UI 里：四条文案必须互相可比（都说「主机名 + 结论」），
     * 且「凭证失效」这条要明确指向下一步动作（重新配对），否则用户会去查网络。
     */
    fun message(hostName: String): String = when (this) {
        is Ok -> "$hostName 正常"
        is Unauthorized -> "$hostName 的凭证已失效，需要重新配对"
        // 「没连上」与「连上了但对面不像 DSH」的排查方向相反，不能共用一句话。
        is Unreachable -> if (reachable) {
            "$hostName 已连上，但桌面端响应异常（$detail）"
        } else {
            "$hostName 连不上，请检查地址与网络"
        }
        is Rejected -> "$hostName $detail"
    }
}

/**
 * 「测试连接」的真实实现（M57）。
 *
 * ## 为什么不能复用 [DshConnection]
 *
 * 非激活主机没有连接实例，为了探一次活去建一条连接代价过大：[DshConnection] 会连带构造
 * `RemoteStreamMux` 与 `RemoteEventsClient`，那是**一条长驻 WebSocket** 的所有权。
 * 探测应该是「问一句、挂断」。
 *
 * ## 为什么是 `session/modelCatalog`
 *
 * 它**无参数**、**只读**、**响应小**，且必须带设备头才能通过：
 * - 无参数 → `payload.args` 是空对象，不会踩 `assertExactArguments`；
 * - 只读 → 探测本身绝不改变桌面端状态，用户点几次都不会留下副作用；
 * - 必须鉴权 → 凭证失效时它必定回 `unpaired` / `unauthorized`，正是要区分的信号。
 *
 * 反例：`GET /api/pair/status` 是**公开探针**，不携带凭证也能 200，因此永远无法
 * 回答「凭证还有效吗」——这正是 R054 留下那条缺口的根因。
 *
 * ## 不用构造 DshConnection 的旁证
 *
 * `RemoteEventsClient.start()` 是显式的（唯一调用点在 `AppContainer.installConnection`，
 * 建好 [DshConnection] 之后立刻调），[DshConnection] 的构造不会自动订阅；但 [TypertClient]
 * 更彻底——它只吃 baseUrl/deviceId/http/json，没有任何生命周期。探测只需要它。
 *
 * 这里刻意**不写行号**：原先是 `AppContainer.kt:364`，后来 `installConnection` 因缓存维护
 * 与复审注释的增删挪到了 375。这类行号不会有人在无关改动时回头核对，读者照着跳到错的
 * 地方，比没有引用更糟。锚在方法名上，方法内部怎么增删都不影响引用是否成立。
 */
class HostProbeClient(
    private val http: OkHttpClient,
    private val json: Json,
) {

    /**
     * 探一次 [baseUrl] + [deviceId] 这台主机。
     *
     * 本方法**不抛异常**：所有失败都折进 [HostProbe] 的四种状态里，
     * 因为它的调用方是 UI 的一句 snakbar，不该再写一层 try/catch。
     */
    suspend fun probe(baseUrl: String, deviceId: String): HostProbe = withContext(Dispatchers.IO) {
        val normalized = PairLinkParser.normalizeBaseUrl(baseUrl)
            ?: return@withContext HostProbe.Rejected("地址格式不对")
        HostPolicy.rejectionReason(normalized)?.let { return@withContext HostProbe.Rejected(it) }

        val typert = TypertClient(normalized, deviceId, http, json)
        try {
            typert.callRaw(DshEndpoints.SESSION_MODEL_CATALOG, EMPTY_ARGS)
            HostProbe.Ok
        } catch (e: TypertException) {
            when {
                // 凭证被桌面端撤销 / harness 围栏拦截：地址明明是通的。
                e.isUnpaired -> HostProbe.Unauthorized(e.code)

                // 传输层失败：DNS、连接被拒、超时——连服务端都没摸到。
                e.code == TypertException.CODE_TRANSPORT ->
                    HostProbe.Unreachable("连不上，请检查地址与网络", reachable = false)

                /*
                 * `http-401` / `http-403`：HTTP 层回了拒绝，但**没有**可解析的错误信封，
                 * 于是 `decodeHttpFailure` 只能按状态码造码。
                 *
                 * 这一支必须算「鉴权失败」而不是「响应异常」，但理由要说准——这里原先写的
                 * 「设备头不对时会直接回纯文本 403」是**实测不成立**的：
                 *
                 * - 设备头缺失 / 为空 / 伪造 / 非 hex / 凭证已撤销，桌面端一律回
                 *   `403` + `{"error":{"code":"unpaired",…}}` 的 **JSON 信封**，
                 *   走的是上面的 `e.isUnpaired` 分支，**根本到不了这里**；
                 * - 纯文本 `401`/`403` 只在**路径不对**时出现（例如前缀写成 `/api/pair`，
                 *   围栏先回 `401 unauthorized`），那是「地址填错」而不是「设备头不对」。
                 *
                 * 所以这一支真正兜住的是「地址打到了某个会回裸 401/403 的地方」——可能填错
                 * 了路径，也可能是中间设备（反向代理 / WAF）拦了。仍算鉴权问题：用户侧唯一
                 * 可行动作就是回去核对地址或重新配对，报成「桌面端响应异常」只会把人引去
                 * 查桌面端版本。
                 *
                 * 实测矩阵与反例见 `app/src/test/…/core/integration/LiveHostProbeTest.kt`
                 * （有一条用例专门钉住「设备头错误走 unpaired 信封而不是纯文本」）。
                 */
                e.code == "http-401" || e.code == "http-403" || e.code == "forbidden" ->
                    HostProbe.Unauthorized(e.code)

                // 信封非法 / rpcId 不匹配 / 服务端 5xx：HTTP 通了，但对面回的不是这个协议。
                // 这种主机「连得上但没法用」，报成凭据之外的故障更诚实。
                // detail 只给裸码：这里是诊断字段，面向用户的「桌面端响应异常（…）」由
                // HostProbe.message() 统一加前缀；构造点再拼一遍就会重复嵌套。
                else -> HostProbe.Unreachable(e.code, reachable = true)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消必须原样上抛，**不能**折成 Unreachable。
            //
            // 用户在这 30 秒读超时窗口内切走这一屏时，`rememberCoroutineScope` 会取消协程；
            // 而 `CancellationException` 是 `Exception` 的子类，被下面的兜底分支接住就会变成
            // 一句「连不上，请检查地址与网络」的假警报——用户已经离开，snackbar 却还在弹。
            // 更根本的问题是它会破坏结构化并发：被取消的协程本该安静结束。
            // 项目里 `DshCache.guard` 已经立了这条规矩（取消原样上抛），此处与之一致。
            throw e
        } catch (e: Exception) {
            // 兜底：OkHttp 抛的 IOException 已被 TypertClient 折成 CODE_TRANSPORT，
            // 走到这里说明是意料之外的东西，同样不该让 UI 崩。
            HostProbe.Unreachable("连不上，请检查地址与网络", reachable = false)
        }
    }

    /**
     * R054 的老探针：只回答「地址通不通」，不带凭证。
     *
     * 与 [probe] 的区别：这里**故意不带凭证**，所以拿不到任何鉴权结论，必然无法区分
     * 「凭证失效」与「通」——这正是 [HostProbe] 分四态要消除的那类含糊，别把两者混用。
     *
     * 配对流程**不经过这里**：`PairViewModel` 用的是它自己的 `PairingApi.status`。
     * 本方法目前**没有任何调用点**，保留它只是留一个「不带凭证的可达性探测」入口；
     * 读这段代码时别假设它有现役调用方。
     */
    suspend fun probeReachability(baseUrl: String): PairStatus? = withContext(Dispatchers.IO) {
        val normalized = PairLinkParser.normalizeBaseUrl(baseUrl) ?: return@withContext null
        if (HostPolicy.rejectionReason(normalized) != null) return@withContext null
        runCatching { PairingApi(http).status(normalized) }.getOrNull()
    }

    private companion object {
        /** 无参数端点的空 `payload.args`。 */
        val EMPTY_ARGS: JsonObject = JsonObject(emptyMap())
    }
}
