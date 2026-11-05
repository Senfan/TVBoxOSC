package com.github.tvbox.osc.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class UpdateCleanupReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        PendingResult result = goAsync();
        try {
            UpdateCleanup.request(context, result::finish);
        } catch (RuntimeException e) {
            result.finish();
        }
    }
}
