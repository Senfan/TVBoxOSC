package android.app;

import java.io.File;

/** JVM-only application fixture; installed version models the PackageManager result. */
public class Application extends android.content.Context {
    private final android.content.pm.PackageManager packages = new android.content.pm.PackageManager();
    public volatile Runnable beforeValidation;
    public android.content.pm.PackageManager getPackageManager() { return packages; }
    private final File cache;
    public final long installedVersion;
    public int validations;

    public Application(File directory, long installedVersion) {
        cache = new File(directory, "cache");
        if (!cache.isDirectory() && !cache.mkdirs()) throw new IllegalStateException("Cannot create test cache");
        this.installedVersion = installedVersion;
    }

    public File getCacheDir() { return cache; }
    public String getPackageName() { return "com.github.tvbox.osc.tk"; }
}
