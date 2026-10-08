// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeUtil;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import thc.CoreModules;
import thc.EntryValue;
import thc.Json;
import thc.Language;
import java.io.File;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import thc.runtime.Unit;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class ManagedWeakTest {
    private Context context() { return context(false); }
    private Context context(boolean nativeAccess) { return context(nativeAccess, "platform"); }
    private Context context(boolean nativeAccess, String hosting) {
        return Context.newBuilder("thc")
            .allowCreateThread(true)
            .option("thc.ThreadHosting", hosting)
            .allowNativeAccess(nativeAccess)
            .allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false")
            .option("engine.MultiTier", "false")
            .option("compiler.Inlining", "false")
            .option("engine.TraceCompilation", System.getProperty("thc.weakTrace", "false"))
            .option("engine.TraceTransferToInterpreter", System.getProperty("thc.weakTrace", "false"))
            .option("engine.TraceAssumptions", System.getProperty("thc.weakTrace", "false"))
            .option("engine.SingleTierCompilationThreshold", "10000")
            .option("engine.CompilationFailureAction", "Throw")
            .option("compiler.CompilationTimeout", "30")
            .build();
    }
    private ExecutableProgram weakProgram(Language language, String backend, boolean distinctValues) {
        boolean lifted = !distinctValues;
        var key = Map.of("kind", "object", "primReps", List.of(lifted ? "BoxedRep (Just Lifted)" : "BoxedRep (Just Unlifted)"), "evaluated", !lifted);
        var weak = Map.of("kind", "object", "primReps", List.of("BoxedRep (Just Unlifted)"), "evaluated", true);
        var state = Map.of("kind", "void", "primReps", List.of(), "evaluated", true);
        var flag = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
        var closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
        var made = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", List.of("BoxedRep (Just Unlifted)"),
            "evaluated", true, "components", List.of(state, weak));
        var observed = Map.of("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", List.of("IntRep", lifted ? "BoxedRep (Just Lifted)" : "BoxedRep (Just Unlifted)"),
            "evaluated", true, "components", List.of(state, flag, key));
        var keyVar = List.of("var", "key", Map.of("rep", key));
        var stateVar = List.of("var", "s", Map.of("rep", state));
        var make = List.of("app", List.of("prim", "mkWeakNoFinalizer#"), List.of(keyVar, distinctValues ? List.of("var", "value", Map.of("rep", key)) : keyVar, stateVar),
            List.of(lifted, lifted, false), false, false, Map.of("rep", made));
        var makeBody = List.of("case", make, "pair", List.of(List.of("data", "tuple2", List.of("s1", "weak"),
            List.of("var", "weak", Map.of("rep", weak)), Map.of("binders", List.of(
                Map.of("id", "s1", "lifted", false, "rep", state), Map.of("id", "weak", "lifted", false, "rep", weak))))),
            Map.of("rep", weak, "binder", Map.of("id", "pair", "lifted", false, "rep", made)));
        var dereference = List.of("app", List.of("prim", "deRefWeak#"),
            List.of(List.of("var", "weak", Map.of("rep", weak)), stateVar), List.of(false, false), false, false, Map.of("rep", observed));
        var observeBody = List.of("case", dereference, "triple", List.of(List.of("data", "tuple3", List.of("s1", "live", "value"),
            List.of("var", "live", Map.of("rep", flag)), Map.of("binders", List.of(
                Map.of("id", "s1", "lifted", false, "rep", state), Map.of("id", "live", "lifted", false, "rep", flag),
                Map.of("id", "value", "lifted", lifted, "rep", key))))),
            Map.of("rep", flag, "binder", Map.of("id", "triple", "lifted", false, "rep", observed)));
        var source = Map.<String, Object>of("schema", 1, "ghc", "9.14.1", "instrument", true,
            "constructors", List.of(Map.of("id", "tuple2", "name", "(#,#)", "arity", 2, "tag", 1, "kind", "unboxed-tuple"),
                Map.of("id", "tuple3", "name", "(#,,#)", "arity", 3, "tag", 1, "kind", "unboxed-tuple")),
            "bindings", List.of(
                Map.of("id", "make", "name", "make", "arity", distinctValues ? 3 : 2, "lifted", true, "rep", closure, "expr", List.of("lam",
                    distinctValues ? List.of(Map.of("id", "key", "lifted", lifted, "rep", key),
                        Map.of("id", "value", "lifted", lifted, "rep", key), Map.of("id", "s", "lifted", false, "rep", state))
                        : List.of(Map.of("id", "key", "lifted", lifted, "rep", key), Map.of("id", "s", "lifted", false, "rep", state)),
                    makeBody, Map.of("rep", closure, "resultRep", weak))),
                Map.of("id", "observe", "name", "observe", "arity", 2, "lifted", true, "rep", closure, "expr", List.of("lam",
                    List.of(Map.of("id", "weak", "lifted", false, "rep", weak), Map.of("id", "s", "lifted", false, "rep", state)),
                    observeBody, Map.of("rep", closure, "resultRep", flag)))));
        return load(language, source, backend);
    }
    private Object makeIdentity(ExecutableProgram program, Object key) {
        return ScalarTestCalls.callScalarTestTarget(program.entryTarget("make"), new Object[]{0L, key, Unit.INSTANCE});
    }
    private long observeIdentity(ExecutableProgram program, Object weak) {
        return (Long) ScalarTestCalls.callScalarTestTarget(program.entryTarget("observe"), new Object[]{0L, weak, Unit.INSTANCE});
    }
    private record Registration(Object weak, WeakReference<Object> referent) {}
    private Registration droppedIdentity(ExecutableProgram program, ReferenceQueue<Object> queue) {
        var key = new Object();
        return new Registration(makeIdentity(program, key), new WeakReference<>(key, queue));
    }
    private Registration droppedPrimitiveCycle(ExecutableProgram program, ReferenceQueue<Object> queue, boolean mvarKey) {
        Object key = mvarKey ? new ManagedMVar() : new ManagedMutVar(Unit.INSTANCE);
        var value = new ManagedMutVar(key);
        var weak = ScalarTestCalls.callScalarTestTarget(program.entryTarget("make"), new Object[]{0L, key, value, Unit.INSTANCE});
        return new Registration(weak, new WeakReference<>(key, queue));
    }
    private Registration conditionalValue(ManagedWeaks registry, Object key, ReferenceQueue<Object> queue) {
        var value = new ManagedMutVar(Unit.INSTANCE);
        return new Registration(registry.make(key, value, null, null), new WeakReference<>(value, queue));
    }
    private static final class Cycle { Cycle other; }
    private WeakReference<Object> gcWitness(ReferenceQueue<Object> queue) {
        var first = new Cycle(); var second = new Cycle(); first.other = second; second.other = first;
        return new WeakReference<>(first, queue);
    }
    private void collect(ReferenceQueue<Object> queue, WeakReference<Object> expected) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        do {
            var pressure = new byte[16][1024 * 1024]; System.gc(); Reference.reachabilityFence(pressure);
            if (queue.remove(100) == expected) { assertNull(expected.get()); return; }
        } while (System.nanoTime() < deadline);
        fail("Allocation pressure plus collection requests did not collect the weak referent");
    }
    @Test
    void identityOnlyGuestWeaksCollectWhileLiveKeysAndFirstCompiledCallsPreserveIdentity() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var registry = Language.currentState().getWeaks(); var program = weakProgram(language, backend, false);
                var key = new Object(); var live = makeIdentity(program, key);
                assertEquals(1L, observeIdentity(program, live)); assertSame(key, registry.dereference(live).getValue());
                for (var name : List.of("make", "observe")) {
                    var target = program.entryTarget(name); target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target);
                }
                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                var queue = new ReferenceQueue<Object>(); var dropped = droppedIdentity(program, queue);
                assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, backend + " first compiled make");
                valid(program.entryTarget("make")); collect(queue, dropped.referent());
                before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                assertEquals(0L, observeIdentity(program, dropped.weak()));
                assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, backend + " first compiled dead dereference");
                valid(program.entryTarget("observe"));
                assertEquals(0L, registry.addCallback(dropped.weak(), () -> fail("Collected weak ran a callback")));
                assertEquals(0L, registry.finalize(dropped.weak()).getFlag());
                assertEquals(1L, observeIdentity(program, live)); assertSame(key, registry.dereference(live).getValue());
                Reference.reachabilityFence(key); registry.finalize(live);
            } finally { context.leave(); }
        }
    }
    @Test
    void mutVarKeyValueBackReferencesCollectOnFirstCompiledCalls() throws Exception {
        // GHC.Internal.Weak: a value's reference back to its key does not keep the key alive.
        // Roots here are the registry and Weak# only; neither may strongly reach the key/value cycle.
        for (boolean mvarKey : new boolean[]{false, true})
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var registry = Language.currentState().getWeaks(); var program = weakProgram(language, backend, true);
                Object key = mvarKey ? new ManagedMVar() : new ManagedMutVar(Unit.INSTANCE);
                var value = new ManagedMutVar(key);
                var live = ScalarTestCalls.callScalarTestTarget(program.entryTarget("make"), new Object[]{0L, key, value, Unit.INSTANCE});
                assertEquals(1L, observeIdentity(program, live)); assertSame(value, registry.dereference(live).getValue());
                for (var name : List.of("make", "observe")) {
                    var target = program.entryTarget(name); target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target);
                }
                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                var queue = new ReferenceQueue<Object>(); var dropped = droppedPrimitiveCycle(program, queue, mvarKey);
                assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, backend + " first compiled make");
                valid(program.entryTarget("make")); collect(queue, dropped.referent());
                before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                assertEquals(0L, observeIdentity(program, dropped.weak()));
                assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, backend + " first compiled dead dereference");
                valid(program.entryTarget("observe"));
                assertEquals(0L, registry.addCallback(dropped.weak(), () -> fail("Collected weak ran a callback")));
                assertEquals(0L, registry.finalize(dropped.weak()).getFlag());
                assertEquals(1L, observeIdentity(program, live)); assertSame(value, registry.dereference(live).getValue());
                Reference.reachabilityFence(key); registry.finalize(live);
            } finally { context.leave(); }
        }
    }
    private ExecutableProgram gcProgram(Language language, String backend, GcForeignOp operation) {
        return load(language, new CompilerHeapHintTest().gcModule(
            new String[]{operation.getSymbol(), "", "safe"}), backend);
    }
    private void requestGc(ExecutableProgram program) {
        assertEquals(42L, Calls.target(program.entryTarget("gc"), new Object[]{0L, 42L, Unit.INSTANCE}));
    }
    private Registration droppedOwnedFree(ExecutableProgram program, ReferenceQueue<Object> queue,
            ManagedAddress address) {
        return droppedOwnedFree(program, queue, address, () -> {});
    }
    private Registration droppedOwnedFree(ExecutableProgram program, ReferenceQueue<Object> queue,
            ManagedAddress address, Runnable whileKeyLive) {
        return droppedOwnedFree(program, queue, address, whileKeyLive, null);
    }
    private Registration droppedOwnedFree(ExecutableProgram program, ReferenceQueue<Object> queue,
            ManagedAddress address, Runnable whileKeyLive, Runnable afterFree) {
        var key = new ManagedMutVar(Unit.INSTANCE); var value = new ManagedMutVar(key);
        var weak = ScalarTestCalls.callScalarTestTarget(program.entryTarget("make"),
            new Object[]{0L, key, value, Unit.INSTANCE});
        var state = Language.currentState();
        // Callbacks prepend: install the observer first so it follows the real free.
        if (afterFree != null) assertEquals(1L, state.getWeaks().addCallback(weak, afterFree));
        assertEquals(1L, state.getWeaks().addCFinalizer(state.cbits().finalizerLabel("free"),
            address, 0L, ManagedAddress.nullAddress(), weak, state.cbits()));
        var registration = new Registration(weak, new WeakReference<>(key, queue));
        try { whileKeyLive.run(); return registration; }
        finally { Reference.reachabilityFence(key); }
    }
    private void awaitNativeRetirement(java.lang.foreign.MemorySegment segment) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (segment.scope().isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
        assertFalse(segment.scope().isAlive(), "Collected owned free must retire without a managed GC or weak operation");
        assertThrows(IllegalStateException.class, () -> segment.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0));
    }
    @Test
    void ownedFreeAutomaticallyRetiresCollectedKeysAndCompletedBorrows() throws Exception {
        for (var backend : List.of("ast", "bytecode")) for (boolean borrowed : new boolean[]{false, true})
        try (var context = context(true)) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState();
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = weakProgram(language, backend, true);
                var address = state.getNativeAllocations().malloc(8);
                var loanReference = new AtomicReference<ManagedNativeAllocations.Owner.Borrow>();
                var queue = new ReferenceQueue<Object>();
                var dropped = droppedOwnedFree(program, queue, address,
                    () -> loanReference.set(address.nativeAllocation().borrow()));
                var loan = loanReference.get(); var segment = loan.segment();
                if (!borrowed) loan.close();
                try {
                    collect(queue, dropped.referent());
                    if (borrowed) {
                        assertTrue(segment.scope().isAlive(), "Collection cannot consume an active borrow");
                        segment.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 91);
                    }
                } finally { loan.close(); }
                // No performGC, dereference, finalization or registry inspection precedes retirement.
                awaitNativeRetirement(segment);
                assertEquals(0L, state.getWeaks().dereference(dropped.weak()).getFlag());
                assertThrows(RuntimeFault.class, () -> address.readWord8(0));
                assertThrows(RuntimeFault.class, () -> state.getNativeAllocations().free(address));
            } finally { context.leave(); }
        }
    }
    @Test
    void ownedFreeRetiresCollectedMutVarKeysAtManagedGcRequests() throws Exception {
        for (var backend : List.of("ast", "bytecode"))
        for (var operation : List.of(GcForeignOp.MINOR, GcForeignOp.MAJOR, GcForeignOp.BLOCKING))
        try (var context = context(true)) {
            context.initialize("thc"); context.enter();
            var state = Language.currentState(); var threads = state.getThreads();
            threads.enterCurrent(null, false, true, null);
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var registry = state.getWeaks(); var allocations = state.getNativeAllocations();
                var program = weakProgram(language, backend, true); var gc = gcProgram(language, backend, operation);
                var liveKey = new ManagedMutVar(Unit.INSTANCE); var liveAddress = allocations.malloc(8);
                liveAddress.writeWord8(0, 73); var live = registry.make(liveKey, new ManagedMutVar(liveKey), null, null);
                assertEquals(1L, registry.addCFinalizer(state.cbits().finalizerLabel("free"), liveAddress, 0L, ManagedAddress.nullAddress(), live, state.cbits()));
                for (var target : List.of(program.entryTarget("make"), gc.entryTarget("gc"))) {
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target);
                }
                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                var address = allocations.malloc(8); var alias = address.plus(1);
                var queue = new ReferenceQueue<Object>(); var dropped = droppedOwnedFree(program, queue, address);
                assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before,
                    backend + " first compiled make"); valid(program.entryTarget("make"));
                assertDoesNotThrow(() -> collect(queue, dropped.referent()), backend + "/" + operation);
                // Ordinary weak operations may observe collection before the GC drain.
                assertEquals(0L, observeIdentity(program, dropped.weak()));
                before = ((Number) gc.diagnostics().get("compiledEntries")).longValue(); requestGc(gc);
                assertTrue(((Number) gc.diagnostics().get("compiledEntries")).longValue() > before,
                    backend + " first compiled GC"); valid(gc.entryTarget("gc"));
                assertThrows(RuntimeFault.class, () -> alias.readWord8(0));
                assertEquals(73L, liveAddress.readWord8(0), "A live key retains its allocation");
                assertEquals(0L, registry.finalize(dropped.weak()).getFlag()); requestGc(gc);
                assertThrows(RuntimeFault.class, () -> allocations.free(address), "Ordinary free still rejects retired aliases");
                assertEquals(0L, registry.finalize(live).getFlag());
                assertThrows(RuntimeFault.class, () -> liveAddress.readWord8(0));
                assertEquals(0L, registry.finalize(live).getFlag()); Reference.reachabilityFence(liveKey);
            } finally { threads.leaveCurrent(); context.leave(); }
        }
    }
    private Registration droppedOwnedIdentityFree(ExecutableProgram program, ReferenceQueue<Object> queue,
            ManagedAddress address) {
        var key = new Object(); var weak = makeIdentity(program, key);
        var state = Language.currentState();
        assertEquals(1L, state.getWeaks().addCFinalizer(state.cbits().finalizerLabel("free"),
            address, 0L, ManagedAddress.nullAddress(), weak, state.cbits()));
        var registration = new Registration(weak, new WeakReference<>(key, queue));
        Reference.reachabilityFence(key); return registration;
    }
    @Test
    void ownedFreeRetiresCollectedIdentityKeysAtManagedGcRequests() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = context(true)) {
            context.initialize("thc"); context.enter();
            var state = Language.currentState(); var threads = state.getThreads();
            threads.enterCurrent(null, false, true, null);
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = weakProgram(language, backend, false); var gc = gcProgram(language, backend, GcForeignOp.MINOR);
                var address = state.getNativeAllocations().malloc(8); var alias = address.plus(1);
                var queue = new ReferenceQueue<Object>(); var dropped = droppedOwnedIdentityFree(program, queue, address);
                assertDoesNotThrow(() -> collect(queue, dropped.referent()), backend + " identity key");
                assertEquals(0L, observeIdentity(program, dropped.weak())); requestGc(gc);
                assertThrows(RuntimeFault.class, () -> alias.readWord8(0));
                assertEquals(0L, state.getWeaks().finalize(dropped.weak()).getFlag()); requestGc(gc);
                assertThrows(RuntimeFault.class, () -> state.getNativeAllocations().free(address));
            } finally { threads.leaveCurrent(); context.leave(); }
        }
    }
    @Test
    void ownedFreeWaitsForRetainedMVarRequestsBeforeRetiringCollectedKeys() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = context(true)) {
            context.initialize("thc"); context.enter();
            var state = Language.currentState(); var threads = state.getThreads();
            threads.enterCurrent(null, false, true, null);
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var registry = state.getWeaks(); var allocations = state.getNativeAllocations();
                var program = weakProgram(language, backend, true); var gc = gcProgram(language, backend, GcForeignOp.MINOR);
                var address = allocations.malloc(8); address.writeWord8(0, 73); var alias = address.plus(1);
                var key = new ManagedMVar(); var request = key.beginRead(); var value = new ManagedMutVar(key);
                var weak = ScalarTestCalls.callScalarTestTarget(program.entryTarget("make"),
                    new Object[]{0L, key, value, Unit.INSTANCE});
                assertEquals(1L, registry.addCFinalizer(state.cbits().finalizerLabel("free"), address, 0L, ManagedAddress.nullAddress(), weak, state.cbits()));
                var queue = new ReferenceQueue<Object>(); var keyReference = new WeakReference<Object>(key, queue);
                var valueReference = new WeakReference<>(value); key = null; value = null;
                collect(queue, gcWitness(queue)); requestGc(gc);
                assertNotNull(keyReference.get(), "A pending request owns its MVar and native lifetime");
                assertSame(valueReference.get(), registry.dereference(weak).getValue());
                assertEquals(73L, address.readWord8(0)); assertTrue(request.cancel());
                collect(queue, gcWitness(queue)); requestGc(gc);
                assertNotNull(keyReference.get(), "A retained cancelled request still owns its MVar");
                assertSame(valueReference.get(), registry.dereference(weak).getValue());
                assertEquals(73L, address.readWord8(0)); Reference.reachabilityFence(request); request = null;
                assertDoesNotThrow(() -> collect(queue, keyReference), backend + " MVar key");
                assertNull(valueReference.get()); assertEquals(0L, registry.dereference(weak).getFlag()); requestGc(gc);
                assertThrows(RuntimeFault.class, () -> alias.readWord8(0));
                assertEquals(0L, registry.finalize(weak).getFlag()); requestGc(gc);
                assertThrows(RuntimeFault.class, () -> allocations.free(address));
            } finally { threads.leaveCurrent(); context.leave(); }
        }
    }
    @Test
    void ownedFreeDefersBorrowedAllocationsUntilBorrowCompletion() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = context(true)) {
            context.initialize("thc"); context.enter();
            var state = Language.currentState(); var threads = state.getThreads();
            threads.enterCurrent(null, false, true, null);
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = weakProgram(language, backend, true); var gc = gcProgram(language, backend, GcForeignOp.MINOR);
                var address = state.getNativeAllocations().malloc(8);
                var queue = new ReferenceQueue<Object>();
                var loan = new java.util.concurrent.atomic.AtomicReference<ManagedNativeAllocations.Owner.Borrow>();
                var dropped = droppedOwnedFree(program, queue, address, () -> loan.set(address.nativeAllocation().borrow()));
                var segment = loan.get().segment();
                try (var borrow = loan.get()) {
                    collect(queue, dropped.referent());
                    assertEquals(0L, state.getWeaks().finalize(dropped.weak()).getFlag());
                    requestGc(gc); requestGc(gc);
                    borrow.segment().set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 91);
                    assertEquals(91L, address.readWord8(0), "Busy retirement must preserve the usable borrow");
                }
                awaitNativeRetirement(segment); requestGc(gc);
                assertThrows(RuntimeFault.class, () -> address.readWord8(0));
                assertEquals(0L, state.getWeaks().finalize(dropped.weak()).getFlag());
                // An explicitly retired owner consumes its stale automatic token without replay.
                var staleAddress = state.getNativeAllocations().malloc(8);
                dropped = droppedOwnedFree(program, queue, staleAddress,
                    () -> state.getNativeAllocations().free(staleAddress));
                collect(queue, dropped.referent());
                requestGc(gc); requestGc(gc);
                assertThrows(RuntimeFault.class, () -> state.getNativeAllocations().free(staleAddress));
            } finally { threads.leaveCurrent(); context.leave(); }
        }
    }
    @Test
    void explicitOwnedFreeFinalizationPreservesAdmissionAndCallbackOrder() throws Exception {
        try (var context = context(true)) {
            context.initialize("thc"); context.enter();
            var state = Language.currentState(); var threads = state.getThreads();
            threads.enterCurrent(null, false, true, null);
            try {
                var registry = state.getWeaks(); var allocations = state.getNativeAllocations();
                var address = allocations.malloc(8); address.writeWord8(0, 37);
                var key = new ManagedMVar(); var value = new ManagedMutVar(key);
                var weak = registry.make(key, value, null, null);
                assertEquals(1L, registry.addCFinalizer(state.cbits().finalizerLabel("free"), address, 0L, ManagedAddress.nullAddress(), weak, state.cbits()));
                var calls = new ArrayList<Integer>();
                assertEquals(1L, registry.addCallback(weak, () -> {
                    assertEquals(0L, registry.dereference(weak).getFlag());
                    assertEquals(37L, address.readWord8(0)); calls.add(1);
                }));
                assertEquals(1L, registry.addCallback(weak, () -> calls.add(2)));
                assertSame(value, registry.dereference(weak).getValue());
                assertEquals(0L, registry.finalize(weak).getFlag()); assertEquals(List.of(2, 1), calls);
                assertThrows(RuntimeFault.class, () -> address.readWord8(0));
                // Explicit finalization also uses the checked allocation boundary for a separately constructed label.
                var separateAddress = allocations.malloc(8); var separateKey = new ManagedMutVar(Unit.INSTANCE);
                var explicit = registry.make(separateKey, new ManagedMutVar(separateKey), null, null);
                var separate = new CFinalizerFunction(state.cbits(), "free", null).getAddress();
                assertEquals(1L, registry.addCFinalizer(separate, separateAddress, 0L, ManagedAddress.nullAddress(), explicit, state.cbits()));
                assertDoesNotThrow(() -> separateAddress.readWord8(0));
                registry.finalize(explicit); assertThrows(RuntimeFault.class, () -> separateAddress.readWord8(0));
                // These checks own live keys; they do not require callbacks to keep dead keys alive.
                Reference.reachabilityFence(key); Reference.reachabilityFence(separateKey);
            } finally { threads.leaveCurrent(); context.leave(); }
        }
    }
    private CompletableFuture<Void> ownedFreeTask(Language.State state, Runnable action) {
        var result = new CompletableFuture<Void>(); var threads = state.getThreads();
        state.admitGuestConcurrency();
        var thread = threads.newThread(state.getEnv(), () -> {
            threads.enterCurrent(MaskingState.UNMASKED, true, true, null);
            try { action.run(); result.complete(null); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
            finally { threads.leaveCurrent(); }
        }, null, null);
        thread.setUncaughtExceptionHandler((_, failure) -> result.completeExceptionally(failure));
        threads.startThread(thread); return result;
    }
    private void awaitOwnedFreeTask(Future<?> future) {
        TruffleSafepoint.setBlockedThreadInterruptible(null, pending -> {
            try { pending.get(5, TimeUnit.SECONDS); }
            catch (ExecutionException | TimeoutException failure) { throw new AssertionError(failure); }
        }, future);
    }
    @Test
    void ownedFreeBorrowDeferralLetsOneLoomHecRunTheBorrowerAgain() throws Exception {
        for (var backend : List.of("ast", "bytecode")) try (var context = context(true, "loom")) {
            context.initialize("thc"); context.enter();
            var release = new ManagedMVar();
            try {
                var state = Language.currentState(); state.getThreads().setCapabilityCount(1);
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = weakProgram(language, backend, true); var gc = gcProgram(language, backend, GcForeignOp.MINOR);
                var address = state.getNativeAllocations().malloc(8);
                var queue = new ReferenceQueue<Object>(); var borrowed = new CompletableFuture<Void>();
                var retirementRequested = new CompletableFuture<Void>();
                var borrowerReference = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Void>>();
                var dropped = droppedOwnedFree(program, queue, address, () -> {
                    var borrower = ownedFreeTask(state, () -> {
                        assertTrue(Thread.currentThread().isVirtual());
                        try (var loan = address.nativeAllocation().borrow()) {
                            borrowed.complete(null); release.take(null);
                            loan.segment().set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 91);
                            assertEquals(91L, address.readWord8(0));
                        }
                    });
                    borrowerReference.set(borrower);
                    awaitOwnedFreeTask(CompletableFuture.anyOf(borrowed, borrower));
                }, () -> retirementRequested.complete(null));
                var borrower = borrowerReference.get(); collect(queue, dropped.referent());
                // Collection/claim is not execution. The real free must return while
                // the borrower is still parked, proving it did not block the only HEC.
                awaitOwnedFreeTask(retirementRequested);
                awaitOwnedFreeTask(ownedFreeTask(state, () -> {
                    requestGc(gc); assertDoesNotThrow(() -> address.readWord8(0));
                    assertEquals(0L, state.getWeaks().finalize(dropped.weak()).getFlag());
                    assertTrue(release.tryPut(Unit.INSTANCE));
                }));
                awaitOwnedFreeTask(borrower);
                awaitOwnedFreeTask(ownedFreeTask(state, () -> {
                    requestGc(gc); assertThrows(RuntimeFault.class, () -> address.readWord8(0));
                }));
            } finally { release.tryPut(Unit.INSTANCE); context.leave(); }
        }
    }
    @Test
    void ownedFreeExplicitFinalizeRacesManagedGcWithoutReplayingRetirement() throws Exception {
        for (var backend : List.of("ast", "bytecode"))
        for (boolean collected : new boolean[]{false, true}) try (var context = context(true)) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = weakProgram(language, backend, true); var gc = gcProgram(language, backend, GcForeignOp.MINOR);
                var address = state.getNativeAllocations().malloc(8); var queue = new ReferenceQueue<Object>();
                var key = new ManagedMutVar(Unit.INSTANCE); Object weak;
                if (collected) {
                    var dropped = droppedOwnedFree(program, queue, address); weak = dropped.weak(); collect(queue, dropped.referent());
                } else {
                    weak = state.getWeaks().make(key, new ManagedMutVar(key), null, null);
                    assertEquals(1L, state.getWeaks().addCFinalizer(state.cbits().finalizerLabel("free"), address, 0L, ManagedAddress.nullAddress(), weak, state.cbits()));
                }
                var gate = new ManagedMVar();
                var finalizing = ownedFreeTask(state, () -> {
                    gate.read(null); assertEquals(0L, state.getWeaks().finalize(weak).getFlag());
                });
                var draining = ownedFreeTask(state, () -> { gate.read(null); requestGc(gc); });
                try {
                    assertTrue(gate.tryPut(Unit.INSTANCE)); awaitOwnedFreeTask(finalizing); awaitOwnedFreeTask(draining);
                } finally { gate.tryPut(Unit.INSTANCE); }
                assertThrows(RuntimeFault.class, () -> address.readWord8(0));
                assertEquals(0L, state.getWeaks().finalize(weak).getFlag()); requestGc(gc); Reference.reachabilityFence(key);
            } finally { context.leave(); }
        }
    }
    @Test
    void ownedFreeDrainCannotOutliveContextCancellation() throws Exception {
        for (var backend : List.of("ast", "bytecode")) for (var hosting : List.of("platform", "loom")) {
            var context = context(true, hosting); context.initialize("thc"); context.enter();
            var release = new ManagedMVar(); CompletableFuture<Void> draining = null;
            java.lang.foreign.MemorySegment segment;
            try {
                var state = Language.currentState(); state.getThreads().setCapabilityCount(1);
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = weakProgram(language, backend, true); var gc = gcProgram(language, backend, GcForeignOp.MINOR);
                var address = state.getNativeAllocations().malloc(8);
                var queue = new ReferenceQueue<Object>(); var ready = new CompletableFuture<Void>();
                var segmentReference = new java.util.concurrent.atomic.AtomicReference<java.lang.foreign.MemorySegment>();
                var drainingReference = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Void>>();
                var dropped = droppedOwnedFree(program, queue, address, () -> {
                    var borrower = ownedFreeTask(state, () -> {
                        try (var loan = address.nativeAllocation().borrow()) {
                            segmentReference.set(loan.segment()); ready.complete(null);
                            release.take(null);
                            for (;;) { requestGc(gc); TruffleSafepoint.poll(null); }
                        }
                    });
                    drainingReference.set(borrower);
                    awaitOwnedFreeTask(CompletableFuture.anyOf(ready, borrower));
                });
                draining = drainingReference.get(); segment = segmentReference.get();
                collect(queue, dropped.referent());
                // Cancellation unwinds the active borrower and completes its pending retirement.
                requestGc(gc); assertTrue(segment.scope().isAlive());
            } finally { release.tryPut(Unit.INSTANCE); context.leave(); }
            assertTrue(segment.scope().isAlive(), "The borrower retains native storage until cancellation");
            try { context.close(true); }
            finally { context.close(true); }
            assertNotNull(draining); assertTrue(draining.isDone(), "Context close joins admitted drains");
            assertFalse(segment.scope().isAlive());
            assertThrows(IllegalStateException.class, () -> segment.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0));
        }
    }
    private Registration completedThunkKey(Language language, ExecutableProgram program,
            Object representative, ReferenceQueue<Object> queue, Object value, Runnable callback, boolean completed) {
        var key = new Thunk(new RootNode(language) {
            @Override public Object execute(VirtualFrame frame) { return representative; }
        }.getCallTarget(), null);
        var registry = Language.currentState().getWeaks();
        var registrationKey = key;
        if (completed) {
            var wrapper = new Thunk(key.getTarget(), null);
            assertSame(representative, program.hostEntryTarget(0).call(key));
            wrapper.setValue(key); wrapper.setState(2); registrationKey = wrapper;
        }
        var weak = registry.make(registrationKey, value, null, null);
        assertEquals(1L, registry.addCallback(weak, callback));
        assertEquals(completed ? 2 : 0, key.getState(), "Registration must leave thunk state unchanged");
        if (!completed) assertSame(representative, program.hostEntryTarget(0).call(key));
        assertEquals(2, key.getState(), "The ordinary Force update published the indirection");
        return new Registration(weak, new WeakReference<>(key, queue));
    }
    private record LiftedAlias(jam.vm.Lifted answer) implements jam.vm.Lifted {
        @Override public jam.vm.Lifted resolve() { return answer; }
        @Override public jam.vm.Lifted project(int field) { return answer.project(field); }
    }
    private Registration liftedProtocolKey(ManagedWeaks registry, jam.vm.Lifted answer,
            ReferenceQueue<Object> queue, Object value) {
        var alias = new LiftedAlias(answer);
        return new Registration(registry.make(alias, value, null, null), new WeakReference<>(alias, queue));
    }
    @Test
    void liftedProtocolKeysUseTheExistingAnswerWithoutForcing() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var answer = new DataLayout(language, "test:Answer", "Answer", new String[]{"IntRep"}).createLong(42L);
                var registry = Language.currentState().getWeaks();
                var value = new Object(); var queue = new ReferenceQueue<Object>();
                var registration = liftedProtocolKey(registry, answer, queue, value);
                collect(queue, registration.referent());
                assertSame(value, registry.dereference(registration.weak()).getValue());
                registry.finalize(registration.weak());
                assertEquals(0L, registry.dereference(registration.weak()).getFlag());
                Reference.reachabilityFence(answer);
            } finally { context.leave(); }
        }
    }
    @Test
    void completedThunkWeakKeysFollowIndependentlyLiveWhnfAliases() throws Exception {
        for (var backend : List.of("ast", "bytecode"))
        for (boolean completed : new boolean[]{false, true}) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = weakProgram(language, backend, false); var representative = new Object();
                var queue = new ReferenceQueue<Object>();
                var value = new ManagedMutVar(representative); var calls = new ArrayList<Integer>();
                var registration = completedThunkKey(language, program, representative, queue, value, () -> calls.add(1), completed);
                collect(queue, registration.referent());
                var registry = Language.currentState().getWeaks();
                assertSame(value, registry.dereference(registration.weak()).getValue(),
                    "A live WHNF alias retains the original distinct value through bootstrap handoff");
                assertTrue(calls.isEmpty(), "Transferring a registration must not execute its real finalizer");
                assertEquals(1L, registry.addCallback(registration.weak(), () -> calls.add(2)));
                assertEquals(0L, registry.finalize(registration.weak()).getFlag());
                assertEquals(List.of(2, 1), calls);
                assertEquals(0L, registry.finalize(registration.weak()).getFlag());
                assertEquals(List.of(2, 1), calls);
                Reference.reachabilityFence(representative);
            } finally { context.leave(); }
        }
    }
    @Test
    void explicitBootstrapFinalizationUnwrapsTheOriginalActionWithoutForcingOpaqueStates() {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var registry = Language.currentState().getWeaks();
                for (int state : new int[]{0, 1, 2, 3, 4, 5}) {
                    var key = new Thunk(new RootNode(language) {
                        @Override public Object execute(VirtualFrame frame) { throw new AssertionError("Weak operation forced its key"); }
                    }.getCallTarget(), null);
                    key.setValue(state == 2 ? key : new IllegalStateException("opaque failure"));
                    key.setOwner(state == 1 ? Thread.currentThread() : null); key.setState(state);
                    var value = new Object[]{key}; var action = new Object(); var calls = new ArrayList<Integer>();
                    var weak = registry.make(key, value, action, new Object());
                    assertEquals(1L, registry.addCallback(weak, () -> calls.add(1)));
                    assertEquals(1L, registry.addCallback(weak, () -> calls.add(2)));
                    assertSame(value, registry.dereference(weak).getValue());
                    assertSame(action, registry.finalize(weak).getValue());
                    assertEquals(List.of(2, 1), calls); assertEquals(state, key.getState());
                    assertEquals(0L, registry.dereference(weak).getFlag());
                    assertEquals(0L, registry.addCallback(weak, () -> fail("Retired bootstrap acquired a callback")));
                    assertEquals(0L, registry.finalize(weak).getFlag());
                    assertEquals(List.of(2, 1), calls); Reference.reachabilityFence(key);
                }
            } finally { context.leave(); }
        }
    }
    private Registration droppedBootstrap(Language language, ManagedWeaks registry, boolean cyclic,
            ReferenceQueue<Object> queue, CompletableFuture<Void> started, ManagedMVar release) {
        var key = new Thunk(new RootNode(language) {
            @Override public Object execute(VirtualFrame frame) { throw new AssertionError("Bootstrap forced a dead key"); }
        }.getCallTarget(), null);
        if (cyclic) {
            var other = new Thunk(key.getTarget(), null);
            other.setValue(key); other.setState(2); key.setValue(other); key.setState(2);
        }
        var value = new Object(); var weak = registry.make(key, value, null, null);
        assertEquals(1L, registry.addCallback(weak, () -> { started.complete(null); release.take(null); }));
        return new Registration(weak, new WeakReference<>(value, queue));
    }
    @Test
    void automaticBootstrapDeathClearsBackingBeforeARealFinalizerBlocks() throws Exception {
        for (var hosting : List.of("platform", "loom"))
        for (boolean cyclic : new boolean[]{false, true}) try (var context = context(false, hosting)) {
            context.initialize("thc"); context.enter(); var release = new ManagedMVar();
            try {
                var state = Language.currentState(); state.getThreads().setCapabilityCount(1);
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var registry = state.getWeaks(); var queue = new ReferenceQueue<Object>();
                var started = new CompletableFuture<Void>();
                var dropped = droppedBootstrap(language, registry, cyclic, queue, started, release);
                var gcQueue = new ReferenceQueue<Object>();
                collect(gcQueue, gcWitness(gcQueue)); awaitOwnedFreeTask(started);
                collect(queue, dropped.referent());
                assertEquals(0L, registry.dereference(dropped.weak()).getFlag());
                assertEquals(0L, registry.addCallback(dropped.weak(), () -> fail("Dead bootstrap acquired a callback")));
                assertEquals(0L, registry.finalize(dropped.weak()).getFlag(),
                    "Operations must finish while the already-claimed real finalizer is blocked");
            } finally { release.tryPut(Unit.INSTANCE); context.leave(); }
        }
    }
    @Test
    void claimedBootstrapWaitsCooperativelyUntilAnotherGuestSettlesTheHandoff() throws Exception {
        for (var backend : List.of("ast", "bytecode"))
        for (var operation : List.of("dereference", "attach", "finalize")) try (var context = context(false, "loom")) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); state.getThreads().setCapabilityCount(1);
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var program = weakProgram(language, backend, false); var registry = state.getWeaks();
                var representative = new Object(); var value = new Object(); var calls = new CopyOnWriteArrayList<Integer>();
                var key = new Thunk(new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) { return representative; }
                }.getCallTarget(), null);
                var weak = registry.make(key, value, null, null);
                assertEquals(1L, registry.addCallback(weak, () -> calls.add(1)));
                assertSame(representative, program.hostEntryTarget(0).call(key));
                // The ordinary native claim seam holds a retired bootstrap before
                // its Runnable enters. Reflection exposes no new runtime/test API.
                var tokenField = weak.getClass().getDeclaredField("token"); tokenField.setAccessible(true);
                long token = tokenField.getLong(weak);
                var bootstrap = jam.vm.Weak.finalizeNow(token); assertNotNull(bootstrap);
                var settled = new java.util.concurrent.atomic.AtomicBoolean();
                try {
                    var entered = new CompletableFuture<Void>();
                    var waiting = ownedFreeTask(state, () -> {
                        entered.complete(null);
                        switch (operation) {
                            case "dereference" -> assertSame(value, registry.dereference(weak).getValue());
                            case "attach" -> assertEquals(1L, registry.addCallback(weak, () -> calls.add(2)));
                            case "finalize" -> assertEquals(0L, registry.finalize(weak).getFlag());
                            default -> throw new AssertionError(operation);
                        }
                    });
                    awaitOwnedFreeTask(entered);
                    awaitOwnedFreeTask(ownedFreeTask(state, () -> {
                        assertFalse(waiting.isDone(), "A claimed bootstrap is pending, not dead");
                        settled.set(true); bootstrap.run();
                    }));
                    awaitOwnedFreeTask(waiting);
                    assertEquals(0L, registry.finalize(weak).getFlag());
                    assertEquals(operation.equals("attach") ? List.of(2, 1) : List.of(1), calls);
                    assertEquals(0L, registry.finalize(weak).getFlag());
                } finally {
                    try { if (!settled.getAndSet(true)) bootstrap.run(); }
                    finally { jam.vm.Weak.complete(token); }
                }
                Reference.reachabilityFence(key); Reference.reachabilityFence(representative);
            } finally { context.leave(); }
        }
    }
    private Registration droppedValueCycle(ManagedWeaks registry, ReferenceQueue<Object> queue) {
        var key = new Object();
        var weak = registry.make(key, new Object[]{key}, null, null);
        return new Registration(weak, new WeakReference<>(key, queue));
    }
    @Test
    void arbitraryJavaKeysRetainConditionalValuesWithoutActivatingValueBackedges() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var registry = Language.currentState().getWeaks(); var key = new Object();
                var queue = new ReferenceQueue<Object>(); var live = conditionalValue(registry, key, queue);
                var dropped = droppedValueCycle(registry, queue);
                collect(queue, dropped.referent());
                assertEquals(0L, registry.dereference(dropped.weak()).getFlag());
                assertNotNull(live.referent().get(), "An independently rooted arbitrary key retains its distinct value");
                assertSame(live.referent().get(), registry.dereference(live.weak()).getValue());
                assertEquals(0L, registry.finalize(live.weak()).getFlag());
                collect(queue, live.referent());
                Reference.reachabilityFence(key);
            } finally { context.leave(); }
        }
    }
    @Test
    void liveMutVarKeysRetainDroppedRegistrationsUntilDetached() throws Exception {
        for (var key : List.of(new Object(), new ManagedMVar(), new ManagedMutVar(Unit.INSTANCE)))
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var registry = Language.currentState().getWeaks();
                var queue = new ReferenceQueue<Object>();
                var first = conditionalValue(registry, key, queue); var droppedValue = first.referent(); first = null;
                var second = conditionalValue(registry, key, queue);
                collect(queue, gcWitness(queue));
                assertNotNull(droppedValue.get()); assertNotNull(second.referent().get());
                assertEquals(0L, registry.finalize(second.weak()).getFlag());
                collect(queue, second.referent());
                assertNotNull(droppedValue.get(), "Detaching one registration must preserve the other value");
                Reference.reachabilityFence(key);
            } finally { context.leave(); }
        }
    }
    @Test
    void retainedMVarRequestKeepsConditionalValueAliveUntilRequestIsDropped() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var registry = Language.currentState().getWeaks(); var key = new ManagedMVar();
                var request = key.beginRead(); var value = new ManagedMutVar(key);
                var queue = new ReferenceQueue<Object>(); var keyReference = new WeakReference<Object>(key, queue);
                var valueReference = new WeakReference<>(value); var weak = registry.make(key, value, null, null);
                key = null; value = null;
                collect(queue, gcWitness(queue));
                assertNotNull(keyReference.get()); assertNotNull(valueReference.get());
                assertSame(valueReference.get(), registry.dereference(weak).getValue());
                assertTrue(request.cancel());
                collect(queue, gcWitness(queue));
                assertNotNull(keyReference.get(), "Even a cancelled retained request still owns its MVar");
                assertSame(valueReference.get(), registry.dereference(weak).getValue());
                Reference.reachabilityFence(request); request = null;
                collect(queue, keyReference);
                assertNull(valueReference.get()); assertEquals(0L, registry.dereference(weak).getFlag());
                assertEquals(0L, registry.finalize(weak).getFlag());
            } finally { context.leave(); }
        }
    }
    @Test
    void explicitFinalizationPreservesValuesAndCallbackOrder() throws Exception {
        for (boolean mvarKey : new boolean[]{false, true})
        for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var registry = Language.currentState().getWeaks(); var program = weakProgram(language, backend, false);
                var key = new Object(); var identity = makeIdentity(program, key);
                var calls = new ArrayList<Integer>();
                assertEquals(1L, registry.addCallback(identity, () -> {
                    assertEquals(0L, registry.dereference(identity).getFlag()); calls.add(1);
                }));
                assertEquals(1L, registry.addCallback(identity, () -> calls.add(2)));
                Object managedKey = mvarKey ? new ManagedMVar() : new ManagedMutVar(Unit.INSTANCE);
                var managedValue = new ManagedMutVar(managedKey);
                var managedWeak = registry.make(managedKey, managedValue, null, null); int[] managedCalls = {0};
                assertEquals(1L, registry.addCallback(managedWeak, () -> ++managedCalls[0]));
                var value = new Object(); var dependentKey = new Object();
                var dependent = registry.make(dependentKey, value, null, null);
                assertEquals(1L, observeIdentity(program, identity)); assertTrue(calls.isEmpty());
                assertSame(managedValue, registry.dereference(managedWeak).getValue());
                assertEquals(0, managedCalls[0]);
                assertEquals(0L, registry.finalize(managedWeak).getFlag()); assertEquals(1, managedCalls[0]);
                assertEquals(0L, registry.finalize(managedWeak).getFlag()); assertEquals(1, managedCalls[0]);
                assertSame(value, registry.dereference(dependent).getValue());
                assertEquals(0L, registry.finalize(identity).getFlag()); assertEquals(List.of(2, 1), calls);
                assertEquals(0L, registry.finalize(identity).getFlag()); assertEquals(List.of(2, 1), calls);
                registry.finalize(dependent);
                assertEquals(0L, registry.dereference(dependent).getFlag());
                // Keep every key live through the explicit-finalization checks on both backends.
                Reference.reachabilityFence(key); Reference.reachabilityFence(managedKey);
                Reference.reachabilityFence(dependentKey);
            } finally { context.leave(); }
        }
    }

    @Test
    void finalizationIsLinearizableAndFailureDoesNotReviveRegistration() throws Exception {
        try (var context = context(true)) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); var registry = state.getWeaks();
                var key = new Object(); var weak = registry.make(key, new Object(), null, null);
                var calls = new java.util.concurrent.atomic.AtomicInteger();
                assertEquals(1L, registry.addCallback(weak, calls::incrementAndGet));
                var ready = new CountDownLatch(2); var gate = new ManagedMVar();
                var results = new ConcurrentLinkedQueue<WeakResult>();
                var workers = new ArrayList<CompletableFuture<Void>>();
                try {
                    for (int i = 0; i < 2; i++) workers.add(ownedFreeTask(state, () -> {
                        ready.countDown(); gate.read(null); results.add(registry.finalize(weak));
                    }));
                    TruffleSafepoint.setBlockedThreadInterruptible(null, pending ->
                        assertTrue(pending.await(3, TimeUnit.SECONDS)), ready);
                    assertTrue(gate.tryPut(Unit.INSTANCE));
                    for (var worker : workers) awaitOwnedFreeTask(worker);
                    assertEquals(2, results.size());
                    for (var result : results) { assertEquals(0L, result.getFlag()); assertNull(result.getValue()); }
                    assertEquals(1, calls.get());
                    assertEquals(0L, registry.dereference(weak).getFlag());
                    var failure = new IllegalStateException("explicit callback failure");
                    var failing = registry.make(key, new Object(), null, null);
                    assertEquals(1L, registry.addCallback(failing, () -> { throw failure; }));
                    assertSame(failure, assertThrows(IllegalStateException.class, () -> registry.finalize(failing)));
                    assertEquals(0L, registry.dereference(failing).getFlag());
                    assertEquals(0L, registry.finalize(failing).getFlag());
                } finally { gate.tryPut(Unit.INSTANCE); Reference.reachabilityFence(key); }
            } finally { context.leave(); }
        }
    }
    @Test
    void finalizerSetupFailuresPreserveUntouchedWorkAndJoinStartedCarriers() throws Exception {
        assertAll(List.of("before-start", "guest-entry", "after-start", "callback").stream()
            .<Executable>map(phase -> () -> {
                try (var context = context()) {
                    context.initialize("thc"); context.enter(); var release = new ManagedMVar();
                    var claimThread = new AtomicReference<Thread>(); var completed = new CompletableFuture<Throwable>();
                    try {
                        var state = Language.currentState(); var registry = state.getWeaks(); var threads = state.getThreads();
                        var affinity = threads.getCpuAffinity();
                        var providerField = CpuAffinity.class.getDeclaredField("nativeAffinity"); providerField.setAccessible(true);
                        var originalProvider = providerField.get(affinity);
                        var failure = new CancellationException("intentional weak " + phase + " failure");
                        var entered = new CompletableFuture<Void>(); var calls = new java.util.concurrent.atomic.AtomicInteger();
                        var key = new Object(); var action = phase.equals("after-start") ? null : new Object();
                        var weak = registry.make(key, new Object(), action, action == null ? null : new Object());
                        assertEquals(1L, registry.addCallback(weak, () -> {
                            calls.incrementAndGet(); entered.complete(null);
                            if (phase.equals("after-start")) release.take(null);
                            if (phase.equals("callback")) throw failure;
                        }));
                        var tokenField = weak.getClass().getDeclaredField("token"); tokenField.setAccessible(true);
                        long token = tokenField.getLong(weak);
                        var claimed = jam.vm.Weak.finalizeNow(token); assertNotNull(claimed);
                        providerField.set(affinity, new NativeCpuAffinity() {
                            @Override public int getCount() { return 1; }
                            @Override public CpuAffinityMode getMode() { return CpuAffinityMode.ADVISORY; }
                            @Override public AutoCloseable bindCurrent(int index) { return null; }
                            @Override public AutoCloseable resetCurrent() {
                                boolean launcher = Thread.currentThread() == claimThread.get();
                                if (phase.equals("before-start") && launcher || phase.equals("guest-entry") && !launcher)
                                    throw failure;
                                return phase.equals("after-start") && launcher ? () -> { throw failure; } : null;
                            }
                        });
                        Thread carrier = null;
                        try {
                            carrier = Thread.ofVirtual().start(() -> {
                                claimThread.set(Thread.currentThread()); Throwable outcome = null;
                                try { claimed.run(); } catch (Throwable caught) { outcome = caught; }
                                finally { jam.vm.Weak.complete(token); }
                                completed.complete(outcome);
                            });
                            if (phase.equals("after-start")) {
                                awaitOwnedFreeTask(entered);
                                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                                while (carrier.getState() != Thread.State.WAITING && !completed.isDone()
                                        && carrier.getState() != Thread.State.TERMINATED && System.nanoTime() < deadline) {
                                    TruffleSafepoint.poll(null); Thread.yield();
                                }
                                assertEquals(Thread.State.WAITING, carrier.getState(), "Claim owner is joining the held real carrier");
                                assertFalse(completed.isDone(), "A thrown affinity restore must still join the started carrier");
                                assertEquals(0L, registry.finalize(weak).getFlag(), "Begun cleanup cannot be claimed again");
                                assertEquals(1, calls.get()); release.tryPut(Unit.INSTANCE);
                            }
                            awaitOwnedFreeTask(completed);
                            assertSame(phase.equals("callback") ? null : failure, completed.getNow(null));
                            boolean untouched = phase.equals("before-start") || phase.equals("guest-entry");
                            if (untouched) {
                                assertEquals(0, calls.get());
                                assertSame(failure, assertThrows(CancellationException.class, () -> registry.dereference(weak)));
                                assertSame(action, registry.finalize(weak).getValue(), "Unentered cleanup remains explicitly settleable");
                            } else assertEquals(0L, registry.finalize(weak).getFlag());
                            assertEquals(1, calls.get()); assertEquals(0L, registry.finalize(weak).getFlag());
                            assertEquals(1, calls.get()); Reference.reachabilityFence(key);
                        } finally {
                            release.tryPut(Unit.INSTANCE);
                            try {
                                if (carrier != null) TruffleSafepoint.setBlockedThreadInterruptible(null, Thread::join, carrier);
                                else jam.vm.Weak.complete(token);
                            } finally { providerField.set(affinity, originalProvider); }
                        }
                    } finally { release.tryPut(Unit.INSTANCE); context.leave(); }
                }
            }));
    }
    @Test
    void explicitCallbacksRunNewestFirstAfterDeathOutsideTheRegistryLock() {
        try (var context = context(true)) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); var registry = state.getWeaks();
                var key = new Object(); var weak = registry.make(key, new Object(), null, null);
                var observed = new ArrayList<Integer>();
                assertEquals(1L, registry.addCallback(weak, () -> observed.add(1)));
                assertEquals(1L, registry.addCallback(weak, () -> {
                    awaitOwnedFreeTask(ownedFreeTask(state, () -> assertEquals(0L, registry.dereference(weak).getFlag())));
                    observed.add(2);
                }));
                assertEquals(0L, registry.finalize(weak).getFlag());
                assertEquals(List.of(2, 1), observed);
                assertEquals(0L, registry.addCallback(weak, () -> observed.add(3)));
                assertEquals(0L, registry.finalize(weak).getFlag());
                assertEquals(List.of(2, 1), observed);
                Reference.reachabilityFence(key);
            } finally { context.leave(); }
        }
    }
    @Test
    void actualContextCloseInvalidatesHandlesAndReleasesConditionalValues() throws Exception {
        for (boolean mvarKey : new boolean[]{false, true}) {
            var first = context();
            first.initialize("thc");
            first.enter();
            var owner = Language.currentState().getWeaks();
            var handleKey = new Object();
            var handle = owner.make(handleKey, new Object(), null, null);
            Object key = mvarKey ? new ManagedMVar() : new ManagedMutVar(Unit.INSTANCE); var queue = new ReferenceQueue<Object>();
            var conditional = conditionalValue(owner, key, queue);
            first.leave();
            try (var second = context()) {
                second.initialize("thc");
                second.enter();
                try {
                    assertThrows(RuntimeFault.class, () -> Language.currentState().getWeaks().dereference(handle));
                    assertThrows(RuntimeFault.class, () -> Language.currentState().getWeaks().finalize(handle));
                    assertThrows(RuntimeFault.class, () -> Language.currentState().getWeaks().dereference(new Object()));
                } finally {
                    second.leave();
                }
            }
            first.close();
            assertThrows(RuntimeFault.class, () -> owner.finalize(handle));
            assertThrows(RuntimeFault.class, () -> owner.make(new Object(), new Object(), null, null));
            assertThrows(RuntimeFault.class, () -> owner.dereference(conditional.weak()));
            collect(queue, conditional.referent()); Reference.reachabilityFence(key); Reference.reachabilityFence(handleKey);
        }
    }
    @Test
    void mainThreadCapabilityProjectsOnlyLiveContextOwnedThreadKeys() {
        var first = context();
        first.initialize("thc");
        first.enter();
        MainThreadWeakKey capability;
        MainThreadWeakKey closing;
        try {
            var state = Language.currentState();
            var owner = state.getWeaks();
            var threads = state.getThreads();
            threads.enterCurrent(null, false, true, null);
            var key = threads.currentIdentity();
            var weak = owner.make(key, new Object(), null, null);
            try {
                capability = owner.mainThreadKey(weak, threads);
                assertEquals(Thread.currentThread().threadId(), capability.liveJavaId(),
                    "Native main-thread projection reads KEY, not value");
                var impostor = new GuestThreadId(
                    key.getLogicalId(), threads, key.getCapability(), Thread.currentThread(), false, false, false);
                assertEquals(key, impostor, "Numeric equality alone must not establish canonical identity");
                assertNull(threads.liveJavaId(impostor));
                assertThrows(RuntimeFault.class, () -> owner.mainThreadKey(owner.make(impostor, new Object(), null, null), threads));
                closing = owner.mainThreadKey(owner.make(key, new Object(), null, null), threads);
                var wrongKey = owner.make(new Object(), key, null, null);
                assertThrows(RuntimeFault.class, () -> owner.mainThreadKey(wrongKey, threads));
            } finally {
                threads.leaveCurrent(GuestThreadStatus.FINISHED);
            }
            assertEquals(key.getJavaId(), capability.liveJavaId(), "A live FOREIGN host carrier is not terminated");
            try (var second = context()) {
                second.initialize("thc");
                second.enter();
                try {
                    var foreign = Language.currentState();
                    foreign.getThreads().enterCurrent(null, false, true, null);
                    try {
                        assertThrows(
                            RuntimeFault.class, () -> foreign.getWeaks().mainThreadKey(weak, foreign.getThreads()));
                        assertThrows(RuntimeFault.class, () -> owner.mainThreadKey(weak, foreign.getThreads()));
                        assertThrows(RuntimeFault.class, () -> foreign.getThreads().liveJavaId(key));
                        var foreignKey = owner.make(foreign.getThreads().currentIdentity(), new Object(), null, null);
                        assertThrows(RuntimeFault.class, () -> owner.mainThreadKey(foreignKey, threads));
                    } finally {
                        foreign.getThreads().leaveCurrent(GuestThreadStatus.FINISHED);
                    }
                } finally {
                    second.leave();
                }
            }
            assertEquals(0L, owner.finalize(weak).getFlag());
            assertNull(capability.liveJavaId());
            assertThrows(RuntimeFault.class, () -> owner.mainThreadKey(weak, threads));
            assertEquals(key.getJavaId(), closing.liveJavaId());
            threads.close();
            assertNull(closing.liveJavaId(), "Thread service close invalidates even a still-live weak registration");
        } finally {
            first.leave();
            first.close();
        }
        assertNull(closing.liveJavaId());
        assertNull(capability.liveJavaId());
    }
    @Test
    void weakThreadIdentityFollowsGuestLifetimeRatherThanRetainedCarrier() throws Exception {
        for (var hosting : List.of("platform", "loom"))
        for (boolean forked : hosting.equals("loom") ? new boolean[]{false, true} : new boolean[]{true})
        try (var context = context(true, hosting)) {
            context.initialize("thc"); context.enter();
            try {
                var state = Language.currentState(); var threads = state.getThreads();
                var owner = state.getWeaks(); var queue = new ReferenceQueue<Object>();
                var registration = new AtomicReference<Registration>();
                var retained = new AtomicReference<GuestThreadId>();
                var failure = new AtomicReference<Throwable>();
                var ready = new CountDownLatch(1); var finish = new CountDownLatch(1);
                state.admitGuestConcurrency();
                var worker = threads.newThread(state.getEnv(), () -> {
                    threads.enterCurrent(null, forked, true, null);
                    try {
                        retained.set(threads.currentIdentity());
                        registration.set(new Registration(
                            owner.make(threads.currentIdentity(), threads.currentIdentity(), null, null),
                            new WeakReference<>(threads.currentIdentity(), queue)));
                        ready.countDown();
                        if (!finish.await(10, TimeUnit.SECONDS)) throw new AssertionError("Guest was not released");
                    } catch (Throwable caught) {
                        failure.set(caught); ready.countDown();
                    } finally { threads.leaveCurrent(GuestThreadStatus.FINISHED); }
                }, null, null);
                threads.startThread(worker);
                try {
                    assertTrue(ready.await(3, TimeUnit.SECONDS));
                    if (failure.get() != null) throw new AssertionError("Guest failed", failure.get());
                    var weak = registration.get().weak();
                    retained.set(null);
                    var activeQueue = new ReferenceQueue<Object>();
                    collect(activeQueue, gcWitness(activeQueue));
                    assertEquals(1L, owner.dereference(weak).getFlag(), "The active guest roots its ThreadId#");
                    retained.set((GuestThreadId) owner.dereference(weak).getValue());
                    finish.countDown(); worker.join(3000); assertFalse(worker.isAlive());
                    if (failure.get() != null) throw new AssertionError("Guest failed", failure.get());
                    assertEquals(GuestThreadStatus.FINISHED, threads.status(retained.get()));
                    var retainedQueue = new ReferenceQueue<Object>();
                    collect(retainedQueue, gcWitness(retainedQueue));
                    assertSame(retained.get(), owner.dereference(weak).getValue(), "A Java-held ThreadId# is a root");
                    retained.set(null);
                    collect(queue, registration.get().referent());
                    assertEquals(0L, owner.dereference(weak).getFlag(), "Retaining a finished carrier does not root its guest identity");
                    Reference.reachabilityFence(worker);
                } finally { finish.countDown(); worker.join(3000); }
            } finally { context.leave(); }
        }
    }
    private record ThreadOutcome(boolean forked, GuestThreadStatus outcome) {}
    @Test
    void mainThreadCapabilityDoesNotResurrectTerminalIdentitiesOrDeadOriginalCarriers() throws Exception {
        for (var test : List.of(new ThreadOutcome(true, GuestThreadStatus.FINISHED),
                 new ThreadOutcome(true, GuestThreadStatus.DIED),
                 new ThreadOutcome(true, GuestThreadStatus.RUNTIME_FAILURE),
                 new ThreadOutcome(false, GuestThreadStatus.FINISHED))) {
            boolean forked = test.forked();
            var outcome = test.outcome();
            try (var context = context(true)) {
                context.initialize("thc"); context.enter();
                try {
                    var state = Language.currentState(); var threads = state.getThreads();
                    var owner = state.getWeaks();
                    var capability = new AtomicReference<MainThreadWeakKey>();
                    var weak = new AtomicReference<Object>();
                    var failure = new AtomicReference<Throwable>();
                    var ready = new CountDownLatch(1);
                    var leave = new CountDownLatch(1);
                    var left = new CountDownLatch(1);
                    var stop = new CountDownLatch(1);
                    state.admitGuestConcurrency();
                    var worker = threads.newThread(state.getEnv(), () -> {
                        boolean entered = false;
                        try {
                            threads.enterCurrent(null, forked, true, null);
                            entered = true;
                            weak.set(owner.make(threads.currentIdentity(), new Object(), null, null));
                            capability.set(owner.mainThreadKey(weak.get(), threads));
                            ready.countDown();
                            if (!leave.await(3, TimeUnit.SECONDS))
                                throw new IllegalStateException("Check failed.");
                            threads.leaveCurrent(outcome);
                            entered = false;
                            left.countDown();
                            if (!stop.await(3, TimeUnit.SECONDS))
                                throw new IllegalStateException("Check failed.");
                        } catch (Throwable error) {
                            failure.set(error);
                            ready.countDown();
                            left.countDown();
                        } finally {
                            if (entered)
                                threads.leaveCurrent(outcome);
                        }
                    }, null, null);
                    threads.startThread(worker);
                    try {
                        assertTrue(ready.await(3, TimeUnit.SECONDS));
                        if (failure.get() != null)
                            throw new AssertionError("thread registration failed", failure.get());
                        assertEquals(worker.threadId(), capability.get().liveJavaId());
                        leave.countDown();
                        assertTrue(left.await(3, TimeUnit.SECONDS));
                        if (failure.get() != null)
                            throw new AssertionError("thread completion failed", failure.get());
                        assertTrue(worker.isAlive(), "Separate terminal guest status from actual Java carrier death");
                        if (forked)
                            assertNull(capability.get().liveJavaId());
                        else
                            assertEquals(worker.threadId(), capability.get().liveJavaId());
                        stop.countDown();
                        worker.join(3000);
                        assertFalse(worker.isAlive());
                        if (failure.get() != null)
                            throw new AssertionError("worker failed", failure.get());
                        assertNull(capability.get().liveJavaId());
                        assertEquals(1L, owner.dereference(weak.get()).getFlag(), "Liveness query must not finalize the weak");
                        threads.close();
                        assertNull(capability.get().liveJavaId());
                    } finally {
                        leave.countDown();
                        stop.countDown();
                        worker.join(3000);
                    }
                } finally { context.leave(); }
            }
        }
    }
    private CoreRepresentation proof(CoreKind kind, List<String> reps) {
        return new CoreRepresentation(kind, false, true, reps, null, null, null, null, null);
    }
    private final CoreRepresentation state = proof(CoreKind.VOID, List.of()),
                                     weak = proof(CoreKind.OBJECT, List.of("BoxedRep (Just Unlifted)")),
                                     lifted = proof(CoreKind.DATA, List.of("BoxedRep (Just Lifted)")),
                                     action = proof(CoreKind.CLOSURE, List.of("BoxedRep (Just Lifted)")),
                                     flag = proof(CoreKind.LONG, List.of("IntRep")),
                                     address = proof(CoreKind.ADDRESS, List.of("AddrRep"));
    private final List<WeakOp> originalOps =
        List.of(WeakOp.MAKE, WeakOp.MAKE_PLAIN, WeakOp.DEREFERENCE, WeakOp.FINALIZE);
    private CoreRepresentation tuple(CoreRepresentation... fields) {
        var reps = new ArrayList<String>();
        for (var field : fields) reps.addAll(Objects.requireNonNull(field.getPrimReps()));
        return new CoreRepresentation(
            CoreKind.UNKNOWN, false, true, reps, Arrays.asList(fields), null, null, null, null);
    }
    private CoreRepresentation withReps(CoreRepresentation original, List<String> reps) {
        return original.copy(original.getKind(), original.getEvaluated(), original.getPresent(), reps,
            original.getComponents(), original.getVector(), original.getAlternatives(), original.getTagSlot(),
            original.getAlternativeSlots());
    }
    private List<CoreRepresentation> inputs(WeakOp op, CoreRepresentation value) {
        return switch (op) {
            case MAKE -> List.of(weak, value, action, state);
            case MAKE_PLAIN -> List.of(weak, value, state);
            case ADD_C_FINALIZER -> List.of(address, address, flag, address, weak, state);
            default -> List.of(weak, state);
        };
    }
    private CoreRepresentation output(WeakOp op, CoreRepresentation value) {
        return switch (op) {
            case MAKE, MAKE_PLAIN -> tuple(state, weak);
            case ADD_C_FINALIZER -> tuple(state, flag);
            case DEREFERENCE -> tuple(state, flag, value);
            case FINALIZE -> tuple(state, flag, action);
        };
    }
    private List<Boolean> flags(List<CoreRepresentation> args) {
        var result = new ArrayList<Boolean>();
        for (var arg : args) result.add(List.of("BoxedRep (Just Lifted)").equals(arg.getPrimReps()));
        return result;
    }
    @Test
    void exactWeakContractsRejectForgedStateRepresentationOrBinding() {
        assertEquals(5, WeakOp.values().length);
        for (var op : WeakOp.values())
            for (var value : List.of(lifted, action, weak)) {
                var args = inputs(op, value);
                var result = output(op, value);
                op.validate(args, flags(args), result);
                assertThrows(
                    RuntimeFault.class, () -> op.validate(args.subList(0, args.size() - 1), flags(args), result));
                for (int index = 0; index < args.size(); index++) {
                    var bad = new ArrayList<>(args);
                    bad.set(index, withReps(flag, List.of("WordRep")));
                    assertThrows(RuntimeFault.class, () -> op.validate(bad, flags(bad), result));
                    var wrongFlags = new ArrayList<>(flags(args));
                    wrongFlags.set(index, !wrongFlags.get(index));
                    assertThrows(RuntimeFault.class, () -> op.validate(args, wrongFlags, result));
                    var stored = new ArrayList<>(args);
                    stored.set(index, withReps(flag, List.of("WordRep")));
                    assertThrows(RuntimeFault.class, () -> op.validateBindings(args, stored));
                }
                assertThrows(RuntimeFault.class, () -> op.validate(args, flags(args), withReps(result, List.of())));
                assertThrows(RuntimeFault.class,
                    ()
                        -> op.validate(args, flags(args),
                            tuple(Objects.requireNonNull(result.getComponents())
                                    .subList(1, result.getComponents().size())
                                    .toArray(CoreRepresentation[] ::new))));
            }
        WeakOp.MAKE.validateAction(new CoreFunctionSignature(List.of(state), tuple(state, lifted)));
        assertThrows(RuntimeFault.class,
            () -> WeakOp.MAKE.validateAction(new CoreFunctionSignature(List.of(flag), tuple(state, lifted))));
        assertThrows(RuntimeFault.class,
            () -> WeakOp.MAKE.validateAction(new CoreFunctionSignature(List.of(state), tuple(state, weak))));
        assertThrows(
            RuntimeFault.class, () -> WeakOp.MAKE.validateAction(new CoreFunctionSignature(List.of(state), state)));
    }
    private final File root = new File(System.getProperty("thc.projectRoot")),
                       directory = new File(root, "build/weak-explicit");
    private Map<String, Object> json(File file) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(file.toPath()));
    }
    private String digest(File file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())));
    }
    private void valid(RootCallTarget target) throws Exception {
        assertEquals(
            true, target.getClass().getMethod("isValidLastTier").invoke(target), target.getRootNode().getName());
    }
    private List<RootCallTarget> activeTargets(RootCallTarget entry) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>());
        var found = new ArrayList<RootCallTarget>();
        visit(entry, seen, found);
        return found;
    }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> found) {
        if (!seen.add(target))
            return;
        var body = target.getRootNode();
        var nodes = new ArrayList<Node>();
        nodes.add(body);
        if (body instanceof BytecodeRoot bytecode)
            for (var instruction : bytecode.getBytecodeNode().getInstructions())
                for (var argument : instruction.getArguments())
                    if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE) {
                        var node = argument.asCachedNode();
                        if (node != null)
                            nodes.add(node);
                    }
        for (var node : nodes)
            for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class))
                if (call.getCurrentCallTarget() instanceof RootCallTarget callee
                    && callee.getRootNode() instanceof GuestRoot)
                    visit(callee, seen, found);
        found.add(target);
    }
    private List<List<Object>> applications(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.getFirst()))
                result.add((List<Object>) list);
            for (var child : list) result.addAll(applications(child));}else if(value instanceof Map<?,?> map)
            for (var child : map.values()) result.addAll(applications(child));
        return result;
    }
    private boolean primitive(List<?> expression, String name) {
        return expression.size() > 1 && expression.get(1) instanceof List<?> head && head.size() >= 2
            && head.subList(0, 2).equals(List.of("prim", name));
    }
    private List<Object> firstPrimitive(Object module, String name) {
        for (var app : applications(module))
            if (primitive(app, name))
                return app;
        throw new NoSuchElementException("Missing primitive " + name);
    }
    private Map<String, Object> merge(List<String> paths) throws Exception {
        var inputs = paths.stream().map(path -> path.startsWith("@")
            ? "@" + new File(root, path.substring(1)).getPath() : new File(root, path).getPath()).toList();
        return thc.CoreCbdFixtures.selectedRoots(inputs, "main:WeakAudit.weakComposite", null);
    }
    private ExecutableProgram load(Language language, Map<String, Object> module, String backend) {
        return backend.equals("ast") ? new Program(language, module) : new BytecodeProgram(language, module);
    }
    @Test
    void genuineCoreCannotBypassEitherLoaderWithForgedWeakContracts() throws Exception {
        var manifest = json(new File(directory, "manifest.json"));
        for (var stageEntry : ((Map<String, List<String>>) manifest.get("stages")).entrySet()) {
            var stage = stageEntry.getKey();
            var original = CoreModules.reachable(merge(stageEntry.getValue()), "main:WeakAudit.weakComposite", true);
            for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
                    context.initialize("thc");
                    context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        for (var op : originalOps)
                            for (int mutation = 0; mutation <= 2; mutation++) {
                                var module = (Map<String, Object>) Json.parse(Json.stringify(original));
                                var app = firstPrimitive(module, op.getPrimitive());
                                var args = (List<Object>) app.get(2);
                                switch (mutation) {
                                    case 0 -> {
                                        var flags = (List<Object>) app.get(3);
                                        flags.set(0, !(Boolean) flags.get(0));
                                    }
                                    case 1 ->
                                        ((Map<String, Object>) CoreRepresentations.metadata(
                                             (List<Object>) args.getLast()))
                                            .put("rep", Map.of("kind", "long", "primReps", List.of("IntRep")));
                                    case 2 -> {
                                        var result = (Map<String, Object>) Objects
                                                         .requireNonNull(CoreRepresentations.metadata(app))
                                                         .get("rep");
                                        ((List<Object>) result.get("components")).remove(0);
                                    }
                                }
                                assertThrows(RuntimeFault.class,
                                    ()
                                        -> load(language, module, backend),
                                    stage + "/" + backend + "/" + op.getPrimitive() + "/mutation=" + mutation);
                            }
                    } finally {
                        context.leave();
                    }
                }
        }
    }
    private record Row(long input, long expected) {}
    private List<Row> rows() throws Exception {
        var rows = new ArrayList<Row>();
        for (var line : Files.readAllLines(new File(directory, "oracle.tsv").toPath())) {
            var fields = line.split("\t", -1);
            rows.add(new Row(Long.parseLong(fields[0]), Long.parseLong(fields[1])));
        }
        return rows;
    }
    @Test
    void delayedBoxedCasesPreserveWeakOperandProofs() throws Exception {
        var manifest = json(new File(directory, "manifest.json"));
        var rows = rows();
        var integer = Map.of("kind", "long", "primReps", List.of("IntRep"), "evaluated", true);
        var zero = List.of("lit", "int", "0", Map.of("rep", integer));
        for (var stageEntry : ((Map<String, List<String>>) manifest.get("stages")).entrySet()) {
            var stage = stageEntry.getKey();
            var original = CoreModules.reachable(merge(stageEntry.getValue()), "main:WeakAudit.weakComposite", true);
            for (var backend : List.of("ast", "bytecode")) try (var context = context()) {
                    context.initialize("thc");
                    context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        for (boolean contradictory : new boolean[] {false, true}) {
                            var module = (Map<String, Object>) Json.parse(Json.stringify(original));
                            var app = firstPrimitive(module, "mkWeak#");
                            var args = (List<Object>) app.get(2);
                            var value = (List<Object>) args.get(1);
                            var proof =
                                (Map<String, Object>) Objects.requireNonNull(CoreRepresentations.metadata(value))
                                    .get("rep");
                            // The original ForeignPtr finalizer uses this lifted CASE shape: delaying it must retain
                            // its value proof, not claim WHNF.
                            var delayed = new LinkedHashMap<>(proof);
                            delayed.put("evaluated", false);
                            args.set(1,
                                List.of("case", zero, "delayedWeakCase",
                                    List.of(Arrays.asList("default", null, List.of(), contradictory ? zero : value)),
                                    Map.of("rep", delayed, "binder",
                                        Map.of("id", "delayedWeakCase", "lifted", false, "rep", integer))));
                            if (contradictory)
                                assertThrows(RuntimeFault.class,
                                    ()
                                        -> load(language, module, backend),
                                    stage + "/" + backend + " contradictory delayed result");
                            else {
                                var function = context.asValue(
                                    new EntryValue(load(language, module, backend), "main:WeakAudit.weakComposite", 1));
                                for (var row : rows)
                                    assertEquals(row.expected(), function.execute(row.input()).asLong(),
                                        stage + "/" + backend + "/" + row.input());
                                assertEquals(0, language.getHandoffState().get().getArguments().retainedReferences());
                                assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
                            }
                        }
                    } finally {
                        context.leave();
                    }
                }
        }
    }
    @Test
    void publicWeakAutomaticFinalizerWithBackedgesAndDroppedHandleAgreesWithNativeGhc() throws Exception {
        var manifest = json(new File(directory, "manifest.json"));
        for (var kind : List.of("inputHashes", "artifactHashes"))
            for (var item : ((Map<String, String>) manifest.get(kind)).entrySet())
                assertEquals(item.getValue(), digest(new File(root, item.getKey())),
                    "Stale automatic weak fixture: " + item.getKey());
        long input = ((Number) manifest.get("automaticInput")).longValue();
        long expected = ((Number) manifest.get("automaticResult")).longValue();
        assertEquals(42L, input);
        assertEquals(input, expected);
        var entry = "main:WeakAudit.weakAutomatic";
        var paths = ((Map<String, List<String>>) manifest.get("stages")).get("post");
        var inputs = paths.stream().map(path -> path.startsWith("@")
            ? "@" + new File(root, path.substring(1)).getPath() : new File(root, path).getPath()).toList();
        for (var backend : List.of("ast", "bytecode")) try (var context = context(true)) {
                context.initialize("thc");
                context.enter();
                try {
                    var function = context.eval("thc", CoreModules.request(inputs, entry, true, false, backend));
                    assertEquals(expected, function.execute(input).asLong(), "post/" + backend + " automatic finalizer");
                    var diagnostics = (Map<String, Object>) Json.parse(function.getMember("diagnostics").asString());
                    assertEquals(0L, ((Number) diagnostics.get("unsupportedTraps")).longValue());
                } finally {
                    context.leave();
                }
            }
    }
    @Test
    void genuineNativeCompositeAgreesOnTheFirstInstalledAstAndBytecodeCalls() throws Exception {
        var manifest = json(new File(directory, "manifest.json"));
        assertEquals(1L, manifest.get("schema"));
        assertEquals("9.14.1", manifest.get("ghc"));
        assertEquals("weakComposite", manifest.get("entry"));
        for (var kind : List.of("inputHashes", "artifactHashes"))
            for (var item : ((Map<String, String>) manifest.get(kind)).entrySet())
                assertEquals(item.getValue(), digest(new File(root, item.getKey())),
                    "Stale explicit weak fixture: " + item.getKey());
        var rows = rows();
        assertEquals(8, rows.size());
        assertEquals((long) rows.size(), manifest.get("nativeRows"));
        for (var row : rows) assertEquals(row.input() + 58L, row.expected());
        for (var stageEntry : ((Map<String, List<String>>) manifest.get("stages")).entrySet()) {
            var stage = stageEntry.getKey();
            var audit = json(new File(directory, stage + "/audit.json"));
            assertEquals(true, audit.get("accepted"));
            assertEquals(List.of(), audit.get("issues"));
            assertEquals(List.of(), audit.get("missingGlobals"));
            var primitives = new HashSet<Object>();
            for (var p : (List<Map<String, Object>>) audit.get("primitives")) primitives.add(p.get("name"));
            var names = new ArrayList<String>();
            for (var op : originalOps) names.add(op.getPrimitive());
            assertTrue(primitives.containsAll(names));
            var inputs = stageEntry.getValue().stream().map(path -> path.startsWith("@")
                ? "@" + new File(root, path.substring(1)).getPath() : new File(root, path).getPath()).toList();
            for (var backend : List.of("ast", "bytecode")) try (var context = context(true)) {
                    context.initialize("thc");
                    context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var function = context.eval("thc",
                            CoreModules.request(inputs, "main:WeakAudit.weakComposite", true, false, backend));
                        var programs = Language.currentState().getCoreUnitPrograms();
                        assertEquals(1, programs.size());
                        var program = programs.getFirst();
                        var original = program.entryTarget("main:WeakAudit.weakComposite");
                        assertTrue(original.getRootNode() instanceof GuestRoot);
                        for (var row : rows) assertEquals(row.expected(), function.execute(row.input()).asLong());
                        // The ordinary public entry compiles its actual typed host bridge,
                        // original guest root and active splits, including cached bytecode calls.
                        assertTrue(function.invokeMember("compile").asBoolean());
                        valid(original);
                        for (var row : rows.reversed()) {
                            long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                            assertEquals(row.expected(), function.execute(row.input()).asLong(),
                                stage + "/" + backend + " first installed " + row.input());
                            long delta = ((Number) program.diagnostics().get("compiledEntries")).longValue() - before;
                            var diagnostics = (Map<String, Object>) Json.parse(function.getMember("diagnostics").asString());
                            var installed = (Map<String, Object>) diagnostics.get("explicitCompilation");
                            System.out.println("weak-explicit " + stage + "/" + backend + " input=" + row.input()
                                + " compiledGuestEntries=" + delta + " installation=" + installed);
                            // EntryRoot does not increment compiledEntries; this is guest entry evidence.
                            assertTrue(delta > 0,
                                stage + "/" + backend + " first installed call entered compiled guest code");
                            assertEquals(true, installed.get("validLastTier"));
                            valid(original);
                            assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
                            assertEquals(0, language.getHandoffState().get().getArguments().retainedReferences());
                            assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                            assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
                            assertNull(language.getHandoffState().get().getPending());
                            assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                        }
                        assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue());
                    } finally {
                        context.leave();
                    }
                }
        }
    }
}
