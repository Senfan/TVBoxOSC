package com.github.tvbox.osc.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

public final class UpdateManifest {
    private final List<UpdateArtifact> artifacts;

    private UpdateManifest(List<UpdateArtifact> artifacts) {
        this.artifacts = artifacts;
    }

    public static UpdateManifest parse(String text, boolean debug) {
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        if (UpdateArtifact.number(root, "schemaVersion", 1) != 1
                || !root.has("artifacts") || !root.get("artifacts").isJsonArray()) {
            throw new IllegalArgumentException("不支持的更新清单格式");
        }
        JsonArray array = root.getAsJsonArray("artifacts");
        if (array.size() > 256) throw new IllegalArgumentException("更新清单条目过多");
        List<UpdateArtifact> items = new ArrayList<>();
        for (JsonElement element : array) items.add(UpdateArtifact.parse(element.getAsJsonObject(), debug));
        return new UpdateManifest(items);
    }

    public Selection select(String applicationId, String brand, String mode, String abi,
                            int sdk, long installedVersion) {
        UpdateArtifact highest = null;
        boolean compatible = false;
        boolean ambiguous = false;
        for (UpdateArtifact item : artifacts) {
            if (!item.applicationId.equals(applicationId) || !item.brand.equals(brand)
                    || !item.mode.equals(mode) || !item.abi.equals(abi) || item.minSdk > sdk) continue;
            compatible = true;
            if (item.versionCode <= installedVersion) continue;
            if (highest == null || item.versionCode > highest.versionCode) {
                highest = item;
                ambiguous = false;
            } else if (item.versionCode == highest.versionCode) {
                ambiguous = true;
            }
        }
        if (ambiguous) throw new IllegalArgumentException("清单存在重复的最高版本，请发布者修正");
        return new Selection(compatible, highest);
    }

    public static final class Selection {
        public final boolean compatible;
        public final UpdateArtifact artifact;
        Selection(boolean compatible, UpdateArtifact artifact) {
            this.compatible = compatible;
            this.artifact = artifact;
        }
    }
}
