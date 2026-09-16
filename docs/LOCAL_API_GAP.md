# 开源版 (1.7.4) 与闭源版 (1.7.5) 差距分析

本文件记录对 `Deekseep_1.7.5.apk`（闭源版）与仓库 `module/`（1.7.4 Fix Open）的实际对比结果。
结论基于对 APK 的静态逆向：jadx 反编译 `classes.dex`、解析 `AndroidManifest.xml` 与
`resources.arsc`、以及资源熵值分析。

**方法说明（重要）**：闭源版把 Local API 的服务端主体放进了 `META-INF/com.dsmod.cloud/1d29ef9ca6318f0c`
——一个 251 KB、熵值 7.999（即强加密）的动态加载载荷。本文**没有**去解密或提取那份载荷，
也没有复制任何反编译得到的闭源代码。所有结论都来自外部可观察行为（UI 文案、Manifest 组件、
类名与常量、文件落盘路径）以及协议层公开标准；下文"开源复刻"部分是据此做的**净室重写**。

---

## 1. 结论速览

| 维度 | 开源版 1.7.4 | 闭源版 1.7.5 |
|---|---|---|
| 功能内核（聊天/账号/外观/调试/工程/Agent/备份/通知/进程/特性开关） | 有 | 有（R8 混淆，同名类仍在 dex 中） |
| **Local API（OpenAI / Anthropic 兼容服务端）** | **1.7.4-fix Open 起已自带开源实现**（`localapi/` 17 个类） | 有 |
| Local API 主体代码位置 | `module/src/com/dsmod/probe/localapi/`，明文可读 | 加密载荷 `META-INF/com.dsmod.cloud/*`，运行时由 `II1O1_O` 解密并 `DexClassLoader` 加载为 `com.dsmod.probe.z1` |
| Local API 后端（驱动宿主） | ✅ `Main.NativeBridge` 走宿主原生会话：临时会话 + 宿主 PoW + 宿主 Flow | 有（复用宿主网络栈，带会话凭证） |
| HTTPS（每设备 CA） | 有（`TlsDirector` + 手写 `Der`，无第三方依赖） | 有（`z5`，内部代号 `dq0`） |
| 后台保活前台服务 | 有（`KeepAliveService`） | 有（`z21`，通知渠道 `dq0`） |
| 公网入口（Pinggy SSH / Cloudflare） | 抽象 + 校验层（`PublicTunnel`），未内置连接器 | 有（经 `com.dsmod.probe.XposedService` ContentProvider 调用） |
| 运行时证明 / 授权组件 | 无（刻意不做：它服务于闭源分发本身） | 有（`RuntimeProof*` 两组 Provider + Service） |
| 插件 API | 无（刻意不做：与 Local API 无关的另一维度） | 有（`JavaPluginApi`，ABI 3） |
| 免 Xposed 被动注入 | 无 | 有（`PassiveInjectionEntry`） |
| 第三方库 | 无（纯 Android，`Der` 手写 DER 以避免引 BouncyCastle） | 打包了 JSch（SSH）等 |

一句话：**除 Local API 外，其余功能两版一致。** Local API 的协议层与后端都已开源复刻并在
真机跑通（真实模型回复、流式逐帧均正常）。剩下三处是需要外部配置或宿主对接的收尾
（隧道连接器、Agent 执行器、文件上传），以及两处刻意不做的体系（授权、插件）。

---

## 2. 证据

### 2.1 差异在文档里已声明
- `README.md`：`Closed.apk — free, closed-source edition with the Local API`；
  `Open.apk — ... without the Local API`。
- `docs/FEATURES.md`：`The Local API and its public-tunnel/keepalive implementation are not part of this repository`。

### 2.2 Class 层面的证据
用 jadx 反编译 `classes.dex`（`com.dsmod` 共 8179 个类型描述符）：
- `com.dsmod.probe.zz.*`：3547 个类（R8 混淆后的功能内核 + UI）。
- `com.dsmod.probe.*` 顶层 156 个类，其中可辨认的、与 Local API 直接相关的：
  - `z5` — TLS/CA 管理（`createUnboundServerSocket` / `exportRootModule` / `validate`）
  - `z13` — 到动态加载类 `com.dsmod.probe.z1` 的**反射桥**（`port` / `tlsPort` / `lanEndpoint` / `openAiEndpoint` / `protocolMode` / `apiKey` / `rotateKey` / `start` / `stop` / `connectionInfo` / `runtimeStatus`）
  - `z2` — 契约：`CompletionRequest` / `CompletionResult` / `DeltaSink` / `GatewayException`，常量 `PROTOCOL_OPENAI` / `PROTOCOL_ANTHROPIC`
  - `z17` — 对 `/v1/models`、`/v1/chat/completions` 的客户端
  - `z21` — 保活前台服务；`z20` — 相关组件
  - `II0_101` — Local API 控制通道（BroadcastReceiver，action `com.dsmod.probe.action.LOCAL_API_CONTROL`，extra `dk_ctrl="dk-ka-v1"`）

### 2.3 Manifest 层面的证据
闭源版比开源版多出：
- Provider authority：`com.dsmod.probe.XposedService`、`com.dsmod.probe.RuntimeProofA2`、`com.dsmod.probe.RuntimeProofB2`
- Service：`RuntimeProofBrokerService`、`RuntimeProofEpoch2BrokerService`、`z20`、`z21`
- Activity：`RuntimeProofTrampolineActivity`、`RuntimeProofEpoch2TrampolineActivity`
- 权限：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`SYSTEM_ALERT_WINDOW`、`WAKE_LOCK`、`INTERNET`

### 2.4 资源层面的证据
- `META-INF/com.dsmod.cloud/1d29ef9ca6318f0c`：251072 B，熵 7.999 → 加密的 dex/jar 载荷。
- `META-INF/com.dsmod.protected/p0.dat`（与 `6f661529ae55e1dc` 内容相同）：12132 B，熵 7.986。
- `META-INF/com.github.mwiede.jsch/.../runtime_policy_extension_20260727_v2.dat`：19656 B，熵 5.98 → 打包了 SSH 实现。
- `META-INF/com.dsmod.protected.licenses/nmmvm-apache-2.0.txt`：Apache-2.0 许可文本。

### 2.5 行为层面的证据（UI 文案，中英双语）
`zz/zg1.java` 中的文案明确写出 Local API 的能力范围：

- `Local API · Experimental`
- `Configure compatible endpoints, background keepalive, keys, listeners, and request statistics.`
- `……/chat/completions 与 /responses；支持普通 JSON、SSE 和 Agent 工具循环。`
- `Chat Completions、Responses 与 /v1/models`
- `Local API · Experimental` 高级页：`Change protocol mode`、`Custom listener port`、
  `Custom API key`、`Copy URL`、`Copy API key`、`Copy LAN address`、`Refresh status`、
  `Add to allowlist`、`Enable HTTPS`、`Local API anti-censor`、`Force deep reasoning`、
  `Custom models`、`Automatic recovery`、`Move long context to a file`、
  `One-hour temporary URL (Pinggy)`、`Cloudflare custom domains`、`Public IP / router forwarding`。

已知运行时常量（`Main.java`）：
`LOCAL_API_TIMEOUT_SECONDS=120`、`LOCAL_API_REQUEST_BUDGET_MS=300000`、
`LOCAL_API_QUEUE_POLL_MS=250`、`LOCAL_API_CHAT_QUEUE_WAIT_MS=30000`、
`LOCAL_API_AUX_QUEUE_WAIT_MS=8000`、`LOCAL_API_AGENT_QUEUE_WAIT_MS=60000`、
`LOCAL_API_NATIVE_PERMIT_COUNT=8`；会话数上限 32、TTL 24h。
默认监听 `0.0.0.0:8765`。

---

## 3. 差距清单（按可实现性排序）

| # | 能力 | 闭源版实现要点 | 开源复刻状态 |
|---|---|---|---|
| 0 | 驱动宿主的后端 | 复用宿主会话与网络栈，带会话凭证 | ✅ 已实现（`Main.NativeBridge`：临时会话 + 宿主 PoW + 宿主 Flow 流式读取） |
| 1 | HTTP(S) 监听 + 鉴权 | `0.0.0.0:8765`，Bearer Key，可自定义端口/Key/轮换 | ✅ 已实现；默认关闭，且开启后默认只绑回环，需显式打开「允许局域网访问」才暴露到局域网（见下） |
| 2 | OpenAI 协议 | `/v1/models`、`/v1/chat/completions`、`/v1/responses`，JSON + SSE | ✅ 已实现 |
| 3 | Anthropic 协议 | `/v1/messages`、`/v1/messages/count_tokens`，`thinking` 块 | ✅ 已实现 |
| 4 | 每设备 CA + HTTPS | 自签 CA、SAN 含回环与 LAN、导出 Magisk 模块 / 用户证书 | ✅ 已实现 |
| 5 | 后台保活 | 前台服务 + PARTIAL_WAKE_LOCK + 心跳 + 看门狗 + 自动恢复 | ✅ 已实现 |
| 6 | 请求统计与日志 | 请求数/成功率/延迟、滚动日志、`deekseep_api.log` | ✅ 已实现 |
| 7 | 模型路由与自定义模型 | `default` / `expert` / `vision`，`dq0_custom_models.json` | ✅ 已实现 |
| 8 | 公网入口（隧道） | Pinggy(SSH) / Cloudflare / 手动端口转发 | ⚠️ 抽象层已实现，具体连接器需自行配置 |
| 9 | Agent 工具循环 | `known/completed/repeatable` 三态去重 + MCP/插件派发 | ⚠️ 契约与去重已实现，执行器需接宿主 |
| 10 | 图片/文件上传 | 走宿主上传器拿 fileId | ⚠️ 接口已预留，未接宿主上传器 |
| 11 | RuntimeProof 授权体系 | `RuntimeProof*` Provider/Service | ❌ 刻意不实现（见下） |
| 12 | `JavaPluginApi` 插件体系 | ABI 3 的插件 SDK | ❌ 刻意不实现（超出 Local API 范围） |

### 为什么 11、12 不实现
- **RuntimeProof** 是闭源版的授权/防篡改组件，服务于闭源分发本身。开源复刻不需要它，
  实现它只会给自由分发增加障碍。
- **`JavaPluginApi`** 是独立的插件生态，与 Local API 无依赖关系，属于另一个功能维度。
  若需要可单独立项。

---

## 4. 开源复刻的边界与诚实说明

1. **未解密、未复制闭源载荷。** 服务端主体（`z1`）在加密 dex 中不可见；复刻版本依据
   OpenAI / Anthropic 的公开协议规范 + 上表可观察行为独立编写。
2. **与宿主的对接点是反射的。** 闭源版通过 `HostCompat` 暴露的
   `localApiSessionCreateMethod()` / `localApiSessionDeleteMethod()` /
   `localApiSessionDeleteRequestClass()` / `localApiAuthInterceptorClass()` 等成员驱动宿主网络栈。
   这些访问器在开源仓库里**原本就存在**（只是包级私有，已改为 `public`），
   因此复刻版沿用同一套映射；具体能否命中取决于 DeepSeek 版本，`HostBackend.Bridge`
   就是为此抽象出来的可替换点。
3. **真机已端到端打通。** 协议层与后端都在 Android 16 / DeepSeek 2.3.6 上验证过：
   非流式返回真实模型回复，流式逐帧输出正常，设备端 9 项回归全过。
   实现细节见 [LOCAL_API.md](LOCAL_API.md) 第 10 节。

### 与默认行为有关的一处刻意差异

闭源版默认监听 `0.0.0.0:8765`，即局域网内任何设备都能访问。开源复刻**默认关闭**，
且开启后默认只绑回环地址——把别人的大模型会话暴露到局域网是件后果不对称的事，
这个开关应当由使用者主动、明确地打开。需要 LAN 或公网访问时再自行改配置。

---

## 5. 验证记录

### 5.1 构建期

| 检查项 | 命令 | 结果 |
|---|---|---|
| 全量源码编译（85 个文件，含新增 localapi） | `javac -source 8 -target 8 -encoding UTF-8 -cp android.jar` | 通过（exit 0） |
| dex 打包 | `d8 --min-api 24` | 通过，产出 `classes.dex` |
| Local API 回归测试 | `LocalApiProtocolRegressionTest` | 通过 |
| 模块 APK 打包 + 签名 | `module/test-local-api-device.sh` 前置的 `build.sh` 流程 | 通过，1.34MB，真机 `adb install -r` 成功 |

顺带修掉的两个构建可移植性问题（与本次功能无关，但阻塞了本机验证）：
- `module-universal/build.sh` 的 javac 未指定 `-encoding UTF-8`，在非 UTF-8 locale（如 GBK Windows）下
  会因 CJK 注释报"不可映射字符"。已补上。
- `HostCompat` 的 6 个 `localApi*` 访问器原为包级私有，子包无法调用，已改为 `public`。
- 另外 4 个脚本把 classpath 分隔符硬编码成 `:`，在 Windows 上应为 `;`。已统一为平台感知的
  `CP_SEP`（`scripts/android-tools.sh`）。同一批还补了 `scripts/org-json.sh`：SDK 的 `android.jar`
  是 stub，不含 `org.json`，编译期需要它、运行时由设备提供。

### 5.2 真机运行期

环境：Android 16（SDK 36）、KernelSU + LSPosed、宿主 DeepSeek 2.3.6（code249-cn）、
模块 1.7.4-fix Open。设备 `adb` 直连，`adb forward` 暴露端口，脚本为
`module/test-local-api-device.sh`。**9 项全部通过**，详见 [LOCAL_API.md](LOCAL_API.md) 第 9 节。

真机联调暴露并修掉了两个只在带真实流量时才会显现的缺陷：
1. 流式响应先提交 `200` 再请求上游，上游失败时错误帧无处可写，客户端收到 `200` + 空 body；
2. 失败路径从不计数，导致上游全挂时 `total_requests` 仍为 0。

另外记录了三条上机才发现的部署事实（写进 `localapi/README` 无意义，但会影响任何复现者）：
- **卸载重装模块会让 LSPosed 丢掉启用状态与作用域**（数据库里 `modules.apk_path` 会更新，
  但 `modules_state` / `scope` 行被删）。`adb install -r` 覆盖安装才会保留。重装后需在
  LSPosed 管理器里重新打开"启用模块"。
- **配置文件的属主必须是宿主 uid**。用 root 写进去的 `dq0_config.json` 宿主读不到，
  表现为"配置明明开着但服务不启动"。
- **`/storage/emulated/0` 写入失败是正常的**：宿主受 scoped storage 限制，模块的注入标记文件
  可能写不出来，不能拿它当"是否注入"的依据。判断依据应当是 LSPosed 模块日志里的
  `(com.deepseek.chat)[com.dsmod.probe,...]` 行，以及宿主 `files/` 目录下的 `dsprobe.log`。

