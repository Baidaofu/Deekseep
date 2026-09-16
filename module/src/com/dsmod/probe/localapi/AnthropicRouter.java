package com.dsmod.probe.localapi;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Translates between the Anthropic Messages wire format and
 * {@link ApiContract}.
 *
 * <p>Supported surface:
 * <ul>
 *   <li>{@code POST /v1/messages} - JSON and SSE, including {@code thinking}
 *       blocks and tool use blocks</li>
 *   <li>{@code POST /v1/messages/count_tokens} - rough token estimate</li>
 * </ul>
 *
 * <p>Because the base URL deliberately carries no {@code /v1} suffix, clients
 * configured for the Anthropic convention can point straight at the phone.
 */
public final class AnthropicRouter {

    private static final String VERSION = "2023-06-01";

    private final ApiContract.Backend backend;

    public AnthropicRouter(ApiContract.Backend backend) {
        this.backend = backend;
    }

    public void messages(HttpExchange exchange) throws Exception {
        JSONObject request = OpenAiRouter.parseBody(exchange);
        String model = request.optString("model", null);
        boolean stream = request.optBoolean("stream", false);
        ApiContract.CompletionRequest completion = fromMessagesRequest(request, model);
        long started = System.currentTimeMillis();

        if (!backend.isReady()) {
            throw new ApiContract.GatewayException(503, "backend_not_ready",
                    "server_error", backend.readinessDetail());
        }
        if (stream) {
            streamMessages(exchange, completion, started);
        } else {
            ApiContract.CompletionResult result = backend.complete(completion, noopSink());
            exchange.respond(200, "application/json",
                    messageBody(result, model).toString().getBytes("UTF-8"));
            LocalApiStats.recordSuccess(false, completion.reasoning,
                    System.currentTimeMillis() - started);
        }
    }

    /** Approximate token count; exact counts require host side tokenisation. */
    public void countTokens(HttpExchange exchange) throws Exception {
        JSONObject request = OpenAiRouter.parseBody(exchange);
        JSONArray messages = request.optJSONArray("messages");
        int characters = 0;
        if (messages != null) {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.optJSONObject(i);
                if (message == null) {
                    continue;
                }
                characters += OpenAiRouter.flattenContent(message.opt("content")).length();
            }
        }
        JSONObject body = new JSONObject();
        body.put("input_tokens", Math.max(1, characters / 4));
        exchange.respond(200, "application/json", body.toString().getBytes("UTF-8"));
    }

    private void streamMessages(HttpExchange exchange, ApiContract.CompletionRequest completion,
            long started) throws Exception {
        exchange.startStreaming(200, "text/event-stream");
        JSONObject start = new JSONObject();
        start.put("type", "message_start");
        start.put("message", new JSONObject()
                .put("id", completion.requestId)
                .put("type", "message")
                .put("role", "assistant")
                .put("model", completion.requestedModel)
                .put("content", new JSONArray())
                .put("stop_reason", JSONObject.NULL)
                .put("usage", new JSONObject().put("input_tokens", 0).put("output_tokens", 0)));
        exchange.writeSse(start.toString());

        if (completion.reasoning) {
            exchange.writeSse(blockStart("thinking").toString());
        }
        exchange.writeSse(blockStart("text").toString());

        ApiContract.DeltaSink sink = new ApiContract.DeltaSink() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void onReasoning(String delta) throws Exception {
                if (delta == null || delta.isEmpty()) {
                    return;
                }
                exchange.writeSse(blockDelta("thinking_delta", "thinking", delta).toString());
            }

            @Override
            public void onText(String delta) throws Exception {
                if (delta == null || delta.isEmpty()) {
                    return;
                }
                exchange.writeSse(blockDelta("text_delta", "text", delta).toString());
            }
        };
        ApiContract.CompletionResult result;
        try {
            result = backend.complete(completion, sink);
        } catch (Exception failure) {
            // Status and SSE headers are already on the wire; Anthropic clients
            // expect an in-band "error" event rather than a bare disconnect.
            failStream(exchange, failure);
            throw failure;
        } catch (Error failure) {
            failStream(exchange, failure);
            throw failure;
        }

        if (completion.reasoning) {
            exchange.writeSse(blockStop().put("index", 0).toString());
        }
        exchange.writeSse(blockStop().put("index", completion.reasoning ? 1 : 0).toString());
        JSONObject delta = new JSONObject();
        delta.put("type", "message_delta");
        delta.put("delta", new JSONObject()
                .put("stop_reason", result.hasToolCalls() ? "tool_use" : "end_turn"));
        delta.put("usage", new JSONObject()
                .put("output_tokens", Math.max(1, result.text.length() / 4)));
        exchange.writeSse(delta.toString());
        exchange.writeSse(new JSONObject().put("type", "message_stop").toString());
        exchange.endStreaming();
        LocalApiStats.recordSuccess(true, completion.reasoning,
                System.currentTimeMillis() - started);
    }

    // -------------------------------------------------------- stream errors

    /**
     * Terminates a doomed stream with Anthropic's {@code error} event.
     *
     * <p>The status line is already committed, so the only honest signal left is
     * an in-band error. The JSON {@code type} field is what Anthropic SDKs key
     * on, which is why the frame is emitted as a plain {@code data:} payload.
     */
    static void failStream(HttpExchange exchange, Throwable failure) {
        try {
            exchange.writeSse(errorFrame(failure));
            exchange.endStreaming();
        } catch (Throwable ignored) {
            // Peer already gone.
        }
    }

    /** Renders a failure as an Anthropic style {@code {"type":"error",...}}. */
    static String errorFrame(Throwable failure) {
        String message;
        String type = "api_error";
        if (failure instanceof ApiContract.GatewayException) {
            ApiContract.GatewayException gateway = (ApiContract.GatewayException) failure;
            message = gateway.getMessage();
            if ("authentication_error".equals(gateway.type)) {
                type = "authentication_error";
            } else if ("invalid_request_error".equals(gateway.type)) {
                type = "invalid_request_error";
            }
        } else {
            message = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
        }
        try {
            JSONObject error = new JSONObject();
            error.put("type", type);
            error.put("message", message == null ? "internal error" : message);
            JSONObject event = new JSONObject();
            event.put("type", "error");
            event.put("error", error);
            return event.toString();
        } catch (Throwable ignored) {
            return "{\"type\":\"error\",\"error\":{\"type\":\"api_error\","
                    + "\"message\":\"internal error\"}}";
        }
    }

    private static JSONObject blockStart(String type) throws Exception {
        JSONObject event = new JSONObject();
        event.put("type", "content_block_start");
        JSONObject block = new JSONObject();
        block.put("type", type);
        if ("thinking".equals(type)) {
            block.put("thinking", "");
        } else if ("text".equals(type)) {
            block.put("text", "");
        }
        event.put("content_block", block);
        return event;
    }

    private static JSONObject blockDelta(String eventType, String blockType, String text)
            throws Exception {
        JSONObject event = new JSONObject();
        event.put("type", "content_block_delta");
        JSONObject delta = new JSONObject();
        delta.put("type", eventType);
        delta.put(blockType, text);
        event.put("delta", delta);
        return event;
    }

    private static JSONObject blockStop() throws Exception {
        JSONObject event = new JSONObject();
        event.put("type", "content_block_stop");
        return event;
    }

    private static ApiContract.CompletionRequest fromMessagesRequest(JSONObject request,
            String model) throws Exception {
        StringBuilder transcript = new StringBuilder();
        StringBuilder system = new StringBuilder();
        Object systemValue = request.opt("system");
        if (systemValue != null) {
            system.append(OpenAiRouter.flattenContent(systemValue));
        }
        JSONArray messages = request.optJSONArray("messages");
        if (messages != null) {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.optJSONObject(i);
                if (message == null) {
                    continue;
                }
                String role = message.optString("role", "user");
                String content = OpenAiRouter.flattenContent(message.opt("content"));
                if ("assistant".equals(role)) {
                    transcript.append("Assistant: ").append(content);
                } else {
                    transcript.append("User: ").append(content);
                }
                if (i < messages.length() - 1) {
                    transcript.append('\n');
                }
            }
        }
        boolean reasoning = request.optJSONObject("thinking") != null
                || ApiContract.MODEL_EXPERT.equals(
                        ModelCatalog.resolveRole(model, customModels()))
                || LocalApiConfig.get().forceReasoning;
        return new ApiContract.CompletionRequest(
                "msg_" + Long.toHexString(System.currentTimeMillis()),
                model,
                ModelCatalog.resolveRole(model, customModels()),
                system.toString(),
                transcript.toString(),
                reasoning,
                true,
                request.optInt("max_tokens", 0),
                toolPlan(request),
                null,
                false);
    }

    private static ApiContract.ToolPlan toolPlan(final JSONObject request) {
        boolean hasTools = request.optJSONArray("tools") != null;
        return new ApiContract.ToolPlan() {
            @Override
            public boolean active() {
                return hasTools;
            }
        };
    }

    private static JSONObject messageBody(ApiContract.CompletionResult result, String model)
            throws Exception {
        JSONArray content = new JSONArray();
        if (!result.reasoning.isEmpty()) {
            JSONObject thinking = new JSONObject();
            thinking.put("type", "thinking");
            thinking.put("thinking", result.reasoning);
            content.put(thinking);
        }
        if (!result.text.isEmpty()) {
            JSONObject text = new JSONObject();
            text.put("type", "text");
            text.put("text", result.text);
            content.put(text);
        }
        for (ApiContract.ToolCall call : result.toolCalls) {
            JSONObject use = new JSONObject();
            use.put("type", "tool_use");
            use.put("id", call.id);
            use.put("name", call.name);
            use.put("input", new JSONObject(call.arguments));
            content.put(use);
        }
        JSONObject body = new JSONObject();
        body.put("id", "msg_" + Long.toHexString(System.currentTimeMillis()));
        body.put("type", "message");
        body.put("role", "assistant");
        body.put("model", model);
        body.put("content", content);
        body.put("stop_reason", result.hasToolCalls() ? "tool_use" : "end_turn");
        body.put("usage", new JSONObject()
                .put("input_tokens", 0)
                .put("output_tokens", Math.max(1, result.text.length() / 4)));
        body.put("anthropic_version", VERSION);
        return body;
    }

    private static java.util.List<Object> customModels() {
        return ModelCatalog.parseCustom(LocalApiConfig.get().customModelsJson);
    }

    private static ApiContract.DeltaSink noopSink() {
        return new ApiContract.DeltaSink() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void onReasoning(String delta) {
            }

            @Override
            public void onText(String delta) {
            }
        };
    }
}
