// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Language
import thc.executionContext

class CoreFunctionIdentityTest {
    private fun module(unit: String): Map<String, Any?> {
        val argument = mapOf("id" to "x", "name" to "x", "lifted" to false)
        val entry = "$unit:Shared.entry"
        return mapOf("schema" to 1, "ghc" to "9.14.1", "unit" to unit, "module" to "Shared",
            "bindings" to listOf(
                mapOf("id" to entry, "name" to "entry", "lifted" to true, "arity" to 1,
                    "expr" to listOf("lam", listOf(argument), listOf("var", "x"))),
                mapOf("id" to "$unit:Shared.\$wentry_r1", "name" to "\$wentry", "lifted" to true,
                    "arity" to 1, "expr" to listOf("lam", listOf(argument), listOf("var", "x"))),
                mapOf("id" to "$unit:Shared.alias", "name" to "alias", "lifted" to true,
                    "expr" to listOf("var", entry))),
            "constructors" to emptyList<Any?>())
    }

    @Test fun rootsRetainExactGhcOwnerAcrossMergedUnitsWithoutSourcePaths() {
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val merged = CoreModules.merge(listOf(module("pkg-a"), module("pkg-b")))
                for (program in listOf(Program(language, merged), BytecodeProgram(language, merged))) {
                    for (unit in listOf("pkg-a", "pkg-b")) {
                        val entry = program.entryTarget("$unit:Shared.entry")
                        assertEquals("lambda x", entry.rootNode.name)
                        assertEquals(CoreFunctionIdentity("$unit:Shared.entry", unit, "Shared", "entry"),
                            (entry.rootNode as GuestRoot).coreIdentity)
                        val alias = program.entryTarget("$unit:Shared.alias")
                        if (alias !== entry) assertNull((alias.rootNode as GuestRoot).coreIdentity)
                        assertEquals("$unit:Shared.entry", (entry.rootNode as GuestRoot).coreIdentity!!.bindingId,
                            "An alias must not claim another binding's target")
                        val worker = program.entryTarget("$unit:Shared.\$wentry_r1")
                        assertEquals("\$wentry_r1", (worker.rootNode as GuestRoot).coreIdentity!!.occurrence,
                            "Post-Tidy local worker IDs retain their unique suffix")
                    }
                }
            } finally { context.leave() }
        }
    }

    @Test fun interfaceFragmentsAndSyntheticMainDoNotInventOwners() {
        val module = module("pkg-a")
        val entry = (module["bindings"] as List<*>).first() as Map<String, Any?>
        assertNull(CoreFunctionIdentity.from(module + ("unit" to "dependency-closure") +
            ("module" to "THC.InterfaceClosure"), entry))
        assertNull(CoreFunctionIdentity.from(module, entry + ("id" to "main::Main.main")))
    }
}
