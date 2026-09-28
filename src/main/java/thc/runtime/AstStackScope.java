// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Every public, forked and callback entry owns its driver, including reentrant entries. */
public final class AstStackScope {
    public static final int MAX_DEPTH = 64;
    private int depth = 0;
    public int getDepth() { return depth; }
    public void setDepth(int value) { depth = value; }
    private boolean driving = false;
    public boolean getDriving() { return driving; }
    public void setDriving(boolean value) { driving = value; }
    private long spills = 0L;
    public long getSpills() { return spills; }
    public void setSpills(long value) { spills = value; }
    private long compactedFrames = 0L;
    public long getCompactedFrames() { return compactedFrames; }
    public void setCompactedFrames(long value) { compactedFrames = value; }
    private long tailAnchors = 0L;
    public long getTailAnchors() { return tailAnchors; }
    public void setTailAnchors(long value) { tailAnchors = value; }
    private int maxParkedSpillParents = 0;
    public int getMaxParkedSpillParents() { return maxParkedSpillParents; }
    public void setMaxParkedSpillParents(int value) { maxParkedSpillParents = value; }
    private AstTailAnchor tailAnchor = null;
    public AstTailAnchor getTailAnchor() { return tailAnchor; }
    public void setTailAnchor(AstTailAnchor value) { tailAnchor = value; }
}
