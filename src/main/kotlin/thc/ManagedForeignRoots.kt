// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc

import thc.runtime.ExecutableProgram
import thc.runtime.RuntimeFault
import java.util.IdentityHashMap

/** GHC ForeignExports.c roots exported closures for the lifetime of their module.
 * This registry does not install a native entrypoint or evaluate an export. */
internal class ManagedForeignRoots(private val owner: Language.State) {
    private val programs = IdentityHashMap<ExecutableProgram, Map<String, Any?>>()
    private var closed = false

    @Synchronized fun retain(program: ExecutableProgram, registrations: List<ManagedExportAdmission>) {
        if (closed || Language.currentState() !== owner)
            throw RuntimeFault("Foreign export roots belong to another or closed THC context")
        if (registrations.isEmpty()) return
        check(program !in programs) { "Foreign exports already registered for this program" }
        // entryValue reads the initialized global slot; it never forces a thunk.
        val roots = registrations.flatMap { it.exports }.associate { it.binder to program.entryValue(it.binder) }
        programs[program] = roots
    }

    @Synchronized internal fun retained(program: ExecutableProgram): Map<String, Any?>? = programs[program]
    @Synchronized internal fun programs(): List<ExecutableProgram> = programs.keys.toList()
    @Synchronized internal fun size(): Int = programs.size
    @Synchronized fun close() { closed = true; programs.clear() }
}
