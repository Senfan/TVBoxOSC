package com.github.tvbox.osc.event;

public final class PlayerPreferenceEvent {
    public final String sourceKey;
    public final String vodId;
    public final int playerType;

    public PlayerPreferenceEvent(String sourceKey, String vodId, int playerType) {
        this.sourceKey = sourceKey;
        this.vodId = vodId;
        this.playerType = playerType;
    }
}
