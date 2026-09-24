@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language

/** Synthetic ABI execution controls; not original public Fingerprint execution. */
class Md5ForeignCallTest {
    private fun scalar(rep: String?, evaluated: Boolean = true) = mapOf("kind" to when (rep) {
        null -> "void"; "AddrRep" -> "address"; "BoxedRep (Just Lifted)" -> "closure"; else -> "long"
    }, "primReps" to (rep?.let { listOf(it) } ?: emptyList()), "evaluated" to evaluated)
    private val state = scalar(null)
    private val long = scalar("IntRep")
    private val closure = scalar("BoxedRep (Just Lifted)")
    private fun tuple(evaluated: Boolean = true) = mapOf("kind" to "unknown", "primReps" to emptyList<String>(),
        "aggregate" to "unboxed-tuple", "components" to listOf(state), "evaluated" to evaluated)
    private fun module(operation: Md5ForeignOp, unit: String = "ghc-internal", descriptor: Boolean = true,
                       foreignId: Any? = "foreign-id", headProof: Map<String, Any?>? = closure): Map<String, Any?> {
        val reps = when (operation) {
            Md5ForeignOp.INIT -> listOf("AddrRep", null)
            Md5ForeignOp.UPDATE -> listOf("AddrRep", "AddrRep", "Int32Rep", null)
            Md5ForeignOp.FINAL -> listOf("AddrRep", "AddrRep", null)
        }
        val formals = reps.mapIndexed { index, rep -> mapOf("id" to "p$index", "lifted" to false, "rep" to scalar(rep)) }
        val metadata = mutableMapOf<String, Any?>("rep" to tuple())
        if (descriptor) metadata["foreignCall"] = mapOf("schema" to 1L,
            "target" to mapOf("kind" to "static", "symbol" to operation.symbol, "unit" to unit, "isFunction" to true),
            "convention" to "ccall", "safety" to "unsafe", "arity" to reps.size.toLong(), "suppliedArity" to reps.size.toLong(),
            "argumentReps" to reps.map { scalar(it, false) }, "resultRep" to tuple(false))
        val call = listOf("app", listOf("var", foreignId, mapOf("rep" to headProof)),
            formals.map { listOf("var", it["id"], mapOf("rep" to it["rep"])) }, List(reps.size) { false }, false, false, metadata)
        val body = listOf("case", call, "pair", listOf(listOf("data", "T1", listOf("s"),
            listOf("lit", "int", "17", mapOf("rep" to long)), mapOf("binders" to listOf(mapOf("id" to "s", "lifted" to false, "rep" to state))))),
            mapOf("rep" to long, "binder" to mapOf("id" to "pair", "lifted" to false, "rep" to tuple())))
        return mapOf("instrument" to true, "constructors" to listOf(mapOf("id" to "T1", "kind" to "unboxed-tuple", "arity" to 1, "tag" to 1)),
            "bindings" to listOf(mapOf("id" to "root", "name" to "root", "arity" to reps.size, "lifted" to true, "rep" to closure,
                "expr" to listOf("lam", formals, body, mapOf("rep" to closure, "resultRep" to long)))))
    }
    private fun context() = Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build()
    private fun valid(target: RootCallTarget) = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun released(language: Language) {
        val handoff = language.handoffState.get()
        assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
        assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
    }
    @Test fun compiledClosedCallsUseVisibleStorageAndEraseOnlyTheStateResult() {
        for (backend in listOf("ast", "bytecode")) for (operation in Md5ForeignOp.entries) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val module = module(operation)
                val program: ExecutableProgram = if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                val entry = program.entryTarget("root")
                fun call(first: ManagedAddress, second: ManagedAddress, size: Long, token: Any? = Unit): Any? {
                    val arguments = when (operation) {
                        Md5ForeignOp.INIT -> arrayOf<Any?>(0L, first, token)
                        Md5ForeignOp.UPDATE -> arrayOf<Any?>(0L, first, second, size, token)
                        Md5ForeignOp.FINAL -> arrayOf<Any?>(0L, first, second, token)
                    }
                    return Calls.target(entry, arguments)
                }
                fun positive(compiled: Boolean) {
                    for (size in listOf(0, 1, 15, 55, 56, 63, 64, 65, 129)) {
                        val bytes = ByteArray(88) { 0xa5.toByte() }; val input = ByteArray(size) { (it * 73 + 127).toByte() }
                        val output = ByteArray(16) { 0xd3.toByte() }
                        val address = ManagedAddress.fromByteArray(bytes); val source = ManagedAddress.fromByteArray(input)
                        val result = ManagedAddress.fromByteArray(output)
                        if (operation != Md5ForeignOp.INIT) ManagedMd5.init(address)
                        if (operation == Md5ForeignOp.FINAL) ManagedMd5.update(address, source, size.toLong())
                        val expected = bytes.copyOf(); val expectedOutput = output.copyOf()
                        when (operation) {
                            Md5ForeignOp.INIT -> ManagedMd5.init(ManagedAddress.fromByteArray(expected))
                            Md5ForeignOp.UPDATE -> ManagedMd5.update(ManagedAddress.fromByteArray(expected), source, size.toLong())
                            Md5ForeignOp.FINAL -> ManagedMd5.finish(ManagedAddress.fromByteArray(expectedOutput), ManagedAddress.fromByteArray(expected))
                        }
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        assertEquals(17L, call(if (operation == Md5ForeignOp.FINAL) result else address,
                            if (operation == Md5ForeignOp.FINAL) address else source, size.toLong()))
                        assertArrayEquals(expected, bytes); assertArrayEquals(expectedOutput, output)
                        if (compiled) { assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong()); valid(entry) }
                        released(language)
                    }
                }
                positive(false)
                entry.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(entry, true); valid(entry)
                positive(true)
                val bytes = ByteArray(88) { 0xa5.toByte() }; val output = ByteArray(16) { 0xd3.toByte() }
                val beforeBytes = bytes.copyOf(); val beforeOutput = output.copyOf()
                val badState = assertThrows(RuntimeFault::class.java) {
                    call(ManagedAddress.fromByteArray(if (operation == Md5ForeignOp.FINAL) output else bytes),
                        ManagedAddress.fromByteArray(bytes), Long.MAX_VALUE, 7L)
                }
                assertTrue(badState.message.orEmpty().contains("zero-width scalar carrier"), badState.message)
                assertArrayEquals(beforeBytes, bytes); assertArrayEquals(beforeOutput, output); released(language)
                if (operation == Md5ForeignOp.UPDATE) for (length in listOf(-1L, 1L shl 32, Long.MAX_VALUE)) {
                    assertThrows(RuntimeFault::class.java) { call(ManagedAddress.fromByteArray(bytes), ManagedAddress.fromByteArray(output), length) }
                    assertArrayEquals(beforeBytes, bytes); assertArrayEquals(beforeOutput, output); released(language)
                }
            } finally { context.leave() }
        }
    }
    @Test fun mainUnitAndMissingDescriptorsNeverSelectTheAdapter() {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (operation in Md5ForeignOp.entries) {
                    fun load(module: Map<String, Any?>): ExecutableProgram =
                        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
                    assertThrows(RuntimeFault::class.java) { load(module(operation, unit = "main")) }
                    assertThrows(UnsupportedCore::class.java) { load(module(operation, descriptor = false)) }
                    // A descriptor cannot bypass ordinary lexical/global Haskell definitions.
                    for (id in listOf(null, "", 3L, "p0", "root"))
                        assertThrows(RuntimeFault::class.java) { load(module(operation, foreignId = id)) }
                    for (proof in listOf(null, state, closure + ("evaluated" to false), closure + ("components" to emptyList<Any?>())))
                        assertThrows(RuntimeFault::class.java) { load(module(operation, headProof = proof)) }
                }
            } finally { context.leave() }
        }
    }
}
