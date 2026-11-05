package com.github.tvbox.osc.player;

/** Tracks real, consecutive playback progress without depending on Android clocks. */
public final class PlaybackSuccessTracker {
    private static final long SUCCESS_ELAPSED_MS = 5000;
    private static final long MAX_SAMPLE_INTERVAL_MS = 2000;
    private static final int MIN_PROGRESS_INTERVALS = 2;

    private boolean hasBaseline;
    private long lastElapsedMs;
    private long lastPositionMs;
    private float lastSpeed;
    private long continuousElapsedMs;
    private int progressIntervals;
    private boolean progressSeen;
    private boolean confirmed;

    public void reset() {
        discontinuity();
        progressSeen = false;
        confirmed = false;
    }

    public boolean sample(long elapsedRealtimeMs, long positionMs, boolean playing, float speed) {
        if (confirmed) {
            return true;
        }
        if (!playing || elapsedRealtimeMs < 0 || positionMs < 0) {
            discontinuity();
            return false;
        }
        float effectiveSpeed = normalizeSpeed(speed);
        if (!hasBaseline) {
            setBaseline(elapsedRealtimeMs, positionMs, effectiveSpeed);
            return false;
        }

        long elapsed = elapsedRealtimeMs - lastElapsedMs;
        long delta = positionMs - lastPositionMs;
        double expectedDelta = elapsed * (double) effectiveSpeed;
        // Account for quantized/jittery player positions, but reject seeks and tiny drift.
        double tolerance = Math.max(250.0, expectedDelta * 0.25);
        boolean normalProgress = elapsed > 0 && elapsed <= MAX_SAMPLE_INTERVAL_MS
                && Float.compare(effectiveSpeed, lastSpeed) == 0
                && delta > 0
                && delta >= Math.max(1.0, Math.max(expectedDelta * 0.5, expectedDelta - tolerance))
                && delta <= expectedDelta + tolerance;
        setBaseline(elapsedRealtimeMs, positionMs, effectiveSpeed);
        if (!normalProgress) {
            continuousElapsedMs = 0;
            progressIntervals = 0;
            return false;
        }

        progressSeen = true;
        continuousElapsedMs += elapsed;
        if (progressIntervals < MIN_PROGRESS_INTERVALS) {
            progressIntervals++;
        }
        if (continuousElapsedMs >= SUCCESS_ELAPSED_MS
                && progressIntervals >= MIN_PROGRESS_INTERVALS) {
            confirmed = true;
        }
        return confirmed;
    }

    public boolean hasProgress() {
        return progressSeen;
    }

    public boolean canConfirmCompletion() {
        return progressIntervals >= MIN_PROGRESS_INTERVALS;
    }

    /** A seek/track change invalidates completion evidence, not the startup watchdog signal. */
    public void discontinuity() {
        hasBaseline = false;
        continuousElapsedMs = 0;
        progressIntervals = 0;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    private void setBaseline(long elapsedMs, long positionMs, float speed) {
        hasBaseline = true;
        lastElapsedMs = elapsedMs;
        lastPositionMs = positionMs;
        lastSpeed = speed;
    }

    private static float normalizeSpeed(float speed) {
        return Float.isNaN(speed) || Float.isInfinite(speed) || speed <= 0 ? 1.0f : speed;
    }
}
