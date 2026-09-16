package com.dsmod.probe.localapi;

import android.content.Context;

import java.util.List;
import java.util.Locale;

/**
 * Drives a conversation through the DeepSeek application's own networking.
 *
 * <p>The module never reimplements DeepSeek's protocol:<｜hy_place▁holder▁no▁813｜> tokens, signing,
 * experiment flags and server routing all live inside the host application and
 * change every release. Instead we open a native session the host itself owns,
 * send the composed prompt through it, and read the stream the host already
 * knows how to parse. That keeps Local API traffic indistinguishable from a
 * normal chat as far as the server is concerned, and it survives app updates
 * better than duplicating their request format.
 *
 * <p>Two integration points exist:
 * <ul>
 *   <li>{@link Bridge} - the reflective driver that talks to the host. The
 *       provided {@link ReflectiveBridge} resolves host members through
 *       {@link com.dsmod.probe.HostCompat}.</li>
 * </ul>
 */
public final class HostBackend implements ApiContract.Backend {

    /** Maximum wait for the first upstream byte, milliseconds. */
    public static final long NATIVE_TIMEOUT_SECONDS = 120L;
    /** Total budget for one completion, milliseconds. */
    public static final long REQUEST_BUDGET_MS = ApiContract.COMPLETION_REQUEST_BUDGET_MS;
    /** Queue poll interval while waiting for a native slot, milliseconds. */
    public static final long QUEUE_POLL_MS = 250L;
    /** Wait allowance before a queued chat request is abandoned, milliseconds. */
    public static final long CHAT_QUEUE_WAIT_MS = 30_000L;
    /** Wait allowance for background helper requests, milliseconds. */
    public static final long AUX_QUEUE_WAIT_MS = 8_000L;
    /** Wait allowance for agent requests, milliseconds. */
    public static final long AGENT_QUEUE_WAIT_MS = 60_000L;
    /** How many native requests may be in flight at once. */
    public static final int NATIVE_PERMIT_COUNT = 8;
    /** Minimum delay between consecutive native starts, milliseconds. */
    public static final long MIN_START_INTERVAL_MS = 200L;
    /** Cool down after a normal completion, milliseconds. */
    public static final long NORMAL_COOLDOWN_MS = 500L;
    /** Cool down before handing a tool result back, milliseconds. */
    public static final long TOOL_HANDOFF_COOLDOWN_MS = 250L;

    /** Native session policy. */
    public static final int SESSION_MAX = 32;
    public static final long SESSION_TTL_MS = 86_400_000L;

    /** Session marker so internal traffic never pollutes the user's history UI. */
    public static final String SESSION_META = "__deekseep_meta";
    /** Marker applied to sessions that have been retired. */
    public static final String SESSION_RETIRED = "__deekseep_retired";

    /** Toggles that modify how a prompt is composed. */
    public interface Bridge {
        /** Opens (or reuses) a native session for the request scope. */
        String openSession(String scope, String nativeModel) throws Exception;

        /** Sends {@code prompt} and pumps deltas into {@code sink} until done. */
        ApiContract.CompletionResult generate(ApiContract.CompletionRequest request,
                String sessionId, ApiContract.DeltaSink sink) throws Exception;

        /** Closes a session and any server side counterpart. */
        void closeSession(String sessionId) throws Exception;
    }

    private final Context context;
    private final Bridge bridge;
    private long lastStartAtMs;

    public HostBackend(Context context, Bridge bridge) {
        this.context = context == null ? null : context.getApplicationContext();
        this.bridge = bridge;
    }

    @Override
    public boolean isReady() {
        if (bridge == null) {
            return false;
        }
        return true;
    }

    @Override
    public String readinessDetail() {
        if (bridge == null) {
            return "No native bridge is available";
        }
        return "Ready";
    }

    @Override
    public ApiContract.CompletionResult complete(ApiContract.CompletionRequest request,
            ApiContract.DeltaSink sink) throws Exception {
        if (bridge == null) {
            throw new ApiContract.GatewayException(503, "backend_unavailable",
                    "server_error", "No native bridge is available.");
        }
        if (request == null || request.prompt == null || request.prompt.trim().isEmpty()) {
            throw new ApiContract.GatewayException(400, "empty_prompt",
                    "invalid_request_error", "The request contained no message content.");
        }
        if (request.remainingMs() <= 0) {
            throw new ApiContract.GatewayException(408, "request_expired",
                    "timeout", "The request budget elapsed before it started.");
        }
        awaitStartSlot();
        String sessionId = null;
        try {
            sessionId = bridge.openSession(request.sessionScope, request.nativeModel);
            ApiContract.CompletionRequest effective = applyPromptPolicy(request);
            try {
                sink.onUpstreamStarted();
            } catch (Throwable ignored) {
                // A sink that does not care about this signal is fine.
            }
            ApiContract.CompletionResult result = bridge.generate(effective, sessionId, sink);
            LocalApiStats.log(request.requestId + " model=" + request.requestedModel
                    + " role=" + request.nativeModel
                    + " chars=" + (result.text == null ? 0 : result.text.length()));
            return result;
        } finally {
            if (sessionId != null) {
                try {
                    bridge.closeSession(sessionId);
                } catch (Throwable ignored) {
                    // Session cleanup failures must not break a completed answer.
                }
            }
        }
    }

    /** Serialise native starts so the upstream never sees a burst. */
    private void awaitStartSlot() {
        while (true) {
            long now = System.currentTimeMillis();
            long earliest = lastStartAtMs + MIN_START_INTERVAL_MS;
            if (now >= earliest) {
                lastStartAtMs = now;
                return;
            }
            sleepQuietly(earliest - now);
        }
    }

    /** Applies the configurable system prompt injection and related policies. */
    private ApiContract.CompletionRequest applyPromptPolicy(
            ApiContract.CompletionRequest request) {
        LocalApiConfig.State state = LocalApiConfig.get();
        if (!state.injectSystemPrompt || state.systemPrompt.isEmpty()) {
            return request;
        }
        String merged = request.systemPrompt == null || request.systemPrompt.isEmpty()
                ? state.systemPrompt
                : state.systemPrompt + "\n\n" + request.systemPrompt;
        return new ApiContract.CompletionRequest(request.requestId, request.requestedModel,
                request.nativeModel, merged, request.prompt, request.reasoning, request.search,
                request.maxOutputTokens, request.toolPlan, request.previousResponseId,
                request.responsesApi, request.sessionScope, request.nativeConversationId,
                request.nativeParentMessageId, request.fileIds, request.knownToolCalls,
                request.completedToolCalls, request.repeatableCompletedToolCalls,
                request.deadlineAtMs);
    }

    /** True when the modeled role wants images attached. */
    static boolean wantsVision(String role, List<String> fileIds) {
        return fileIds != null && !fileIds.isEmpty()
                && ApiContract.MODEL_VISION.equals(String.valueOf(role).toLowerCase(Locale.US));
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
