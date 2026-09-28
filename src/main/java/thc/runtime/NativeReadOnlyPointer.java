// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import java.lang.ref.Reference;

@ExportLibrary(InteropLibrary.class)
public final class NativeReadOnlyPointer implements TruffleObject {
    private final NativeReadOnlyImage image;
    private final Object source;
    public NativeReadOnlyPointer(NativeReadOnlyImage image, Object source) { this.image = image; this.source = source; }
    @ExportMessage public boolean isPointer() { return image.isAlive(); }
    @ExportMessage public long asPointer() throws UnsupportedMessageException {
        if (!image.isAlive()) throw UnsupportedMessageException.create();
        try { return image.getBase(); } finally { Reference.reachabilityFence(source); }
    }
}
