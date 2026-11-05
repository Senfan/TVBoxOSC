package androidx.lifecycle;

/** Only the value API used by UpdateViewModel, not Activity/observer lifecycle emulation. */
public class LiveData<T> {
    protected T value;
    public T getValue() { return value; }
}
