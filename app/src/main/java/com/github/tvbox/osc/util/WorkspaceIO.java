package com.github.tvbox.osc.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Bounded file operations shared by SAF and the LAN server. No Android or URI-to-real-path API. */
public final class WorkspaceIO {
    public static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    public static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    public static final long MAX_TEXT_BYTES = 8L * 1024 * 1024;
    public static final int MAX_ENTRIES = 2048;
    public static final int MAX_DEPTH = 16;

    private WorkspaceIO() { }

    public static File directory(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("无法创建私有目录");
        }
        return dir.getCanonicalFile();
    }

    /** Input is decoded once by the caller. Hidden/internal names and symlinks are never served. */
    public static File resolve(File root, String path) throws IOException {
        if (path == null || path.startsWith("/") || path.contains("\\") || path.contains(":")) {
            throw new IOException("非法工作区路径");
        }
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.matches(".*%(?:2e|2f|5c|00|25).*")) throw new IOException("重复编码路径");
        String[] parts = path.split("/", -1);
        if (parts.length > MAX_DEPTH) throw new IOException("目录过深");
        for (String part : parts) {
            if (part.startsWith(".") || part.length() > 240) throw new IOException("非法文件名");
            for (int i = 0; i < part.length(); i++) {
                if (Character.isISOControl(part.charAt(i))) throw new IOException("非法文件名");
            }
        }
        File base = root.getCanonicalFile();
        File target = path.isEmpty() ? base : new File(base, path);
        File canonical = target.getCanonicalFile();
        if (!canonical.equals(base) && !canonical.getPath().startsWith(base.getPath() + File.separator)) {
            throw new IOException("路径超出工作区");
        }
        // Reject aliases even when the link happens to lead back into the root.
        if (!target.getAbsoluteFile().equals(canonical)) throw new IOException("不接受符号链接或路径别名");
        return canonical;
    }

    public static String name(String name) throws IOException {
        if (name == null || name.isEmpty() || name.contains("/")) throw new IOException("缺少文件名");
        resolve(new File(System.getProperty("java.io.tmpdir", "/")), name);
        return name;
    }

    public static String relative(File root, File file) throws IOException {
        File base = root.getCanonicalFile();
        String prefix = base.getPath() + File.separator;
        String path = file.getCanonicalPath();
        if (path.equals(base.getPath())) return "";
        if (!path.startsWith(prefix)) throw new IOException("路径超出工作区");
        String relative = path.substring(prefix.length()).replace(File.separatorChar, '/');
        resolve(base, relative);
        return relative;
    }

    /** Caller owns both streams. Never uses available(); it need not describe stream length. */
    public static long copy(InputStream input, OutputStream output, long limit) throws IOException {
        if (input == null || limit < 0) throw new IOException("无法读取文件流");
        long total = 0;
        byte[] buffer = new byte[16384];
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("导入已取消");
            int count = input.read(buffer);
            if (count == -1) return total;
            if (count == 0) {
                int one = input.read();
                if (one == -1) return total;
                buffer[0] = (byte) one;
                count = 1;
            }
            if (count > limit - total) throw new IOException("文件超过大小上限");
            output.write(buffer, 0, count);
            total += count;
        }
    }

    public static InputStream limited(InputStream input, long limit) {
        return new java.io.FilterInputStream(input) {
            private long remaining = limit;
            @Override public int read() throws IOException {
                int value = in.read();
                if (value != -1 && remaining-- <= 0) throw new IOException("输入流超过大小上限");
                return value;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (length == 0) return 0;
                int count = in.read(bytes, offset, (int) Math.min(length, remaining + 1));
                if (count > remaining) throw new IOException("输入流超过大小上限");
                if (count > 0) remaining -= count;
                return count;
            }
        };
    }

    public static byte[] read(InputStream input, long limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(input, output, limit);
        return output.toByteArray();
    }

    public static String text(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            return new String(read(input, MAX_TEXT_BYTES), StandardCharsets.UTF_8);
        }
    }

    /** Complete sibling then rename, never delete/truncate the prior file on a failed copy. */
    public static synchronized void writeAtomic(InputStream input, File target, long limit) throws IOException {
        File parent = directory(target.getParentFile());
        if (!new File(parent, target.getName()).equals(target.getCanonicalFile())
                || (target.exists() && !target.isFile())) throw new IOException("目标不是普通文件");
        File stage = File.createTempFile(".write-", ".part", parent);
        try {
            try (FileOutputStream output = new FileOutputStream(stage)) {
                copy(input, output, limit);
                output.flush();
                output.getFD().sync();
            }
            // Android/Linux rename replaces a file atomically; on unsupported filesystems fail closed.
            if (!stage.renameTo(target)) throw new IOException("无法提交文件，原文件保持不变");
        } finally {
            stage.delete();
        }
    }

    public static File newStage(File stagingRoot) throws IOException {
        File stage = new File(directory(stagingRoot), "import-" + UUID.randomUUID());
        if (!stage.mkdir()) throw new IOException("无法创建导入事务");
        return stage;
    }

    /** A bundle always gets a new namespace, so imports never overwrite prior configurations. */
    public static File publish(File stage, File destination) throws IOException {
        return publish(stage, destination, destination);
    }

    public static File publish(File stage, File destination, File containmentRoot) throws IOException {
        directory(destination);
        File published = resolve(destination, "import-" + System.currentTimeMillis() + "-" + UUID.randomUUID());
        String prefix = relative(containmentRoot, published);
        validatePublishedPaths(stage, stage, containmentRoot, prefix);
        if (!stage.renameTo(published)) throw new IOException("无法提交导入");
        return published;
    }

    private static void validatePublishedPaths(File stage, File dir, File root, String prefix) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) throw new IOException("导入目录不可读");
        for (File child : children) {
            resolve(root, prefix + "/" + relative(stage, child));
            if (!child.getAbsoluteFile().equals(child.getCanonicalFile())) throw new IOException("不发布符号链接");
            if (child.isDirectory()) validatePublishedPaths(stage, child, root, prefix);
        }
    }

    public static final class Budget {
        private int entries;
        private long bytes;
        public void entry() throws IOException {
            if (++entries > MAX_ENTRIES) throw new IOException("文件数量超过上限");
        }
        public long remaining() { return Math.min(MAX_FILE_BYTES, MAX_TOTAL_BYTES - bytes); }
        public void add(long count) throws IOException {
            if (count < 0 || count > MAX_TOTAL_BYTES - bytes) throw new IOException("导入总大小超过上限");
            bytes += count;
        }
    }

    /** stage must be new/empty and is not published until the entire archive passes validation. */
    public static void unzip(InputStream input, File stage, Budget budget) throws IOException {
        File archive = File.createTempFile(".archive-", ".zip", stage.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(archive)) { copy(input, output, MAX_FILE_BYTES); }
            // ZipInputStream alone accepts a truncated/missing central directory. Check it first.
            try (java.util.zip.ZipFile checked = new java.util.zip.ZipFile(archive)) {
                if (checked.size() == 0 || checked.size() > MAX_ENTRIES) throw new IOException("ZIP文件数无效");
            }
            try (InputStream verified = new FileInputStream(archive)) { unzipChecked(verified, stage, budget); }
        } finally { archive.delete(); }
    }

    private static void unzipChecked(InputStream input, File stage, Budget budget) throws IOException {
        Set<String> seen = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(limited(input, MAX_FILE_BYTES))) {
            ZipEntry entry;
            boolean any = false;
            while ((entry = zip.getNextEntry()) != null) {
                any = true;
                budget.entry();
                String path = entry.getName();
                if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
                File target = resolve(stage, path);
                if (path.isEmpty() || !seen.add(target.getPath()) || target.isFile()) {
                    throw new IOException("ZIP含重复或空路径");
                }
                if (entry.isDirectory()) {
                    directory(target);
                } else {
                    if (entry.getSize() > budget.remaining()) throw new IOException("ZIP文件过大");
                    directory(target.getParentFile());
                    try (FileOutputStream output = new FileOutputStream(target)) {
                        budget.add(copy(zip, output, budget.remaining()));
                        output.getFD().sync();
                    }
                }
                zip.closeEntry();
            }
            if (!any) throw new IOException("不是有效的非空ZIP文件");
        }
    }

    /** Explicit legacy migration only: copy the selected tree, never move/remove its contents. */
    public static void copyTree(File source, File stage, Budget budget) throws IOException {
        copyTree(source.getCanonicalFile(), source.getCanonicalFile(), stage, budget, 0);
    }

    private static void copyTree(File root, File source, File stage, Budget budget, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("目录过深");
        File[] children = source.listFiles();
        if (children == null) throw new IOException("目录不可读，请使用系统文件选择器");
        for (File child : children) {
            if (child.getName().equals(".tvbox_folder")) continue; // Old server marker, not user data.
            if (!child.getAbsoluteFile().equals(child.getCanonicalFile())) throw new IOException("不导入符号链接");
            budget.entry();
            File target = resolve(stage, relative(root, child));
            if (child.isDirectory()) {
                directory(target);
                copyTree(root, child, stage, budget, depth + 1);
            } else if (child.isFile()) {
                directory(target.getParentFile());
                try (InputStream input = new FileInputStream(child); FileOutputStream output = new FileOutputStream(target)) {
                    budget.add(copy(input, output, budget.remaining()));
                    output.getFD().sync();
                }
            } else throw new IOException("不导入特殊文件");
        }
    }

    public static void zip(File source, OutputStream output) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zipTree(source, source, zip, new Budget());
        }
    }

    private static void zipTree(File root, File source, ZipOutputStream zip, Budget budget) throws IOException {
        File[] children = source.listFiles();
        if (children == null) throw new IOException("备份不可读");
        for (File child : children) {
            budget.entry();
            String path = relative(root, child);
            if (!child.getAbsoluteFile().equals(child.getCanonicalFile())) throw new IOException("不导出符号链接");
            if (child.isDirectory()) zipTree(root, child, zip, budget);
            else if (child.isFile()) {
                zip.putNextEntry(new ZipEntry(path));
                try (InputStream input = new FileInputStream(child)) { budget.add(copy(input, zip, budget.remaining())); }
                zip.closeEntry();
            } else throw new IOException("不导出特殊文件");
        }
    }

    /** Only use for this attempt's staging directory or an explicitly selected workspace entry. */
    public static void deleteTree(File file) throws IOException {
        if (!file.getAbsoluteFile().equals(file.getCanonicalFile())) throw new IOException("不删除符号链接");
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("目录不可读");
            for (File child : children) deleteTree(child);
        }
        if (file.exists() && !file.delete()) throw new IOException("删除失败");
    }
}
