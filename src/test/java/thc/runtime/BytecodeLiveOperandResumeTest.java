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
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

class BytecodeLiveOperandResumeTest {
    @Test void compiledResuspensionPreservesLiveOperandAndSavedFrame() {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var metrics = new Metrics(true);
                var transactionSlot = new LocalAccessor[1];
                var root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                    b.beginRoot();
                    var result = b.createLocal("result", FrameSlotKind.Object);
                    var transaction = b.createLocal("transaction", FrameSlotKind.Object);
                    transactionSlot[0] = LocalAccessor.constantOf(transaction);
                    b.beginStaticStoreObject(transaction); b.emitCurrentTransaction(); b.endStaticStoreObject();
                    b.beginTryCatch();
                    b.beginReturn();
                    b.beginStaticLongArithmetic(0);
                    b.emitLoadConstant(40L);
                    b.beginYield();
                    b.beginBlock();
                    b.beginStaticStoreObject(result);
                    b.beginYield(); b.emitLoadConstant(Unit.INSTANCE); b.endYield();
                    b.endStaticStoreObject();
                    b.emitEnterRoot(metrics);
                    b.emitStaticLoadObject(result);
                    b.endBlock(); b.endYield();
                    b.endStaticLongArithmetic(); b.endReturn();
                    b.beginBlock();
                    b.beginStaticStoreObject(result);
                    b.beginCallSuspensionOnly(); b.emitLoadException(); b.endCallSuspensionOnly();
                    b.endStaticStoreObject();
                    b.beginReturn(); b.emitStaticLoadObject(result); b.endReturn();
                    b.endBlock(); b.endTryCatch();
                    b.endRoot();
                }).getNode(0);
                root.configureAsync(true); root.configureStackDriver(metrics);
                root.configureStackTransaction(transactionSlot[0]);
                assertTrue(root.prepareForCompilation(true, 2, true));
                root.getBytecodeNode().ensureSourceInformation();
                var first = assertInstanceOf(ContinuationResult.class,
                        Calls.target(root.getCallTarget(), new Object[]{0L}));
                assertSame(Unit.INSTANCE, first.getResult());
                var savedLocation = first.getContinuationRootNode().getLocation();
                var target = (OptimizedCallTarget) first.getContinuationRootNode().getCallTarget();
                assertTrue(target.compile(true)); assertTrue(target.isValidLastTier());
                assertEquals(0L, metrics.getCompiledEntries());
                ((OptimizedTruffleRuntime) Truffle.getRuntime()).bypassedInstalledCode(target);
                var second = assertInstanceOf(ContinuationResult.class, first.continueWith(2L));
                assertEquals(2L, second.getResult());
                assertSame(first.getFrame(), second.getFrame());
                assertEquals(1L, metrics.getCompiledEntries());
                assertSame(target, first.getContinuationRootNode().getCallTarget());
                assertSame(savedLocation.getBytecodeNode(), first.getContinuationRootNode().getLocation().getBytecodeNode());
                assertEquals(savedLocation.getBytecodeIndex(), first.getContinuationRootNode().getLocation().getBytecodeIndex());
                assertEquals(43L, second.continueWith(3L));
                assertEquals(1L, metrics.getCompiledEntries());
            } finally { context.leave(); }
        }
    }
}
