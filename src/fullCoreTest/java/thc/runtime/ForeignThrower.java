// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

@ExportLibrary(InteropLibrary.class)
public final class ForeignThrower implements TruffleObject {
    private final MetadataProtocolFailure failure;
    public ForeignThrower(MetadataProtocolFailure failure) { this.failure = failure; }
    @ExportMessage public boolean isExecutable() { return true; }
    @ExportMessage public Object execute(Object[] arguments) { throw failure; }
}
