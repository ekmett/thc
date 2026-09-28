// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedTruffleRuntime;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

class BytecodeStaleFrameTest {
    static Object[] carriers() {
        return new Object[]{Integer.MIN_VALUE, Long.MAX_VALUE,
                Float.intBitsToFloat(0xffc01234), Double.longBitsToDouble(0x8000000000000000L), true, new Object()};
    }

    static Object[] primitives() {
        return java.util.Arrays.copyOf(carriers(), 5);
    }

    @ParameterizedTest @MethodSource("carriers")
    void matchingSavedTagsEnterCompiledSuffix(Object value) throws Exception {
        exercise(value, false, false);
    }

    @ParameterizedTest @MethodSource("primitives")
    void stalePrimitiveTagsRepairBeforeGuestSuffix(Object value) throws Exception {
        exercise(value, true, false);
    }

    @org.junit.jupiter.api.Test void hostEditedObjectTagRetainsOriginalGeneralizingRepair() throws Exception {
        exercise(new Object(), false, true);
    }

    private static void exercise(Object value, boolean stale, boolean editObject) throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var metrics = new Metrics(true); var transactionSlot = new LocalAccessor[1];
                var prefixMarker = new Object(); var retainedMarker = new Object();
                var root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                    b.beginRoot();
                    var local = b.createLocal("value", null);
                    var result = b.createLocal("resume", FrameSlotKind.Object);
                    var marker = b.createLocal("prefix marker", FrameSlotKind.Object);
                    var transaction = b.createLocal("transaction", FrameSlotKind.Object);
                    transactionSlot[0] = LocalAccessor.constantOf(transaction);
                    // An uninitialized live local also exercises the Illegal-tag fast path.
                    b.createLocal("uninitialized", null);
                    b.beginStaticStoreObject(transaction); b.emitCurrentTransaction(); b.endStaticStoreObject();
                    b.beginStaticStoreObject(marker); b.emitLoadConstant(prefixMarker); b.endStaticStoreObject();
                    b.beginStoreLocal(local); b.emitLoadArgument(1); b.endStoreLocal();
                    b.beginStaticStoreObject(result);
                    b.beginYield(); b.emitLoadConstant(Unit.INSTANCE); b.endYield();
                    b.endStaticStoreObject();
                    b.emitEnterRoot(metrics);
                    b.beginStaticStoreObject(result);
                    b.beginYield(); b.emitLoadConstant(Unit.INSTANCE); b.endYield();
                    b.endStaticStoreObject();
                    b.beginReturn(); b.emitStaticLoadObject(marker); b.endReturn(); b.endRoot();
                }).getNode(0);
                root.configureAsync(true); root.configureStackDriver(metrics);
                root.configureStackTransaction(transactionSlot[0]);
                assertTrue(root.prepareForCompilation(true, 2, true));
                root.getBytecodeNode().ensureSourceInformation();
                var first = assertInstanceOf(ContinuationResult.class,
                        Calls.target(root.getCallTarget(), new Object[]{0L, editObject ? 17L : value}));
                var savedFrame = first.getFrame();
                assertEquals(FrameSlotKind.Illegal.tag, savedFrame.getTag(5));
                savedFrame.setObject(3, retainedMarker); // Detect any replay of the original prefix.
                if (stale) {
                    // A distinct invocation generalizes the cache, not the first captured frame.
                    // Neither saved suffix has run or been trained.
                    var newer = assertInstanceOf(ContinuationResult.class,
                            Calls.target(root.getCallTarget(), new Object[]{0L, new Object()}));
                    assertNotEquals(newer.getFrame().getTag(1), savedFrame.getTag(1));
                    assertEquals(FrameSlotKind.Object.tag, newer.getFrame().getTag(1));
                }
                if (editObject) savedFrame.setObject(1, value);
                var location = first.getContinuationRootNode().getLocation();
                var target = (OptimizedCallTarget) first.getContinuationRootNode().getCallTarget();
                assertTrue(target.compile(true)); assertTrue(target.isValidLastTier());
                ((OptimizedTruffleRuntime) Truffle.getRuntime()).bypassedInstalledCode(target);
                assertEquals(0, metrics.getCompiledEntries());
                var second = assertInstanceOf(ContinuationResult.class, first.continueWith(Unit.INSTANCE));
                assertEquals(stale || editObject ? 0 : 1, metrics.getCompiledEntries(),
                        "matching tags enter installed suffix; stale tags repair in the interpreter first");
                assertSame(savedFrame, second.getFrame());
                assertSame(target, first.getContinuationRootNode().getCallTarget());
                assertSame(location, first.getContinuationRootNode().getLocation());
                assertEquals(FrameSlotKind.Illegal.tag, savedFrame.getTag(5));
                assertSame(Unit.INSTANCE, second.getResult());
                var actualValue = savedFrame.getValue(1);
                if (value instanceof Float f) {
                    assertEquals(Float.floatToRawIntBits(f), Float.floatToRawIntBits((Float) actualValue));
                } else if (value instanceof Double d) {
                    assertEquals(Double.doubleToRawLongBits(d), Double.doubleToRawLongBits((Double) actualValue));
                } else assertEquals(value, actualValue);
                if (stale || editObject) {
                    assertEquals(FrameSlotKind.Object.tag, savedFrame.getTag(1));
                    assertEquals(value, savedFrame.getObject(1));
                }
                assertSame(retainedMarker, second.continueWith(Unit.INSTANCE), "no prefix replay");
            } finally { context.leave(); }
        }
    }
}
