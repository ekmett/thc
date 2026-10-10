// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;

/** Standalone pre-main checkpoint: retained Core has no CBD readers or mappings.
 * Guest contexts, native startup and the main/shutdown wrapper begin on restore.
 * This captures decoded Core, not compiled guest code or a warmed application. */
final class CracExecutable {
    private CracExecutable() {}

    // The ordinary Jam JDK has no CRaC module. Keep this optional launcher mode
    // linkable there, but reject it before reading any application input.
    static Runnable checkpointOperation() {
        final java.lang.reflect.Method checkpoint;
        try {
            checkpoint = Class.forName("jdk.crac.Core").getMethod("checkpointRestore");
        } catch (ReflectiveOperationException missing) {
            throw new IllegalStateException("--checkpoint-executable requires a CRaC-capable Jam JDK with jdk.crac available; the ordinary Jam JDK cannot checkpoint", missing);
        }
        return () -> {
            try { checkpoint.invoke(null); }
            catch (InvocationTargetException failed) {
                throw new IllegalStateException("CRaC checkpoint failed; guest main was not started", failed.getCause());
            } catch (IllegalAccessException inaccessible) {
                throw new IllegalStateException("Cannot access jdk.crac.Core.checkpointRestore", inaccessible);
            }
        };
    }

    @SuppressWarnings("unchecked")
    static Map<String,Object> checkpoint(String request, Runnable checkpoint) {
        var input = (Map<String,Object>) Json.parse(request);
        var core = CoreModules.selectedModules(input, (String) input.get("entry"));
        CoreFileMappings.shared.prepareCheckpoint();
        // The real operation exits at checkpoint and returns only on restore.
        // A failed checkpoint propagates; there is no fallback to running main.
        checkpoint.run();
        return core;
    }
}
