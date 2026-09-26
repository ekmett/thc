// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import thc.Language
import thc.launcherArguments
import java.lang.foreign.ValueLayout

class GuestArgumentsTest {
    private fun context(vararg arguments: String): Context = Context.newBuilder("thc")
        .allowNativeAccess(true).arguments("thc", arguments).build()
    private fun inside(body: () -> Unit) {
        assumeTrue(System.getProperty("os.name") == "Linux" &&
            System.getProperty("os.arch") in setOf("amd64", "x86_64"))
        context().use { context ->
            context.initialize("thc"); context.enter()
            try { body() } finally { context.leave() }
        }
    }
    private fun get(arguments: GuestArguments): Pair<Int, ManagedAddress> {
        val allocations = Language.currentState().nativeAllocations
        val count = allocations.malloc(4)
        val vector = allocations.malloc(8)
        try {
            arguments.get(count, vector)
            return count.withNativeSegment { it.get(ValueLayout.JAVA_INT_UNALIGNED, 0) } to
                vector.readAddressElementIndex(0)
        } finally { allocations.free(count); allocations.free(vector) }
    }
    private fun text(address: ManagedAddress): String =
        ByteArray(address.cStringLength().toInt()) { address.readWord8(it.toLong()).toByte() }.toString(Charsets.UTF_8)
    private fun values(image: Pair<Int, ManagedAddress>): List<String> {
        assertSame(ManagedAddress.nullAddress(), image.second.readAddressElementIndex(image.first.toLong()))
        return List(image.first) { text(image.second.readAddressElementIndex(it.toLong())) }
    }

    @Test fun nativeImagePreservesProgramNameEmptyUnicodeAndOptions() = inside {
        val arguments = Language.currentState().arguments
        arguments.initialize("program", arrayOf("", "\u03bb\uD834\uDD1E", "--help", "--"))
        val image = get(arguments)
        assertEquals(listOf("program", "", "\u03bb\uD834\uDD1E", "--help", "--"), values(image))
        image.second.withNativeSegment { pointers ->
            assertEquals(image.second.readAddressElementIndex(0).toNativeBits(),
                pointers.get(ValueLayout.JAVA_LONG_UNALIGNED, 0))
        }
        arguments.get(ManagedAddress.nullAddress(), ManagedAddress.nullAddress())
        assertThrows(RuntimeFault::class.java) { arguments.initialize("again", emptyArray()) }
    }

    @Test fun setCopiesBeforeRetiringEvenSelfAliasedInputAndAllowsEmptyArgv() = inside {
        val arguments = Language.currentState().arguments
        arguments.initialize("original", arrayOf("one", "two"))
        val old = get(arguments)
        val oldString = old.second.readAddressElementIndex(1)
        arguments.set(2, old.second.plus(8))
        assertEquals(listOf("one", "two"), values(get(arguments)))
        assertThrows(RuntimeFault::class.java) { old.second.readAddressElementIndex(0) }
        assertThrows(RuntimeFault::class.java) { oldString.readWord8(0) }
        arguments.set(0, ManagedAddress.nullAddress())
        assertEquals(emptyList<String>(), values(get(arguments)))
    }

    @Test fun setCopiesManagedInputsAndRejectsInvalidCountsAndUnterminatedStrings() = inside {
        val arguments = Language.currentState().arguments
        val storage = ManagedAllocation.mutable(16, 8)
        val vector = ManagedAddress.fromAllocation(storage)
        vector.writeAddressElementIndex(0, ManagedAddress.fromHex("6100"))
        arguments.set(1, vector)
        vector.writeAddressElementIndex(0, ManagedAddress.fromHex("6200"))
        assertEquals(listOf("a"), values(get(arguments)))
        assertThrows(RuntimeFault::class.java) { arguments.set(-1, vector) }
        assertThrows(RuntimeFault::class.java) { arguments.set(Int.MAX_VALUE.toLong() + 1, vector) }
        assertThrows(RuntimeFault::class.java) { arguments.set(3, vector) }
        val unterminated = ManagedAddress.fromAllocation(ManagedAllocation.mutable(2, 8))
        unterminated.writeWord8(0, 97); unterminated.writeWord8(1, 98)
        vector.writeAddressElementIndex(0, unterminated)
        assertThrows(RuntimeFault::class.java) { arguments.set(1, vector) }
        assertEquals(listOf("a"), values(get(arguments)))
    }

    @Test fun contextIsolationAndDisposalInvalidateNativeArgumentAliases() = inside {
        val outer = Language.currentState().arguments
        val outerImage = get(outer)
        lateinit var escaped: ManagedAddress
        context("inner").use { inner ->
            inner.initialize("thc"); inner.enter()
            try {
                assertThrows(RuntimeFault::class.java) { get(outer) }
                assertThrows(RuntimeFault::class.java) { outerImage.second.readAddressElementIndex(0) }
                val image = get(Language.currentState().arguments)
                assertEquals(listOf("", "inner"), values(image))
                escaped = image.second
            } finally { inner.leave() }
        }
        assertThrows(RuntimeFault::class.java) { escaped.readAddressElementIndex(0) }
        assertEquals(listOf(""), values(get(outer)))
    }

    @Test fun pointerCellsKeepRangeOwnershipAndOpaqueUnknownBitsChecks() = inside {
        val allocations = Language.currentState().nativeAllocations
        val vector = allocations.malloc(16)
        val payload = allocations.malloc(2)
        payload.writeWord8(0, 65); payload.writeWord8(1, 0)
        vector.writeAddressElementIndex(0, payload)
        assertEquals("A", text(vector.readAddressElementIndex(0)))
        assertThrows(RuntimeFault::class.java) { vector.readAddressElementIndex(Long.MAX_VALUE) }
        assertThrows(RuntimeFault::class.java) { vector.writeAddressElementIndex(2, payload) }
        vector.writeNativeScalar(1, 8, 17)
        val unknown = vector.readAddressElementIndex(1)
        assertEquals(17L, unknown.toNativeBits())
        assertThrows(RuntimeFault::class.java) { unknown.readWord8(0) }
        allocations.free(payload)
        assertThrows(RuntimeFault::class.java) { vector.readAddressElementIndex(0).readWord8(0) }
        allocations.free(vector)
        assertThrows(RuntimeFault::class.java) { vector.readAddressElementIndex(0) }
    }

    @Test fun invalidOutputStorageAndNulCannotMutateTheArguments() = inside {
        val arguments = Language.currentState().arguments
        val nil = ManagedAddress.nullAddress()
        assertThrows(RuntimeFault::class.java) { arguments.get(ManagedAddress.fromHex("00000000"), nil) }
        arguments.initialize("program", arrayOf("bad\u0000argument"))
        assertThrows(RuntimeFault::class.java) { arguments.get(nil, nil) }
        arguments.initialize("valid", emptyArray())
        assertEquals(listOf("valid"), values(get(arguments)))
    }

    @Test fun launcherSuffixKeepsOpaqueArgumentsAndLegacyNoArgumentCalls() {
        assertEquals("thc", launcherArguments(arrayOf("--run-io", "modules", "entry"), 3).first)
        val parsed = launcherArguments(arrayOf("--run-io", "modules", "entry", "--", "program", "", "--", "--help"), 3)
        assertEquals("program", parsed.first)
        assertArrayEquals(arrayOf("", "--", "--help"), parsed.second)
        assertThrows(IllegalArgumentException::class.java) { launcherArguments(arrayOf("a", "b", "c", "--"), 3) }
        assertThrows(IllegalArgumentException::class.java) { launcherArguments(arrayOf("a", "b", "c", "bad", "name"), 3) }
    }

    @Test fun argumentForeignAdmissionChecksTheActualCAbiAndStateTuple() {
        // Synthetic admission controls; ordinary end-to-end tests supply genuine
        // GHC FCallIds through the full installed interface exporter.
        fun scalar(primitive: String?, evaluated: Boolean = true): Map<String, Any?> = mapOf(
            "kind" to when (primitive) { null -> "void"; "AddrRep" -> "address"; else -> "long" },
            "primReps" to listOfNotNull(primitive), "evaluated" to evaluated)
        val result = mapOf("kind" to "unknown", "primReps" to emptyList<String>(), "evaluated" to true,
            "aggregate" to "unboxed-tuple", "components" to listOf(scalar(null)))
        for (operation in RtsArgumentsOp.entries) {
            val descriptor = mapOf("schema" to 1, "target" to mapOf("kind" to "static",
                "symbol" to operation.symbol, "unit" to "ghc-internal", "isFunction" to true),
                "convention" to "ccall", "safety" to "unsafe", "arity" to 3, "suppliedArity" to 3,
                "argumentReps" to operation.arguments.map { scalar(it, false) },
                "resultRep" to (result + ("evaluated" to false)))
            val arguments = operation.arguments.map { scalar(it) }
            fun validate(declaration: Map<String, Any?>) = CoreRtsArgumentsForeign.validate(
                mapOf("foreignCall" to declaration, "rep" to result), arguments, List(3) { false }, result)
            assertEquals(operation, validate(descriptor))
            for ((key, value) in listOf("safety" to "safe", "arity" to 2, "resultRep" to scalar(null),
                "argumentReps" to listOf(scalar("IntRep", false), scalar("AddrRep", false), scalar(null, false)))) {
                assertThrows(RuntimeFault::class.java) { validate(descriptor + (key to value)) }
            }
        }
    }
}
