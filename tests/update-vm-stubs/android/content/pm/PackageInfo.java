package android.content.pm;
public final class PackageInfo {
    public String packageName;
    public int versionCode;
    public ApplicationInfo applicationInfo;
    public long getLongVersionCode() { return versionCode; }
}
