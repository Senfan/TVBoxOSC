package android.os;
import java.util.HashMap;
public final class Bundle extends HashMap<String, String> {
    public String getString(String key) { return get(key); }
    public void putString(String key, String value) { put(key, value); }
}
