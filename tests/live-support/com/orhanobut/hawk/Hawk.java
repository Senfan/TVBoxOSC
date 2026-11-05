package com.orhanobut.hawk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class Hawk {
    public static final Map<String, Object> values = new HashMap<>();
    public static final Set<String> writes = new HashSet<>();
    public static boolean failWrites;

    @SuppressWarnings("unchecked")
    public static <T> T get(String key, T defaultValue) {
        return values.containsKey(key) ? (T) values.get(key) : defaultValue;
    }

    public static <T> boolean put(String key, T value) {
        if (failWrites) return false;
        writes.add(key);
        values.put(key, value);
        return true;
    }

    public static void clear() {
        values.clear();
        writes.clear();
        failWrites = false;
    }
}
