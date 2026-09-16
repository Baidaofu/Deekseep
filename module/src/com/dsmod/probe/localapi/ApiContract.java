package com.dsmod.probe.localapi;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Wire-level contract shared by every Local API transport.
 *
 * <p>The contract deliberately mirrors the observable behaviour of the DeepSeek
 * Local API so that OpenAI and Anthropic clients can talk to it unchanged:
 * messages are normalised into a {@link CompletionRequest}, the backend streams
 * partial answers into a {@link DeltaSink}, and everything that can fail is
 * reported as a {@link GatewayException} carrying an HTTP status plus an
 * OpenAI-shaped {@code type}/{@code code} pair.
 *
 * <p>This file is an independent implementation. It contains no code copied
 * from any other project.
 */
public final class ApiContract {

    /** Hard wall clock budget for a single generation, in milliseconds. */
    public static final long COMPLETION_REQUEST_BUDGET_MS = 600_000L;

    public static final String PROTOCOL_OPENAI = "openai";
    public static final String PROTOCOL_ANTHROPIC = "anthropic";

    /** Tags understood on {@code /v1/models}. */
    public static final String MODEL_DEFAULT = "default";
    public static final String MODEL_EXPERT = "expert";
    public static final String MODEL_VISION = "vision";

    private ApiContract() {
    }

    /** Produces a completion. Implementations must be safe to call concurrently. */
    public interface Backend {
        CompletionResult complete(CompletionRequest request, DeltaSink sink) throws Exception;

        boolean isReady();

        String readinessDetail();
    }

    /**
     * Receives incremental generation output.
     *
     * <p>{@code reasoning} and {@code text} are kept apart because DeepSeek
     * emits the chain of thought on a separate channel from the visible answer.
     */
    public interface DeltaSink {

        boolean isCancelled();

        /** True once enough text has been produced to satisfy a short answer. */
        default boolean isSatisfied() {
            return false;
        }

        /** Called once the upstream request has been accepted. */
        default void onUpstreamStarted() throws Exception {
        }

        void onReasoning(String delta) throws Exception;

        void onText(String delta) throws Exception;

        /** Snapshot of already published text, used to avoid re-emitting on retry. */
        default String publishedTextSnapshot() {
            return "";
        }
    }

    /** Every API failure that can be reported to a client. */
    public static final class GatewayException extends Exception {
        private static final long serialVersionUID = 1L;

        public final int status;
        public final String code;
        public final String type;

        public GatewayException(int status, String code, String type, String message) {
            super(message);
            this.status = status;
            this.code = code;
            this.type = type;
        }

        public GatewayException(int status, String code, String message) {
            this(status, code, "invalid_request_error", message);
        }
    }

    /** OpenAI {@code error} envelope payload. */
    public static final class ErrorBody {
        public String message = "";
        public String type = "server_error";
        public String param = null;
        public String code = null;

        public ErrorBody() {
        }

        public ErrorBody(String message, String type, String code) {
            this.message = message == null ? "" : message;
            this.type = type;
            this.code = code;
        }
    }

    /** Result of one generation round. */
    public static final class CompletionResult {
        public final String text;
        public final String reasoning;
        public final String finishReason;
        public final List<ToolCall> toolCalls;

        public CompletionResult(String text, String reasoning, String finishReason,
                List<ToolCall> toolCalls) {
            this.text = text == null ? "" : text;
            this.reasoning = reasoning == null ? "" : reasoning;
            this.finishReason = finishReason == null || finishReason.isEmpty()
                    ? "stop" : finishReason;
            this.toolCalls = toolCalls == null || toolCalls.isEmpty()
                    ? Collections.<ToolCall>emptyList()
                    : Collections.unmodifiableList(
                            new java.util.ArrayList<ToolCall>(toolCalls));
        }

        public CompletionResult(String text, String reasoning, String finishReason) {
            this(text, reasoning, finishReason, Collections.<ToolCall>emptyList());
        }

        public boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }
    }

    /** Normalised function/tool invocation produced by the model. */
    public static final class ToolCall {
        public final String id;
        public final String type;
        public final String name;
        public final String arguments;

        public ToolCall(String id, String type, String name, String arguments) {
            this.id = id;
            this.type = type == null || type.isEmpty() ? "function" : type;
            this.name = name;
            this.arguments = arguments == null ? "{}" : arguments;
        }
    }

    /** Whether the agent tool loop is armed for this request. */
    public interface ToolPlan {
        boolean active();
    }

    /** Tool de-duplication state carried across loop iterations. */
    public static final class ToolHistory {
        public final Map<String, String> knownCalls;
        public final Set<String> completedCalls;
        public final Set<String> repeatableCalls;

        public ToolHistory(Map<String, String> known, Set<String> completed) {
            this.knownCalls = new HashMap<String, String>();
            this.completedCalls = new HashSet<String>();
            this.repeatableCalls = new HashSet<String>();
            if (known != null) {
                this.knownCalls.putAll(known);
            }
            if (completed != null) {
                this.completedCalls.addAll(completed);
            }
        }

        public ToolHistory() {
            this(null, null);
        }
    }

    /** Normalised request handed to the backend. */
    public static final class CompletionRequest {
        public final String requestId;
        public final String requestedModel;
        public final String nativeModel;
        public final String systemPrompt;
        public final String prompt;
        public final boolean reasoning;
        public final boolean search;
        public final int maxOutputTokens;
        public final ToolPlan toolPlan;
        public final String previousResponseId;
        public final boolean responsesApi;
        public final String sessionScope;
        public final String nativeConversationId;
        public final Integer nativeParentMessageId;
        public final List<String> fileIds;
        public final Map<String, String> knownToolCalls;
        public final Set<String> completedToolCalls;
        public final Set<String> repeatableCompletedToolCalls;
        public final long deadlineAtMs;

        @SuppressWarnings("unchecked")
        public CompletionRequest(String requestId, String requestedModel, String nativeModel,
                String systemPrompt, String prompt, boolean reasoning, boolean search,
                int maxOutputTokens, ToolPlan toolPlan, String previousResponseId,
                boolean responsesApi, String sessionScope, String nativeConversationId,
                Integer nativeParentMessageId, List<String> fileIds,
                Map<String, String> knownToolCalls, Set<String> completedToolCalls,
                Set<String> repeatableCompletedToolCalls, long deadlineAtMs) {
            this.requestId = requestId;
            this.requestedModel = requestedModel;
            this.nativeModel = nativeModel;
            this.systemPrompt = systemPrompt;
            this.prompt = prompt;
            this.reasoning = reasoning;
            this.search = search;
            this.maxOutputTokens = maxOutputTokens;
            this.toolPlan = toolPlan;
            this.previousResponseId = previousResponseId;
            this.responsesApi = responsesApi;
            this.sessionScope = sessionScope;
            this.nativeConversationId =
                    (nativeConversationId == null || nativeConversationId.isEmpty())
                            ? null : nativeConversationId;
            this.nativeParentMessageId =
                    (nativeParentMessageId == null || nativeParentMessageId.intValue() <= 0)
                            ? null : nativeParentMessageId;
            this.fileIds = (fileIds == null || fileIds.isEmpty())
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new java.util.ArrayList<String>(fileIds));
            this.knownToolCalls = (knownToolCalls == null || knownToolCalls.isEmpty())
                    ? Collections.<String, String>emptyMap()
                    : Collections.unmodifiableMap(new HashMap<String, String>(knownToolCalls));
            this.completedToolCalls =
                    (completedToolCalls == null || completedToolCalls.isEmpty())
                            ? Collections.<String>emptySet()
                            : Collections.unmodifiableSet(
                                    new HashSet<String>(completedToolCalls));
            this.repeatableCompletedToolCalls =
                    (repeatableCompletedToolCalls == null
                            || repeatableCompletedToolCalls.isEmpty())
                                    ? Collections.<String>emptySet()
                                    : Collections.unmodifiableSet(new HashSet<String>(
                                            repeatableCompletedToolCalls));
            this.deadlineAtMs = deadlineAtMs;
        }

        public CompletionRequest(String requestId, String requestedModel, String nativeModel,
                String systemPrompt, String prompt, boolean reasoning, boolean search,
                int maxOutputTokens, ToolPlan toolPlan, String previousResponseId,
                boolean responsesApi) {
            this(requestId, requestedModel, nativeModel, systemPrompt, prompt, reasoning,
                    search, maxOutputTokens, toolPlan, previousResponseId, responsesApi, null,
                    null, null, Collections.<String>emptyList(),
                    Collections.<String, String>emptyMap(), Collections.<String>emptySet(),
                    Collections.<String>emptySet(),
                    System.currentTimeMillis() + COMPLETION_REQUEST_BUDGET_MS);
        }

        public boolean toolsActive() {
            return toolPlan != null && toolPlan.active();
        }

        /** True when the request targets a background helper model. */
        public boolean auxiliary() {
            if (requestedModel == null) {
                return false;
            }
            String lower = requestedModel.toLowerCase(Locale.US);
            return lower.equals("deepseek-aux") || lower.startsWith("deepseek-aux-");
        }

        /** True when the request should drive the visible agent loop. */
        public boolean interactiveAgent() {
            return toolsActive() && !auxiliary();
        }

        public long remainingMs() {
            return deadlineAtMs - System.currentTimeMillis();
        }

        public CompletionRequest withPrompt(String newPrompt) {
            return new CompletionRequest(requestId, requestedModel, nativeModel, systemPrompt,
                    newPrompt, reasoning, search, maxOutputTokens, toolPlan, previousResponseId,
                    responsesApi, sessionScope, nativeConversationId, nativeParentMessageId,
                    fileIds, knownToolCalls, completedToolCalls, repeatableCompletedToolCalls,
                    deadlineAtMs);
        }

        public CompletionRequest withNativeConversation(String conversationId,
                Integer parentMessageId) {
            return new CompletionRequest(requestId, requestedModel, nativeModel, systemPrompt,
                    prompt, reasoning, search, maxOutputTokens, toolPlan, previousResponseId,
                    responsesApi, sessionScope, conversationId, parentMessageId, fileIds,
                    knownToolCalls, completedToolCalls, repeatableCompletedToolCalls,
                    deadlineAtMs);
        }

        public CompletionRequest withToolHistory(ToolHistory history) {
            if (history == null) {
                return this;
            }
            return new CompletionRequest(requestId, requestedModel, nativeModel, systemPrompt,
                    prompt, reasoning, search, maxOutputTokens, toolPlan, previousResponseId,
                    responsesApi, sessionScope, nativeConversationId, nativeParentMessageId,
                    fileIds, history.knownCalls, history.completedCalls, history.repeatableCalls,
                    deadlineAtMs);
        }
    }
}
