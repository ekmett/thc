// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoreForeignArtifactsTest {
    private val label = mapOf("isInitializer" to false, "unit" to "pkg", "module" to "M", "name" to "exit")
    private val stubs = mapOf("header" to "", "source" to "", "initializers" to emptyList<Any>(),
        "finalizers" to listOf(label))
    private val foreign = mapOf("schema" to 1L, "execution" to "not-linked", "stubs" to stubs,
        "files" to emptyList<Any>())
    private val module = mapOf("schema" to 2L, "ghc" to "9.14.1", "unit" to "pkg", "module" to "M",
        "foreign" to foreign, "bindings" to emptyList<Any>(), "constructors" to emptyList<Any>())

    @Test fun finalizersAndRawObjectContentsAreArchiveOnly() {
        CoreForeignArtifacts.validateArchive(module)
        val file = mapOf("language" to "RawObject", "source" to "\u0000\u00ff\u03bb\n", "extension" to ".o")
        val withFile = module + ("foreign" to (foreign + mapOf("stubs" to null, "files" to listOf(file))))
        val roundTrip = Json.parse(Json.stringify(withFile)) as Map<*, *>
        assertEquals(withFile, roundTrip)
        CoreForeignArtifacts.validateArchive(roundTrip)
        for (value in listOf(module, withFile)) {
            val error = assertThrows(IllegalArgumentException::class.java) { CoreModules.merge(listOf(value)) }
            assertTrue(error.message!!.contains("Unsupported foreign code/registration for pkg:M"))
        }
    }

    @Test fun malformedOrDowngradedForeignArtifactsFailClosed() {
        val variants = listOf(
            module + ("schema" to 1L), module - "foreign", module + ("schema" to 2.5),
            module + ("foreign" to (foreign + ("execution" to "linked"))),
            module + ("foreign" to (foreign + ("schema" to 1.5))),
            module + ("foreign" to (foreign + ("unknown" to true))),
            module + ("foreign" to (foreign + ("stubs" to null))),
            module + ("foreign" to (foreign + ("files" to listOf("missing content")))),
            module + ("foreign" to (foreign + ("stubs" to (stubs +
                ("finalizers" to listOf(label + ("isInitializer" to true))))))))
        for (value in variants)
            assertThrows(IllegalArgumentException::class.java) { CoreForeignArtifacts.validateArchive(value) }
    }

    @Test fun ordinarySchemaOneHasNoForeignRegistrationObligations() {
        for (schema in listOf(1, 1L))
            CoreForeignArtifacts.requireExecutable(mapOf("schema" to schema))
        assertThrows(IllegalArgumentException::class.java) {
            CoreForeignArtifacts.validateArchive(mapOf("schema" to 1L, "foreign" to null))
        }
    }
}
