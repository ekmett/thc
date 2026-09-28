// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.interop.*;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/** A logical host aggregate, never a view of pooled guest transport storage. */
@ExportLibrary(InteropLibrary.class)
public final class HostArray implements TruffleObject {
    private final Object[] elements;
    HostArray(Object[] elements) { this.elements = elements; }
    @ExportMessage public boolean hasArrayElements() { return true; }
    @ExportMessage public long getArraySize() { return elements.length; }
    @ExportMessage public boolean isArrayElementReadable(long index) { return index >= 0 && index < elements.length; }
    @ExportMessage public Object readArrayElement(long index) throws InvalidArrayIndexException {
        if (!isArrayElementReadable(index)) throw InvalidArrayIndexException.create(index);
        return elements[(int) index];
    }
}
