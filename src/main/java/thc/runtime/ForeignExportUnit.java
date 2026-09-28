// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
/** Java null is not a valid receiver for Truffle interop libraries. */
@ExportLibrary(InteropLibrary.class)
public final class ForeignExportUnit implements TruffleObject {
    public static final ForeignExportUnit INSTANCE = new ForeignExportUnit();
    private ForeignExportUnit() {}
    @ExportMessage public boolean isNull() { return true; }
}
