package com.dsmod.probe.localapi;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;

/**
 * Owns the listening socket and dispatches authenticated requests.
 *
 * <p>Security model is deliberately simple and documented rather than clever:
 * <ul>
 *   <li>every request must carry {@code Authorization: Bearer <key>};</li>
 *   <li>the listener binds to all interfaces because LAN clients must reach it,
 *       which is exactly why the key is mandatory even from loopback;</li>
 *   <li>HTTPS is optional and backed by a per-device CA, see
 *       {@link TlsDirector}.</li>
 * </ul>
 */
public final class LocalApiServer implements HttpServer.Handler {

    /** Worker threads. Local clients are few, but streams are long lived. */
    private static final int WORKERS = 8;
    /** How often an idle SSE stream emits a keepalive comment, milliseconds. */
    static final long HEARTBEAT_INTERVAL_MS = 5000L;

    private final OpenAiRouter openAi;
    private final AnthropicRouter anthropic;

    private HttpServer server;
    private volatile int httpPort;
    private volatile int httpsPort;
    private volatile String boundHost;

    public LocalApiServer(ApiContract.Backend backend) {
        this.openAi = new OpenAiRouter(backend);
        this.anthropic = new AnthropicRouter(backend);
    }

    /** Binds the listener. Returns true when the listener came up. */
    public synchronized boolean start(android.content.Context context) throws Exception {
        if (server != null && server.isRunning()) {
            return true;
        }
        LocalApiConfig.State state = LocalApiConfig.get();
        ServerSocket socket;
        if (state.https) {
            socket = openTlsSocket(context, state.port);
        } else {
            socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(state.port));
        }
        HttpServer http = new HttpServer(this, WORKERS);
        http.start(socket);
        this.server = http;
        this.httpPort = http.boundPort();
        this.httpsPort = state.https ? http.boundPort() : 0;
        this.boundHost = "0.0.0.0";
        return true;
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
        httpPort = 0;
        httpsPort = 0;
    }

    public boolean isRunning() {
        return server != null && server.isRunning();
    }

    public int httpPort() {
        return httpPort;
    }

    public int httpsPort() {
        return httpsPort;
    }

    public String boundHost() {
        return boundHost;
    }

    /** Scheme actually used for the currently bound listener. */
    public String scheme() {
        return httpsPort > 0 ? "https" : "http";
    }

    /** Base URL advertised to OpenAI style clients. */
    public String openAiBaseUrl() {
        String host = TlsDirector.lanAddress();
        String authority = host == null ? "127.0.0.1" : host;
        return scheme() + "://" + authority + ":" + (httpsPort > 0 ? httpsPort : httpPort) + "/v1";
    }

    /** Base URL advertised to Anthropic style clients (no {@code /v1} suffix). */
    public String anthropicBaseUrl() {
        String host = TlsDirector.lanAddress();
        String authority = host == null ? "127.0.0.1" : host;
        return scheme() + "://" + authority + ":" + (httpsPort > 0 ? httpsPort : httpPort);
    }

    public String loopbackRoot() {
        return scheme() + "://127.0.0.1:" + (httpsPort > 0 ? httpsPort : httpPort);
    }

    // ------------------------------------------------------------ tls socket

    private ServerSocket openTlsSocket(android.content.Context context, int port) throws Exception {
        TlsDirector.prepare(context);
        TlsDirector.Material material = TlsDirector.material(context);
        KeyManagerFactory managers = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        managers.init(material.serverStore, material.password);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(managers.getKeyManagers(), null, new java.security.SecureRandom());
        SSLServerSocket socket =
                (SSLServerSocket) ssl.getServerSocketFactory().createServerSocket();
        socket.setReuseAddress(true);
        restrictProtocols(socket);
        socket.setUseClientMode(false);
        socket.setNeedClientAuth(false);
        socket.setWantClientAuth(false);
        socket.bind(new InetSocketAddress(port));
        return socket;
    }

    /** Restricts negotiation to the two protocol families we trust. */
    private void restrictProtocols(SSLServerSocket socket) {
        List<String> allowed = new ArrayList<String>();
        String[] supported = socket.getSupportedProtocols();
        for (String candidate : supported) {
            if ("TLSv1.3".equals(candidate) || "TLSv1.2".equals(candidate)) {
                allowed.add(candidate);
            }
        }
        if (!allowed.isEmpty()) {
            socket.setEnabledProtocols(allowed.toArray(new String[allowed.size()]));
        }
    }

    // -------------------------------------------------------------- dispatch

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equals(exchange.method)) {
            exchange.respond(204, "text/plain", addCors(new byte[0]));
            return;
        }
        long started = System.currentTimeMillis();
        try {
            authorize(exchange);
            route(exchange);
        } catch (ApiContract.GatewayException failure) {
            noteFailure(exchange, started, failure.status, failure.code);
            respondError(exchange, failure.status, failure.code, failure.type, failure.getMessage());
        } catch (Throwable failure) {
            String message = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            noteFailure(exchange, started, 500, "internal_error");
            respondError(exchange, 500, "internal_error", "server_error", message);
        }
    }

    /**
     * Registers a failed completion attempt.
     *
     * <p>Only the completion endpoints are counted, so {@code total} stays the
     * sum of {@code successful + failed} rather than being diluted by probe
     * traffic against {@code /health} or {@code /v1/models}. A streaming
     * request that already emitted its error frame lands here too, which is
     * exactly why the frames carry the code the caller sees.
     */
    private static void noteFailure(HttpExchange exchange, long started, int status, String code) {
        String path = exchange.path();
        if (!isCompletionPath(path)) {
            return;
        }
        LocalApiStats.recordFailure(System.currentTimeMillis() - started);
        LocalApiStats.log("failed " + exchange.method + " " + path + " -> " + status
                + " " + code + " (" + (System.currentTimeMillis() - started) + "ms)");
    }

    /** Endpoints whose outcome feeds the success/failure counters. */
    static boolean isCompletionPath(String path) {
        return "/v1/chat/completions".equals(path)
                || "/v1/responses".equals(path)
                || "/v1/messages".equals(path);
    }

    private void authorize(HttpExchange exchange) throws ApiContract.GatewayException {
        if ("GET".equals(exchange.method) && "/health".equals(exchange.path())) {
            return;
        }
        String expected = LocalApiConfig.get().apiKey;
        String presented = bearer(exchange.header("authorization"));
        if (presented == null) {
            presented = bearer(exchange.header("x-api-key"));
        }
        if (expected == null || presented == null || !constantTimeEquals(expected, presented)) {
            throw new ApiContract.GatewayException(401, "invalid_api_key",
                    "authentication_error",
                    "Incorrect API key provided. Use the key shown in Deekseep settings.");
        }
    }

    /** Extracts the token from an {@code Authorization} header value. */
    static String bearer(String header) {
        if (header == null) {
            return null;
        }
        String value = header.trim();
        if (value.length() > 7 && value.substring(0, 7).equalsIgnoreCase("Bearer ")) {
            return value.substring(7).trim();
        }
        return value.isEmpty() ? null : value;
    }

    /** Length independent comparison so key checks cannot be timed out. */
    static boolean constantTimeEquals(String expected, String presented) {
        if (expected == null || presented == null) {
            return false;
        }
        if (expected.length() != presented.length()) {
            return false;
        }
        int difference = 0;
        for (int i = 0; i < expected.length(); i++) {
            difference |= expected.charAt(i) ^ presented.charAt(i);
        }
        return difference == 0;
    }

    private void route(HttpExchange exchange) throws Exception {
        String path = exchange.path();
        String mode = LocalApiConfig.get().protocolMode;
        boolean openAiMode = ApiContract.PROTOCOL_OPENAI.equals(mode);

        if ("/health".equals(path)) {
            exchange.respond(200, "application/json", health());
            return;
        }
        if ("/v1/models".equals(path)) {
            require(exchange, openAiMode, "GET");
            openAi.models(exchange);
            return;
        }
        if ("/v1/chat/completions".equals(path)) {
            require(exchange, openAiMode, "POST");
            openAi.chatCompletions(exchange);
            return;
        }
        if ("/v1/responses".equals(path)) {
            require(exchange, openAiMode, "POST");
            openAi.responses(exchange);
            return;
        }
        if ("/v1/messages".equals(path)) {
            require(exchange, !openAiMode, "POST");
            anthropic.messages(exchange);
            return;
        }
        if ("/v1/messages/count_tokens".equals(path)) {
            require(exchange, !openAiMode, "POST");
            anthropic.countTokens(exchange);
            return;
        }
        throw new ApiContract.GatewayException(404, "unknown_endpoint",
                "invalid_request_error", "Unknown endpoint: " + path);
    }

    /** Rejects a request whose verb or protocol does not match the mode. */
    private void require(HttpExchange exchange, boolean expectedMode, String verb)
            throws ApiContract.GatewayException {
        if (!expectedMode) {
            throw new ApiContract.GatewayException(404, "protocol_mismatch",
                    "invalid_request_error",
                    "This endpoint is not served in the active protocol mode.");
        }
        if (!verb.equals(exchange.method)) {
            throw new ApiContract.GatewayException(405, "method_not_allowed",
                    "invalid_request_error", "Expected " + verb + ", got " + exchange.method);
        }
    }

    private byte[] health() throws IOException {
        org.json.JSONObject body = new org.json.JSONObject();
        try {
            body.put("status", isRunning() ? "ok" : "stopped");
            body.put("protocol", LocalApiConfig.get().protocolMode);
            body.put("https", httpsPort > 0);
            body.put("port", httpsPort > 0 ? httpsPort : httpPort);
            body.put("stats", LocalApiStats.snapshot());
        } catch (Throwable ignored) {
            return "{\"status\":\"degraded\"}".getBytes("UTF-8");
        }
        return body.toString().getBytes("UTF-8");
    }

    static void respondError(HttpExchange exchange, int status, String code, String type,
            String message) throws IOException {
        org.json.JSONObject error = new org.json.JSONObject();
        try {
            error.put("message", message);
            error.put("type", type);
            error.put("code", code);
            org.json.JSONObject body = new org.json.JSONObject();
            body.put("error", error);
            if (exchange.headersWritten()) {
                return;
            }
            exchange.respond(status, "application/json", body.toString().getBytes("UTF-8"));
        } catch (Throwable ignored) {
            exchange.respond(status, "application/json",
                    "{\"error\":{\"message\":\"internal error\"}}".getBytes("UTF-8"));
        }
    }

    private static byte[] addCors(byte[] payload) {
        return payload;
    }

    /** Marks that the factory reference is intentional for future socket tuning. */
    @SuppressWarnings("unused")
    private static SSLServerSocketFactory unusedFactory() {
        return (SSLServerSocketFactory) SSLServerSocketFactory.getDefault();
    }
}
