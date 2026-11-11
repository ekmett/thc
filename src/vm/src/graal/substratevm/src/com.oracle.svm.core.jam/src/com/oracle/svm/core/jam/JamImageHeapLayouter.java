// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
package com.oracle.svm.core.jam;

import java.nio.ByteBuffer;
import com.oracle.svm.core.image.ImageHeap;
import com.oracle.svm.core.image.ImageHeapLayoutInfo;
import com.oracle.svm.core.image.ImageHeapLayouter;
import com.oracle.svm.core.image.ImageHeapObject;
import com.oracle.svm.core.image.ImageHeapObjectSorter;
import com.oracle.svm.shared.util.VMError;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.BuildtimeAccessOnly;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;

/** The permanent image has page-separated read-only, relocated, patched and writable regions. */
@SingletonTraits(access = BuildtimeAccessOnly.class, layeredCallbacks = NoLayeredCallbacks.class)
final class JamImageHeapLayouter implements ImageHeapLayouter {
    private final JamImageHeapInfo info;
    private final JamImageHeapPartition[] partitions = {
        new JamImageHeapPartition("readOnly", false),
        new JamImageHeapPartition("readOnlyRelocatable", false),
        new JamImageHeapPartition("writablePatched", true),
        new JamImageHeapPartition("writable", true)
    };

    JamImageHeapLayouter(JamImageHeapInfo info) { this.info = info; }

    @Override
    public JamImageHeapPartition[] getPartitions() { return partitions; }

    @Override
    public void assignObjectToPartition(ImageHeapObject object, boolean immutable, boolean references, boolean relocatable, boolean patched) {
        VMError.guarantee(!patched || !relocatable, "An image object cannot be both patched and relocatable");
        JamImageHeapPartition partition = partitions[patched ? 2 : immutable ? relocatable ? 1 : 0 : 3];
        object.setHeapPartition(partition);
        partition.objects.add(object);
    }

    private static long align(long value, long alignment) {
        return (value + alignment - 1) & -alignment;
    }

    @Override
    public ImageHeapLayoutInfo layout(ImageHeap imageHeap, int pageSize, ImageHeapObjectSorter sorter, ImageHeapLayouterCallback callback) {
        long cursor = JamHeap.IMAGE_OFFSET;
        ImageHeapLayouterControl control = new ImageHeapLayouterControl(callback);
        for (int i = 0; i < partitions.length; i++) {
            JamImageHeapPartition partition = partitions[i];
            cursor = align(cursor, pageSize);
            partition.start = cursor;
            sorter.sort(partition, partition.objects);
            for (ImageHeapObject object : partition.objects) {
                control.poll();
                VMError.guarantee((object.getSize() & 7) == 0, "Jam image object must be word aligned");
                object.setOffsetInPartition(cursor - partition.start);
                cursor += object.getSize();
            }
            partition.size = cursor - partition.start;
            if (!partition.objects.isEmpty()) {
                info.setBounds(i, partition.objects.getFirst().getWrapped(), partition.objects.getLast().getWrapped());
            }
        }
        long end = align(cursor, pageSize);
        VMError.guarantee(end <= JamHeap.YOUNG_OFFSET, "Jam image overlaps the young arena");
        info.classCount = imageHeap.countPatchAndVerifyDynamicHubs();
        return new ImageHeapLayoutInfo(JamHeap.IMAGE_OFFSET, end, partitions[2].start, end - partitions[2].start,
            partitions[1].start, partitions[1].size, partitions[2].start, partitions[2].size, pageSize);
    }

    @Override
    public void writeMetadata(ByteBuffer bytes, long offset) {
        // Jam's permanent image needs no chunk or forwarding metadata.
    }
}
