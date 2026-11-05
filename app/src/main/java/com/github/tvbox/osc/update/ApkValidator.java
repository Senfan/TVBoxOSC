package com.github.tvbox.osc.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;

import com.github.tvbox.osc.BuildConfig;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ApkValidator {
    private ApkValidator() {}

    public static long installedVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return version(info);
        } catch (PackageManager.NameNotFoundException e) { throw new IllegalStateException(e); }
    }

    private static long version(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    public static void verify(Context context, File file, UpdateArtifact expected,
                              UpdateIntegrity.StopSignal stop) throws IOException {
        UpdateIntegrity.verify(file, expected.sizeBytes, expected.sha256, stop);
        int flags = PackageManager.GET_META_DATA | (Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
        PackageManager pm = context.getPackageManager();
        PackageInfo archive = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        if (archive == null || archive.applicationInfo == null) throw new IOException("无法解析更新 APK");
        if (!context.getPackageName().equals(archive.packageName)
                || !expected.applicationId.equals(archive.packageName)) throw new IOException("APK 包名不匹配");
        long actualVersion = version(archive);
        if (actualVersion != expected.versionCode || actualVersion <= installedVersion(context)
                || !expected.versionName.equals(archive.versionName)) throw new IOException("APK 版本与清单不匹配或不是新版本");
        if (expected.minSdk > Build.VERSION.SDK_INT || (Build.VERSION.SDK_INT >= 24
                && (archive.applicationInfo.minSdkVersion != expected.minSdk
                || archive.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT))) {
            throw new IOException("APK 系统版本要求不匹配");
        }
        Bundle metadata = archive.applicationInfo.metaData;
        if (metadata == null || !BuildConfig.UPDATE_BRAND.equals(expected.brand)
                || !BuildConfig.UPDATE_MODE.equals(expected.mode) || !BuildConfig.UPDATE_ABI.equals(expected.abi)
                || !expected.brand.equals(metadata.getString("tvbox.update.brand"))
                || !expected.mode.equals(metadata.getString("tvbox.update.mode"))
                || !expected.abi.equals(metadata.getString("tvbox.update.abi"))) {
            throw new IOException("APK 更新渠道不匹配");
        }
        try {
            PackageInfo installed = pm.getPackageInfo(context.getPackageName(), flags);
            Set<String> trusted = certificates(installed);
            if (trusted.isEmpty() || !trusted.equals(certificates(archive))) {
                throw new IOException("APK 签名与当前应用不一致，禁止覆盖安装");
            }
        } catch (PackageManager.NameNotFoundException e) { throw new IOException("无法读取当前应用签名", e); }
        boolean nativeAbi = false;
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                if (stop.stopped()) throw new IOException("更新已取消");
                if (++count > 100000) throw new IOException("APK 条目数量异常");
                String name = entries.nextElement().getName();
                if (name.startsWith("lib/" + expected.abi + "/") && name.endsWith(".so")) nativeAbi = true;
                else if (name.startsWith("lib/") && name.endsWith(".so")) throw new IOException("APK CPU 架构不匹配");
            }
        }
        if (!nativeAbi) throw new IOException("APK 缺少当前架构的原生库");
        if (stop.stopped()) throw new IOException("更新已取消");
        // This is certificate identity preflight. Android's installer verifies the APK signature itself.
    }

    private static Set<String> certificates(PackageInfo info) {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28
                ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()) : info.signatures;
        Set<String> result = new TreeSet<>();
        if (signatures != null) for (Signature signature : signatures) result.add(signature.toCharsString());
        return result;
    }
}
