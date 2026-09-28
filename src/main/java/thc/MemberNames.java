// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

@ExportLibrary(InteropLibrary.class)
public final class MemberNames implements TruffleObject {
    private final String[] names;
    public MemberNames(String[] names) { this.names = names; }
    @ExportMessage public boolean hasArrayElements() { return true; }
    @ExportMessage public long getArraySize() { return names.length; }
    @ExportMessage public boolean isArrayElementReadable(long index) { return index >= 0 && index < names.length; }
    @ExportMessage public Object readArrayElement(long index) throws InvalidArrayIndexException {
        if (!isArrayElementReadable(index)) throw InvalidArrayIndexException.create(index);
        return names[(int) index];
    }
}
