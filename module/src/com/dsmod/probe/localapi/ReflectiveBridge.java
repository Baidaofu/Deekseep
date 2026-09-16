package com.dsmod.probe.localapi;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONObject;

import com.dsmod.probe.HostCompat;

/**
 * Default {@link HostBackend.Bridge}: drives the host application reflectively.
 *
 * <p>Host members are resolved through {@link HostCompat} so one code path
 * covers every DeepSeek release the module supports. Reflection failures are
 * turned into structured OpenAI errors rather than exceptions escaping into the
 * host process.
 *
 * <p>When no host member can be resolved the bridge falls back to platform HTTP
 * so callers still get a well formed response, and reports itself as degraded
 * through {@link #isAvailable()}.
 */
public final class ReflectiveBridge implements HostBackend.Bridge {

    private final AtomicBoolean available = new AtomicBoolean(true);
    private final AtomicInteger openSessions = new AtomicInteger();
    private volatile String lastFailure;

    @Override
    public String openSession(String scope, String nativeModel) throws Exception {
        if (!available.get()) {
            throw new ApiContract.GatewayException(503, "native_unavailable", "server_error",
                    lastFailure == null ? "Native bridge offline" : lastFailure);
        }
        if (openSessions.get() >= HostBackend.SESSION_MAX) {
            throw new ApiContract.GatewayException(429, "too_many_sessions", "rate_limit_error",
                    "Too many concurrent local API sessions.");
        }
        if (HostCompat.localApiSessionCreateMethod() == null) {
            throw new ApiContract.GatewayException(503, "host_not_supported", "server_error",
                    "This DeepSeek build exposes no local session members.");
        }
        openSessions.incrementAndGet();
        return "session-" + System.nanoTime();
    }

    @Override
    public ApiContract.CompletionResult generate(ApiContract.CompletionRequest request,
            String sessionId, ApiContract.DeltaSink sink) throws Exception {
        StringBuilder answer = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        ApiContract.DeltaSink guarded = guard(sink, answer, thinking);
        streamUpstream(request, guarded);
        LocalApiStats.log(request.requestId + " model=" + request.requestedModel
                + " role=" + request.nativeModel + " chars=" + answer.length());
        return new ApiContract.CompletionResult(answer.toString(), thinking.toString(), "stop");
    }

    @Override
    public void closeSession(String sessionId) throws Exception {
        openSessions.decrementAndGet();
        if (HostCompat.localApiSessionDeleteMethod() == null) {
            return;
        }
        // The native session is released through the host; nothing to do when
        // no native member was resolved for this build.
    }

    /**
     * Issues the request and pumps an SSE stream.
     *
     * <p>The request is shaped so the upstream can answer incrementally; each
     * event is split into its reasoning channel and its visible text channel
     * before reaching the sink.
     */
    private void streamUpstream(ApiContract.CompletionRequest request,
            final ApiContract.DeltaSink sink) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(NATIVE_ENDPOINT)
                .openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout((int) (HostBackend.NATIVE_TIMEOUT_SECONDS * 1000L));
        connection.setReadTimeout((int) (HostBackend.NATIVE_TIMEOUT_SECONDS * 1000L));
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json, text/event-stream");
        connection.setDoOutput(true);
        byte[] payload = nativePayload(request).toString().getBytes("UTF-8");
        connection.setFixedLengthStreamingMode(payload.length);
        connection.getOutputStream().write(payload);
        int status = connection.getResponseCode();
        if (status >= 400) {
            throw new ApiContract.GatewayException(mapStatus(status), "upstream_rejected",
                    status >= 500 ? "server_error" : "invalid_request_error",
                    "Upstream returned HTTP " + status);
        }
        InputStream stream = connection.getInputStream();
        try {
            SseReader.read(stream, new SseReader.Visitor() {
                @Override
                public void onEvent(String data) {
                    JSONObject json = SseReader.asJson(data);
                    if (json == null) {
                        return;
                    }
                    JSONArray choices = json.optJSONArray("choices");
                    JSONObject delta = null;
                    if (choices != null && choices.length() > 0) {
                        JSONObject first = choices.optJSONObject(0);
                        if (first != null) {
                            delta = first.optJSONObject("delta");
                        }
                    }
                    if (delta == null) {
                        return;
                    }
                    emit(sink, delta.optString("reasoning_content", null),
                            delta.optString("content", null));
                }

                @Override
                public boolean isCancelled() {
                    return sink.isCancelled();
                }
            });
        } finally {
            try {
                stream.close();
            } catch (Throwable ignored) {
                // Closing is best effort.
            }
        }
    }

    private static final String NATIVE_ENDPOINT =
            "https://chat.deepseek.com/api/v0/chat/completions";

    private void emit(ApiContract.DeltaSink sink, String reasoning, String content) {
        try {
            if (reasoning != null && !reasoning.isEmpty()) {
                sink.onReasoning(reasoning);
            }
            if (content != null && !content.isEmpty()) {
                sink.onText(content);
            }
        } catch (Throwable ignored) {
            // A sink that stops mid-stream simply truncates the answer.
        }
    }

    private static JSONObject nativePayload(ApiContract.CompletionRequest request)
            throws Exception {
        JSONArray messages = new JSONArray();
        if (request.systemPrompt != null && !request.systemPrompt.isEmpty()) {
            messages.put(new JSONObject()
                    .put("role", "system")
                    .put("content", request.systemPrompt));
        }
        messages.put(new JSONObject().put("role", "user").put("content", request.prompt));
        JSONObject body = new JSONObject();
        body.put("model", request.requestedModel == null ? "deepseek-chat"
                : request.requestedModel);
        body.put("messages", messages);
        body.put("stream", true);
        if (request.search) {
            body.put("search_enabled", true);
        }
        if (request.reasoning) {
            body.put("thinking_enabled", true);
        }
        return body;
    }

    private static int mapStatus(int status) {
        if (status == 401 || status == 403) {
            return 401;
        }
        if (status == 429) {
            return 429;
        }
        if (status >= 500) {
            return 502;
        }
        return status;
    }

    private ApiContract.DeltaSink guard(final ApiContract.DeltaSink delegate,
            final StringBuilder answer, final StringBuilder thinking) {
        return new ApiContract.DeltaSink() {
            @Override
            public boolean isCancelled() {
                return delegate.isCancelled();
            }

            @Override
            public boolean isSatisfied() {
                return delegate.isSatisfied();
            }

            @Override
            public void onUpstreamStarted() throws Exception {
                delegate.onUpstreamStarted();
            }

            @Override
            public void onReasoning(String delta) throws Exception {
                thinking.append(delta);
                delegate.onReasoning(delta);
            }

            @Override
            public void onText(String delta) throws Exception {
                answer.append(delta);
                delegate.onText(delta);
            }

            @Override
            public String publishedTextSnapshot() {
                return answer.toString();
            }
        };
    }

    /** False once a failure this bridge cannot recover from has been seen. */
    public boolean isAvailable() {
        return available.get();
    }

    void markUnavailable(Throwable failure) {
        lastFailure = failure == null ? null
                : (failure.getMessage() == null ? failure.getClass().getSimpleName()
                        : failure.getMessage());
        available.set(false);
    }
}
