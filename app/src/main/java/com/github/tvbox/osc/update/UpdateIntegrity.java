package com.github.tvbox.osc.update;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class UpdateIntegrity {
    public interface StopSignal { boolean stopped(); }
    private UpdateIntegrity() {}

    public static void verify(File file, long expectedSize, String expectedHash, StopSignal stop)
            throws IOException {
        if (!file.isFile() || file.length() != expectedSize || expectedSize < 1
                || expectedSize > UpdateArtifact.MAX_APK_BYTES) throw new IOException("APK 大小不匹配或文件缺失");
        MessageDigest digest = digest();
        long total = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (stop.stopped()) throw new IOException("更新已取消");
                total += count;
                if (total > expectedSize) throw new IOException("APK 大小不匹配");
                digest.update(buffer, 0, count);
            }
        }
        if (total != expectedSize || !hex(digest.digest()).equals(expectedHash)) {
            throw new IOException("APK SHA-256 校验失败，请重新下载");
        }
    }

    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static String hex(byte[] value) {
        StringBuilder text = new StringBuilder(value.length * 2);
        for (byte b : value) {
            text.append(Character.forDigit((b >>> 4) & 15, 16));
            text.append(Character.forDigit(b & 15, 16));
        }
        return text.toString();
    }
}
