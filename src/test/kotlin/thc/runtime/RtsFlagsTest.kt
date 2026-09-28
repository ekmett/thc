// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import thc.ContextProfile
import thc.Language
import thc.withContextProfile
import java.io.ByteArrayOutputStream

class RtsFlagsTest {
    private val address = mapOf("kind" to "address", "primReps" to listOf("AddrRep"), "evaluated" to true)
    private val state = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
    private val int = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val byte = mapOf("kind" to "long", "primReps" to listOf("Word8Rep"), "evaluated" to true)
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private val proof get() = CoreRepresentations.parse(address)

    private fun layout(abi: String = "inplace"): TargetLayout {
        assumeTrue(System.getProperty("os.name").startsWith("Linux") &&
            System.getProperty("os.arch") in setOf("amd64", "x86_64"), "Only the evidenced Linux producing layout is supported")
        val document = StackInfoTestLayout.document()
        return TargetLayout.fromDocument(document + ("compiler" to
            ((document.getValue("compiler") as Map<*, *>) + ("abi" to abi))))
    }

    private fun context(output: ByteArrayOutputStream = ByteArrayOutputStream()) =
        Context.newBuilder("thc").withContextProfile(ContextProfile.SYNCHRONOUS_TEST).err(output).build()

    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target))
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target)
        val runtime = Truffle.getRuntime()
        runtime.javaClass.getMethod("bypassedInstalledCode",
            Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")).invoke(runtime, target)
        valid(target)
    }

    @Test fun composedFieldViewsAreReadOnlyAndContextBound() {
        val layout = layout()
        val first = context(); val second = context()
        lateinit var base: ManagedAddress
        lateinit var field: ManagedAddress
        first.initialize("thc"); second.initialize("thc"); first.enter()
        try {
            base = CoreDataLabels.fromCore("RtsFlags", proof, layout)
            assertSame(base, CoreDataLabels.fromCore("RtsFlags", proof, layout))
            field = base.plus(392).plus(11)
            assertEquals(1L, field.readWord8(0))
            assertEquals(1L, base.readWord8(403))
            assertEquals(1L, base.plus(404).readWord8(-1))
            assertTrue(field.sameLocation(base.plus(403)))
            assertFalse(field.sameLocation(base))
            for (offset in listOf(0L, 392L, 402L, 404L, -1L, Long.MAX_VALUE)) {
                assertEquals("Unsupported RtsFlags byte field at offset $offset",
                    assertThrows(RuntimeFault::class.java) { base.readWord8(offset) }.message)
                assertEquals("Unsupported RtsFlags byte field at offset $offset",
                    assertThrows(RuntimeFault::class.java) { base.readWord8Int(offset) }.message)
            }
            assertThrows(RuntimeFault::class.java) { base.plus(Long.MAX_VALUE).plus(1) }
            assertThrows(RuntimeFault::class.java) { field.readWord8(Long.MAX_VALUE) }
            for (operation in ManagedAddressRead.entries)
                assertThrows(RuntimeFault::class.java) { operation.read(field, 0) }
            assertThrows(RuntimeFault::class.java) { field.writeWord8(0, 0) }
            assertThrows(RuntimeFault::class.java) { field.writeNativeScalar(0, 4, 0) }
            assertThrows(RuntimeFault::class.java) { AtomicAddressOp.READ.numeric(field) }
            assertThrows(RuntimeFault::class.java) { field.toNativeBits() }
            assertThrows(RuntimeFault::class.java) { field.rawBacking() }
            assertThrows(RuntimeFault::class.java) { field.availableBytes() }
            assertThrows(RuntimeFault::class.java) { field.compareWithinAllocation(base) }
        } finally { first.leave() }
        second.enter()
        try {
            assertEquals(1L, CoreDataLabels.fromCore("RtsFlags", proof, layout).readWord8(403))
            assertThrows(RuntimeFault::class.java) { field.readWord8(0) }
            assertEquals("Compiler RTS cell belongs to another or closed THC context",
                assertThrows(RuntimeFault::class.java) { base.readWord8Int(402) }.message)
            assertThrows(RuntimeFault::class.java) { base.plus(0) }
            assertThrows(RuntimeFault::class.java) { field.sameLocation(field) }
        } finally { second.leave(); second.close(); first.close() }
        assertThrows(RuntimeFault::class.java) { field.readWord8(0) }
        assertEquals("Compiler RTS cell belongs to another or closed THC context",
            assertThrows(RuntimeFault::class.java) { base.readWord8Int(402) }.message)
        assertThrows(RuntimeFault::class.java) { field.plus(0) }
    }

    @Test fun missingLayoutAndFalseRepresentationsDoNotAdmitTheLabel() {
        val valid = layout()
        val wrongAbi = layout("another-build")
        context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                assertThrows(RuntimeFault::class.java) { CoreDataLabels.fromCore("RtsFlags", proof) }
                assertThrows(RuntimeFault::class.java) { CoreDataLabels.fromCore("RtsFlags", proof, wrongAbi) }
                assertThrows(RuntimeFault::class.java) { CoreDataLabels.fromCore("RtsFlags", null, valid) }
                assertThrows(RuntimeFault::class.java) {
                    CoreDataLabels.fromCore("RtsFlags", proof.withEvaluated(false), valid)
                }
                assertThrows(RuntimeFault::class.java) {
                    CoreDataLabels.fromCore("RtsFlags", CoreRepresentations.parse(int), valid)
                }
                assertThrows(RuntimeFault::class.java) { CoreDataLabels.fromCore("RtsFlags_extra", proof, valid) }
            } finally { context.leave() }
        }
    }

    private fun getterModule(layout: TargetLayout): Map<String, Any?> {
        fun plus(base: Any, amount: Long) = listOf("app", listOf("prim", "plusAddr#"),
            listOf(base, listOf("lit", "int", amount.toString(), mapOf("rep" to int))),
            listOf(false, false), false, false, mapOf("rep" to address))
        val label = listOf("lit", "data-addr", "RtsFlags", mapOf("rep" to address))
        val tuple = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple", "evaluated" to true,
            "primReps" to listOf("Word8Rep"), "components" to listOf(state, byte))
        val call = listOf("app", listOf("prim", "readWord8OffAddr#"),
            listOf(plus(plus(label, 392), 11), listOf("lit", "int", "0", mapOf("rep" to int)),
                listOf("var", "s", mapOf("rep" to state))), listOf(false, false, false), false, false,
            mapOf("rep" to tuple))
        val fields = listOf(mapOf("id" to "next", "lifted" to false, "rep" to state),
            mapOf("id" to "value", "lifted" to false, "rep" to byte))
        val body = listOf("case", call, "pair", listOf(listOf("data", "tuple2", listOf("next", "value"),
            listOf("var", "value", mapOf("rep" to byte)), mapOf("binders" to fields))),
            mapOf("rep" to byte, "binder" to mapOf("id" to "pair", "lifted" to false, "rep" to tuple)))
        return mapOf("targetLayout" to layout, "instrument" to true,
            "constructors" to listOf(mapOf("id" to "tuple2", "kind" to "unboxed-tuple", "arity" to 2, "tag" to 1)),
            "bindings" to listOf(mapOf("id" to "read", "name" to "read", "arity" to 1, "lifted" to true,
                "rep" to closure, "expr" to listOf("lam", listOf(mapOf("id" to "s", "lifted" to false, "rep" to state)),
                    body, mapOf("rep" to closure, "resultRep" to byte)))))
    }

    @Test fun exactGetterOperationsExecuteOnBothBackends() {
        val layout = layout()
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            val threads = Language.currentState().threads
            threads.enterCurrent()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val program = if (backend == "ast") Program(language, getterModule(layout))
                    else BytecodeProgram(language, getterModule(layout))
                val target = program.entryTarget("read")
                assertTrue((target.rootNode as GuestRoot).scalarResultProof.isInt)
                repeat(3) { assertEquals(1, Calls.target(target, arrayOf(0L, Unit))) }
                val before = (program.diagnostics()["compiledEntries"] as Number).toLong()
                compile(target)
                assertEquals(before, (program.diagnostics()["compiledEntries"] as Number).toLong())
                assertEquals(1, Calls.target(target, arrayOf(0L, Unit)))
                assertEquals(before + 1, (program.diagnostics()["compiledEntries"] as Number).toLong(),
                    "$backend immediate installed getter")
                valid(target)
            } finally { threads.leaveCurrent(); context.leave() }
        }
    }

    @Test fun unsupportedOffsetFirstInstalledCallKeepsTheTargetAndValidRead() {
        val layout = layout()
        context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val address = CoreDataLabels.fromCore("RtsFlags", proof, layout)
                val root = object : RootNode(language) {
                    var compiledEntries = 0L
                    override fun execute(frame: VirtualFrame): Any {
                        if (CompilerDirectives.inCompiledCode()) compiledEntries++
                        return address.readWord8Int(frame.arguments[0] as Long)
                    }
                }
                val target = root.callTarget
                assertEquals(1, target.call(403L))
                val before = root.compiledEntries
                compile(target)
                assertEquals(before, root.compiledEntries)
                val failure = assertThrows(RuntimeFault::class.java) { target.call(402L) }
                assertEquals("Unsupported RtsFlags byte field at offset 402", failure.message)
                assertEquals(before + 1, root.compiledEntries, "First installed call is the invalid offset")
                valid(target)
                assertEquals(1, target.call(403L))
                assertEquals(before + 2, root.compiledEntries)
                valid(target)
            } finally { context.leave() }
        }
    }

    @Test fun userFlagReflectsAllThreeTraceSinksNotDiagnosticCounters() {
        val layout = layout()
        val previous = System.getProperty("thc.diagnostics")
        try {
            for (diagnostics in listOf(false, true)) {
                System.setProperty("thc.diagnostics", diagnostics.toString())
                val output = ByteArrayOutputStream()
                context(output).use { context ->
                    context.initialize("thc"); context.enter()
                    val threads = Language.currentState().threads
                    threads.enterCurrent()
                    try {
                        assertEquals(1L, CoreDataLabels.fromCore("RtsFlags", proof, layout).readWord8(403))
                        val text = ManagedAddress.fromByteArray(byteArrayOf(65, 0))
                        for (operation in TraceOp.values()) RtsDiagnostics.trace(null, operation, text, 1)
                    } finally { threads.leaveCurrent(); context.leave() }
                }
                assertEquals("[thc trace event] A\n[thc trace binary] 41\n[thc trace marker] A\n", output.toString(Charsets.UTF_8))
            }
        } finally {
            if (previous == null) System.clearProperty("thc.diagnostics") else System.setProperty("thc.diagnostics", previous)
        }
    }
}
