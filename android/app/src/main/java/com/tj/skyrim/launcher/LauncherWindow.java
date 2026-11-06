package com.tj.skyrim.launcher;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The settings window over the prelaunch screen, in the Dusklight shape: a
 * scrim, a rounded panel with a 2dp bronze border, a 64dp tab bar (uppercase
 * headings, the selected tab underlined in gold) and one scrollable pane per
 * tab. Tabs are switched with a tap, with LB/RB (see GamepadController) or
 * the tab bar itself; every pane remembers where its focus was.
 */
public final class LauncherWindow {

    /** One tab: its title, the pane content and where the focus starts. */
    public interface Content {
        CharSequence title();

        void build(LinearLayout pane);

        View initialFocus();
    }

    /** What the activity wants to know about the window. */
    public interface Listener {
        void onWindowOpened(int tabIndex);

        void onWindowClosed(int tabIndex);
    }

    private static final int TAB_BAR_DP = 64;
    private static final int MAX_WINDOW_W_DP = 1088, MAX_WINDOW_H_DP = 768;

    private final Activity activity;
    private final Widgets widgets;
    private final FrameLayout overlay;
    private final LinearLayout panel;
    private final LinearLayout tabBar;
    private final LinearLayout underlineBar;
    private final FrameLayout contentHost;
    private final List<Content> contents = new ArrayList<>();
    private final List<TextView> tabs = new ArrayList<>();
    private final List<View> underlines = new ArrayList<>();
    private final List<ScrollView> paneViews = new ArrayList<>();
    private final List<LinearLayout> panes = new ArrayList<>();
    private final Map<Integer, View> lastFocus = new HashMap<>();

    private Listener listener;
    private int current = -1;

    public LauncherWindow(Activity activity, Widgets widgets, ViewGroup parent) {
        this.activity = activity;
        this.widgets = widgets;

        overlay = new FrameLayout(activity);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setVisibility(View.GONE);

        View scrim = new View(activity);
        scrim.setBackgroundColor(0x99000000);
        scrim.setOnClickListener(v -> close());
        overlay.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Widgets.SURFACE);
        background.setCornerRadius(widgets.dp(14));
        background.setStroke(widgets.dp(2), Widgets.BORDER);
        panel.setBackground(background);
        panel.setElevation(widgets.dp(12));
        overlay.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        tabBar = new LinearLayout(activity);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setBackgroundColor(0x1AD9D9D9);
        panel.addView(tabBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, widgets.dp(TAB_BAR_DP)));
        underlineBar = new LinearLayout(activity);
        underlineBar.setOrientation(LinearLayout.HORIZONTAL);
        panel.addView(underlineBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, widgets.dp(3)));
        View barBorder = new View(activity);
        barBorder.setBackgroundColor(Widgets.BORDER);
        panel.addView(barBorder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, widgets.dp(2)));

        contentHost = new FrameLayout(activity);
        panel.addView(contentHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        parent.addView(overlay);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isOpen() {
        return overlay.getVisibility() == View.VISIBLE;
    }

    /** Tabs can also be prepared at startup, without showing the window:
     * the panes (and their rows) exist before anything reads them. */
    public void prepare(List<Content> tabs) {
        if (!sameTabs(tabs)) rebuildTabs(tabs);
    }

    public void open(List<Content> tabs, int index) {
        if (!sameTabs(tabs)) rebuildTabs(tabs);
        sizePanel();
        overlay.setVisibility(View.VISIBLE);
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(180).start();
        panel.setAlpha(0f);
        panel.setScaleX(0.94f);
        panel.setScaleY(0.94f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start();
        show(index);
        if (listener != null) listener.onWindowOpened(index);
    }

    /** The window is rebuilt when it is opened with another set of tabs
     * (the four settings tabs, or just the wallpaper one). */
    private boolean sameTabs(List<Content> tabs) {
        if (contents.size() != tabs.size()) return false;
        for (int i = 0; i < tabs.size(); i++)
            if (contents.get(i) != tabs.get(i)) return false;
        return true;
    }

    private void rebuildTabs(List<Content> tabs) {
        current = -1;
        contents.clear();
        this.tabs.clear();
        underlines.clear();
        paneViews.clear();
        panes.clear();
        lastFocus.clear();
        tabBar.removeAllViews();
        contentHost.removeAllViews();
        contents.addAll(tabs);
        buildTabs();
    }

    public void close() {
        if (!isOpen()) return;
        int was = current;
        current = -1;
        overlay.animate().alpha(0f).setDuration(120).start();
        panel.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f).setDuration(120)
                .withEndAction(() -> overlay.setVisibility(View.GONE)).start();
        if (listener != null) listener.onWindowClosed(was);
    }

    /** LB/RB: the next tab, wrapping around. */
    public boolean next() {
        if (!isOpen() || contents.size() < 2) return false;
        show((current + 1) % contents.size());
        return true;
    }

    /** LB/RB: the previous tab, wrapping around. */
    public boolean previous() {
        if (!isOpen() || contents.size() < 2) return false;
        show((current - 1 + contents.size()) % contents.size());
        return true;
    }

    public void show(int index) {
        if (index < 0 || index >= contents.size()) return;
        rememberFocus();
        current = index;
        for (int i = 0; i < tabs.size(); i++) {
            boolean selected = i == index;
            tabs.get(i).setAlpha(selected ? 1f : 0.4f);
            underlines.get(i).setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            paneViews.get(i).setVisibility(selected ? View.VISIBLE : View.GONE);
        }
        View focus = lastFocus.get(index);
        if (focus == null || focus.getParent() == null)
            focus = contents.get(index).initialFocus();
        if (focus != null) focus.requestFocus();
    }

    /* ------------------------------------------------------------------ */

    private void buildTabs() {
        for (int i = 0; i < contents.size(); i++) {
            final int index = i;
            Content content = contents.get(i);

            TextView tab = new TextView(activity);
            tab.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            widgets.focusable(tab);
            tab.setOnClickListener(v -> show(index));
            tab.setText(content.title().toString().toUpperCase(Locale.ROOT));
            tab.setTypeface(widgets.fonts().heading);
            tab.setTextSize(17);
            tab.setLetterSpacing(0.08f);
            tab.setTextColor(Widgets.TEXT);
            tab.setGravity(Gravity.CENTER);
            tabBar.addView(tab);
            tabs.add(tab);

            View underline = new View(activity);
            underline.setBackgroundColor(Widgets.GOLD);
            underline.setVisibility(View.INVISIBLE);
            underlineBar.addView(underline, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            underlines.add(underline);

            ScrollView scroll = new ScrollView(activity);
            scroll.setFocusable(false);   /* the rows take the focus, not the scroller */
            LinearLayout pane = new LinearLayout(activity);
            pane.setOrientation(LinearLayout.VERTICAL);
            pane.setPadding(widgets.dp(24), widgets.dp(12), widgets.dp(24), widgets.dp(24));
            scroll.addView(pane, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            scroll.setVisibility(View.GONE);
            contentHost.addView(scroll, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            paneViews.add(scroll);
            panes.add(pane);
            content.build(pane);
        }
    }

    private void sizePanel() {
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        int w = Math.min(dm.widthPixels - widgets.dp(48), widgets.dp(MAX_WINDOW_W_DP));
        int h = Math.min(dm.heightPixels - widgets.dp(48), widgets.dp(MAX_WINDOW_H_DP));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        lp.width = w;
        lp.height = h;
        panel.setLayoutParams(lp);
    }

    private void rememberFocus() {
        if (current < 0) return;
        View focus = activity.getCurrentFocus();
        if (focus != null && isInside(paneViews.get(current), focus))
            lastFocus.put(current, focus);
    }

    private static boolean isInside(ViewGroup root, View view) {
        for (ViewParent p = view.getParent(); p != null; p = p.getParent())
            if (p == root) return true;
        return false;
    }
}
