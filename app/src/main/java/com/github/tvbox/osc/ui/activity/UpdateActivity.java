package com.github.tvbox.osc.ui.activity;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.lifecycle.ViewModelProvider;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.ui.dialog.BaseDialog;
import com.github.tvbox.osc.update.UpdateArtifact;
import com.github.tvbox.osc.update.UpdateCleanup;
import com.github.tvbox.osc.update.UpdateState;
import com.github.tvbox.osc.update.UpdateViewModel;

import java.io.File;

/** An explicit, user-driven update flow; networking and APK validation belong to the VM. */
public class UpdateActivity extends BaseActivity {
    private static final int REQUEST_SOURCE_PERMISSION = 4101;
    private static final int REQUEST_INSTALL = 4102;
    private static final String KEY_DRAFT = "update.addressDraft";
    private static final String KEY_INSTALL_REQUESTED = "update.installRequested";
    private static final String KEY_PERMISSION_VALIDATION = "update.permissionValidation";
    private static final String KEY_PERMISSION_RESULT = "update.permissionResult";
    private static final String KEY_INSTALL_RESULT = "update.installResult";
    private static final String KEY_INSTALL_HOLD = "update.installHold";
    private static final String KEY_NOTICE = "update.notice";

    private UpdateViewModel vm;
    private UpdateState state;
    private EditText address;
    private TextView addressHelp, status, detail, notice, artifact, notes, progressText, action;
    private View save, clear, check, cancel, notesScroll;
    private ProgressBar progress;
    private BaseDialog confirmation;
    private Bundle creationState;
    private boolean installRequested;
    private boolean permissionReturnValidation;
    private boolean awaitingPermissionResult;
    private boolean awaitingInstallResult;
    private String installHold;
    private String noticeText = "";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // BaseActivity calls init() from its onCreate(), so restore action gates before observing.
        creationState = savedInstanceState;
        super.onCreate(savedInstanceState);
        creationState = null;
    }

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_update;
    }

    @Override
    protected void init() {
        vm = new ViewModelProvider(this).get(UpdateViewModel.class);
        address = findViewById(R.id.updateAddress);
        addressHelp = findViewById(R.id.updateAddressHelp);
        status = findViewById(R.id.updateStatus);
        detail = findViewById(R.id.updateDetail);
        notice = findViewById(R.id.updateNotice);
        artifact = findViewById(R.id.updateArtifact);
        notes = findViewById(R.id.updateNotes);
        notesScroll = findViewById(R.id.updateNotesScroll);
        progress = findViewById(R.id.updateProgress);
        progressText = findViewById(R.id.updateProgressText);
        action = findViewById(R.id.updateAction);
        save = findViewById(R.id.updateSave);
        clear = findViewById(R.id.updateClear);
        check = findViewById(R.id.updateCheck);
        cancel = findViewById(R.id.updateCancel);
        showCurrentVersion();

        address.setText(creationState == null ? savedAddress()
                : creationState.getString(KEY_DRAFT, savedAddress()));
        if (creationState != null) {
            // Only a retained instance may continue a user-initiated validation. A fresh VM
            // after process death must not treat its initial READY as permission to install.
            boolean retained = Boolean.TRUE.equals(getLastCustomNonConfigurationInstance());
            installRequested = retained && creationState.getBoolean(KEY_INSTALL_REQUESTED);
            permissionReturnValidation = retained && creationState.getBoolean(KEY_PERMISSION_VALIDATION);
            awaitingPermissionResult = creationState.getBoolean(KEY_PERMISSION_RESULT);
            awaitingInstallResult = creationState.getBoolean(KEY_INSTALL_RESULT);
            installHold = creationState.getString(KEY_INSTALL_HOLD);
            noticeText = creationState.getString(KEY_NOTICE, "");
        }
        address.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                render();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        findViewById(R.id.updateBack).setOnClickListener(view -> onBackPressed());
        save.setOnClickListener(view -> saveAddress(address.getText().toString().trim()));
        clear.setOnClickListener(view -> {
            if (blocked()) return;
            confirm(getString(R.string.update_clear_confirm), R.string.update_clear,
                    () -> saveAddress(""));
        });
        check.setOnClickListener(view -> {
            if (blocked()) return;
            if (dirty()) {
                showNotice(R.string.update_save_first);
                return;
            }
            if (TextUtils.isEmpty(savedAddress())) {
                showNotice(R.string.update_unconfigured);
                return;
            }
            noticeText = "";
            vm.check();
        });
        action.setOnClickListener(view -> {
            if (blocked() || dirty()) return;
            if (available()) {
                confirmDownload();
            } else if (ready()) {
                noticeText = "";
                installRequested = true;
                // READY produced by download alone never reaches the installer.
                vm.validateForInstall();
            }
        });
        cancel.setOnClickListener(view -> {
            if (awaitingPermissionResult || awaitingInstallResult) return;
            installRequested = false;
            permissionReturnValidation = false;
            noticeText = getString(R.string.update_operation_cancelled);
            vm.cancel();
            render();
        });
        vm.getState().observe(this, next -> {
            state = next;
            if (next != null) {
                switch (next.phase) {
                    case READY:
                        if (permissionReturnValidation) {
                            permissionReturnValidation = false;
                            installRequested = false;
                            noticeText = getString(R.string.update_permission_ready);
                        } else if (installRequested && !awaitingPermissionResult && !awaitingInstallResult) {
                            installRequested = false;
                            render();
                            installValidatedApk();
                        }
                        break;
                    case ERROR:
                    case CANCELLED:
                    case UNCONFIGURED:
                    case IDLE:
                    case CURRENT:
                    case AVAILABLE:
                    case UPDATED:
                        installRequested = false;
                        permissionReturnValidation = false;
                        break;
                    default:
                        break;
                }
            }
            render();
        });
        render();
        if (creationState == null) {
            if (TextUtils.isEmpty(savedAddress())) address.requestFocus();
            else check.requestFocus();
        }
        // Deliberately do not check on entry, address save, or resume.
    }

    private void showCurrentVersion() {
        TextView currentVersion = findViewById(R.id.updateCurrentVersion);
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
            currentVersion.setText(getString(R.string.update_current_version, info.versionName, code));
        } catch (PackageManager.NameNotFoundException e) {
            currentVersion.setText(R.string.update_version_unknown);
        }
    }

    private String savedAddress() {
        String value = vm.getAddress();
        return value == null ? "" : value;
    }

    private boolean dirty() {
        return !address.getText().toString().trim().equals(savedAddress());
    }

    private boolean busy() {
        return vm.isBusy() || (state != null && state.isBusy());
    }

    private boolean blocked() {
        return busy() || installRequested || permissionReturnValidation
                || awaitingPermissionResult || awaitingInstallResult;
    }

    private boolean available() {
        if (state == null) return false;
        switch (state.phase) {
            case AVAILABLE: return state.artifact != null;
            case ERROR:
            case CANCELLED: return state.artifact != null;
            default: return false;
        }
    }

    private boolean ready() {
        if (state == null) return false;
        switch (state.phase) {
            case READY: return true;
            default: return false;
        }
    }

    private void saveAddress(String value) {
        if (blocked()) return;
        try {
            vm.saveAddress(value);
            address.setText(savedAddress());
            noticeText = getString(TextUtils.isEmpty(savedAddress())
                    ? R.string.update_address_cleared : R.string.update_address_saved);
        } catch (RuntimeException e) {
            noticeText = "";
            vm.setError(TextUtils.isEmpty(e.getMessage())
                    ? getString(R.string.update_address_invalid) : e.getMessage());
        }
        render();
    }

    private void confirmDownload() {
        if (state.artifact == null) {
            fail(R.string.update_artifact_missing);
            return;
        }
        UpdateArtifact selected = state.artifact;
        confirm(getString(R.string.update_download_confirm, selected.versionName,
                selected.versionCode, sizeText(selected.sizeBytes), savedAddress()),
                R.string.update_download, () -> {
                    if (!blocked() && !dirty() && available() && state.artifact == selected) {
                        noticeText = "";
                        vm.download();
                    }
                });
    }

    private String sizeText(long size) {
        return size > 0 ? Formatter.formatFileSize(this, size) : getString(R.string.update_size_unknown);
    }

    private void confirm(String message, int proceedLabel, Runnable proceed) {
        if (isFinishing() || isDestroyed() || confirmation != null) return;
        BaseDialog dialog = new BaseDialog(this) {
            private boolean initialFocusPending = true;

            @Override
            public void onWindowFocusChanged(boolean hasFocus) {
                super.onWindowFocusChanged(hasFocus);
                if (hasFocus && initialFocusPending) {
                    initialFocusPending = false;
                    findViewById(R.id.updateConfirmCancel).requestFocus();
                }
            }
        };
        confirmation = dialog;
        dialog.setContentView(R.layout.dialog_update_confirm);
        dialog.setCanceledOnTouchOutside(false);
        ((TextView) dialog.findViewById(R.id.updateConfirmMessage)).setText(message);
        TextView positive = dialog.findViewById(R.id.updateConfirmProceed);
        positive.setText(proceedLabel);
        positive.setOnClickListener(view -> {
            dialog.dismiss();
            if (!isFinishing() && !isDestroyed()) proceed.run();
        });
        View negative = dialog.findViewById(R.id.updateConfirmCancel);
        negative.setOnClickListener(view -> dialog.dismiss());
        dialog.setOnDismissListener(ignored -> {
            if (confirmation == dialog) confirmation = null;
        });
        dialog.show();
        // A held remote OK key must never approve a dangerous action by default.
        negative.requestFocus();
    }

    private void installValidatedApk() {
        if (!ready() || busy() || dirty() || isFinishing() || isDestroyed()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                if (!getPackageManager().canRequestPackageInstalls()) {
                    showNotice(R.string.update_permission_needed);
                    confirm(getString(R.string.update_permission_confirm), R.string.update_open_settings,
                            this::openSourcePermission);
                    return;
                }
            } catch (SecurityException e) {
                fail(R.string.update_permission_failed);
                return;
            }
        }
        File apk = vm.getApkFile();
        if (apk == null) {
            fail(R.string.update_apk_missing);
            return;
        }
        boolean installerOpened = false;
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri(getString(R.string.update_title), uri));
            intent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            if (intent.resolveActivity(getPackageManager()) == null) {
                fail(R.string.update_installer_missing);
                return;
            }
            installHold = UpdateCleanup.holdForInstall(this, state.artifact);
            awaitingInstallResult = true;
            startActivityForResult(intent, REQUEST_INSTALL);
            installerOpened = true;
            noticeText = getString(R.string.update_install_handoff);
            vm.installHandedOff();
        } catch (ActivityNotFoundException e) {
            releaseInstallHold();
            awaitingInstallResult = false;
            fail(R.string.update_installer_missing);
        } catch (RuntimeException e) {
            if (!installerOpened) {
                releaseInstallHold();
                awaitingInstallResult = false;
                fail(R.string.update_install_failed);
            } else {
                showNotice(R.string.update_install_handoff);
            }
        }
        render();
    }

    private void releaseInstallHold() {
        UpdateCleanup.releaseInstall(this, installHold);
        installHold = null;
    }

    private void openSourcePermission() {
        if (blocked() || !ready() || dirty()) return;
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()));
        try {
            if (intent.resolveActivity(getPackageManager()) == null) {
                fail(R.string.update_permission_settings_missing);
                return;
            }
            awaitingPermissionResult = true;
            startActivityForResult(intent, REQUEST_SOURCE_PERMISSION);
            showNotice(R.string.update_permission_waiting);
        } catch (ActivityNotFoundException e) {
            awaitingPermissionResult = false;
            fail(R.string.update_permission_settings_missing);
        } catch (SecurityException e) {
            awaitingPermissionResult = false;
            fail(R.string.update_permission_failed);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SOURCE_PERMISSION) {
            awaitingPermissionResult = false;
            installRequested = false;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        && !getPackageManager().canRequestPackageInstalls()) {
                    fail(R.string.update_permission_denied);
                    return;
                }
                permissionReturnValidation = true;
                showNotice(R.string.update_permission_revalidating);
                // Authorization return is not an installation request and cannot reopen settings.
                vm.validateForInstall();
            } catch (SecurityException e) {
                permissionReturnValidation = false;
                fail(R.string.update_permission_failed);
            }
        } else if (requestCode == REQUEST_INSTALL) {
            releaseInstallHold();
            awaitingInstallResult = false;
            installRequested = false;
            permissionReturnValidation = false;
            noticeText = getString(resultCode == RESULT_OK ? R.string.update_install_returned
                    : resultCode == RESULT_CANCELED ? R.string.update_install_cancelled
                    : R.string.update_install_return_failed);
            // A system result is not proof of success; the VM checks the installed version.
            vm.installationReturned();
            showCurrentVersion();
        }
        render();
    }

    private void fail(int message) {
        installRequested = false;
        permissionReturnValidation = false;
        noticeText = "";
        vm.setError(getString(message));
        render();
    }

    private void showNotice(int message) {
        noticeText = getString(message);
        render();
    }

    private int statusTitle() {
        if (state == null) return R.string.update_idle;
        switch (state.phase) {
            case UNCONFIGURED: return R.string.update_unconfigured;
            case IDLE: return R.string.update_idle;
            case CHECKING: return R.string.update_checking;
            case CURRENT: return R.string.update_current;
            case AVAILABLE: return R.string.update_available;
            case DOWNLOADING: return R.string.update_downloading;
            case VALIDATING: return R.string.update_validating;
            case READY: return R.string.update_ready;
            case HANDOFF: return R.string.update_handoff;
            case UPDATED: return R.string.update_updated;
            case CANCELLED: return R.string.update_cancelled;
            case ERROR: return R.string.update_error;
            default: return R.string.update_idle;
        }
    }

    private void render() {
        View focused = getCurrentFocus();
        boolean blocked = blocked();
        boolean dirty = dirty();
        setEnabled(address, !blocked);
        setEnabled(save, !blocked && dirty);
        setEnabled(clear, !blocked && (!TextUtils.isEmpty(savedAddress())
                || address.length() > 0));
        setEnabled(check, !blocked && !dirty && !TextUtils.isEmpty(savedAddress()));
        addressHelp.setText(dirty ? R.string.update_save_first : R.string.update_address_help);
        status.setText(statusTitle());
        detail.setText(state == null || TextUtils.isEmpty(state.detail) ? "" : state.detail);
        detail.setVisibility(TextUtils.isEmpty(detail.getText()) ? View.GONE : View.VISIBLE);
        notice.setText(noticeText);
        notice.setVisibility(TextUtils.isEmpty(noticeText) ? View.GONE : View.VISIBLE);

        UpdateArtifact value = state == null ? null : state.artifact;
        artifact.setVisibility(value == null ? View.GONE : View.VISIBLE);
        notesScroll.setVisibility(value == null ? View.GONE : View.VISIBLE);
        if (value != null) {
            artifact.setText(getString(R.string.update_new_version, value.versionName,
                    value.versionCode, sizeText(value.sizeBytes)));
            notes.setText(getString(R.string.update_notes, TextUtils.isEmpty(value.notes)
                    ? getString(R.string.update_no_notes) : value.notes));
        }
        boolean working = false;
        boolean downloading = false;
        if (state != null) {
            switch (state.phase) {
                case DOWNLOADING: downloading = true; working = true; break;
                case CHECKING:
                case VALIDATING: working = true; break;
                default: break;
            }
        }
        progress.setVisibility(working ? View.VISIBLE : View.GONE);
        progressText.setVisibility(downloading ? View.VISIBLE : View.GONE);
        if (working) {
            long total = value == null ? 0 : value.sizeBytes;
            progress.setIndeterminate(!downloading || total <= 0);
            if (downloading) {
                long downloaded = Math.max(0, state.downloadedBytes);
                if (total > 0) {
                    progress.setProgress((int) Math.min(1000, 1000d * downloaded / total));
                    progressText.setText(getString(R.string.update_progress,
                            Formatter.formatFileSize(this, downloaded), sizeText(total)));
                } else {
                    progressText.setText(getString(R.string.update_progress_unknown,
                            Formatter.formatFileSize(this, downloaded)));
                }
            }
        }
        action.setVisibility(available() || ready() ? View.VISIBLE : View.GONE);
        action.setText(ready() ? R.string.update_install : R.string.update_download);
        setEnabled(action, !blocked && !dirty);
        cancel.setVisibility(working ? View.VISIBLE : View.GONE);
        setEnabled(cancel, !awaitingPermissionResult && !awaitingInstallResult);

        if (focused != null && (!focused.isEnabled() || !focused.isShown())) {
            if (cancel.isShown() && cancel.isEnabled()) cancel.requestFocus();
            else if (check.isEnabled()) check.requestFocus();
            else if (address.isEnabled()) address.requestFocus();
            else findViewById(R.id.updateBack).requestFocus();
        }
    }

    private void setEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setFocusable(enabled);
        view.setAlpha(enabled ? 1f : 0.45f);
    }

    @Override
    public Object onRetainCustomNonConfigurationInstance() {
        // A marker only: never retain an Activity, View, dialog or callback.
        return Boolean.TRUE;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString(KEY_DRAFT, address.getText().toString());
        outState.putBoolean(KEY_INSTALL_REQUESTED, installRequested);
        outState.putBoolean(KEY_PERMISSION_VALIDATION, permissionReturnValidation);
        outState.putBoolean(KEY_PERMISSION_RESULT, awaitingPermissionResult);
        outState.putBoolean(KEY_INSTALL_RESULT, awaitingInstallResult);
        outState.putString(KEY_INSTALL_HOLD, installHold);
        outState.putString(KEY_NOTICE, noticeText);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        installRequested = false;
        permissionReturnValidation = false;
        vm.cancel();
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (confirmation != null) confirmation.dismiss();
        // onStop intentionally does not cancel while the system authorization/installer is open.
        // Retained VM work survives configuration changes; its onCleared() owns final cleanup.
        if (vm != null && !isChangingConfigurations()
                && !awaitingPermissionResult && !awaitingInstallResult) {
            vm.cancel();
        }
        super.onDestroy();
    }
}
