// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.ThreadLocalAction;
import com.oracle.truffle.api.interop.InteropLibrary;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreBackendTestSupport.*;

/** Scalar self-tail loops have no guest allocation or explicit polling operation. */
class LoopSafepointTest {
    private static final class StopLoop extends RuntimeException { StopLoop() { super("stop scalar loop"); } }
    private List<Object> variable(String name) { return list("var", name); }
    private List<Object> integer(long value) { return list("lit", "int", Long.toString(value)); }
    @SafeVarargs private final List<Object> primitive(String name, List<Object>... args) { return list("app", list("prim", name), Arrays.asList(args), Collections.nCopies(args.length, false)); }
    private Map<String, Object> module() {
        var remaining = variable("remaining"); var total = variable("total");
        var next = primitive("-#", remaining, integer(1)); var nextTotal = primitive("+#", total, remaining);
        var body = list("case", primitive("<=#", remaining, integer(0)), "condition", list(list("lit", list("int", "1"), list(), total),
            list("default", null, list(), list("app", variable("loop"), list(next, nextTotal), list(false, false)))));
        var entry = map("id", "loop", "name", "loop", "type", "Synthetic", "lifted", true, "arity", 2, "expr", list("lam", list(
            map("id", "remaining", "name", "remaining", "type", "Int#", "lifted", false, "coercion", false),
            map("id", "total", "name", "total", "type", "Int#", "lifted", false, "coercion", false)), body));
        return map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.LoopSafepoint", "instrument", true, "bindings", list(entry), "constructors", list());
    }
    private <T> T entered(Context context, Callable<T> action) throws Exception { context.enter(); try { return action.call(); } finally { context.leave(); } }
    private Object invoke(ExecutableProgram program, long n) { return Calls.target(program.hostEntryTarget(2), new Object[]{program.entryValue("loop"), new Object[]{n, 0L}}); }
    private void run(String backend, boolean compiled) throws Exception {
        var context = compiled ? Main.executionContext(false) : Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.Compilation", "false").build();
        try {
            context.initialize("thc");
            var language = entered(context, () -> TruffleLanguage.LanguageReference.create(Language.class).get(null));
            var state = entered(context, () -> Language.currentState());
            ExecutableProgram program = entered(context, () -> backend.equals("ast") ? new Program(language, module(), false, false) : new BytecodeProgram(language, module()));
            entered(context, () -> {
                for (int i = 0; i < 8; i++) assertEquals(36L, invoke(program, 8L));
                if (compiled) assertTrue((Boolean) InteropLibrary.getUncached().invokeMember(new EntryValue(program, "loop", 2), "compile"));
                return null;
            });
            long before = ((Number) program.diagnostics().get("selfTailReentries")).longValue(); var pool = Executors.newSingleThreadExecutor();
            try {
                var runnerThread = new AtomicReference<Thread>();
                var stopped = pool.submit(() -> entered(context, () -> { runnerThread.set(Thread.currentThread()); try { return invoke(program, Long.MAX_VALUE); } catch (Throwable failure) { return failure; } }));
                if (backend.equals("ast")) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (((Number) program.diagnostics().get("selfTailReentries")).longValue() - before < 1_000L && !stopped.isDone() && System.nanoTime() < deadline) Thread.sleep(1);
                    assertTrue(((Number) program.diagnostics().get("selfTailReentries")).longValue() - before >= 1_000L, backend + "/" + compiled + " did not enter its scalar loop");
                } else Thread.sleep(50);
                if (stopped.isDone()) fail(backend + "/" + compiled + " returned before asynchronous delivery: " + stopped.get());
                assertNotNull(runnerThread.get(), backend + "/" + compiled + " never entered the context");
                state.getEnv().submitThreadLocal(new Thread[]{runnerThread.get()}, new ThreadLocalAction(true, false) {
                    @Override protected void perform(Access access) { throw new StopLoop(); }
                });
                assertTrue(stopped.get(5, TimeUnit.SECONDS) instanceof StopLoop, backend + "/" + compiled + " did not deliver the action at a loop safepoint");
            } finally {
                context.close(true); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), backend + "/" + compiled + " did not stop after cancellation");
            }
            if (compiled) assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > 0, backend + " must have executed installed guest code");
            else assertEquals(0L, program.diagnostics().get("compiledEntries"));
        } finally { context.close(true); }
    }
    @Test void interpretedAstScalarLoopDelivers() throws Exception { run("ast", false); }
    @Test void compiledAstScalarLoopDelivers() throws Exception { run("ast", true); }
    @Test void interpretedBytecodeScalarLoopDelivers() throws Exception { run("bytecode", false); }
    @Test void compiledBytecodeScalarLoopDelivers() throws Exception { run("bytecode", true); }
}
