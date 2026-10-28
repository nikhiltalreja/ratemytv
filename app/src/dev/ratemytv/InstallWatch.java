package dev.ratemytv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Package installs and removals, for the Watch These rail.
 *
 * A runtime receiver with the same filter registers cleanly on this TV and never fires;
 * a manifest receiver does. The only thing it needs to do is ask the card to re-check
 * which services it can launch, and that is a no-op when the card is closed.
 */
public class InstallWatch extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String pkg = i.getData() == null ? null : i.getData().getSchemeSpecificPart();
        Log.i("RATEMYTV", "package event " + i.getAction() + ": " + pkg);
        OverlayController.checkInstalls();
    }
}
