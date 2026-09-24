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
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
            if (marker instanceof GuestException failure) throw failure;
            return new Answer(number, marker);
        }
    }

    @Operation(forceCached = true)
    @ConstantOperand(type = AtomicReference.class, name = "child")
    @ConstantOperand(type = RootCallTarget.class, name = "childForceTarget")
    public static final class CallChild {
        @Specialization public static Object run(AtomicReference<?> child, RootCallTarget childForceTarget,
                @Cached IndirectCallNode call) {
            return call.call(childForceTarget, child.get());
        }
    }

    /** Gives the nested force its own VirtualFrame without materializing the caller. */
    private static final class ChildForceRoot extends RootNode {
        @Child private Force force = new Force(new Metrics(true));
        private ChildForceRoot() { super(null); }
        @Override public Object execute(VirtualFrame frame) {
            return force.execute(frame, frame.getArguments()[0]);
        }
    }

    @Operation
    public static final class SuspensionOnly {
        @Specialization public static ThunkSuspended run(AbstractTruffleException failure) {
            if (failure instanceof ThunkSuspended suspended) return suspended;
            throw failure;
        }
    }

    @Operation
    public static final class ResumeChild {
        @Specialization public static Object run(ChildResume resume) {
            if (resume.getFailure() != null) throw resume.getFailure();
            return resume.getValue();
        }
        @Specialization public static Object deliver(AsyncThunkUnwind delivery) {
            throw new PrivateAsyncDelivery(delivery.getPayload());
        }
    }

    /** A test-only origin tag: never a GuestException or shared thunk result. */
    public static final class PrivateAsyncDelivery extends AbstractTruffleException {
        private final Object payload;
        PrivateAsyncDelivery(Object payload) {
            super("Private captured-handler delivery", null, 0, null);
            this.payload = payload;
        }
        public Object getPayload() { return payload; }
    }

    @Operation public static final class RequirePrivateAsyncDelivery {
        @Specialization public static Object payload(AbstractTruffleException failure) {
            if (failure instanceof PrivateAsyncDelivery delivery) return delivery.getPayload();
            throw failure;
        }
    }

    @Operation
    public static final class AddNumber {
        @Specialization public static long run(long left, Answer right) {
            return left + right.number();
        }
        @Specialization public static long run(long left, long right) {
            return left + right;
        }
    }

    /** The test root uses the same mask operations as production bytecode. */
    @Operation
    @ConstantOperand(type = MaskingState.class, name = "target")
    public static final class EnterLogicalMask {
        @Specialization public static MaskingState run(MaskingState target, @Bind("$node") Node node) {
            return BytecodeRoot.EnterMask.enter(target, node);
        }
    }

    @Operation public static final class RestoreLogicalMask {
        @Specialization public static void run(MaskingState prior, @Bind("$node") Node node) {
            BytecodeRoot.RestoreMask.restore(prior, node);
        }
    }

    @Operation public static final class EnterExceptionHandler {
        @Specialization public static MaskingState run(@Bind("$node") Node node) {
            return BytecodeRoot.EnterHandlerMask.enter(node);
        }
    }

    @Operation public static final class RequireGuestFailure {
        @Specialization public static Object run(AbstractTruffleException failure) {
            return BytecodeRoot.RequireGuestFailure.payload(failure);
        }
    }

    public static final class MaskProbe {
        public final AtomicReference<MaskingState> parked = new AtomicReference<>();
        public final AtomicReference<MaskingState> reentered = new AtomicReference<>();
        public final AtomicReference<MaskingState> afterInner = new AtomicReference<>();
        public final AtomicReference<MaskingState> afterOuter = new AtomicReference<>();
        public final AtomicReference<MaskingState> handlerMask = new AtomicReference<>();
        public final AtomicReference<Object> handlerPayload = new AtomicReference<>();
    }

    @Operation
    @ConstantOperand(type = MaskProbe.class, name = "probe")
    public static final class ParkLogicalMask {
        @Specialization public static ThunkSuspended run(MaskProbe probe,
                ThunkSuspended suspension, MaskingState ambient, MaskingState active,
                @Bind("$node") Node node) {
            MaskingState current = SynchronousMasking.current(node);
            if (current != active) throw new AssertionError("Lost logical mask before suspension");
            probe.parked.set(current);
            // Yield does not execute the lexical finally handlers. Unwind all
            // nested mask scopes to the original carrier ambient explicitly.
            BytecodeRoot.RestoreMask.restore(ambient, node);
            return suspension;
        }
    }

    @Operation
    @ConstantOperand(type = MaskProbe.class, name = "probe")
    public static final class ReenterLogicalMask {
        @Specialization public static Object run(MaskProbe probe,
                Object resumed, MaskingState active, @Bind("$node") Node node) {
            BytecodeRoot.EnterMask.enter(active, node);
            probe.reentered.set(SynchronousMasking.current(node));
            return resumed;
        }
    }

    @Operation
    @ConstantOperand(type = MaskProbe.class, name = "probe")
    public static final class ObserveHandler {
        @Specialization public static void run(MaskProbe probe, Object failure, @Bind("$node") Node node) {
            probe.handlerMask.set(SynchronousMasking.current(node));
            probe.handlerPayload.set(failure);
        }
    }

    @Operation
    @ConstantOperand(type = MaskProbe.class, name = "probe")
    public static final class AfterInnerMask {
        @Specialization public static void run(MaskProbe probe, @Bind("$node") Node node) {
            probe.afterInner.set(SynchronousMasking.current(node));
        }
    }

    @Operation
    @ConstantOperand(type = MaskProbe.class, name = "probe")
    public static final class AfterOuterMask {
        @Specialization public static void run(MaskProbe probe, @Bind("$node") Node node) {
            probe.afterOuter.set(SynchronousMasking.current(node));
        }
    }

    public static final class TailProbe {
        public final RootCallTarget target;
        public volatile boolean armed;
        public TailProbe(RootCallTarget target) { this.target = target; }
    }

    @Operation
    @ConstantOperand(type = TailProbe.class, name = "probe")
    public static final class TailAfterResume {
        @Specialization public static Object run(TailProbe probe, Object answer) {
            if (!probe.armed) return answer;
            throw new TailCall(probe.target, new Object[]{0L}, null);
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

    /** The left operand remains live below the try/catch and nested child call. */
    public static RootCallTarget caller(Language language, Thunk child, AtomicInteger effects) {
        return caller(language, new AtomicReference<>(child), effects, new AtomicInteger());
    }

    public static RootCallTarget caller(Language language, AtomicReference<Thunk> child,
                                        AtomicInteger effects, AtomicInteger compiledEffects) {
        RootCallTarget childForceTarget = new ChildForceRoot().getCallTarget();
        return ThunkYieldProofRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
            b.beginRoot();
            BytecodeLocal childResult = b.createLocal("child result", "object");
            b.beginBlock();
            b.emitEffect(effects, compiledEffects);
            b.beginReturn();
            b.beginAddNumber();
            b.emitLoadConstant(100L);
            b.beginBlock();
            b.beginTryCatch();
            b.beginStoreLocal(childResult); b.emitCallChild(child, childForceTarget); b.endStoreLocal();
            b.beginStoreLocal(childResult);
                b.beginResumeChild();
                    b.beginYield();
                    b.beginSuspensionOnly(); b.emitLoadException(); b.endSuspensionOnly();
                    b.endYield();
                b.endResumeChild();
            b.endStoreLocal();
            b.endTryCatch();
            b.emitLoadLocal(childResult);
            b.endBlock();
            b.endAddNumber();
            b.endReturn();
            b.endBlock();
            b.endRoot();
        }).getNode(0).getCallTarget();
    }

    /** Nested mask and real guest catch scopes around a shared suspending child. */
    public static RootCallTarget maskedCaller(Language language, AtomicReference<Thunk> child, AtomicInteger effects,
            AtomicInteger compiledEffects, MaskingState outer, MaskingState inner, MaskProbe probe) {
        return maskedCaller(language, child, effects, compiledEffects, outer, inner, probe, null, false);
    }

    public static RootCallTarget maskedCaller(Language language, AtomicReference<Thunk> child, AtomicInteger effects,
            AtomicInteger compiledEffects, MaskingState outer, MaskingState inner, MaskProbe probe,
            TailProbe tailProbe) {
        return maskedCaller(language, child, effects, compiledEffects, outer, inner, probe, tailProbe, false);
    }

    public static RootCallTarget privateAsyncHandlerCaller(Language language, AtomicReference<Thunk> child,
            AtomicInteger effects, AtomicInteger compiledEffects, MaskingState outer, MaskingState inner,
            MaskProbe probe) {
        return maskedCaller(language, child, effects, compiledEffects, outer, inner, probe, null, true);
    }

    private static RootCallTarget maskedCaller(Language language, AtomicReference<Thunk> child, AtomicInteger effects,
            AtomicInteger compiledEffects, MaskingState outer, MaskingState inner, MaskProbe probe,
            TailProbe tailProbe, boolean privateAsyncHandler) {
        RootCallTarget childForceTarget = new ChildForceRoot().getCallTarget();
        return ThunkYieldProofRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
            b.beginRoot();
            BytecodeLocal outerPrior = b.createLocal("outer prior mask", "object");
            BytecodeLocal innerPrior = b.createLocal("inner prior mask", "object");
            BytecodeLocal active = b.createLocal("logical active mask", "object");
            BytecodeLocal childResult = b.createLocal("child result", "object");
            BytecodeLocal answer = b.createLocal("answer", "primitive");
            BytecodeLocal payload = b.createLocal("guest payload", "object");
            BytecodeLocal handlerPrior = b.createLocal("handler prior mask", "object");
            b.beginBlock();
            b.emitEffect(effects, compiledEffects);
            b.beginStoreLocal(outerPrior); b.emitEnterLogicalMask(outer); b.endStoreLocal();
            b.beginTryFinally(() -> {
                b.beginRestoreLogicalMask(); b.emitLoadLocal(outerPrior); b.endRestoreLogicalMask();
            });
            b.beginBlock();
            b.beginStoreLocal(innerPrior); b.emitEnterLogicalMask(inner); b.endStoreLocal();
            b.beginStoreLocal(active); b.emitLoadConstant(inner); b.endStoreLocal();
            b.beginTryFinally(() -> {
                b.beginRestoreLogicalMask(); b.emitLoadLocal(innerPrior); b.endRestoreLogicalMask();
            });
            b.beginTryCatch();
            b.beginStoreLocal(answer);
            if (tailProbe != null) b.beginTailAfterResume(tailProbe);
            b.beginAddNumber();
            b.emitLoadConstant(100L);
            b.beginBlock();
            b.beginTryCatch();
            b.beginStoreLocal(childResult); b.emitCallChild(child, childForceTarget); b.endStoreLocal();
            b.beginStoreLocal(childResult);
            b.beginResumeChild();
            b.beginReenterLogicalMask(probe);
            b.beginYield();
            b.beginParkLogicalMask(probe);
            b.beginSuspensionOnly(); b.emitLoadException(); b.endSuspensionOnly();
            b.emitLoadLocal(outerPrior);
            b.emitLoadLocal(active);
            b.endParkLogicalMask();
            b.endYield();
            b.emitLoadLocal(active);
            b.endReenterLogicalMask();
            b.endResumeChild();
            b.endStoreLocal();
            b.endTryCatch();
            b.emitLoadLocal(childResult);
            b.endBlock();
            b.endAddNumber();
            if (tailProbe != null) b.endTailAfterResume();
            b.endStoreLocal();
            b.beginBlock();
            b.beginStoreLocal(payload);
            if (privateAsyncHandler) {
                b.beginRequirePrivateAsyncDelivery(); b.emitLoadException(); b.endRequirePrivateAsyncDelivery();
            } else {
                b.beginRequireGuestFailure(); b.emitLoadException(); b.endRequireGuestFailure();
            }
            b.endStoreLocal();
            b.beginStoreLocal(handlerPrior); b.emitEnterExceptionHandler(); b.endStoreLocal();
            b.beginTryFinally(() -> {
                b.beginRestoreLogicalMask(); b.emitLoadLocal(handlerPrior); b.endRestoreLogicalMask();
            });
            b.beginBlock();
            b.beginObserveHandler(probe);
            b.emitLoadLocal(payload);
            b.endObserveHandler();
            b.beginStoreLocal(answer); b.emitLoadConstant(77L); b.endStoreLocal();
            b.endBlock();
            b.endTryFinally();
            b.endBlock();
            b.endTryCatch();
            b.endTryFinally();
            b.emitAfterInnerMask(probe);
            b.endBlock();
            b.endTryFinally();
            b.emitAfterOuterMask(probe);
            b.beginReturn(); b.emitLoadLocal(answer); b.endReturn();
            b.endBlock();
            b.endRoot();
        }).getNode(0).getCallTarget();
    }

    /** A Haskell catch must reject an uncaptured internal suspension. */
    public static RootCallTarget uncapturedMaskedCaller(Language language, Thunk child, MaskProbe probe) {
        RootCallTarget childForceTarget = new ChildForceRoot().getCallTarget();
        AtomicReference<Thunk> selectedChild = new AtomicReference<>(child);
        return ThunkYieldProofRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
            b.beginRoot();
            BytecodeLocal prior = b.createLocal("caller mask", "object");
            BytecodeLocal result = b.createLocal("child result", "object");
            BytecodeLocal payload = b.createLocal("guest payload", "object");
            b.beginBlock();
            b.beginStoreLocal(prior);
            b.emitEnterLogicalMask(MaskingState.MASKED_INTERRUPTIBLE);
            b.endStoreLocal();
            b.beginTryFinally(() -> {
                b.beginRestoreLogicalMask(); b.emitLoadLocal(prior); b.endRestoreLogicalMask();
            });
            b.beginTryCatch();
            b.beginStoreLocal(result); b.emitCallChild(selectedChild, childForceTarget); b.endStoreLocal();
            b.beginBlock();
            b.beginStoreLocal(payload);
            b.beginRequireGuestFailure(); b.emitLoadException(); b.endRequireGuestFailure();
            b.endStoreLocal();
            b.beginObserveHandler(probe);
            b.emitLoadLocal(payload);
            b.endObserveHandler();
            b.endBlock();
            b.endTryCatch();
            b.endTryFinally();
            b.beginReturn(); b.emitLoadConstant(0L); b.endReturn();
            b.endBlock();
            b.endRoot();
        }).getNode(0).getCallTarget();
    }
}
