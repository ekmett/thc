// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.interop.*;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

@ExportLibrary(InteropLibrary.class)
public final class PackageNativePointer implements TruffleObject {
    private final long bits;
    private final PackagePointerLease lease;
    public PackageNativePointer(long bits, PackagePointerLease lease) { this.bits = bits; this.lease = lease; }
    @ExportMessage public boolean isPointer() { return lease == null || lease.open; }
    @ExportMessage public long asPointer() throws UnsupportedMessageException {
        if (!isPointer()) throw UnsupportedMessageException.create();
        return bits;
    }
}
