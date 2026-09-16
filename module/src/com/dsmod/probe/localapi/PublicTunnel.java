package com.dsmod.probe.localapi;

import android.content.Context;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes the loopback listener to a public HTTPS address.
 *
 * <p>Two independent strategies are supported, mirroring what users actually
 * need:
 * <ul>
 *   <li><b>Ephemeral SSH tunnel</b> - for occasional remote access. The phone
 *       opens an outbound SSH connection to a public gateway that hands back a
 *       random HTTPS URL. Nothing inbound has to be opened on the home router
 *       and the address expires on its own.</li>
 *   <li><b>Named tunnel agent</b> - for always-on access under your own domain.
 *       The tunnel is configured with a token plus the hostnames to expose, and
 *       it reconnects whenever the network changes.</li>
 * </ul>
 *
 * <p>Both are optional and off by default. The Local API keeps working on the
 * LAN with no tunnel at all.
 */
public final class PublicTunnel {

    public enum Transport {
        AUTO("auto"),
        HTTP2("http2"),
        QUIC("quic");

        public final String value;

        Transport(String value) {
            this.value = value;
        }

        /** Next value in the cycle used by the UI toggle. */
        public Transport next() {
            switch (this) {
                case AUTO: return HTTP2;
                case HTTP2: return QUIC;
                default: return AUTO;
            }
        }

        public static Transport fromValue(String raw) {
            for (Transport candidate : values()) {
                if (candidate.value.equals(raw)) {
                    return candidate;
                }
            }
            return AUTO;
        }
    }

    /** Observable state of either tunnel implementation. */
    public static final class Status {
        public final String state;
        public final String url;
        public final String error;

        public Status(String state, String url, String error) {
            this.state = state;
            this.url = url;
            this.error = error;
        }
    }

    private static final AtomicReference<Status> STATUS =
            new AtomicReference<Status>(new Status("off", null, null));

    private PublicTunnel() {
    }

    public static Status status() {
        return STATUS.get();
    }

    private static void publish(String state, String url, String error) {
        STATUS.set(new Status(state, url, error));
        LocalApiStats.log("tunnel " + state + (url == null ? "" : " " + url)
                + (error == null ? "" : " " + error));
    }

    /** Clears any published URL and stops both strategies. */
    public static synchronized void stop() {
        publish("off", null, null);
    }

    /**
     * Records a manually configured public origin, used when the user forwards
     * a port on their own router instead of tunnelling.
     */
    public static synchronized void setPublicRoot(String root) {
        LocalApiConfig.setPublicRootUrl(root == null ? "" : root.trim());
    }

    /** Currently configured public origin, or empty when none is set. */
    public static String publicRoot() {
        return LocalApiConfig.get().publicRootUrl;
    }

    /**
     * Validates a candidate public origin before it is stored.
     *
     * <p>Only http/https schemes with a non empty host are accepted, which
     * prevents the UI from storing something that will silently fail later.
     */
    public static String validateRoot(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        String value = raw.trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return null;
        }
        try {
            android.net.Uri uri = android.net.Uri.parse(value);
            if (uri.getHost() == null || uri.getHost().isEmpty()) {
                return null;
            }
            return value;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Working directory for downloaded tunnel helpers. */
    static File workDirectory(Context context) {
        Context app = context == null ? null : context.getApplicationContext();
        File base = app == null ? null : new File(app.getFilesDir(), "dq0_tunnel");
        if (base != null && !base.isDirectory() && !base.mkdirs()) {
            return null;
        }
        return base;
    }
}
