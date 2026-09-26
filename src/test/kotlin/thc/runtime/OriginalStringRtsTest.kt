// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Json
import thc.Language

/** Descriptors copied unchanged from original GHC.Internal.System.Posix.Internals
 * core/235.json, sha256 9e77ab9a3dbb5c507f9ec6a8c16e37b6b9f29eaf677520f1c532e6657ac30ce9. */
class OriginalStringRtsTest {
    private val descriptors get() = Json.parse(
        javaClass.getResource("/core/original-string-rts-descriptors.json")!!.readText()) as Map<String, Map<String, Any?>>
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)

    private fun module(symbol: String, declaration: Map<String, Any?> = descriptors.getValue(symbol),
        canonical: Map<String, Any?> = descriptors.getValue(symbol)): Map<String, Any?> {
        val result = canonical.getValue("resultRep") as Map<String, Any?>
        val fields = result.getValue("components") as List<Map<String, Any?>>
        val formals = (canonical.getValue("argumentReps") as List<Map<String, Any?>>).mapIndexed { index, rep ->
            mapOf("id" to "arg$index", "lifted" to false, "rep" to (rep + ("evaluated" to true)))
        }
        val call = listOf("app", listOf("var", "original-$symbol", mapOf("rep" to closure)),
            formals.map { listOf("var", it.getValue("id"), mapOf("rep" to it.getValue("rep"))) },
            List(formals.size) { false }, false, false, mapOf("rep" to result, "foreignCall" to declaration))
        val resultLong = fields.last()
        val body = listOf("case", call, "result-tuple", listOf(
            listOf("data", "tuple2", listOf("result-state", "result-long"),
                listOf("var", "result-long", mapOf("rep" to resultLong)),
                mapOf("binders" to fields.mapIndexed { index, rep ->
                    mapOf("id" to if (index == 0) "result-state" else "result-long",
                        "lifted" to false, "rep" to rep)
                }))),
            mapOf("rep" to resultLong, "binder" to mapOf("id" to "result-tuple",
                "lifted" to false, "rep" to (result + ("evaluated" to true)))))
        val binding = mapOf("id" to symbol, "name" to symbol, "arity" to formals.size, "lifted" to true,
            "rep" to closure, "expr" to listOf("lam", formals, body,
                mapOf("rep" to closure, "resultRep" to resultLong)))
        return mapOf("schema" to 1, "module" to "SyntheticOriginalStringRts", "unit" to "test", "ghc" to "9.14.1",
            "instrument" to true, "bindings" to listOf(binding), "constructors" to listOf(
                mapOf("id" to "tuple2", "name" to "(#,#)", "kind" to "unboxed-tuple", "arity" to 2, "tag" to 1)))
    }

    private fun context() = Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw")
        .option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun <T> inside(block: (Language) -> T): T = context().use { context ->
        context.initialize("thc"); context.enter()
        try { block(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
        finally { context.leave() }
    }
    private fun program(language: Language, backend: String, source: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, source) else BytecodeProgram(language, source)

    @Test fun originalByteStringSizeTDeclarationUsesTheSameCheckedCStringStorage() {
        // Unchanged FCallId in original unix System.Posix.PosixPath.FilePath,
        // module SHA-256 e341553bb7289341df45e43917d9dc17a3f7e67074c9afbd315326480175a26b.
        val original = Json.parse(javaClass.getResource("/core/original-bytestring-strlen-descriptor.json")!!.readText())
            as Map<String, Any?>
        for (backend in listOf("ast", "bytecode")) inside { language ->
            val guest = program(language, backend, module("strlen", original, original))
            val target = guest.entryTarget("strlen")
            val text = ManagedAddress.fromByteArray(byteArrayOf(65, -50, -69, 0, 66))
            fun length(address: ManagedAddress) = Calls.target(target, arrayOf(0L, address, Unit)) as Long
            assertEquals(3L, length(text))
            assertEquals(0L, length(text.plus(3)))
            target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
            valid(target)
            val before = (guest.diagnostics().getValue("compiledEntries") as Number).toLong()
            assertEquals(2L, length(text.plus(1)))
            assertEquals(before + 1, (guest.diagnostics().getValue("compiledEntries") as Number).toLong())
            valid(target)
            assertThrows(RuntimeFault::class.java) { length(ManagedAddress.fromByteArray(byteArrayOf(65))) }
            val wrong = original + ("resultRep" to descriptors.getValue("strlen").getValue("resultRep"))
            assertThrows(RuntimeFault::class.java) { program(language, backend, module("strlen", wrong, original)) }
        }
    }

    @Test fun originalStrlenScansManagedAndOwnedNativeMemoryThroughFirstNulOnCompiledEntry() {
        for (backend in listOf("ast", "bytecode")) inside { language ->
            val guest = program(language, backend, module("strlen"))
            val target = guest.entryTarget("strlen")
            fun length(address: ManagedAddress) = Calls.target(target, arrayOf(0L, address, Unit)) as Long
            val managed = ManagedAddress.fromAllocation(ManagedAllocation.mutable(12, 8))
            listOf(65L, 0xceL, 0xbbL, 0L, 80L).forEachIndexed { i, byte -> managed.writeWord8(i.toLong(), byte) }
            assertEquals(3L, length(managed))
            assertEquals(2L, length(managed.plus(1)))
            assertEquals(0L, length(managed.plus(3)))
            val raw = ManagedAddress.fromByteArray(byteArrayOf(1, 2, 0, 4))
            assertEquals(2L, length(raw))
            target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
            valid(target)
            val before = (guest.diagnostics().getValue("compiledEntries") as Number).toLong()
            assertEquals(3L, length(managed))
            assertEquals(before + 1, (guest.diagnostics().getValue("compiledEntries") as Number).toLong())
            valid(target)
            if (System.getProperty("os.name") == "Linux" &&
                System.getProperty("os.arch") in setOf("amd64", "x86_64")) {
                val native = Language.currentState().nativeAllocations.malloc(8)
                listOf(5L, 6L, 0L).forEachIndexed { i, byte -> native.writeWord8(i.toLong(), byte) }
                val nativeBefore = (guest.diagnostics().getValue("compiledEntries") as Number).toLong()
                val alias = native.plus(1)
                assertEquals(1L, length(alias))
                assertEquals(nativeBefore + 1, (guest.diagnostics().getValue("compiledEntries") as Number).toLong())
                valid(target)
                Language.currentState().nativeAllocations.free(native)
                assertThrows(RuntimeFault::class.java) { length(alias) }
            }
            assertThrows(RuntimeFault::class.java) { length(ManagedAddress.fromByteArray(byteArrayOf(1, 2))) }
            assertThrows(RuntimeFault::class.java) { length(managed.plus(12)) }
            assertThrows(RuntimeFault::class.java) { length(ManagedAddress.nullAddress()) }
            assertEquals(0L, length(ManagedAddress.fromHex("0000000000000000")))
            assertThrows(RuntimeFault::class.java) { length(ManagedAddress.unownedNumeric(0x1234L)) }
            val pointers = ManagedAddress.fromAllocation(ManagedAllocation.mutable(16, 8))
            pointers.writeAddressElementIndex(0, managed)
            assertThrows(RuntimeFault::class.java) { length(pointers) }
        }
    }

    @Test fun originalRtsWayQueryUsesNonthreadedFdContractOnCompiledEntry() {
        for (backend in listOf("ast", "bytecode")) inside { language ->
            val guest = program(language, backend, module("rts_isThreaded"))
            val target = guest.entryTarget("rts_isThreaded")
            fun query() = Calls.target(target, arrayOf(0L, Unit)) as Long
            assertEquals(0L, query())
            target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
            valid(target)
            val before = (guest.diagnostics().getValue("compiledEntries") as Number).toLong()
            assertEquals(0L, query())
            assertEquals(before + 1, (guest.diagnostics().getValue("compiledEntries") as Number).toLong())
            valid(target)
        }
    }

    @Test fun alteredOriginalDeclarationsRejectBeforeExecution() = inside { language ->
        for (symbol in listOf("strlen", "rts_isThreaded")) {
            assertThrows(RuntimeFault::class.java) {
                CoreStringRtsForeign.validateHead(listOf("var", "forged", mapOf("rep" to closure)), true)
            }
            for (backend in listOf("ast", "bytecode")) {
                fun reject(change: (MutableMap<String, Any?>) -> Unit) {
                    val malformed = descriptors.getValue(symbol).toMutableMap().also(change)
                    assertThrows(RuntimeFault::class.java) { program(language, backend, module(symbol, malformed)) }
                }
                reject { it["safety"] = "safe" }
                reject { it["arity"] = 3L }
                reject { it["argumentReps"] = emptyList<Any>() }
                reject { it["resultRep"] = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to false) }
                reject { it["target"] = (it.getValue("target") as Map<String, Any?>) + ("unit" to "foreign") }
            }
        }
    }
}
