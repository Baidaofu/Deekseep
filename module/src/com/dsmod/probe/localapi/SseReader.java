package com.dsmod.probe.localapi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Minimal Server-Sent Events reader used to consume a streaming upstream.
 *
 * <p>Only the parts of the event stream that actually matter are handled:
 * blank lines terminate an event, comment lines are ignored, repeated
 * {@code data:} lines are concatenated, and the conventional
 * {@code [DONE]} sentinel ends the stream. Everything else is forwarded
 * verbatim to {@link #onEvent(String)} which then decides whether the payload
 * carries reasoning, visible text, tool calls, or usage.
 */
public final class SseReader {

    /** Sentinel that terminates an OpenAI style completion stream. */
    public static final String DONE = "[DONE]";

    public interface Visitor {
        /** Called once per complete event payload. */
        void onEvent(String data);

        /** True when the caller no longer wants events. */
        boolean isCancelled();
    }

    private SseReader() {
    }

    /**
     * Reads until the stream ends, the visitor cancels, or {@code [DONE]}.
     *
     * @return true when the sentinel was reached (clean end), false otherwise
     */
    public static boolean read(InputStream input, Visitor visitor) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, Charset.forName("UTF-8")));
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (visitor.isCancelled()) {
                return false;
            }
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    String payload = data.toString();
                    data.setLength(0);
                    if (DONE.equals(payload.trim())) {
                        return true;
                    }
                    visitor.onEvent(payload);
                }
                continue;
            }
            if (line.charAt(0) == ':') {
                // Comment / keepalive preamble.
                continue;
            }
            if (line.startsWith("data:")) {
                String value = line.substring("data:".length());
                if (!value.isEmpty() && value.charAt(0) == ' ') {
                    value = value.substring(1);
                }
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(value);
            }
            // event:, id:, retry: fields carry no payload we need.
        }
        if (data.length() > 0 && DONE.equals(data.toString().trim())) {
            return true;
        }
        if (data.length() > 0) {
            visitor.onEvent(data.toString());
        }
        return false;
    }

    /** Best effort extraction of a JSON object from one event payload. */
    public static JSONObject asJson(String data) {
        String trimmed = data == null ? "" : data.trim();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            return new JSONObject(trimmed);
        } catch (JSONException ignored) {
            return null;
        }
    }
}
