package com.tj.skyrim.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * In-app folder browser for the download destination. Android's document
 * picker refuses storage roots, Download and Android/data even with all-files
 * access ("Can't use this folder"); this lists the real directories instead.
 */
public final class FolderBrowser {

    public interface Listener {
        void onFolderChosen(String path);
    }

    public static void show(Activity activity, String start, Listener listener) {
        File startDir = existingDirectory(start);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Choose the download folder");

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 16);
        root.setPadding(pad, pad / 2, pad, 0);

        TextView pathView = new TextView(activity);
        pathView.setTextSize(13);
        pathView.setTextColor(Color.GRAY);
        pathView.setText(startDir.getAbsolutePath());
        root.addView(pathView);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        builder.setView(root);
        builder.setPositiveButton("Use this folder", null);
        builder.setNegativeButton("Cancel", null);
        AlertDialog dialog = builder.create();

        Navigator navigator = new Navigator(activity, dialog, list, pathView, listener);
        navigator.current = startDir;
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                listener.onFolderChosen(navigator.current.getAbsolutePath());
                dialog.dismiss();
            });
            navigator.fill();
        });
        dialog.show();
    }

    private static final class Navigator {
        final Activity activity;
        final AlertDialog dialog;
        final LinearLayout list;
        final TextView pathView;
        final Listener listener;
        File current;

        Navigator(Activity activity, AlertDialog dialog, LinearLayout list, TextView pathView,
                  Listener listener) {
            this.activity = activity;
            this.dialog = dialog;
            this.list = list;
            this.pathView = pathView;
            this.listener = listener;
        }

        void fill() {
            list.removeAllViews();
            pathView.setText(current.getAbsolutePath());

            // Fixed shortcuts: Android's picker refuses these roots, and the
            // /storage listing is not always reachable through parents.
            File internal = new File("/storage/emulated/0");
            if (internal.isDirectory()) {
                row("Internal storage   /storage/emulated/0", () -> navigate(internal));
            }
            File volume = volumeRoot(current);
            if (volume != null) {
                row("SD card   " + volume.getAbsolutePath(), () -> navigate(volume));
            }

            File parent = current.getParentFile();
            if (parent != null && parent.isDirectory()) {
                row("..  " + parent.getName(), () -> navigate(parent));
            }

            File[] children = current.listFiles();
            if (children != null) {
                List<File> dirs = new ArrayList<>();
                for (File child : children) {
                    if (child.isDirectory() && !child.getName().startsWith(".")) {
                        dirs.add(child);
                    }
                }
                Collections.sort(dirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File dir : dirs) {
                    row(dir.getName(), () -> navigate(dir));
                }
            }
            row("+ New folder\u2026", this::newFolder);
        }

        private void row(String text, Runnable action) {
            Button button = new Button(activity);
            button.setText(text);
            button.setAllCaps(false);
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            button.setOnClickListener(v -> action.run());
            list.addView(button, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        private void navigate(File dir) {
            current = dir;
            fill();
        }

        private void newFolder() {
            EditText input = new EditText(activity);
            input.setInputType(InputType.TYPE_CLASS_TEXT);
            input.setHint("Folder name");
            new AlertDialog.Builder(activity)
                    .setTitle("New folder in " + current.getName())
                    .setView(input)
                    .setPositiveButton("Create", (d, which) -> {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty() || name.contains("/") || name.equals("..")) {
                            return;
                        }
                        File dir = new File(current, name);
                        if (dir.mkdirs()) {
                            navigate(dir);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    private static File volumeRoot(File current) {
        String path = current.getAbsolutePath();
        if (!path.startsWith("/storage/") || path.startsWith("/storage/emulated/")) {
            return null;
        }
        String[] parts = path.split("/");
        if (parts.length < 3) {
            return null;
        }
        File root = new File("/storage/" + parts[2]);
        return root.isDirectory() ? root : null;
    }

    private static File existingDirectory(String start) {
        File dir = start == null || start.isEmpty() ? null : new File(start);
        while (dir != null && !dir.isDirectory()) {
            dir = dir.getParentFile();
        }
        if (dir != null && dir.isDirectory()) {
            return dir;
        }
        File internal = new File("/storage/emulated/0");
        return internal.isDirectory() ? internal : new File("/");
    }

    private static int dp(Activity activity, int value) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics());
    }

    private FolderBrowser() {
    }
}
