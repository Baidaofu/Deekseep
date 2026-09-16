package com.dsmod.probe.localapi;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Persistent Local API settings.
 *
 * <p>Everything lives in a single JSON document inside the DeepSeek private
 * directory so that the whole configuration can be exported, imported, and
 * wiped together. Every accessor is defensive: a missing or corrupt document
 * silently degrades to defaults instead of breaking the host app.
 */
public final class LocalApiConfig {

    public static final int DEFAULT_PORT = 8765;
    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 65535;

    public static final int MIN_KEY_LENGTH = 8;
    public static final int MAX_KEY_LENGTH = 256;

    private static final String FILE = "dq0_config.json";

    private static final Object LOCK = new Object();
    private static volatile State state;
    private static File directory;
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<Listener>();

    /** Notified when any setting changes, so the server can react live. */
    public interface Listener {
        void onChanged(LocalApiConfig config);
    }

    /** Immutable snapshot of every setting. */
    public static final class State {
        public final boolean enabled;
        public final int port;
        public final String protocolMode;
        public final String apiKey;
        public final boolean https;
        public final boolean keepAliveNotification;
        public final boolean serialRequests;
        public final boolean antiCensor;
        public final boolean injectSystemPrompt;
        public final String systemPrompt;
        public final boolean longContextRelay;
        public final boolean forceReasoning;
        public final String customModelsJson;
        public final String publicRootUrl;
        public final boolean autoRecovery;

        State(boolean enabled, int port, String protocolMode, String apiKey, boolean https,
                boolean keepAliveNotification, boolean serialRequests, boolean antiCensor,
                boolean injectSystemPrompt, String systemPrompt, boolean longContextRelay,
                boolean forceReasoning, String customModelsJson, String publicRootUrl,
                boolean autoRecovery) {
            this.enabled = enabled;
            this.port = port;
            this.protocolMode = protocolMode;
            this.apiKey = apiKey;
            this.https = https;
            this.keepAliveNotification = keepAliveNotification;
            this.serialRequests = serialRequests;
            this.antiCensor = antiCensor;
            this.injectSystemPrompt = injectSystemPrompt;
            this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
            this.longContextRelay = longContextRelay;
            this.forceReasoning = forceReasoning;
            this.customModelsJson = customModelsJson == null ? "[]" : customModelsJson;
            this.publicRootUrl = publicRootUrl == null ? "" : publicRootUrl;
            this.autoRecovery = autoRecovery;
        }

        boolean get(String key) {
            if ("enabled".equals(key)) return enabled;
            if ("https".equals(key)) return https;
            if ("keepAliveNotification".equals(key)) return keepAliveNotification;
            if ("serialRequests".equals(key)) return serialRequests;
            if ("antiCensor".equals(key)) return antiCensor;
            if ("injectSystemPrompt".equals(key)) return injectSystemPrompt;
            if ("longContextRelay".equals(key)) return longContextRelay;
            if ("forceReasoning".equals(key)) return forceReasoning;
            if ("autoRecovery".equals(key)) return autoRecovery;
            throw new IllegalArgumentException(key);
        }
    }

    private LocalApiConfig() {
    }

    /** Installs the working directory. Must be called before any accessor. */
    public static void initialize(File filesDir) {
        directory = filesDir;
        load();
    }

    public static State get() {
        State snapshot = state;
        if (snapshot == null) {
            snapshot = load();
        }
        return snapshot;
    }

    public static void addListener(Listener listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    // ---------------------------------------------------------------- setters

    public static void setEnabled(boolean value) {
        update("enabled", value);
    }

    public static boolean setPort(int value) {
        if (value < MIN_PORT || value > MAX_PORT) {
            return false;
        }
        update("port", value);
        return true;
    }

    /** Returns null when the key is rejected by the length/charset rules. */
    public static String setCustomKey(String value) {
        Validation validation = validateKey(value);
        if (!validation.ok) {
            return validation.reason;
        }
        update("apiKey", value);
        return null;
    }

    /** Generates and stores a fresh random key. Returns the new value. */
    public static String rotateKey() {
        String key = generateKey();
        update("apiKey", key);
        return key;
    }

    public static boolean setProtocolMode(String value) {
        String normalised = ApiContract.PROTOCOL_ANTHROPIC.equals(value)
                ? ApiContract.PROTOCOL_ANTHROPIC : ApiContract.PROTOCOL_OPENAI;
        update("protocolMode", normalised);
        return true;
    }

    public static void setHttps(boolean value) {
        update("https", value);
    }

    public static void setSerialRequests(boolean value) {
        update("serialRequests", value);
    }

    public static void setAntiCensor(boolean value) {
        update("antiCensor", value);
    }

    public static void setInjectSystemPrompt(boolean value) {
        update("injectSystemPrompt", value);
    }

    public static void setSystemPrompt(String value) {
        update("systemPrompt", value == null ? "" : value);
    }

    public static void setLongContextRelay(boolean value) {
        update("longContextRelay", value);
    }

    public static void setForceReasoning(boolean value) {
        update("forceReasoning", value);
    }

    public static void setCustomModelsJson(String value) {
        update("customModelsJson", value == null ? "[]" : value);
    }

    public static void setPublicRootUrl(String value) {
        update("publicRootUrl", value == null ? "" : value);
    }

    public static void setAutoRecovery(boolean value) {
        update("autoRecovery", value);
    }

    // ------------------------------------------------------------- validation

    public static final class Validation {
        public final boolean ok;
        public final String reason;

        Validation(boolean ok, String reason) {
            this.ok = ok;
            this.reason = reason;
        }
    }

    /** API keys are printable ASCII without whitespace, 8-256 characters. */
    public static Validation validateKey(String value) {
        if (value == null) {
            return new Validation(false, "key_missing");
        }
        if (value.length() < MIN_KEY_LENGTH || value.length() > MAX_KEY_LENGTH) {
            return new Validation(false, "key_length");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= ' ' || c > '~') {
                return new Validation(false, "key_charset");
            }
        }
        return new Validation(true, null);
    }

    public static String generateKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder builder = new StringBuilder(64);
        for (int i = 0; i < bytes.length; i++) {
            builder.append(String.format(Locale.US, "%02x", bytes[i] & 0xff));
        }
        return builder.toString();
    }

    // ------------------------------------------------------------ persistence

    private static State load() {
        synchronized (LOCK) {
            State defaults = defaults();
            File file = file();
            if (file == null || !file.isFile()) {
                state = defaults;
                return defaults;
            }
            try {
                byte[] raw = readAll(file);
                JSONObject json = new JSONObject(new String(raw, "UTF-8"));
                state = fromJson(json, defaults);
            } catch (Throwable ignored) {
                state = defaults;
            }
            return state;
        }
    }

    private static State defaults() {
        return new State(false, DEFAULT_PORT, ApiContract.PROTOCOL_OPENAI, generateKey(), false,
                true, true, false, false, "", true, false, "[]", "", true);
    }

    private static State fromJson(JSONObject json, State fallback) {
        return new State(
                json.optBoolean("enabled", fallback.enabled),
                clampPort(json.optInt("port", fallback.port), fallback.port),
                ApiContract.PROTOCOL_ANTHROPIC.equals(
                        json.optString("protocolMode", fallback.protocolMode))
                                ? ApiContract.PROTOCOL_ANTHROPIC
                                : ApiContract.PROTOCOL_OPENAI,
                keyOrGenerate(json.optString("apiKey", null)),
                json.optBoolean("https", fallback.https),
                json.optBoolean("keepAliveNotification", fallback.keepAliveNotification),
                json.optBoolean("serialRequests", fallback.serialRequests),
                json.optBoolean("antiCensor", fallback.antiCensor),
                json.optBoolean("injectSystemPrompt", fallback.injectSystemPrompt),
                json.optString("systemPrompt", fallback.systemPrompt),
                json.optBoolean("longContextRelay", fallback.longContextRelay),
                json.optBoolean("forceReasoning", fallback.forceReasoning),
                json.optString("customModelsJson", fallback.customModelsJson),
                json.optString("publicRootUrl", fallback.publicRootUrl),
                json.optBoolean("autoRecovery", fallback.autoRecovery));
    }

    private static void update(String key, Object value) {
        State current = get();
        State next = with(current, key, value);
        synchronized (LOCK) {
            state = next;
            persist(next);
        }
        notifyChanged();
    }

    private static void notifyChanged() {
        for (Listener listener : LISTENERS) {
            try {
                listener.onChanged(null);
            } catch (Throwable ignored) {
                // A misbehaving listener must never break a settings change.
            }
        }
    }

    private static State with(State current, String key, Object value) {
        if ("port".equals(key)) {
            value = clampPort(((Integer) value).intValue(), current.port);
        }
        if ("apiKey".equals(key) && value != null) {
            Validation validation = validateKey(String.valueOf(value));
            if (!validation.ok) {
                return current;
            }
        }
        if ("https".equals(key)) value = (Boolean) value;
        boolean[] flags = new boolean[] {current.enabled, current.https,
                current.keepAliveNotification, current.serialRequests, current.antiCensor,
                current.injectSystemPrompt, current.longContextRelay, current.forceReasoning,
                current.autoRecovery};
        String[] flagKeys = new String[] {"enabled", "https", "keepAliveNotification",
                "serialRequests", "antiCensor", "injectSystemPrompt", "longContextRelay",
                "forceReasoning", "autoRecovery"};
        for (int i = 0; i < flagKeys.length; i++) {
            if (flagKeys[i].equals(key)) {
                flags[i] = ((Boolean) value).booleanValue();
            }
        }
        return new State(
                flags[0],
                key.equals("port") ? ((Integer) value).intValue() : current.port,
                key.equals("protocolMode") ? String.valueOf(value) : current.protocolMode,
                key.equals("apiKey") ? String.valueOf(value) : current.apiKey,
                flags[1], flags[2], flags[3], flags[4], flags[5],
                key.equals("systemPrompt") ? String.valueOf(value) : current.systemPrompt,
                flags[6], flags[7],
                key.equals("customModelsJson") ? String.valueOf(value)
                        : current.customModelsJson,
                key.equals("publicRootUrl") ? String.valueOf(value) : current.publicRootUrl,
                flags[8]);
    }

    private static void persist(State snapshot) {
        File file = file();
        if (file == null) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("enabled", snapshot.enabled);
            json.put("port", snapshot.port);
            json.put("protocolMode", snapshot.protocolMode);
            json.put("apiKey", snapshot.apiKey);
            json.put("https", snapshot.https);
            json.put("keepAliveNotification", snapshot.keepAliveNotification);
            json.put("serialRequests", snapshot.serialRequests);
            json.put("antiCensor", snapshot.antiCensor);
            json.put("injectSystemPrompt", snapshot.injectSystemPrompt);
            json.put("systemPrompt", snapshot.systemPrompt);
            json.put("longContextRelay", snapshot.longContextRelay);
            json.put("forceReasoning", snapshot.forceReasoning);
            json.put("customModelsJson", snapshot.customModelsJson);
            json.put("publicRootUrl", snapshot.publicRootUrl);
            json.put("autoRecovery", snapshot.autoRecovery);
            writeAtomic(file, json.toString().getBytes("UTF-8"));
        } catch (Throwable ignored) {
            // Persistence failures are non fatal; the in memory state stays live.
        }
    }

    private static File file() {
        return directory == null ? null : new File(directory, FILE);
    }

    private static int clampPort(int value, int fallback) {
        if (value < MIN_PORT || value > MAX_PORT) {
            return fallback;
        }
        return value;
    }

    private static String keyOrGenerate(String raw) {
        Validation validation = validateKey(raw);
        return validation.ok ? raw : generateKey();
    }

    static byte[] readAll(File file) throws Exception {
        byte[] buffer = new byte[(int) file.length()];
        FileInputStream input = new FileInputStream(file);
        try {
            int offset = 0;
            while (offset < buffer.length) {
                int read = input.read(buffer, offset, buffer.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            return buffer;
        } finally {
            input.close();
        }
    }

    /** Writes through a {@code .new} sibling then renames, so readers never see a torn file. */
    static void writeAtomic(File target, byte[] payload) throws Exception {
        File staged = new File(target.getPath() + ".new");
        FileOutputStream output = new FileOutputStream(staged, false);
        try {
            output.write(payload);
            output.getFD().sync();
        } finally {
            output.close();
        }
        if (target.exists() && !target.delete()) {
            throw new java.io.IOException("could not replace " + target.getName());
        }
        if (!staged.renameTo(target)) {
            throw new java.io.IOException("could not commit " + target.getName());
        }
    }

    /** Serialises the current state as indented JSON, for the export dialog. */
    public static String exportJson() {
        State snapshot = get();
        try {
            JSONObject json = new JSONObject();
            json.put("enabled", snapshot.enabled);
            json.put("port", snapshot.port);
            json.put("protocolMode", snapshot.protocolMode);
            json.put("https", snapshot.https);
            json.put("serialRequests", snapshot.serialRequests);
            json.put("antiCensor", snapshot.antiCensor);
            json.put("injectSystemPrompt", snapshot.injectSystemPrompt);
            json.put("systemPrompt", snapshot.systemPrompt);
            json.put("longContextRelay", snapshot.longContextRelay);
            json.put("forceReasoning", snapshot.forceReasoning);
            json.put("customModels", new JSONArray(snapshot.customModelsJson));
            json.put("publicRootUrl", snapshot.publicRootUrl);
            json.put("autoRecovery", snapshot.autoRecovery);
            return json.toString(2);
        } catch (Throwable ignored) {
            return "{}";
        }
    }
}
