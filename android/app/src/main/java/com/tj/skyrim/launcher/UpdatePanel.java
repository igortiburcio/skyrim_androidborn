package com.tj.skyrim.launcher;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;

/**
 * The persistent update board on the right side of the launcher: a
 * gold-bordered card with the release notes and the download / install
 * button. It is shown as soon as the checker finds a newer release and is not
 * modal, so Play and the menu stay usable.
 */
public final class UpdatePanel {

    /** What the activity does when the user presses a button. */
    public interface Listener {
        void onDownloadRequested();

        void onInstallRequested();

        void onOpenReleaseRequested();
    }

    private final Widgets widgets;
    private final Listener listener;

    private final LinearLayout card;
    private final TextView version, notes, progress;
    private final Button action, open;

    private UpdateChecker.Release release;
    private boolean downloading, downloaded, verifying;
    private String error;

    public UpdatePanel(Activity activity, Widgets widgets, ViewGroup root, Listener listener) {
        this.widgets = widgets;
        this.listener = listener;

        card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(widgets.dp(24), widgets.dp(20), widgets.dp(24), widgets.dp(20));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Widgets.SURFACE);
        background.setCornerRadius(widgets.dp(14));
        background.setStroke(widgets.dp(2), Widgets.GOLD);
        card.setBackground(background);
        card.setVisibility(View.GONE);

        /* Keep clear of the 300dp menu on the left and the corner status below. */
        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        int screenH = activity.getResources().getDisplayMetrics().heightPixels;
        int width = Math.max(widgets.dp(280),
                Math.min(screenW - widgets.dp(436), widgets.dp(480)));
        int height = Math.max(widgets.dp(260),
                Math.min(screenH - widgets.dp(112), widgets.dp(560)));
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                width, height, Gravity.RIGHT | Gravity.TOP);
        cardLp.topMargin = widgets.dp(24);
        cardLp.rightMargin = widgets.dp(48);
        root.addView(card, cardLp);

        TextView title = new TextView(activity);
        title.setText("New update");
        title.setTypeface(widgets.fonts().heading);
        title.setTextSize(22);
        title.setLetterSpacing(0.04f);
        title.setTextColor(Widgets.GOLD);
        card.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        version = new TextView(activity);
        version.setTypeface(widgets.fonts().body);
        version.setTextSize(14);
        version.setTextColor(Widgets.TEXT_DIM);
        LinearLayout.LayoutParams versionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        versionLp.topMargin = widgets.dp(6);
        card.addView(version, versionLp);

        ScrollView notesScroll = new ScrollView(activity);
        notesScroll.setFocusable(false);
        notes = new TextView(activity);
        notes.setTypeface(widgets.fonts().body);
        notes.setTextSize(13);
        notes.setTextColor(Widgets.TEXT);
        notesScroll.addView(notes, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.topMargin = widgets.dp(10);
        card.addView(notesScroll, scrollLp);

        progress = new TextView(activity);
        progress.setTypeface(widgets.fonts().body);
        progress.setTextSize(13);
        progress.setTextColor(Widgets.TEXT_DIM);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressLp.topMargin = widgets.dp(8);
        card.addView(progress, progressLp);

        action = widgets.updateButton("Download update", v -> {
            if (release == null || downloading || verifying) return;
            if (downloaded) listener.onInstallRequested();
            else if (release.apkUrl.isEmpty()) listener.onOpenReleaseRequested();
            else listener.onDownloadRequested();
        });
        card.addView(action, widgets.paneRow());

        open = widgets.actionButton(card, "Open release page",
                v -> listener.onOpenReleaseRequested());

        refreshUi();
    }

    public boolean isVisible() {
        return card.getVisibility() == View.VISIBLE;
    }

    /** Shows the board with a short slide-in; it does not take the focus. */
    public void show() {
        if (release == null || isVisible()) return;
        card.setVisibility(View.VISIBLE);
        card.setAlpha(0f);
        card.setTranslationX(widgets.dp(24));
        card.animate().alpha(1f).translationX(0f).setDuration(200).start();
    }

    public void hide() {
        card.setVisibility(View.GONE);
    }

    /** While the settings window is open, its buttons must not take the D-pad. */
    public void setFocusable(boolean value) {
        action.setFocusable(value);
        action.setFocusableInTouchMode(value);
        open.setFocusable(value);
        open.setFocusableInTouchMode(value);
    }

    public void setRelease(UpdateChecker.Release release) {
        this.release = release;
        refreshUi();
    }

    /** The download started; the button stays focused but ignores taps. */
    public void downloading(boolean value) {
        downloading = value;
        if (value) {
            downloaded = false;
            verifying = false;
            error = null;
        }
        refreshUi();
    }

    /** The APK is on the device and the button now opens the installer. */
    public void downloaded(boolean value) {
        downloaded = value;
        if (value) {
            downloading = false;
            verifying = false;
            error = null;
        }
        refreshUi();
    }

    /** The file is being hashed before the installer opens. */
    public void verifying() {
        verifying = true;
        error = null;
        action.setText("Checking update...");
        action.setAlpha(0.65f);
        progress.setText("Checking the update file...");
        progress.setTextColor(Widgets.TEXT_DIM);
        progress.setVisibility(View.VISIBLE);
    }

    public void progress(long soFar, long total) {
        if (total > 0) {
            int percent = (int) Math.min(100, soFar * 100 / total);
            progress.setText(String.format(Locale.ROOT, "Downloading... %d%% (%s of %s)",
                    percent, size(soFar), size(total)));
        } else {
            progress.setText("Downloading... " + size(soFar));
        }
        progress.setTextColor(Widgets.TEXT_DIM);
        progress.setVisibility(View.VISIBLE);
    }

    public void error(String message) {
        error = message;
        downloading = false;
        verifying = false;
        refreshUi();
    }

    private void refreshUi() {
        if (release == null) return;
        version.setText("v" + widgets.versionName() + "  \u2192  " + release.version);
        notes.setText(plainNotes(release.notes));
        open.setVisibility(release.htmlUrl.isEmpty() ? View.GONE : View.VISIBLE);

        if (downloading) {
            action.setText("Downloading...");
            action.setEnabled(true);
            action.setAlpha(0.65f);
            progress.setVisibility(View.VISIBLE);
            progress.setTextColor(Widgets.TEXT_DIM);
            if (error == null) progress.setText("Starting download...");
        } else if (downloaded) {
            action.setText("Install update");
            action.setEnabled(true);
            action.setAlpha(1f);
            progress.setVisibility(View.VISIBLE);
            if (error != null) {
                progress.setText(error);
                progress.setTextColor(Widgets.BAD_COLOR);
            } else {
                progress.setText("Download complete. Tap Install update.");
                progress.setTextColor(Widgets.OK_COLOR);
            }
        } else {
            action.setText(release.apkUrl.isEmpty() ? "Open release page"
                    : release.apkSize > 0
                    ? "Download update (" + size(release.apkSize) + ")"
                    : "Download update");
            action.setEnabled(true);
            action.setAlpha(1f);
            if (error != null) {
                progress.setText(error);
                progress.setTextColor(Widgets.BAD_COLOR);
                progress.setVisibility(View.VISIBLE);
            } else {
                progress.setVisibility(View.GONE);
            }
        }
    }

    /** A light Markdown cleanup: headings and bullets read well as plain text. */
    private static String plainNotes(String markdown) {
        if (markdown == null || markdown.trim().isEmpty()) {
            return "See the release page for the patch notes.";
        }
        StringBuilder out = new StringBuilder();
        for (String line : markdown.split("\n")) {
            String text = line.trim();
            if (text.startsWith("#")) {
                int i = 0;
                while (i < text.length() && text.charAt(i) == '#') i++;
                text = text.substring(i).trim();
            }
            if (text.startsWith("- ") || text.startsWith("* ")) {
                text = "\u2022 " + text.substring(2);
            }
            out.append(text).append('\n');
        }
        return out.toString().trim();
    }

    private static String size(long bytes) {
        if (bytes <= 0) return "0 MB";
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
