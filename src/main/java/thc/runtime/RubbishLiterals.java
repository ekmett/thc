// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.List;

/** Non-bottom, same-representation absent fillers; their payload is not observable. */
public final class RubbishLiterals {
    private final TruffleLanguage<?> language;
    private DataValue boxed;
    private Closure closure;
    public RubbishLiterals(TruffleLanguage<?> language) { this.language = language; }
    private synchronized DataValue boxed() {
        if (boxed == null) {
            if (language == null) throw new RuntimeFault("Boxed rubbish requires a guest language");
            boxed = new DataLayout(language, "THC.Rubbish", "<absent>", new String[0], new Class<?>[0]).create(new Object[0]);
        }
        return boxed;
    }
    private synchronized Closure closure() {
        if (closure == null) closure = new Closure(null, 1, RootNode.createConstantNode(boxed()).getCallTarget());
        return closure;
    }
    public Object decode(CoreRepresentation proof) {
        return switch (proof.getKind()) {
            case LONG -> { if (proof.isInt()) yield Integer.valueOf(0); yield Long.valueOf(0L); }
            case FLOAT -> 0.0f;
            case DOUBLE -> 0.0;
            case ADDRESS -> ManagedAddress.Companion.nullAddress();
            case CLOSURE -> closure();
            case DATA, OBJECT -> boxed();
            default -> throw new UnsupportedCore("Unsupported rubbish representation");
        };
    }
    public static CoreRepresentation proof(List<Object> expression) {
        Object rep = expression.size() > 2 ? expression.get(2) : null;
        CoreKind expected = rep instanceof String name ? switch (name) {
            case "IntRep", "Int8Rep", "Int16Rep", "Int32Rep", "Int64Rep",
                 "WordRep", "Word8Rep", "Word16Rep", "Word32Rep", "Word64Rep" -> CoreKind.LONG;
            case "FloatRep" -> CoreKind.FLOAT;
            case "DoubleRep" -> CoreKind.DOUBLE;
            case "AddrRep" -> CoreKind.ADDRESS;
            case "BoxedRep (Just Lifted)", "BoxedRep (Just Unlifted)" -> CoreKind.OBJECT;
            default -> null;
        } : null;
        if (expected == null) throw new UnsupportedCore("Unsupported rubbish representation: " + rep);
        var metadata = CoreRepresentations.INSTANCE.metadata(expression);
        var actual = CoreRepresentations.INSTANCE.parse(metadata == null ? null : metadata.get("rep"));
        boolean matchingKind = actual.getKind() == expected || expected == CoreKind.OBJECT &&
            (actual.getKind() == CoreKind.DATA || actual.getKind() == CoreKind.CLOSURE);
        if (!actual.getPresent() || !actual.getEvaluated() || !matchingKind || actual.isAggregate() || actual.isVector() ||
            !List.of(rep).equals(actual.getPrimReps()))
            throw new RuntimeFault("Rubbish literal requires explicit exact evaluated scalar representation");
        return actual;
    }
}
