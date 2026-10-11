// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

package jam.vm;

/**
 * Language-owned, nonforcing resolution of computations and projections.
 *
 * <p>A replacement preserves the guest result, effects and sharing of the
 * original computation. It may be another computation or a language constructor
 * implementing Lifted. For example, an I# constructor stores its primitive
 * integer directly; it need not contain a java.lang.Integer. Resolution must
 * not evaluate guest code, wait for another evaluator, or manufacture a boxed projection.
 * The implementation owns safe publication of its state.
 *
 * <p>These are ordinary Java calls, outside collection. Implementing this
 * interface does not change strong-reference identity or enable collector
 * slot rewriting. Callers own traversal and cycle detection; null means no
 * replacement is available, not that the object is dead or evaluated.
 * Language-specific state must distinguish a terminal value from an
 * unresolved computation when implementing weak finalizer handoffs.
 */
public interface Lifted {
    /**
     * Inspect an already available replacement without forcing this computation.
     *
     * @return the next replacement, or null when none is available
     */
    Lifted resolve();

    /**
     * Inspect an existing reference-valued field without forcing it or boxing
     * a primitive field. The language defines field numbering and which
     * projections are valid. An available result may itself be unevaluated.
     *
     * @param field the language-defined projection
     * @return the existing field reference, or null when unavailable
     */
    Lifted project(int field);
}
