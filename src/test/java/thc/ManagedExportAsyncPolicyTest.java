// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.TruffleLanguage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.Test;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreBackendTestSupport.*;

/** Model export protocol controls, not native registration or ABI evidence. */
class ManagedExportAsyncPolicyTest {
    private final Map<String, Object> dataRep = map("kind", "data", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", false);
    private final Map<String, Object> closureRep = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private final Map<String, Object> stateRep = map("kind", "void", "primReps", list(), "evaluated", true);
    private final Map<String, Object> scalar = map("kind", "tycon", "name", map("unit", "ghc-internal", "module", "GHC.Internal.Int", "occurrence", "Int32", "namespace", "type"), "arguments", list());
    private Value exported(Context context, String backend, boolean async) {
        context.initialize("thc"); context.enter();
        try {
            var owner = Language.currentState(null); var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
            var body = list("case", list("app", list("prim", "traceEvent#"), list(list("lit", "string-bytes", "706f6c696379"), list("void", map("rep", stateRep))),
                list(false, false), false, false, map("rep", stateRep)), "traced", list(list("default", null, list(), list("var", "x", map("rep", dataRep)))),
                map("rep", dataRep, "binder", map("id", "traced", "lifted", false, "rep", stateRep)));
            var module = map("instrument", true, "bindings", list(map("id", "model:Export.identity", "name", "identity", "type", "Int32 -> Int32", "lifted", true, "arity", 1, "rep", closureRep,
                "expr", list("lam", list(map("id", "x", "name", "x", "type", "Int32", "lifted", true, "coercion", false, "rep", dataRep)), body, map("rep", closureRep, "resultRep", dataRep)))),
                "constructors", list(map("id", "ghc-internal:GHC.Internal.Int.I32#", "name", "I32#", "kind", "boxed", "arity", 1, "fieldReps", list(list("Int32Rep")), "strictFields", list(false), "fieldLifted", list(false))));
            ExecutableProgram program = backend.equals("ast") ? new Program(language, module, async, false) : new BytecodeProgram(language, module, async);
            var signature = new ManagedExportSignature("model", "Export", "identity", "model:Export.identity", list(scalar), scalar, false, 64, null);
            return context.asValue(new ManagedExportValue(owner.getManagedExports$org_intelligence_thc(), owner, language, program, signature));
        } finally { context.leave(); }
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> void rethrow(Throwable error) throws E { throw (E) error; }
    private void checkPolicy(boolean imported) throws Exception {
        for (String backend : list("ast", "bytecode")) for (boolean async : list(false, true)) {
            int[] observed = {0}; GuestThreadId[] active = {null};
            var output = new ByteArrayOutputStream() {
                @Override public void write(byte[] bytes, int start, int length) {
                    if (length > 0) {
                        observed[0]++; var threads = Language.currentState(null).getThreads$org_intelligence_thc();
                        var slot = Objects.requireNonNull(threads.pollState$org_intelligence_thc(Thread.currentThread()).getCurrent$org_intelligence_thc());
                        active[0] = slot.getIdentity(); assertEquals(async, slot.getExternalAsync(), backend + " async=" + async + " imported=" + imported);
                        try {
                            CompletableFuture.runAsync(() -> {
                                if (async) {
                                    var request = threads.send(slot.getIdentity(), "external policy probe");
                                    try { assertFalse(request.getForceSelf$org_intelligence_thc(), "Another carrier cannot use self delivery"); assertEquals(AsyncRequestState.PENDING, request.getState()); }
                                    finally { assertTrue(request.cancel()); }
                                    assertEquals(AsyncRequestState.CANCELLED, request.getState());
                                } else assertThrows(UnsupportedCore.class, () -> threads.send(slot.getIdentity(), "must not wait on a nonpolling export"));
                            }).get(5, TimeUnit.SECONDS);
                        } catch (Exception error) { ManagedExportAsyncPolicyTest.<RuntimeException>rethrow(error); }
                    }
                    super.write(bytes, start, length);
                }
            };
            try (var exporter = Context.newBuilder("thc").err(output).build()) {
                var value = exported(exporter, backend, async); assertEquals(0, observed[0], "Creating an exported value must not execute it");
                if (!imported) assertEquals(19, value.execute(19).asInt());
                else try (var importer = Context.newBuilder("thc").build()) {
                    importer.initialize("thc"); importer.enter();
                    try {
                        var owner = Language.currentState(null); var threads = owner.getThreads$org_intelligence_thc();
                        threads.enterCurrent(null, false, true, null); var caller = threads.currentIdentity();
                        try {
                            var foreign = threads.enterForeign(ForeignSafety.SAFE);
                            try { assertEquals(19, value.execute(19).asInt()); } finally { threads.leaveForeign(foreign); }
                            assertSame(owner, Language.currentState(null)); assertSame(caller, threads.currentIdentity()); assertNotSame(caller, active[0]);
                            assertEquals(GuestThreadStatus.RUNNING, threads.status(caller));
                        } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
                        assertNull(threads.pollState$org_intelligence_thc(Thread.currentThread()).getCurrent$org_intelligence_thc());
                    } finally { importer.leave(); }
                }
                assertTrue(observed[0] > 0); assertEquals("[thc trace event] policy\n", output.toString(StandardCharsets.UTF_8));
                exporter.enter();
                try {
                    var threads = Language.currentState(null).getThreads$org_intelligence_thc();
                    assertNull(threads.pollState$org_intelligence_thc(Thread.currentThread()).getCurrent$org_intelligence_thc(), "Export return must restore the prior guest slot");
                    assertEquals(imported ? GuestThreadStatus.FINISHED : GuestThreadStatus.FOREIGN, threads.status(Objects.requireNonNull(active[0])));
                    var late = CompletableFuture.supplyAsync(() -> threads.send(Objects.requireNonNull(active[0]), "after return")).get(5, TimeUnit.SECONDS);
                    assertEquals(AsyncRequestState.TARGET_FINISHED, late.getState());
                } finally { exporter.leave(); }
                exporter.close(); assertThrows(RuntimeException.class, () -> value.execute(1));
            }
        }
    }
    @Test void directExportsPreserveTheirExecutableAsyncPolicy() throws Exception { checkPolicy(false); }
    @Test void safeCrossContextImportsPreserveExporterPolicyAndRestoreCaller() throws Exception { checkPolicy(true); }
}
