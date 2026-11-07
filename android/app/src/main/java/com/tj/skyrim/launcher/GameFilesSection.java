package com.tj.skyrim.launcher;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

/**
 * The "Game Files" window: the game data status and folder chooser, then the
 * Steam sign-in/download block. One scrollable pane, no tabs: pick an
 * existing folder or download the data with Steam. Both copies can be removed
 * here (the port/save folder with the saves is always kept).
 */
public final class GameFilesSection implements LauncherWindow.Content,
        FolderSection.Listener, SteamSection.Listener {

    /** The activity owns the pickers, permissions and the Play status. */
    public interface Listener {
        void onChooseFolderRequested();

        void onAllFilesRequested();

        void onChooseDownloadFolderRequested();

        void onDownloadFinished(String path);

        void onRemoveSteamRequested();

        void onRemoveFolderRequested();
    }

    private final Widgets widgets;
    private final LauncherSettings settings;
    private final Listener listener;
    private final FolderSection folder;
    private final SteamSection steam;
    private Button removeSteamButton, removeFolderButton;
    private boolean confirmSteam, confirmFolder;

    public GameFilesSection(Widgets widgets, LauncherSettings settings, FolderCheck check,
                            StorageAccess storage, Listener listener) {
        this.widgets = widgets;
        this.settings = settings;
        this.listener = listener;
        this.folder = new FolderSection(widgets, settings, check, storage, this);
        this.steam = new SteamSection(widgets, this);
    }

    @Override
    public CharSequence title() {
        return "Game Files";
    }

    @Override
    public void build(LinearLayout pane) {
        heading(pane, "Game data", 0);
        folder.buildInto(pane);
        removeSteamButton = widgets.actionButton(pane, "Remove Steam download",
                v -> removePressed(true));
        removeFolderButton = widgets.actionButton(pane, "Remove this game folder",
                v -> removePressed(false));
        TextView keep = widgets.text(pane,
                "Removal deletes Data and appinfo.bin; port/save (your saves) is kept.",
                12, false);
        keep.setTextColor(widgets.hintColor());
        heading(pane, "Steam", 18);
        steam.buildInto(pane);
        refresh();
    }

    @Override
    public View initialFocus() {
        return folder.initialFocus();
    }

    /** Re-reads the folder (after a pick or a download). */
    public void refresh() {
        folder.refresh();
        if (removeSteamButton == null) {
            return;
        }
        boolean running = SteamDownloadState.isRunning();
        String steamFolder = settings.steamFolder();
        String current = settings.folder();
        boolean currentIsSteam = samePath(current, steamFolder);
        resetConfirmations();
        removeSteamButton.setVisibility(
                steamFolder != null && !running ? View.VISIBLE : View.GONE);
        removeFolderButton.setVisibility(
                current != null && !currentIsSteam && !running ? View.VISIBLE : View.GONE);
    }

    public boolean ready() {
        return folder.ready();
    }

    public List<String> problems() {
        return folder.problems();
    }

    public String statusLine() {
        return folder.statusLine();
    }

    public void error(String message) {
        folder.error(message);
    }

    /** Shows the result of a download, removal or pick. */
    public void note(String message, boolean ok) {
        folder.note(message, ok);
    }

    public void downloadInto(String path) {
        steam.downloadInto(path);
    }

    public void close() {
        steam.close();
    }

    /* ---- removal ---- */

    private void removePressed(boolean steamCopy) {
        if (steamCopy) {
            if (!confirmSteam) {
                confirmSteam = true;
                confirmFolder = false;
                removeSteamButton.setText("Tap again to delete the Steam data");
                removeSteamButton.setTextColor(widgets.badColor());
                return;
            }
            confirmSteam = false;
            removeSteamButton.setEnabled(false);
            removeSteamButton.setText("Removing\u2026");
            listener.onRemoveSteamRequested();
        } else {
            if (!confirmFolder) {
                confirmFolder = true;
                confirmSteam = false;
                removeFolderButton.setText("Tap again to delete the game data");
                removeFolderButton.setTextColor(widgets.badColor());
                return;
            }
            confirmFolder = false;
            removeFolderButton.setEnabled(false);
            removeFolderButton.setText("Removing\u2026");
            listener.onRemoveFolderRequested();
        }
    }

    private void resetConfirmations() {
        confirmSteam = false;
        confirmFolder = false;
        if (removeSteamButton != null) {
            removeSteamButton.setEnabled(true);
            removeSteamButton.setText("Remove Steam download");
            removeSteamButton.setTextColor(widgets.textColor());
        }
        if (removeFolderButton != null) {
            removeFolderButton.setEnabled(true);
            removeFolderButton.setText("Remove this game folder");
            removeFolderButton.setTextColor(widgets.textColor());
        }
    }

    private static boolean samePath(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b) || a.equals(b + "/") || b.equals(a + "/");
    }

    /* ---- child callbacks ---- */

    @Override
    public void onChooseFolderRequested() {
        listener.onChooseFolderRequested();
    }

    @Override
    public void onAllFilesRequested() {
        listener.onAllFilesRequested();
    }

    @Override
    public void onChooseDownloadFolderRequested() {
        listener.onChooseDownloadFolderRequested();
    }

    @Override
    public void onDownloadFinished(String path) {
        refresh();
        if (listener != null) {
            listener.onDownloadFinished(path);
        }
    }

    private void heading(LinearLayout pane, String text, int topDp) {
        TextView view = widgets.text(pane, text, 15, true);
        LinearLayout.LayoutParams lp = view.getLayoutParams() instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) view.getLayoutParams()
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = widgets.dp(topDp);
        view.setLayoutParams(lp);
        view.setTextColor(Widgets.GOLD);
    }
}
