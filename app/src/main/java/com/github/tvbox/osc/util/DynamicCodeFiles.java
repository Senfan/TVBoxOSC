package com.github.tvbox.osc.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Copies dynamic code into an app-private directory supplied by the caller.
 * Open the writable output FD before marking the empty staging path read-only; writes then
 * use that already-open FD. Only complete, size/checksum-checked files are atomically published.
 * MD5 is a compatibility/integrity check, not authentication of a plugin or its publisher.
 */
public final class DynamicCodeFiles {
    private static final int BUFFER_SIZE = 16 * 1024;
    public static final long MAX_CODE_BYTES = 128L * 1024 * 1024;

    private DynamicCodeFiles() {
    }

    public static File prepareForLoading(File source, File privateRoot, String prefix,
                                         String suffix, String expectedMd5) throws IOException {
        requireRegularFile(source, "Dynamic code source");
        try (FileInputStream input = new FileInputStream(source)) {
            return writeReadOnly(input, input.getChannel().size(), privateRoot, prefix, suffix, expectedMd5);
        }
    }

    /** Caller owns input. A length of -1 supports bounded HTTP streams without Content-Length. */
    public static File writeReadOnly(InputStream input, long expectedLength, File privateRoot,
                                     String prefix, String suffix, String expectedMd5) throws IOException {
        if (input == null) throw new IOException("Dynamic code input is null");
        if (expectedLength < -1 || expectedLength == 0 || expectedLength > MAX_CODE_BYTES) {
            throw new IOException("Invalid dynamic code length (limit " + MAX_CODE_BYTES + ")");
        }
        normalizeMd5(expectedMd5);
        File root = requirePrivateRoot(privateRoot);
        String safePrefix = safePart(prefix, "code");
        String safeSuffix = safeSuffix(suffix);
        File staging = File.createTempFile(safePrefix + "-", ".stage", root);
        File published = null;
        boolean complete = false;
        boolean renamed = false;
        try {
            MessageDigest digest = md5();
            long copied = 0;
            try (FileOutputStream output = new FileOutputStream(staging, false)) {
                // The path is read-only BEFORE the first content write; the open FD stays writable.
                if (!staging.setReadOnly() || staging.canWrite()) {
                    throw new IOException("Unable to make dynamic code staging path read-only");
                }
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count == 0) {
                        int one = input.read();
                        if (one == -1) break;
                        if (one < 0 || one > 255) throw new IOException("Invalid dynamic code stream byte");
                        buffer[0] = (byte) one;
                        count = 1;
                    }
                    if (count < 0 || count > buffer.length || count > MAX_CODE_BYTES - copied
                            || (expectedLength != -1 && count > expectedLength - copied)) {
                        throw new IOException("Dynamic code stream exceeds its length or size limit");
                    }
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    copied += count;
                }
                if (copied == 0 || (expectedLength != -1 && copied != expectedLength)) {
                    throw new IOException("Dynamic code copy length mismatch: expected "
                            + expectedLength + ", copied " + copied);
                }
                output.flush();
                output.getFD().sync();
                String actualMd5 = hex(digest.digest());
                requireExpectedMd5(actualMd5, expectedMd5);
                // Re-read the actual file, not just the stream used to write it.
                if (staging.length() != copied) throw new IOException("Dynamic code file length changed");
                verifyMd5(staging, actualMd5);
                published = new File(root, safePrefix + "-" + actualMd5 + "-" + UUID.randomUUID() + safeSuffix);
            }
            if (published.exists() || !staging.renameTo(published)) {
                throw new IOException("Unable to atomically publish dynamic code");
            }
            renamed = true;
            if (!isReadOnlyRegularFile(published)) {
                throw new IOException("Published dynamic code is not a read-only regular file");
            }
            complete = true;
            return published;
        } finally {
            // Only files owned by this attempt are removed, never an old plugin or unknown file.
            if (staging.exists()) staging.delete();
            if (!complete && renamed) published.delete();
        }
    }

    public static void verifyMd5(File file, String expectedMd5) throws IOException {
        String expected = normalizeMd5(expectedMd5);
        requireRegularFile(file, "Checksum source");
        long length = file.length();
        if (length <= 0 || length > MAX_CODE_BYTES) throw new IOException("Invalid dynamic code file size");
        if (expected.isEmpty()) return;
        MessageDigest digest = md5();
        long read = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) continue;
                if (count > MAX_CODE_BYTES - read) throw new IOException("Checksum source exceeds size limit");
                digest.update(buffer, 0, count);
                read += count;
            }
        }
        if (read != length) throw new IOException("Checksum source length changed");
        requireExpectedMd5(hex(digest.digest()), expected);
    }

    /** Publish a validated sibling, retaining the old cache inode and restoring it on failure. */
    public static synchronized File replacePreservingPrevious(File validatedSibling, File target) throws IOException {
        requireRegularFile(validatedSibling, "Validated cache candidate");
        if (!isReadOnlyRegularFile(validatedSibling)) throw new IOException("Cache candidate is writable");
        File sourceParent = validatedSibling.getParentFile();
        File targetParent = target == null ? null : target.getParentFile();
        if (targetParent == null || !sameFile(sourceParent, targetParent) || sameFile(validatedSibling, target)) {
            throw new IOException("Cache candidate and target must be distinct siblings");
        }
        if (isSymbolicLink(target) || (target.exists() && !target.isFile())) {
            throw new IOException("Cache target is not a regular file");
        }
        File previous = null;
        if (target.exists()) {
            previous = new File(targetParent, target.getName() + ".previous-" + UUID.randomUUID());
            if (!target.renameTo(previous)) throw new IOException("Unable to preserve previous cache file");
        }
        boolean replaced = false;
        try {
            replaced = validatedSibling.renameTo(target);
            if (replaced) return target;
        } finally {
            if (!replaced && previous != null && !previous.renameTo(target)) {
                throw new IOException("Unable to publish cache file or restore previous cache file at " + previous);
            }
        }
        throw new IOException("Unable to publish validated cache file");
    }

    /** Only recognizes our complete filenames, then verifies the checksum encoded in the name. */
    public static File latestReadOnly(File privateRoot, String prefix, String suffix) throws IOException {
        File root = requirePrivateRoot(privateRoot);
        String start = safePart(prefix, "code") + "-";
        String end = safeSuffix(suffix);
        File[] files = root.listFiles();
        File latest = null;
        if (files == null) throw new IOException("Unable to list dynamic code directory");
        for (File file : files) {
            String name = file.getName();
            if (!name.startsWith(start) || !name.endsWith(end)) continue;
            String middle = name.substring(start.length(), name.length() - end.length());
            if (!middle.matches("[0-9a-f]{32}-[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) continue;
            if (!isReadOnlyRegularFile(file)) continue;
            try {
                verifyMd5(file, middle.substring(0, 32));
            } catch (IOException e) {
                continue;
            }
            if (latest == null || file.lastModified() > latest.lastModified()) latest = file;
        }
        return latest;
    }

    public static boolean isReadOnlyRegularFile(File file) {
        try {
            return file != null && file.isFile() && file.length() > 0 && file.length() <= MAX_CODE_BYTES
                    && !file.canWrite() && !isSymbolicLink(file);
        } catch (IOException e) {
            return false;
        }
    }

    private static File requirePrivateRoot(File root) throws IOException {
        if (root == null) throw new IOException("Dynamic code root is null");
        if (isSymbolicLink(root)) throw new IOException("Dynamic code root is a symbolic link");
        if (!root.exists() && !root.mkdirs() && !root.isDirectory()) {
            throw new IOException("Unable to create dynamic code root");
        }
        if (!root.isDirectory() || isSymbolicLink(root)) {
            throw new IOException("Dynamic code root is not an independent directory");
        }
        return root.getCanonicalFile();
    }

    private static void requireRegularFile(File file, String label) throws IOException {
        if (file == null || !file.isFile() || isSymbolicLink(file)) {
            throw new IOException(label + " is not a regular file");
        }
    }

    private static boolean isSymbolicLink(File file) throws IOException {
        File absolute = file.getAbsoluteFile();
        File parent = absolute.getParentFile();
        if (parent == null) return false;
        File inCanonicalParent = new File(parent.getCanonicalFile(), absolute.getName());
        return !inCanonicalParent.getAbsoluteFile().equals(inCanonicalParent.getCanonicalFile());
    }

    private static boolean sameFile(File first, File second) throws IOException {
        return first != null && second != null
                && first.getCanonicalFile().equals(second.getCanonicalFile());
    }

    private static MessageDigest md5() throws IOException {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("MD5 is unavailable", e);
        }
    }

    private static String normalizeMd5(String expected) throws IOException {
        String value = expected == null ? "" : expected.trim();
        if (!value.isEmpty() && !value.matches("(?i)[0-9a-f]{32}")) {
            throw new IOException("Invalid declared dynamic code MD5");
        }
        return value;
    }

    private static void requireExpectedMd5(String actual, String expected) throws IOException {
        String value = normalizeMd5(expected);
        if (!value.isEmpty() && !actual.equalsIgnoreCase(value)) {
            throw new IOException("Dynamic code checksum mismatch");
        }
    }

    private static String safePart(String value, String fallback) {
        String safe = value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() < 3) safe = fallback;
        if (safe.length() > 48) safe = safe.substring(0, 48);
        return safe;
    }

    private static String safeSuffix(String suffix) throws IOException {
        String value = suffix == null ? "" : suffix;
        if (!value.matches("\\.[A-Za-z0-9]{1,12}")) throw new IOException("Invalid dynamic code suffix");
        return value;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }
}
