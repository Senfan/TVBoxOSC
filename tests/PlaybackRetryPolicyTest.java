import com.github.tvbox.osc.player.PlaybackRetryPolicy;
import com.github.tvbox.osc.player.PlaybackRetryPolicy.Action;
import com.github.tvbox.osc.player.PlaybackRetryPolicy.ErrorType;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class PlaybackRetryPolicyTest {
    private static final String URL = "https://video.test/movie.m3u8";
    private static final String OTHER_URL = "https://video.test/backup.m3u8";
    private static final List<Integer> ALL = Arrays.asList(12, 3, 2, 11, 0, 10, 1, 2, 12, null, 99);
    private static final int[] ORDER = {1, 2, 0, 3, 10, 11, 12};
    private static int assertions;

    public static void main(String[] args) {
        selectionDoesNotConsumeAttempts();
        preferredPlayerAndDeterministicOrder();
        unavailableExternalAndUnsupportedPlayers();
        invalidUrlsAndAvailability();
        differentUrlsHaveIndependentAttempts();
        nextRecordsFailureButNotCandidate();
        allPlayersBeforeNextUrlAndRefetch();
        refetchDoesNotResetUrlAttempts();
        sourceErrorsDoNotSwitchOrConsumePlayers();
        candidatesComeBeforeRefetch();
        forceExoOnlyUsesExo();
        successPreservesAttemptsAndBudget();
        newRoundUsesOriginalPreferenceAfterTemporarySuccess();
        resetClearsAllState();
        dashDetectionIsUnchanged();
        System.out.println("PlaybackRetryPolicy: " + assertions + " assertions passed");
    }

    private static void selectionDoesNotConsumeAttempts() {
        PlaybackRetryPolicy policy = new PlaybackRetryPolicy();
        expect(1, policy.selectPlayer(URL, 1, false, ALL));
        expect(1, policy.selectPlayer(URL, 1, false, ALL));
        expect(-1, policy.getTemporaryPlayerType());
        expect(true, policy.beginAttempt(URL, 1));
        expect(false, policy.beginAttempt(URL, 1));
        expect(2, policy.selectPlayer(URL, 1, false, ALL));
        expect(2, policy.selectPlayer(URL, 1, false, ALL));
        expect(-1, policy.getTemporaryPlayerType());
        expect(true, policy.beginAttempt(URL, 2));
        expect(false, policy.beginAttempt(URL, 2));
        expect(0, policy.selectPlayer(URL, 2, false, ALL));
    }

    private static void preferredPlayerAndDeterministicOrder() {
        PlaybackRetryPolicy policy = new PlaybackRetryPolicy();
        for (int player : new int[]{10, 1, 2, 0, 3, 11, 12}) {
            expect(player, policy.selectPlayer(URL, 10, false, ALL));
            expect(true, policy.beginAttempt(URL, player));
        }
        expect(-1, policy.selectPlayer(URL, 10, false, ALL));
        expect(false, policy.beginAttempt(URL, 10));
        for (int manual : ORDER) {
            policy.reset();
            expect(manual, policy.selectPlayer(URL, manual, false, ALL));
        }
    }

    private static void unavailableExternalAndUnsupportedPlayers() {
        List<Integer> builtins = Arrays.asList(3, 0, 2, 1, 3);
        for (int missingExternal : new int[]{10, 11, 12}) {
            PlaybackRetryPolicy policy = new PlaybackRetryPolicy();
            expect(1, policy.selectPlayer(URL, missingExternal, false, builtins));
        }
        PlaybackRetryPolicy policy = new PlaybackRetryPolicy();
        expect(12, policy.selectPlayer(URL, 12, false, ALL));
        expect(1, policy.selectPlayer(URL, 99, false, ALL));
        expect(false, policy.beginAttempt(URL, 99));
        expect(false, policy.beginAttempt(URL, -1));
        expect(-1, policy.selectPlayer(URL, 99, false, Arrays.asList(99, -1, 4, null, 99)));
        expect(10, policy.selectPlayer(URL, -1, false, Arrays.asList(12, 11, 10, 10)));
    }

    private static void invalidUrlsAndAvailability() {
        for (String invalid : new String[]{null, "", " \t\n "}) {
            PlaybackRetryPolicy policy = new PlaybackRetryPolicy();
            expect(-1, policy.selectPlayer(invalid, 1, false, ALL));
            expect(false, policy.beginAttempt(invalid, 1));
            expect(Action.NEXT_URL, policy.next(ErrorType.PLAYER, 1, false, invalid, true, ALL));
            expect(-1, policy.getTemporaryPlayerType());
            expect(Action.REFETCH, policy.next(ErrorType.PLAYER, 1, false, invalid, false, ALL));
            expect(Action.NONE, policy.next(ErrorType.PLAYER, 1, false, invalid, false, ALL));
        }
        PlaybackRetryPolicy policy = new PlaybackRetryPolicy();
        expect(-1, policy.selectPlayer(URL, 1, false, null));
        expect(-1, policy.selectPlayer(URL, 1, false, Collections.<Integer>emptyList()));
        expect(Action.REFETCH, policy.next(ErrorType.PLAYER, 1, false, URL, false, null));
        expect(false, policy.beginAttempt(URL, 1));
        expect(2, policy.selectPlayer(URL, 1, false, ALL));
    }

    private static void differentUrlsHaveIndependentAttempts() {
        PlaybackRetryPolicy policy = new PlaybackRetryPolicy();
        for (int player : ORDER) {
            expect(player, policy.selectPlayer(URL, -1, false, ALL));
            expect(true, policy.beginAttempt(URL, player));
            expect(player, policy.selectPlayer(OTHER_URL, -1, false, ALL));
            expect(true, policy.beginAttempt(OTHER_URL, player));
        }
        expect(-1, policy.selectPlayer(URL, -1, false, ALL));
        expect(-1, policy.selectPlayer(OTHER_URL, -1, false, ALL));
    }

    private static void nextRecordsFailureButNotCandidate() {
        PlaybackRetryPolicy policy = new PlaybackRetryPolicy();
        expect(Action.REPLAY_URL, policy.next(ErrorType.PLAYER, 1, false, URL, true, ALL));
        expect(false, policy.beginAttempt(URL, 1));
        expect(2, policy.getTemporaryPlayerType());
        expect(2, policy.selectPlayer(URL, 1, false, ALL));
        expect(2, policy.getTemporaryPlayerType());
        expect(Action.REPLAY_URL, policy.next(ErrorType.PLAYER, 1, false, URL, true, ALL));
        expect(2, policy.getTemporaryPlayerType());
        expect(true, policy.beginAttempt(URL, 2));
        expect(Action.REPLAY_URL, policy.next(ErrorType.PLAYER, 2, false, URL, true, ALL));
        expect(0, policy.getTemporaryPlayerType());
        expect(true, policy.beginAttempt(URL, 0));
    }

    private static void allPlayersBeforeNextUrlAndRefetch() {
        PlaybackRetryPolicy policy = new PlaybackRetryPolicy();
        expect(true, policy.beginAttempt(URL, 1));
        for (int index = 1; index < ORDER.length; index++) {
            expect(Action.REPLAY_URL,
                    policy.next(ErrorType.PLAYER, ORDER[index - 1], false, URL, true, ALL));
            expect(ORDER[index], policy.getTemporaryPlayerType());
            expect(true, policy.beginAttempt(URL, ORDER[index]));
        }
        expect(Action.NEXT_URL, policy.next(ErrorType.PLAYER, 12, false, URL, true, ALL));
        expect(-1, policy.getTemporaryPlayerType());
        expect(1, policy.selectPlayer(OTHER_URL, -1, false, ALL));
        expect(Action.REFETCH, policy.next(ErrorType.PLAYER, 12, false, URL, false, ALL));
        expect(-1, policy.getTemporaryPlayerType());
        expect(Action.NONE, policy.next(ErrorType.PLAYER, 12, false, URL, false, ALL));
        expect(-1, policy.getTemporaryPlayerType());
    }

    private static void refetchDoesNotResetUrlAttempts() {
        PlaybackRetryPolicy policy = new PlaybackRetryPolicy();
        exhaust(policy, URL);
        expect(Action.REFETCH, policy.next(ErrorType.PLAYER, 12, false, URL, false, ALL));
        expect(-1, policy.selectPlayer(URL, 1, false, ALL));
        expect(false, policy.beginAttempt(URL, 1));
        expect(Action.NONE, policy.next(ErrorType.PLAYER, 1, false, URL, false, ALL));
        // New addresses are still usable, but the refetch budget is episode-wide.
        expect(1, policy.selectPlayer(OTHER_URL, 1, false, ALL));
        exhaust(policy, OTHER_URL);
        expect(Action.NONE, policy.next(ErrorType.PLAYER, 12, false, OTHER_URL, false, ALL));
    }

    private static void sourceErrorsDoNotSwitchOrConsumePlayers() {
        for (ErrorType error : new ErrorType[]{ErrorType.PLAY_INFO, ErrorType.PARSE, ErrorType.SNIFF}) {
            PlaybackRetryPolicy policy = new PlaybackRetryPolicy();
            expect(Action.NEXT_URL, policy.next(error, 1, false, URL, true, ALL));
            expect(-1, policy.getTemporaryPlayerType());
            expect(1, policy.selectPlayer(URL, 1, false, ALL));
            expect(Action.REFETCH, policy.next(error, 1, false, URL, false, ALL));
            expect(Action.NONE, policy.next(error, 1, false, URL, false, ALL));
            expect(1, policy.selectPlayer(URL, 1, false, ALL));
            expect(Action.REPLAY_URL, policy.next(ErrorType.PLAYER, 1, false, URL, false, ALL));
            expect(2, policy.getTemporaryPlayerType());
            expect(Action.NONE, policy.next(error, 2, false, URL, false, ALL));
            expect(-1, policy.getTemporaryPlayerType());
            expect(2, policy.selectPlayer(URL, 2, false, ALL));
        }
    }

    private static void candidatesComeBeforeRefetch() {
        PlaybackRetryPolicy policy = new PlaybackRetryPolicy();
        expect(Action.NEXT_URL, policy.next(ErrorType.SNIFF, 1, false, null, true, ALL));
        expect(Action.NEXT_URL, policy.next(ErrorType.SNIFF, 1, false, null, true, ALL));
        expect(Action.REFETCH, policy.next(ErrorType.SNIFF, 1, false, null, false, ALL));
        // hasNextUrl is supplied by the caller, not checked against player availability here.
        expect(Action.NEXT_URL, policy.next(ErrorType.SNIFF, 1, false, null, true, null));
        expect(Action.NONE, policy.next(ErrorType.SNIFF, 1, false, null, false, ALL));
    }

    private static void forceExoOnlyUsesExo() {
        PlaybackRetryPolicy policy = new PlaybackRetryPolicy();
        expect(2, policy.selectPlayer(URL, 1, true, ALL));
        expect(2, policy.selectPlayer(URL, 12, true, ALL));
        expect(2, policy.selectPlayer(URL, 2, true, ALL));
        expect(-1, policy.selectPlayer(URL, 1, true, Arrays.asList(1, 0, 3, 10, 11, 12)));
        expect(Action.REPLAY_URL, policy.next(ErrorType.PLAYER, 1, true, URL, true, ALL));
        expect(2, policy.getTemporaryPlayerType());
        expect(true, policy.beginAttempt(URL, 2));
        expect(-1, policy.selectPlayer(URL, 1, true, ALL));
        expect(Action.NEXT_URL, policy.next(ErrorType.PLAYER, 2, true, URL, true, ALL));
        expect(-1, policy.getTemporaryPlayerType());
        expect(Action.REFETCH, policy.next(ErrorType.PLAYER, 2, true, URL, false, ALL));
        expect(Action.NONE, policy.next(ErrorType.PLAYER, 2, true, URL, false, ALL));
        expect(0, policy.selectPlayer(URL, 1, false, ALL));
    }

    private static void successPreservesAttemptsAndBudget() {
        PlaybackRetryPolicy policy = new PlaybackRetryPolicy();
        expect(Action.REFETCH, policy.next(ErrorType.PARSE, 1, false, null, false, ALL));
        expect(Action.REPLAY_URL, policy.next(ErrorType.PLAYER, 1, false, URL, false, ALL));
        expect(true, policy.beginAttempt(URL, 2));
        policy.markSuccess(2);
        expect(-1, policy.getTemporaryPlayerType());
        expect(false, policy.beginAttempt(URL, 1));
        expect(false, policy.beginAttempt(URL, 2));
        expect(0, policy.selectPlayer(URL, 2, false, ALL));
        expect(Action.REPLAY_URL, policy.next(ErrorType.PLAYER, 2, false, URL, false, ALL));
        expect(0, policy.getTemporaryPlayerType());
        for (int player : new int[]{0, 3, 10, 11, 12}) {
            expect(true, policy.beginAttempt(URL, player));
        }
        policy.markSuccess(12);
        expect(-1, policy.selectPlayer(URL, 12, false, ALL));
        expect(Action.NONE, policy.next(ErrorType.PLAYER, 12, false, URL, false, ALL));
    }

    private static void newRoundUsesOriginalPreferenceAfterTemporarySuccess() {
        for (int preferred : ORDER) {
            PlaybackRetryPolicy policy = new PlaybackRetryPolicy();
            expect(preferred, policy.selectPlayer(URL, preferred, false, ALL));
            expect(true, policy.beginAttempt(URL, preferred));
            expect(Action.REPLAY_URL, policy.next(ErrorType.PLAYER, preferred, false, URL, false, ALL));
            int temporary = policy.getTemporaryPlayerType();
            expect(false, temporary == preferred);
            expect(true, policy.beginAttempt(URL, temporary));
            policy.markSuccess(temporary);
            expect(false, policy.beginAttempt(URL, preferred));
            expect(false, policy.beginAttempt(URL, temporary));
            // The caller still supplies its original preference for a new playback round.
            policy.reset();
            expect(preferred, policy.selectPlayer(URL, preferred, false, ALL));
        }
    }

    private static void resetClearsAllState() {
        PlaybackRetryPolicy policy = new PlaybackRetryPolicy();
        exhaust(policy, URL);
        exhaust(policy, OTHER_URL);
        expect(Action.REFETCH, policy.next(ErrorType.PLAYER, 12, false, URL, false, ALL));
        expect(Action.REPLAY_URL, policy.next(ErrorType.PLAYER, 1, false, URL + "?v=2", false, ALL));
        expect(2, policy.getTemporaryPlayerType());
        policy.reset();
        expect(-1, policy.getTemporaryPlayerType());
        expect(1, policy.selectPlayer(URL, 1, false, ALL));
        expect(1, policy.selectPlayer(OTHER_URL, 1, false, ALL));
        expect(1, policy.selectPlayer(URL + "?v=2", 1, false, ALL));
        expect(Action.REFETCH, policy.next(ErrorType.PARSE, 1, false, URL, false, ALL));
        expect(Action.NONE, policy.next(ErrorType.PARSE, 1, false, URL, false, ALL));
    }

    private static void dashDetectionIsUnchanged() {
        expect(false, PlaybackRetryPolicy.requiresExo(null));
        expect(false, PlaybackRetryPolicy.requiresExo(URL));
        for (String url : new String[]{"data:application/dash+xml;base64,abc",
                "https://video.test/movie.mpd", "https://video.test/play?type=mpd"}) {
            expect(true, PlaybackRetryPolicy.requiresExo(url));
        }
    }

    private static void exhaust(PlaybackRetryPolicy policy, String url) {
        for (int player : ORDER) {
            expect(true, policy.beginAttempt(url, player));
        }
    }

    private static void expect(Object expected, Object actual) {
        assertions++;
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }
}
