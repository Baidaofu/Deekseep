# Deekseep · 开源 Local API 复刻版

> **English abstract.** This is a fork of the open-source `lllucccian/Deekseep`
> (1.7.4-fix Open) LSPosed module for the official DeepSeek Android app. Its
> defining difference from upstream: the **Local API** — an OpenAI- and
> Anthropic-compatible HTTP(S) server that runs inside the DeepSeek process — is
> here implemented **fully in the open**, as a clean-room rewrite from the public
> protocol specs. The closed edition ships that server as an encrypted,
> dynamically loaded payload; we did not decrypt or copy it. Runtime-attestation
> and plugin-sdk components of the closed build are intentionally not reproduced.

Deekseep 是一个面向官方 **DeepSeek Android App**（`com.deepseek.chat`）的独立
LSPosed / Xposed 模块。它运行在 DeepSeek 进程内，在不修改宿主安装包的前提下，为宿主
追加一系列增强功能。

本仓库相对上游开源版（`lllucccian/Deekseep` 1.7.4-fix Open）的**关键增量**是：
闭源版把 **Local API**（在手机上提供 OpenAI / Anthropic 兼容接口的本地服务端）封装进
一个加密的运行时载荷里，而本仓库基于公开的 OpenAI / Anthropic 协议规范，
**独立、净室地重写了整套 Local API**，并接上了宿主原生后端 `Main.NativeBridge`。
任何支持自定义 `base_url` 的客户端（OpenAI SDK、Cherry Studio、NextChat、Claude 客户端等）
都能把你的手机直接当作推理端点。

> ⚠️ **本项目与 DeepSeek 官方无关**，也不是上游作者的发布仓库。它用于研究与个人自用，
> 实验性设置可能影响宿主稳定性，请自担风险。

---

## 本分支与上游的关系（诚实说明）

| 维度 | 上游开源版 (1.7.4-fix Open) | 上游闭源版 (1.7.5) | **本仓库 (Baidaofu/Deekseep)** |
| --- | --- | --- | --- |
| Local API 服务端 | ❌ 不含 | ✅ 加密载荷，运行时解密加载 | ✅ **明文开源实现**（`localapi/` 17 个类） |
| Local API 后端驱动 | — | 复用宿主会话与网络栈 | ✅ `Main.NativeBridge`：临时会话 + 宿主 PoW + 宿主 Flow 流式 |
| 其余模块功能内核 | ✅ | ✅ | ✅（与开源版同步） |
| 运行时证明 / 授权组件 `RuntimeProof*` | ❌ | ✅ | ❌ **刻意不实现** |
| 插件 SDK `JavaPluginApi` | ❌ | ✅ | ❌ **刻意不实现** |

**为什么刻意不做后两者**：`RuntimeProof` 是闭源分发的防篡改 / 授权体系，服务于闭源分发
本身；`JavaPluginApi` 是独立的插件生态，与 Local API 无依赖关系。开源复刻不需要它们，
实现它们只会给自由分发增加无谓障碍。

**一处与默认行为有关的刻意差异**：闭源版 Local API 默认监听 `0.0.0.0:8765`（局域网内任意
设备可达）。本仓库**默认关闭** Local API，且开启后**默认只绑回环地址 `127.0.0.1`**——
把别人的大模型会话暴露到局域网是后果不对称的事，这个开关应由使用者主动、明确地打开。
需要局域网或公网访问时，在设置页显式开启「允许局域网访问」再自行配置。

完整的差距与证据记录见 [docs/LOCAL_API_GAP.md](docs/LOCAL_API_GAP.md)。

---

## 功能总览

### 模块功能内核（来自上游开源版，本仓库同步）
按宿主内「Deekseep」面板的分类组织：

- **聊天增强**：聊天外观（字体、气泡、配色）、聊天编辑器、聊天搜索、备份与恢复、深度思考 / 回复就绪策略。
- **账号与隐私**：多账号管理、Google / 微信登录解锁、凭据编解码。
- **调试与工程**：请求调试、缓存清理、崩溃诊断、特性开关（RemoteFeatureFlags）、进程管理、原生 API 补丁解析。
- **Agent**：Agent 设置、运行记录、工具配置、自动续答策略、设备桥接。
- **通知 / 外观 / 动效**：通知图标、液态玻璃、空间视差、文字波形等视觉与交互增强。
- **保活与后台**：前台保活服务、心跳、看门狗。

### Local API（本仓库核心增量）
在 DeepSeek 进程内起一个 HTTP(S) 服务，对外提供 **OpenAI 兼容** 与 **Anthropic 兼容**
两种接口。要点：

- **协议层**：OpenAI 模式 `/v1/models`、`/v1/chat/completions`、`/v1/responses`；
  Anthropic 模式 `/v1/messages`、`/v1/messages/count_tokens`。均支持 JSON 与 SSE 流式。
  两种模式互斥（配错协议返回 `404 protocol_mismatch`）。
- **后端**：不走匿名 HTTP，而是复用宿主自己的传输——开一个临时会话、用宿主的 PoW 与签名
  把它当成一次普通对话发出去，再从宿主 UI 消费的同一个 Flow 里读回增量。对服务端而言与
  普通一轮对话无异，模块也无需重新实现每版都变的签名与实验开关。
- **鉴权**：`Authorization: Bearer <key>`，缺失或错误返回 `401`。API Key 首次自动生成
  64 位十六进制，支持轮换与自定义（定长时间比较防计时侧信道）。**即使是回环请求也必须带 Key。**
- **HTTPS**：可选开启，首次生成每设备唯一自签 CA，SAN 含 `127.0.0.1` / `localhost` / 当前
  LAN IP；换网络自动重签。证书由手写极简 `Der` 写入器构造，不引入 BouncyCastle 等第三方依赖。
- **后台保活**：前台服务 + `PARTIAL_WAKE_LOCK` + 心跳 + 看门狗 + 自动恢复。
- **统计与日志**：请求数 / 成功率 / 延迟、滚动日志 `deekseep_api.log`。
- **公网入口**：提供隧道状态机与校验（Pinggy SSH / Cloudflare / 手动端口转发），抽象层已就位，
  具体连接器需自行接入。
- **设置界面**：`LocalApiUi` 作为**宿主进程内的全屏 Dialog** 存在（设置须写入宿主私有目录才有意义），
  位于「DeepSeek → 设置 → Deekseep → 工程 → 本地 API · 实验性」。

**已实现 vs 已知限制**（详见 [docs/LOCAL_API_GAP.md](docs/LOCAL_API_GAP.md) 第 3 节）：

| 能力 | 状态 |
| --- | --- |
| HTTP(S) 监听 + 鉴权、OpenAI / Anthropic 协议、每设备 CA + HTTPS、后台保活、统计日志、模型路由与自定义模型 | ✅ 已实现 |
| 驱动宿主的后端（NativeBridge） | ✅ 真机端到端打通（Android 16 / DeepSeek 2.3.6） |
| 公网隧道连接器、Agent 工具执行器、图片 / 文件上传 | ⚠️ 抽象 / 契约已就位，执行器或宿主对接待补 |
| 运行时证明授权体系、插件 SDK | ❌ 刻意不实现 |

---

## Local API 快速开始

入口在 **DeepSeek 自己的设置里**，不在模块 APK 的独立界面：

1. 在 LSPosed / Xposed 中启用 **Deekseep**，作用域只勾选 `com.deepseek.chat`。
2. 打开 DeepSeek → 侧边栏 → 底部 **⋯** → **设置** → 右上角 **Deekseep** →
   **工程** → **本地 API · 实验性**。
3. 打开 **启用本地 API**。
4. **要连接本机之外，再打开「允许局域网访问」。** 默认只监听 `127.0.0.1`，
   只有手机自身（及 `adb forward`）能连上。
5. 点 **复制连接信息**，把地址与密钥填进客户端：
   - OpenAI 客户端：`base_url = http://<地址>:8765/v1`
   - Anthropic 客户端：`base_url = http://<地址>:8765`（**无** `/v1` 后缀）

设置页与接口细节、HTTPS 信任方式、保活机制、真机联调记录见
[docs/LOCAL_API.md](docs/LOCAL_API.md)。

---

## 工程结构（代码地图）

```
module/src/com/dsmod/probe/
├── Main.java                 Xposed 入口、宿主注入、NativeBridge 后端挂载
├── DeekseepUi.java           宿主内模块面板（分类页、功能搜索）
├── LocalApiUi.java           ★ 本地 API 设置页（宿主进程内 Dialog）
├── Agent*/Chat*/Account*/…   模块各功能分类的实现与 UI
└── localapi/                 ★ 本地 API 净室实现
    ├── ApiContract.java       契约：请求 / 结果 / 增量 / 错误
    ├── LocalApi.java          门面：start / stop / status
    ├── LocalApiServer.java    监听、鉴权、路由、TLS 接入
    ├── LocalApiConfig.java    全部设置与持久化
    ├── LocalApiStats.java     统计与滚动日志
    ├── OpenAiRouter.java      /v1/models、/v1/chat/completions、/v1/responses
    ├── AnthropicRouter.java   /v1/messages、/v1/messages/count_tokens
    ├── ModelCatalog.java      模型 → 宿主角色映射
    ├── HostBackend.java       请求编排：队列、超时、并发许可、会话生命周期
    ├── ReflectiveBridge.java  通过 HostCompat 驱动宿主网络栈（兜底）
    ├── HttpServer.java / HttpExchange.java / SseReader.java   轻量 HTTP/SSE 栈
    ├── TlsDirector.java / Der.java   每设备 CA、证书签发、导出（无第三方依赖）
    ├── KeepAliveService.java  前台保活服务
    └── PublicTunnel.java      公网入口状态机与校验
```

`LocalApiUi` 必须留在 `com.dsmod.probe` 包，因为 `DeekseepUi` / `UiLanguage` 等是包内可见。
改端口、HTTPS、协议、绑定地址后页面会**就地重启监听**，无需退出 DeepSeek。

---

## 构建

要求：JDK 17、Bash、Android SDK Platform 35+、Android Build Tools
（`aapt2` / `d8` / `zipalign` / `apksigner`）、`zip`、`curl`。

设置 `ANDROID_SDK_ROOT` 或 `ANDROID_HOME` 后：

```bash
bash scripts/build-all.sh
```

只构建一个渠道：

```bash
(cd module-universal && bash build.sh)
(cd module-universal && GOOGLE_PLAY_BUILD=true bash build.sh)
```

脚本会在需要时生成本地开发签名密钥（**不要**将其用作发布身份）。构建产物与密钥已被 Git 忽略。
通用入口声明 Xposed API 82 为最低、无上限；兼容回归覆盖 API 82–102。详见
[docs/BUILDING.md](docs/BUILDING.md)。

---

## 环境要求

- Android 7.0 或更高（API 24+）。
- 官方 DeepSeek 包名 `com.deepseek.chat`。
- 适配：国内版 DeepSeek 2.3.6（versionCode 249）、2.3.4（245/246）、2.3.0（237）、2.2.x。
  2.3.1–2.3.3 与 2.3.5 不支持。
- 能加载传统 Xposed 入口的 LSPosed / Xposed（覆盖 API 82–102）。
- Root，或你的 LSPosed / Xposed 环境所要求的权限。
- 如需后台请求或通知，建议取消 DeepSeek 的电池优化限制。

---

## 安全与隐私提示

- **局域网暴露需主动开启**：默认只绑 `127.0.0.1`；开启「允许局域网访问」后绑定 `0.0.0.0`，
  请仅在可信网络下使用，并务必保留强 API Key。
- **每个请求都必须带 API Key**，包括来自本机的请求。
- **HTTPS 证书**：建议在客户端「固定证书」而非修改系统信任；设置页会显示 CA 的 SHA-256
  指纹，便于核对信任的确实是本机那张证书。
- 实验性设置可能影响宿主稳定性；如遇异常，先关闭模块并以备份恢复。

---

## 许可证与免责

- 以 [GPL-3.0-only](LICENSE) 许可证发布。
- 本项目不是 DeepSeek 官方项目，与 DeepSeek 官方无隶属关系。
- 本仓库不包含官方 DeepSeek APK、Root 方案或 LSPosed / Xposed 安装器。
- Local API 的开源实现**未解密、未复制**任何闭源载荷，依据 OpenAI / Anthropic 公开协议规范
  及外部可观察行为独立编写。使用本模块产生的任何后果由使用者自行承担。

---

问题反馈与复现步骤：在项目 Issue 提交。源码构建、安装与排障分别见
[docs/BUILDING.md](docs/BUILDING.md)、[docs/INSTALLATION.md](docs/INSTALLATION.md)、
[docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)。
