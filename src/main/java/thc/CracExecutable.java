// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/** Checkpoint the initialized executable immediately before its guest handler.
 * Reachable targets are compiled without invoking main; installation is checked
 * after restore before the one-shot main/shutdown lifecycle starts. */
public final class CracExecutable {
    private CracExecutable() {}
    private static final ThreadLocal<java.util.function.Consumer<Main.ProgramArguments>> restoreArguments = new ThreadLocal<>();

    /** CRaC restore entry: supply fresh argv to the suspended launcher, without invoking guest code. */
    public static void main(String[] arguments) {
        var receive = restoreArguments.get();
        if (receive == null) throw new IllegalStateException("No checkpointed THC launcher awaits restore arguments");
        if (arguments.length == 0) throw new IllegalArgumentException("Expected PROGRAM_NAME [ARG...] on restore");
        receive.accept(new Main.ProgramArguments(arguments[0], java.util.Arrays.copyOfRange(arguments, 1, arguments.length)));
    }

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

    static void run(boolean interfaceHelper, String request, Runnable checkpoint) {
        if (restoreArguments.get() != null) throw new IllegalStateException("Checkpoint launcher already active");
        var restored = new Main.ProgramArguments[1];
        var prepared = new Value[1];
        restoreArguments.set(arguments -> {
            if (restored[0] != null) throw new IllegalStateException("Restore arguments already supplied");
            restored[0] = arguments;
        });
        try {
            Main.runExecutable(() -> {
                if (restored[0] == null) throw new IllegalStateException(
                    "Restore requires thc.CracExecutable PROGRAM_NAME [ARG...] after -XX:CRaCRestoreFrom");
                return restored[0];
            }, interfaceHelper,
                context -> prepared[0] = checkpoint(context, request, checkpoint),
                context -> prepared[0]);
        } finally { restoreArguments.remove(); }
    }

    static Value checkpoint(Context context, String request, Runnable checkpoint) {
        var core = selectedCore(request);
        EntryValue entry;
        context.initialize("thc"); context.enter();
        try {
            var language = com.oracle.truffle.api.TruffleLanguage.LanguageReference.create(Language.class).get(null);
            entry = language.checkpointEntry(core);
            report("before", entry.prepareCheckpointCompilation());
        } finally { context.leave(); }
        // No context entry or lowering lock is held across the real engine call.
        // Failure propagates to the owning context; guest startup is never a fallback.
        CoreFileMappings.shared.prepareCheckpoint();
        checkpoint.run();
        context.enter();
        try {
            report("after", entry.verifyCheckpointCompilation());
            return context.asValue(entry);
        }
        finally { context.leave(); }
    }

    private static void report(String phase, Map<String,Object> compilation) {
        var summary = new java.util.LinkedHashMap<>(compilation);
        if (!Boolean.getBoolean("thc.diagnostics")) summary.remove("targets");
        System.err.println("THC checkpoint compilation " + phase + ": " + Json.stringify(summary));
    }

}
