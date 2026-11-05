package com.github.tvbox.osc.update;

import android.app.Application;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.github.tvbox.osc.BuildConfig;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.github.tvbox.osc.update.UpdateState.Phase;

public class UpdateViewModel extends AndroidViewModel {
    private final MutableLiveData<UpdateState> state = new MutableLiveData<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final UpdatePreferences preferences;
    private final File directory;
    private volatile int generation;
    private volatile UpdateClient active;
    private boolean verifiedForInstall;

    public UpdateViewModel(@NonNull Application application) {
        super(application);
        preferences = new UpdatePreferences(application);
        directory = UpdateCleanup.directory(application);
        restore(false);
        UpdateCleanup.request(application);
    }

    public LiveData<UpdateState> getState() { return state; }
    public String getAddress() { return preferences.address(); }
    public boolean isBusy() { return state.getValue() != null && state.getValue().isBusy(); }

    public void saveAddress(String value) {
        String address = value == null ? "" : value.trim();
        if (!address.isEmpty()) address = UpdateUrlPolicy.validate(address, BuildConfig.DEBUG);
        if (address.equals(getAddress())) return;
        stopWork();
        preferences.setAddress(address);
        set(address.isEmpty() ? Phase.UNCONFIGURED : Phase.IDLE, null, 0, "");
    }

    public void check() {
        if (isBusy()) return;
        String address = getAddress();
        if (address.isEmpty()) { set(Phase.UNCONFIGURED, null, 0, "请先保存更新清单地址"); return; }
        UpdateState previous = state.getValue();
        UpdateArtifact fallback = previous == null ? null : previous.artifact;
        int ticket = start(Phase.CHECKING, fallback);
        UpdateClient client = active;
        executor.execute(() -> {
            try {
                UpdateManifest.Selection selection = client.check(address).select(getApplication().getPackageName(),
                        BuildConfig.UPDATE_BRAND, BuildConfig.UPDATE_MODE, BuildConfig.UPDATE_ABI,
                        Build.VERSION.SDK_INT, ApkValidator.installedVersion(getApplication()));
                post(ticket, () -> {
                    // Keep a completed download recoverable until another download replaces it.
                    if (!selection.compatible) set(Phase.ERROR, fallback, 0, "没有匹配当前渠道与系统的更新包");
                    else if (selection.artifact == null) set(Phase.CURRENT, null, 0, "当前渠道已是最新版");
                    else set(Phase.AVAILABLE, selection.artifact, 0, "发现新版本，请确认后下载");
                });
            } catch (Exception e) { failed(ticket, fallback, e); }
        });
    }

    public void download() {
        if (isBusy()) return;
        UpdateState current = state.getValue();
        if (current == null || current.artifact == null) { setError("请先检查更新"); return; }
        UpdateArtifact artifact = current.artifact;
        String address = getAddress();
        int ticket = start(Phase.DOWNLOADING, artifact);
        UpdateClient client = active;
        Work work = new Work(UpdateCleanup.acquire(getApplication(), directory));
        try { executor.execute(() -> {
            File part = null;
            try {
                if (ticket != generation || client.stopped()) throw new IOException("更新已取消");
                File destination = fileFor(artifact);
                boolean cached = false;
                if (destination.isFile()) {
                    post(ticket, () -> set(Phase.VALIDATING, artifact, artifact.sizeBytes, "重新校验缓存 APK"), work);
                    try {
                        ApkValidator.verify(getApplication(), destination, artifact, client);
                        cached = true;
                    } catch (IOException invalid) {
                        if (ticket != generation || client.stopped()) throw invalid;
                        if (!destination.delete()) throw new IOException("无法清理损坏的更新缓存", invalid);
                    }
                }
                if (!cached) {
                    preferences.own(artifact);
                    post(ticket, () -> set(Phase.DOWNLOADING, artifact, 0, ""), work);
                    part = client.download(artifact, directory,
                            bytes -> post(ticket, () -> set(Phase.DOWNLOADING, artifact, bytes, ""), work));
                    post(ticket, () -> set(Phase.VALIDATING, artifact, artifact.sizeBytes, "校验 APK 身份"), work);
                    ApkValidator.verify(getApplication(), part, artifact, client);
                    if (ticket != generation || client.stopped()) throw new IOException("更新已取消");
                    if (!part.renameTo(destination)) throw new IOException("无法保存更新 APK");
                    part = null;
                }
                post(ticket, () -> {
                    preferences.savePending(artifact, address);
                    verifiedForInstall = true;
                    set(Phase.READY, artifact, artifact.sizeBytes, "校验通过，安装仍需系统确认");
                }, work);
            } catch (Exception e) { failed(ticket, artifact, e, work); }
            finally {
                if (part != null) part.delete();
                work.finish();
            }
        }); } catch (RuntimeException e) { work.finish(); setError(e.getMessage()); }
    }

    public void validateForInstall() {
        if (isBusy()) return;
        UpdateState current = state.getValue();
        if (current == null || current.artifact == null) { setError("没有待安装更新"); return; }
        UpdateArtifact artifact = current.artifact;
        int ticket = start(Phase.VALIDATING, artifact);
        UpdateClient client = active;
        Work work = new Work(UpdateCleanup.acquire(getApplication(), directory));
        try { executor.execute(() -> {
            try {
                ApkValidator.verify(getApplication(), fileFor(artifact), artifact, client);
                post(ticket, () -> {
                    verifiedForInstall = true;
                    set(Phase.READY, artifact, artifact.sizeBytes, "校验通过，安装仍需系统确认");
                }, work);
            } catch (Exception e) { failed(ticket, artifact, e, work); }
            finally { work.finish(); }
        }); } catch (RuntimeException e) { work.finish(); setError(e.getMessage()); }
    }

    public File getApkFile() {
        UpdateState current = state.getValue();
        return current != null && current.artifact != null && verifiedForInstall ? fileFor(current.artifact) : null;
    }

    public void installHandedOff() {
        UpdateState current = state.getValue();
        if (current == null || current.phase != Phase.READY || !verifiedForInstall) {
            setError("APK 需要重新校验"); return;
        }
        verifiedForInstall = false;
        set(Phase.HANDOFF, current.artifact, current.artifact.sizeBytes, "已交给系统确认，尚未确认安装成功");
    }

    public void installationReturned() {
        verifiedForInstall = false;
        UpdateState current = state.getValue();
        // A recreated VM may have already consumed the pending record after package replacement.
        if (current != null && current.phase == Phase.UPDATED) return;
        restore(true);
        UpdateCleanup.request(getApplication());
    }

    private void restore(boolean returned) {
        long installed = ApkValidator.installedVersion(getApplication());
        UpdateArtifact raw = preferences.rawPending(BuildConfig.DEBUG);
        boolean completed = raw != null && UpdateCleanup.matches(getApplication(), raw) && raw.versionCode <= installed;
        try { completed |= preferences.reconcile(getApplication(), installed); }
        catch (RuntimeException ignored) { }
        completed |= preferences.consumeCompletion(installed);
        UpdateArtifact pending = preferences.pending(BuildConfig.DEBUG);
        if (pending != null && pending.versionCode > installed && fileFor(pending).isFile()) {
            set(Phase.AVAILABLE, pending, 0, returned ? "尚未确认升级完成，可重新校验后安装"
                    : "发现待安装文件；再次点击下载可重新校验，安装前仍需验证");
        } else if (completed) {
            set(Phase.UPDATED, null, 0, "已确认当前安装版本已更新");
        } else {
            // Malformed records stay protected; a missing, valid candidate can be retired.
            if (pending != null && !fileFor(pending).isFile()) {
                try { preferences.clearPending(); } catch (RuntimeException ignored) { }
            }
            set(getAddress().isEmpty() ? Phase.UNCONFIGURED : Phase.IDLE, null, 0,
                    returned ? "没有待安装记录，请手动检查更新" : "");
        }
    }

    public void cancel() {
        UpdateState current = state.getValue();
        stopWork();
        set(Phase.CANCELLED, current == null ? null : current.artifact, 0, "更新已取消，原有应用不受影响");
    }

    public void setError(String detail) {
        UpdateState current = state.getValue();
        stopWork();
        set(Phase.ERROR, current == null ? null : current.artifact, 0, detail);
    }

    private File fileFor(UpdateArtifact artifact) { return new File(directory, artifact.sha256 + ".apk"); }

    private int start(Phase phase, UpdateArtifact artifact) {
        stopWork();
        active = new UpdateClient(BuildConfig.DEBUG);
        set(phase, artifact, 0, "");
        return generation;
    }

    private void stopWork() {
        generation++;
        verifiedForInstall = false;
        if (active != null) active.cancel();
    }

    private static final class Work {
        private final UpdateCleanup.Lease lease;
        private int users = 1;
        Work(UpdateCleanup.Lease lease) { this.lease = lease; }
        synchronized void retain() { users++; }
        synchronized void finish() { if (--users == 0) lease.close(); }
    }

    private void post(int ticket, Runnable action) { post(ticket, action, null); }

    private void post(int ticket, Runnable action, Work work) {
        if (work != null) work.retain();
        boolean posted = false;
        try {
            posted = main.post(() -> {
                try {
                    if (ticket != generation) return;
                    try { action.run(); }
                    catch (RuntimeException e) { setError(e.getMessage()); }
                } finally { if (work != null) work.finish(); }
            });
        } finally { if (!posted && work != null) work.finish(); }
    }

    private void failed(int ticket, UpdateArtifact artifact, Exception error) { failed(ticket, artifact, error, null); }

    private void failed(int ticket, UpdateArtifact artifact, Exception error, Work work) {
        post(ticket, () -> {
            verifiedForInstall = false;
            set(Phase.ERROR, artifact, 0, error.getMessage() == null ? "更新失败" : error.getMessage());
        }, work);
    }

    private void set(Phase phase, UpdateArtifact artifact, long bytes, String detail) {
        state.setValue(new UpdateState(phase, artifact, bytes, detail));
    }

    @Override protected void onCleared() {
        stopWork();
        executor.shutdown();
        super.onCleared();
    }
}
