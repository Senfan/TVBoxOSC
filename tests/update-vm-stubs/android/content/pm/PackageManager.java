package android.content.pm;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
public final class PackageManager {
    public static final int GET_META_DATA = 128;
    public final Map<String, PackageInfo> archives = new ConcurrentHashMap<>();
    public volatile Runnable onParse;
    public PackageInfo getPackageArchiveInfo(String path, int flags) {
        if (onParse != null) onParse.run();
        return archives.get(path);
    }
}
