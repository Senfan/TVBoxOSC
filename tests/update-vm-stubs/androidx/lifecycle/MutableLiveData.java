package androidx.lifecycle;

import android.os.Looper;

public final class MutableLiveData<T> extends LiveData<T> {
    public void setValue(T value) {
        if (!Looper.isMainThread()) throw new AssertionError("LiveData publication off main thread");
        this.value = value;
    }
}
