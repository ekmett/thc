// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.ThreadLocalAction;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import org.junit.jupiter.api.Test;
import org.graalvm.polyglot.Context;
import thc.Language;
import thc.CoreModules;
import thc.Json;
import thc.runtime.Unit;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.executionContext;

/** A real GHC Core thunk and local demand, with a private deterministic test checkpoint. */
class CoreContinuationNativeTest {
    private final File root = new File(System.getProperty("thc.projectRoot"));

    private void compile(RootCallTarget target) throws Exception {
        var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
        assertTrue(type.isInstance(target));
        type.getMethod("compile", boolean.class).invoke(target, true);
        type.getMethod("waitForCompilation").invoke(target);
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
        // Restore the shared entry stub without a guest settling call before
        // arming the first-effect checkpoint, as EntryValue.compile does.
        var runtime = Truffle.getRuntime();
        runtime.getClass().getMethod("bypassedInstalledCode", type).invoke(runtime, target);
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
    }

    private static final class Driver extends RootNode {
        @Child private Force force = new Force(new Metrics(true));
        Driver() { super(null); }
        @Override public Object execute(VirtualFrame frame) { return force.execute(frame, frame.getArguments()[0]); }
        Object force(Thunk thunk) { return Calls.target(getCallTarget(), new Object[]{thunk}); }
        Object deliver(Object boundary, CallSegment child, Object payload) {
            return force.deliverAtCapturedIOHandler(boundary, child, payload, null);
        }
        Object deliver(Object boundary, CallSegment child, Object payload, Runnable afterClaim) {
            return force.deliverAtCapturedIOHandler(boundary, child, payload, afterClaim);
        }
        Object deliver(CapturedAsyncRequest request) { return force.deliverAtCapturedIOHandler(request, null); }
        Object deliver(CapturedAsyncRequest request, Runnable afterClaim) {
            return force.deliverAtCapturedIOHandler(request, afterClaim);
        }
    }

    @FunctionalInterface private interface Action<T> { T run() throws Exception; }
    private static <T> T entered(Context context, Action<T> action) throws Exception {
        context.enter();
        try { return action.run(); } finally { context.leave(); }
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> module() throws Exception {
        return thc.CoreCbdFixtures.read(new File(root,
            "build/core-continuation/core/CoreContinuationAudit.cbd").toPath());
    }
    private List<String> oracle() throws Exception {
        return Files.readAllLines(new File(root, "build/core-continuation/native-output.txt").toPath());
    }
    private static Language language() { return TruffleLanguage.LanguageReference.create(Language.class).get(null); }
    private static long number(DataValue value) { return value.getLayout().readLong(value, 0); }
    private static BytecodeCheckpoint armedCheckpoint() {
        var checkpoint = new BytecodeCheckpoint(); checkpoint.setArmed(true); return checkpoint;
    }
    private static CallSegment segment(Thunk parent) {
        return ((CallSegmentSuspended) ((ContinuationResult) parent.getValue()).getResult()).getSegment();
    }

    @Test void explicitCompilationRestoresEntryBeforeAnOriginalCatchCanYield() throws Exception {
        var module = module();
        try (var context = executionContext()) {
            context.initialize("thc");
            entered(context, () -> {
                var language = language();
                var checkpoint = new BytecodeCheckpoint();
                var program = new BytecodeProgram(language, CoreModules.reachable(module, "main:CoreContinuationAudit.catchActionAnswer", true), checkpoint);
                var parent = (Thunk) program.entryValue("main:CoreContinuationAudit.catchActionAnswer");
                var target = parent.getTarget();
                var warm = (DataValue) Calls.target(target, new Object[]{0L});
                assertEquals(42L, number(warm));
                compile(target);
                var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                var valid = type.getMethod("isValidLastTier");
                var calls = type.getMethod("getCallCount");
                var jvmci = Class.forName("jdk.vm.ci.runtime.JVMCI").getMethod("getRuntime").invoke(null);
                var backend = Class.forName("jdk.vm.ci.runtime.JVMCIRuntime").getMethod("getHostJVMCIBackend").invoke(jvmci);
                var metaAccess = Class.forName("jdk.vm.ci.runtime.JVMCIBackend").getMethod("getMetaAccess").invoke(backend);
                var method = type.getDeclaredMethod("callBoundary", Object[].class);
                var boundary = Class.forName("jdk.vm.ci.meta.MetaAccessProvider")
                    .getMethod("lookupJavaMethod", java.lang.reflect.Executable.class).invoke(metaAccess, method);
                var hasCode = Class.forName("jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod").getMethod("hasCompiledCode");
                var runtime = Truffle.getRuntime();
                try {
                    Class.forName("jdk.vm.ci.meta.ResolvedJavaMethod").getMethod("reprofile").invoke(boundary);
                    assertEquals(false, hasCode.invoke(boundary));
                    assertEquals(true, valid.invoke(target), "Retiring the host stub must leave the guest code valid");
                    var callsBefore = calls.invoke(target);
                    int effectsBefore = checkpoint.getVisits().get();
                    long compiledBefore = (Long) program.diagnostics().get("compiledEntries");
                    compile(target);
                    assertEquals(true, hasCode.invoke(boundary), "Compilation setup must restore the retired entry stub");
                    assertEquals(callsBefore, calls.invoke(target), "Setup must not execute a settling guest call");
                    assertEquals(effectsBefore, checkpoint.getVisits().get());
                    assertEquals(compiledBefore, program.diagnostics().get("compiledEntries"));
                    checkpoint.setArmed(true);
                    var driver = new Driver();
                    assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                    assertEquals(1, checkpoint.getVisits().get());
                    assertTrue(checkpoint.getCompiledVisits().get() > 0, "The first suspending call must enter installed Core");
                    assertTrue((Long) program.diagnostics().get("compiledEntries") > compiledBefore);
                    assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                    var answer = (DataValue) driver.force(parent);
                    assertEquals(42L, number(answer));
                    assertEquals(2, checkpoint.getVisits().get(), "Neither original checkpoint may replay");
                    assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                } finally { runtime.getClass().getMethod("bypassedInstalledCode", type).invoke(runtime, target); }
                return null;
            });
        }
    }

    @Test void compiledCoreRootPollParksTheClaimedRequestAndResumesItsFrame() throws Exception {
        var module = module();
        try (var context = executionContext()) {
            context.initialize("thc");
            entered(context, () -> {
                var language = language();
                var program = new BytecodeProgram(language, CoreModules.reachable(module, "main:CoreContinuationAudit.applicationAnswer"), true);
                var thunk = (Thunk) program.entryValue("main:CoreContinuationAudit.applicationAnswer");
                var target = thunk.getTarget();
                var warm = (DataValue) Calls.target(target, new Object[]{0L});
                assertEquals(208L, number(warm)); compile(target);
                var threads = Language.currentState().getThreads();
                var id = threads.enterCurrent();
                try {
                    var request = threads.send(id, "compiled poll");
                    var driver = new Driver();
                    assertSame(thunk, assertThrows(ThunkSuspended.class, () -> driver.force(thunk)).getThunk());
                    assertEquals(AsyncRequestState.CLAIMED, request.getState());
                    assertTrue(request.compiledCapture, "Installed bytecode must notice the request before its cold mailbox call");
                    assertSame(request, AsyncContinuations.request((ContinuationResult) thunk.getValue()));
                    request.acknowledge();
                    var answer = (DataValue) driver.force(thunk);
                    assertEquals(208L, number(answer));
                    assertEquals(AsyncRequestState.ACKNOWLEDGED, request.getState());
                    assertEquals(2, thunk.getState());
                } finally { threads.leaveCurrent(); }
                return null;
            });
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> linkedWithPayload(Map<String, Object> module, String entry) {
        var action = CoreModules.reachable(module, "main:CoreContinuationAudit." + entry);
        var payload = CoreModules.reachable(module, "main:CoreContinuationAudit.asyncPayload");
        var bindings = new ArrayList<Map<String, Object>>();
        var ids = new LinkedHashSet<Object>();
        for (var binding : (List<Map<String, Object>>) action.get("bindings"))
            if (ids.add(binding.get("id"))) bindings.add(binding);
        for (var binding : (List<Map<String, Object>>) payload.get("bindings"))
            if (ids.add(binding.get("id"))) bindings.add(binding);
        var result = new LinkedHashMap<>(action); result.put("bindings", bindings); return result;
    }
    private RootCallTarget callSegmentCaller(Language language, CallSegment segment) {
        var suspended = new CallSegmentSuspended(segment);
        return BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
            b.beginRoot(); b.beginReturn(); b.beginResumeApplication();
            b.emitLoadConstant(suspended);
            b.beginYield(); b.emitLoadConstant(suspended); b.endYield();
            b.endResumeApplication(); b.endReturn(); b.endRoot();
        }).getNode(0).getCallTarget();
    }

    @Test void nativeNonTailApplicationResumesCalleeThenCaller() throws Exception {
        assertEquals("208", oracle().get(1));
        var module = module();
        try (var context = executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = language();
                var linked = CoreModules.reachable(module, "main:CoreContinuationAudit.applicationAnswer");
                var ast = new Program(language, linked);
                var astThunk = (Thunk) ast.entryValue("main:CoreContinuationAudit.applicationAnswer");
                var astTarget = astThunk.getTarget();
                var astAnswer = (DataValue) Calls.target(ast.hostEntryTarget(0), new Object[]{astThunk});
                assertEquals(208L, number(astAnswer)); compile(astTarget);
                var compiledAst = (DataValue) Calls.target(astTarget, new Object[]{0L});
                assertEquals(208L, number(compiledAst));
                var checkpoint = new BytecodeCheckpoint();
                var program = new BytecodeProgram(language, linked, checkpoint);
                var parent = (Thunk) program.entryValue("main:CoreContinuationAudit.applicationAnswer");
                var target = parent.getTarget();
                var callee = program.entryTarget("main:CoreContinuationAudit.delayed");
                assertTrue(Calls.target(callee, new Object[]{0L, 7L}) instanceof DataValue); compile(callee);
                assertTrue(Calls.target(target, new Object[]{0L}) instanceof DataValue); compile(target);
                long compiledBefore = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                checkpoint.setArmed(true);
                var driver = new Driver();
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                var caller = (ContinuationResult) parent.getValue();
                var suspendedCall = ((CallSegmentSuspended) caller.getResult()).getSegment();
                assertNotSame(parent, suspendedCall); assertEquals(5, suspendedCall.getState());
                var calleeSegment = (ContinuationResult) suspendedCall.getValue();
                assertTrue(((BytecodeRoot) calleeSegment.getContinuationRootNode().getSourceRootNode()).isSelf(callee));
                assertEquals(1, checkpoint.getVisits().get());
                assertTrue(checkpoint.getCompiledVisits().get() > 0, "The callee checkpoint ran in installed code");
                assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > compiledBefore,
                    "The Core caller entered its explicitly compiled target");
                var result = (DataValue) driver.force(parent);
                assertEquals(208L, number(result));
                assertEquals(1, checkpoint.getVisits().get(), "The callee must not replay its checkpoint");
                assertEquals(2, suspendedCall.getState()); assertEquals(2, parent.getState());
            } finally { context.leave(); }
        }
    }

    @SuppressWarnings("unchecked") private Map<String, Object> binding(Map<String, Object> module, String suffix) {
        Map<String, Object> found = null;
        for (var binding : (List<Map<String, Object>>) module.get("bindings")) {
            if (((String) binding.get("id")).endsWith(suffix)) {
                if (found != null) throw new IllegalArgumentException("Collection contains more than one matching element.");
                found = binding;
            }
        }
        if (found == null) throw new java.util.NoSuchElementException("Collection contains no element matching the predicate.");
        return found;
    }
    private boolean hasOverapplication(Object value, String suffix) {
        if (!(value instanceof List<?> list)) return false;
        if (!list.isEmpty() && "app".equals(list.get(0)) && list.size() > 2
            && list.get(1) instanceof List<?> function && function.size() > 1
            && function.get(1) instanceof String name && name.endsWith(suffix)
            && list.get(2) instanceof List<?> arguments && arguments.size() == 2) return true;
        for (var child : list) if (hasOverapplication(child, suffix)) return true;
        return false;
    }

    @Test void originalOverapplicationShapesUseOrdinaryBranchProfiles() throws Exception {
        var module = module();
        try (var context = executionContext()) {
            context.initialize("thc");
            entered(context, () -> {
                for (String entry : List.of("overapplicationAnswer", "tupleOverapplicationAnswer")) {
                    var program = new BytecodeProgram(language(), CoreModules.reachable(module, "main:CoreContinuationAudit." + entry, true), true);
                    var root = (BytecodeRoot) program.entryTarget("main:CoreContinuationAudit." + entry).getRootNode();
                    int shapes = 0;
                    boolean pending = false, zeroArity = false;
                    for (var instruction : root.getBytecodeNode().getInstructions()) {
                        if (pending) {
                            assertTrue(instruction.getName().startsWith("branch.false"), entry + " shape dispatch");
                            boolean profiled = false;
                            for (var argument : instruction.getArguments())
                                profiled |= argument.getKind() == com.oracle.truffle.api.bytecode.Instruction.Argument.Kind.BRANCH_PROFILE;
                            assertTrue(profiled, entry + " application shapes use stock branch profiles");
                            shapes++;
                        }
                        pending = instruction.getName().startsWith("c.SavedCallArity");
                        if (pending) for (var argument : instruction.getArguments())
                            if (argument.getKind() == com.oracle.truffle.api.bytecode.Instruction.Argument.Kind.CONSTANT)
                                zeroArity |= Integer.valueOf(0).equals(argument.asConstant());
                    }
                    assertFalse(pending);
                    assertTrue(shapes >= 3, entry + " has the overapplication gate, zero-arity loop and exact stage");
                    assertTrue(zeroArity, entry + " zero-arity closure loop is included");
                }
                return null;
            });
        }
    }

    @Test void originalOverapplicationKeepsItsSavedSuffixAfterTheFirstCalleeYields() throws Exception {
        assertEquals("209", oracle().get(14));
        var module = module();
        var binding = binding(module, ".overapplicationAnswer");
        assertTrue(hasOverapplication(binding.get("expr"), ".stagedFunction"),
            "GHC must retain a two-argument call to the one-arity prefix");
        try (var context = executionContext()) {
            context.initialize("thc");
            entered(context, () -> {
                var language = language();
                var linked = CoreModules.reachable(module, "main:CoreContinuationAudit.overapplicationThunk", true);
                var driver = new Driver();
                for (var ordinary : List.of(new Program(language, linked), new BytecodeProgram(language, linked))) {
                    var answer = (DataValue) driver.force((Thunk) ordinary.entryValue("main:CoreContinuationAudit.overapplicationThunk"));
                    assertEquals(209L, number(answer));
                }
                var checkpoint = new BytecodeCheckpoint();
                var program = new BytecodeProgram(language, linked, checkpoint);
                var parent = (Thunk) program.entryValue("main:CoreContinuationAudit.overapplicationThunk");
                var stage = program.entryTarget("main:CoreContinuationAudit.stagedFunction");
                assertEquals(1, ((Closure) program.entryValue("main:CoreContinuationAudit.stagedFunction")).arity);
                assertTrue(Calls.target(parent.getTarget(), new Object[]{0L}) instanceof DataValue);
                compile(parent.getTarget()); checkpoint.setArmed(true);
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                var caller = (ContinuationResult) parent.getValue();
                var segment = ((CallSegmentSuspended) caller.getResult()).getSegment();
                var current = segment;
                var savedRoots = new ArrayList<BytecodeRoot>();
                while (true) {
                    if (!(current.getValue() instanceof ContinuationResult continuation)) break;
                    savedRoots.add((BytecodeRoot) continuation.getContinuationRootNode().getSourceRootNode());
                    if (!(continuation.getResult() instanceof CallSegmentSuspended suspended)) break;
                    current = suspended.getSegment();
                }
                boolean retained = false;
                for (var savedRoot : savedRoots) if (savedRoot.isSelf(stage)) { retained = true; break; }
                assertTrue(retained, "The saved suffix must retain the exact one-arity callee: " + savedRoots);
                assertEquals(1, checkpoint.getVisits().get());
                var answer = (DataValue) driver.force(parent);
                assertEquals(209L, number(answer));
                assertEquals(1, checkpoint.getVisits().get(), "The first stage must not be called again on suffix resume");
                assertEquals(2, segment.getState()); return null;
            });
        }
    }

    @Test void exactTailCallerForwardsTheFinalOverapplicationContinuation() throws Exception {
        assertEquals("208", oracle().get(15)); var module = module();
        try (var context = executionContext()) {
            context.initialize("thc");
            entered(context, () -> {
                var language = language(); var linked = CoreModules.reachable(module, "main:CoreContinuationAudit.overapplicationTail", true);
                var driver = new Driver(); var checkpoint = new BytecodeCheckpoint();
                var program = new BytecodeProgram(language, linked, checkpoint);
                var parent = (Thunk) program.entryValue("main:CoreContinuationAudit.overapplicationTail"); var target = parent.getTarget();
                assertTrue(Calls.target(target, new Object[]{0L}) instanceof DataValue); compile(target);
                checkpoint.setArmed(true);
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                assertEquals(1, checkpoint.getVisits().get());
                var answer = (DataValue) driver.force(parent); assertEquals(208L, number(answer));
                assertEquals(1, checkpoint.getVisits().get()); return null;
            });
        }
    }

    @Test void directlyOverappliedTailRootKeepsItsSuffixAfterYield() throws Exception {
        assertEquals("8", oracle().get(16)); var module = module();
        var direct = binding(module, ".directOverapplicationTail");
        assertTrue(((List<?>) direct.get("expr")).toString().contains("stagedFunction"));
        try (var context = executionContext()) {
            context.initialize("thc");
            entered(context, () -> {
                var language = language(); var linked = CoreModules.reachable(module, "main:CoreContinuationAudit.directOverapplicationTailThunk", true);
                var checkpoint = new BytecodeCheckpoint(); var program = new BytecodeProgram(language, linked, checkpoint);
                var parent = (Thunk) program.entryValue("main:CoreContinuationAudit.directOverapplicationTailThunk"); var driver = new Driver();
                var warm = (DataValue) Calls.target(parent.getTarget(), new Object[]{0L});
                assertEquals(8L, number(warm)); compile(parent.getTarget()); checkpoint.setArmed(true);
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                var answer = (DataValue) driver.force(parent); assertEquals(8L, number(answer));
                assertEquals(1, checkpoint.getVisits().get()); return null;
            });
        }
    }

    @Test void compactAndTypedScalarCallsResumeTheirExactCalleeWithoutReplayingInputs() throws Exception {
        var oracle = oracle(); var module = module();
        record Case(int line, String entry, String callee, long expected) {}
        for (var row : List.of(new Case(12, "compactScalarAnswer", "compactScalarDelayed", 208L),
                              new Case(13, "typedScalarAnswer", "typedScalarDelayed", 209L))) {
            var entry = row.entry(); long expected = row.expected();
            assertEquals(Long.toString(expected), oracle.get(row.line()));
            try (var context = executionContext()) {
                context.initialize("thc");
                entered(context, () -> {
                    var language = language(); var linked = CoreModules.reachable(module, "main:CoreContinuationAudit." + entry, true); var driver = new Driver();
                    for (var ordinary : List.of(new Program(language, linked), new BytecodeProgram(language, linked))) {
                        var answer = (DataValue) driver.force((Thunk) ordinary.entryValue("main:CoreContinuationAudit." + entry));
                        assertEquals(expected, number(answer), entry + " ordinary");
                    }
                    var checkpoint = new BytecodeCheckpoint(); var program = new BytecodeProgram(language, linked, checkpoint);
                    var parent = (Thunk) program.entryValue("main:CoreContinuationAudit." + entry); var target = parent.getTarget();
                    var warm = (DataValue) Calls.target(target, new Object[]{0L}); assertEquals(expected, number(warm));
                    compile(target);
                    var compiled = (DataValue) Calls.target(target, new Object[]{0L}); assertEquals(expected, number(compiled));
                    checkpoint.setArmed(true);
                    assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                    var suspended = (CallSegmentSuspended) ((ContinuationResult) parent.getValue()).getResult();
                    var segment = suspended.getSegment(); var calleeTarget = program.entryTarget("main:CoreContinuationAudit." + row.callee());
                    assertTrue(((BytecodeRoot) ((ContinuationResult) segment.getValue()).getContinuationRootNode().getSourceRootNode())
                        .isSelf(calleeTarget), entry + " must capture its actual typed/compact callee");
                    assertEquals(1, checkpoint.getVisits().get(), entry + " input/callee prefix runs once");
                    var result = (DataValue) driver.force(parent); assertEquals(expected, number(result));
                    assertEquals(1, checkpoint.getVisits().get(), entry + " must not replay its callee");
                    assertEquals(2, segment.getState()); assertEquals(2, parent.getState()); return null;
                });
            }
        }
    }

    private record Checked(BytecodeProgram program, Thunk thunk) {}
    private Checked checked(Language language, Map<String, Object> module, String entry) {
        var program = new BytecodeProgram(language, CoreModules.reachable(module, "main:CoreContinuationAudit." + entry), armedCheckpoint());
        return new Checked(program, (Thunk) program.entryValue("main:CoreContinuationAudit." + entry));
    }
    @Test void nestedExactTailRootForwardsYieldAndPreservesMaskedCarrier() throws Exception {
        var module = module();
        try (var context = executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = language(); var driver = new Driver();
                var nested = checked(language, module, "nestedApplication").thunk();
                assertSame(nested, assertThrows(ThunkSuspended.class, () -> driver.force(nested)).getThunk());
                var nestedAnswer = (DataValue) driver.force(nested); assertEquals(208L, number(nestedAnswer));
                assertEquals(2, nested.getState(), "The exact nested tail root completes without replay");
                var masked = checked(language, module, "applicationAnswer").thunk();
                var maskNode = masked.getTarget().getRootNode();
                SynchronousMasking.set(maskNode, MaskingState.MASKED_INTERRUPTIBLE);
                try {
                    assertSame(masked, assertThrows(ThunkSuspended.class, () -> driver.force(masked)).getThunk());
                    assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(driver));
                    var answer = (DataValue) driver.force(masked); assertEquals(208L, number(answer));
                    assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(driver));
                } finally { SynchronousMasking.set(maskNode, MaskingState.UNMASKED); }
                var malformed = checked(language, module, "applicationAnswer").thunk();
                assertThrows(ThunkSuspended.class, () -> driver.force(malformed));
                var saved = (ContinuationResult) malformed.getValue();
                assertThrows(IllegalStateException.class, () -> saved.continueWith(Unit.INSTANCE));
            } finally { context.leave(); }
        }
    }

    @Test void suspendedApplicationReturnsLazyThunkWithoutEnteringIt() throws Exception {
        try (var context = executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = language(); var checkpoint = armedCheckpoint(); var lazyEffects = new AtomicInteger();
                var lazy = new Thunk(new RootNode(null) {
                    @Override public Object execute(VirtualFrame frame) { lazyEffects.incrementAndGet(); return 7L; }
                }.getCallTarget(), null);
                var callee = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                    b.beginRoot(); var visited = b.createLocal("visited", "primitive"); b.beginBlock();
                    b.beginStoreLocal(visited); b.emitCheckpointArmed(checkpoint); b.endStoreLocal();
                    b.beginYield(); b.emitLoadConstant(Unit.INSTANCE); b.endYield();
                    b.beginYield(); b.emitLoadConstant(Unit.INSTANCE); b.endYield();
                    b.beginReturn(); b.emitLoadConstant(lazy); b.endReturn(); b.endBlock(); b.endRoot();
                }).getNode(0).getCallTarget();
                var continuation = (ContinuationResult) Calls.target(callee, new Object[]{0L});
                var function = new Closure(null, Closure.NO_PAP_ARGUMENTS, 0, callee);
                var call = assertThrows(CapturedCallSuspension.class, () ->
                    BytecodeRoot.CaptureApplicationResult.capture(0, function, continuation,
                        MaskingState.UNMASKED, new Driver())).getSegment();
                assertEquals(5, call.getState()); assertEquals(1, checkpoint.getVisits().get());
                assertEquals(0, lazy.getState(), "The returned lazy thunk must not be forced"); assertEquals(0, lazyEffects.get());
                var suspended = new CallSegmentSuspended(call);
                var layout = new DataLayout(language, "proof.LazyBox", "LazyBox", new String[]{"LiftedRep"});
                var caller = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                    b.beginRoot(); b.beginReturn(); b.beginConstruct(layout); b.beginResumeApplication();
                    b.emitLoadConstant(suspended); b.beginYield(); b.emitLoadConstant(suspended); b.endYield();
                    b.endResumeApplication(); b.endConstruct(); b.endReturn(); b.endRoot();
                }).getNode(0).getCallTarget();
                var parent = new Thunk(caller, null); var driver = new Driver();
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                assertEquals(5, call.getState(), "A second yield retains the same call segment");
                var box = (DataValue) driver.force(parent);
                assertSame(lazy, box.getLayout().read(box, 0), "The call returns the original lazy value");
                assertEquals(2, call.getState()); assertEquals(2, parent.getState());
                assertEquals(1, checkpoint.getVisits().get(), "The call must not replay its pre-yield work");
                assertEquals(0, lazyEffects.get(), "Constructing the caller result must not enter the thunk");
                assertEquals(7L, driver.force(lazy)); assertEquals(1, lazyEffects.get(), "A later demand enters the value exactly once");
                assertEquals(7L, driver.force(lazy)); assertEquals(1, lazyEffects.get());
            } finally { context.leave(); }
        }
    }

    private record SegmentParent(CallSegment segment, Thunk parent) {}
    @Test void twoWaitersResumeOneCallSegmentWithoutReplayingItsPrefix() throws Exception {
        try (var context = executionContext()) {
            context.initialize("thc"); var language = entered(context, CoreContinuationNativeTest::language);
            var effects = new AtomicInteger(); var gate = new ThunkYieldProofRoot.Gate(); gate.armed = true;
            var marker = new Object();
            var pair = entered(context, () -> {
                var callee = ThunkYieldProofRoot.target(language, effects, new AtomicInteger(), gate, marker);
                var continuation = (ContinuationResult) Calls.target(callee, new Object[]{0L});
                var segment = new CallSegment(continuation);
                return new SegmentParent(segment, new Thunk(callSegmentCaller(language, segment), null));
            });
            var segment = pair.segment(); var parent = pair.parent(); var driver = entered(context, Driver::new);
            entered(context, () -> { assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk()); return null; });
            try (var pool = Executors.newFixedThreadPool(2)) {
                Callable<Object> reader = () -> entered(context, () -> {
                    for (int i = 0; i < 4; i++) {
                        try { return driver.force(parent); }
                        catch (ThunkSuspended yielded) { assertSame(parent, yielded.getThunk()); }
                    }
                    return fail("The same segment did not finish after its two yields");
                });
                var first = pool.submit(reader);
                assertTrue(gate.entered.await(5, TimeUnit.SECONDS), "The first owner must be inside the segment");
                var second = pool.submit(reader); gate.release.countDown();
                var firstAnswer = first.get(5, TimeUnit.SECONDS);
                assertSame(firstAnswer, second.get(5, TimeUnit.SECONDS));
                assertSame(marker, ((ThunkYieldProofRoot.Answer) firstAnswer).marker());
            }
            assertEquals(1, effects.get(), "Neither waiter may re-enter the callee prefix");
            assertEquals(2, segment.getState()); assertEquals(2, parent.getState());
        }
    }

    @Test void callSegmentMemoizesGuestFailureButHostUnwindFailsClosed() throws Exception {
        try (var context = executionContext()) {
            context.initialize("thc"); var language = entered(context, CoreContinuationNativeTest::language);
            var driver = entered(context, Driver::new); var payload = new Object(); var effects = new AtomicInteger();
            var pair = entered(context, () -> {
                var failure = new GuestException(payload, driver);
                var callee = ThunkYieldProofRoot.target(language, effects, new AtomicInteger(), new ThunkYieldProofRoot.Gate(), failure);
                var segment = new CallSegment((ContinuationResult) Calls.target(callee, new Object[]{0L}));
                return new SegmentParent(segment, new Thunk(callSegmentCaller(language, segment), null));
            });
            var segment = pair.segment(); var parent = pair.parent();
            entered(context, () -> {
                for (int i = 0; i < 2; i++) assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                assertSame(payload, assertThrows(GuestException.class, () -> driver.force(parent)).getPayload());
                assertSame(payload, assertThrows(GuestException.class, () -> driver.force(parent)).getPayload()); return null;
            });
            assertEquals(3, segment.getState()); assertEquals(3, parent.getState()); assertEquals(1, effects.get());
            var blocked = new ThunkYieldProofRoot.Gate(); blocked.armed = true; var hostEffects = new AtomicInteger();
            var hostPair = entered(context, () -> {
                var callee = ThunkYieldProofRoot.target(language, hostEffects, new AtomicInteger(), blocked, new Object());
                var hostSegment = new CallSegment((ContinuationResult) Calls.target(callee, new Object[]{0L}));
                return new SegmentParent(hostSegment, new Thunk(callSegmentCaller(language, hostSegment), null));
            });
            var hostSegment = hostPair.segment(); var hostParent = hostPair.parent();
            entered(context, () -> { assertSame(hostParent, assertThrows(ThunkSuspended.class, () -> driver.force(hostParent)).getThunk()); return null; });
            try (var pool = Executors.newSingleThreadExecutor()) {
                var ownerThread = new AtomicReference<Thread>(); var finished = new CountDownLatch(1);
                var owner = pool.submit(() -> entered(context, () -> {
                    ownerThread.set(Thread.currentThread());
                    try { driver.force(hostParent); return (Throwable) null; }
                    catch (Throwable failure) { return failure; }
                    finally { finished.countDown(); }
                }));
                assertTrue(blocked.entered.await(5, TimeUnit.SECONDS)); ownerThread.get().interrupt();
                assertTrue(finished.await(5, TimeUnit.SECONDS)); blocked.release.countDown();
                assertNotNull(owner.get(5, TimeUnit.SECONDS));
                assertEquals(4, hostSegment.getState(), "Unknown host unwind cannot replay a call segment");
            }
            entered(context, () -> {
                var fault = assertThrows(RuntimeFault.class, () -> driver.force(hostParent));
                assertTrue(fault.getMessage().contains("no resumable continuation")); return null;
            });
            assertEquals(1, hostEffects.get());
        }
    }

    @Test void repeatedCallYieldRetainsActiveMaskAndRejectsUnrestoredCompletion() throws Exception {
        try (var context = executionContext()) {
            context.initialize("thc"); var language = entered(context, CoreContinuationNativeTest::language);
            var pair = entered(context, () -> {
                var target = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                    b.beginRoot(); var prior = b.createLocal("prior mask", "object");
                    b.beginYield(); b.emitLoadConstant(Unit.INSTANCE); b.endYield();
                    b.beginStoreLocal(prior); b.emitEnterMask(MaskingState.MASKED_INTERRUPTIBLE); b.endStoreLocal();
                    b.beginYield(); b.emitLoadConstant(Unit.INSTANCE); b.endYield();
                    b.beginReturn(); b.emitLoadConstant(1L); b.endReturn(); b.endRoot();
                }).getNode(0).getCallTarget();
                var segment = new CallSegment((ContinuationResult) Calls.target(target, new Object[]{0L}));
                return new SegmentParent(segment, new Thunk(callSegmentCaller(language, segment), null));
            });
            var segment = pair.segment(); var parent = pair.parent(); var driver = entered(context, Driver::new);
            entered(context, () -> {
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                assertEquals(MaskingState.MASKED_INTERRUPTIBLE, segment.getLogicalMask());
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(driver));
                var unsupported = assertThrows(IllegalStateException.class, () -> driver.force(parent));
                assertTrue(unsupported.getMessage().contains("did not restore its caller mask"));
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(driver)); return null;
            });
            assertEquals(4, segment.getState()); assertEquals(5, parent.getState());
        }
    }

    private static TupleShape stateDataTuple(Language language) {
        return new TupleShape(new CoreRepresentation(CoreKind.UNKNOWN, true, true,
            List.of("BoxedRep (Just Lifted)"), List.of(
                new CoreRepresentation(CoreKind.VOID, true, true, List.of(), null, null, null, null, null),
                new CoreRepresentation(CoreKind.DATA, true, true, List.of("BoxedRep (Just Lifted)"), null, null, null, null, null)),
            null, null, null, null), language);
    }
    @Test void wrongMaskCompletionReleasesPooledTupleBeforeFailingClosed() throws Exception {
        try (var context = executionContext()) {
            context.initialize("thc"); var language = entered(context, CoreContinuationNativeTest::language);
            var marker = new Object(); var tuple = entered(context, () -> stateDataTuple(language));
            var pair = entered(context, () -> {
                var target = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                    b.beginRoot(); var field = b.createLocal("tuple reference", "object");
                    var slots = new BytecodeTupleSlots(tuple, new LocalAccessor[]{LocalAccessor.constantOf(field)});
                    var prior = b.createLocal("prior mask", "object");
                    b.beginStoreLocal(field); b.emitLoadConstant(marker); b.endStoreLocal();
                    b.beginYield(); b.emitLoadConstant(Unit.INSTANCE); b.endYield();
                    b.beginStoreLocal(prior); b.emitEnterMask(MaskingState.MASKED_INTERRUPTIBLE); b.endStoreLocal();
                    b.beginReturn(); b.emitFinishTuple(slots); b.endReturn(); b.endRoot();
                }).getNode(0).getCallTarget();
                var segment = new CallSegment((ContinuationResult) Calls.target(target, new Object[]{0L}),
                    MaskingState.UNMASKED, MaskingState.UNMASKED, tuple);
                return new SegmentParent(segment, new Thunk(callSegmentCaller(language, segment), null));
            });
            var segment = pair.segment(); var parent = pair.parent(); var driver = entered(context, Driver::new);
            entered(context, () -> {
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                var unsupported = assertThrows(IllegalStateException.class, () -> driver.force(parent));
                assertTrue(unsupported.getMessage().contains("did not restore its caller mask"));
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(driver));
                assertEquals(0, language.getHandoffState().get().getResults().getDepth(),
                    "The completed producer-thread slab is released before wrong-mask rejection");
                assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
                assertThrows(RuntimeFault.class, () -> driver.force(parent)); return null;
            });
            assertEquals(4, segment.getState());
        }
    }

    @Test void forwardedRecursiveCellStillResumesItsCapturedChild() throws Exception {
        try (var context = executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = language(); var effects = new AtomicInteger();
                var child = new Thunk(ThunkYieldProofRoot.target(language, effects, new AtomicInteger(),
                    new ThunkYieldProofRoot.Gate(), new Object()), null);
                var cell = new RecCell(); cell.setValue(child); cell.setInitialized(true); var metrics = new Metrics(true);
                var target = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                    b.beginRoot(); var local = b.createLocal("recursive binding", "object");
                    var result = b.createLocal("forced result", "object"); var suspended = b.createLocal("suspended child", "object");
                    b.beginBlock(); b.beginStoreLocal(local); b.emitLoadConstant(cell); b.endStoreLocal();
                    b.beginReturn(); b.beginBlock(); b.beginTryCatch(); b.beginStoreLocal(result);
                    b.beginForceLocal(metrics, local, true, false); b.emitLoadLocal(local); b.endForceLocal();
                    b.endStoreLocal(); b.beginBlock(); b.beginStoreLocal(suspended);
                    b.beginSuspensionOnly(); b.emitLoadException(); b.endSuspensionOnly(); b.endStoreLocal();
                    b.beginStoreLocal(result); b.beginResumeForcedLocal(local, true); b.emitLoadLocal(suspended);
                    b.beginYield(); b.emitLoadLocal(suspended); b.endYield(); b.endResumeForcedLocal();
                    b.endStoreLocal(); b.endBlock(); b.endTryCatch(); b.emitLoadLocal(result);
                    b.endBlock(); b.endReturn(); b.endBlock(); b.endRoot();
                }).getNode(0).getCallTarget();
                var caller = new Thunk(target, null); var driver = new Driver();
                assertSame(caller, assertThrows(ThunkSuspended.class, () -> driver.force(caller)).getThunk());
                assertSame(child, cell.getValue()); assertThrows(ThunkSuspended.class, () -> driver.force(child));
                var answer = driver.force(child);
                cell.updateForced(child, answer); // A second force publishes through the shared RecCell.
                assertSame(answer, cell.getValue()); assertSame(answer, driver.force(caller));
                assertEquals(2, caller.getState()); assertEquals(1, effects.get(), "The child's pre-yield effect must not replay");
            } finally { context.leave(); }
        }
    }

    @Test void nativeCoreThunkResumesThroughForcedLocal() throws Exception {
        assertEquals(List.of("108", "208", "42", "77", "43", "114", "114", "79", "2", "0", "1", "208", "208", "209", "209", "208", "8", "114", "114"), oracle());
        var module = module();
        try (var context = executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = language(); var checkpoint = new BytecodeCheckpoint();
                var program = new BytecodeProgram(language, CoreModules.reachable(module, "main:CoreContinuationAudit.sharedAnswer"), checkpoint);
                var thunk = (Thunk) program.entryValue("main:CoreContinuationAudit.sharedAnswer"); var target = thunk.getTarget();
                var child = (Thunk) program.entryValue("main:CoreContinuationAudit.checkpointValue"); var childTarget = child.getTarget();
                assertTrue(Calls.target(childTarget, new Object[]{0L}) instanceof DataValue); compile(childTarget);
                assertTrue(Calls.target(childTarget, new Object[]{0L}) instanceof DataValue);
                assertTrue(checkpoint.getCompiledVisits().get() > 0, "Ordinary checkpoint path runs installed code");
                SynchronousMasking.set(target.getRootNode(), MaskingState.MASKED_INTERRUPTIBLE);
                try { assertTrue(Calls.target(target, new Object[]{0L}) instanceof DataValue); }
                finally { SynchronousMasking.set(target.getRootNode(), MaskingState.UNMASKED); }
                compile(target); checkpoint.setArmed(true); var host = program.hostEntryTarget(0);
                long compiledBefore = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                var suspended = assertThrows(ThunkSuspended.class, () -> Calls.target(host, new Object[]{thunk}));
                assertSame(thunk, suspended.getThunk()); assertEquals(5, thunk.getState());
                var saved = (ContinuationResult) thunk.getValue();
                boolean hasLong = false;
                for (int i = 0; i < saved.getFrame().getFrameDescriptor().getNumberOfSlots(); i++)
                    if (saved.getFrame().isLong(i)) { hasLong = true; break; }
                assertTrue(hasLong, "The captured Core caller retains a primitive Long local");
                var root = (BytecodeRootNode) target.getRootNode(); boolean profilesLong = false;
                for (var local : root.getBytecodeNode().getLocals())
                    if (local.getTypeProfile() == FrameSlotKind.Long) { profilesLong = true; break; }
                assertTrue(profilesLong, "The ordinary Core root profiles an unboxed Long local");
                assertEquals(1, checkpoint.getVisits().get());
                assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > compiledBefore,
                    "The suspending Core caller entered its explicitly compiled target");
                var answer = (DataValue) Calls.target(host, new Object[]{thunk}); assertEquals(108L, number(answer));
                assertEquals(1, checkpoint.getVisits().get(), "Resume must not re-enter the checkpoint"); assertEquals(2, thunk.getState());
            } finally { context.leave(); }
        }
    }

    private record NamedAnswer(String name, long expected) {}
    @Test void lazyOriginalActionAndHandlerHeadsSuspendInsideTheirCatchScopes() throws Exception {
        checkLazyCallbacks(List.of(new NamedAnswer("catchLazyHandlerHead", 77L), new NamedAnswer("catchLazyActionHead", 42L)), false);
    }
    @Test void keepAliveBodySuspendsAndResumesWithItsSavedReference() throws Exception {
        checkLazyCallbacks(List.of(new NamedAnswer("keepAliveScalar", 43L), new NamedAnswer("keepAliveTuple", 44L)), true);
    }
    @SuppressWarnings("unchecked") private void checkLazyCallbacks(List<NamedAnswer> entries, boolean compiled) throws Exception {
        var oracle = Files.readAllLines(new File(root, "build/core-continuation/lazy-native-output.txt").toPath());
        assertEquals(List.of("42", "77", "43", "44"), oracle);
        var module = thc.CoreCbdFixtures.read(new File(root,
            "build/core-continuation/core/LazyIOCallbackAudit.cbd").toPath());
        try (var context = executionContext()) {
            context.initialize("thc"); var language = entered(context, CoreContinuationNativeTest::language); var driver = entered(context, Driver::new);
            for (var row : entries) {
                var name = row.name(); long expected = row.expected(); var linked = CoreModules.reachable(module, "main:LazyIOCallbackAudit." + name, true);
                var checkpoint = new BytecodeCheckpoint(); var program = entered(context, () -> new BytecodeProgram(language, linked, checkpoint));
                var parent = entered(context, () -> (Thunk) program.entryValue("main:LazyIOCallbackAudit." + name));
                entered(context, () -> {
                    if (compiled) {
                        var target = parent.getTarget(); var warm = (DataValue) Calls.target(target, new Object[]{0L});
                        assertEquals(expected, number(warm)); compile(target);
                    }
                    checkpoint.setArmed(true);
                    assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                    if (compiled) assertTrue(checkpoint.getCompiledVisits().get() > 0, name + " checkpoint must run in installed guest code");
                    assertEquals(1, checkpoint.getVisits().get(), name + " head checkpoint");
                    assertEquals(5, parent.getState(), name + " must retain its captured bytecode frame");
                    var answer = (DataValue) driver.force(parent); assertEquals(expected, number(answer), name);
                    assertEquals(1, checkpoint.getVisits().get(), name + " head prefix must not replay");
                    assertEquals(2, parent.getState(), name + " must publish completion");
                    assertEquals(0, language.getHandoffState().get().getResults().getDepth()); return null;
                });
            }
        }
    }

    private static List<CoreKind> componentKinds(TupleShape tuple) {
        var result = new ArrayList<CoreKind>();
        for (var component : tuple.getProof().getComponents()) result.add(component.getKind());
        return result;
    }
    @Test void genuineCatchActionResumesOwnedTupleAcrossThreads() throws Exception {
        assertEquals(List.of("108", "208", "42", "77", "43", "114", "114", "79", "2", "0", "1", "208", "208", "209", "209", "208", "8", "114", "114"), oracle());
        var module = module();
        try (var context = executionContext()) {
            context.initialize("thc"); var language = entered(context, CoreContinuationNativeTest::language); var driver = entered(context, Driver::new);
            record Case(String name, long expected, int visits) {}
            for (var row : List.of(new Case("catchActionAnswer", 42L, 2), new Case("catchActionFailure", 77L, 1))) {
                var name = row.name(); long expected = row.expected(); int visits = row.visits();
                var linked = CoreModules.reachable(module, "main:CoreContinuationAudit." + name);
                entered(context, () -> {
                    for (var program : List.of(new Program(language, linked), new BytecodeProgram(language, linked)))
                        assertEquals(expected, number((DataValue) driver.force((Thunk) program.entryValue("main:CoreContinuationAudit." + name))), name + " ordinary");
                    return null;
                });
                var checkpoint = new BytecodeCheckpoint(); var program = entered(context, () -> new BytecodeProgram(language, linked, checkpoint));
                var thunk = entered(context, () -> (Thunk) program.entryValue("main:CoreContinuationAudit." + name)); var target = thunk.getTarget();
                entered(context, () -> {
                    assertEquals(expected, number((DataValue) Calls.target(target, new Object[]{0L}))); compile(target);
                    assertEquals(expected, number((DataValue) Calls.target(target, new Object[]{0L})));
                    assertTrue(checkpoint.getCompiledVisits().get() > 0, "The ordinary catch action entered its installed bytecode root");
                    checkpoint.setArmed(true); long compiledBefore = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                    SynchronousMasking.set(driver, MaskingState.MASKED_INTERRUPTIBLE);
                    try {
                        assertSame(thunk, assertThrows(ThunkSuspended.class, () -> driver.force(thunk)).getThunk());
                        assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(driver),
                            "The initial carrier keeps its ambient mask after the action yields");
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED); }
                    assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > compiledBefore,
                        "The suspending catch action entered installed guest code");
                    assertEquals(0, language.getHandoffState().get().getResults().getDepth(), "No pooled result escapes first yield"); return null;
                });
                var continuation = (ContinuationResult) thunk.getValue();
                var segment = ((CallSegmentSuspended) continuation.getResult()).getSegment();
                var tuple = java.util.Objects.requireNonNull(segment.getTupleShape());
                assertEquals(List.of(CoreKind.VOID, CoreKind.DATA), componentKinds(tuple),
                    "GHC catch# retains its recursive State# and lifted Box tuple proof");
                assertEquals(1, tuple.getWidth(), "The erased State# has no physical carrier slot");
                var observer = entered(context, () -> new Thunk(callSegmentCaller(language, segment), null));
                entered(context, () -> { assertSame(observer, assertThrows(ThunkSuspended.class, () -> driver.force(observer)).getThunk()); return null; });
                try (var pool = Executors.newSingleThreadExecutor()) {
                    var result = pool.submit(() -> entered(context, () -> {
                        SynchronousMasking.set(driver, MaskingState.MASKED_UNINTERRUPTIBLE);
                        try {
                            for (int i = 0; i < visits - 1; i++) {
                                assertSame(observer, assertThrows(ThunkSuspended.class, () -> driver.force(observer)).getThunk());
                                assertEquals(0, language.getHandoffState().get().getResults().getDepth(), "No pooled result escapes repeated yield");
                            }
                            if (name.equals("catchActionFailure")) assertThrows(GuestException.class, () -> driver.force(observer));
                            else assertTrue(driver.force(observer) instanceof HandoffStorage);
                            assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver),
                                "The resumer keeps its ambient mask after success or guest failure");
                            assertEquals(0, language.getHandoffState().get().getResults().getDepth(), "Result slab released on resume");
                        } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED); }
                        return Unit.INSTANCE;
                    }));
                    result.get(5, TimeUnit.SECONDS);
                }
                entered(context, () -> {
                    if (name.equals("catchActionAnswer")) {
                        assertTrue(segment.getValue() instanceof HandoffStorage, "Published tuple must own its fields, not a thread-local completion token");
                        assertNotSame(TupleComplete.INSTANCE, segment.getValue());
                    }
                    SynchronousMasking.set(driver, MaskingState.MASKED_INTERRUPTIBLE);
                    try {
                        assertEquals(expected, number((DataValue) driver.force(thunk)), name + " parent on another carrier");
                        assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(driver),
                            "The catch caller restores its original carrier mask");
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED); }
                    assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                    assertEquals(0, language.getHandoffState().get().getResults().retainedReferences()); return null;
                });
                assertEquals(visits, checkpoint.getVisits().get(), "The action prefix must not replay");
                assertEquals(name.equals("catchActionFailure") ? 3 : 2, segment.getState()); assertEquals(2, thunk.getState());
            }
        }
    }

    @Test void genuineCatchHandlerResumesItsOriginalTupleAndLogicalMask() throws Exception {
        assertEquals("79", oracle().get(7)); var module = module();
        try (var context = executionContext()) {
            context.initialize("thc"); var language = entered(context, CoreContinuationNativeTest::language); var driver = entered(context, Driver::new);
            var linked = CoreModules.reachable(module, "main:CoreContinuationAudit.catchHandlerAnswer", true);
            entered(context, () -> {
                for (var program : List.of(new Program(language, linked), new BytecodeProgram(language, linked)))
                    assertEquals(79L, number((DataValue) driver.force((Thunk) program.entryValue("main:CoreContinuationAudit.catchHandlerAnswer")))); return null;
            });
            var checkpoint = new BytecodeCheckpoint(); var program = entered(context, () -> new BytecodeProgram(language, linked, checkpoint));
            entered(context, () -> {
                var target = program.entryTarget("main:CoreContinuationAudit.catchHandlerAnswer");
                assertEquals(79L, number((DataValue) Calls.target(target, new Object[]{0L}))); compile(target);
                assertEquals(79L, number((DataValue) Calls.target(target, new Object[]{0L})));
                assertTrue(checkpoint.getCompiledVisits().get() > 0); return null;
            });
            checkpoint.setArmed(true); var parent = entered(context, () -> (Thunk) program.entryValue("main:CoreContinuationAudit.catchHandlerAnswer"));
            long compiledBefore = ((Number) program.diagnostics().get("compiledEntries")).longValue();
            entered(context, () -> {
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                assertEquals(1, checkpoint.getVisits().get(), "The action prefix ran exactly once");
                assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                assertEquals(2, checkpoint.getVisits().get(), "The original handler reached its first checkpoint");
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(driver), "A parked handler restores its carrier ambient mask");
                assertEquals(0, language.getHandoffState().get().getResults().getDepth()); return null;
            });
            var continuation = (ContinuationResult) parent.getValue(); var handler = ((CallSegmentSuspended) continuation.getResult()).getSegment();
            assertFalse(handler.getCaughtIOAction(), "The handler result is not the interrupted action");
            assertEquals(List.of(CoreKind.VOID, CoreKind.DATA), componentKinds(java.util.Objects.requireNonNull(handler.getTupleShape())));
            assertTrue(((ContinuationResult) handler.getValue()).getContinuationRootNode().getSourceRootNode() instanceof BytecodeRoot,
                "The saved continuation belongs to the original GHC handler root");
            assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > compiledBefore,
                "The suspended catch caller entered installed guest bytecode");
            assertEquals(MaskingState.MASKED_INTERRUPTIBLE, handler.getLogicalMask()); assertEquals(5, handler.getState());
            try (var pool = Executors.newSingleThreadExecutor()) {
                var resumed = pool.submit(() -> entered(context, () -> {
                    SynchronousMasking.set(driver, MaskingState.MASKED_UNINTERRUPTIBLE);
                    try {
                        assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                        assertEquals(3, checkpoint.getVisits().get(), "The handler reached its second checkpoint");
                        assertEquals(5, handler.getState());
                        assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver));
                        assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                        long result = number((DataValue) driver.force(parent));
                        assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver),
                            "Completion restores the second carrier's ambient mask");
                        assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                        assertEquals(0, language.getHandoffState().get().getResults().retainedReferences()); return result;
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED); }
                }));
                assertEquals(79L, resumed.get(5, TimeUnit.SECONDS));
            }
            assertEquals(2, handler.getState()); assertEquals(2, parent.getState());
            assertEquals(3, checkpoint.getVisits().get(), "Neither the action nor the handler prefix replays");
        }
    }

    @Test void originalMaskActionsSuspendTwiceAndRestoreEachLogicalScope() throws Exception {
        assertEquals(List.of("2", "0", "1"), oracle().subList(8, 11)); var module = module();
        try (var context = executionContext()) {
            context.initialize("thc"); var language = entered(context, CoreContinuationNativeTest::language); var driver = entered(context, Driver::new);
            record Case(String entry, long expected, MaskingState active) {}
            for (var row : List.of(new Case("maskedCheckpointAnswer", 2L, MaskingState.MASKED_INTERRUPTIBLE),
                new Case("unmaskedCheckpointAnswer", 0L, MaskingState.UNMASKED),
                new Case("uninterruptibleCheckpointAnswer", 1L, MaskingState.MASKED_UNINTERRUPTIBLE))) {
                var entry = row.entry(); long expected = row.expected(); var active = row.active();
                var linked = CoreModules.reachable(module, "main:CoreContinuationAudit." + entry, true);
                entered(context, () -> {
                    for (var ordinary : List.of(new Program(language, linked), new BytecodeProgram(language, linked)))
                        assertEquals(expected, number((DataValue) driver.force((Thunk) ordinary.entryValue("main:CoreContinuationAudit." + entry))), entry); return null;
                });
                var checkpoint = new BytecodeCheckpoint(); var program = entered(context, () -> new BytecodeProgram(language, linked, checkpoint));
                entered(context, () -> {
                    var target = program.entryTarget("main:CoreContinuationAudit." + entry);
                    assertEquals(expected, number((DataValue) Calls.target(target, new Object[]{0L}))); compile(target);
                    assertEquals(expected, number((DataValue) Calls.target(target, new Object[]{0L})));
                    assertTrue(checkpoint.getCompiledVisits().get() > 0); return null;
                });
                checkpoint.setArmed(true); var parent = entered(context, () -> (Thunk) program.entryValue("main:CoreContinuationAudit." + entry));
                // Enter unmask# from a masked caller so its lexical prior is observable.
                var initialMask = entry.equals("unmaskedCheckpointAnswer") ? MaskingState.MASKED_INTERRUPTIBLE : MaskingState.UNMASKED;
                long resumedExpected = entry.equals("unmaskedCheckpointAnswer") ? 200L : expected;
                entered(context, () -> {
                    SynchronousMasking.set(driver, initialMask);
                    try {
                        assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                        assertEquals(initialMask, SynchronousMasking.current(driver), "Parking " + entry + " restores the first carrier's ambient mask");
                        assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED); }
                    return null;
                });
                var first = (ContinuationResult) parent.getValue(); var segment = ((CallSegmentSuspended) first.getResult()).getSegment();
                assertEquals(active, segment.getLogicalMask(), entry + " saves its active logical mask"); assertFalse(segment.getCaughtIOAction());
                assertEquals(List.of(CoreKind.VOID, CoreKind.DATA), componentKinds(java.util.Objects.requireNonNull(segment.getTupleShape())));
                try (var pool = Executors.newSingleThreadExecutor()) {
                    var completed = pool.submit(() -> entered(context, () -> {
                        SynchronousMasking.set(driver, MaskingState.MASKED_UNINTERRUPTIBLE);
                        try {
                            assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                            assertEquals(2, checkpoint.getVisits().get(), "The same action reaches its second checkpoint");
                            assertEquals(5, segment.getState()); assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver));
                            assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                            long answer = number((DataValue) driver.force(parent));
                            assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver),
                                "Completing " + entry + " restores the second carrier's ambient mask");
                            assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                            assertEquals(0, language.getHandoffState().get().getResults().retainedReferences()); return answer;
                        } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED); }
                    }));
                    assertEquals(resumedExpected, completed.get(5, TimeUnit.SECONDS), entry);
                }
                assertEquals(2, segment.getState()); assertEquals(2, parent.getState());
                assertEquals(2, checkpoint.getVisits().get(), entry + " never replays its first checkpoint");
            }
        }
    }

    @Test void genuineGlobalApplicationIsSavedBeforeItsNonlocalForce() throws Exception {
        assertEquals("208", oracle().get(11)); var module = module();
        try (var context = executionContext()) {
            context.initialize("thc"); var language = entered(context, CoreContinuationNativeTest::language); var driver = entered(context, Driver::new);
            var linked = CoreModules.reachable(module, "main:CoreContinuationAudit.forceNonlocalAnswer", true);
            entered(context, () -> {
                for (var ordinary : List.of(new Program(language, linked), new BytecodeProgram(language, linked)))
                    assertEquals(208L, number((DataValue) driver.force((Thunk) ordinary.entryValue("main:CoreContinuationAudit.forceNonlocalAnswer")))); return null;
            });
            var checkpoint = new BytecodeCheckpoint(); var program = entered(context, () -> new BytecodeProgram(language, linked, checkpoint));
            entered(context, () -> {
                var target = program.entryTarget("main:CoreContinuationAudit.forceNonlocalAnswer");
                // Warm only the masked branch's distinct global; the unmasked application remains unevaluated.
                SynchronousMasking.set(driver, MaskingState.MASKED_INTERRUPTIBLE);
                try { assertEquals(210L, number((DataValue) Calls.target(target, new Object[]{0L}))); }
                finally { SynchronousMasking.set(driver, MaskingState.UNMASKED); }
                compile(target); var delayed = program.entryTarget("main:CoreContinuationAudit.delayedTwice");
                assertTrue(Calls.target(delayed, new Object[]{0L, 7L}) instanceof DataValue); compile(delayed); return null;
            });
            checkpoint.setArmed(true); var parent = entered(context, () -> (Thunk) program.entryValue("main:CoreContinuationAudit.forceNonlocalAnswer"));
            var global = entered(context, () -> (Thunk) program.entryValue("main:CoreContinuationAudit.delayedTwiceGlobal"));
            long compiledBefore = ((Number) program.diagnostics().get("compiledEntries")).longValue();
            entered(context, () -> {
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                assertEquals(1, checkpoint.getVisits().get(), "The parent prefix executes once"); assertTrue(checkpoint.getCompiledVisits().get() > 0);
                assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > compiledBefore,
                    "The original Core caller entered installed bytecode");
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                assertEquals(2, checkpoint.getVisits().get(), "The delayed result entered its first checkpoint");
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(driver)); return null;
            });
            var saved = (ContinuationResult) parent.getValue();
            var demandSegment = assertInstanceOf(CallSegmentSuspended.class, saved.getResult()).getSegment();
            var demandSaved = assertInstanceOf(AstContinuation.class,
                    SavedGuestContinuations.savedGuestContinuation(demandSegment.getValue()));
            var demandRoot = assertInstanceOf(FunctionRoot.class, demandSaved.getSourceRoot());
            assertEquals(FunctionRootRole.PASS_THROUGH, demandRoot.getRole());
            assertTrue(demandRoot.getCapturesContinuations()); assertNull(demandRoot.getCoreIdentity());
            var callerRoot = assertInstanceOf(BytecodeRoot.class,
                    saved.getContinuationRootNode().getSourceRootNode());
            assertTrue(entered(context, () -> ThreadInventoryCoreEvidence.targets(callerRoot.getCallTarget())
                    .stream().anyMatch(target -> target.getRootNode() == demandRoot)));
            var child = assertInstanceOf(ThunkSuspended.class, demandSaved.getYielded()).getThunk();
            assertSame(global, child, "The case forces the original GHC global application"); assertEquals(5, child.getState());
            boolean retainsChild = false;
            for (int i = 0; i < saved.getFrame().getFrameDescriptor().getNumberOfSlots(); i++)
                if (saved.getFrame().isObject(i) && saved.getFrame().getObject(i) == child) { retainsChild = true; break; }
            assertTrue(retainsChild, "The caller frame retains the exact evaluated selector result");
            try (var pool = Executors.newSingleThreadExecutor()) {
                var resumed = pool.submit(() -> entered(context, () -> {
                    SynchronousMasking.set(driver, MaskingState.MASKED_UNINTERRUPTIBLE);
                    try {
                        assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                        assertEquals(3, checkpoint.getVisits().get()); assertEquals(5, child.getState());
                        assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver));
                        long answer = number((DataValue) driver.force(parent));
                        assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver)); return answer;
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED); }
                }));
                assertEquals(208L, resumed.get(5, TimeUnit.SECONDS));
            }
            assertEquals(2, child.getState()); assertEquals(2, parent.getState());
            assertEquals(3, checkpoint.getVisits().get(), "Neither parent nor child checkpoint replays");
        }
    }

    @SuppressWarnings("unchecked") private static <E extends Throwable> void rethrow(Throwable failure) throws E { throw (E) failure; }
    private static void unchecked(Action<Void> action) {
        try { action.run(); } catch (Exception failure) { CoreContinuationNativeTest.<RuntimeException>rethrow(failure); }
    }
    @Test void capturedRequestAcknowledgesOnlyAtOriginalHandlerAndSurvivesCarrierChange() throws Exception {
        var module = module();
        try (var context = executionContext()) {
            context.initialize("thc"); var language = entered(context, CoreContinuationNativeTest::language); var state = entered(context, Language::currentState);
            var driver = entered(context, Driver::new); var checkpoint = armedCheckpoint();
            var program = entered(context, () -> new BytecodeProgram(language, linkedWithPayload(module, "catchActionAnswer"), checkpoint));
            var payload = entered(context, () -> (DataValue) driver.force((Thunk) program.entryValue("main:CoreContinuationAudit.asyncPayload")));
            var parent = entered(context, () -> (Thunk) program.entryValue("main:CoreContinuationAudit.catchActionAnswer"));
            var child = entered(context, () -> {
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk()); return segment(parent);
            });
            var cancelled = state.getCapturedAsyncRequests().submit(parent, child, payload);
            try (var pool = Executors.newSingleThreadExecutor()) {
                var senderThread = new AtomicReference<Thread>();
                var sender = pool.submit(() -> entered(context, () -> {
                    senderThread.set(Thread.currentThread());
                    try { cancelled.await(driver); return (Throwable) null; } catch (Throwable failure) { return failure; }
                }));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while ((senderThread.get() == null || senderThread.get().getState() != Thread.State.WAITING) && System.nanoTime() < deadline) Thread.sleep(1);
                assertEquals(Thread.State.WAITING, senderThread.get() == null ? null : senderThread.get().getState());
                state.getEnv().submitThreadLocal(new Thread[]{senderThread.get()}, new ThreadLocalAction(true, false) {
                    @Override protected void perform(Access access) { throw new AsyncThunkUnwind("sender"); }
                });
                assertTrue(sender.get(5, TimeUnit.SECONDS) instanceof AsyncThunkUnwind);
            }
            assertEquals(CapturedRequestState.CANCELLED, cancelled.getState()); assertFalse(cancelled.cancel());
            entered(context, () -> assertThrows(RuntimeFault.class, () -> driver.deliver(cancelled)));
            assertEquals(5, parent.getState()); assertEquals(5, child.getState());
            var request = state.getCapturedAsyncRequests().submit(parent, child, payload);
            assertThrows(IllegalStateException.class, () -> state.getCapturedAsyncRequests().submit(parent, child, payload));
            var waiting = new CountDownLatch(1); var committed = new CountDownLatch(1); var finishCut = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var sender = pool.submit(() -> entered(context, () -> { waiting.countDown(); return request.await(driver); }));
                assertTrue(waiting.await(5, TimeUnit.SECONDS));
                var receiver = pool.submit(() -> entered(context, () -> {
                    SynchronousMasking.set(driver, MaskingState.MASKED_UNINTERRUPTIBLE);
                    try {
                        var answer = (DataValue) driver.deliver(request, () -> unchecked(() -> {
                            committed.countDown(); assertTrue(finishCut.await(5, TimeUnit.SECONDS)); return null;
                        }));
                        assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver)); return number(answer);
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED); }
                }));
                assertTrue(committed.await(5, TimeUnit.SECONDS)); assertEquals(CapturedRequestState.COMMITTED, request.getState());
                assertFalse(sender.isDone(), "A committed cut is not yet a delivered exception");
                assertFalse(request.cancel(), "Cancellation cannot revoke a committed cut"); finishCut.countDown();
                assertEquals(107L, receiver.get(5, TimeUnit.SECONDS)); assertEquals(CapturedRequestState.ACKNOWLEDGED, sender.get(5, TimeUnit.SECONDS));
            }
            assertEquals(2, parent.getState()); assertEquals(5, child.getState(), "The original action remains available to another evaluator");
            assertEquals(1, checkpoint.getVisits().get(), "The action prefix was not replayed");
        }
    }

    @Test void committedRequestFailsOnHostUnwindAndContextClosureWakesPendingSender() throws Exception {
        var module = module(); var context = executionContext();
        try {
            context.initialize("thc"); var language = entered(context, CoreContinuationNativeTest::language); var state = entered(context, Language::currentState);
            var driver = entered(context, Driver::new); var checkpoint = armedCheckpoint();
            var program = entered(context, () -> new BytecodeProgram(language, linkedWithPayload(module, "catchActionAnswer"), checkpoint));
            var payload = entered(context, () -> (DataValue) driver.force((Thunk) program.entryValue("main:CoreContinuationAudit.asyncPayload")));
            var parent = entered(context, () -> (Thunk) program.entryValue("main:CoreContinuationAudit.catchActionAnswer"));
            var child = entered(context, () -> {
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk()); return segment(parent);
            });
            var request = state.getCapturedAsyncRequests().submit(parent, child, payload);
            entered(context, () -> assertThrows(IllegalStateException.class, () -> driver.deliver(request, () -> {
                assertEquals(CapturedRequestState.COMMITTED, request.getState()); throw new IllegalStateException("host unwind before handler");
            })));
            assertEquals(CapturedRequestState.FAILED, request.getState());
            assertEquals(CapturedRequestState.FAILED, entered(context, () -> request.await(driver)));
            assertEquals(4, parent.getState(), "An unacknowledged host unwind cannot replay the thunk"); assertEquals(5, child.getState());
            var second = entered(context, () -> new BytecodeProgram(language, linkedWithPayload(module, "catchActionAnswer"), checkpoint));
            var pendingParent = entered(context, () -> (Thunk) second.entryValue("main:CoreContinuationAudit.catchActionAnswer"));
            var pendingChild = entered(context, () -> {
                assertSame(pendingParent, assertThrows(ThunkSuspended.class, () -> driver.force(pendingParent)).getThunk()); return segment(pendingParent);
            });
            assertThrows(IllegalStateException.class, () -> state.getCapturedAsyncRequests().submit(pendingParent, child, payload));
            var pending = state.getCapturedAsyncRequests().submit(pendingParent, pendingChild, payload);
            try (var pool = Executors.newSingleThreadExecutor()) {
                var waiting = new CountDownLatch(1);
                var sender = pool.submit(() -> entered(context, () -> { waiting.countDown(); return pending.await(driver); }));
                assertTrue(waiting.await(5, TimeUnit.SECONDS)); state.getCapturedAsyncRequests().close();
                assertEquals(CapturedRequestState.FAILED, sender.get(5, TimeUnit.SECONDS), "Closing the logical request owner must wake a blocked sender");
            }
            context.close(true); assertEquals(CapturedRequestState.FAILED, pending.getState());
            assertThrows(IllegalStateException.class, () -> state.getCapturedAsyncRequests().submit(pendingParent, pendingChild, payload));
        } finally { context.close(true); }
    }

    @Test void staleRequestFailsAfterAnotherEvaluatorCompletesTheParent() throws Exception {
        var module = module();
        try (var context = executionContext()) {
            context.initialize("thc"); var language = entered(context, CoreContinuationNativeTest::language); var state = entered(context, Language::currentState);
            var driver = entered(context, Driver::new); var checkpoint = armedCheckpoint();
            var program = entered(context, () -> new BytecodeProgram(language, linkedWithPayload(module, "catchActionAnswer"), checkpoint));
            var payload = entered(context, () -> (DataValue) driver.force((Thunk) program.entryValue("main:CoreContinuationAudit.asyncPayload")));
            var parent = entered(context, () -> (Thunk) program.entryValue("main:CoreContinuationAudit.catchActionAnswer"));
            var child = entered(context, () -> {
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk()); return segment(parent);
            });
            var request = state.getCapturedAsyncRequests().submit(parent, child, payload);
            try (var pool = Executors.newSingleThreadExecutor()) {
                var waiting = new CountDownLatch(1);
                var sender = pool.submit(() -> entered(context, () -> { waiting.countDown(); return request.await(driver); }));
                assertTrue(waiting.await(5, TimeUnit.SECONDS)); checkpoint.setArmed(false);
                var completed = entered(context, () -> (DataValue) driver.force(parent));
                assertEquals(42L, number(completed)); assertSame(completed, parent.getValue());
                assertEquals(2, parent.getState()); assertEquals(2, child.getState());
                entered(context, () -> assertThrows(RuntimeFault.class, () -> driver.deliver(request)));
                assertEquals(CapturedRequestState.FAILED, sender.get(5, TimeUnit.SECONDS));
                assertSame(completed, parent.getValue(), "Stale delivery cannot overwrite the newer result");
                assertEquals(1, checkpoint.getVisits().get(), "Ordinary completion must not replay the prefix");
            }
        }
    }

    @Test void privateDeliveryCutsOriginalCatchAndLeavesItsActionShared() throws Exception {
        assertEquals("42", oracle().get(2)); var module = module();
        try (var context = executionContext()) {
            context.initialize("thc"); var language = entered(context, CoreContinuationNativeTest::language); var driver = entered(context, Driver::new);
            var checkpoint = new BytecodeCheckpoint();
            var program = entered(context, () -> new BytecodeProgram(language, linkedWithPayload(module, "catchActionAnswer"), checkpoint));
            var payload = entered(context, () -> (DataValue) driver.force((Thunk) program.entryValue("main:CoreContinuationAudit.asyncPayload")));
            assertEquals(7L, number(payload));
            entered(context, () -> {
                var target = program.entryTarget("main:CoreContinuationAudit.catchActionAnswer");
                assertEquals(42L, number((DataValue) Calls.target(target, new Object[]{0L}))); compile(target);
                assertEquals(42L, number((DataValue) Calls.target(target, new Object[]{0L}))); return null;
            });
            checkpoint.setArmed(true); var parent = entered(context, () -> (Thunk) program.entryValue("main:CoreContinuationAudit.catchActionAnswer"));
            long compiledBefore = ((Number) program.diagnostics().get("compiledEntries")).longValue();
            var child = entered(context, () -> {
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk()); return segment(parent);
            });
            assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > compiledBefore,
                "The captured original catch caller entered installed bytecode");
            assertTrue(child.getCaughtIOAction()); assertEquals(5, child.getState());
            try (var pool = Executors.newSingleThreadExecutor()) {
                var delivered = pool.submit(() -> entered(context, () -> {
                    SynchronousMasking.set(driver, MaskingState.MASKED_UNINTERRUPTIBLE);
                    try {
                        var answer = (DataValue) driver.deliver(parent, child, payload);
                        assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver)); return number(answer);
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED); }
                }));
                assertEquals(107L, delivered.get(5, TimeUnit.SECONDS), "The original catch# handler sees the lazy async payload");
            }
            assertEquals(2, parent.getState()); assertEquals(5, child.getState(), "Delivery must leave the shared action continuation parked");
            entered(context, () -> {
                assertEquals(107L, number((DataValue) driver.force(parent))); var observer = new Thunk(callSegmentCaller(language, child), null);
                assertSame(observer, assertThrows(ThunkSuspended.class, () -> driver.force(observer)).getThunk());
                assertSame(observer, assertThrows(ThunkSuspended.class, () -> driver.force(observer)).getThunk());
                assertTrue(driver.force(observer) instanceof HandoffStorage);
                assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                assertEquals(0, language.getHandoffState().get().getResults().retainedReferences()); return null;
            });
            assertEquals(2, child.getState()); assertEquals(2, checkpoint.getVisits().get(), "The action's two checkpoints execute once each");
        }
    }

    @Test void committedCatchCutSurvivesIndependentChildCompletionBeforeHandlerResume() throws Exception {
        var module = module();
        try (var context = executionContext()) {
            context.initialize("thc"); var language = entered(context, CoreContinuationNativeTest::language); var driver = entered(context, Driver::new);
            var checkpoint = armedCheckpoint();
            var program = entered(context, () -> new BytecodeProgram(language, linkedWithPayload(module, "catchActionAnswer"), checkpoint));
            var payload = entered(context, () -> (DataValue) driver.force((Thunk) program.entryValue("main:CoreContinuationAudit.asyncPayload")));
            var parent = entered(context, () -> (Thunk) program.entryValue("main:CoreContinuationAudit.catchActionAnswer"));
            var child = entered(context, () -> {
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk()); return segment(parent);
            });
            var observer = entered(context, () -> new Thunk(callSegmentCaller(language, child), null));
            try (var deliveryPool = Executors.newSingleThreadExecutor()) {
                var delivered = deliveryPool.submit(() -> entered(context, () -> {
                    var answer = (DataValue) driver.deliver(parent, child, payload, () -> unchecked(() -> {
                        assertEquals(1, parent.getState(), "The parent cut is committed before the observer runs");
                        try (var observerPool = Executors.newSingleThreadExecutor()) {
                            var finished = observerPool.submit(() -> entered(context, () -> {
                                for (int i = 0; i < 2; i++) assertSame(observer, assertThrows(ThunkSuspended.class, () -> driver.force(observer)).getThunk());
                                assertTrue(driver.force(observer) instanceof HandoffStorage);
                                assertEquals(0, language.getHandoffState().get().getResults().getDepth()); return Unit.INSTANCE;
                            }));
                            finished.get(5, TimeUnit.SECONDS);
                        }
                        assertEquals(2, child.getState(), "The shared child completed before handler continuation"); return null;
                    }));
                    return number(answer);
                }));
                assertEquals(107L, delivered.get(5, TimeUnit.SECONDS));
            }
            assertEquals(2, parent.getState()); assertEquals(2, child.getState());
            assertEquals(2, checkpoint.getVisits().get(), "The shared action prefix is never replayed");
        }
    }

    @Test void originalNestedTupleApplicationResumesTypedFieldsAcrossCarriers() throws Exception {
        assertEquals("114", oracle().get(5)); var module = module();
        try (var context = executionContext()) {
            context.initialize("thc"); var language = entered(context, CoreContinuationNativeTest::language); var driver = entered(context, Driver::new);
            var linked = CoreModules.reachable(module, "main:CoreContinuationAudit.tupleApplicationAnswer", true);
            entered(context, () -> {
                for (var program : List.of(new Program(language, linked), new BytecodeProgram(language, linked))) {
                    var answer = (DataValue) driver.force((Thunk) program.entryValue("main:CoreContinuationAudit.tupleApplicationAnswer")); assertEquals(114L, number(answer));
                }
                return null;
            });
            var checkpoint = new BytecodeCheckpoint(); var program = entered(context, () -> new BytecodeProgram(language, linked, checkpoint));
            entered(context, () -> {
                var target = program.entryTarget("main:CoreContinuationAudit.tupleApplicationAnswer");
                assertEquals(114L, number((DataValue) Calls.target(target, new Object[]{0L}))); compile(target);
                assertEquals(114L, number((DataValue) Calls.target(target, new Object[]{0L})));
                compile(((Closure) program.entryValue("main:CoreContinuationAudit.tupleDelayed")).target); return null;
            });
            int compiledVisitsBefore = checkpoint.getCompiledVisits().get(); checkpoint.setArmed(true);
            var parent = entered(context, () -> (Thunk) program.entryValue("main:CoreContinuationAudit.tupleApplicationAnswer"));
            entered(context, () -> {
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                assertEquals(5, parent.getState(), "The caller's prefix checkpoint is itself resumable");
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk()); return null;
            });
            var continuation = (ContinuationResult) parent.getValue(); var child = ((CallSegmentSuspended) continuation.getResult()).getSegment();
            var shape = java.util.Objects.requireNonNull(child.getTupleShape());
            var components = new ArrayList<CoreKind>(); for (var component : shape.getComponents()) components.add(component.getKind());
            assertEquals(List.of(CoreKind.VOID, CoreKind.LONG, CoreKind.UNKNOWN), components);
            var leaves = new ArrayList<CoreKind>(); for (var leaf : shape.getLeaves()) leaves.add(leaf.getKind());
            assertEquals(List.of(CoreKind.LONG, CoreKind.DATA), leaves);
            assertEquals(2, shape.getWidth(), "Both erased State# fields occupy zero physical slots");
            assertTrue(checkpoint.getCompiledVisits().get() >= compiledVisitsBefore + 2,
                "Both installed caller and exact tuple callee reached their suspension checkpoints");
            try (var pool = Executors.newSingleThreadExecutor()) {
                var resumed = pool.submit(() -> entered(context, () -> {
                    SynchronousMasking.set(driver, MaskingState.MASKED_INTERRUPTIBLE);
                    try {
                        assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                        assertEquals(5, child.getState(), "The exact child segment re-yields");
                        var answer = (DataValue) driver.force(parent);
                        assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(driver));
                        assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                        assertEquals(0, language.getHandoffState().get().getResults().retainedReferences()); return number(answer);
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED); }
                }));
                assertEquals(114L, resumed.get(5, TimeUnit.SECONDS));
            }
            assertEquals(2, parent.getState()); assertEquals(2, child.getState());
            assertEquals(3, checkpoint.getVisits().get(), "Caller prefix and two child checkpoints execute once each");
        }
    }

    @Test void originalTupleOverapplicationKeepsItsSuffixAndNestedFields() throws Exception {
        assertEquals("114", oracle().get(17)); var module = module(); var binding = binding(module, ".tupleOverapplicationAnswer");
        assertTrue(hasOverapplication(binding.get("expr"), ".tupleStage"), "GHC must retain a two-argument tuple call");
        try (var context = executionContext()) {
            context.initialize("thc"); var language = entered(context, CoreContinuationNativeTest::language); var driver = entered(context, Driver::new);
            var linked = CoreModules.reachable(module, "main:CoreContinuationAudit.tupleOverapplicationThunk", true);
            entered(context, () -> {
                for (var program : List.of(new Program(language, linked), new BytecodeProgram(language, linked))) {
                    var result = (DataValue) driver.force((Thunk) program.entryValue("main:CoreContinuationAudit.tupleOverapplicationThunk")); assertEquals(114L, number(result));
                }
                return null;
            });
            var checkpoint = new BytecodeCheckpoint(); var program = entered(context, () -> new BytecodeProgram(language, linked, checkpoint));
            var parent = entered(context, () -> (Thunk) program.entryValue("main:CoreContinuationAudit.tupleOverapplicationThunk"));
            entered(context, () -> {
                var target = parent.getTarget(); var firstStage = (Closure) program.entryValue("main:CoreContinuationAudit.tupleStage");
                assertEquals(1, firstStage.arity); assertTrue(Calls.target(firstStage.target, new Object[]{0L, 6L}) instanceof Closure); compile(firstStage.target);
                var ordinary = (DataValue) Calls.target(target, new Object[]{0L}); assertEquals(114L, number(ordinary)); compile(target); return null;
            });
            checkpoint.setArmed(true);
            entered(context, () -> {
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk()); assertEquals(1, checkpoint.getVisits().get());
                assertTrue(checkpoint.getCompiledVisits().get() > 0, "The first exact stage yielded from installed code"); return null;
            });
            try (var pool = Executors.newSingleThreadExecutor()) {
                var resumed = pool.submit(() -> entered(context, () -> {
                    SynchronousMasking.set(driver, MaskingState.MASKED_INTERRUPTIBLE);
                    try {
                        var result = (DataValue) driver.force(parent);
                        assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(driver));
                        assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                        assertEquals(0, language.getHandoffState().get().getResults().retainedReferences()); return number(result);
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED); }
                }));
                assertEquals(114L, resumed.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, checkpoint.getVisits().get(), "The first stage must not replay after the tuple suffix"); assertEquals(2, parent.getState());
        }
    }

    @Test void originalTailTupleOverapplicationForwardsItsFinalCalleeYield() throws Exception {
        assertEquals("114", oracle().get(18)); var module = module(); var tail = binding(module, ".tupleTailOverapplication");
        var body = (List<?>) ((List<?>) tail.get("expr")).get(2);
        assertEquals("app", body.get(0)); assertEquals(2, ((List<?>) body.get(2)).size(), "GHC retained a tuple tail overapplication");
        try (var context = executionContext()) {
            context.initialize("thc"); var language = entered(context, CoreContinuationNativeTest::language); var driver = entered(context, Driver::new);
            var linked = CoreModules.reachable(module, "main:CoreContinuationAudit.tupleTailOverapplicationThunk", true);
            entered(context, () -> {
                for (var ordinary : List.of(new Program(language, linked), new BytecodeProgram(language, linked))) {
                    var answer = (DataValue) driver.force((Thunk) ordinary.entryValue("main:CoreContinuationAudit.tupleTailOverapplicationThunk")); assertEquals(114L, number(answer));
                }
                return null;
            });
            var checkpoint = new BytecodeCheckpoint(); var program = entered(context, () -> new BytecodeProgram(language, linked, checkpoint));
            var parent = entered(context, () -> (Thunk) program.entryValue("main:CoreContinuationAudit.tupleTailOverapplicationThunk"));
            entered(context, () -> {
                var target = parent.getTarget(); var ordinary = (DataValue) Calls.target(target, new Object[]{0L}); assertEquals(114L, number(ordinary));
                compile(target); checkpoint.setArmed(true);
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk()); assertEquals(1, checkpoint.getVisits().get()); return null;
            });
            try (var pool = Executors.newSingleThreadExecutor()) {
                var result = pool.submit(() -> entered(context, () -> {
                    SynchronousMasking.set(driver, MaskingState.MASKED_INTERRUPTIBLE);
                    try {
                        var answer = (DataValue) driver.force(parent);
                        assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(driver));
                        assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                        assertEquals(0, language.getHandoffState().get().getResults().retainedReferences()); return number(answer);
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED); }
                }));
                assertEquals(114L, result.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, checkpoint.getVisits().get(), "The tail prefix must not replay"); assertEquals(2, parent.getState());
        }
    }

    @Test void nestedTupleResultIsReleasedBeforePostCallGuestFailure() throws Exception {
        var module = module();
        try (var context = executionContext()) {
            context.initialize("thc"); var language = entered(context, CoreContinuationNativeTest::language); var driver = entered(context, Driver::new);
            var linked = CoreModules.reachable(module, "main:CoreContinuationAudit.tupleApplicationFailure", true); var checkpoint = armedCheckpoint();
            var program = entered(context, () -> new BytecodeProgram(language, linked, checkpoint));
            var parent = entered(context, () -> (Thunk) program.entryValue("main:CoreContinuationAudit.tupleApplicationFailure"));
            var child = entered(context, () -> {
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk()); return segment(parent);
            });
            try (var pool = Executors.newSingleThreadExecutor()) {
                var failed = pool.submit(() -> entered(context, () -> {
                    SynchronousMasking.set(driver, MaskingState.MASKED_UNINTERRUPTIBLE);
                    try {
                        assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                        var failure = assertThrows(GuestException.class, () -> driver.force(parent));
                        var payload = (DataValue) driver.force((Thunk) failure.getPayload());
                        assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver));
                        assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                        assertEquals(0, language.getHandoffState().get().getResults().retainedReferences()); return number(payload);
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED); }
                }));
                assertEquals(9L, failed.get(5, TimeUnit.SECONDS));
            }
            assertEquals(3, parent.getState(), "The ordinary guest failure is memoized after tuple consumption"); assertEquals(2, child.getState());
            assertEquals(2, checkpoint.getVisits().get(), "The child's pre-failure work is never replayed");
            entered(context, () -> assertThrows(GuestException.class, () -> driver.force(parent)));
        }
    }

    @Test void originalCompactTupleArgumentKeepsLogicalArityOnResume() throws Exception {
        assertEquals("114", oracle().get(6)); var module = module();
        try (var context = executionContext()) {
            context.initialize("thc"); var language = entered(context, CoreContinuationNativeTest::language); var driver = entered(context, Driver::new);
            var linked = CoreModules.reachable(module, "main:CoreContinuationAudit.tupleCompactAnswer", true);
            entered(context, () -> {
                for (var program : List.of(new Program(language, linked), new BytecodeProgram(language, linked))) {
                    var answer = (DataValue) driver.force((Thunk) program.entryValue("main:CoreContinuationAudit.tupleCompactAnswer")); assertEquals(114L, number(answer));
                }
                return null;
            });
            var checkpoint = armedCheckpoint(); var program = entered(context, () -> new BytecodeProgram(language, linked, checkpoint));
            var parent = entered(context, () -> (Thunk) program.entryValue("main:CoreContinuationAudit.tupleCompactAnswer"));
            var child = entered(context, () -> {
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk()); return segment(parent);
            });
            assertEquals(5, child.getState());
            entered(context, () -> {
                var result = (DataValue) driver.force(parent); assertEquals(114L, number(result));
                assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                assertEquals(0, language.getHandoffState().get().getResults().retainedReferences()); return null;
            });
            assertEquals(2, parent.getState()); assertEquals(2, child.getState()); assertEquals(1, checkpoint.getVisits().get());
        }
    }

    @Test void originalTupleCalleeGuestFailurePropagatesWithoutPublishingAResult() throws Exception {
        var module = module();
        try (var context = executionContext()) {
            context.initialize("thc"); var language = entered(context, CoreContinuationNativeTest::language); var driver = entered(context, Driver::new);
            var linked = CoreModules.reachable(module, "main:CoreContinuationAudit.tupleRaiseAnswer", true); var checkpoint = armedCheckpoint();
            var program = entered(context, () -> new BytecodeProgram(language, linked, checkpoint));
            var parent = entered(context, () -> (Thunk) program.entryValue("main:CoreContinuationAudit.tupleRaiseAnswer"));
            var child = entered(context, () -> {
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk()); return segment(parent);
            });
            try (var pool = Executors.newSingleThreadExecutor()) {
                var failed = pool.submit(() -> entered(context, () -> {
                    SynchronousMasking.set(driver, MaskingState.MASKED_INTERRUPTIBLE);
                    try {
                        var failure = assertThrows(GuestException.class, () -> driver.force(parent));
                        var payload = (DataValue) driver.force((Thunk) failure.getPayload());
                        assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(driver));
                        assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                        assertEquals(0, language.getHandoffState().get().getResults().retainedReferences()); return number(payload);
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED); }
                }));
                assertEquals(9L, failed.get(5, TimeUnit.SECONDS));
            }
            assertEquals(3, child.getState(), "The child memoizes an ordinary guest failure");
            assertEquals(3, parent.getState(), "The caller propagates the same failure without a tuple update");
            assertEquals(1, checkpoint.getVisits().get(), "The child prefix is not replayed");
            entered(context, () -> assertThrows(GuestException.class, () -> driver.force(parent)));
        }
    }

    @Test void nestedOriginalCatchCutsNearestHandlerAndKeepsInnerActionResumable() throws Exception {
        assertEquals("43", oracle().get(4)); var module = module();
        try (var context = executionContext()) {
            context.initialize("thc"); var language = entered(context, CoreContinuationNativeTest::language); var driver = entered(context, Driver::new);
            entered(context, () -> {
                var linked = CoreModules.reachable(module, "main:CoreContinuationAudit.nestedCatchAction");
                for (var program : List.of(new Program(language, linked), new BytecodeProgram(language, linked))) {
                    var answer = (DataValue) driver.force((Thunk) program.entryValue("main:CoreContinuationAudit.nestedCatchAction"));
                    assertEquals(43L, number(answer), "Native and ordinary guest catch agree");
                }
                return null;
            });
            var checkpoint = armedCheckpoint();
            var program = entered(context, () -> new BytecodeProgram(language, linkedWithPayload(module, "nestedCatchAction"), checkpoint));
            var payload = entered(context, () -> (DataValue) driver.force((Thunk) program.entryValue("main:CoreContinuationAudit.asyncPayload")));
            var parent = entered(context, () -> (Thunk) program.entryValue("main:CoreContinuationAudit.nestedCatchAction"));
            record Segments(CallSegment outer, CallSegment inner) {}
            var pair = entered(context, () -> {
                SynchronousMasking.set(driver, MaskingState.MASKED_INTERRUPTIBLE);
                try {
                    assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                    assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(driver));
                    var outer = segment(parent); var inner = ((CallSegmentSuspended) ((ContinuationResult) outer.getValue()).getResult()).getSegment();
                    assertTrue(outer.getCaughtIOAction() && inner.getCaughtIOAction());
                    assertThrows(RuntimeFault.class, () -> driver.deliver(parent, inner, payload));
                    assertEquals(5, parent.getState(), "The outer handler cannot steal the inner action"); return new Segments(outer, inner);
                } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED); }
            });
            var outer = pair.outer(); var inner = pair.inner();
            try (var pool = Executors.newSingleThreadExecutor()) {
                var delivered = pool.submit(() -> entered(context, () -> {
                    SynchronousMasking.set(driver, MaskingState.MASKED_UNINTERRUPTIBLE);
                    try {
                        var answer = driver.deliver(outer, inner, payload);
                        assertEquals(MaskingState.MASKED_UNINTERRUPTIBLE, SynchronousMasking.current(driver)); return answer;
                    } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED); }
                }));
                assertTrue(delivered.get(5, TimeUnit.SECONDS) instanceof HandoffStorage);
            }
            assertEquals(2, outer.getState()); assertEquals(5, inner.getState());
            entered(context, () -> {
                SynchronousMasking.set(driver, MaskingState.MASKED_INTERRUPTIBLE);
                try {
                    var answer = (DataValue) driver.force(parent);
                    assertEquals(78L, number(answer), "The inner handler adds 70 and outer action adds 1; outer handler would add 1000");
                    assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(driver));
                } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED); }
                var observer = new Thunk(callSegmentCaller(language, inner), null);
                assertSame(observer, assertThrows(ThunkSuspended.class, () -> driver.force(observer)).getThunk());
                assertTrue(driver.force(observer) instanceof HandoffStorage);
                assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                assertEquals(0, language.getHandoffState().get().getResults().retainedReferences()); return null;
            });
            assertEquals(2, inner.getState()); assertEquals(2, parent.getState());
            assertEquals(1, checkpoint.getVisits().get(), "The inner action checkpoint executes only once");
        }
    }

    @Test void ordinaryCatchRetainsCaptureAdmissionAndRejectsUnparkedDelivery() throws Exception {
        var payload = new Object(); var delivered = new CapturedAsyncDelivery(payload);
        assertSame(delivered, assertThrows(CapturedAsyncDelivery.class, () -> BytecodeRoot.RequireGuestFailure.payload(delivered)));
        assertSame(payload, BytecodeRoot.RequireCaughtIOFailure.payload(delivered)); var module = module();
        try (var context = executionContext()) {
            context.initialize("thc");
            entered(context, () -> {
                var language = language(); var linked = CoreModules.reachable(module, "main:CoreContinuationAudit.nestedCatchAction");
                var program = new BytecodeProgram(language, linked);
                var ordinary = program.bytecodeDump();
                var privateDump = new BytecodeProgram(language, linked, new BytecodeCheckpoint()).bytecodeDump();
                assertTrue(ordinary.contains("RequireCaughtIOFailure"));
                assertTrue(privateDump.contains("RequireCaughtIOFailure"));
                var parent = (Thunk) program.entryValue("main:CoreContinuationAudit.nestedCatchAction");
                var driver = new Driver();
                assertEquals(0, parent.getState());
                var rejected = assertThrows(RuntimeFault.class, () -> driver.deliver(parent, null, payload));
                assertTrue(rejected.getMessage().contains("exact parked action"));
                assertEquals(0, parent.getState(), "Unowned private delivery must not claim the ordinary action");
                assertEquals(43L, number((DataValue) driver.force(parent)));
                assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
                return null;
            });
        }
    }

    @Test void asyncMarkerDuringTupleSegmentResumeFailsClosedWithoutReplayingOrLeaking() throws Exception {
        try (var context = executionContext()) {
            context.initialize("thc"); var language = entered(context, CoreContinuationNativeTest::language); var driver = entered(context, Driver::new);
            var effects = new AtomicInteger(); var marker = new AsyncThunkUnwind(new Object());
            var tuple = entered(context, () -> stateDataTuple(language));
            var pair = entered(context, () -> {
                var target = ThunkYieldProofRoot.target(language, effects, new AtomicInteger(), new ThunkYieldProofRoot.Gate(), marker);
                var segment = new CallSegment((ContinuationResult) Calls.target(target, new Object[]{0L}), MaskingState.UNMASKED, MaskingState.UNMASKED, tuple);
                return new SegmentParent(segment, new Thunk(callSegmentCaller(language, segment), null));
            });
            var segment = pair.segment(); var parent = pair.parent();
            entered(context, () -> {
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                assertSame(parent, assertThrows(ThunkSuspended.class, () -> driver.force(parent)).getThunk());
                SynchronousMasking.set(driver, MaskingState.MASKED_INTERRUPTIBLE);
                try {
                    assertSame(marker, assertThrows(AsyncThunkUnwind.class, () -> driver.force(parent)));
                    assertEquals(MaskingState.MASKED_INTERRUPTIBLE, SynchronousMasking.current(driver),
                        "The host carrier retains its ambient mask after unsupported async unwind");
                } finally { SynchronousMasking.set(driver, MaskingState.UNMASKED); }
                assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
                assertThrows(RuntimeFault.class, () -> driver.force(parent)); return null;
            });
            assertEquals(4, segment.getState(), "Async origin must not become a memoized guest failure");
            assertEquals(5, parent.getState(), "The parked parent retains its captured frame but cannot replay the closed child");
            assertEquals(1, effects.get(), "An unsupported unwind must never replay the prefix");
        }
    }

    private void malformed(Language language, Map<String, Object> module, Object resume) {
        var checkpoint = armedCheckpoint(); var program = new BytecodeProgram(language, CoreModules.reachable(module, "main:CoreContinuationAudit.sharedAnswer"), checkpoint);
        var parent = (Thunk) program.entryValue("main:CoreContinuationAudit.sharedAnswer");
        assertThrows(ThunkSuspended.class, () -> Calls.target(program.hostEntryTarget(0), new Object[]{parent}));
        var saved = (ContinuationResult) parent.getValue(); assertThrows(IllegalStateException.class, () -> saved.continueWith(resume));
    }
    @Test void malformedResumeStaysClosedAndExactTailCallCanSuspend() throws Exception {
        var module = module();
        try (var context = executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = language(); malformed(language, module, Unit.INSTANCE); malformed(language, module, new ChildResume(new Object(), null));
                var other = new BytecodeProgram(language, CoreModules.reachable(module, "main:CoreContinuationAudit.uncaptured"), armedCheckpoint());
                var uncaptured = (Thunk) other.entryValue("main:CoreContinuationAudit.uncaptured");
                assertSame(uncaptured, assertThrows(ThunkSuspended.class, () -> Calls.target(other.hostEntryTarget(0), new Object[]{uncaptured})).getThunk());
                var answer = (DataValue) Calls.target(other.hostEntryTarget(0), new Object[]{uncaptured});
                assertEquals(8L, number(answer)); assertEquals(2, uncaptured.getState());
            } finally { context.leave(); }
        }
    }

    @Test void ordinaryCoreCaptureCapabilityDoesNotYieldWithoutARequest() throws Exception {
        var module = module();
        try (var context = executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = language();
                var owner = Language.currentState();
                assertTrue(owner.getSingleGuestOriginAssumption().isValid());
                var linked = CoreModules.reachable(module, "main:CoreContinuationAudit.sharedAnswer"); var ast = new Program(language, linked);
                var astAnswer = (DataValue) Calls.target(ast.hostEntryTarget(0), new Object[]{ast.entryValue("main:CoreContinuationAudit.sharedAnswer")}); assertEquals(108L, number(astAnswer));
                var ordinary = new BytecodeProgram(language, linked); assertTrue(ordinary.bytecodeDump().contains("yield"), "Ordinary lowering must retain continuation capability");
                var answer = (DataValue) Calls.target(ordinary.hostEntryTarget(0), new Object[]{ordinary.entryValue("main:CoreContinuationAudit.sharedAnswer")}); assertEquals(108L, number(answer));
                var normalCall = new BytecodeProgram(language, CoreModules.reachable(module, "main:CoreContinuationAudit.applicationAnswer"));
                var normalDump = normalCall.bytecodeDump(); assertTrue(normalDump.contains("yield")); assertTrue(normalDump.contains("CaptureApplicationResult"));
                var callAnswer = (DataValue) Calls.target(normalCall.hostEntryTarget(0), new Object[]{normalCall.entryValue("main:CoreContinuationAudit.applicationAnswer")}); assertEquals(208L, number(callAnswer));
                for (var row : List.of(new NamedAnswer("catchActionAnswer", 42L), new NamedAnswer("catchActionFailure", 77L))) {
                    var name = row.name(); long expected = row.expected(); var action = new BytecodeProgram(language, CoreModules.reachable(module, "main:CoreContinuationAudit." + name));
                    var dump = action.bytecodeDump(); assertTrue(dump.contains("yield"), name + " retains continuation capability");
                    var caught = (DataValue) Calls.target(action.hostEntryTarget(0), new Object[]{action.entryValue("main:CoreContinuationAudit." + name)}); assertEquals(expected, number(caught));
                }
                assertTrue(owner.getSingleGuestOriginAssumption().isValid(), "Capture-capable ordinary execution keeps polling cold");
                assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                assertEquals(0, language.getHandoffState().get().getResults().retainedReferences());
            } finally { context.leave(); }
        }
    }

    private long result(BytecodeProgram program, String entry) {
        var target = program.entryTarget("main:CoreContinuationAudit." + entry); var answer = (DataValue) Calls.target(target, new Object[]{0L}); return number(answer);
    }
    @Test void asyncEnabledOrdinaryCoreKeepsTypedTuplesAndStagedCalls() throws Exception {
        var module = module(); var oracle = oracle();
        // Ordinary GHC roots with no async request; public-parser capture includes tuple and staged-call paths.
        record Case(String entry, int oracleLine, long expected) {}
        var cases = List.of(new Case("applicationAnswer", 1, 208L), new Case("typedScalarAnswer", 13, 209L),
            new Case("overapplicationThunk", 14, 209L), new Case("tupleApplicationAnswer", 5, 114L), new Case("tupleOverapplicationThunk", 6, 114L));
        try (var context = executionContext()) {
            context.initialize("thc");
            entered(context, () -> {
                var language = language();
                for (var row : cases) {
                    var entry = row.entry(); long expected = row.expected();
                    assertEquals(Long.toString(expected), oracle.get(row.oracleLine()), entry + " native result");
                    var linked = CoreModules.reachable(module, "main:CoreContinuationAudit." + entry, true);
                    var ordinary = new BytecodeProgram(language, linked); var async = new BytecodeProgram(language, linked, true);
                    assertEquals(expected, result(ordinary, entry), entry + " ordinary interpreted");
                    assertEquals(expected, result(async, entry), entry + " async interpreted");
                    var target = async.entryTarget("main:CoreContinuationAudit." + entry);
                    assertTrue(((BytecodeRoot) target.getRootNode()).isAsyncEnabled(), entry + " uses public parser mode"); compile(target);
                    long before = ((Number) async.diagnostics().get("compiledEntries")).longValue();
                    assertEquals(expected, result(async, entry), entry + " async compiled");
                    assertTrue(((Number) async.diagnostics().get("compiledEntries")).longValue() > before, entry + " must enter installed guest code");
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), entry);
                }
                return null;
            });
        }
    }
}
