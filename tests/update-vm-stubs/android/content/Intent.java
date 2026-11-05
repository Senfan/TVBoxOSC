package android.content;
public final class Intent {
    public static final String ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED";
    private final String action;
    public Intent(String action) { this.action = action; }
    public String getAction() { return action; }
}
