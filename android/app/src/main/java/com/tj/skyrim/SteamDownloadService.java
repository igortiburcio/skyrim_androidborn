package com.tj.skyrim;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import com.tj.skyrim.launcher.LauncherSettings;
import com.tj.skyrim.launcher.SteamDownloadState;
import com.tj.skyrim.launcher.SteamSession;
import com.tj.skyrim.steam.SteamClient;
import com.tj.skyrim.steam.SteamDownloader;
import java.io.File;
import java.util.Locale;

/**
 * Foreground service that keeps the Steam data download alive while the
 * launcher is in the background or the screen is off. The signed-in
 * {@link SteamClient} is shared through {@link SteamSession}; progress is
 * published to {@link SteamDownloadState} for the tab.
 */
public final class SteamDownloadService extends Service {
    public static final String ACTION_START = "com.tj.skyrim.STEAM_START";
    public static final String ACTION_CANCEL = "com.tj.skyrim.STEAM_CANCEL";
    public static final String EXTRA_TARGET = "target";
    public static final String EXTRA_APP_ID = "appId";

    private static final String CHANNEL = "steam-download";
    private static final int NOTIFICATION_ID = 41;
    private static final long WAKELOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L;

    private SteamDownloader downloader;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private long lastNotify;
    private String target = "";

    public static void start(Context context, String target, int appId) {
        Intent intent = new Intent(context, SteamDownloadService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TARGET, target)
                .putExtra(EXTRA_APP_ID, appId);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void cancel(Context context) {
        try {
            context.startService(new Intent(context, SteamDownloadService.class)
                    .setAction(ACTION_CANCEL));
        } catch (Exception ignored) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Steam download",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Skyrim game data downloaded from Steam");
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            if (downloader != null) {
                downloader.cancel();
            }
            SteamDownloadState.cancelled();
            releaseLocks();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        target = intent == null ? "" : intent.getStringExtra(EXTRA_TARGET);
        int appId = intent == null ? SteamDownloader.SKYRIM_APP_ID
                : intent.getIntExtra(EXTRA_APP_ID, SteamDownloader.SKYRIM_APP_ID);
        startForeground(NOTIFICATION_ID, notification("Preparing\u2026", 0, 0, true));
        acquireLocks();

        SteamClient client = SteamSession.get();
        if (client == null || !client.isLoggedOn()) {
            fail("Sign in with Steam in the launcher first");
            return START_NOT_STICKY;
        }
        File dir = new File(target);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            fail("Cannot write to " + target);
            return START_NOT_STICKY;
        }

        SteamDownloadState.start(target);
        SteamDownloadState.plan(0, 0, 0);
        downloader = new SteamDownloader(client);
        new Thread(() -> downloader.download(appId, dir, new SteamDownloader.Listener() {
            @Override
            public void onPlan(int depots, int files, long bytes) {
                SteamDownloadState.plan(depots, files, bytes);
                update("Downloading " + files + " files (" + size(bytes) + ")", 0, bytes);
            }

            @Override
            public void onDepot(String message) {
                SteamDownloadState.detail(message);
            }

            @Override
            public void onProgress(long done, long total, String file) {
                SteamDownloadState.progress(done, total, file);
                long now = System.currentTimeMillis();
                if (now - lastNotify < 500 && done < total) {
                    return;
                }
                lastNotify = now;
                update(file, done, total);
            }

            @Override
            public void onDone() {
                LauncherSettings saved = new LauncherSettings(getApplicationContext());
                saved.folder(target);
                saved.steamFolder(target);
                SteamDownloadState.done(target);
                finish("Game data ready");
            }

            @Override
            public void onError(Throwable error) {
                String message = error.getMessage() == null
                        ? error.toString() : error.getMessage();
                SteamDownloadState.error(message);
                finish("Download failed: " + message);
            }
        }), "steam-download").start();
        return START_NOT_STICKY;
    }

    private void update(String text, long done, long total) {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification(text, done, total, true));
    }

    private void finish(String text) {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification(text, 0, 0, false));
        releaseLocks();
        stopForeground(false);
        stopSelf();
    }

    private void fail(String message) {
        SteamDownloadState.error(message);
        finish(message);
    }

    private Notification notification(String text, long done, long total, boolean ongoing) {
        Intent open = new Intent(this, LauncherActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancelIntent = PendingIntent.getService(this, 1,
                new Intent(this, SteamDownloadService.class).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Skyrim Androidborn")
                .setContentText(text)
                .setContentIntent(openIntent)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing);
        if (total > 0) {
            builder.setProgress(100, (int) Math.min(100, done * 100 / total), false);
        } else {
            builder.setProgress(0, 0, false);
        }
        if (ongoing) {
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel",
                    cancelIntent);
        }
        return builder.build();
    }

    private void acquireLocks() {
        if (wakeLock == null) {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "Skyrim:steam-download");
            wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
        }
        if (wifiLock == null) {
            WifiManager wifi = (WifiManager) getApplicationContext()
                    .getSystemService(WIFI_SERVICE);
            if (wifi != null) {
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "Skyrim:steam-download");
                wifiLock.acquire();
            }
        }
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        wifiLock = null;
    }

    @Override
    public void onDestroy() {
        if (downloader != null) {
            downloader.cancel();
        }
        releaseLocks();
        super.onDestroy();
    }

    private static String size(long bytes) {
        if (bytes >= 1_000_000_000L) {
            return String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0);
        }
        return String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0);
    }
}
