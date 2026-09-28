// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0
package protocolprobe;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.RepeatingNode;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.runtime.BaseOSRRootNode;
import com.oracle.truffle.runtime.BytecodeOSRMetadata;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedOSRLoopNode;
import org.graalvm.polyglot.Context;
import thc.Language;

/** The accessor reports the actual source root for both pinned OSR hierarchies. */
public final class OsrSourceRootProbe {
    private static void require(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }

    private static final class OneIteration extends Node implements RepeatingNode {
        int effects;
        @Override public boolean executeRepeating(VirtualFrame frame) { effects++; return false; }
    }

    private static final class LoopRoot extends RootNode {
        @Child OptimizedOSRLoopNode loop;
        LoopRoot(Language language) {
            super(language);
            loop = (OptimizedOSRLoopNode) Truffle.getRuntime().createLoopNode(new OneIteration());
        }
        @Override public Object execute(VirtualFrame frame) { loop.execute(frame); return 42L; }
        @Override public boolean isCloningAllowed() { return true; }
    }

    private static void loopOwner(LoopRoot root) {
        root.getCallTarget();
        root.loop.forceOSR();
        OptimizedCallTarget target = root.loop.getCompiledOSRLoop();
        require(target != null && target.isValidLastTier(), "actual loop OSR compiled");
        require(((BaseOSRRootNode) target.getRootNode()).getSourceRootNode() == root,
                "loop OSR exposes its actual adopted source root");
        require(((OneIteration) root.loop.getRepeatingNode()).effects == 0,
                "source ownership query neither executes nor warms the guest");
    }

    private static void bytecodeOwner(Language language, boolean materializable) {
        var state = new MaterializationProbe.LoopState();
        var root = new MaterializationProbe.OsrRoot(language, MaterializationProbe.descriptor(), materializable, state);
        require(root.getCallTarget().call(state.arguments).equals(100000L), "original bytecode OSR result");
        require(state.compiled > 0, "actual compiled bytecode OSR entry");
        require(state.sameArguments == materializable && (state.copies == 0) == materializable,
                "unchanged declared parent-frame versus copied-frame transfer");
        var targets = ((BytecodeOSRMetadata) root.body.getOSRMetadata()).getOSRCompilations();
        require(targets.size() == 1, "one actual bytecode OSR target");
        var target = targets.values().iterator().next();
        require(target.isValidLastTier(), "bytecode OSR remains installed");
        require(((BaseOSRRootNode) target.getRootNode()).getSourceRootNode() == root,
                "bytecode OSR exposes exact owner for either frame-transfer implementation");
    }

    public static void main(String[] args) {
        try (Context context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.SingleTierCompilationThreshold", "10000000")
                .option("engine.OSRCompilationThreshold", "1024")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            context.initialize("thc"); context.enter();
            try {
                Language language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var source = new LoopRoot(language);
                loopOwner(source);
                var clone = NodeUtil.cloneNode(source);
                require(clone != source && clone.loop != source.loop, "actual cloned loop nodes");
                loopOwner(clone);
                require(source.loop.getCompiledOSRLoop() != clone.loop.getCompiledOSRLoop(), "independent OSR targets");
                bytecodeOwner(language, false);
                bytecodeOwner(language, true);
                System.out.println("PASS original and cloned LoopNode OSR owners; copied and parent-frame bytecode OSR owners");
            } finally { context.leave(); }
        }
    }
}
