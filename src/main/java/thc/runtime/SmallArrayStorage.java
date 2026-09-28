// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import static thc.runtime.RuntimeServiceStatus.fault;

/** SmallArray# has a distinct carrier and an independently published logical size. */
public final class SmallArrayStorage {
    private final Object[] elements;
    private volatile boolean frozen;
    private volatile int logicalSize;
    public SmallArrayStorage(Object[] elements) { this.elements = elements; logicalSize = elements.length; }
    public Object[] getElements() { return elements; }
    public boolean getFrozen() { return frozen; }
    public void setFrozen(boolean frozen) { this.frozen = frozen; }
    public int getLogicalSize() { return logicalSize; }
    public synchronized void shrink(long size) {
        if (size < 0 || size > logicalSize) throw fault("SmallMutableArray# shrink length outside current size");
        Arrays.fill(elements, (int) size, logicalSize, null);
        logicalSize = (int) size;
    }
}
