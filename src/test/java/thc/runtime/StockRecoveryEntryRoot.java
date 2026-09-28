// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.ConstantOperand;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.bytecode.Prolog;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.nodes.DirectCallNode;
import java.util.concurrent.atomic.AtomicInteger;
import thc.Language;

/** Public stock entry-hook prerequisite, not a second recovery implementation. */
@GenerateBytecode(languageClass = Language.class, enableYield = true)
public abstract class StockRecoveryEntryRoot extends ContextRoot implements BytecodeRootNode {
    @Child private volatile DirectCallNode replacement;
    protected StockRecoveryEntryRoot(Language language, FrameDescriptor descriptor) { super(language, descriptor); }
    final StockRecoveryEntryRoot freshCopy() { return (StockRecoveryEntryRoot) cloneUninitialized(); }

    final void install(RootCallTarget target) {
        DirectCallNode prepared = DirectCallNode.create(target);
        atomic(() -> {
            if (replacement != null) throw new IllegalStateException("Entry already replaced");
            replacement = insert(prepared);
            reportReplace(this, this, "test stock entry hook");
        });
    }

    private static final class EnterReplacement extends ControlFlowException {
        private static final EnterReplacement INSTANCE = new EnterReplacement();
    }
    @Prolog public static final class Entry {
        @Specialization public static void enter(@Bind StockRecoveryEntryRoot root) {
            if (root.replacement != null) throw EnterReplacement.INSTANCE;
        }
    }
    @Override public Object interceptControlFlowException(ControlFlowException failure, VirtualFrame frame,
            BytecodeNode bytecode, int bci) {
        if (failure != EnterReplacement.INSTANCE) throw failure;
        return Calls.direct(replacement, frame.getArguments());
    }

    @Operation
    @ConstantOperand(type = AtomicInteger.class, name = "effects")
    @ConstantOperand(type = AtomicInteger.class, name = "compiled")
    public static final class Effect {
        @Specialization public static void run(AtomicInteger effects, AtomicInteger compiled) {
            effects.incrementAndGet();
            if (CompilerDirectives.inCompiledCode()) compiled.incrementAndGet();
        }
    }
}
