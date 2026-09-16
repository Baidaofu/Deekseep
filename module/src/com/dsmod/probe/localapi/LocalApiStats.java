package com.dsmod.probe.localapi;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Rolling request statistics and the human readable request log.
 *
 * <p>Counters are kept in memory and mirrored to
 * {@code deekseep_api_status.json} so external tooling can scrape them. The
 * per-request log is capped both by line count and by time window.
 */
public final class LocalApiStats {

    /** Maximum number of log lines retained on disk. */
    public static final int LOG_LINES = 400;
    /** Maximum age of a retained log line, in milliseconds. */
    public static final long LOG_WINDOW_MS = 6L * 60L * 60L * 1000L;

    private static final String STATUS_FILE = "deekseep_api_status.json";
    private static final String LOG_FILE = "deekseep_api.log";

    private static final Object LOCK = new Object();
    private static File directory;

    private static long startedAt = System.currentTimeMillis();
    private static long total;
    private static long failures;
    private static long streamingCount;
    private static long toolRounds;
    private static long reasoningCount;
    private static long latencySumMs;
    private static long latencyMaxMs;
    private static long contextRelaySuccess;
    private static long contextRelayCached;
    private static long contextRelayFailed;
    private static long recoveryCount;

    private static final List<String> RECENT = new ArrayList<String>();

    private LocalApiStats() {
    }

    public static void initialize(File filesDir) {
        directory = filesDir;
    }

    /** Records the successful completion of one request. */
    public static void recordSuccess(boolean streaming, boolean reasoningMode, long latencyMs) {
        record(false, streaming, reasoningMode, latencyMs);
    }

    /** Records a failed request. */
    public static void recordFailure(long latencyMs) {
        record(true, false, false, latencyMs);
    }

    private static void record(boolean failed, boolean streaming, boolean reasoningMode,
            long latencyMs) {
        synchronized (LOCK) {
            total++;
            if (failed) {
                failures++;
            } else {
                if (streaming) {
                    streamingCount++;
                }
                if (reasoningMode) {
                    reasoningCount++;
                }
            }
            latencySumMs += Math.max(0L, latencyMs);
            if (latencyMs > latencyMaxMs) {
                latencyMaxMs = latencyMs;
            }
        }
    }

    public static void noteToolRound() {
        synchronized (LOCK) {
            toolRounds++;
        }
    }

    public static void noteContextRelay(boolean success, boolean cached) {
        synchronized (LOCK) {
            if (cached) {
                contextRelayCached++;
            } else if (success) {
                contextRelaySuccess++;
            } else {
                contextRelayFailed++;
            }
        }
    }

    public static void noteRecovery() {
        synchronized (LOCK) {
            recoveryCount++;
        }
    }

    /** Appends one line to the rolling request log. */
    public static void log(String line) {
        if (line == null) {
            return;
        }
        String stamped = new java.text.SimpleDateFormat("HH:mm:ss.SSS",
                java.util.Locale.US).format(new java.util.Date()) + " " + line;
        synchronized (LOCK) {
            RECENT.add(stamped);
            while (RECENT.size() > LOG_LINES) {
                RECENT.remove(0);
            }
            flushLogLocked();
        }
    }

    /** Machine readable snapshot, also written to {@code deekseep_api_status.json}. */
    public static JSONObject snapshot() {
        synchronized (LOCK) {
            long successful = total - failures;
            JSONObject json = new JSONObject();
            try {
                json.put("started_at", startedAt);
                json.put("uptime_ms", System.currentTimeMillis() - startedAt);
                json.put("total_requests", total);
                json.put("successful_requests", successful);
                json.put("failed_requests", failures);
                json.put("streaming_requests", streamingCount);
                json.put("reasoning_requests", reasoningCount);
                json.put("tool_rounds", toolRounds);
                json.put("average_latency_ms", successful == 0 ? 0 : latencySumMs / successful);
                json.put("max_latency_ms", latencyMaxMs);
                json.put("context_relay_success", contextRelaySuccess);
                json.put("context_relay_cached", contextRelayCached);
                json.put("context_relay_failed", contextRelayFailed);
                json.put("auto_recovery_count", recoveryCount);
            } catch (Throwable ignored) {
                return json;
            }
            persistLocked(json);
            return json;
        }
    }

    /** Short multi line summary shown in the settings screen. */
    public static String summary() {
        JSONObject json = snapshot();
        StringBuilder builder = new StringBuilder();
        builder.append("requests=").append(json.optLong("total_requests", 0));
        builder.append(" ok=").append(json.optLong("successful_requests", 0));
        builder.append(" err=").append(json.optLong("failed_requests", 0));
        builder.append(" avg=").append(json.optLong("average_latency_ms", 0)).append("ms");
        builder.append(" max=").append(json.optLong("max_latency_ms", 0)).append("ms");
        builder.append(" stream=").append(json.optLong("streaming_requests", 0));
        builder.append(" tools=").append(json.optLong("tool_rounds", 0));
        return builder.toString();
    }

    public static String recentLog() {
        synchronized (LOCK) {
            StringBuilder builder = new StringBuilder();
            for (int i = RECENT.size() - 1; i >= 0; i--) {
                builder.append(RECENT.get(i)).append('\n');
            }
            return builder.toString();
        }
    }

    public static void reset() {
        synchronized (LOCK) {
            startedAt = System.currentTimeMillis();
            total = 0;
            failures = 0;
            streamingCount = 0;
            toolRounds = 0;
            reasoningCount = 0;
            latencySumMs = 0;
            latencyMaxMs = 0;
            contextRelaySuccess = 0;
            contextRelayCached = 0;
            contextRelayFailed = 0;
            RECENT.clear();
        }
    }

    private static void persistLocked(JSONObject json) {
        File file = directory == null ? null : new File(directory, STATUS_FILE);
        if (file == null) {
            return;
        }
        try {
            LocalApiConfig.writeAtomic(file, json.toString().getBytes("UTF-8"));
        } catch (Throwable ignored) {
            // Status scraping is best effort.
        }
    }

    private static void flushLogLocked() {
        File file = directory == null ? null : new File(directory, LOG_FILE);
        if (file == null) {
            return;
        }
        try {
            StringBuilder builder = new StringBuilder();
            long cutoff = System.currentTimeMillis() - LOG_WINDOW_MS;
            for (String line : RECENT) {
                builder.append(line).append('\n');
            }
            if (builder.length() == 0) {
                builder.append('\n');
            }
            LocalApiConfig.writeAtomic(file, builder.toString().getBytes("UTF-8"));
            if (cutoff < 0) {
                return;
            }
        } catch (Throwable ignored) {
            // Never let logging take the host down.
        }
    }

    /** Renders the log as a JSON array for diagnostics export. */
    public static JSONArray logAsArray() {
        JSONArray array = new JSONArray();
        List<String> copy;
        synchronized (LOCK) {
            copy = new ArrayList<String>(RECENT);
        }
        for (String line : copy) {
            array.put(line);
        }
        return array;
    }
}
