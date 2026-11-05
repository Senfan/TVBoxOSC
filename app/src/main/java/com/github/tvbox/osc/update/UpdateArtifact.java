package com.github.tvbox.osc.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Locale;

public final class UpdateArtifact {
    public static final long MAX_APK_BYTES = 200L * 1024 * 1024;
    public final String applicationId, brand, mode, abi, versionName, apkUrl, sha256, notes;
    public final int versionCode, minSdk;
    public final long sizeBytes;

    private UpdateArtifact(JsonObject json, boolean debug) {
        applicationId = text(json, "applicationId", 200);
        brand = text(json, "brand", 40);
        mode = text(json, "mode", 40);
        abi = text(json, "abi", 40);
        versionName = text(json, "versionName", 100);
        apkUrl = UpdateUrlPolicy.validate(text(json, "apkUrl", 4096), debug);
        sha256 = text(json, "sha256", 64).toLowerCase(Locale.ROOT);
        if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("APK SHA-256 格式错误");
        versionCode = (int) number(json, "versionCode", 2100000000L);
        minSdk = (int) number(json, "minSdk", 10000);
        sizeBytes = number(json, "sizeBytes", MAX_APK_BYTES);
        notes = json.has("notes") ? text(json, "notes", 8192, true) : "";
    }

    public static UpdateArtifact parse(JsonObject json, boolean debug) {
        return new UpdateArtifact(json, debug);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("applicationId", applicationId);
        json.addProperty("brand", brand);
        json.addProperty("mode", mode);
        json.addProperty("abi", abi);
        json.addProperty("versionName", versionName);
        json.addProperty("versionCode", versionCode);
        json.addProperty("minSdk", minSdk);
        json.addProperty("apkUrl", apkUrl);
        json.addProperty("sha256", sha256);
        json.addProperty("sizeBytes", sizeBytes);
        json.addProperty("notes", notes);
        return json;
    }

    static long number(JsonObject json, String key, long max) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("[1-9][0-9]{0,10}")) {
            throw new IllegalArgumentException("更新清单整数格式错误：" + key);
        }
        long result = Long.parseLong(value.getAsString());
        if (result > max) throw new IllegalArgumentException("更新清单数值超限：" + key);
        return result;
    }

    private static String text(JsonObject json, String key, int max) {
        return text(json, key, max, false);
    }

    private static String text(JsonObject json, String key, int max, boolean allowEmpty) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("更新清单缺少文本字段：" + key);
        }
        String result = value.getAsString().trim();
        if ((!allowEmpty && result.isEmpty()) || result.length() > max) {
            throw new IllegalArgumentException("更新清单文本格式错误：" + key);
        }
        return result;
    }
}
