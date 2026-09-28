// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.interop.*;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/** Cold checked-protocol failure, eligible for the genuine Haskell ForeignException bridge. */
@ExportLibrary(InteropLibrary.class)
public final class InteropFailure extends AbstractTruffleException {
    private final InteropException original;
    private InteropFailure(InteropException original) { super(original.getMessage()); this.original = original; }
    @TruffleBoundary public static InteropFailure create(InteropException original) { return new InteropFailure(original); }
    public InteropException getOriginal() { return original; }
    @ExportMessage boolean isException() { return true; }
    @ExportMessage RuntimeException throwException() { throw this; }
    @ExportMessage ExceptionType getExceptionType() { return ExceptionType.RUNTIME_ERROR; }
    @ExportMessage boolean hasExceptionMessage() { return true; }
    @ExportMessage Object getExceptionMessage() { return getMessage() == null ? original.getClass().getSimpleName() : getMessage(); }
    @ExportMessage boolean hasMetaObject() { return true; }
    @ExportMessage Object getMetaObject() { return new Kind(original.getClass()); }

    @ExportLibrary(InteropLibrary.class)
    static final class Kind implements TruffleObject {
        private final Class<?> kind;
        Kind(Class<?> kind) { this.kind = kind; }
        @ExportMessage boolean isMetaObject() { return true; }
        @ExportMessage Object getMetaQualifiedName() { return kind.getName(); }
        @ExportMessage @TruffleBoundary Object getMetaSimpleName() { return kind.getSimpleName(); }
        @ExportMessage boolean isMetaInstance(Object value) { return value instanceof InteropFailure failure && kind.isInstance(failure.original); }
    }
}
