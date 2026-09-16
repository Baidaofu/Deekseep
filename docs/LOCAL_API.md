# Local API

Local API 在手机上的 DeepSeek 进程内起一个 HTTP(S) 服务，对外提供
**OpenAI 兼容**与 **Anthropic 兼容**两种接口，让任意支持自定义 `base_url` 的客户端
（OpenAI SDK、Cherry Studio、NextChat、Claude 客户端等）直接把手机当作推理端点。

本实现是 `module/src/com/dsmod/probe/localapi/` 下的**独立开源实现**，不依赖任何闭源载荷。
背景与差距分析见 [LOCAL_API_GAP.md](LOCAL_API_GAP.md)。

---

## 1. 快速开始

1. 在 LSPosed 中启用 Deekseep，作用域只勾选 `com.deepseek.chat`。
2. 打开 DeepSeek → 设置 → Deekseep → **Local API · Experimental**。
3. 打开 **Enable local API service**。
4. 复制 **Copy URL** 得到的地址与 **Copy API key** 得到的密钥，填进你的客户端。
   - OpenAI 客户端：`base_url = http://<手机IP>:8765/v1`
   - Anthropic 客户端：`base_url = http://<手机IP>:8765`（**无** `/v1` 后缀）

> 监听地址固定为 `0.0.0.0`，局域网内可达。**因此即使从本机访问也必须带 API Key。**

---

## 2. 接口

### 通用
- 鉴权：`Authorization: Bearer <key>`。缺失或错误返回 `401`：
  ```json
  {"error":{"message":"Incorrect API key provided...","type":"authentication_error","code":"invalid_api_key"}}
  ```
- `GET /health` 免鉴权，返回服务状态与统计。
- 错误统一为 OpenAI 风格：`{"error":{"message","type","code"}}`。

### OpenAI 模式（`protocolMode = openai`，默认）
| 端点 | 方法 | 说明 |
|---|---|---|
| `/v1/models` | GET | 返回 `deepseek-chat` / `deepseek-reasoner` / `deepseek-vision` 及自定义模型 |
| `/v1/chat/completions` | POST | JSON 与 SSE（`stream: true`）；`stream_options.include_usage` 支持；`tool_calls` 会流式发出 |
| `/v1/responses` | POST | Responses API；支持 `instructions`、`previous_response_id`、SSE |

流式返回中思维链走 `delta.reasoning_content`，正文走 `delta.content`，两者独立。

### Anthropic 模式（`protocolMode = anthropic`）
| 端点 | 方法 | 说明 |
|---|---|---|
| `/v1/messages` | POST | JSON 与 SSE；`thinking` 块、`tool_use` 块 |
| `/v1/messages/count_tokens` | POST | 粗略 token 估算 |

> 两种模式互斥：在 OpenAI 模式下调用 `/v1/messages` 会返回
> `404 / protocol_mismatch`，反之亦然。这是刻意的设计，避免客户端配错协议时拿到畸形结果。

### 模型路由
| 公开 id | 宿主角色 |
|---|---|
| `deepseek-chat` | `default` |
| `deepseek-reasoner` | `expert`（深度思考） |
| `deepseek-vision` | `vision` |

输入别名（不对外广告，但接受）：`deepseek-v4-flash`、`deepseek-v4-pro`、`deepseek-r1`、`reasoner`。
未知模型名**不会 404**，而是回落到 `default`——客户端自己造模型名时仍能拿到回答。
`deepseek-aux` 与 `deepseek-aux-*` 被识别为后台辅助请求，不进入可见的 Agent 循环。
自定义模型写入 `dq0_custom_models.json`：`[{"id":"my-model","native_model":"expert"}]`。

---

## 3. 配置项

| 设置 | 持久化 | 默认 |
|---|---|---|
| Enable local API service | `dq0_config.json` | 关 |
| Format（`openai` / `anthropic`） | 同上 | `openai` |
| Custom listener port（1024–65535） | 同上 | `8765` |
| Custom API key（8–256 位无空格 ASCII） | 同上 | 首次自动生成 64 位十六进制 |
| Enable HTTPS | 同上 | 关 |
| Serial request policy | 同上 | 开 |
| Local API anti-censor | 同上 | 关 |
| Inject system prompt / 提示词 | 同上 | 关 / 空 |
| Move long context to a file | 同上 | 开 |
| Force deep reasoning | 同上 | 关 |
| Custom models | 同上 | `[]` |
| Public origin（自建端口转发） | 同上 | 空 |
| Automatic recovery | 同上 | 开 |

API Key 支持"生成随机 Key"（轮换）与自定义；校验为**定长时间比较**，防止计时侧信道。

---

## 4. HTTPS

默认关闭（HTTP）。开启后：
1. 首次生成本机唯一的自签 CA，存 `dq0_ca.p12`（私钥仅存于 DeepSeek 私有目录，密码随机生成）。
2. 用该 CA 签发服务器证书，SAN 含 `127.0.0.1`、`localhost` 与当前 LAN IP。
   换网络时证书会**自动重签**，无需重新信任 CA。
3. 仅允许 `TLSv1.2` / `TLSv1.3`。

信任 CA 有三条路：
- 导出 `Deekseep-Local-API-CA.cer` 到下载目录，用系统凭据安装器导入；
- 导出 `Deekseep-CA-Root.zip`，在 Magisk / KernelSU / APatch 中刷入（系统级信任，需重启）；
- 只导出 PEM，让客户端**固定证书**而不改系统信任。

设置页会显示 CA 的 SHA-256 指纹，便于核对信任的确实是本机那张证书。

> 证书由包内 `Der.java` 这个极小的 DER 写入器构造，未引入 BouncyCastle 等依赖。

---

## 5. 后台保活

`KeepAliveService` 以前台服务运行（通知渠道 `dq0`），并持有 `PARTIAL_WAKE_LOCK`：
- 每 5 秒发一次心跳广播；
- 90 秒未收到确认则判定失联：若开了 Automatic recovery 则自动重启监听并计数，否则自行停止；
- 通知可被用户关闭，服务本身不导出。

电池优化会直接导致后台断流，设置页提供 **Open battery settings / Add to allowlist** 两个入口。

---

## 6. 统计与日志

- 内存计数 + `deekseep_api_status.json`：请求总数、成功/失败、流式数、深度思考数、
  工具轮次、平均/最大延迟、长上下文转移成功/缓存/失败、自动恢复次数。
- 滚动日志 `deekseep_api.log`，保留最近 400 行。
- `GET /health` 与设置页的 "Live listener and request statistics" 都读同一份数据。

---

## 7. 公网访问（可选）

三种方式，都默认关闭：
1. **临时 SSH 隧道**：手机主动外连公共网关换取随机 HTTPS 地址，无需在路由器开端口，
   地址约 1 小时失效。
2. **命名隧道**：配置 token + 自己的域名，常驻且随网络变化重连；边缘传输可选
   `auto → http2 → quic`。
3. **手动端口转发**：在路由器上转发后，把形如 `http://203.0.113.10:8765` 的地址填进
   Public origin。

`PublicTunnel` 提供了状态机与校验（只接受 http/https 且带主机名的地址）；
具体连接器由使用者自行接入。

---

## 8. 代码结构

```
module/src/com/dsmod/probe/localapi/
├── ApiContract.java       契约：请求/结果/增量/错误（对应闭源版 z2 的语义）
├── LocalApi.java          门面：start/stop/status
├── LocalApiServer.java    监听、鉴权、路由、TLS 接入
├── LocalApiConfig.java    全部设置与持久化
├── LocalApiStats.java     统计与滚动日志
├── OpenAiRouter.java      /v1/models、/v1/chat/completions、/v1/responses
├── AnthropicRouter.java   /v1/messages、/v1/messages/count_tokens
├── ModelCatalog.java      模型 → 宿主角色映射
├── HostBackend.java       请求编排：队列、超时、并发许可、会话生命周期
├── ReflectiveBridge.java  通过 HostCompat 驱动宿主网络栈
├── HttpServer.java        accept 循环 + 工作线程池
├── HttpExchange.java      HTTP 解析与响应（含 chunked / SSE）
├── SseReader.java         上游 SSE 解析
├── TlsDirector.java       每设备 CA、证书签发、导出
├── Der.java               极简 DER 写入器（供 TlsDirector 用）
├── KeepAliveService.java  前台保活服务
└── PublicTunnel.java      公网入口状态机与校验
```

### 扩展点
需要改接入方式时，实现 `HostBackend.Bridge` 并通过 `LocalApi.setBackend(...)` 注入即可，
不必改动协议层。闭源版把同一件事藏在加密 dex 里；这里把它显式做成接口。

---

## 9. 真机联调

`module/test-local-api-device.sh` 把设备上的监听端口转发到本机并跑完整协议矩阵：

```bash
adb forward tcp:8765 tcp:8765
./module/test-local-api-device.sh sk-deekseep-... 8765
```

脚本每次请求前都会**重建转发**。这不是可有可无的谨慎：adb daemon 一旦重启就会丢弃
已有 forward，此时客户端收到的是"连接被拒绝"，很容易被误判成"服务端挂了"。

已通过（Android 16 / SDK 36，KernelSU + LSPosed，DeepSeek 2.3.6，模块 1.7.4-fix Open）：

| 项 | 结果 |
| --- | --- |
| `/health` 免鉴权可访问 | 200 |
| 错误 Key 拒绝 | 401 `invalid_api_key` |
| `/v1/models` | 200，三个模型 |
| openai 模式下 `/v1/messages` | 404 `protocol_mismatch` |
| `/v1/models` 收到 POST | 405 `method_not_allowed` |
| 未知路径 | 404 `unknown_endpoint` |
| 非法 JSON | 400 `invalid_json` |
| 流式请求出错 | 200 + `data: {"error":...}` + `data: [DONE]` |
| 统计计数 | `total == successful + failed`，成功/失败分别累计 |

真机联调暴露并修掉了两个只在带真实流量的情况下才会显现的缺陷：

1. **流式先提交 200 再请求上游。** 上游失败时 `respondError` 因响应头已写出而静默返回，
   客户端拿到的是 `200` + 空 body —— 看起来像"模型回了空话"。现在失败会以 SSE 错误帧
   在带内送达，然后才 `[DONE]`。
2. **失败路径从不计数。** 只有成功路径调用 `recordSuccess`，所以上游全挂时 `total_requests`
   仍然是 0。现在 `LocalApiServer.handle` 统一登记失败，且只统计补全端点，使
   `total` 恒等于 `successful + failed`，不被探活流量稀释。

## 10. 已知限制

- **图片/文件上传**尚未接宿主上传器：`OpenAiRouter.flattenContent` 目前只提取文本部分。
- **Agent 工具循环**的契约与三态去重已就位，但执行器（MCP / 插件派发）未接。
- 公网隧道只提供抽象与校验，未内置具体连接器。
- 会话删除走的是 `HostCompat` 里的历史类名 (`jb1`)；在部分构建上该类名已变（例如解析为
  `mc1`），删除会失败。临时会话由服务端自行过期，失败只写日志，不影响补全。

### 宿主原生补全（已接通）

Local API 的后端**不走匿名 HTTP**，而是复用宿主自己的传输：开一个临时会话，
用宿主的 PoW 与签名把它当成一次普通对话发出去，再从宿主 UI 消费的同一个 Flow 里读回增量。
对服务端而言这和普通一轮对话没有区别，模块也不需要重新实现每版都变的签名与实验开关。
`ReflectiveBridge` 仍保留为兜底，仅在 `r92`/`q71` 尚未捕获时使用。

实现落在 `Main.NativeBridge`（`HostBackend.Bridge`）：

| 步骤 | 实现 |
| --- | --- |
| 铸 PoW | `mintCompletionPow(cl, liveQ71)` |
| 建会话 | `createThrowawaySession(cl, r92)`，结束用 `deleteThrowawaySession` |
| 构造请求 | `newNativeCompletionRequest`：优先克隆宿主最近一次真实请求对象（`liveRequestTemplate`，保留宿主自己填的所有默认值），否则用 `allocateByConstructor` 按结构签名兜底；再覆盖字段 `a`=会话 id、`b`=null、`c`=prompt、`d`=fileIds、`e`/`f`=`FALSE`、`i`=model_type（`default`/`expert`/`vision`）、`k`=PoW |
| 发起 | `r92` 上参数个数为 2 的 `b(request, null)` 返回 Flow |
| 消费 | `collectFlowStreaming`：与 `collectFlow` 同骨架，但增量边到边转发给 `DeltaSink` |
| 区分通道 | `emitNativeEvent` 按事件 JSON 判断走 reasoning 还是 content |

`captureApiManagers` 捕获到传输对象后调用 `installNativeBackend()` 注入，
因此注入时机不依赖设置页。

**顺带修掉的一个真 bug**：`extractSessionId(String)` 重载此前并不存在，
`createThrowawaySession` 里的 `extractSessionId(body)` 会解析回 `Object` 版本并无限递归，
以 `StackOverflowError` 收场。也就是说专家视觉中继的建会话一直是坏的。
现已补上基于 JSON 的实现（见 `firstUsableId`）。

