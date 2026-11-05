package com.orhanobut.hawk;
import java.util.HashMap;
import java.util.Map;
public final class Hawk {
    private static final Map<String, Object> VALUES = new HashMap<>();
    @SuppressWarnings("unchecked") public static synchronized <T> T get(String key, T fallback) { return (T) VALUES.getOrDefault(key, fallback); }
    public static synchronized boolean put(String key, Object value) { VALUES.put(key, value); return true; }
}
