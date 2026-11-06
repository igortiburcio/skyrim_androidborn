package com.tj.skyrim.launcher;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * The "Data files" tab: one row per .esm/.esp with its On/Off state and the
 * ▲▼ arrows that move it in the load order. Skyrim.esm shows up locked
 * (always on, like the engine treats it).
 */
public final class PluginsSection implements LauncherWindow.Content {

    private final Widgets widgets;
    private final PluginRegistry registry;

    private LinearLayout pane;
    private View firstRow;

    public PluginsSection(Widgets widgets, PluginRegistry registry) {
        this.widgets = widgets;
        this.registry = registry;
    }

    @Override
    public CharSequence title() {
        return "Data files";
    }

    @Override
    public void build(LinearLayout pane) {
        this.pane = pane;
        render();
    }

    @Override
    public View initialFocus() {
        return firstRow;
    }

    /** Re-reads the Data folder and redraws the rows. */
    public void reload() {
        registry.reload();
        if (pane != null) render();
    }

    /** Saves the order and the active set (when the activity pauses). */
    public void persist() {
        registry.save();
    }

    /** A copy of the load order for the launch plan. */
    public List<Plugin> snapshot() {
        return new ArrayList<>(registry.plugins());
    }

    /* ------------------------------------------------------------------ */

    private void render() {
        pane.removeAllViews();
        firstRow = null;
        if (registry.isEmpty()) {
            TextView empty = widgets.text(pane, "No .esm/.esp in Data (choose the folder first)",
                    14, false);
            empty.setTextColor(widgets.hintColor());
            return;
        }
        for (int i = 0; i < registry.plugins().size(); i++) addRow(i);
    }

    /** [ row: name -> On/Off ]  ▲  ▼ */
    private void addRow(final int index) {
        final Plugin plugin = registry.plugins().get(index);

        LinearLayout wrapper = new LinearLayout(widgets.context());
        wrapper.setOrientation(LinearLayout.HORIZONTAL);
        wrapper.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = widgets.dp(8);

        SelectRow row = SelectRow.toggle(widgets, plugin.name,
                plugin.on || plugin.alwaysOn(),
                r -> {
                    if (!plugin.alwaysOn()) {
                        plugin.on = r.on();
                        registry.save();
                    }
                });
        if (plugin.alwaysOn()) row.setRowEnabled(false);
        wrapper.addView(row, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (firstRow == null) firstRow = row;

        wrapper.addView(arrow("\u25B2", index, -1), new LinearLayout.LayoutParams(
                widgets.dp(46), ViewGroup.LayoutParams.WRAP_CONTENT));
        wrapper.addView(arrow("\u25BC", index, 1), new LinearLayout.LayoutParams(
                widgets.dp(46), ViewGroup.LayoutParams.WRAP_CONTENT));

        pane.addView(wrapper, lp);
    }

    private Button arrow(String glyph, final int index, final int by) {
        return widgets.compactButton(glyph, v -> move(index, by));
    }

    /** Moves a plugin and keeps the focus on its arrow, so a gamepad can hold ▲/▼. */
    private void move(int index, int by) {
        registry.move(index, by);
        registry.save();
        render();
        int to = index + by;
        if (to >= 0 && to < pane.getChildCount()) {
            LinearLayout wrapper = (LinearLayout) pane.getChildAt(to);
            // [0]=row [1]=▲ [2]=▼
            View target = wrapper.getChildAt(by < 0 ? 1 : 2);
            if (target != null) target.requestFocus();
        }
    }
}
