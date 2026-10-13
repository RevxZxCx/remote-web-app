package com.revxzxcx.remotewebapp.core.protocol

/**
 * 服务端 RPC 端点与**参数 wire 名**的权威清单。
 *
 * 为什么必须集中登记：Typert 的调用参数不是「一个 request 对象」，而是按每个参数的 `wire` 名
 * 平铺进 `payload.args`。名字不对服务端直接回
 * `gateway/arguments-invalid: args fields do not match the descriptor: missing "x"; unexpected "y"`
 * （`dsh-api-gateway/lib/index.js:1497-1508` `assertExactArguments`）。
 *
 * 而 `wire` 名**不是统一规则**：多数方法叫 `request`，但 `session/list` 叫 `_request`
 * （源码签名是 `async list(_request: SessionListRequest, signal)`），`workspaceFiles` 系列是
 * `workspaceFileScopeId` + `path`（第一个是 Typert lookup，不是普通值）。
 *
 * 本文件里的名字全部由 M1 真机探针向运行中的实例逐个确认（见 `测试记录.md` T004），
 * 不是从类型声明猜的——类型声明里的字段名与 wire 名可以不同。
 */
object DshEndpoints {

    // ---------------------------------------------------------------- session

    const val SESSION_LIST = "session/list"
    const val SESSION_CREATE = "session/create"
    const val SESSION_SEARCH = "session/search"
    const val SESSION_PAGE = "session/page"
    const val SESSION_FOLLOW = "session/follow"
    const val SESSION_CONTROL = "session/control"
    const val SESSION_PROMPT = "session/prompt"
    const val SESSION_CANCEL = "session/cancel"
    const val SESSION_RENAME = "session/rename"
    const val SESSION_FORK = "session/fork"
    const val SESSION_SELECT_MODEL = "session/selectModel"
    const val SESSION_MODEL_CATALOG = "session/modelCatalog"
    const val SESSION_INITIALIZE_DEFAULT_MODEL = "session/initializeDefaultModel"
    const val SESSION_UPDATE_QUEUE = "session/updateQueue"
    const val SESSION_ATTACHMENT = "session/attachment"
    const val SESSION_PROJECTIONS = "session/projections"
    const val SESSION_OPEN_WORKSPACE_PATH = "session/openWorkspacePath"
    const val SESSION_WORKSPACE_PATH_APPLICATIONS = "session/workspacePathApplications"
    const val SESSION_CAN_OPEN_WORKSPACE_PATH = "session/canOpenWorkspacePath"

    // -------------------------------------------------------------- workspace

    const val WORKSPACE_CREATE = "workspace/create"
    const val WORKSPACE_RENAME = "workspace/rename"
    const val WORKSPACE_DELETE = "workspace/delete"
    const val WORKSPACE_INSERT_BEFORE = "workspace/insertBefore"
    const val WORKSPACE_INSERT_SESSION_BEFORE = "workspace/insertSessionBefore"
    const val WORKSPACE_ARCHIVE_SESSION = "workspace/archiveSession"
    const val WORKSPACE_UNARCHIVE_SESSION = "workspace/unarchiveSession"
    const val WORKSPACE_PIN_SESSION = "workspace/pinSession"
    const val WORKSPACE_UNPIN_SESSION = "workspace/unpinSession"
    const val WORKSPACE_INITIALIZE_DEFAULT = "workspace/initializeDefault"
    const val WORKSPACE_FOLLOW = "workspace/follow"

    // -------------------------------------------------------- directoryPicker

    const val DIRECTORY_PICKER_LIST = "directoryPicker/list"
    const val DIRECTORY_PICKER_PICK = "directoryPicker/pick"
    const val DIRECTORY_PICKER_CREATE_DIRECTORY = "directoryPicker/createDirectory"

    // --------------------------------------------------------- workspaceFiles

    const val WORKSPACE_FILES_READ = "workspaceFiles/read"
    const val WORKSPACE_FILES_READ_BYTES = "workspaceFiles/readBytes"
    const val WORKSPACE_FILES_STAT = "workspaceFiles/stat"
    const val WORKSPACE_FILES_LIST = "workspaceFiles/list"
    const val WORKSPACE_FILES_CHANGES = "workspaceFiles/changes"

    // ------------------------------------------------------------------ 参数名

    /**
     * 默认的单请求参数名。
     *
     * 除 `session/list` 外的 session 系列与全部 workspace 系列都用它（真机探针逐条确认）。
     */
    const val ARG_REQUEST = "request"

    /**
     * `session/list` 专用——它是**唯一**用下划线前缀的方法。
     *
     * 漏掉这一点会让会话列表永远报 `missing "_request"`。
     */
    const val ARG_REQUEST_UNDERSCORE = "_request"

    /** workspaceFiles 系列的第一个参数：传 **sessionId**，服务端据此推导 workspace 根。 */
    const val ARG_FILE_SCOPE = "workspaceFileScopeId"

    const val ARG_PATH = "path"

    const val ARG_NAME = "name"

    /** `workspaceFiles/read` 的分页参数（`{offset?, limit?}`）。 */
    const val ARG_RANGE = "range"

    /** `workspaceFiles/readBytes` 的选项（`{range?, baseFile?}`）。 */
    const val ARG_OPTIONS = "options"

    /** 流式端点走 mux，不拼 URL；这里只是给日志/调试用。 */
    fun isStream(endpoint: String): Boolean = endpoint in STREAM_ENDPOINTS

    private val STREAM_ENDPOINTS: Set<String> = setOf(
        SESSION_FOLLOW,
        SESSION_CONTROL,
        WORKSPACE_FOLLOW,
        WORKSPACE_FILES_CHANGES,
    )
}
