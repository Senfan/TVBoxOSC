package com.github.tvbox.osc.update;

public final class UpdateState {
    public enum Phase {
        UNCONFIGURED, IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, VALIDATING,
        READY, HANDOFF, UPDATED, CANCELLED, ERROR
    }
    public final Phase phase;
    public final UpdateArtifact artifact;
    public final long downloadedBytes;
    public final String detail;

    public UpdateState(Phase phase, UpdateArtifact artifact, long downloadedBytes, String detail) {
        this.phase = phase;
        this.artifact = artifact;
        this.downloadedBytes = downloadedBytes;
        this.detail = detail == null ? "" : detail;
    }

    public boolean isBusy() {
        return phase == Phase.CHECKING || phase == Phase.DOWNLOADING || phase == Phase.VALIDATING;
    }
}
