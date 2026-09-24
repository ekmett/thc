// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeLocal;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.bytecode.ConstantOperand;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import thc.Language;

/** A bytecode root whose actual frame, program counter and live locals cross two yields. */
@GenerateBytecode(languageClass = Language.class, enableYield = true,
        enableUncachedInterpreter = true, defaultUncachedThreshold = "0",
        boxingEliminationTypes = {long.class})
public abstract class ThunkYieldProofRoot extends RootNode implements BytecodeRootNode {
    protected ThunkYieldProofRoot(Language language, FrameDescriptor descriptor) {
        super(language, descriptor);
    }

    @Operation
    @ConstantOperand(type = AtomicInteger.class, name = "effects")
    @ConstantOperand(type = AtomicInteger.class, name = "compiledEffects")
    public static final class Effect {
        @Specialization public static void run(AtomicInteger effects, AtomicInteger compiledEffects) {
            effects.incrementAndGet();
            if (CompilerDirectives.inCompiledCode()) compiledEffects.incrementAndGet();
        }
    }

    public static final class Gate {
        public volatile boolean armed;
        public final CountDownLatch entered = new CountDownLatch(1);
        public final CountDownLatch release = new CountDownLatch(1);
    }

    @Operation
    @ConstantOperand(type = Gate.class, name = "gate")
    public static final class Pause {
        @Specialization @CompilerDirectives.TruffleBoundary public static void run(Gate gate) {
            if (!gate.armed) return;
            gate.entered.countDown();
            try {
                if (!gate.release.await(5, TimeUnit.SECONDS)) throw new AssertionError("resume gate timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("resume gate interrupted", e);
            }
        }
    }

    public record Answer(long number, Object marker) {}

    @Operation
    public static final class Finish {
        @Specialization public static Answer run(long number, Object marker) {
            return new Answer(number, marker);
        }
    }

    public static RootCallTarget target(Language language, AtomicInteger effects,
                                        AtomicInteger compiledEffects, Gate gate, Object marker) {
        return ThunkYieldProofRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
            b.beginRoot();
            BytecodeLocal number = b.createLocal("number", "primitive");
            BytecodeLocal reference = b.createLocal("reference", "object");
            b.beginBlock();
            b.beginStoreLocal(number); b.emitLoadConstant(42L); b.endStoreLocal();
            b.beginStoreLocal(reference); b.emitLoadConstant(marker); b.endStoreLocal();
            b.emitEffect(effects, compiledEffects);
            b.beginYield(); b.emitLoadConstant("first"); b.endYield();
            b.emitPause(gate);
            b.beginYield(); b.emitLoadConstant("second"); b.endYield();
            b.beginReturn();
            b.beginFinish(); b.emitLoadLocal(number); b.emitLoadLocal(reference); b.endFinish();
            b.endReturn();
            b.endBlock();
            b.endRoot();
        }).getNode(0).getCallTarget();
    }
}
