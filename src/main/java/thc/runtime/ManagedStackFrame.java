// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ManagedStackFrame {
    private final String functionName;
    private final ManagedStackSource location;
    private final ManagedStackLocationKind locationKind;
    private final List<ManagedStackSource> sections;
    private final List<ManagedStackNote> notes;
    private final ManagedStackFunctionIdentity coreIdentity;
    public ManagedStackFrame(String functionName, ManagedStackSource location, ManagedStackLocationKind locationKind,
                             List<ManagedStackSource> sections, List<ManagedStackNote> notes,
                             ManagedStackFunctionIdentity coreIdentity) {
        this.functionName = functionName; this.location = location; this.locationKind = locationKind;
        this.sections = Collections.unmodifiableList(new ArrayList<>(sections));
        this.notes = Collections.unmodifiableList(new ArrayList<>(notes)); this.coreIdentity = coreIdentity;
    }
    public String getFunctionName() { return functionName; }
    public ManagedStackSource getLocation() { return location; }
    public ManagedStackLocationKind getLocationKind() { return locationKind; }
    /** Bytecode sections are most-to-least concrete; AST notes retain exporter order. */
    public List<ManagedStackSource> getSections() { return sections; }
    public List<ManagedStackNote> getNotes() { return notes; }
    public ManagedStackFunctionIdentity getCoreIdentity() { return coreIdentity; }
}
