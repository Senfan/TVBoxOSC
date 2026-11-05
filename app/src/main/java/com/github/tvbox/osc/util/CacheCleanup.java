package com.github.tvbox.osc.util;

import java.io.File;
import java.io.IOException;

final class CacheCleanup {
    private static final int MAX_ENTRIES = 10000;
    private static final int MAX_DEPTH = 32;

    private CacheCleanup() { }

    static void clearExceptUpdates(File directory) {
        if (directory == null) return;
        try {
            File root = directory.getCanonicalFile();
            File expected = new File(directory.getParentFile().getCanonicalFile(), directory.getName());
            if (!root.equals(expected) || !root.isDirectory()) return;
            File[] files = root.listFiles();
            if (files == null) return;
            int[] visited = {0};
            for (File file : files) {
                if (file.getName().equals("updates")) continue;
                remove(file, visited, 0);
            }
        } catch (IOException | SecurityException ignored) { }
    }

    private static void remove(File file, int[] visited, int depth) throws IOException {
        if (++visited[0] > MAX_ENTRIES || depth > MAX_DEPTH) return;
        if (!file.getCanonicalFile().equals(file.getAbsoluteFile())) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return;
            for (File child : children) remove(child, visited, depth + 1);
        }
        file.delete();
    }

    static void removeEmptyDirectory(File directory) {
        if (directory == null) return;
        try {
            File expected = new File(directory.getParentFile().getCanonicalFile(), directory.getName());
            if (!directory.getCanonicalFile().equals(expected)) return;
            String[] children = directory.list();
            if (children != null && children.length == 0) directory.delete();
        } catch (IOException | SecurityException ignored) { }
    }
}
