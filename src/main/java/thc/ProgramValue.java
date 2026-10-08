// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.interop.*;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import thc.runtime.*;

/** Entry views of one context-owned program, sharing its native roots and lazy cells. */
@ExportLibrary(InteropLibrary.class)
public final class ProgramValue implements TruffleObject {
    private final CoreUnitProgram program;
    private final Language language;
    private final Language.State owner;
    private final boolean diagnostic;
    private final ConcurrentHashMap<View,EntryValue> entries = new ConcurrentHashMap<>();

    ProgramValue(CoreUnitProgram program, Language language, Language.State owner, boolean diagnostic) {
        this.program = program; this.language = language; this.owner = owner; this.diagnostic = diagnostic;
    }
    private void checkOwner() {
        if (Language.currentState(null) != owner || owner.getEnv().getContext().isClosed())
            throw new RuntimeFault("Program belongs to another or closed THC context");
    }
    record View(String entry, boolean ioMain, String shutdown) {
        View {
            Objects.requireNonNull(entry, "Expected entry name");
            if (shutdown != null && (!ioMain || shutdown.equals(entry) || shutdown.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))))
                throw new IllegalArgumentException("Executable shutdown requires a distinct IO entry");
        }
    }
    /** Signature admission is inert; construction may initialize demanded native dependencies. */
    record EntryPlan(View view, int arity, CoreRepresentation io, CoreRepresentation shutdown,
            List<CoreRepresentation> inputs, CoreRepresentation result) {
        @SuppressWarnings("unchecked")
        static EntryPlan select(CoreUnitProgram program, View view, boolean diagnostic) {
            if (view.ioMain() && diagnostic) throw new IllegalArgumentException("IO main requires strict unsupported-Core rejection");
            var bindings = program.signatureBindings(view.entry());
            var selected = bindings.getFirst(); // signatureBindings puts the selected identity first.
            var expression = (List<Object>) selected.get("expr");
            var io = view.ioMain() ? CoreRepresentations.ioMainResult(selected, bindings) : null;
            CoreRepresentation shutdown = null;
            if (view.shutdown() != null) {
                var definitions = program.signatureBindings(view.shutdown());
                shutdown = CoreRepresentations.ioMainResult(definitions.getFirst(), definitions);
            }
            List<CoreRepresentation> inputs = null;
            CoreRepresentation result = null;
            int arity = ((Number) selected.get("arity")).intValue();
            if (io == null) {
                var signature = CoreHostSignature.select(selected, bindings);
                if (signature != null) { inputs = signature.getInputs(); result = signature.getResult(); }
                else if (arity == 0) {
                    result = CoreRepresentations.binder(selected).refine(CoreRepresentations.expression(expression));
                    inputs = List.of();
                }
            }
            if (inputs != null) {
                for (var proof : inputs) HostAbi.require(proof);
                HostAbi.require(result);
            }
            return new EntryPlan(view, arity, io, shutdown, inputs, result);
        }
        EntryValue create(CoreUnitProgram program, Language language) {
            return new EntryValue(program, view.entry(), arity, null, io, language, view.shutdown(), shutdown,
                program.getCapturesContinuations() && program.contains(CoreSignalForeign.dispatcher), inputs, result);
        }
    }
    @ExportMessage public boolean hasMembers() { checkOwner(); return true; }
    @ExportMessage public Object getMembers(boolean includeInternal) { checkOwner(); return new MemberNames(new String[]{"entry"}); }
    @ExportMessage public boolean isMemberInvocable(String member) { checkOwner(); return "entry".equals(member); }
    @ExportMessage @TruffleBoundary public Object invokeMember(String member, Object[] arguments)
            throws UnknownIdentifierException, ArityException, UnsupportedTypeException {
        checkOwner();
        if (!"entry".equals(member)) throw UnknownIdentifierException.create(member);
        if (arguments.length < 1 || arguments.length > 3) throw ArityException.create(1, 3, arguments.length);
        var interop = InteropLibrary.getUncached();
        View view;
        try {
            view = new View(interop.asString(arguments[0]), arguments.length > 1 && interop.asBoolean(arguments[1]),
                arguments.length > 2 ? interop.asString(arguments[2]) : null);
        } catch (UnsupportedMessageException failure) {
            throw UnsupportedTypeException.create(arguments, "entry expects a name, optional IO-main Boolean and optional shutdown name");
        }
        var existing = entries.get(view);
        if (existing != null) return existing;
        // Never retain a view-cache lock while cold admission initializes native
        // libraries. Only the published winner exposes compilation/IO lifecycle state.
        var created = EntryPlan.select(program, view, diagnostic).create(program, language);
        existing = entries.putIfAbsent(view, created);
        return existing == null ? created : existing;
    }
    @ExportMessage public boolean hasLanguage() { return true; }
    @ExportMessage public Class<? extends TruffleLanguage<?>> getLanguage() { return Language.class; }
    @ExportMessage public String toDisplayString(boolean allowSideEffects) { return "THC loaded program"; }
}
