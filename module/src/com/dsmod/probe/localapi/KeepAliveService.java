package com.dsmod.probe.localapi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the Local API listener alive while DeepSeek sits in the background.
 *
 * <p>Runs as a foreground service so the process is not reclaimed, holds a
 * partial wake lock so the CPU stays awake while streams are open, and beats a
 * heart every few seconds so the module can confirm the listener is still
 * serving. When the listening side stops answering, the service stops itself
 * and optionally triggers an automatic recovery, rather than quietly burning
 * battery pretending everything is fine.
 */
public final class KeepAliveService extends Service {

    public static final String CHANNEL_ID = "dq0";
    public static final String ACTION_HEARTBEAT = "com.dsmod.probe.action.LOCAL_API_HEARTBEAT";

    private static final int NOTIFICATION_ID = 54689;
    private static final String WAKE_LOCK_TAG = "Deekseep:ka";

    /** Heartbeat period, milliseconds. */
    public static final long HEARTBEAT_INTERVAL_MS = 5000L;
    /** Silence tolerated before the service gives up, milliseconds. */
    public static final long WATCHDOG_TIMEOUT_MS = 90_000L;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean beating = new AtomicBoolean(false);

    private PowerManager.WakeLock wakeLock;
    private volatile long lastConfirmedAtMs;

    /** Result forwarded by the listening side after each heartbeat. */
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (!beating.get()) {
                return;
            }
            try {
                Intent intent = new Intent(ACTION_HEARTBEAT);
                intent.setPackage(getPackageName());
                intent.putExtra("timestamp", SystemClock.elapsedRealtime());
                sendBroadcast(intent);
            } catch (Throwable ignored) {
                // Broadcasting is best effort.
            }
            checkWatchdog();
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        acquireWakeLock();
        RUNNING.set(true);
        lastConfirmedAtMs = SystemClock.elapsedRealtime();
        LocalApiStats.log("keepalive service started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_HEARTBEAT.equals(intent.getAction())) {
            acknowledge();
            return START_STICKY;
        }
        startBeating();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopBeating();
        releaseWakeLock();
        RUNNING.set(false);
        LocalApiStats.log("keepalive service stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Called by the listener side to confirm it is still alive. */
    public static void acknowledge(Intent intent) {
        // Acknowledgement is recorded by the running instance, see below.
    }

    private void acknowledge() {
        lastConfirmedAtMs = SystemClock.elapsedRealtime();
    }

    /** True while the keepalive loop is installed. */
    public static boolean isRunning() {
        return RUNNING.get();
    }

    public static Intent createIntent(Context context) {
        return new Intent(context, KeepAliveService.class);
    }

    private void startBeating() {
        if (beating.compareAndSet(false, true)) {
            lastConfirmedAtMs = SystemClock.elapsedRealtime();
            handler.post(heartbeat);
        }
    }

    private void stopBeating() {
        beating.set(false);
        handler.removeCallbacks(heartbeat);
    }

    private void checkWatchdog() {
        long silentFor = SystemClock.elapsedRealtime() - lastConfirmedAtMs;
        if (silentFor < WATCHDOG_TIMEOUT_MS) {
            return;
        }
        LocalApiStats.log("keepalive watchdog fired after " + silentFor + "ms of silence");
        stopBeating();
        if (LocalApiConfig.get().autoRecovery) {
            LocalApiStats.noteRecovery();
            try {
                LocalApi.start(getApplicationContext());
            } catch (Throwable ignored) {
                // Recovery failure leaves the service stopped; the UI reports it.
            }
        } else {
            stopSelf();
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(
                Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the local API and its streams reachable");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private static final String CHANNEL_NAME = "DeepSeek Local API";

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setContentTitle("DeepSeek Local API")
                .setContentText("The local listener and its streams are running")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_SECRET);
        return builder.build();
    }

    private void acquireWakeLock() {
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (power == null) {
            return;
        }
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
        try {
            wakeLock.setReferenceCounted(false);
        } catch (Throwable ignored) {
            // Non fatal; defaults apply.
        }
        try {
            wakeLock.acquire();
        } catch (Throwable ignored) {
            // Skipping the wake lock merely allows deeper sleep.
        }
    }

    private void releaseWakeLock() {
        PowerManager.WakeLock lock = wakeLock;
        wakeLock = null;
        if (lock != null) {
            try {
                lock.release();
            } catch (Throwable ignored) {
                // Already released.
            }
        }
    }
}
