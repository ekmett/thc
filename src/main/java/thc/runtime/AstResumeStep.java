// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.VirtualFrame;

/** A cold AST fragment resumes after its child has produced the input value. */
@FunctionalInterface
public interface AstResumeStep {
    Object resume(VirtualFrame frame, Object input);
}
