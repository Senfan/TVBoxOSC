package android.content;

import android.content.pm.PackageManager;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

public abstract class Context {
    public static final int MODE_PRIVATE = 0;
    private static final Map<String, DiskPreferences> STORES = new HashMap<>();
    public abstract File getCacheDir();
    public abstract String getPackageName();
    public abstract PackageManager getPackageManager();
    public Context getApplicationContext() { return this; }
    public SharedPreferences getSharedPreferences(String name, int mode) {
        File file = new File(getCacheDir().getParentFile(), name + ".properties");
        synchronized (STORES) { return STORES.computeIfAbsent(file.getAbsolutePath(), key -> new DiskPreferences(file)); }
    }
    public static void forgetMemory() { synchronized (STORES) { STORES.clear(); } }
}
