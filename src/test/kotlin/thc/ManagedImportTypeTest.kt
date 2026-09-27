// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Metadata models only: no C product in this test is linked or executed. */
class ManagedImportTypeTest {
    private fun identity(name: String, namespace: String = "type") = mapOf(
        "unit" to "ghc-internal", "module" to "GHC.Internal.Types", "occurrence" to name, "namespace" to namespace)
    private fun tycon(name: String, vararg arguments: Any?) = mapOf(
        "kind" to "tycon", "name" to identity(name), "arguments" to arguments.toList())
    private val type = tycon("Type")
    private fun variable(index: Any?) = mapOf("kind" to "bound-variable", "index" to index)
    private fun forall(body: Any?, kind: Any? = type) = mapOf("kind" to "forall", "binderKind" to kind, "body" to body)
    private fun pointerFunction(argument: Any?) = mapOf("kind" to "function", "multiplicity" to tycon("Many"),
        "argument" to mapOf("kind" to "tycon", "name" to (identity("Ptr") + ("module" to "GHC.Internal.Ptr")),
            "arguments" to listOf(argument)), "result" to tycon("IO", tycon("Unit")))
    private val quantified get() = forall(pointerFunction(variable(0L)))

    private fun module(declared: Any? = quantified, normalized: Any? = declared): Map<String, Any?> {
        val foreign = mapOf("schema" to 1L, "execution" to "not-linked", "files" to emptyList<Any>(),
            "stubs" to mapOf("header" to "", "source" to "void capi_wrapper(void *p) { free(p); }",
                "initializers" to emptyList<Any>(), "finalizers" to emptyList<Any>()))
        fun imported(name: String, convention: String, symbol: String) = mapOf(
            "binder" to mapOf("unit" to "fixture", "module" to "Imports", "occurrence" to name, "namespace" to "value"),
            "header" to if (convention == "capi") "stdlib.h" else null,
            "symbol" to "free", "unit" to null, "isFunction" to true, "convention" to convention, "safety" to "unsafe",
            "declaredType" to declared, "normalizedType" to normalized, "normalizationRole" to "representational",
            "emitted" to mapOf("symbol" to symbol, "unit" to null, "convention" to convention, "safety" to "unsafe",
                "arguments" to listOf("AddrRep", "void"), "result" to listOf("void")))
        return mapOf("schema" to 2L, "ghc" to "9.14.1", "unit" to "fixture", "module" to "Imports",
            "foreign" to foreign, "bindings" to emptyList<Any>(), "staticForeignImportStubs" to mapOf(
                "schema" to 1L, "scope" to "retained-static-import-products", "execution" to "not-linked",
                "profile" to "ghc-9.14.1-thc-only-static-c-imports-v1", "unit" to "fixture", "module" to "Imports",
                "status" to "verified", "wordBits" to 64L, "expectedForeign" to foreign,
                "imports" to listOf(imported("c_free", "ccall", "free"), imported("capi_free", "capi", "capi_wrapper")),
                "expectedCalls" to emptyList<Any>()))
    }

    @Test fun quantifiedPointerImportsRetainTheirOriginalTypesAndScalarAbi() {
        val original = module()
        val admission = ManagedImportAdmission.read(original)!!
        assertSame(original, admission.module)
        assertNotNull(ManagedImportAdmission.read(Json.parse(Json.stringify(original)) as Map<*, *>))
        assertNotNull(ManagedImportAdmission.read(module(pointerFunction(tycon("Unit")))))
        assertNotNull(ManagedImportAdmission.read(original - "bindings", completeBindings = false))
    }

    @Test fun nestedBindersScopeTheirBodyButNotTheirOwnKind() {
        assertNotNull(ManagedImportAdmission.read(module(forall(forall(pointerFunction(variable(1L)))))))
        // An outer variable is also in scope in an inner binder's kind.
        assertNotNull(ManagedImportAdmission.read(module(forall(forall(pointerFunction(variable(0L)), variable(0L))))))
        for (invalid in listOf(variable(0L), forall(pointerFunction(variable(1L))),
            forall(pointerFunction(variable(0L)), variable(0L)),
            forall(forall(pointerFunction(variable(2L)))))) {
            assertThrows(IllegalArgumentException::class.java) { ManagedImportAdmission.read(module(invalid)) }
            assertThrows(IllegalArgumentException::class.java) { ManagedImportAdmission.read(module(quantified, invalid)) }
        }
    }

    @Test fun boundVariablesRequireExactIntegralIndicesAndRecordFields() {
        for (index in listOf(null, -1L, Long.MAX_VALUE, true, 0.0, "0"))
            assertThrows(IllegalArgumentException::class.java) {
                ManagedImportAdmission.read(module(forall(pointerFunction(variable(index)))))
            }
        assertNotNull(ManagedImportAdmission.read(module(forall(pointerFunction(variable(0))))))
        for (invalid in listOf(variable(0L) + ("representation" to "AddrRep"), mapOf("kind" to "bound-variable")))
            assertThrows(IllegalArgumentException::class.java) {
                ManagedImportAdmission.read(module(forall(pointerFunction(invalid))))
            }
    }

    @Test fun malformedKindAndTypeRecordsCannotBeErasedByForall() {
        for (invalid in listOf(null, "Type", mapOf("kind" to "unknown"),
            type + ("arguments" to "ignored"), type + ("name" to (identity("Type") + ("namespace" to "unknown"))),
            mapOf("kind" to "bound-variable", "index" to 0L)))
            assertThrows(IllegalArgumentException::class.java) {
                ManagedImportAdmission.read(module(forall(pointerFunction(variable(0L)), invalid)))
            }
        for (invalid in listOf(quantified - "binderKind", quantified + ("representation" to "AddrRep"),
            forall(mapOf("kind" to "cast", "type" to pointerFunction(variable(0L))))))
            assertThrows(IllegalArgumentException::class.java) { ManagedImportAdmission.read(module(invalid)) }
    }

    @Test fun quantifiedTypesDoNotWaiveImportOwnerRoleOrEmittedCarrierChecks() {
        val original = module()
        val proof = original["staticForeignImportStubs"] as Map<String, Any?>
        val imports = proof["imports"] as List<Map<String, Any?>>
        val direct = imports.first()
        val emitted = direct["emitted"] as Map<String, Any?>
        val changes = listOf(
            direct + ("normalizationRole" to "phantom"),
            direct + ("binder" to ((direct["binder"] as Map<*, *>) + ("unit" to "other"))),
            direct + ("emitted" to (emitted + ("unit" to "other"))),
            direct + ("emitted" to (emitted + ("symbol" to "not_free"))),
            direct + ("emitted" to (emitted + ("arguments" to listOf("BoxedRep (Just Lifted)", "void")))),
            direct + ("emitted" to (emitted + ("arguments" to listOf("AddrRep")))),
            direct + ("emitted" to (emitted + ("result" to listOf("AddrRep")))))
        for (bad in changes) assertThrows(IllegalArgumentException::class.java) {
            ManagedImportAdmission.read(original + ("staticForeignImportStubs" to (proof + ("imports" to listOf(bad, imports.last())))))
        }
    }
}
