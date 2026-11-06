package com.tj.skyrim.launcher;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/**
 * One setting row in the Dusklight style: an uppercase key on the left, the
 * value on the right, and ‹ › chevrons for a stepper (hidden for On/Off rows,
 * like Dusklight's bool button). The whole row is one focus target: ←/→ change
 * the value, A advances or toggles, and touching the row (or a chevron) does
 * the same. Focused, it gets the gold ring over the dark row fill.
 */
public final class SelectRow extends LinearLayout {

    /** Notified after the value changed through input (not programmatic sets). */
    public interface Listener {
        void onChanged(SelectRow row);
    }

    private final Widgets widgets;
    private final TextView key;
    private final TextView chevronPrev;
    private final TextView value;
    private final TextView chevronNext;
    private final Listener listener;
    private final boolean boolRow;

    private String[] items;
    private int position;

    public static SelectRow stepper(Widgets widgets, String keyText, String[] items,
                                    int selected, Listener listener) {
        return new SelectRow(widgets, keyText, items, selected, false, listener);
    }

    /** An On/Off row (Dusklight's BoolButton: confirm/left/right toggles). */
    public static SelectRow toggle(Widgets widgets, String keyText, boolean on, Listener listener) {
        return new SelectRow(widgets, keyText, new String[] {"Off", "On"}, on ? 1 : 0, true, listener);
    }

    private SelectRow(Widgets widgets, String keyText, String[] items, int selected,
                      boolean boolRow, Listener listener) {
        super(widgets.context());
        this.widgets = widgets;
        this.items = items;
        this.boolRow = boolRow;
        this.listener = listener;
        this.position = Math.min(Math.max(0, selected), items.length - 1);

        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(widgets.dp(54));
        setPadding(widgets.dp(16), 0, widgets.dp(10), 0);
        setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) getLayoutParams()).topMargin = widgets.dp(8);
        widgets.focusable(this);
        setBackground(widgets.fillBackground());

        key = new TextView(getContext());
        key.setText(keyText.toUpperCase(Locale.ROOT));
        key.setTypeface(widgets.fonts().heading);
        key.setTextSize(17);
        key.setLetterSpacing(0.05f);
        key.setTextColor(Widgets.TEXT);
        key.setSingleLine(true);
        key.setEllipsize(TextUtils.TruncateAt.END);
        addView(key, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        value = new TextView(getContext());
        value.setTypeface(widgets.fonts().bodyBold);
        value.setTextSize(19);
        value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.55f));

        chevronPrev = chevron("\u2039");
        chevronNext = chevron("\u203A");
        addView(chevronPrev, new LinearLayout.LayoutParams(
                widgets.dp(26), ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(chevronNext, new LinearLayout.LayoutParams(
                widgets.dp(26), ViewGroup.LayoutParams.WRAP_CONTENT));
        if (boolRow) {
            chevronPrev.setVisibility(GONE);
            chevronNext.setVisibility(GONE);
        }

        updateValue();

        setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN || !isEnabled()) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (boolRow) toggle(); else step(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (boolRow) toggle(); else step(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    activate();
                    return true;
                default:
                    return false;
            }
        });
        setOnClickListener(v -> activate());
        chevronPrev.setOnClickListener(v -> step(-1));
        chevronNext.setOnClickListener(v -> step(1));
    }

    /* ---- state ---- */

    public int selected() {
        return position;
    }

    public void select(int index) {
        position = clamp(index);
        updateValue();
    }

    /** Only for toggle rows. */
    public boolean on() {
        return position == 1;
    }

    /** Only for toggle rows. */
    public void set(boolean on) {
        position = on ? 1 : 0;
        updateValue();
    }

    /** Swaps the item list (the resolution row when the aspect changes). */
    public void items(String[] newItems, int selected) {
        items = newItems;
        position = clamp(selected);
        boolean many = items.length > 1 && !boolRow;
        chevronPrev.setVisibility(many ? VISIBLE : GONE);
        chevronNext.setVisibility(many ? VISIBLE : GONE);
        updateValue();
    }

    public String value() {
        return items[position];
    }

    /** Whether this row is an On/Off one (Dusklight's bool button). */
    public boolean isOnOffRow() {
        return boolRow;
    }

    /** Locked rows (Skyrim.esm) look and behave disabled. */
    public void setRowEnabled(boolean enabled) {
        setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.45f);
        key.setEnabled(enabled);
        value.setEnabled(enabled);
        chevronPrev.setEnabled(enabled);
        chevronNext.setEnabled(enabled);
    }

    /* ------------------------------------------------------------------ */

    private void activate() {
        if (!isEnabled()) return;
        if (boolRow) toggle();
        else step(1);
    }

    private void toggle() {
        position = position == 1 ? 0 : 1;
        updateValue();
        if (listener != null) listener.onChanged(this);
    }

    private void step(int delta) {
        if (items.length == 0) return;
        position = (position + delta + items.length) % items.length;
        updateValue();
        if (listener != null) listener.onChanged(this);
    }

    private int clamp(int index) {
        return Math.min(Math.max(0, index), items.length - 1);
    }

    private void updateValue() {
        value.setText(items[position]);
        if (boolRow) value.setTextColor(position == 1 ? Widgets.GOLD_BRIGHT : Widgets.TEXT_DIM);
        else value.setTextColor(Widgets.TEXT);
    }

    private TextView chevron(String glyph) {
        TextView t = new TextView(getContext());
        t.setText(glyph);
        t.setTypeface(widgets.fonts().bodyBold);
        t.setTextSize(22);
        t.setTextColor(Widgets.GOLD);
        t.setGravity(Gravity.CENTER);
        return t;
    }
}
