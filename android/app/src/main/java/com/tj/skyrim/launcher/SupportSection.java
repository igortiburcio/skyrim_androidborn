package com.tj.skyrim.launcher;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;

/**
 * The "Support" tab: one button that writes the log zip a bug report needs and
 * one that prepares the character-face repair. The result lines tell the player
 * the exact path or the next step, since the folder is on a real SD card and
 * every file manager can see it.
 */
public final class SupportSection implements LauncherWindow.Content {

    /** The activity runs the file IO and reports back. */
    public interface Listener {
        void onExportLogsRequested();

        void onRepairFaceRequested();
    }

    private final Widgets widgets;
    private final Listener listener;
    private TextView status;
    private TextView faceStatus;
    private View exportButton;
    private View repairButton;

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

        TextView faceTitle = widgets.text(pane, "Character face", 15, true);
        faceTitle.setTextColor(Widgets.GOLD);
        faceTitle.setPadding(0, widgets.dp(12), 0, 0);

        widgets.text(pane, "Skyrim can lose the character's face colours (a dark "
                + "or brown face) after the character becomes a vampire. The port "
                + "keeps the last good face colours and Repair character face also "
                + "writes a dump of the face texture, so a report shows what the "
                + "game did.\n\nIf the face in a save is already dark, make the game "
                + "rebuild it: visit the Face Sculptor in the Ragged Flagon "
                + "(Dawnguard) and change anything, or open the console with the "
                + "keyboard button, type player.getrace, and then player.setrace "
                + "with exactly that name. Keep any Vampire in the name: entering "
                + "the plain race cures vampirism.",
                14, false);

        /* The status sits above the button: the pane scrolls and a line below
         * the last button ends up off screen. */
        faceStatus = widgets.text(pane, "", 13, false);
        faceStatus.setTextColor(widgets.hintColor());

        repairButton = widgets.actionButton(pane, "Repair character face",
                v -> listener.onRepairFaceRequested());
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

    /** The repair switches are in place for the next launch. */
    public void faceRepairEnabled(File saveDir) {
        if (faceStatus == null) return;
        faceStatus.setTextColor(widgets.okColor());
        faceStatus.setText("Ready for the next launch. The texture dump goes to "
                + new File(saveDir, "texdump").getAbsolutePath()
                + " and is included in Export logs after you play.");
    }

    /** The repair switches could not be written. */
    public void faceRepairFailed(String message) {
        if (faceStatus == null) return;
        faceStatus.setTextColor(widgets.badColor());
        faceStatus.setText(message);
    }

    @Override
    public View initialFocus() {
        return exportButton;
    }
}
