package com.dsmod.probe.localapi;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Translates between OpenAI wire format and {@link ApiContract}.
 *
 * <p>Supported surface:
 * <ul>
 *   <li>{@code GET /v1/models}</li>
 *   <li>{@code POST /v1/chat/completions} - JSON and SSE, tool calls</li>
 *   <li>{@code POST /v1/responses} - the newer Responses API, non streaming
 *       plus SSE, with {@code previous_response_id} continuity</li>
 * </ul>
 *
 * <p>The mapping aims for pragmatic compatibility: fields clients rely on are
 * honoured, exotic fields are accepted and ignored rather than rejected, so an
 * ordinary client never sees a needless 400.
 */
public final class OpenAiRouter {

    private static final String OBJECT_CHAT_COMPLETION = "chat.completion";
    private static final String OBJECT_CHAT_CHUNK = "chat.completion.chunk";
    private static final String ROLE_ASSISTANT = "assistant";

    private final ApiContract.Backend backend;

    public OpenAiRouter(ApiContract.Backend backend) {
        this.backend = backend;
    }

    // --------------------------------------------------------------- models

    public void models(HttpExchange exchange) throws Exception {
        JSONObject body = new JSONObject();
        body.put("object", "list");
        JSONArray data = new JSONArray();
        for (ModelCatalog.Entry entry : ModelCatalog.advertised(customModels())) {
            JSONObject item = new JSONObject();
            item.put("id", entry.id);
            item.put("object", "model");
            item.put("created", 0);
            item.put("owned_by", entry.ownedBy);
            data.put(item);
        }
        body.put("data", data);
        exchange.respond(200, "application/json", body.toString().getBytes("UTF-8"));
    }

    // ----------------------------------------------------- chat completions

    public void chatCompletions(HttpExchange exchange) throws Exception {
        JSONObject request = parseBody(exchange);
        String model = request.optString("model", null);
        boolean stream = request.optBoolean("stream", false);
        boolean includeUsage = request.optJSONObject("stream_options") != null
                && request.optJSONObject("stream_options").optBoolean("include_usage", false);
        ApiContract.CompletionRequest completion = fromChatRequest(request, model, exchange);
        long started = System.currentTimeMillis();

        if (!backend.isReady()) {
            throw new ApiContract.GatewayException(503, "backend_not_ready",
                    "server_error", backend.readinessDetail());
        }
        if (stream) {
            streamChat(exchange, completion, model, includeUsage, started);
        } else {
            completeChat(exchange, completion, model, started);
        }
    }

    private void completeChat(HttpExchange exchange, ApiContract.CompletionRequest completion,
            String model, long started) throws Exception {
        ApiContract.CompletionResult result = backend.complete(completion, noopSink());
        exchange.respond(200, "application/json",
                chatCompletionBody(completion.requestId, model, result).toString()
                        .getBytes("UTF-8"));
        LocalApiStats.recordSuccess(false, completion.reasoning,
                System.currentTimeMillis() - started);
    }

    private void streamChat(HttpExchange exchange, ApiContract.CompletionRequest completion,
            String model, boolean includeUsage, long started) throws Exception {
        exchange.startStreaming(200, "text/event-stream");
        final StringBuilder answer = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        String id = completion.requestId;
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
                thinking.append(delta);
                exchange.writeSse(chunkBuilder(id, null)
                        .put("choices", choices(reasoningDelta(delta)))
                        .toString());
            }

            @Override
            public void onText(String delta) throws Exception {
                if (delta == null || delta.isEmpty()) {
                    return;
                }
                answer.append(delta);
                exchange.writeSse(chunkBuilder(id, null)
                        .put("choices", choices(contentDelta(delta)))
                        .toString());
            }

            @Override
            public String publishedTextSnapshot() {
                return answer.toString();
            }
        };
        ApiContract.CompletionResult result;
        try {
            result = backend.complete(completion, sink);
        } catch (Exception failure) {
            // The 200 was already committed with the SSE headers, so the failure
            // has to travel inside the stream. Emitting a terminal error frame is
            // the only way a streaming client learns what went wrong; ending the
            // body silently would look like a successful empty answer.
            failStream(exchange, failure);
            throw failure;
        } catch (Error failure) {
            failStream(exchange, failure);
            throw failure;
        }
        if (result.hasToolCalls()) {
            emitToolCalls(exchange, id, result);
        }
        exchange.writeSse(chunkBuilder(id, null)
                .put("choices", choices(finishDelta(result.finishReason)))
                .toString());
        if (includeUsage) {
            exchange.writeSse(chunkBuilder(id, null)
                    .put("choices", new JSONArray())
                    .put("usage", usage(result))
                    .toString());
        }
        exchange.writeSse("[DONE]");
        exchange.endStreaming();
        LocalApiStats.recordSuccess(true, completion.reasoning,
                System.currentTimeMillis() - started);
    }

    // -------------------------------------------------------------- responses

    public void responses(HttpExchange exchange) throws Exception {
        JSONObject request = parseBody(exchange);
        String model = request.optString("model", null);
        boolean stream = request.optBoolean("stream", false);
        ApiContract.CompletionRequest completion = fromResponsesRequest(request, model);
        long started = System.currentTimeMillis();

        if (!backend.isReady()) {
            throw new ApiContract.GatewayException(503, "backend_not_ready",
                    "server_error", backend.readinessDetail());
        }
        if (stream) {
            streamResponses(exchange, completion, model, started);
        } else {
            ApiContract.CompletionResult result = backend.complete(completion, noopSink());
            exchange.respond(200, "application/json",
                    responseBody(completion.requestId, model, result, completion.previousResponseId)
                            .toString().getBytes("UTF-8"));
            LocalApiStats.recordSuccess(false, completion.reasoning,
                    System.currentTimeMillis() - started);
        }
    }

    private void streamResponses(HttpExchange exchange, ApiContract.CompletionRequest completion,
            String model, long started) throws Exception {
        exchange.startStreaming(200, "text/event-stream");
        final String previous = completion.previousResponseId;
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
                exchange.writeSse(responseEvent("response.reasoning_text.delta", delta));
            }

            @Override
            public void onText(String delta) throws Exception {
                if (delta == null || delta.isEmpty()) {
                    return;
                }
                exchange.writeSse(responseEvent("response.output_text.delta", delta));
            }
        };
        ApiContract.CompletionResult result;
        try {
            result = backend.complete(completion, sink);
        } catch (Exception failure) {
            failStream(exchange, failure);
            throw failure;
        } catch (Error failure) {
            failStream(exchange, failure);
            throw failure;
        }
        JSONObject completed = new JSONObject();
        completed.put("type", "response.completed");
        completed.put("response", responseBody(completion.requestId, model, result, previous));
        exchange.writeSse(completed.toString());
        exchange.endStreaming();
        LocalApiStats.recordSuccess(true, completion.reasoning,
                System.currentTimeMillis() - started);
    }

    // -------------------------------------------------------- stream errors

    /**
     * Terminates a doomed stream with an explicit error frame.
     *
     * <p>Once {@code startStreaming} has written the status line the response is
     * committed: the status can no longer be changed, so the failure has to be
     * delivered in band. A client that understands SSE will surface it; one that
     * does not will at least see the stream close instead of hanging.
     */
    static void failStream(HttpExchange exchange, Throwable failure) {
        try {
            exchange.writeSse(errorFrame(failure));
            exchange.writeSse("[DONE]");
            exchange.endStreaming();
        } catch (Throwable ignored) {
            // The peer is already gone; the socket close in the worker is enough.
        }
    }

    /** Renders a failure as an OpenAI style {@code {"error":{...}}} SSE payload. */
    static String errorFrame(Throwable failure) {
        String message;
        String type = "server_error";
        String code = "internal_error";
        if (failure instanceof ApiContract.GatewayException) {
            ApiContract.GatewayException gateway = (ApiContract.GatewayException) failure;
            message = gateway.getMessage();
            type = gateway.type;
            code = gateway.code;
        } else {
            message = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
        }
        try {
            JSONObject error = new JSONObject();
            error.put("message", message == null ? "internal error" : message);
            error.put("type", type);
            error.put("code", code);
            JSONObject body = new JSONObject();
            body.put("error", error);
            return body.toString();
        } catch (Throwable ignored) {
            return "{\"error\":{\"message\":\"internal error\",\"type\":\"server_error\","
                    + "\"code\":\"internal_error\"}}";
        }
    }

    private String responseEvent(String type, String delta) throws Exception {
        JSONObject event = new JSONObject();
        event.put("type", type);
        event.put("delta", delta);
        return event.toString();
    }

    // ---------------------------------------------------------- translation

    static ApiContract.CompletionRequest fromChatRequest(JSONObject request, String model,
            Object ignored) throws Exception {
        JSONArray messages = request.optJSONArray("messages");
        StringBuilder transcript = new StringBuilder();
        StringBuilder system = new StringBuilder();
        if (messages != null) {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.optJSONObject(i);
                if (message == null) {
                    continue;
                }
                String role = message.optString("role", "user");
                String content = flattenContent(message.opt("content"));
                if ("system".equals(role) || "developer".equals(role)) {
                    if (system.length() > 0) {
                        system.append("\n\n");
                    }
                    system.append(content);
                } else {
                    if (transcript.length() > 0) {
                        transcript.append('\n');
                    }
                    transcript.append(ROLE_PREFIX(role)).append(content);
                }
            }
        }
        boolean reasoning = reasoningRequested(model, request);
        return new ApiContract.CompletionRequest(
                "chatcmpl-" + Long.toHexString(System.currentTimeMillis()),
                model,
                ModelCatalog.resolveRole(model, customModels()),
                system.toString(),
                transcript.toString(),
                reasoning,
                request.optBoolean("web_search", true),
                request.optInt("max_tokens", request.optInt("max_completion_tokens", 0)),
                toolPlan(request),
                null,
                false);
    }

    private static ApiContract.CompletionRequest fromResponsesRequest(JSONObject request,
            String model) throws Exception {
        StringBuilder transcript = new StringBuilder();
        StringBuilder system = new StringBuilder();
        Object input = request.opt("input");
        if (input instanceof JSONArray) {
            JSONArray items = (JSONArray) input;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String role = item.optString("role", "user");
                String content = flattenContent(item.opt("content"));
                if ("system".equals(role) || "developer".equals(role)) {
                    if (system.length() > 0) {
                        system.append("\n\n");
                    }
                    system.append(content);
                } else {
                    if (transcript.length() > 0) {
                        transcript.append('\n');
                    }
                    transcript.append(ROLE_PREFIX(role)).append(content);
                }
            }
        } else if (input != null) {
            transcript.append(input.toString());
        }
        String systemInstruction = request.optString("instructions", null);
        if (systemInstruction != null && !systemInstruction.isEmpty()) {
            if (system.length() > 0) {
                system.append("\n\n");
            }
            system.append(systemInstruction);
        }
        boolean reasoning = reasoningRequested(model, request);
        return new ApiContract.CompletionRequest(
                "resp-" + Long.toHexString(System.currentTimeMillis()),
                model,
                ModelCatalog.resolveRole(model, customModels()),
                system.toString(),
                transcript.toString(),
                reasoning,
                true,
                request.optInt("max_output_tokens", 0),
                toolPlan(request),
                request.optString("previous_response_id", null),
                true);
    }

    private static String ROLE_PREFIX(String role) {
        if (ROLE_ASSISTANT.equals(role)) {
            return "Assistant: ";
        }
        if ("tool".equals(role)) {
            return "Tool result: ";
        }
        return "User: ";
    }

    /** Flattens the two OpenAI content shapes: plain string or parts array. */
    static String flattenContent(Object raw) {
        if (raw == null) {
            return "";
        }
        if (raw instanceof String) {
            return (String) raw;
        }
        if (raw instanceof JSONArray) {
            JSONArray parts = (JSONArray) raw;
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part == null) {
                    continue;
                }
                String type = part.optString("type", "text");
                if ("text".equals(type)) {
                    builder.append(part.optString("text", ""));
                } else if ("input_text".equals(type)) {
                    builder.append(part.optString("text", ""));
                }
                // Image parts require a native upload round trip; noted not embedded.
            }
            return builder.toString();
        }
        return String.valueOf(raw);
    }

    private static boolean reasoningRequested(String model, JSONObject request) {
        if (ApiContract.MODEL_EXPERT.equals(ModelCatalog.resolveRole(model, customModels()))) {
            return true;
        }
        if (LocalApiConfig.get().forceReasoning) {
            return true;
        }
        Object effort = request.opt("reasoning_effort");
        return effort != null && !"none".equals(String.valueOf(effort));
    }

    private static ApiContract.ToolPlan toolPlan(final JSONObject request) {
        boolean hasTools = request.optJSONArray("tools") != null
                || request.optJSONArray("functions") != null;
        return new ApiContract.ToolPlan() {
            @Override
            public boolean active() {
                return hasTools;
            }
        };
    }

    private static java.util.List<Object> customModels() {
        return ModelCatalog.parseCustom(LocalApiConfig.get().customModelsJson);
    }

    // ------------------------------------------------------------- rendering

    private static JSONObject chatCompletionBody(String id, String model,
            ApiContract.CompletionResult result) throws Exception {
        JSONObject message = new JSONObject();
        message.put("role", ROLE_ASSISTANT);
        if (!result.reasoning.isEmpty()) {
            message.put("reasoning_content", result.reasoning);
        }
        message.put("content", result.text);
        if (result.hasToolCalls()) {
            message.put("tool_calls", renderToolCalls(result));
        }
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", result.hasToolCalls() ? "tool_calls" : result.finishReason);
        JSONObject body = new JSONObject();
        body.put("id", id);
        body.put("object", OBJECT_CHAT_COMPLETION);
        body.put("created", System.currentTimeMillis() / 1000L);
        body.put("model", model);
        body.put("choices", new JSONArray(java.util.Collections.singletonList(choice)));
        body.put("usage", usage(result));
        return body;
    }

    private static JSONObject responseBody(String id, String model,
            ApiContract.CompletionResult result, String previousId) throws Exception {
        JSONArray output = new JSONArray();
        if (!result.reasoning.isEmpty()) {
            JSONObject reasoning = new JSONObject();
            reasoning.put("type", "reasoning");
            reasoning.put("summary", new JSONArray());
            output.put(reasoning);
        }
        JSONObject item = new JSONObject();
        item.put("type", "message");
        item.put("role", ROLE_ASSISTANT);
        item.put("status", "completed");
        JSONArray content = new JSONArray();
        JSONObject text = new JSONObject();
        text.put("type", "output_text");
        text.put("text", result.text);
        content.put(text);
        item.put("content", content);
        output.put(item);

        JSONObject body = new JSONObject();
        body.put("id", id);
        body.put("object", "response");
        body.put("created_at", System.currentTimeMillis() / 1000L);
        body.put("status", "completed");
        body.put("model", model);
        body.put("output", output);
        if (previousId != null && !previousId.isEmpty()) {
            body.put("previous_response_id", previousId);
        }
        body.put("usage", usage(result));
        return body;
    }

    private static JSONObject usage(ApiContract.CompletionResult result) throws Exception {
        JSONObject usage = new JSONObject();
        usage.put("prompt_tokens", 0);
        usage.put("completion_tokens", result.text.length() / 4);
        usage.put("total_tokens", result.text.length() / 4);
        return usage;
    }

    private static JSONObject chunkBuilder(String id, String model) throws Exception {
        JSONObject chunk = new JSONObject();
        chunk.put("id", id);
        chunk.put("object", OBJECT_CHAT_CHUNK);
        chunk.put("created", System.currentTimeMillis() / 1000L);
        if (model != null) {
            chunk.put("model", model);
        }
        return chunk;
    }

    private static JSONArray choices(JSONObject delta) throws Exception {
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("delta", delta);
        return new JSONArray(java.util.Collections.singletonList(choice));
    }

    private static JSONObject contentDelta(String text) throws Exception {
        JSONObject delta = new JSONObject();
        delta.put("content", text);
        return delta;
    }

    private static JSONObject reasoningDelta(String text) throws Exception {
        JSONObject delta = new JSONObject();
        delta.put("reasoning_content", text);
        return delta;
    }

    private static JSONObject finishDelta(String reason) throws Exception {
        JSONObject delta = new JSONObject();
        delta.put("finish_reason", reason);
        return delta;
    }

    private static JSONArray renderToolCalls(ApiContract.CompletionResult result)
            throws Exception {
        JSONArray calls = new JSONArray();
        for (int i = 0; i < result.toolCalls.size(); i++) {
            ApiContract.ToolCall call = result.toolCalls.get(i);
            JSONObject function = new JSONObject();
            function.put("name", call.name);
            function.put("arguments", call.arguments);
            JSONObject rendered = new JSONObject();
            rendered.put("index", i);
            rendered.put("id", call.id);
            rendered.put("type", call.type);
            rendered.put("function", function);
            calls.put(rendered);
        }
        return calls;
    }

    private static void emitToolCalls(HttpExchange exchange, String id,
            ApiContract.CompletionResult result) throws Exception {
        for (int i = 0; i < result.toolCalls.size(); i++) {
            ApiContract.ToolCall call = result.toolCalls.get(i);
            JSONObject function = new JSONObject();
            function.put("name", call.name);
            function.put("arguments", call.arguments);
            JSONObject tool = new JSONObject();
            tool.put("index", i);
            tool.put("id", call.id);
            tool.put("type", call.type);
            tool.put("function", function);
            JSONObject delta = new JSONObject();
            delta.put("tool_calls", new JSONArray(java.util.Collections.singletonList(tool)));
            exchange.writeSse(chunkBuilder(id, null).put("choices", choices(delta)).toString());
        }
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

    static JSONObject parseBody(HttpExchange exchange) throws Exception {
        byte[] body = exchange.body();
        if (body.length == 0) {
            return new JSONObject();
        }
        try {
            return new JSONObject(new String(body, "UTF-8"));
        } catch (org.json.JSONException failure) {
            throw new ApiContract.GatewayException(400, "invalid_json",
                    "invalid_request_error", "Request body is not valid JSON.");
        }
    }
}
