// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import thc.Main.withContextProfile

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import thc.*

/** Actual C-owned static storage, returned by compiled C with no known managed
 * backing. The Core callers are explicit runtime models, not exporter proofs. */
class NarrowReturnedPointerTest {
    private class Buffer(language: Language, call: PackageScalarCall) : RootNode(language) {
        @Child private var access = PackageScalarAccess(call)
        override fun execute(frame: VirtualFrame): Any = access.executeAddress(emptyArray(), Unit)
    }
    private fun module(family: String, byteOffset: Boolean): Map<String, Any?> {
        val address = mapOf("kind" to "address", "primReps" to listOf("AddrRep"), "evaluated" to true)
        val index = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
        val value = mapOf("kind" to "long", "primReps" to listOf("${family}Rep"), "evaluated" to true)
        val state = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
        val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
        val parameters = listOf("p" to address, "i" to index, "x" to value).map { (id, rep) ->
            mapOf("id" to id, "lifted" to false, "rep" to rep)
        }
        fun variable(id: String, rep: Map<String, Any>) = listOf("var", id, mapOf("rep" to rep))
        val suffix = if (byteOffset) "Word8OffAddrAs$family#" else "${family}OffAddr#"
        val write = listOf("app", listOf("prim", "write$suffix"),
            listOf(variable("p", address), variable("i", index), variable("x", value), listOf("void", mapOf("rep" to state))),
            listOf(false, false, false, false), false, false, mapOf("rep" to state))
        val read = listOf("app", listOf("prim", "index$suffix"),
            listOf(variable("p", address), variable("i", index)), listOf(false, false), false, false, mapOf("rep" to value))
        val body = listOf("case", write, "stored", listOf(listOf("default", null, emptyList<Any>(), read)),
            mapOf("rep" to value, "binder" to mapOf("id" to "stored", "type" to "State# RealWorld", "lifted" to false, "rep" to state)))
        return mapOf("instrument" to true, "constructors" to emptyList<Any>(), "bindings" to listOf(
            mapOf("id" to "roundtrip", "name" to "roundtrip", "arity" to 3, "lifted" to true, "rep" to closure,
                "expr" to listOf("lam", parameters, body, mapOf("rep" to closure, "resultRep" to value)))))
    }

    @ParameterizedTest @CsvSource(
        "ast,Int16,false", "ast,Word16,false", "ast,Int32,false", "ast,Word32,false",
        "ast,Int16,true", "ast,Word16,true", "ast,Int32,true", "ast,Word32,true",
        "bytecode,Int16,false", "bytecode,Word16,false", "bytecode,Int32,false", "bytecode,Word32,false",
        "bytecode,Int16,true", "bytecode,Word16,true", "bytecode,Int32,true", "bytecode,Word32,true")
    fun cOwnedBufferAcceptsNarrowStoresAndRetainsFirstCompiledEntry(backend: String, family: String, byteOffset: Boolean) {
        Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000")
            .let { withContextProfile(it, ContextProfile.SYNCHRONOUS_TEST) }.build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val owner = Language.currentState()
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val symbol = "thc_package_pointer_test_buffer"
                    val signature = PackageScalarSignature(symbol, symbol, emptyList(), "AddrRep")
                    val bytes = javaClass.getResourceAsStream("/thc/cbits/package-pointer.bc")!!.use { it.readBytes() }
                    val link = PackageScalarLink("narrow-external-control", "unused", "narrow-external-control", "", bytes, listOf(signature))
                    owner.packageCbits.link(link)
                    val pointer = Buffer(language, PackageScalarCall(link, signature)).callTarget.call() as ManagedAddress
                    assertNotNull(pointer.returnedAddress())
                    assertNull(pointer.returnedAddress()!!.backing)
                    assertNull(pointer.nativeAllocation())
                    assertThrows(RuntimeFault::class.java) { pointer.availableBytes() }
                    val integer = NarrowInteger.fromRep("${family}Rep")!!
                    val width = integer.bits / 8
                    val offset = if (byteOffset) 3L else 1L
                    val displacement = if (byteOffset) offset else offset * width
                    val values = if (integer.bits == 16) listOf(-32768, -1, 0, 32767, 65535)
                        else listOf(Int.MIN_VALUE, -1, 0, Int.MAX_VALUE)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, module(family, byteOffset))
                        else BytecodeProgram(language, module(family, byteOffset))
                    val target = program.entryTarget("roundtrip")
                    fun count() = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    fun valid() = assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                    fun check(raw: Int) {
                        val expected = integer.narrow(raw)
                        pointer.fill(32, 90)
                        assertEquals(expected, callScalarTestTarget(target, arrayOf(0L, pointer, offset, expected)))
                        val result = ByteArray(32).also { pointer.copyToByteArray(it, 0, 32) }
                        val expectedBytes = ByteArray(32) { 90 }
                        val buffer = java.nio.ByteBuffer.wrap(expectedBytes).order(java.nio.ByteOrder.nativeOrder())
                        if (width == 2) buffer.putShort(displacement.toInt(), expected.toShort())
                        else buffer.putInt(displacement.toInt(), expected)
                        assertArrayEquals(expectedBytes, result, "native bytes and untouched neighbours")
                    }
                    owner.threads.enterCurrent()
                    try {
                        repeat(5) { values.forEach(::check) }
                        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                        valid()
                        val runtime = Truffle.getRuntime()
                        runtime.javaClass.getMethod("bypassedInstalledCode", Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget"))
                            .invoke(runtime, target)
                        valid()
                        for (value in values) {
                            val before = count()
                            check(value)
                            assertEquals(before + 1, count(), "first installed call must enter the original target")
                            valid()
                        }
                        assertThrows(RuntimeFault::class.java) { pointer.writeNativeInt(Long.MAX_VALUE, width, 1) }
                        assertThrows(RuntimeFault::class.java) { pointer.writeNativeInt(0, 8, 1) }
                    } finally { owner.threads.leaveCurrent() }
                } finally { context.leave() }
            }
    }
}
