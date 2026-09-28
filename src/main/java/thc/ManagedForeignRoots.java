// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;
import thc.runtime.ExecutableProgram;
import thc.runtime.RuntimeFault;

/** GHC ForeignExports.c roots exported closures for the lifetime of their module.
 * This registry does not install a native entrypoint or evaluate an export. */
public final class ManagedForeignRoots {
    private final Language.State owner;
    private final IdentityHashMap<ExecutableProgram,Map<String,Object>> programs = new IdentityHashMap<>();
    private boolean closed;
    public ManagedForeignRoots(Language.State owner) { this.owner = owner; }
    public synchronized void retain(ExecutableProgram program, List<ManagedExportAdmission> registrations) {
        if (closed || Language.currentState(null) != owner) throw new RuntimeFault("Foreign export roots belong to another or closed THC context");
        if (registrations.isEmpty()) return;
        if (programs.containsKey(program)) throw new IllegalStateException("Foreign exports already registered for this program");
        // entryValue reads the initialized global slot; it never forces a thunk.
        var roots = new LinkedHashMap<String,Object>();
        for (var registration : registrations) for (var export : registration.getExports()) roots.put(export.binder(), program.entryValue(export.binder()));
        programs.put(program, roots);
    }
    public synchronized Map<String,Object> retained(ExecutableProgram program) { return programs.get(program); }
    public synchronized List<ExecutableProgram> programs() { return new ArrayList<>(programs.keySet()); }
    public synchronized int size() { return programs.size(); }
    public synchronized void close() { closed = true; programs.clear(); }
}
