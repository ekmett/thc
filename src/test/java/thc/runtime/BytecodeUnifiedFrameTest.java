// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedTruffleRuntime;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

class BytecodeUnifiedFrameTest {
    private static Context context() {
        return Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build();
    }

    private static void compile(com.oracle.truffle.api.CallTarget callTarget) {
        var target = (OptimizedCallTarget) callTarget;
        assertTrue(target.compile(true)); assertTrue(target.isValidLastTier());
        ((OptimizedTruffleRuntime) Truffle.getRuntime()).bypassedInstalledCode(target);
    }

    private static void cleared(MaterializedFrame frame, int... slots) throws Exception {
        var field = frame.getClass().getDeclaredField("indexedLocals"); field.setAccessible(true);
        var references = (Object[]) field.get(frame);
        for (int slot : slots) {
            assertEquals(FrameSlotKind.Illegal.tag, frame.getTag(slot), "cleared slot " + slot);
            assertNull(references[slot], "dead reference retained in slot " + slot);
        }
    }

    @Test void firstCompiledBlockExitClearsOnlyDeadLocals() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var metrics = new Metrics(true); var retained = new Object(); var dead = new Object();
                var transactionSlot = new LocalAccessor[1];
                var root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                    b.beginRoot(); b.emitEnterRoot(metrics);
                    var ref = b.createLocal("retained", FrameSlotKind.Object);
                    var number = b.createLocal("number", FrameSlotKind.Long);
                    var transaction = b.createLocal("transaction", FrameSlotKind.Object);
                    transactionSlot[0] = LocalAccessor.constantOf(transaction);
                    b.beginStaticStoreObject(transaction); b.emitCurrentTransaction(); b.endStaticStoreObject();
                    b.beginStaticStoreObject(ref); b.emitLoadConstant(retained); b.endStaticStoreObject();
                    b.beginStaticStoreLong(number); b.emitLoadConstant(Long.MIN_VALUE); b.endStaticStoreLong();
                    b.beginBlock();
                    var temporary = b.createLocal("dead", FrameSlotKind.Object);
                    var floating = b.createLocal("deadDouble", FrameSlotKind.Double);
                    b.beginStaticStoreObject(temporary); b.emitLoadConstant(dead); b.endStaticStoreObject();
                    b.beginStaticStoreDouble(floating); b.emitLoadConstant(-0.0d); b.endStaticStoreDouble();
                    b.endBlock();
                    b.beginYield(); b.beginRecordContinuationOwner(); b.emitLoadConstant(Unit.INSTANCE); b.endRecordContinuationOwner(); b.endYield();
                    b.beginReturn(); b.emitStaticLoadObject(ref); b.endReturn(); b.endRoot();
                }).getNode(0);
                root.configureAsync(true); root.configureStackDriver(metrics);
                root.configureStackTransaction(transactionSlot[0]);
                compile((OptimizedCallTarget) root.getCallTarget());
                assertEquals(0, metrics.getCompiledEntries());
                var saved = assertInstanceOf(ContinuationResult.class, Calls.target(root.getCallTarget(), new Object[]{0L}));
                assertEquals(1, metrics.getCompiledEntries());
                assertSame(Unit.INSTANCE, saved.getResult());
                assertSame(retained, saved.getFrame().getObject(1));
                assertEquals(Long.MIN_VALUE, saved.getFrame().getLong(2));
                cleared(saved.getFrame(), 0, 4, 5);
                assertSame(retained, SavedGuestContinuations.savedGuestContinuation(saved).continueWith(Unit.INSTANCE));
            } finally { context.leave(); }
        }
    }

    @Test void firstCompiledResumeCatchesOnceAndClearsExitedScopeBeforeResuspending() throws Exception {
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var metrics = new Metrics(true); var retained = new Object(); var dead = new Object();
                var transactionSlot = new LocalAccessor[1];
                var failure = new GuestException(Unit.INSTANCE, null);
                var thunk = new Thunk(new com.oracle.truffle.api.nodes.RootNode(language) {
                    @Override public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) { throw failure; }
                }.getCallTarget(), null);
                var root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                    b.beginRoot();
                    var ref = b.createLocal("retained", FrameSlotKind.Object);
                    var number = b.createLocal("number", FrameSlotKind.Long);
                    var transaction = b.createLocal("transaction", FrameSlotKind.Object);
                    transactionSlot[0] = LocalAccessor.constantOf(transaction);
                    b.beginStaticStoreObject(transaction); b.emitCurrentTransaction(); b.endStaticStoreObject();
                    b.beginStaticStoreObject(ref); b.emitLoadConstant(retained); b.endStaticStoreObject();
                    b.beginStaticStoreLong(number); b.emitLoadConstant(Long.MAX_VALUE); b.endStaticStoreLong();
                    b.beginTryCatch(); b.beginBlock();
                    var temporary = b.createLocal("dead", FrameSlotKind.Object);
                    var narrow = b.createLocal("deadInt", FrameSlotKind.Int);
                    var floating = b.createLocal("deadFloat", FrameSlotKind.Float);
                    b.beginStaticStoreObject(temporary); b.emitLoadConstant(dead); b.endStaticStoreObject();
                    b.beginStaticStoreInt(narrow); b.emitLoadConstant(Integer.MIN_VALUE); b.endStaticStoreInt();
                    b.beginStaticStoreFloat(floating); b.emitLoadConstant(-0.0f); b.endStaticStoreFloat();
                    b.beginStaticStoreObject(temporary);
                    b.beginYield(); b.beginRecordContinuationOwner(); b.emitLoadConstant(Unit.INSTANCE); b.endRecordContinuationOwner(); b.endYield();
                    b.endStaticStoreObject();
                    b.emitEnterRoot(metrics);
                    b.beginForceValue(metrics, false); b.emitLoadArgument(1); b.endForceValue();
                    b.endBlock();
                    b.beginYield(); b.beginRecordContinuationOwner(); b.emitLoadException(); b.endRecordContinuationOwner(); b.endYield();
                    b.endTryCatch();
                    b.beginReturn(); b.emitStaticLoadObject(ref); b.endReturn(); b.endRoot();
                }).getNode(0);
                root.configureAsync(true); root.configureStackDriver(metrics);
                root.configureStackTransaction(transactionSlot[0]);
                assertTrue(root.prepareForCompilation(true, 2, true));
                // Freeze metadata before saving the location; no suffix has executed yet.
                root.getBytecodeNode().ensureSourceInformation();
                var first = assertInstanceOf(ContinuationResult.class,
                        Calls.target(root.getCallTarget(), new Object[]{0L, thunk}));
                assertSame(dead, first.getFrame().getObject(4));
                assertEquals(Integer.MIN_VALUE, first.getFrame().getInt(5));
                assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits(first.getFrame().getFloat(6)));
                var savedLocation = first.getContinuationRootNode().getLocation();
                var target = (OptimizedCallTarget) first.getContinuationRootNode().getCallTarget();
                compile(target);
                assertEquals(0, metrics.getCompiledEntries()); assertEquals(0, metrics.getThunkEvaluations());
                var second = assertInstanceOf(ContinuationResult.class, SavedGuestContinuations.savedGuestContinuation(first).continueWith(Unit.INSTANCE));
                assertSame(failure, second.getResult()); assertSame(first.getFrame(), second.getFrame());
                assertEquals(1, metrics.getCompiledEntries(), "first installed resume entry");
                assertEquals(1, metrics.getThunkEvaluations(), "once-only failing thunk");
                assertSame(target, first.getContinuationRootNode().getCallTarget());
                assertSame(savedLocation, first.getContinuationRootNode().getLocation());
                assertSame(retained, second.getFrame().getObject(1));
                assertEquals(Long.MAX_VALUE, second.getFrame().getLong(2));
                cleared(second.getFrame(), 0, 4, 5, 6);
                assertSame(retained, SavedGuestContinuations.savedGuestContinuation(second).continueWith(Unit.INSTANCE));
                assertEquals(1, metrics.getThunkEvaluations());
            } finally { context.leave(); }
        }
    }
}
