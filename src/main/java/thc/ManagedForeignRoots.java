// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;
import thc.runtime.ExecutableProgram;
import thc.runtime.RuntimeFault;

/** Context-owned GHC ForeignExports.c roots and their declared C entrypoints.
 * Registration prepares closures, but never evaluates an export. */
public final class ManagedForeignRoots {
    private final Language.State owner;
    private final IdentityHashMap<ExecutableProgram,Map<String,Object>> programs = new IdentityHashMap<>();
    private volatile boolean closed;
    public ManagedForeignRoots(Language.State owner) { this.owner = owner; }
    private void checkOwner() {
        if (closed || Language.currentState(null) != owner) throw new RuntimeFault("Foreign export roots belong to another or closed THC context");
    }
    public void register(ExecutableProgram program, Language language, List<ManagedExportAdmission> registrations,
            List<ManagedExportSignature> checked) {
        checkOwner();
        owner.getNativeCallbacks().registerStaticExports(program, language, checked);
        try { retain(program, registrations); }
        catch (Throwable failure) { release(program); throw failure; }
    }
    public void retain(ExecutableProgram program, List<ManagedExportAdmission> registrations) {
        synchronized (this) {
            checkOwner();
            if (programs.containsKey(program)) throw new IllegalStateException("Foreign exports already registered for this program");
        }
        if (registrations.isEmpty()) return;
        // entryValue reads the initialized global slot; it never forces a thunk.
        // Lazy preparation must not run while holding the registry monitor.
        var roots = new LinkedHashMap<String,Object>();
        for (var registration : registrations) for (var export : registration.getExports()) roots.put(export.binder(), program.entryValue(export.binder()));
        synchronized (this) { checkOwner(); programs.put(program, roots); }
    }
    public void release(ExecutableProgram program) {
        owner.getNativeCallbacks().unregisterStaticExports(program);
        synchronized (this) { checkOwner(); programs.remove(program); }
    }
    public synchronized Map<String,Object> retained(ExecutableProgram program) { return programs.get(program); }
    public synchronized List<ExecutableProgram> programs() { return new ArrayList<>(programs.keySet()); }
    public synchronized int size() { return programs.size(); }
    public synchronized void close() { closed = true; programs.clear(); }
}
