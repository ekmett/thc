// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.List;
import java.util.function.Consumer;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

/** Cold compilation uses real conditions; only real interpreter execution learns a profile. */
class BytecodeColdBranchTest {
    private static Context context() {
        return Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw").build();
    }
    private static void compile(RootCallTarget target) throws Exception {
        var type = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
        type.getMethod("compile", boolean.class).invoke(target, true);
        valid(target, true);
        Truffle.getRuntime().getClass().getMethod("bypassedInstalledCode", type).invoke(Truffle.getRuntime(), target);
    }
    private static void valid(RootCallTarget target, boolean expected) throws Exception {
        assertEquals(expected, target.getClass().getMethod("isValidLastTier").invoke(target));
    }
    private static int[] profiles(BytecodeRoot root) throws Exception {
        var bytecode = root.getBytecodeNode();
        var field = bytecode.getClass().getDeclaredField("branchProfiles_"); field.setAccessible(true);
        return ((int[]) field.get(bytecode)).clone();
    }
    private static List<String> instructions(BytecodeRoot root) {
        return java.util.stream.StreamSupport.stream(root.getBytecodeNode().getInstructions().spliterator(), false)
                .map(instruction -> instruction.toString()).toList();
    }
    private static BytecodeRoot choice(Language language, Metrics metrics, Consumer<BytecodeRootGen.Builder> condition) {
        return BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
            b.beginRoot(); b.emitEnterRoot(metrics); b.beginIfThenElse(); condition.accept(b);
            b.beginReturn(); b.emitLoadConstant(11L); b.endReturn();
            b.beginReturn(); b.emitLoadConstant(22L); b.endReturn(); b.endIfThenElse(); b.endRoot();
        }).getNode(0);
    }
    @Test void untouchedStackGuardTakesBothRealSensesWithoutLearningOrQuickening() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (boolean first : new boolean[]{false, true}) {
                    var metrics = new Metrics(true); var root = choice(language, metrics, BytecodeRootGen.Builder::emitStackLimit);
                    var scope = AstStacks.astStackScope(root); var target = root.getCallTarget();
                    long spills = scope.getSpills();
                    compile(target); var code = instructions(root); assertArrayEquals(new int[]{0, 0}, profiles(root));
                    assertEquals(0L, metrics.getCompiledEntries());
                    try {
                        for (boolean sense : new boolean[]{first, !first}) {
                            scope.setDepth(sense ? AstStackScope.MAX_DEPTH : 0);
                            assertEquals(sense ? 11L : 22L, Calls.target(target, new Object[0]));
                            valid(target, true); assertArrayEquals(new int[]{0, 0}, profiles(root));
                            assertEquals(code, instructions(root));
                        }
                        assertEquals(spills + 1, scope.getSpills()); assertEquals(2L, metrics.getCompiledEntries());
                    } finally { scope.setDepth(0); }
                }
            } finally { context.leave(); }
        }
    }
    @Test void untouchedMatchDataKeepsBothConstructorArmsInstalled() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var yes = new DataLayout(language, "ColdYes", "ColdYes", new String[0]);
                var no = new DataLayout(language, "ColdNo", "ColdNo", new String[0]);
                var metrics = new Metrics(true);
                var root = choice(language, metrics, b -> { b.beginMatchData(yes); b.emitLoadArgument(0); b.endMatchData(); });
                var target = root.getCallTarget(); compile(target);
                assertEquals(0L, metrics.getCompiledEntries());
                assertEquals(22L, Calls.target(target, new Object[]{no.allocate()})); valid(target, true);
                assertEquals(11L, Calls.target(target, new Object[]{yes.allocate()})); valid(target, true);
                assertArrayEquals(new int[]{0, 0}, profiles(root)); assertEquals(2L, metrics.getCompiledEntries());
            } finally { context.leave(); }
        }
    }
    @Test void untouchedLiteralRetryLoopRunsItsEffectOnce() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var metrics = new Metrics(true); var effects = new int[1]; var scope = AstStacks.astStackScope(null);
                var action = new Closure(null, 0, new RootNode(language) {
                    @Override public Object execute(VirtualFrame frame) { effects[0]++; scope.setDepth(AstStackScope.MAX_DEPTH + scope.getDepth()); return Unit.INSTANCE; }
                }.getCallTarget());
                var coldTarget = PreparedDispatch.prepareApplication(language, 0, false, metrics, null, false, true, false, null);
                compile(coldTarget);
                var root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                    b.beginRoot(); b.emitEnterRoot(metrics); b.beginBlock();
                    var ignored = b.createLocal("completed effect", FrameSlotKind.Object); var done = b.createLabel();
                    b.beginWhile(); b.emitLoadConstant(true); b.beginBlock();
                    b.beginIfThen(); b.emitStackLimit(); b.emitBranch(done); b.endIfThen();
                    b.beginStaticStoreObject(ignored); b.beginApply(0, false, metrics, new boolean[0], coldTarget);
                    b.emitLoadConstant(action); b.endApply(); b.endStaticStoreObject();
                    b.endBlock(); b.endWhile(); b.emitLabel(done);
                    b.beginReturn(); b.emitLoadConstant(7L); b.endReturn(); b.endBlock(); b.endRoot();
                }).getNode(0);
                var target = root.getCallTarget(); compile(target); assertEquals(0, effects[0]);
                try {
                    assertEquals(7L, Calls.target(target, new Object[]{0L})); assertEquals(1, effects[0]);
                    valid(target, true); assertArrayEquals(new int[]{0, 0, 0, 0, 0, 0}, profiles(root));
                } finally { scope.setDepth(0); }
            } finally { context.leave(); }
        }
    }
    @Test void observedOneSidedProfileStillDeoptimizesOnTheUnseenSense() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (boolean first : new boolean[]{false, true}) {
                    var root = choice(language, new Metrics(true), BytecodeRootGen.Builder::emitStackLimit);
                    var scope = AstStacks.astStackScope(root); var target = root.getCallTarget();
                    try {
                        scope.setDepth(first ? AstStackScope.MAX_DEPTH : 0);
                        assertEquals(first ? 11L : 22L, Calls.target(target, new Object[0]));
                        assertArrayEquals(first ? new int[]{1, 0} : new int[]{0, 1}, profiles(root));
                        compile(target); assertEquals(first ? 11L : 22L, Calls.target(target, new Object[0])); valid(target, true);
                        scope.setDepth(first ? 0 : AstStackScope.MAX_DEPTH);
                        assertEquals(first ? 22L : 11L, Calls.target(target, new Object[0])); valid(target, false);
                        assertArrayEquals(new int[]{1, 1}, profiles(root), "Compiled execution must not invent observations");
                    } finally { scope.setDepth(0); }
                }
            } finally { context.leave(); }
        }
    }
    @Test void observedTwoSidedProfileKeepsItsCountsAndBothInstalledArms() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var root = choice(language, new Metrics(true), BytecodeRootGen.Builder::emitStackLimit);
                var scope = AstStacks.astStackScope(root); var target = root.getCallTarget();
                try {
                    scope.setDepth(0); assertEquals(22L, Calls.target(target, new Object[0]));
                    scope.setDepth(AstStackScope.MAX_DEPTH); assertEquals(11L, Calls.target(target, new Object[0]));
                    assertArrayEquals(new int[]{1, 1}, profiles(root)); compile(target);
                    scope.setDepth(0); assertEquals(22L, Calls.target(target, new Object[0])); valid(target, true);
                    scope.setDepth(AstStackScope.MAX_DEPTH); assertEquals(11L, Calls.target(target, new Object[0])); valid(target, true);
                    assertArrayEquals(new int[]{1, 1}, profiles(root));
                } finally { scope.setDepth(0); }
            } finally { context.leave(); }
        }
    }
}
