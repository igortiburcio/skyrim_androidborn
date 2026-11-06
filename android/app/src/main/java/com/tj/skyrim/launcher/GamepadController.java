package com.tj.skyrim.launcher;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * Translates gamepad input into what Android widgets already understand:
 * <ul>
 *   <li>BUTTON_A becomes DPAD_CENTER (rows and buttons treat it as "confirm"),</li>
 *   <li>BUTTON_B goes back (closes the settings window, or leaves the screen),</li>
 *   <li>the bumpers switch the settings window's tabs,</li>
 *   <li>the left stick — or a D-pad reported as hat axes, which some controllers
 *       do — becomes D-pad keys, with key repeat, because Android repeats key
 *       events but not axes.</li>
 * </ul>
 * A press that has to restore the focus is consumed instead of also moving it.
 */
public final class GamepadController {

    /** What the controller needs from the window it drives. */
    public interface Host {
        View currentFocus();

        /** Puts the focus back on the first control (after it was lost). */
        void focusDefaultControl();

        /** The "back" action (BUTTON_B). */
        void goBack();

        /** Lets the window itself handle an event the controller remapped. */
        boolean passThrough(KeyEvent event);

        /** LB: the previous settings tab. */
        boolean previousTab();

        /** RB: the next settings tab. */
        boolean nextTab();
    }

    private static final int FIRST_REPEAT_DELAY_MS = 400;
    private static final int REPEAT_INTERVAL_MS = 120;
    private static final float STICK_ON_THRESHOLD = 0.6f;
    private static final float STICK_OFF_THRESHOLD = 0.35f;

    private final Host host;
    private final Handler repeatHandler = new Handler(Looper.getMainLooper());

    private int horizontalDirection;  /* -1 = left,  0 = centred, +1 = right */
    private int verticalDirection;    /* -1 = up,    0 = centred, +1 = down  */
    private int heldKeyCode;          /* 0 = no stick direction is repeating */

    /** Re-sends the held stick key like a keyboard auto-repeat would. */
    private final Runnable repeater = new Runnable() {
        @Override public void run() {
            if (heldKeyCode == 0) return;
            if (!isGamepadConnected()) {   /* unplugged while the stick was held */
                reset();
                return;
            }
            sendSyntheticKey(heldKeyCode);
            repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS);
        }
    };

    public GamepadController(Host host) {
        this.host = host;
    }

    /** Entry point for the window's key events; true when consumed. */
    public boolean dispatchKeyEvent(KeyEvent event) {
        return handleKey(event);
    }

    /** Entry point for the window's motion events; true when consumed. */
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (!isGamepadEvent(event) || event.getAction() != MotionEvent.ACTION_MOVE) return false;

        /* Some controllers report the D-pad as hat axes; otherwise use the stick. */
        float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        float horizontalAxis = hatX != 0 ? hatX : event.getAxisValue(MotionEvent.AXIS_X);
        float verticalAxis = hatY != 0 ? hatY : event.getAxisValue(MotionEvent.AXIS_Y);

        horizontalDirection = resolveAxisDirection(horizontalDirection, horizontalAxis);
        verticalDirection = resolveAxisDirection(verticalDirection, verticalAxis);

        int keyCode = directionKeyCode(verticalDirection, horizontalDirection);
        if (keyCode != heldKeyCode) {
            heldKeyCode = keyCode;
            repeatHandler.removeCallbacks(repeater);
            if (keyCode != 0) {
                sendSyntheticKey(keyCode);
                repeatHandler.postDelayed(repeater, FIRST_REPEAT_DELAY_MS);
            }
        }
        return true;
    }

    /** Stops the stick repeat and forgets the directions (activity paused). */
    public void pause() {
        reset();
    }

    /* ------------------------------------------------------------------ */

    private boolean handleKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean keyDown = event.getAction() == KeyEvent.ACTION_DOWN;
        View focusedView = host.currentFocus();

        if (keyDown && focusedView == null) {
            host.focusDefaultControl();
            focusedView = host.currentFocus();
            /* The press that restores focus must not also move it. */
            if (isDirectionKey(keyCode)) return true;
        }

        if (keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            /* Holding A must not confirm over and over. */
            if (event.getRepeatCount() > 0) return true;
            return host.passThrough(remapKeyCode(event, KeyEvent.KEYCODE_DPAD_CENTER));
        }

        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            if (keyDown) host.goBack();
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_PAGE_UP) {
            if (keyDown) host.previousTab();
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_BUTTON_R1 || keyCode == KeyEvent.KEYCODE_PAGE_DOWN) {
            if (keyDown) host.nextTab();
            return true;
        }

        if (isDirectionKey(keyCode)) {
            /* Rows and tabs handle left/right themselves, and the framework may
             * move the focus; only when nobody did, we navigate on our own. */
            if (host.passThrough(event)) return true;
            if (keyDown) moveFocus(keyCode);
            return true;
        }

        return host.passThrough(event);
    }

    /**
     * Moves the focus ourselves: the framework only navigates when a key was
     * left completely unhandled, which depends on touch mode and on what the
     * window did with it - doing it here makes the D-pad (and the stick's
     * synthetic keys) navigate no matter what.
     */
    private void moveFocus(int keyCode) {
        View focused = host.currentFocus();
        if (focused == null) {
            host.focusDefaultControl();
            return;
        }
        int direction;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: direction = View.FOCUS_UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: direction = View.FOCUS_DOWN; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: direction = View.FOCUS_LEFT; break;
            default: direction = View.FOCUS_RIGHT; break;
        }
        View next = focused.focusSearch(direction);
        if (next != null && next != focused) next.requestFocus(direction);
    }

    private void reset() {
        repeatHandler.removeCallbacks(repeater);
        heldKeyCode = 0;
        horizontalDirection = 0;
        verticalDirection = 0;
    }

    /** A down/up pair that goes through the same handling as a real key. */
    private void sendSyntheticKey(int keyCode) {
        long now = SystemClock.uptimeMillis();
        handleKey(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0));
        handleKey(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0));
    }

    /** The same event with another keycode, keeping its timing and modifiers. */
    private static KeyEvent remapKeyCode(KeyEvent original, int newKeyCode) {
        return new KeyEvent(original.getDownTime(), original.getEventTime(), original.getAction(),
                newKeyCode, 0, original.getMetaState());
    }

    private static boolean isDirectionKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private static boolean isGamepadEvent(InputEvent event) {
        return event.isFromSource(InputDevice.SOURCE_GAMEPAD)
                || event.isFromSource(InputDevice.SOURCE_JOYSTICK);
    }

    private static boolean isGamepadConnected() {
        for (int deviceId : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(deviceId);
            if (device == null) continue;
            int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                return true;
            }
        }
        return false;
    }

    /**
     * Turns a continuous axis value into -1, 0 or +1. The two thresholds overlap
     * so a resting stick does not flicker between moving and stopped.
     */
    private static int resolveAxisDirection(int previousDirection, float axisValue) {
        if (axisValue >= STICK_ON_THRESHOLD) return 1;
        if (axisValue <= -STICK_ON_THRESHOLD) return -1;
        if (axisValue > -STICK_OFF_THRESHOLD && axisValue < STICK_OFF_THRESHOLD) return 0;
        return previousDirection;
    }

    /** Vertical wins over horizontal so a diagonal push produces one direction. */
    private static int directionKeyCode(int vertical, int horizontal) {
        if (vertical < 0) return KeyEvent.KEYCODE_DPAD_UP;
        if (vertical > 0) return KeyEvent.KEYCODE_DPAD_DOWN;
        if (horizontal < 0) return KeyEvent.KEYCODE_DPAD_LEFT;
        if (horizontal > 0) return KeyEvent.KEYCODE_DPAD_RIGHT;
        return 0;
    }
}
