package com.tj.skyrim.launcher;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Live state of the Steam data download. The foreground service writes it and
 * the launcher reads it, so the tab shows progress again after being recreated.
 */
public final class SteamDownloadState {
    public static final int IDLE = 0;
    public static final int RUNNING = 1;
    public static final int DONE = 2;
    public static final int ERROR = 3;
    public static final int CANCELLED = 4;

    public interface Listener {
        void onDownloadStateChanged();
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile int state = IDLE;
    private static volatile long done;
    private static volatile long total;
    private static volatile int depots;
    private static volatile int files;
    private static volatile String file = "";
    private static volatile String detail = "";
    private static volatile String error = "";
    private static volatile String target = "";

    public static void add(Listener listener) {
        if (listener != null) {
            LISTENERS.addIfAbsent(listener);
        }
    }

    public static void remove(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static int state() {
        return state;
    }

    public static boolean isRunning() {
        return state == RUNNING;
    }

    public static long done() {
        return done;
    }

    public static long total() {
        return total;
    }

    public static int depots() {
        return depots;
    }

    public static int files() {
        return files;
    }

    public static String file() {
        return file;
    }

    public static String detail() {
        return detail;
    }

    public static String error() {
        return error;
    }

    public static String target() {
        return target;
    }

    public static void start(String path) {
        state = RUNNING;
        done = 0;
        total = 0;
        depots = 0;
        files = 0;
        file = "";
        detail = "";
        error = "";
        target = path == null ? "" : path;
        notifyListeners();
    }

    public static void plan(int depotCount, int fileCount, long bytes) {
        depots = depotCount;
        files = fileCount;
        total = bytes;
        notifyListeners();
    }

    public static void detail(String message) {
        detail = message == null ? "" : message;
        notifyListeners();
    }

    public static void progress(long doneBytes, long totalBytes, String currentFile) {
        done = doneBytes;
        if (totalBytes > 0) {
            total = totalBytes;
        }
        file = currentFile == null ? "" : currentFile;
        notifyListeners();
    }

    public static void done(String path) {
        state = DONE;
        if (path != null) {
            target = path;
        }
        done = total;
        file = "";
        notifyListeners();
    }

    public static void error(String message) {
        state = ERROR;
        error = message == null ? "download failed" : message;
        notifyListeners();
    }

    public static void cancelled() {
        state = CANCELLED;
        notifyListeners();
    }

    private static void notifyListeners() {
        for (Listener listener : LISTENERS) {
            try {
                listener.onDownloadStateChanged();
            } catch (Throwable ignored) {
            }
        }
    }

    private SteamDownloadState() {
    }
}
