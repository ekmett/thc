// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.io.IOAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import thc.*;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Original Conc.Signal owns lookup, ForeignPtr lifetime and forkIO, not this test. */
@Timeout(120)
@SuppressWarnings("unchecked")
public class SignalDispatchFullCoreTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final File directory = new File(root, "build/signal-dispatch");
    private Map<String, Object> document(String path) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(new File(directory, path).toPath(), StandardCharsets.UTF_8)); }
    private Map<String, Object> fixture() throws Exception {
        var manifest = document("manifest.json"); assertEquals("9.14.1", manifest.get("ghc")); assertEquals(List.of("setupHandler", "awaitHandler", CoreSignalForeign.dispatcher), manifest.get("entries"));
        OriginalStdioChecks.INSTANCE.hashes(root, manifest.get("inputHashes"), Set.of("compiler/test-fixtures/SignalDispatchAudit.hs", "compiler/test-fixtures/SignalDispatchNative.hs",
            "test/haskell-fixtures/SignalDispatchFixtures.hs", "scripts/core-capabilities.json"), null);
        OriginalStdioChecks.INSTANCE.hashes(root, manifest.get("artifactHashes"), Set.of("build/signal-dispatch/oracle.txt", "build/signal-dispatch/pre/audit.json", "build/signal-dispatch/post/audit.json"), null);
        assertEquals("1\n10010\n2\n20020\n3\n30030\n15\n150150\n", Files.readString(new File(directory, "oracle.txt").toPath(), StandardCharsets.UTF_8));
        for (String stage : List.of("pre", "post")) { assertEquals(true, document(stage + "/audit.json").get("accepted")); assertEquals(List.of(), document(stage + "/audit.json").get("missingGlobals")); }
        return manifest;
    }
    @Test public void astOriginalHandlersSelectSignalAndInheritNativeMasking() throws Exception { dispatch("ast"); }
    @Test public void bytecodeOriginalHandlersSelectSignalAndInheritNativeMasking() throws Exception { dispatch("bytecode"); }
    private long call(ExecutableProgram program, String entry, long argument) {
        var target = program.entryTarget(entry); var shape = Objects.requireNonNull(((GuestRoot) target.getRootNode()).getTupleResult());
        var result = Calls.target(target, new Object[] {0L, argument, kotlin.Unit.INSTANCE}); return shape.getLayout().getLong(TupleResultsKt.ownedTupleResult(result, shape), 0);
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable, T> T rethrow(Throwable failure) throws E { throw (E) failure; }
    private void dispatch(String backend) throws Exception {
        var manifest = fixture(); var originals = new ArrayList<Map<String, Object>>();
        var layout = Objects.requireNonNull(CorePackageManifest.visitModules(new File(root, (String) manifest.get("packageManifest")).getPath(), (module, path) -> originals.add(module)).getTargetLayout());
        var lines = Files.readAllLines(new File(directory, "oracle.txt").toPath(), StandardCharsets.UTF_8); var nativeRows = new LinkedHashMap<Long, Long>();
        for (int i = 0; i < lines.size(); i += 2) nativeRows.put(Long.parseLong(lines.get(i)), Long.parseLong(lines.get(i + 1)));
        for (var stage : ((Map<String, List<String>>) manifest.get("stages")).entrySet()) {
            var modules = new ArrayList<>(originals); for (String path : stage.getValue()) modules.add((Map<String, Object>) Json.parse(Files.readString(new File(root, path).toPath(), StandardCharsets.UTF_8)));
            var combined = new LinkedHashMap<>(CoreModules.merge(modules)); combined.put("targetLayout", layout);
            var linked = new LinkedHashMap<>(CoreModules.reachable(combined, (List<String>) manifest.get("entries"), true)); linked.put("instrument", true);
            try (Context context = Context.newBuilder("thc", "llvm").allowNativeAccess(true).allowIO(IOAccess.ALL).allowCreateThread(true).allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var owner = Language.currentState();
                    ExecutableProgram program = backend.equals("ast") ? new Program(language, linked, true) : new BytecodeProgram(language, linked, true);
                    var events = new LinkedBlockingQueue<ProcessSignalTransport.Event>(); var closed = new AtomicInteger();
                    var transport = new ProcessSignalTransport() {
                        @Override public ProcessSignalTransport.Result install(int signal, int action) { return new ProcessSignalTransport.Result(-1, 0); }
                        @Override public ProcessSignalTransport.Event take() {
                            try { var event = events.take(); return event.getInfo().length == 0 ? null : event; }
                            catch (InterruptedException failure) { return rethrow(failure); }
                        }
                        @Override public void wake() { events.offer(new ProcessSignalTransport.Event(0, new byte[0])); }
                        @Override public void resetWake() { events.removeIf(event -> event.getInfo().length == 0); }
                        @Override public void close() { closed.incrementAndGet(); }
                    };
                    var service = new ManagedSignals(owner, language, true, () -> NativeSignalTransport.userSignalAvailable(), () -> transport);
                    service.bind(program); service.authorizeLauncher(); // Test-only transport, never changes host process handlers.
                    owner.getThreads().enterCurrent(null, false, true, null);
                    try {
                        for (var row : nativeRows.entrySet()) {
                            long signal = row.getKey(), expected = row.getValue(); assertEquals(signal, call(program, "setupHandler", signal), stage.getKey() + "/" + backend + " original setHandler");
                            assertEquals(-1L, service.install(signal, -4L, ManagedAddress.nullAddress()));
                            byte[] info = ByteBuffer.allocate(128).order(ByteOrder.nativeOrder()).putInt((int) signal).array(); events.put(new ProcessSignalTransport.Event((int) signal, info));
                            // The original forked Haskell handler decodes the actual info pointer,
                            // observes its mask and publishes through the original MVar action.
                            assertEquals(expected, call(program, "awaitHandler", signal), stage.getKey() + "/" + backend + "/" + signal);
                            assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(program.entryTarget("awaitHandler").getRootNode()));
                            var handoff = language.getHandoffState().get(); assertEquals(0, handoff.getArguments().getDepth()); assertEquals(0, handoff.getResults().getDepth());
                            assertEquals(0, handoff.getArguments().retainedReferences()); assertEquals(0, handoff.getResults().retainedReferences());
                        }
                        assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                    } finally { try { service.close(); } finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); } }
                    assertEquals(1, closed.get(), "native restoration must run exactly once");
                } finally { context.leave(); }
            }
        }
    }
}
