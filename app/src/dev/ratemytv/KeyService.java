package dev.ratemytv;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Watches hardware keys. A long press of the Netflix key opens Rate My TV.
 * Logs every key so the exact code can be confirmed against the real remote.
 */
public class KeyService extends AccessibilityService {
    static final String TAG = "RATEMYTV_KEY";
    static final long LONG_MS = 600;
    long downAt = -1;
    int downCode = -1;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
        Log.i(TAG, "connected");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }

    @Override public void onInterrupt() { }

    @Override protected boolean onKeyEvent(KeyEvent ev) {
        Log.i(TAG, "key=" + ev.getKeyCode() + " action=" + ev.getAction()
                + " repeat=" + ev.getRepeatCount());
        if (ev.getAction() == KeyEvent.ACTION_DOWN) {
            if (downCode != ev.getKeyCode()) {
                downCode = ev.getKeyCode();
                downAt = System.currentTimeMillis();
            }
            return false;
        }
        if (ev.getAction() == KeyEvent.ACTION_UP && ev.getKeyCode() == downCode) {
            long held = System.currentTimeMillis() - downAt;
            int code = ev.getKeyCode();
            downCode = -1;
            downAt = -1;
            if (held >= LONG_MS && isTarget(code)) {
                Log.i(TAG, "LONG " + code + " " + held + "ms -> overlay");
                OverlayController.show((Context) this);
                return true;
            }
        }
        return false;
    }

    boolean isTarget(int code) {
        return code == KeyEvent.KEYCODE_BUTTON_4;
    }
}
