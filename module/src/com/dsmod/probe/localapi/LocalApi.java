package com.dsmod.probe.localapi;

import android.content.Context;

/**
 * Facade the Xposed entry point uses to drive the Local API.
 *
 * <p>Everything else in this package is an implementation detail; this class is
 * the seam between the module lifecycle and the API surface. Calls are safe to
 * make repeatedly and from any thread.
 */
public final class LocalApi {

    private static final Object LOCK = new Object();
    private static volatile LocalApiServer server;
    private static volatile HostBackend backend;

    private LocalApi() {
    }

    /** Installs working directories. Must run once before anything else. */
    public static void initialize(Context context) {
        Context app = context == null ? null : context.getApplicationContext();
        if (app == null) {
            return;
        }
        LocalApiConfig.initialize(app.getFilesDir());
        LocalApiStats.initialize(app.getFilesDir());
    }

    /** Brings the listener up. Returns true when it is serving. */
    public static boolean start(Context context) throws Exception {
        Context app = context == null ? null : context.getApplicationContext();
        if (app == null) {
            return false;
        }
        initialize(app);
        synchronized (LOCK) {
            if (server != null && server.isRunning()) {
                return true;
            }
            HostBackend nativeBackend = backend(app);
            LocalApiServer created = new LocalApiServer(nativeBackend);
            if (!created.start(app)) {
                return false;
            }
            server = created;
            LocalApiConfig.setEnabled(true);
            LocalApiStats.log("listener started on " + created.loopbackRoot());
            return true;
        }
    }

    /** Stops the listener and releases every socket. */
    public static void stop() {
        synchronized (LOCK) {
            LocalApiServer current = server;
            if (current != null) {
                current.stop();
                LocalApiStats.log("listener stopped");
            }
            server = null;
            LocalApiConfig.setEnabled(false);
        }
    }

    public static boolean isRunning() {
        LocalApiServer current = server;
        return current != null && current.isRunning();
    }

    /**
     * Reconciles the listener with the saved configuration.
     *
     * <p>Called from the host {@code Activity.onResume} hook, which is the first
     * point where an application context exists and the only place that runs
     * again after the user flips the switch from another process. Everything is
     * best effort: a failure to bind must never take the host application down,
     * so problems are recorded in the API log instead of propagating.
     */
    public static void onHostResumed(Context context) {
        Context app = context == null ? null : context.getApplicationContext();
        if (app == null) {
            return;
        }
        try {
            initialize(app);
            LocalApiConfig.State state = LocalApiConfig.get();
            if (state.enabled) {
                if (!isRunning()) {
                    start(app);
                }
                if (state.keepAliveNotification) {
                    try {
                        app.startService(KeepAliveService.createIntent(app));
                    } catch (Throwable ignored) {
                        // Foreground starts are rejected in some backgrounds; the
                        // listener itself is unaffected.
                    }
                }
            } else if (isRunning()) {
                stop();
                try {
                    app.stopService(KeepAliveService.createIntent(app));
                } catch (Throwable ignored) {
                    // Nothing to stop when the service was never bound.
                }
            }
        } catch (Throwable failure) {
            try {
                LocalApiStats.log("autostart failed: " + failure);
            } catch (Throwable ignored) {
                // Logging is the last resort; never let it throw either.
            }
        }
    }

    /** Multi line status shown on the settings screen. */
    public static String status(Context context) {
        StringBuilder builder = new StringBuilder();
        LocalApiServer current = server;
        LocalApiConfig.State state = LocalApiConfig.get();
        builder.append("enabled=").append(state.enabled);
        builder.append(" running=").append(isRunning());
        builder.append(" protocol=").append(state.protocolMode);
        if (current != null) {
            builder.append(" scheme=").append(current.scheme());
            builder.append(" host=").append(current.boundHost());
            builder.append(" port=").append(current.httpPort() == 0
                    ? current.httpsPort() : current.httpPort());
            builder.append('\n').append("openai=").append(current.openAiBaseUrl());
            builder.append('\n').append("anthropic=").append(current.anthropicBaseUrl());
        }
        builder.append('\n').append("keepalive=").append(KeepAliveService.isRunning());
        builder.append('\n').append(LocalApiStats.summary());
        String root = PublicTunnel.publicRoot();
        if (root != null && !root.isEmpty()) {
            builder.append('\n').append("public=").append(root);
        }
        return builder.toString();
    }

    /** OpenAI style base URL, or null while stopped. */
    public static String openAiBaseUrl() {
        LocalApiServer current = server;
        return current == null ? null : current.openAiBaseUrl();
    }

    /** Anthropic style base URL, or null while stopped. */
    public static String anthropicBaseUrl() {
        LocalApiServer current = server;
        return current == null ? null : current.anthropicBaseUrl();
    }

    /** Human readable description written to {@code Deekseep_API.txt}. */
    public static String connectionCard(Context context) {
        LocalApiServer current = server;
        if (current == null) {
            return "Local API is stopped";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("Deekseep Local API\n");
        builder.append("API key: ").append(LocalApiConfig.get().apiKey).append('\n');
        builder.append("Protocol: ").append(LocalApiConfig.get().protocolMode).append('\n');
        builder.append("OpenAI base URL: ").append(current.openAiBaseUrl()).append('\n');
        builder.append("Anthropic base URL: ").append(current.anthropicBaseUrl()).append('\n');
        if (PublicTunnel.publicRoot() != null && !PublicTunnel.publicRoot().isEmpty()) {
            builder.append("Public origin: ").append(PublicTunnel.publicRoot()).append('\n');
        }
        return builder.toString();
    }

    /** Replaces the bridge implementation; used by tests and by the host hook. */
    public static void setBackend(HostBackend custom) {
        backend = custom;
    }

    private static HostBackend backend(Context app) {
        HostBackend existing = backend;
        if (existing != null) {
            return existing;
        }
        HostBackend created = new HostBackend(app, new ReflectiveBridge());
        backend = created;
        return created;
    }
}
