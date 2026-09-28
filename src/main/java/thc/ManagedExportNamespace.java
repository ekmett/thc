// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.interop.*;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import java.util.Map;
import java.util.function.Supplier;

/** Read-only member names retain exact GHC unit/module and external symbol identities. */
@ExportLibrary(InteropLibrary.class)
public final class ManagedExportNamespace implements TruffleObject {
    private final ManagedExportRegistry registry;
    private final String description;
    private final Supplier<? extends Map<String,?>> members;
    public ManagedExportNamespace(ManagedExportRegistry registry, String description, Supplier<? extends Map<String,?>> members) { this.registry = registry; this.description = description; this.members = members; }
    @ExportMessage public boolean hasMembers() { registry.checkOwner(); return true; }
    @ExportMessage @TruffleBoundary public Object getMembers(boolean includeInternal) { registry.checkOwner(); return new MemberNames(members.get().keySet().toArray(String[]::new)); }
    @ExportMessage @TruffleBoundary public boolean isMemberReadable(String member) { registry.checkOwner(); return members.get().containsKey(member); }
    @ExportMessage @TruffleBoundary public Object readMember(String member) throws UnknownIdentifierException {
        registry.checkOwner(); Object value = members.get().get(member); if (value == null) throw UnknownIdentifierException.create(member); return value;
    }
    @ExportMessage public boolean isScope() { registry.checkOwner(); return true; }
    @ExportMessage public boolean hasLanguage() { return true; }
    @ExportMessage public Class<? extends TruffleLanguage<?>> getLanguage() { return Language.class; }
    @ExportMessage public String toDisplayString(boolean allowSideEffects) { return description; }
}
