// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.ContextProfile
import thc.Language
import thc.withContextProfile
import java.util.concurrent.atomic.AtomicReference

class EnabledCapabilitiesTest {
    private val address = mapOf("kind" to "address", "primReps" to listOf("AddrRep"), "evaluated" to true)
    private val state = mapOf("kind" to "void", "primReps" to emptyList<String>(), "evaluated" to true)
    private val offset = mapOf("kind" to "long", "primReps" to listOf("IntRep"), "evaluated" to true)
    private val word32 = mapOf("kind" to "long", "primReps" to listOf("Word32Rep"), "evaluated" to true)
    private val closure = mapOf("kind" to "closure", "primReps" to listOf("BoxedRep (Just Lifted)"), "evaluated" to true)
    private val result = mapOf("kind" to "unknown", "aggregate" to "unboxed-tuple", "evaluated" to true,
        "primReps" to listOf("Word32Rep"), "components" to listOf(state, word32))
    private val label = listOf("lit", "data-addr", "enabled_capabilities", mapOf("rep" to address))

    private fun module(): Map<String, Any?> {
        val parameter = mapOf("id" to "s", "lifted" to false, "rep" to state)
        val call = listOf("app", listOf("prim", "readWord32OffAddr#"),
            listOf(label, listOf("lit", "int", "0", mapOf("rep" to offset)),
                listOf("var", "s", mapOf("rep" to state))),
            listOf(false, false, false), false, false, mapOf("rep" to result))
        val fields = listOf(mapOf("id" to "next", "lifted" to false, "rep" to state),
            mapOf("id" to "value", "lifted" to false, "rep" to word32))
        val body = listOf("case", call, "pair", listOf(listOf("data", "tuple2", listOf("next", "value"),
            listOf("var", "value", mapOf("rep" to word32)), mapOf("binders" to fields))),
            mapOf("rep" to word32, "binder" to mapOf("id" to "pair", "lifted" to false, "rep" to result)))
        return mapOf("instrument" to true,
            "constructors" to listOf(mapOf("id" to "tuple2", "kind" to "unboxed-tuple", "arity" to 2, "tag" to 1)),
            "bindings" to listOf(mapOf("id" to "read", "name" to "read", "arity" to 1,
                "lifted" to true, "rep" to closure,
                "expr" to listOf("lam", listOf(parameter), body, mapOf("rep" to closure, "resultRep" to word32)))))
    }

    private fun context() = Context.newBuilder("thc").withContextProfile(ContextProfile.SYNCHRONOUS_TEST).build()
    private fun valid(target: RootCallTarget) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target))

    @Test fun liveLabelUsesExactWord32ReadAndOwnedContextInBothBackends() {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val threads = Language.currentState().threads
                threads.enterCurrent()
                try {
                    val program = if (backend == "ast") Program(language, module()) else BytecodeProgram(language, module())
                val target = program.entryTarget("read")
                fun read() = Calls.target(target, arrayOf(0L, Unit)) as Long
                repeat(100) { assertEquals(1L, read()) }
                target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                valid(target)
                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                assertEquals(1L, read())
                assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                valid(target)

                val workerFailure = AtomicReference<Throwable?>()
                val worker = Thread {
                    try { threads.enterCurrent(); threads.leaveCurrent() }
                    catch (failure: Throwable) { workerFailure.set(failure) }
                }
                worker.start(); worker.join()
                workerFailure.get()?.let { throw AssertionError("Guest carrier registration failed", it) }
                assertEquals(2L, read(), "The RTS label must observe a later guest carrier")
                valid(target)

                val cell = CoreDataLabels.fromCore("enabled_capabilities",
                    CoreRepresentations.parse(address))
                assertEquals(2L, ManagedAddressRead.WORD32.read(cell, 0))
                for (operation in ManagedAddressRead.entries.filter { it != ManagedAddressRead.WORD32 })
                    assertThrows(RuntimeFault::class.java) { operation.read(cell, 0) }
                assertThrows(RuntimeFault::class.java) { ManagedAddressRead.WORD32.read(cell, 1) }
                assertThrows(RuntimeFault::class.java) { cell.readWord8(0) }
                assertThrows(RuntimeFault::class.java) { cell.writeWord8(0, 1) }
                assertThrows(RuntimeFault::class.java) { cell.writeNativeScalar(0, 4, 1) }
                assertThrows(RuntimeFault::class.java) { cell.plus(1) }
                assertThrows(RuntimeFault::class.java) { cell.toNativeBits() }
                for (bad in listOf("other_symbol", "enabled_capabilities_extra"))
                    assertThrows(RuntimeFault::class.java) { CoreDataLabels.fromCore(bad, CoreRepresentations.parse(address)) }
                assertThrows(RuntimeFault::class.java) { CoreDataLabels.fromCore("enabled_capabilities",
                    CoreRepresentations.parse(address + ("primReps" to listOf("WordRep")))) }
                assertThrows(RuntimeFault::class.java) { CoreDataLabels.fromCore("enabled_capabilities",
                    CoreRepresentations.parse(address + ("evaluated" to false))) }

                val foreignCell = context().use { foreign ->
                    foreign.initialize("thc"); foreign.enter()
                    try {
                        assertThrows(RuntimeFault::class.java) { ManagedAddressRead.WORD32.read(cell, 0) }
                        CoreDataLabels.fromCore("enabled_capabilities", CoreRepresentations.parse(address))
                    }
                    finally { foreign.leave() }
                }
                assertEquals(2L, ManagedAddressRead.WORD32.read(cell, 0))
                assertTrue(cell.sameLocation(cell))
                assertThrows(RuntimeFault::class.java) { cell.sameLocation(foreignCell) }
                } finally { threads.leaveCurrent() }
            } finally { context.leave() }
        }
    }
}
