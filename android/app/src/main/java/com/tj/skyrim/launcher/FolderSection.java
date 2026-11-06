package com.tj.skyrim.launcher;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * The "Game folder" tab: where the install lives and whether it is ready. The
 * always-visible status on the prelaunch screen comes from here too.
 */
public final class FolderSection implements LauncherWindow.Content {

    /** The activity's side of the folder flow. */
    public interface Listener {
        /** "Choose Skyrim folder" was pressed (the activity runs the storage/picker flow). */
        void onChooseFolderRequested();

        /** "All files access" was pressed. */
        void onAllFilesRequested();
    }

    private final Widgets widgets;
    private final LauncherSettings settings;
    private final FolderCheck check;
    private final StorageAccess storage;
    private final Listener listener;

    private TextView pathText, detailText;
    private View chooseButton, allFilesButton;
    private List<String> problems = new ArrayList<>();

    public FolderSection(Widgets widgets, LauncherSettings settings, FolderCheck check,
                         StorageAccess storage, Listener listener) {
        this.widgets = widgets;
        this.settings = settings;
        this.check = check;
        this.storage = storage;
        this.listener = listener;
    }

    @Override
    public CharSequence title() {
        return "Game folder";
    }

    @Override
    public void build(LinearLayout pane) {
        pathText = widgets.text(pane, "", 16, false);
        detailText = widgets.text(pane, "", 14, false);
        detailText.setTextColor(widgets.hintColor());
        chooseButton = widgets.actionButton(pane, "Choose Skyrim folder",
                v -> listener.onChooseFolderRequested());
        allFilesButton = widgets.actionButton(pane, "All files access",
                v -> listener.onAllFilesRequested());
        refresh();
    }

    @Override
    public View initialFocus() {
        return chooseButton;
    }

    /** Recomputes the status from the settings. */
    public void refresh() {
        String path = settings.folder();
        problems = check.problems(path);
        if (pathText != null) {
            pathText.setText(path == null
                    ? "No folder chosen yet. Copy your Skyrim folder (with the 3 extra files) anywhere "
                      + "outside Android/data, e.g. Internal storage/Skyrim, then choose it here."
                    : path);
            detailText.setText(problems.isEmpty()
                    ? "Ready - everything the port needs is here."
                    : String.join("\n", problems));
            detailText.setTextColor(problems.isEmpty() ? widgets.okColor() : widgets.badColor());
            allFilesButton.setVisibility(storage.allFilesGranted() ? View.GONE : View.VISIBLE);
        }
    }

    public boolean ready() {
        return problems.isEmpty();
    }

    public List<String> problems() {
        return problems;
    }

    /** One line for the prelaunch corner: the status of the folder. */
    public String statusLine() {
        return problems.isEmpty() ? "Skyrim ready" : "Not ready: " + String.join(", ", problems);
    }

    /** Shows a play-time failure (the settings could not be written). */
    public void error(String message) {
        if (detailText != null) {
            detailText.setText(message);
            detailText.setTextColor(widgets.badColor());
        }
    }
}
