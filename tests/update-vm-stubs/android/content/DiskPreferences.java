package android.content;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Minimal SharedPreferences boundary: durable storage and injectable Android-like commit failure. */
public final class DiskPreferences implements SharedPreferences {
    private final File file;
    private final Map<String, Object> values = new HashMap<>();
    public volatile boolean failCommits;
    public volatile boolean failReads;
    DiskPreferences(File file) {
        this.file = file;
        if (!file.isFile()) return;
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(file)) { properties.load(input); }
        catch (IOException error) { throw new IllegalStateException(error); }
        for (String key : properties.stringPropertyNames()) values.put(key, properties.getProperty(key));
    }
    public synchronized Map<String, ?> getAll() {
        if (failReads) throw new IllegalStateException("Injected preference read failure");
        return new HashMap<>(values);
    }
    public synchronized String getString(String key, String fallback) {
        if (failReads) throw new IllegalStateException("Injected preference read failure");
        return (String) values.getOrDefault(key, fallback);
    }
    public synchronized void putWrongType(String key) { values.put(key, 123); }
    public Editor edit() {
        return new Editor() {
            final Map<String, String> writes = new HashMap<>();
            final Set<String> removes = new HashSet<>();
            public Editor putString(String key, String value) { writes.put(key, value); return this; }
            public Editor remove(String key) { removes.add(key); return this; }
            public boolean commit() {
                synchronized (DiskPreferences.this) {
                    for (String key : removes) values.remove(key);
                    values.putAll(writes); // Android may publish memory even when disk commit fails.
                    if (failCommits) return false;
                    Properties properties = new Properties();
                    for (Map.Entry<String, Object> entry : values.entrySet()) properties.put(entry.getKey(), entry.getValue().toString());
                    try (FileOutputStream output = new FileOutputStream(file)) {
                        properties.store(output, "JVM test preferences");
                        output.getFD().sync();
                        return true;
                    } catch (IOException error) { throw new IllegalStateException(error); }
                }
            }
        };
    }
}
