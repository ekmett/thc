// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

/** Metadata models only: no C product in this test is linked or executed. */
class ManagedImportTypeTest {
    private Map<String, Object> identity(String name) {
        return map("unit", "ghc-internal", "module", "GHC.Internal.Types", "occurrence", name, "namespace", "type");
    }
    private Map<String, Object> tycon(String name, Object... arguments) { return map("kind", "tycon", "name", identity(name), "arguments", list(arguments)); }
    private final Map<String, Object> type = tycon("Type");
    private Map<String, Object> variable(Object index) { return map("kind", "bound-variable", "index", index); }
    private Map<String, Object> forall(Object body) { return forall(body, type); }
    private Map<String, Object> forall(Object body, Object kind) { return map("kind", "forall", "binderKind", kind, "body", body); }
    private Map<String, Object> pointerFunction(Object argument) {
        return map("kind", "function", "multiplicity", tycon("Many"), "argument",
            map("kind", "tycon", "name", with(identity("Ptr"), "module", "GHC.Internal.Ptr"), "arguments", list(argument)),
            "result", tycon("IO", tycon("Unit")));
    }
    private Map<String, Object> quantified() { return forall(pointerFunction(variable(0L))); }
    private Map<String, Object> imported(String name, String convention, String symbol, Object declared, Object normalized) {
        return map("binder", map("unit", "fixture", "module", "Imports", "occurrence", name, "namespace", "value"),
            "header", convention.equals("capi") ? "stdlib.h" : null, "symbol", "free", "unit", null, "isFunction", true,
            "convention", convention, "safety", "unsafe", "declaredType", declared, "normalizedType", normalized,
            "normalizationRole", "representational", "emitted", map("symbol", symbol, "unit", null,
                "convention", convention, "safety", "unsafe", "arguments", list("AddrRep", "void"), "result", list("void")));
    }
    private Map<String, Object> module() { return module(quantified()); }
    private Map<String, Object> module(Object declared) { return module(declared, declared); }
    private Map<String, Object> module(Object declared, Object normalized) {
        var foreign = map("schema", 1L, "execution", "not-linked", "files", List.of(),
            "stubs", map("header", "", "source", "void capi_wrapper(void *p) { free(p); }", "initializers", List.of(), "finalizers", List.of()));
        return map("schema", 2L, "ghc", "9.14.1", "unit", "fixture", "module", "Imports", "foreign", foreign,
            "bindings", List.of(), "staticForeignImportStubs", map("schema", 1L, "scope", "retained-static-import-products",
                "execution", "not-linked", "profile", "ghc-9.14.1-thc-only-static-c-imports-v1", "unit", "fixture", "module", "Imports",
                "status", "verified", "wordBits", 64L, "expectedForeign", foreign,
                "imports", list(imported("c_free", "ccall", "free", declared, normalized), imported("capi_free", "capi", "capi_wrapper", declared, normalized)),
                "expectedCalls", List.of()));
    }
    private ManagedImportAdmission read(Map<?, ?> module) { return ManagedImportAdmission.read(module, true); }
    private Map<String,Object> address() {
        var result = tycon("IO", map("kind", "tycon", "name", with(identity("Unit"), "module", "GHC.Internal.Tuple"), "arguments", List.of()));
        var function = with(pointerFunction(tycon("Unit")), "result", result);
        var callbackType = map("kind", "tycon", "name", with(identity("FunPtr"), "module", "GHC.Internal.Ptr"), "arguments", list(function));
        return map("binder", map("unit", "fixture", "module", "Imports", "occurrence", "cleanup", "namespace", "value"),
            "header", null, "symbol", "cleanup", "isFunction", true, "convention", "ccall", "normalizationRole", "representational",
            "declaredType", callbackType, "normalizedType", callbackType, "callback", map("arguments", list("AddrRep"), "result", "void"));
    }
    private Map<String,Object> withAddresses() {
        var original = module();
        return with(original, "staticForeignImportStubs", with((Map<?,?>) original.get("staticForeignImportStubs"), "schema", 2L, "addresses", list(address())));
    }
    @Test void stubsOnlyAddressProofRetainsItsExactNominalInventory() {
        var original = withAddresses();
        assertNotNull(read(original));
        assertFalse(original.containsKey("staticForeignImports"));
        assertNotNull(read((Map<?,?>) Json.parse(Json.stringify(original))));
    }
    private void rejectInvalidSelectedAddresses(Map<String,Object> original) {
        var selected = (Map<?,?>) original.get("staticForeignImportStubs");
        for (var addresses : list(null, List.of(), list("not an address"),
                list(with(address(), "binder", with((Map<?,?>) address().get("binder"), "unit", "other"))),
                list(with(address(), "callback", map("arguments", list("AddrRep", "AddrRep"), "result", "void"))),
                list(with(address(), "normalizedType", tycon("Int")))))
            assertThrows(IllegalArgumentException.class, () -> read(with(original, "staticForeignImportStubs", with(selected, "addresses", addresses))));
    }
    @Test void malformedStubsOnlyAddressInventoryIsRejected() {
        rejectInvalidSelectedAddresses(withAddresses());
    }
    @Test void anotherImportsFieldCannotHideMalformedSelectedStubAddresses() {
        var original = withAddresses();
        for (var parallel : List.of(module().get("staticForeignImportStubs"), original.get("staticForeignImportStubs")))
            rejectInvalidSelectedAddresses(with(original, "staticForeignImports", parallel));
    }
    @Test void quantifiedPointerImportsRetainTheirOriginalTypesAndScalarAbi() {
        var original = module(); var admission = Objects.requireNonNull(read(original));
        assertSame(original, admission.getModule());
        assertNotNull(read((Map<?, ?>) Json.parse(Json.stringify(original))));
        assertNotNull(read(module(pointerFunction(tycon("Unit")))));
        assertNotNull(ManagedImportAdmission.read(without(original, "bindings"), false));
        assertThrows(IllegalArgumentException.class, () -> read(with(original,
                "bindings", list(map("foreignCall", "malformed")))));
    }
    @Test void nestedBindersScopeTheirBodyButNotTheirOwnKind() {
        assertNotNull(read(module(forall(forall(pointerFunction(variable(1L)))))));
        // An outer variable is also in scope in an inner binder's kind.
        assertNotNull(read(module(forall(forall(pointerFunction(variable(0L)), variable(0L))))));
        for (var invalid : list(variable(0L), forall(pointerFunction(variable(1L))),
                forall(pointerFunction(variable(0L)), variable(0L)), forall(forall(pointerFunction(variable(2L)))))) {
            assertThrows(IllegalArgumentException.class, () -> read(module(invalid)));
            assertThrows(IllegalArgumentException.class, () -> read(module(quantified(), invalid)));
        }
    }
    @Test void boundVariablesRequireExactIntegralIndicesAndRecordFields() {
        for (var index : list(null, -1L, Long.MAX_VALUE, true, 0.0, "0"))
            assertThrows(IllegalArgumentException.class, () -> read(module(forall(pointerFunction(variable(index))))));
        assertNotNull(read(module(forall(pointerFunction(variable(0))))));
        for (var invalid : list(with(variable(0L), "representation", "AddrRep"), map("kind", "bound-variable")))
            assertThrows(IllegalArgumentException.class, () -> read(module(forall(pointerFunction(invalid)))));
    }
    @Test void malformedKindAndTypeRecordsCannotBeErasedByForall() {
        for (var invalid : list(null, "Type", map("kind", "unknown"), with(type, "arguments", "ignored"),
                with(type, "name", with(identity("Type"), "namespace", "unknown")), map("kind", "bound-variable", "index", 0L)))
            assertThrows(IllegalArgumentException.class, () -> read(module(forall(pointerFunction(variable(0L)), invalid))));
        for (var invalid : list(without(quantified(), "binderKind"), with(quantified(), "representation", "AddrRep"),
                forall(map("kind", "cast", "type", pointerFunction(variable(0L))))))
            assertThrows(IllegalArgumentException.class, () -> read(module(invalid)));
    }
    @Test void quantifiedTypesDoNotWaiveImportOwnerRoleOrEmittedCarrierChecks() {
        var original = module(); var proof = (Map<?, ?>) original.get("staticForeignImportStubs");
        var imports = (List<?>) proof.get("imports"); var direct = (Map<?, ?>) imports.getFirst();
        var emitted = (Map<?, ?>) direct.get("emitted");
        var changes = list(with(direct, "normalizationRole", "phantom"),
            with(direct, "binder", with((Map<?, ?>) direct.get("binder"), "unit", "other")),
            with(direct, "emitted", with(emitted, "unit", "other")), with(direct, "emitted", with(emitted, "symbol", "not_free")),
            with(direct, "emitted", with(emitted, "arguments", list("BoxedRep (Just Lifted)", "void"))),
            with(direct, "emitted", with(emitted, "arguments", list("AddrRep"))), with(direct, "emitted", with(emitted, "result", list("AddrRep"))));
        for (var bad : changes) assertThrows(IllegalArgumentException.class, () ->
            read(with(original, "staticForeignImportStubs", with(proof, "imports", list(bad, imports.getLast())))));
    }
}
