package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.ui.activity.UpdateActivity;

import org.jetbrains.annotations.NotNull;

public class AboutDialog extends BaseDialog {

    public AboutDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_about);
        TextView version = findViewById(R.id.aboutVersion);
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
            version.setText(context.getString(R.string.update_current_version, info.versionName, code));
        } catch (PackageManager.NameNotFoundException e) {
            version.setText(R.string.update_version_unknown);
        }
        findViewById(R.id.aboutUpdate).setOnClickListener(view -> {
            Intent intent = new Intent(context, UpdateActivity.class);
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            try {
                context.startActivity(intent);
                dismiss();
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(context, R.string.update_entry_unavailable, Toast.LENGTH_LONG).show();
            }
        });
    }
}
