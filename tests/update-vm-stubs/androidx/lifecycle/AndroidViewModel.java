package androidx.lifecycle;

import android.app.Application;

public class AndroidViewModel {
    private final Application application;
    public AndroidViewModel(Application application) { this.application = application; }
    @SuppressWarnings("unchecked")
    public <T extends Application> T getApplication() { return (T) application; }
    protected void onCleared() { }
}
