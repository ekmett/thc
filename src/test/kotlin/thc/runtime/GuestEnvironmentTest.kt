// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.EnvironmentAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import thc.Language

class GuestEnvironmentTest {
    private fun context() = Context.newBuilder("thc").allowNativeAccess(true)
        .allowEnvironmentAccess(EnvironmentAccess.NONE).environment("THC_INITIAL", "lambda-\u03bb")
        .allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun inside(body: (Language) -> Unit) {
        assumeTrue(System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in setOf("amd64", "x86_64"))
        context().use { context ->
            context.initialize("thc"); context.enter()
            try { body(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
        }
    }
    private fun string(value: String): ManagedAddress {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return Language.currentState().nativeAllocations.malloc(bytes.size.toLong() + 1).also { address ->
            for (index in bytes.indices) address.writeWord8(index.toLong(), bytes[index].toLong())
            address.writeWord8(bytes.size.toLong(), 0)
        }
    }
    private fun text(address: ManagedAddress) =
        ByteArray(address.cStringLength().toInt()) { address.readWord8(it.toLong()).toByte() }.toString(Charsets.UTF_8)
    private fun entries(vector: ManagedAddress): List<String> = buildList {
        var index = 0L
        while (true) {
            val address = vector.readAddressElementIndex(index++)
            if (address === ManagedAddress.nullAddress()) break
            add(text(address))
        }
    }
    private fun scalar(primitive: String?, evaluated: Boolean = true): Map<String, Any?> = mapOf(
        "kind" to when (primitive) { null -> "void"; "AddrRep" -> "address"; "BoxedRep (Just Lifted)" -> "closure"; else -> "long" },
        "primReps" to listOfNotNull(primitive), "evaluated" to evaluated)
    private val closure = scalar("BoxedRep (Just Lifted)")
    private fun tuple(operation: EnvironmentOp, evaluated: Boolean = false) = mapOf(
        "kind" to "unknown", "primReps" to listOf(operation.result), "evaluated" to evaluated,
        "aggregate" to "unboxed-tuple", "components" to listOf(scalar(null), scalar(operation.result)))
    private fun declaration(operation: EnvironmentOp): Map<String, Any?> = mapOf(
        "schema" to 1, "target" to mapOf("kind" to "static", "symbol" to operation.symbol,
            "unit" to "ghc-internal", "isFunction" to true), "convention" to "ccall", "safety" to "unsafe",
        "arity" to operation.arguments.size, "suppliedArity" to operation.arguments.size,
        "argumentReps" to operation.arguments.map { scalar(it, false) }, "resultRep" to tuple(operation))
    private fun module(): Map<String, Any?> {
        // Explicitly synthetic consumers. EnvironmentFullCore exercises genuine
        // original installed System.Environment definitions independently.
        val bindings = EnvironmentOp.entries.map { operation ->
            val name = operation.symbol
            val formals = operation.arguments.mapIndexed { index, rep ->
                mapOf("id" to "$name-$index", "lifted" to false, "rep" to scalar(rep)) }
            val call = listOf("app", listOf("var", "$name-synthetic-fcall", mapOf("rep" to closure)),
                formals.map { listOf("var", it["id"], mapOf("rep" to it["rep"])) }, formals.map { false },
                false, false, mapOf("rep" to tuple(operation), "foreignCall" to declaration(operation)))
            val fields = listOf(scalar(null), scalar(operation.result))
            val ids = listOf("$name-state", "$name-result")
            val body = listOf("case", call, "$name-tuple", listOf(listOf("data", "tuple2", ids,
                listOf("var", ids[1], mapOf("rep" to fields[1])), mapOf("binders" to fields.mapIndexed { index, rep ->
                    mapOf("id" to ids[index], "lifted" to false, "rep" to rep) }))),
                mapOf("rep" to fields[1], "binder" to mapOf("id" to "$name-tuple", "lifted" to false,
                    "rep" to tuple(operation, true))))
            mapOf("id" to name, "name" to name, "arity" to formals.size, "lifted" to true, "rep" to closure,
                "expr" to listOf("lam", formals, body, mapOf("rep" to closure, "resultRep" to fields[1])))
        }
        return mapOf("bindings" to bindings, "instrument" to true, "constructors" to listOf(
            mapOf("id" to "tuple2", "name" to "(#,#)", "kind" to "unboxed-tuple", "arity" to 2, "tag" to 1)))
    }

    @Test fun firstInstalledCallsUseBothTypedBackendsAndReleaseHandoffs() {
        for (backend in listOf("ast", "bytecode")) inside { language ->
            val program = if (backend == "ast") Program(language, module()) else BytecodeProgram(language, module())
            val targets = EnvironmentOp.entries.associateWith { program.entryTarget(it.symbol) }
            val name = string("THC_LOCAL")
            val entry = string("THC_LOCAL=first")
            val environment = Language.currentState().environment
            environment.environ() // Materialize host-authorized initial state before compiling.
            var compiled = false
            fun call(operation: EnvironmentOp, vararg arguments: Any): Any? {
                val target = targets.getValue(operation)
                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                val result = Calls.target(target, arrayOf(0L, *arguments, Unit))
                assertEquals(before + if (compiled) 1 else 0,
                    (program.diagnostics().getValue("compiledEntries") as Number).toLong(), backend)
                if (compiled) assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                val handoff = language.handoffState.get()
                assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
                assertNull(handoff.pending)
                return result
            }
            fun semantics() {
                assertEquals(0L, call(EnvironmentOp.PUT, entry))
                assertEquals("first", text(call(EnvironmentOp.GET, name) as ManagedAddress))
                assertEquals(setOf("THC_INITIAL=lambda-\u03bb", "THC_LOCAL=first"),
                    entries(call(EnvironmentOp.ENUMERATE) as ManagedAddress).toSet())
                assertEquals(0L, call(EnvironmentOp.UNSET, name))
                assertSame(ManagedAddress.nullAddress(), call(EnvironmentOp.GET, name))
            }
            // Check the entire semantic corpus in the interpreter, then install
            // once. There is no guest call between compilation and the first
            // checked installed entry, and no settling/retry after compilation.
            semantics()
            for (target in targets.values) {
                target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
            }
            compiled = true
            semantics()
        }
    }

    @Test fun putenvRetainsCallerBytesAndEnumerationHasNativePointerIdentity() = inside {
        val environment = Language.currentState().environment
        val name = string("LOCAL")
        val entry = string("LOCAL=abc")
        assertEquals(0L, environment.put(entry))
        val firstVector = environment.environ()
        assertEquals(entry.toNativeBits() + 6, environment.get(name).toNativeBits())
        entry.writeWord8(6, 'X'.code.toLong())
        assertEquals("Xbc", text(environment.get(name)))
        assertTrue("LOCAL=Xbc" in entries(firstVector))
        assertEquals(0L, environment.put(string("LOCAL=")))
        assertEquals("", text(environment.get(name)))
        assertThrows(RuntimeFault::class.java) { firstVector.readAddressElementIndex(0) }
        assertEquals(0L, environment.put(name)) // Linux putenv without '=' removes.
        assertSame(ManagedAddress.nullAddress(), environment.get(name))
        assertEquals(-1L, environment.unset(string("invalid=name")))
        assertEquals(-1L, environment.unset(string("")))
        assertEquals(0L, environment.unset(name))
        assertSame(ManagedAddress.nullAddress(), environment.get(string("")))
        val unterminated = Language.currentState().nativeAllocations.malloc(1)
        unterminated.writeWord8(0, 65)
        assertThrows(RuntimeFault::class.java) { environment.put(unterminated) }
        assertEquals(listOf("THC_INITIAL=lambda-\u03bb"), entries(environment.environ()))
    }

    @Test fun contextIsolationEnvironmentPolicyAndDisposalArePreserved() = inside {
        val outer = Language.currentState().environment
        val name = string("THC_LOCAL")
        outer.put(string("THC_LOCAL=outer"))
        lateinit var escaped: ManagedAddress
        context().use { inner ->
            inner.initialize("thc"); inner.enter()
            try {
                assertThrows(RuntimeFault::class.java) { outer.environ() }
                val current = Language.currentState().environment
                assertSame(ManagedAddress.nullAddress(), current.get(string("THC_LOCAL")))
                assertEquals(listOf("THC_INITIAL=lambda-\u03bb"), entries(current.environ()))
                escaped = current.get(string("THC_INITIAL"))
            } finally { inner.leave() }
        }
        assertThrows(RuntimeFault::class.java) { escaped.readWord8(0) }
        assertEquals("outer", text(outer.get(name)))
        assertNotEquals("outer", System.getenv("THC_LOCAL"))
    }

    @Test fun foreignAdmissionRejectsWrongOwnerAbiAndTuple() {
        for (operation in EnvironmentOp.entries) {
            val original = declaration(operation)
            fun validate(call: Map<String, Any?>) = CoreEnvironmentForeign.validate(
                mapOf("foreignCall" to call, "rep" to tuple(operation)), operation.arguments.map { scalar(it) },
                operation.arguments.map { false }, tuple(operation))
            assertEquals(operation, validate(original))
            for ((key, wrong) in listOf("safety" to "safe", "convention" to "capi", "arity" to 0,
                "resultRep" to scalar(operation.result), "argumentReps" to emptyList<Any?>(),
                "target" to mapOf("kind" to "static", "symbol" to operation.symbol, "unit" to "other", "isFunction" to true)))
                assertThrows(RuntimeFault::class.java) { validate(original + (key to wrong)) }
        }
    }
}
