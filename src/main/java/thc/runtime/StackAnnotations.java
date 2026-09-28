// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import java.util.List;
import thc.Language;

public final class StackAnnotations {
    private StackAnnotations() {}
    public static void validate(List<CoreRepresentation> arguments, List<?> flags, CoreRepresentation result) {
        CoreRepresentation annotation = arguments.isEmpty() ? null : arguments.getFirst();
        if (arguments.size() != 3 || !flags.equals(List.of(true, true, false)) || annotation == null || annotation.isAggregate() || annotation.isVector() ||
            (annotation.getKind() != CoreKind.OBJECT && annotation.getKind() != CoreKind.CLOSURE && annotation.getKind() != CoreKind.DATA))
            throw RuntimeFault.fault("annotateStack#: expected a lazy lifted annotation and State action");
        CoreProfileAction.validate(arguments.subList(1, arguments.size()), flags.subList(1, flags.size()), result);
    }
    public static StackAnnotationState current(Node node) { return Language.currentState(node).getThreadAnnotations().get().getValue(); }
    @TruffleBoundary public static void set(Node node, StackAnnotationState state) { Language.currentState(node).getStackAnnotations().set(state); }
    public static StackAnnotationState enter(Node node, Object annotation) {
        StackAnnotationState prior = current(node); set(node, prior.push(annotation)); return prior;
    }
}
