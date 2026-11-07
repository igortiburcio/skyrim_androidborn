package com.tj.skyrim.launcher;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import java.io.File;

/**
 * Owns the update APK download. DownloadManager does the transfer and keeps
 * the system notification; this class only starts it, reports progress and
 * points at the finished file.
 */
public final class UpdateDownloader {

    public static final String APK_MIME = "application/vnd.android.package-archive";

    /** Progress and completion of one download. */
    public interface Listener {
        void onProgress(long soFar, long total);

        void onComplete();

        void onError(String message);
    }

    private final Context context;
    private final DownloadManager manager;

    public UpdateDownloader(Context context) {
        this.context = context.getApplicationContext();
        this.manager = (DownloadManager) this.context.getSystemService(Context.DOWNLOAD_SERVICE);
    }

    /** Starts the APK download and returns its DownloadManager id. */
    public long start(UpdateChecker.Release release) {
        if (manager == null) throw new IllegalStateException("download manager unavailable");
        cleanOld(release);
        File target = fileFor(release);
        if (target != null && target.exists()) target.delete();

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(release.apkUrl));
        request.setTitle("Skyrim Androidborn " + release.version);
        request.setDescription("Downloading update");
        request.setMimeType(APK_MIME);
        request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS,
                fileName(release.version));
        request.setAllowedOverMetered(true);
        request.setAllowedOverRoaming(false);
        return manager.enqueue(request);
    }

    /** Polls one download and reports through the listener on the caller thread. */
    public void poll(long id, Listener listener) {
        if (manager == null) {
            listener.onError("The download manager is not available.");
            return;
        }
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) {
                listener.onError("The download was removed. Try again.");
                return;
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_STATUS));
            long soFar = cursor.getLong(cursor.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = cursor.getLong(cursor.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            switch (status) {
                case DownloadManager.STATUS_SUCCESSFUL:
                    listener.onComplete();
                    break;
                case DownloadManager.STATUS_FAILED:
                    listener.onError(reason(cursor.getInt(cursor.getColumnIndexOrThrow(
                            DownloadManager.COLUMN_REASON))));
                    break;
                default:
                    listener.onProgress(soFar, total);
                    break;
            }
        } catch (Exception e) {
            listener.onError("Could not read the download status.");
        }
    }

    /** The content:// URI the Android installer can read, or null when not ready. */
    public Uri uriFor(long id) {
        if (manager == null) return null;
        try {
            return manager.getUriForDownloadedFile(id);
        } catch (Exception e) {
            return null;
        }
    }

    /** The file DownloadManager writes; null when external storage is unavailable. */
    public File fileFor(UpdateChecker.Release release) {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        return dir == null ? null : new File(dir, fileName(release.version));
    }

    /** Removes APKs from earlier attempts so the folder does not grow. */
    public void cleanOld(UpdateChecker.Release keep) {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        String keepName = fileName(keep.version);
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".apk")
                    && !file.getName().equals(keepName)) {
                file.delete();
            }
        }
    }

    private static String fileName(String version) {
        return "Skyrim-Androidborn-" + version.replaceAll("[^0-9A-Za-z._-]", "_") + ".apk";
    }

    private static String reason(int code) {
        switch (code) {
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                return "Not enough free space for the update.";
            case DownloadManager.ERROR_HTTP_DATA_ERROR:
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                return "GitHub did not send the update file.";
            case DownloadManager.ERROR_CANNOT_RESUME:
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                return "The download could not continue. Try again.";
            default:
                return "The download failed. Try again.";
        }
    }
}
