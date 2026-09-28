// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import java.util.Arrays;

/** Force marked arguments only for saturated calls; PAP construction never enters here. */
public final class EntryArguments extends Node {
    @CompilationFinal(dimensions = 1) private final int[] positions;
    @Children private Force[] forces;
    public EntryArguments(RootCallTarget target, Metrics metrics) { this(target, metrics, new boolean[0], 0); }
    public EntryArguments(RootCallTarget target, Metrics metrics, boolean[] knownEvaluated) { this(target, metrics, knownEvaluated, 0); }
    public EntryArguments(RootCallTarget target, Metrics metrics, boolean[] knownEvaluated, int prefixSize) {
        if (!(target.getRootNode() instanceof GuestRoot root) || captures(root)) positions = new int[0];
        else {
            boolean[] strict = root.getEntryStrict();
            ArgumentLayout layout = root.getInputLayout();
            int[] selected = new int[strict.length];
            int count = 0;
            for (int i = 0; i < strict.length; i++) {
                int known = i - prefixSize;
                if (strict[i] && (layout == null || !layout.isTuple(i) && !layout.isVector(i)) &&
                    (i < prefixSize || known >= knownEvaluated.length || !knownEvaluated[known]))
                    selected[count++] = ArgumentLayout.offset(layout, i) + root.getEntryArgumentOffset();
            }
            positions = Arrays.copyOf(selected, count);
        }
        forces = new Force[positions.length];
        for (int i = 0; i < forces.length; i++) forces[i] = new Force(metrics);
    }
    static boolean captures(GuestRoot root) {
        return root instanceof BytecodeRoot bytecode && bytecode.isAsyncEnabled() ||
            root instanceof FunctionRoot ast && ast.getCapturesContinuations$org_intelligence_thc();
    }
    @ExplodeLoop public void execute(VirtualFrame frame, Object[] packet) {
        for (int i = 0; i < positions.length; i++) {
            int position = positions[i];
            packet[position] = forces[i].execute(frame, packet[position]);
        }
    }
    public void executeCaptured(VirtualFrame frame, Object[] packet) { forceFrom(frame, packet, 0); }
    @ExplodeLoop private void forceFrom(VirtualFrame frame, Object[] packet, int start) {
        for (int i = start; i < positions.length; i++) {
            int position = positions[i];
            try { packet[position] = AstControl.forceCallback(frame, this, forces[i], packet[position]); }
            catch (AstCapture cut) {
                int next = i + 1;
                throw cut.append((saved, input) -> {
                    packet[position] = input;
                    forceFrom(saved, packet, next);
                    return Unit.INSTANCE;
                });
            }
        }
    }
}
