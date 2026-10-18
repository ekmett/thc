// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
package com.oracle.svm.core.jam;

import java.util.ArrayList;
import java.util.List;
import com.oracle.svm.core.image.ImageHeapObject;
import com.oracle.svm.core.image.ImageHeapPartition;

final class JamImageHeapPartition implements ImageHeapPartition {
    final List<ImageHeapObject> objects = new ArrayList<>();
    private final String name;
    private final boolean writable;
    long start;
    long size;

    JamImageHeapPartition(String name, boolean writable) {
        this.name = name;
        this.writable = writable;
    }

    @Override
    public String getName() { return name; }
    @Override
    public boolean isWritable() { return writable; }
    @Override
    public long getStartOffset() { return start; }
    @Override
    public long getSize() { return size; }
}
