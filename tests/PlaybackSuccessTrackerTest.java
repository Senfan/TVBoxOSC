import com.github.tvbox.osc.player.PlaybackSuccessTracker;

public final class PlaybackSuccessTrackerTest {
    private static int assertions;

    public static void main(String[] args) {
        baselineAndFiveSeconds();
        realTimeNotMediaTime();
        pauseResetsContinuousObservation();
        stallAndBufferingResetObservation();
        seekAndReverseDoNotConfirm();
        speedChangesResetObservation();
        abnormalSpeedsUseNormalSpeed();
        jitterIsAcceptedButTinyDriftIsNot();
        longGapsAndInvalidClocksDoNotCount();
        shortCompletionRequiresTwoConsecutiveIntervals();
        discontinuityPreservesProgressButNotCompletion();
        confirmationIsStickyUntilReset();
        System.out.println("PlaybackSuccessTracker: " + assertions + " assertions passed");
    }

    private static void baselineAndFiveSeconds() {
        PlaybackSuccessTracker tracker = new PlaybackSuccessTracker();
        expect(false, tracker.hasProgress());
        expect(false, tracker.canConfirmCompletion());
        expect(false, tracker.isConfirmed());
        // A cached/high starting position is just a baseline, not evidence of playback.
        expect(false, tracker.sample(10000, 90000, true, 1));
        expect(false, tracker.hasProgress());
        expect(false, tracker.canConfirmCompletion());
        expect(false, tracker.sample(11000, 91000, true, 1));
        expect(true, tracker.hasProgress());
        expect(false, tracker.canConfirmCompletion());
        for (int second = 2; second <= 4; second++) {
            expect(false, tracker.sample(10000 + second * 1000, 90000 + second * 1000, true, 1));
            expect(true, tracker.canConfirmCompletion());
            expect(false, tracker.isConfirmed());
        }
        expect(true, tracker.sample(15000, 95000, true, 1));
        expect(true, tracker.isConfirmed());
    }

    private static void realTimeNotMediaTime() {
        for (float speed : new float[]{0.5f, 2.0f, 3.0f}) {
            PlaybackSuccessTracker tracker = new PlaybackSuccessTracker();
            expect(false, tracker.sample(0, 0, true, speed));
            for (int second = 1; second <= 5; second++) {
                expect(second == 5, tracker.sample(second * 1000, (long) (second * 1000 * speed),
                        true, speed));
            }
        }
    }

    private static void pauseResetsContinuousObservation() {
        PlaybackSuccessTracker tracker = fourSeconds();
        expect(false, tracker.sample(5000, 5000, false, 1));
        expect(true, tracker.hasProgress());
        expect(false, tracker.canConfirmCompletion());
        expect(false, tracker.sample(10000, 10000, false, 1));
        expect(false, tracker.sample(11000, 10000, true, 1));
        expect(false, tracker.canConfirmCompletion());
        for (int second = 1; second <= 5; second++) {
            expect(second == 5, tracker.sample(11000 + second * 1000, 10000 + second * 1000,
                    true, 1));
        }
    }

    private static void stallAndBufferingResetObservation() {
        PlaybackSuccessTracker tracker = fourSeconds();
        expect(false, tracker.sample(5000, 4000, true, 1));
        expect(false, tracker.canConfirmCompletion());
        expect(true, tracker.hasProgress());
        for (int second = 1; second <= 5; second++) {
            expect(second == 5, tracker.sample(5000 + second * 1000, 4000 + second * 1000,
                    true, 1));
        }
        tracker.reset();
        expect(false, tracker.sample(0, 70000, false, 1));
        expect(false, tracker.sample(1000, 70000, true, 1));
        expect(false, tracker.sample(2000, 70000, true, 1));
        expect(false, tracker.hasProgress());
        expect(false, tracker.canConfirmCompletion());
        expect(false, tracker.sample(3000, 71000, true, 1));
        expect(true, tracker.hasProgress());
        expect(false, tracker.canConfirmCompletion());
        expect(false, tracker.sample(4000, 71000, false, 1));
        expect(false, tracker.sample(5000, 72000, true, 1));
        expect(false, tracker.canConfirmCompletion());
        expect(false, tracker.sample(6000, 73000, true, 1));
        expect(false, tracker.canConfirmCompletion());
    }

    private static void seekAndReverseDoNotConfirm() {
        for (long seekPosition : new long[]{104000, 0}) {
            PlaybackSuccessTracker tracker = fourSeconds();
            expect(false, tracker.sample(5000, seekPosition, true, 1));
            expect(false, tracker.canConfirmCompletion());
            expect(false, tracker.isConfirmed());
            for (int second = 1; second <= 5; second++) {
                expect(second == 5, tracker.sample(5000 + second * 1000,
                        seekPosition + second * 1000, true, 1));
            }
        }
        PlaybackSuccessTracker tracker = new PlaybackSuccessTracker();
        expect(false, tracker.sample(0, 0, true, 1));
        expect(false, tracker.sample(1000, 6000, true, 1));
        expect(false, tracker.hasProgress());
        expect(false, tracker.canConfirmCompletion());
        expect(false, tracker.isConfirmed());
    }

    private static void speedChangesResetObservation() {
        PlaybackSuccessTracker tracker = fourSeconds();
        expect(false, tracker.sample(5000, 6000, true, 2));
        expect(false, tracker.canConfirmCompletion());
        expect(true, tracker.hasProgress());
        for (int second = 1; second <= 5; second++) {
            expect(second == 5, tracker.sample(5000 + second * 1000, 6000 + second * 2000,
                    true, 2));
        }
        tracker = fourSeconds();
        expect(false, tracker.sample(5000, 4500, true, 0.5f));
        expect(false, tracker.canConfirmCompletion());
        for (int second = 1; second <= 5; second++) {
            expect(second == 5, tracker.sample(5000 + second * 1000, 4500 + second * 500,
                    true, 0.5f));
        }
    }

    private static void abnormalSpeedsUseNormalSpeed() {
        PlaybackSuccessTracker tracker = new PlaybackSuccessTracker();
        expect(false, tracker.sample(0, 0, true, Float.NaN));
        float[] speeds = {Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0, -1, 1};
        for (int index = 0; index < speeds.length; index++) {
            expect(index == 4, tracker.sample((index + 1) * 1000, (index + 1) * 1000,
                    true, speeds[index]));
        }
    }

    private static void jitterIsAcceptedButTinyDriftIsNot() {
        PlaybackSuccessTracker tracker = new PlaybackSuccessTracker();
        expect(false, tracker.sample(0, 0, true, 1));
        long position = 0;
        int[] deltas = {900, 1200, 1000, 800, 1100};
        for (int index = 0; index < deltas.length; index++) {
            position += deltas[index];
            expect(index == 4, tracker.sample((index + 1) * 1000, position, true, 1));
        }
        for (int interval : new int[]{100, 1000}) {
            tracker.reset();
            expect(false, tracker.sample(0, 0, true, 1));
            for (int sample = 1; sample <= 10000 / interval; sample++) {
                expect(false, tracker.sample(sample * interval, sample, true, 1));
            }
            expect(false, tracker.hasProgress());
            expect(false, tracker.canConfirmCompletion());
        }
        tracker.reset();
        expect(false, tracker.sample(0, 0, true, 2));
        expect(false, tracker.sample(1000, 2200, true, 2));
        expect(true, tracker.hasProgress());
        expect(false, tracker.sample(2000, 5000, true, 2));
        expect(false, tracker.canConfirmCompletion());
    }

    private static void longGapsAndInvalidClocksDoNotCount() {
        PlaybackSuccessTracker tracker = new PlaybackSuccessTracker();
        expect(false, tracker.sample(0, 0, true, 1));
        expect(false, tracker.sample(5000, 5000, true, 1));
        expect(false, tracker.hasProgress());
        expect(false, tracker.canConfirmCompletion());
        for (int second = 1; second <= 5; second++) {
            expect(second == 5, tracker.sample(5000 + second * 1000, 5000 + second * 1000,
                    true, 1));
        }
        tracker.reset();
        expect(false, tracker.sample(0, 0, true, 1));
        expect(false, tracker.sample(2000, 2000, true, 1));
        expect(false, tracker.sample(4000, 4000, true, 1));
        expect(true, tracker.canConfirmCompletion());
        expect(true, tracker.sample(5000, 5000, true, 1));
        tracker = fourSeconds();
        expect(false, tracker.sample(6001, 6001, true, 1));
        expect(false, tracker.canConfirmCompletion());
        expect(false, tracker.isConfirmed());
        expect(false, tracker.sample(7001, 7001, true, 1));
        expect(false, tracker.canConfirmCompletion());
        tracker = fourSeconds();
        expect(false, tracker.sample(4000, 5000, true, 1));
        expect(false, tracker.canConfirmCompletion());
        expect(false, tracker.sample(3000, 5000, true, 1));
        expect(false, tracker.sample(4000, 6000, true, 1));
        expect(false, tracker.canConfirmCompletion());
        expect(false, tracker.sample(-1, 7000, true, 1));
        expect(false, tracker.sample(5000, 7000, true, 1));
        expect(false, tracker.canConfirmCompletion());
        expect(false, tracker.sample(6000, -1, true, 1));
        expect(false, tracker.sample(7000, 8000, true, 1));
        expect(false, tracker.canConfirmCompletion());
        expect(false, tracker.sample(8000, 9000, true, 1));
        expect(false, tracker.canConfirmCompletion());
    }

    private static void shortCompletionRequiresTwoConsecutiveIntervals() {
        PlaybackSuccessTracker tracker = new PlaybackSuccessTracker();
        expect(false, tracker.sample(0, 0, true, 1));
        expect(false, tracker.canConfirmCompletion());
        expect(false, tracker.sample(400, 400, true, 1));
        expect(false, tracker.canConfirmCompletion());
        expect(false, tracker.sample(800, 800, true, 1));
        expect(true, tracker.canConfirmCompletion());
        expect(false, tracker.isConfirmed());
        expect(false, tracker.sample(1200, 100000, true, 1));
        expect(false, tracker.canConfirmCompletion());
        expect(false, tracker.sample(1600, 100400, true, 1));
        expect(false, tracker.canConfirmCompletion());
        expect(false, tracker.sample(2000, 100800, true, 1));
        expect(true, tracker.canConfirmCompletion());
        expect(false, tracker.sample(2400, 100800, true, 1));
        expect(false, tracker.canConfirmCompletion());
    }

    private static void discontinuityPreservesProgressButNotCompletion() {
        PlaybackSuccessTracker tracker = new PlaybackSuccessTracker();
        expect(false, tracker.sample(0, 0, true, 1));
        expect(false, tracker.sample(1000, 1000, true, 1));
        expect(false, tracker.sample(2000, 2000, true, 1));
        expect(true, tracker.canConfirmCompletion());
        tracker.discontinuity();
        expect(true, tracker.hasProgress());
        expect(false, tracker.canConfirmCompletion());
        expect(false, tracker.isConfirmed());
        // Seeking near the end needs a new baseline and two actual intervals.
        expect(false, tracker.sample(9000, 999000, true, 1));
        expect(false, tracker.canConfirmCompletion());
        expect(false, tracker.sample(10000, 1000000, true, 1));
        expect(false, tracker.canConfirmCompletion());
        expect(false, tracker.sample(11000, 1001000, true, 1));
        expect(true, tracker.canConfirmCompletion());
        tracker.reset();
        expect(false, tracker.hasProgress());
        expect(false, tracker.canConfirmCompletion());
        expect(false, tracker.isConfirmed());
        expect(false, tracker.sample(12000, 1002000, true, 1));
        expect(false, tracker.hasProgress());
    }

    private static void confirmationIsStickyUntilReset() {
        PlaybackSuccessTracker tracker = fourSeconds();
        expect(true, tracker.sample(5000, 5000, true, 1));
        expect(true, tracker.sample(6000, 5000, true, 1));
        expect(true, tracker.sample(7000, 5000, false, 1));
        expect(true, tracker.sample(8000, 100000, true, 2));
        tracker.discontinuity();
        expect(true, tracker.isConfirmed());
        expect(true, tracker.hasProgress());
        expect(false, tracker.canConfirmCompletion());
        expect(true, tracker.sample(9000, 101000, false, 1));
        tracker.reset();
        expect(false, tracker.isConfirmed());
        expect(false, tracker.hasProgress());
        expect(false, tracker.sample(10000, 102000, true, 1));
        expect(false, tracker.hasProgress());
    }

    private static PlaybackSuccessTracker fourSeconds() {
        PlaybackSuccessTracker tracker = new PlaybackSuccessTracker();
        expect(false, tracker.sample(0, 0, true, 1));
        for (int second = 1; second <= 4; second++) {
            expect(false, tracker.sample(second * 1000, second * 1000, true, 1));
        }
        return tracker;
    }

    private static void expect(Object expected, Object actual) {
        assertions++;
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
