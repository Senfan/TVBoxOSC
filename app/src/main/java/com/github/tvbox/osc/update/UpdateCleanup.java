package com.github.tvbox.osc.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import com.github.tvbox.osc.BuildConfig;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class UpdateCleanup {
    static final Object LOCK = new Object();
    private static final Object REQUEST_LOCK = new Object();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "update-cleanup");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<String, Integer> LEASES = new HashMap<>();
    private static final List<Runnable> COMPLETIONS = new ArrayList<>();
    private static boolean running;
    private static boolean requested;
    private static Context requestedContext;
    private static final int MAX_FILES = 256;
    private static final long MAX_NANOS = 3_000_000_000L;

    interface Delete { boolean delete(File file); }
    static volatile Delete delete = File::delete;

    private UpdateCleanup() {}

    public static void request(Context context) { request(context, null); }

    public static void request(Context context, Runnable completion) {
        synchronized (REQUEST_LOCK) {
            requestedContext = context.getApplicationContext();
            requested = true;
            if (completion != null) COMPLETIONS.add(completion);
            if (running) return;
            running = true;
            WORKER.execute(UpdateCleanup::runRequests);
        }
    }

    private static void runRequests() {
        List<Runnable> completions;
        try {
            // Coalesce bursts without allowing a receiver to wait behind network I/O.
            for (int pass = 0; pass < 2; pass++) {
                Context context;
                synchronized (REQUEST_LOCK) {
                    requested = false;
                    context = requestedContext;
                }
                try { clean(context); } catch (RuntimeException ignored) { }
                synchronized (REQUEST_LOCK) { if (!requested) break; }
            }
        } finally {
            synchronized (REQUEST_LOCK) {
                completions = new ArrayList<>(COMPLETIONS);
                COMPLETIONS.clear();
            }
            for (Runnable completion : completions) {
                try { completion.run(); } catch (RuntimeException ignored) { }
            }
            synchronized (REQUEST_LOCK) {
                if (requested) WORKER.execute(UpdateCleanup::runRequests);
                else running = false;
            }
        }
    }

    public static String holdForInstall(Context context, UpdateArtifact artifact) {
        if (artifact == null || !matches(context, artifact)) throw new IllegalStateException("没有可安装的更新包");
        synchronized (LOCK) { return new UpdatePreferences(context).hold(artifact); }
    }

    public static void releaseInstall(Context context, String token) {
        if (token == null) return;
        try { new UpdatePreferences(context).release(token); } catch (RuntimeException ignored) { }
        request(context);
    }

    static boolean matches(Context context, UpdateArtifact artifact) {
        return context.getPackageName().equals(artifact.applicationId)
                && BuildConfig.UPDATE_BRAND.equals(artifact.brand)
                && BuildConfig.UPDATE_MODE.equals(artifact.mode) && BuildConfig.UPDATE_ABI.equals(artifact.abi);
    }

    static final class Lease implements AutoCloseable {
        private final String path;
        private final Context context;
        private boolean closed;
        private Lease(String path, Context context) { this.path = path; this.context = context; }
        @Override public void close() {
            boolean last;
            synchronized (LOCK) {
                if (closed) return;
                closed = true;
                int count = LEASES.get(path);
                last = count == 1;
                if (last) LEASES.remove(path); else LEASES.put(path, count - 1);
            }
            if (last && context != null) request(context);
        }
    }

    static Lease acquire(File directory) { return acquire(null, directory); }

    static Lease acquire(Context context, File directory) {
        synchronized (LOCK) {
            try {
                String path = directory.getCanonicalPath();
                LEASES.put(path, LEASES.containsKey(path) ? LEASES.get(path) + 1 : 1);
                return new Lease(path, context == null ? null : context.getApplicationContext());
            } catch (IOException e) { throw new IllegalStateException("无法保护更新文件", e); }
        }
    }

    static File directory(Context context) {
        try { return new File(context.getCacheDir().getCanonicalFile(), "updates"); }
        catch (IOException e) { throw new IllegalStateException("无法读取更新目录", e); }
    }

    private static final class Budget {
        final long deadline = System.nanoTime() + MAX_NANOS;
        int entries;
        boolean available() { return entries < MAX_FILES && System.nanoTime() < deadline; }
        boolean take() { if (!available()) return false; entries++; return true; }
    }

    static void clean(Context context) {
        Budget budget = new Budget();
        long deadline = budget.deadline;
        UpdatePreferences preferences = new UpdatePreferences(context);
        long installed = ApkValidator.installedVersion(context);
        preferences.reconcile(context, installed);
        File directory = directory(context);
        try {
            File cache = context.getCacheDir().getCanonicalFile();
            if (!directChild(cache, directory)) return;
            UpdatePreferences.Records records = preferences.records();
            int checked = 0;
            for (String hash : records.owned.keySet()) {
                if (++checked > MAX_FILES || System.nanoTime() >= deadline) break;
                synchronized (LOCK) {
                    if (!protectedFile(directory, hash, preferences.records())
                            && !new File(directory, hash + ".apk").exists()) preferences.forgetOwned(hash);
                }
            }
            if (!directory.isDirectory()) return;
            File[] files = directory.listFiles();
            if (files == null) return;
            for (File file : files) {
                if (!budget.take()) break;
                if (!directChild(directory, file) || !file.isFile()) continue;
                String name = file.getName();
                boolean part = name.matches("download-[A-Za-z0-9_-]+\\.part");
                boolean apk = name.matches("[0-9a-f]{64}\\.apk");
                if (!part && !apk) continue;
                String hash = apk ? name.substring(0, 64) : "";
                UpdatePreferences.Records before;
                synchronized (LOCK) {
                    before = preferences.records();
                    if (protectedFile(directory, hash, before)) continue;
                }
                boolean owned = apk && before.owned.containsKey(hash);
                if (apk && !owned && !legacyInstalled(context, file, installed)) continue;
                if (System.nanoTime() >= deadline) break;
                synchronized (LOCK) {
                    UpdatePreferences.Records current = preferences.records();
                    if (protectedFile(directory, hash, current) || !directChild(directory, file)) continue;
                    // A record change while APK metadata was parsed invalidates the old ownership decision.
                    if (owned && !current.owned.containsKey(hash)) continue;
                    if (!file.exists() || delete.delete(file)) {
                        if (apk && current.owned.containsKey(hash)) preferences.forgetOwned(hash);
                    }
                }
            }
            pruneEmpty(directory, directory, cache, preferences, budget, 0);
        } catch (IOException ignored) { }
    }

    private static void pruneEmpty(File root, File directory, File parent, UpdatePreferences preferences,
                                   Budget budget, int depth) throws IOException {
        if (depth > 8 || System.nanoTime() >= budget.deadline || !directChild(parent, directory)) return;
        synchronized (LOCK) {
            UpdatePreferences.Records records = preferences.records();
            if (records.uncertain || LEASES.containsKey(root.getCanonicalPath())) return;
        }
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!budget.take()) break;
            if (depth < 8 && directChild(directory, child) && child.isDirectory()) {
                pruneEmpty(root, child, directory, preferences, budget, depth + 1);
            }
        }
        if (System.nanoTime() >= budget.deadline) return;
        synchronized (LOCK) {
            UpdatePreferences.Records records = preferences.records();
            if (records.uncertain || LEASES.containsKey(root.getCanonicalPath())
                    || (directory.equals(root) && !records.protectedHashes.isEmpty())
                    || !directChild(parent, directory)) return;
            File[] remaining = directory.listFiles();
            if (remaining != null && remaining.length == 0) delete.delete(directory);
        }
    }

    private static boolean protectedFile(File directory, String hash, UpdatePreferences.Records records)
            throws IOException {
        return LEASES.containsKey(directory.getCanonicalPath()) || records.uncertain
                || records.protectedHashes.contains(hash);
    }

    private static boolean directChild(File parent, File child) throws IOException {
        File canonicalParent = parent.getCanonicalFile();
        return child.getAbsoluteFile().equals(child.getCanonicalFile())
                && canonicalParent.equals(child.getCanonicalFile().getParentFile());
    }

    private static boolean legacyInstalled(Context context, File file, long installed) {
        try {
            PackageInfo info = context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(),
                    PackageManager.GET_META_DATA);
            if (info == null || info.applicationInfo == null || !context.getPackageName().equals(info.packageName)) return false;
            Bundle metadata = info.applicationInfo.metaData;
            long version = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return version > 0 && version <= installed && metadata != null
                    && BuildConfig.UPDATE_BRAND.equals(metadata.getString("tvbox.update.brand"))
                    && BuildConfig.UPDATE_MODE.equals(metadata.getString("tvbox.update.mode"))
                    && BuildConfig.UPDATE_ABI.equals(metadata.getString("tvbox.update.abi"));
        } catch (RuntimeException e) { return false; }
    }
}
