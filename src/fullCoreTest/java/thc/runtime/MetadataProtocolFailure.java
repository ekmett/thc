// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.interop.ExceptionType;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import java.util.function.Supplier;

@ExportLibrary(InteropLibrary.class)
public final class MetadataProtocolFailure extends AbstractTruffleException {
    private final ExceptionType kind;
    private final Supplier<String> messageAction;
    public MetadataProtocolFailure(Supplier<String> messageAction) { this(ExceptionType.RUNTIME_ERROR, messageAction); }
    public MetadataProtocolFailure(ExceptionType kind, Supplier<String> messageAction) { super("inert metadata test failure"); this.kind = kind; this.messageAction = messageAction; }
    @ExportMessage public ExceptionType getExceptionType() { return kind; }
    @ExportMessage public boolean hasExceptionMessage() { return true; }
    @ExportMessage public Object getExceptionMessage() { return messageAction.get(); }
    @ExportMessage public int getExceptionExitStatus() throws UnsupportedMessageException { if (kind != ExceptionType.EXIT) throw UnsupportedMessageException.create(); return 7; }
}
