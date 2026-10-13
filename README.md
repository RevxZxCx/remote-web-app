# DSH Remote Web App

DSH（DeepSeek Harness）的**原生 Android 客户端**。直接以 Typert RPC 协议对接 DSH，不再把桌面 Web UI 塞进手机浏览器。

---

## 为什么做这个

现有的手机操控方案（`@linxin666/dsh-remote-web-ui`）把官方**桌面 SPA** 搬到手机上，靠 `mobile-adapt.ts` 事后注入 CSS 做适配。后果是：

- 每次打开网页都要重新下载/加载插件 —— 相当于重启，加载时间很长
- 还要渲染整套桌面 UI，交互性差
- 注入的规则 `[class$="_frame"][data-sidebar-collapsed]{grid-template-columns:0 ...}` 把会话侧栏压成 0 宽，手机上根本没有会话选择器

本项目改用原生 Compose UI，只发协议请求，**不加载任何官方前端资源**。

---

## 当前状态

**M0 · 项目初始化**（进行中）—— 需求文档与原型已获用户批准，正在搭建工程骨架。

进度详情见 **[docs/dev/开发进度.md](docs/dev/开发进度.md)**。

---

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [docs/需求文档.md](docs/需求文档.md) | 需求、已核实的接口契约、功能需求 FR-1～FR-5、里程碑、风险 |
| [docs/原型.html](docs/原型.html) | 五屏可点击原型（配对 / 工作区 / 聊天 / 文件查看 / 设置） |
| [docs/dev/开发进度.md](docs/dev/开发进度.md) | **恢复上下文的第一入口**：当前阶段、已完成、待办、已排除方案 |
| [docs/dev/开发流程.md](docs/dev/开发流程.md) | 强制流程：计划→实现→自测→记录→审查→修复→提交 |
| [docs/dev/决策记录.md](docs/dev/决策记录.md) | ADR：D01–D17 已拍板决策及理由 |
| [docs/dev/测试记录.md](docs/dev/测试记录.md) | 每次测试的命令、结果、失败分析 |
| [docs/dev/代码审查记录.md](docs/dev/代码审查记录.md) | 每次审查的范围、发现、结论 |
| [docs/dev/环境与构建.md](docs/dev/环境与构建.md) | SDK / JDK / 代理 / 构建命令等环境事实 |

---

## 技术栈

Kotlin · Jetpack Compose · OkHttp · kotlinx.serialization · Room · Coroutines/Flow

`minSdk 26`（Android 8.0）· `targetSdk 36` · `compileSdk 36`

---

## 协议速览

DSH 的 API 是 **Typert RPC**，不是 REST。

```
unary:  POST /remote/api/<namespace>/<method>
        header  x-dsh-remote-device: <deviceId>
        body    {"type":"client-request","rpcId":"<uuid v4>","method":"<ns>/<method>","payload":{"args":{...}}}

stream: wss://<host>/remote/api/remote.mux?device=<deviceId>
        ↑ 一条 WebSocket 多路复用所有流（session/follow、session/control、workspace/follow）

resp:   {"type":"server-response","rpcId":"...","result":{"ok":true,"value":...}}
        {"type":"server-response","rpcId":"...","result":{"ok":false,"error":{"code":...,"message":...,"details":{...}}}}
```

配对只能在**桌面端**发起（`POST /api/pair/issue` 是 loopback-only），App 侧扫码或粘贴链接完成 `POST /api/pair/accept`。

---

## 构建

```powershell
$env:JAVA_HOME='E:\JDK 21'
$env:ANDROID_HOME='F:\Android\Sdk'

.\gradlew.bat :app:assembleDebug
```

详见 [docs/dev/环境与构建.md](docs/dev/环境与构建.md)。

---

## 许可

私有项目。
