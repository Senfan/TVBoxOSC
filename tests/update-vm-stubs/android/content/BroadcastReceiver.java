package android.content;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
public abstract class BroadcastReceiver {
    public static final List<PendingResult> RESULTS = new CopyOnWriteArrayList<>();
    public abstract void onReceive(Context context, Intent intent);
    public PendingResult goAsync() { PendingResult result = new PendingResult(); RESULTS.add(result); return result; }
    public static final class PendingResult {
        public final AtomicInteger finishes = new AtomicInteger();
        public void finish() { finishes.incrementAndGet(); }
    }
}
