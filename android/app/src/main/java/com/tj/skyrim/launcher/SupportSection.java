package com.tj.skyrim.launcher;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;

/**
 * The "Support" tab: one button that writes the log zip a bug report needs.
 * The result line tells the player the exact file to attach, since the folder
 * is on a real SD card and every file manager can see it.
 */
public final class SupportSection implements LauncherWindow.Content {

    /** The activity runs the export (file IO) and reports back. */
    public interface Listener {
        void onExportLogsRequested();
    }

    private final Widgets widgets;
    private final Listener listener;
    private TextView status;
    private View exportButton;

    public SupportSection(Widgets widgets, Listener listener) {
        this.widgets = widgets;
        this.listener = listener;
    }

    @Override
    public CharSequence title() {
        return "Support";
    }

    @Override
    public void build(LinearLayout pane) {
        TextView title = widgets.text(pane, "Logs", 15, true);
        title.setTextColor(Widgets.GOLD);

        widgets.text(pane, "A bug report needs the game log, the last exit "
                + "report and the graphics settings. Export logs writes them into "
                + "one zip inside the game's port folder and tells you the path.",
                14, false);

        exportButton = widgets.actionButton(pane, "Export logs",
                v -> listener.onExportLogsRequested());

        status = widgets.text(pane, "", 13, false);
        status.setTextColor(widgets.hintColor());
    }

    /** Shows where the zip went. */
    public void exported(File zip) {
        if (status == null) return;
        status.setTextColor(widgets.okColor());
        status.setText("Saved " + zip.getAbsolutePath());
    }

    /** Shows why the export failed. */
    public void failed(String message) {
        if (status == null) return;
        status.setTextColor(widgets.badColor());
        status.setText(message);
    }

    @Override
    public View initialFocus() {
        return exportButton;
    }
}
