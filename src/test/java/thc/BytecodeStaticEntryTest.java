// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.LocalVariable;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.source.Source;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreBackendTestSupport.*;

class BytecodeStaticEntryTest {
    @FunctionalInterface private interface Action { void run(Language language) throws Exception; }
    private void withLanguage(Action action) throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); } finally { context.leave(); }
        }
    }
    private void compile(RootCallTarget target) throws Exception {
        var type = target.getClass(); type.getMethod("compile", boolean.class).invoke(target, true);
        assertEquals(true, type.getMethod("isValidLastTier").invoke(target));
        var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target);
    }
    private void valid(RootCallTarget target) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target)); }
    private LocalVariable singleLocal(BytecodeRoot root) {
        var locals = root.getBytecodeNode().getLocals(); assertEquals(1, locals.size()); return locals.getFirst();
    }
    private record Code(String name, List<String> arguments) {}
    private List<Code> code(BytecodeRoot root) {
        var result = new ArrayList<Code>();
        for (var instruction : root.getBytecodeNode().getInstructions()) {
            var args = new ArrayList<String>(); for (var arg : instruction.getArguments()) args.add(arg.toString()); result.add(new Code(instruction.getName(), args));
        }
        return result;
    }
    private BytecodeRoot cloneRoot(BytecodeRoot root) throws Exception {
        var method = root.getClass().getDeclaredMethod("cloneUninitialized"); method.setAccessible(true); return (BytecodeRoot) method.invoke(root);
    }
    @Test void allThreeStatelessOperationsMatchBoundaryArithmeticOnTheirFirstCompiledCall() throws Exception {
        withLanguage(language -> {
            for (int op = 0; op <= 2; op++) {
                int operation = op; var metrics = new Metrics(true);
                var root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                    b.beginRoot(); b.emitEnterRoot(metrics); b.beginReturn(); b.beginStaticLongArithmetic(operation);
                    b.emitLoadArgument(0); b.emitLoadArgument(1); b.endStaticLongArithmetic(); b.endReturn(); b.endRoot();
                }).getNode(0);
                var target = root.getCallTarget(); compile(target); assertEquals(0L, metrics.getCompiledEntries());
                for (long[] pair : new long[][]{{Long.MAX_VALUE, 2L}, {Long.MIN_VALUE, -1L}, {0L, 0L}}) {
                    long left = pair[0], right = pair[1]; long expected = switch (operation) { case 0 -> left + right; case 1 -> left - right; default -> left * right; };
                    long before = metrics.getCompiledEntries(); assertEquals(expected, Calls.target(target, new Object[]{left, right})); assertEquals(before + 1, metrics.getCompiledEntries()); valid(target);
                }
            }
        });
    }
    @Test void staticLocalAndOperationsEnterColdCompiledCodeThenRetainSourceReplayAndCloneMetadata() throws Exception {
        withLanguage(language -> {
            int[] sourceReads = {0}; var metrics = new Metrics(true); var certificate = FrameSlotKind.Long;
            var nodes = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                if (b.isParsingSources()) { sourceReads[0]++; b.beginSource(Source.newBuilder("thc", "f x = x + 2", "Cold.hs").build()); b.beginSourceSection(0, 11); }
                b.beginRoot(); var local = b.createLocal("x", certificate); b.emitEnterRoot(metrics);
                b.beginStaticStoreLong(local); b.emitLoadArgument(0); b.endStaticStoreLong(); b.beginReturn(); b.beginStaticLongArithmetic(0);
                b.emitStaticLoadLong(local); b.emitLoadConstant(2L); b.endStaticLongArithmetic(); b.endReturn(); b.endRoot();
                if (b.isParsingSources()) { b.endSourceSection(); b.endSource(); }
            });
            var root = nodes.getNode(0); var target = root.getCallTarget(); assertEquals(0, sourceReads[0]); assertEquals(0L, metrics.getCompiledEntries()); compile(target);
            assertEquals(FrameSlotKind.Long, singleLocal(root).getTypeProfile());
            assertEquals(42L, Calls.target(target, new Object[]{40L})); assertEquals(1L, metrics.getCompiledEntries()); valid(target); assertEquals(0, sourceReads[0]);
            var originalCode = code(root); root.getBytecodeNode().ensureSourceInformation(); assertEquals(1, sourceReads[0]); assertEquals(originalCode, code(root));
            assertSame(target, root.getCallTarget()); assertSame(certificate, singleLocal(root).getInfo()); assertEquals(Long.MIN_VALUE + 2, Calls.target(target, new Object[]{Long.MIN_VALUE}));
            var clone = cloneRoot(root); var clonedTarget = clone.getCallTarget(); assertNotSame(target, clonedTarget); assertSame(certificate, singleLocal(clone).getInfo());
            long before = metrics.getCompiledEntries(); compile(clonedTarget); assertEquals(Long.MIN_VALUE + 1, Calls.target(clonedTarget, new Object[]{Long.MAX_VALUE})); assertEquals(before + 1, metrics.getCompiledEntries()); valid(clonedTarget);
        });
    }
    @Test void realIngressChecksWrongCarriersAndExistingAdaptiveWritesStillWiden() throws Exception {
        withLanguage(language -> {
            var root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                b.beginRoot(); var local = b.createLocal("initial long", FrameSlotKind.Long);
                b.beginStaticStoreLong(local); b.emitLoadArgument(0); b.endStaticStoreLong();
                // An uncertified writer retains ordinary DSL widening.
                b.beginStoreLocal(local); b.emitLoadArgument(1); b.endStoreLocal(); b.beginReturn(); b.emitLoadLocal(local); b.endReturn(); b.endRoot();
            }).getNode(0);
            var target = root.getCallTarget(); assertThrows(RuntimeFault.class, () -> Calls.target(target, new Object[]{"wrong", 0L}));
            var marker = new Object(); assertSame(marker, Calls.target(target, new Object[]{1L, marker})); assertEquals(FrameSlotKind.Object, singleLocal(root).getTypeProfile());
            assertEquals(3L, Calls.target(target, new Object[]{2L, 3L})); compile(target); assertEquals(FrameSlotKind.Object, singleLocal(root).getTypeProfile());
            assertEquals(5L, Calls.target(target, new Object[]{4L, 5L})); assertEquals(FrameSlotKind.Object, singleLocal(root).getTypeProfile());
        });
    }
    @Test void declaredObjectScratchIsColdStatelessAndRetainsReplayAndCloneMetadata() throws Exception {
        withLanguage(language -> {
            var metrics = new Metrics(true); int[] sourceReads = {0};
            var root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                if (b.isParsingSources()) { sourceReads[0]++; b.beginSource(Source.newBuilder("thc", "scratch", "Scratch.hs").build()); b.beginSourceSection(0, 7); }
                b.beginRoot(); b.emitEnterRoot(metrics); var local = b.createLocal("scratch", FrameSlotKind.Object);
                b.beginStaticStoreObject(local); b.emitLoadArgument(0); b.endStaticStoreObject(); b.beginReturn(); b.emitStaticLoadObject(local); b.endReturn(); b.endRoot();
                if (b.isParsingSources()) { b.endSourceSection(); b.endSource(); }
            }).getNode(0);
            var target = root.getCallTarget(); compile(target); assertEquals(0, sourceReads[0]); assertEquals(0L, metrics.getCompiledEntries()); assertEquals(FrameSlotKind.Object, singleLocal(root).getTypeProfile());
            for (Object value : list(new Object(), 1L, "value", null)) {
                long before = metrics.getCompiledEntries(); assertSame(value, Calls.target(target, new Object[]{value})); assertEquals(before + 1, metrics.getCompiledEntries()); valid(target);
            }
            var originalCode = code(root); root.getBytecodeNode().ensureSourceInformation(); assertEquals(1, sourceReads[0]); assertSame(target, root.getCallTarget());
            assertEquals(originalCode, code(root)); assertSame(FrameSlotKind.Object, singleLocal(root).getInfo());
            var clone = cloneRoot(root); compile(clone.getCallTarget()); assertSame(FrameSlotKind.Object, singleLocal(clone).getInfo());
            assertEquals(FrameSlotKind.Object, singleLocal(clone).getTypeProfile()); var value = new Object();
            assertSame(value, Calls.target(clone.getCallTarget(), new Object[]{value})); valid(clone.getCallTarget());
        });
    }
    private BytecodeProgram program(Language language, Map<String, Object> rep, boolean lifted, boolean async) {
        var binder = map("id", "x", "name", "x", "rep", rep, "lifted", lifted);
        var expr = list("lam", list(binder), list("var", "x", map("rep", rep)), map("rep", map("kind", "closure", "evaluated", true, "primReps", list("BoxedRep (Just Lifted)")), "resultRep", rep));
        return new BytecodeProgram(language, map("bindings", list(map("id", "f", "name", "f", "arity", 1L, "lifted", true, "expr", expr)), "constructors", list()), async);
    }
    @Test void compilerCertifiesExactMarkersAndKeepsUnknownAndNarrowInputsOutOfWideSlots() throws Exception {
        withLanguage(language -> {
            for (Object marker : list(FrameSlotKind.Long, FrameSlotKind.Int, FrameSlotKind.Float, FrameSlotKind.Double,
                    FrameSlotKind.Boolean, FrameSlotKind.Object, FrameSlotKind.Byte, FrameSlotKind.Illegal,
                    FrameSlotKind.Static, "object", "primitive", null)) {
                var root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                    b.beginRoot(); var local = b.createLocal("declared marker", marker); b.beginStoreLocal(local); b.emitLoadArgument(0); b.endStoreLocal(); b.beginReturn(); b.emitLoadLocal(local); b.endReturn(); b.endRoot();
                }).getNode(0);
                var expected = marker == FrameSlotKind.Long || marker == FrameSlotKind.Int || marker == FrameSlotKind.Float
                        || marker == FrameSlotKind.Double || marker == FrameSlotKind.Boolean || marker == FrameSlotKind.Object
                        ? marker : FrameSlotKind.Illegal;
                compile(root.getCallTarget()); assertEquals(expected, singleLocal(root).getTypeProfile(),
                        "Only explicitly approved physical-carrier singletons may initialize a local: " + marker);
            }
            var wide = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
            for (boolean async : list(false, true)) {
                var selected = (BytecodeRoot) program(language, wide, false, async).entryTarget("f").getRootNode();
                var locals = selected.getBytecodeNode().getLocals().stream().filter(local -> Objects.equals(local.getName(), "x")).toList(); assertEquals(1, locals.size()); assertSame(FrameSlotKind.Long, locals.getFirst().getInfo());
            }
            var unknown = (BytecodeRoot) program(language, map("kind", "unknown", "evaluated", false), true, false).entryTarget("f").getRootNode();
            assertTrue(unknown.getBytecodeNode().getLocals().stream().noneMatch(local -> local.getInfo() == FrameSlotKind.Long));
            var narrow = (BytecodeRoot) program(language, map("kind", "long", "primReps", list("Int32Rep"), "evaluated", true), false, false).entryTarget("f").getRootNode();
            var narrowFormals = narrow.getBytecodeNode().getLocals().stream().filter(local -> Objects.equals(local.getName(), "x")).toList();
            assertEquals(1, narrowFormals.size()); assertSame(FrameSlotKind.Int, narrowFormals.getFirst().getInfo());
        });
    }
    @Test void unprofiledBooleanBranchRetainsItsFirstCompiledBothArmsAndReplay() throws Exception {
        withLanguage(language -> {
            var layout = new DataLayout(language, "BranchTrue", "BranchTrue", new String[0], new Class<?>[0]); var trueValue = layout.create(new Object[0]);
            var falseValue = new DataLayout(language, "BranchFalse", "BranchFalse", new String[0], new Class<?>[0]).create(new Object[0]);
            for (boolean primitiveCondition : list(false, true)) {
                var metrics = new Metrics(true); int[] sourceReads = {0};
                var root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                    if (b.isParsingSources()) { sourceReads[0]++; b.beginSource(Source.newBuilder("thc", "if p then 11 else 22", "ColdBranch.hs").build()); b.beginSourceSection(0, 20); }
                    b.beginRoot(); b.emitEnterRoot(metrics); b.beginUnprofiledIfThen();
                    // MatchData produces a primitive Boolean without adaptive scalar specialization.
                    if (primitiveCondition) b.beginMatchData(layout); b.emitLoadArgument(0); if (primitiveCondition) b.endMatchData();
                    b.beginReturn(); b.emitLoadConstant(11L); b.endReturn(); b.endUnprofiledIfThen(); b.beginReturn(); b.emitLoadConstant(22L); b.endReturn(); b.endRoot();
                    if (b.isParsingSources()) { b.endSourceSection(); b.endSource(); }
                }).getNode(0);
                var target = root.getCallTarget(); assertTrue(code(root).stream().anyMatch(instruction -> instruction.name().equals("branch.false.unprofiled"))); compile(target);
                // Compare the prepared view before the FIRST call, not a warmed-up trace.
                var beforeCode = code(root); assertEquals(0L, metrics.getCompiledEntries()); boolean[] conditions = {true, false, true};
                for (int index = 0; index < conditions.length; index++) {
                    boolean condition = conditions[index]; Object input = primitiveCondition ? (condition ? trueValue : falseValue) : condition;
                    assertEquals(condition ? 11L : 22L, Calls.target(target, new Object[]{input}));
                    assertEquals(index + 1L, metrics.getCompiledEntries(), "primitiveCondition=" + primitiveCondition + ", call=" + index + ", condition=" + condition); valid(target);
                }
                assertEquals(beforeCode, code(root), "Unprofiled branch does not require observed quickening"); root.getBytecodeNode().ensureSourceInformation();
                assertEquals(1, sourceReads[0]); assertEquals(beforeCode, code(root)); var clone = cloneRoot(root); var clonedTarget = clone.getCallTarget(); compile(clonedTarget);
                long before = metrics.getCompiledEntries(); Object input = primitiveCondition ? falseValue : false;
                assertEquals(22L, Calls.target(clonedTarget, new Object[]{input})); assertEquals(before + 1, metrics.getCompiledEntries()); valid(clonedTarget);
                if (!primitiveCondition) {
                    assertThrows(ClassCastException.class, () -> Calls.target(target, new Object[]{"not Boolean"})); assertThrows(NullPointerException.class, () -> Calls.target(target, new Object[1]));
                }
            }
        });
    }
}
