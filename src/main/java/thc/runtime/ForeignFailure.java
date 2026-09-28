// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import java.util.concurrent.atomic.AtomicReferenceArray;
import thc.Language;

/** No foreign code runs when Haskell displays this opaque value. Metadata is
 * queried only by the explicit IO operations and then cached as inert text. */
public final class ForeignFailure {
    private final Language.State owner;
    private final AbstractTruffleException original;
    private final ForeignExceptionRegistry.Projector projector;
    private final AtomicReferenceArray<Text> text = new AtomicReferenceArray<>(2);
    public ForeignFailure(Language.State owner, AbstractTruffleException original, ForeignExceptionRegistry.Projector projector) {
        this.owner = owner; this.original = original; this.projector = projector;
    }
    public Language.State getOwner() { return owner; }
    public AbstractTruffleException getOriginal() { return original; }
    public ForeignExceptionRegistry.Projector getProjector() { return projector; }
    public AtomicReferenceArray<Text> getText() { return text; }
    public static final class Text {
        private final String value;
        public Text(String value) { this.value = value; }
        public String getValue() { return value; }
    }
}
