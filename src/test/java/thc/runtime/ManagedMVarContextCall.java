// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/** Test-only Polyglot entry; the bodies and Box constructor remain genuine exported GHC Core. */
@ExportLibrary(InteropLibrary.class)
final class ManagedMVarContextCall implements TruffleObject {
    private final ExecutableProgram program;
    private final String name;
    private final Object[] arguments;
    ManagedMVarContextCall(ExecutableProgram program, String name, Object[] arguments) { this.program = program; this.name = name; this.arguments = arguments; }
    @ExportMessage boolean isExecutable() { return true; }
    @ExportMessage Object execute(Object[] hostArguments) {
        if (hostArguments.length != 0) throw new IllegalStateException("Check failed.");
        return Calls.target(program.hostEntryTarget(arguments.length), new Object[]{program.entryValue(name), arguments});
    }
}
