// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.frame.FrameDescriptor;

final class AuxiliarySlotLookup {
    private AuxiliarySlotLookup() {}

    static int findActive(FrameDescriptor descriptor, Object key) {
        return descriptor.getAuxiliarySlots().getOrDefault(key, -1);
    }
}
