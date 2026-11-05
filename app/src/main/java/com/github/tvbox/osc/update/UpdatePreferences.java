package com.github.tvbox.osc.update;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.JsonParser;
import com.orhanobut.hawk.Hawk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class UpdatePreferences {
    private static final String ADDRESS = "tvbox_update_manifest_url";
    private static final String OWNED = "owned.";
    private static final String HOLD = "install.";
    private static final String RECEIPT = "completedVersion";
    private static final Set<SharedPreferences> FAILED_COMMITS = new HashSet<>();
    private final SharedPreferences pending;

    UpdatePreferences(Context context) {
        pending = context.getSharedPreferences("update_pending", Context.MODE_PRIVATE);
    }

    String address() { return Hawk.get(ADDRESS, ""); }

    void setAddress(String address) {
        synchronized (UpdateCleanup.LOCK) {
            if (!Hawk.put(ADDRESS, address)) throw new IllegalStateException("无法保存更新地址");
            clearPending();
        }
    }

    void savePending(UpdateArtifact artifact, String address) {
        synchronized (UpdateCleanup.LOCK) {
            String json = artifact.toJson().toString();
            commit(pending.edit().putString("artifact", json).putString("source", address)
                    .putString(OWNED + artifact.sha256, json));
        }
    }

    void own(UpdateArtifact artifact) {
        synchronized (UpdateCleanup.LOCK) {
            commit(pending.edit().putString(OWNED + artifact.sha256, artifact.toJson().toString()));
        }
    }

    UpdateArtifact pending(boolean debug) {
        synchronized (UpdateCleanup.LOCK) {
            try {
                if (!address().equals(pending.getString("source", ""))) return null;
                return parse(pending.getString("artifact", ""), debug);
            } catch (RuntimeException e) { return null; }
        }
    }

    UpdateArtifact rawPending(boolean debug) {
        synchronized (UpdateCleanup.LOCK) {
            try { return parse(pending.getString("artifact", ""), debug); }
            catch (RuntimeException e) { return null; }
        }
    }

    void clearPending() {
        synchronized (UpdateCleanup.LOCK) {
            commit(pending.edit().remove("artifact").remove("source"));
        }
    }

    String hold(UpdateArtifact artifact) {
        synchronized (UpdateCleanup.LOCK) {
            String token = UUID.randomUUID().toString();
            String json = artifact.toJson().toString();
            commit(pending.edit().putString(HOLD + token, json).putString(OWNED + artifact.sha256, json));
            return token;
        }
    }

    void release(String token) {
        if (token == null) return;
        synchronized (UpdateCleanup.LOCK) {
            String key = HOLD + token;
            Object original = pending.getAll().get(key);
            if (!(original instanceof String)) return;
            try { commit(pending.edit().remove(key)); }
            catch (RuntimeException error) {
                // Android may remove the in-memory value even when the disk commit failed.
                try { commit(pending.edit().putString(key, (String) original)); } catch (RuntimeException ignored) { }
                throw error;
            }
        }
    }

    static final class Records {
        UpdateArtifact candidate;
        final Map<String, UpdateArtifact> owned = new HashMap<>();
        final Map<String, UpdateArtifact> holds = new HashMap<>();
        final Set<String> protectedHashes = new HashSet<>();
        boolean uncertain;
    }

    Records records() {
        synchronized (UpdateCleanup.LOCK) {
            Records result = new Records();
            result.uncertain = FAILED_COMMITS.contains(pending);
            try {
                Map<String, ?> values = pending.getAll();
                if (values.size() > 512) { result.uncertain = true; return result; }
                for (Map.Entry<String, ?> entry : values.entrySet()) {
                    String key = entry.getKey();
                    if (!key.equals("artifact") && !key.startsWith(HOLD) && !key.startsWith(OWNED)) continue;
                    if (!(entry.getValue() instanceof String)) { result.uncertain = true; continue; }
                    UpdateArtifact artifact;
                    try { artifact = parse((String) entry.getValue(), true); }
                    catch (RuntimeException e) { result.uncertain = true; continue; }
                    if (artifact == null) { result.uncertain = true; continue; }
                    if (key.equals("artifact")) result.candidate = artifact;
                    else if (key.startsWith(HOLD)) result.holds.put(key, artifact);
                    else if (key.substring(OWNED.length()).equals(artifact.sha256)) result.owned.put(artifact.sha256, artifact);
                    else result.uncertain = true;
                    if (!key.startsWith(OWNED)) result.protectedHashes.add(artifact.sha256);
                }
            } catch (RuntimeException e) { result.uncertain = true; }
            return result;
        }
    }

    // Success and ownership survive candidate removal and failed filesystem deletion.
    boolean reconcile(Context context, long installed) {
        synchronized (UpdateCleanup.LOCK) {
            Records records = records();
            SharedPreferences.Editor edit = pending.edit();
            boolean completed = false;
            if (records.candidate != null && UpdateCleanup.matches(context, records.candidate)
                    && records.candidate.versionCode <= installed) {
                edit.remove("artifact").remove("source").putString(OWNED + records.candidate.sha256,
                        records.candidate.toJson().toString());
                completed = true;
            }
            for (Map.Entry<String, UpdateArtifact> hold : records.holds.entrySet()) {
                UpdateArtifact artifact = hold.getValue();
                if (UpdateCleanup.matches(context, artifact) && artifact.versionCode <= installed) {
                    edit.remove(hold.getKey()).putString(OWNED + artifact.sha256, artifact.toJson().toString());
                    completed = true;
                }
            }
            if (completed) commit(edit.putString(RECEIPT, Long.toString(installed)));
            return completed;
        }
    }

    boolean consumeCompletion(long installed) {
        synchronized (UpdateCleanup.LOCK) {
            try {
                String receipt = pending.getString(RECEIPT, "");
                if (!receipt.equals(Long.toString(installed))) return false;
                // A failed receipt removal must not hide an actual successful installation.
                try { commit(pending.edit().remove(RECEIPT)); } catch (RuntimeException ignored) { }
                return true;
            } catch (RuntimeException e) { return false; }
        }
    }

    void forgetOwned(String hash) {
        synchronized (UpdateCleanup.LOCK) { commit(pending.edit().remove(OWNED + hash)); }
    }

    private static UpdateArtifact parse(String json, boolean debug) {
        if (json.isEmpty()) return null;
        if (json.length() > 32768) throw new IllegalArgumentException("更新记录过大");
        return UpdateArtifact.parse(JsonParser.parseString(json).getAsJsonObject(), debug);
    }

    private void commit(SharedPreferences.Editor editor) {
        try {
            if (!editor.commit()) throw new IllegalStateException("无法保存更新安装记录");
            FAILED_COMMITS.remove(pending);
        } catch (RuntimeException e) {
            FAILED_COMMITS.add(pending);
            throw new IllegalStateException("无法保存更新安装记录", e);
        }
    }
}
