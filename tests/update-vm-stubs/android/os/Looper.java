package android.os;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Posts wait for the test's main thread, rather than running on the VM executor. */
public final class Looper {
    private static final Looper MAIN = new Looper();
    private final Thread owner = Thread.currentThread();
    final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();

    public static Looper getMainLooper() { return MAIN; }
    public static boolean isMainThread() { return Thread.currentThread() == MAIN.owner; }

    public static void drain() throws InterruptedException {
        while (!MAIN.queue.isEmpty()) runOne(0);
    }

    public static void runOne(long timeoutMillis) throws InterruptedException {
        if (!isMainThread()) throw new AssertionError("Callbacks must run on the test main thread");
        Runnable callback = MAIN.queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        if (callback != null) callback.run();
    }
}
