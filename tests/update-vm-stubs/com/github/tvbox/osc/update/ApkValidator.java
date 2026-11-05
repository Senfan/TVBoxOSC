package com.github.tvbox.osc.update;

import android.app.Application;
import java.io.File;
import java.io.IOException;

/** PackageManager identity is out of scope; still exercise actual file integrity validation. */
public final class ApkValidator {
    public static long installedVersion(android.content.Context context) { return ((Application) context).installedVersion; }
    public static void verify(android.content.Context context, File file, UpdateArtifact expected,
                              UpdateIntegrity.StopSignal stop) throws IOException {
        Application application = (Application) context;
        if (application.beforeValidation != null) application.beforeValidation.run();
        UpdateIntegrity.verify(file, expected.sizeBytes, expected.sha256, stop);
        if (stop.stopped()) throw new IOException("Cancelled test validation");
        application.validations++;
    }
}
