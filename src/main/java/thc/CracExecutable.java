// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/** Standalone pre-main checkpoint: reachable executable nodes retain no CBD readers.
 * The owning context survives restore; native startup and the main/shutdown wrapper
 * start afterward. Preparing nodes does not evaluate guest CAFs or compile machine code. */
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
    static Map<String,Object> selectedCore(String request) {
        var input = (Map<String,Object>) Json.parse(request);
        return CoreModules.selectedModules(input, (String) input.get("entry"));
    }

    static void run(Main.ProgramArguments guest, boolean interfaceHelper, String request, Runnable checkpoint) {
        var entry = new com.oracle.truffle.api.CallTarget[1];
        Main.runExecutable(guest, interfaceHelper,
            context -> entry[0] = prepareAndCheckpoint(context, request, checkpoint),
            context -> resume(context, entry[0]));
    }

    static Value checkpoint(Context context, String request, Runnable checkpoint) {
        return resume(context, prepareAndCheckpoint(context, request, checkpoint));
    }

    private static com.oracle.truffle.api.CallTarget prepareAndCheckpoint(Context context, String request, Runnable checkpoint) {
        var core = selectedCore(request);
        com.oracle.truffle.api.CallTarget entry;
        context.initialize("thc"); context.enter();
        try {
            var language = com.oracle.truffle.api.TruffleLanguage.LanguageReference.create(Language.class).get(null);
            entry = language.checkpointRoot(core);
        } finally { context.leave(); }
        CoreFileMappings.shared.prepareCheckpoint();
        // No context entry or lowering lock is held across the real engine call.
        // Failure propagates to the owning context; guest startup is never a fallback.
        checkpoint.run();
        return entry;
    }

    private static Value resume(Context context, com.oracle.truffle.api.CallTarget entry) {
        context.enter();
        try { return context.asValue(entry.call()); }
        finally { context.leave(); }
    }
}
