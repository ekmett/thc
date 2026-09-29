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
            case ADDRESS -> ManagedAddress.nullAddress();
            case VOID -> Unit.INSTANCE;
            case VECTOR -> new VectorLayout(proof).getSpecies().zero();
            case CLOSURE -> closure();
            case DATA, OBJECT -> boxed();
            default -> throw new UnsupportedCore("Unsupported rubbish representation");
        };
    }
    public static CoreRepresentation proof(List<Object> expression) {
        if (expression.size() < 3 || expression.get(2) != null)
            throw new RuntimeFault("Rubbish literal has no payload; its representation belongs in metadata");
        var metadata = CoreRepresentations.metadata(expression);
        var actual = CoreRepresentations.parse(metadata == null ? null : metadata.get("rep"));
        if (!actual.getPresent() || !actual.getEvaluated())
            throw new RuntimeFault("Rubbish literal requires explicit evaluated representation");
        validate(actual);
        return actual;
    }
    private static void validate(CoreRepresentation proof) {
        if (proof.isTuple()) {
            TupleShape.validate(proof);
            for (var component : proof.getComponents()) validate(component);
        } else if (proof.isSum()) {
            SumShape.validate(proof);
            for (var alternative : proof.getAlternatives()) validate(alternative);
        } else if (proof.isVector()) VectorLayout.validate(proof);
        else if (!proof.getPresent() || proof.getKind() == CoreKind.UNKNOWN)
            throw new UnsupportedCore("Rubbish requires a complete logical representation");
    }

}
