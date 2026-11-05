package com.github.tvbox.osc.player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PlaybackRetryPolicy {
    public enum ErrorType {
        PLAY_INFO, PARSE, SNIFF, PLAYER
    }

    public enum Action {
        NONE, REPLAY_URL, NEXT_URL, REFETCH
    }

    private static final int[] PLAYER_ORDER = {1, 2, 0, 3, 10, 11, 12};

    private final Map<String, Set<Integer>> attemptedPlayers = new HashMap<>();
    private int retryCount;
    private int temporaryPlayerType = -1;

    public void reset() {
        retryCount = 0;
        temporaryPlayerType = -1;
        attemptedPlayers.clear();
    }

    public int getTemporaryPlayerType() {
        return temporaryPlayerType;
    }

    /** Selects without consuming an attempt; only beginAttempt records a start. */
    public int selectPlayer(String url, int preferredPlayer, boolean forceExo,
                            List<Integer> available) {
        if (!hasUrl(url) || available == null) {
            return -1;
        }
        if (isSupported(preferredPlayer) && (!forceExo || preferredPlayer == 2)
                && available.contains(preferredPlayer) && !wasAttempted(url, preferredPlayer)) {
            return preferredPlayer;
        }
        for (int player : PLAYER_ORDER) {
            if ((!forceExo || player == 2) && available.contains(player)
                    && !wasAttempted(url, player)) {
                return player;
            }
        }
        return -1;
    }

    public boolean beginAttempt(String url, int actualPlayer) {
        if (!hasUrl(url) || !isSupported(actualPlayer)) {
            return false;
        }
        Set<Integer> players = attemptedPlayers.get(url);
        if (players == null) {
            players = new HashSet<>();
            attemptedPlayers.put(url, players);
        }
        return players.add(actualPlayer);
    }

    public Action next(ErrorType error, int actualPlayer, boolean forceExo,
                       String currentUrl, boolean hasNextUrl, List<Integer> available) {
        temporaryPlayerType = -1;
        if (error == ErrorType.PLAYER && hasUrl(currentUrl)) {
            // Also record failures reported before the caller registered the start.
            beginAttempt(currentUrl, actualPlayer);
            int nextPlayer = selectPlayer(currentUrl, -1, forceExo, available);
            if (nextPlayer != -1) {
                temporaryPlayerType = nextPlayer;
                return Action.REPLAY_URL;
            }
        }
        if (hasNextUrl) {
            return Action.NEXT_URL;
        }
        if (retryCount < 1) {
            retryCount++;
            return Action.REFETCH;
        }
        return Action.NONE;
    }

    /** Success does not replenish URL attempts or the refetch budget. */
    public void markSuccess(int playerType) {
        temporaryPlayerType = -1;
    }

    private boolean wasAttempted(String url, int player) {
        Set<Integer> players = attemptedPlayers.get(url);
        return players != null && players.contains(player);
    }

    private static boolean hasUrl(String url) {
        return url != null && !url.trim().isEmpty();
    }

    private static boolean isSupported(int player) {
        for (int supported : PLAYER_ORDER) {
            if (player == supported) {
                return true;
            }
        }
        return false;
    }

    public static boolean requiresExo(String url) {
        return url != null && (url.startsWith("data:application/dash+xml;base64,")
                || url.contains(".mpd") || url.contains("type=mpd"));
    }
}
