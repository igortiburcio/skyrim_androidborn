package com.tj.skyrim.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;

/**
 * The storage prompts. The app targets API 29 with legacy storage, so the
 * normal "allow access to files" prompt already gives the game plain file
 * access; all-files access is only asked for when the chosen folder exists but
 * still cannot be read (this Android ignored legacy storage).
 */
public final class StorageAccess {

    /** Request code of the READ/WRITE permission prompt. */
    public static final int PERMISSION_REQUEST = 2;

    private final Activity activity;

    public StorageAccess(Activity activity) {
        this.activity = activity;
    }

    public boolean granted() {
        return activity.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
                || (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager());
    }

    public void request() {
        activity.requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST);
    }

    public boolean allFilesGranted() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }

    /** Opens the system screen that grants access to all files. */
    public void requestAllFiles() {
        try {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (Exception e) {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    /**
     * content://...tree/primary:Games/Skyrim -> /storage/emulated/0/Games/Skyrim,
     * or null when the picked location is not a plain folder on this device.
     */
    public static String folderPathOf(Uri tree) {
        String id;
        try {
            id = DocumentsContract.getTreeDocumentId(tree);
        } catch (Exception e) {
            return null;
        }
        int c = id.indexOf(':');
        if (c < 0) return null;
        String vol = id.substring(0, c), rel = id.substring(c + 1);
        String base = vol.equalsIgnoreCase("primary")
                ? Environment.getExternalStorageDirectory().getPath() : "/storage/" + vol;
        return rel.isEmpty() ? base : base + "/" + rel;
    }
}
