// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;

/** An unexecuted scalar side-root entry, before publishing any caller segment.
 * Appended suffixes and scope enclosures keep this step and force publication. */
final class AstPendingTail implements AstResumeStep {
    final AstContinuation child;
    final RootCallTarget target;
    private final Node node;
    private final MaskingState mask;
    private CallSegment segment;

    AstPendingTail(AstContinuation child, RootCallTarget target, Node node, MaskingState mask) {
        this.child = child;
        this.target = target;
        this.node = node;
        this.mask = mask;
    }

    CallSegmentSuspended publish() {
        if (segment != null) throw new IllegalStateException("Pending tail was already published");
        segment = new CallSegment(child.getIdentity(), mask, mask, null, false, true);
        return new CallSegmentSuspended(segment, null, null, true);
    }

    @Override public Object resume(VirtualFrame frame, Object input) {
        if (segment == null) throw new IllegalStateException("Unpublished tail cannot resume a child result");
        return AstControl.resumeChild(segment, node, input, this);
    }
}
