package com.tj.skyrim.launcher;

import android.content.ContentResolver;
import android.net.Uri;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * The "GPU driver" tab: which driver is in use (the system one by default),
 * installing a Turnip / Mr. Purple zip, and removing the selected driver.
 * The choice is passed to the game on the next Play.
 */
public final class DriverSection implements LauncherWindow.Content {

    /** The activity's side of the zip flow (it owns the pickers). */
    public interface Listener {
        /** "Choose driver zip" was pressed. */
        void onChooseZipRequested();
    }

    private static final String SYSTEM_DRIVER = "System driver";

    private final Widgets widgets;
    private final DriverRegistry registry;
    private final Listener listener;

    private TextView status;
    private SelectRow driverRow;

    public DriverSection(Widgets widgets, DriverRegistry registry, Listener listener) {
        this.widgets = widgets;
        this.registry = registry;
        this.listener = listener;
    }

    @Override
    public CharSequence title() {
        return "GPU driver";
    }

    @Override
    public void build(LinearLayout pane) {
        status = widgets.text(pane, "", 14, false);
        status.setTextColor(widgets.hintColor());

        driverRow = SelectRow.stepper(widgets, "Driver", labels(), selectedIndex(),
                r -> { saveSelection(r.selected()); updateStatus(); });
        pane.addView(driverRow);

        widgets.actionButton(pane, "Choose driver zip", v -> listener.onChooseZipRequested());
        widgets.actionButton(pane, "Remove selected driver", v -> removeSelected());
        updateStatus();
    }

    @Override
    public View initialFocus() {
        return driverRow;
    }

    /** Re-reads the installed drivers and redraws the row. */
    public void refresh() {
        registry.refresh();
        if (driverRow != null) {
            driverRow.items(labels(), selectedIndex());
            updateStatus();
        }
    }

    /** The driver to launch the game with; null for the system driver. */
    public Driver selectedDriver() {
        int index = driverRow != null ? driverRow.selected() : selectedIndex();
        List<Driver> drivers = registry.drivers();
        return index > 0 && index - 1 < drivers.size() ? drivers.get(index - 1) : null;
    }

    /** Installs a picked zip; the status line reports how it went. */
    public void onZipPicked(Uri zipUri, ContentResolver resolver) {
        DriverRegistry.InstallResult result = registry.install(zipUri, resolver);
        refresh();
        if (status != null) {
            status.setTextColor(result.driver == null ? widgets.badColor() : widgets.okColor());
            status.setText(result.driver != null
                    ? "Installed " + result.driver.label + " - applies on the next Play."
                    : result.error);
        }
    }

    /* ------------------------------------------------------------------ */

    /** Persists the stepper choice: it must survive the next launch. */
    private void saveSelection(int index) {
        List<Driver> drivers = registry.drivers();
        registry.select(index > 0 && index - 1 < drivers.size() ? drivers.get(index - 1) : null);
    }

    private void removeSelected() {
        Driver selected = selectedDriver();
        if (selected != null) registry.remove(selected);
        refresh();
    }

    private void updateStatus() {
        if (status == null) return;
        Driver selected = selectedDriver();
        status.setTextColor(widgets.hintColor());
        status.setText(selected == null
                ? "Turnip / Mr. Purple driver zips (the same ones emulators use). "
                  + "The system driver is used; a picked one applies on the next Play."
                : "GPU driver: " + selected.label + "  (applies on the next Play)");
    }

    private String[] labels() {
        List<String> labels = new ArrayList<>();
        labels.add(SYSTEM_DRIVER);
        for (Driver d : registry.drivers()) labels.add(d.label);
        return labels.toArray(new String[0]);
    }

    private int selectedIndex() {
        Driver selected = registry.selected();
        if (selected == null) return 0;
        for (int i = 0; i < registry.drivers().size(); i++)
            if (registry.drivers().get(i).dirName().equals(selected.dirName())) return i + 1;
        return 0;
    }
}
