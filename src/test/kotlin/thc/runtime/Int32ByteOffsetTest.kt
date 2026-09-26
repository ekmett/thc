// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class Int32ByteOffsetTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private data class Row(val signed: Long, val unsigned: Long, val actual: List<Long>)
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build()
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val targets = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val root = target.rootNode
            val nodes = if (root is BytecodeRoot) listOf(root) + root.bytecodeNode.instructions
                .flatMap { it.arguments }.filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }
                .mapNotNull { it.asCachedNode() }
            else listOf(root)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val active = call.currentCallTarget as? RootCallTarget ?: continue
                if (active.rootNode is GuestRoot) visit(active)
            }
            targets.add(target)
        }
        visit(entry)
        return targets
    }

    @Test fun unalignedSignedUnsignedStorageChecksBoundsPointerCellsAndState() {
        val bytes = ByteArray(16)
        ManagedByteArray.writeInt32ByteOffsetGuest(bytes, 1, -2147483648)
        ManagedByteArray.writeInt32ByteOffsetGuest(bytes, 9, 0x80000001L)
        assertEquals(-2147483648L, ManagedByteArray.readInt32ByteOffsetGuest(bytes, 1, false))
        assertEquals(0x80000001L, ManagedByteArray.readInt32ByteOffsetGuest(bytes, 9, true))
        val model = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder())
            .putInt(1, -2147483648).putInt(9, 0x80000001L.toInt()).array()
        assertArrayEquals(model, bytes)
        val tail = ByteArray(16)
        ManagedByteArray.writeInt32ByteOffsetGuest(tail, 12, -1)
        assertEquals(4294967295L, ManagedByteArray.readInt32ByteOffsetGuest(tail, 12, true))
        for (offset in listOf(-1L, 13L, Long.MAX_VALUE)) {
            assertThrows(RuntimeFault::class.java) { ManagedByteArray.readInt32ByteOffsetGuest(bytes, offset, false) }
            assertThrows(RuntimeFault::class.java) { ManagedByteArray.writeInt32ByteOffsetGuest(bytes, offset, 1) }
        }

        val owner = ManagedAllocation.mutable(24, 8)
        val target = ManagedAddress.fromAllocation(ManagedAllocation.mutable(8, 8))
        owner.writeAddressByteOffset(8, target)
        ManagedByteArray.writeInt32ByteOffsetGuest(owner, 1, -1)
        assertEquals(-1L, ManagedByteArray.readInt32ByteOffsetGuest(owner, 1, false))
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.readInt32ByteOffsetGuest(owner, 9, true) }
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.writeInt32ByteOffsetGuest(owner, 9, 1) }
        assertSame(target, owner.readAddressByteOffset(8))

        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), FrameDescriptor.newBuilder().build())
        fun operand(value: Any): Expr = object : Expr() { override fun execute(frame: VirtualFrame): Any = value }
        val write = byteArrayExpression(ByteArrayOp.WRITE_WORD8_AS_INT32, CoreRepresentation.UNKNOWN,
            arrayOf(operand(bytes), operand(1L), operand(7L), operand("invalid state")))
        assertThrows(RuntimeFault::class.java) { write.execute(frame) }
        assertThrows(RuntimeFault::class.java) { BytecodeRoot.WriteInt32Array.write(true, bytes, 9L, 7L, "invalid state") }
        assertEquals(-2147483648L, ManagedByteArray.readInt32ByteOffsetGuest(bytes, 1, false))
        assertEquals(0x80000001L, ManagedByteArray.readInt32ByteOffsetGuest(bytes, 9, true))
    }

    @Test fun metadataRequiresScalarCarriersAndByteOffsets() {
        fun proof(kind: CoreKind, rep: String) = CoreRepresentation(kind, primReps = listOf(rep))
        val array = proof(CoreKind.OBJECT, "BoxedRep (Just Unlifted)")
        val offset = proof(CoreKind.LONG, "IntRep")
        val wrongOffset = proof(CoreKind.DOUBLE, "DoubleRep")
        val signed = proof(CoreKind.LONG, "Int32Rep")
        val unsigned = proof(CoreKind.LONG, "Word32Rep")
        ByteArrayOp.INDEX_WORD8_AS_INT32.validate(listOf(array, offset), listOf(false, false), signed)
        ByteArrayOp.INDEX_WORD8_AS_WORD32.validate(listOf(array, offset), listOf(false, false), unsigned)
        assertThrows(RuntimeFault::class.java) {
            ByteArrayOp.INDEX_WORD8_AS_INT32.validate(listOf(array, wrongOffset), listOf(false, false), signed)
        }
        assertDoesNotThrow {
            ByteArrayOp.INDEX_WORD8_AS_WORD32.validate(listOf(array, offset), listOf(false, false), signed)
        }
        assertThrows(RuntimeFault::class.java) {
            ByteArrayOp.INDEX_WORD8_AS_INT32.validate(listOf(array, offset), listOf(false, true), signed)
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun originalGhcInt32ByteOffsetCompositeMatchesNativeInBothBackends() {
        val manifest = Json.parse(File(root, "build/int32-byte-offset/manifest.json").readText()) as Map<String, Any?>
        assertEquals(1L, manifest["schema"])
        assertEquals("9.14.1", manifest["ghc"])
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[kind] as Map<String, String>) {
                val digest = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, digest, "Stale int32 byte-offset " + kind + ": " + path)
            }
        val rows = File(root, "build/int32-byte-offset/oracle.tsv").readLines().map { line ->
            val fields = line.split('\t')
            assertEquals(6, fields.size)
            Row(fields[0].toLong(), fields[1].toLong(),
                fields.drop(2).map { it.toLong() })
        }
        assertEquals(10, rows.size)
        for (row in rows) {
            val model = ByteBuffer.allocate(24).order(ByteOrder.nativeOrder())
                .putInt(1, row.signed.toInt()).putInt(9, row.unsigned.toInt())
            assertEquals(listOf(model.getInt(1).toLong(), model.getInt(1).toLong(),
                model.getInt(9).toLong() and 0xffffffffL, model.getInt(9).toLong() and 0xffffffffL),
                row.actual)
        }
        for (stage in listOf("pre", "post")) {
            val directory = File(root, "build/int32-byte-offset/" + stage)
            val audit = Json.parse(File(directory, "audit.json").readText()) as Map<String, Any?>
            assertEquals(true, audit["accepted"])
            assertEquals(emptyList<Any>(), audit["missingGlobals"])
            val required = setOf("indexWord8ArrayAsInt32#", "readWord8ArrayAsInt32#", "writeWord8ArrayAsInt32#",
                "indexWord8ArrayAsWord32#", "readWord8ArrayAsWord32#", "writeWord8ArrayAsWord32#")
            val primitives = audit["primitives"] as List<Map<String, Any?>>
            for (primitive in required) {
                val evidence = primitives.single { it["name"] == primitive }
                val owners = (evidence["uses"] as List<Map<String, Any?>>).map { it["owner"] }.toSet()
                assertEquals(setOf("main:Int32ByteOffsetAudit.int32ByteOffsetValues"), owners,
                    stage + "/" + primitive)
            }
            val module = Json.parse(File(directory, "core/Int32ByteOffsetAudit.json").readText()) as Map<String, Any?>
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val entry = "int32ByteOffsetValues"
                    val source = CoreModules.reachable(module, entry) + ("instrument" to true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, source)
                        else BytecodeProgram(language, source)
                    val function = context.asValue(EntryValue(program, entry, 3))
                    val guest = program.entryTarget(entry)
                    val host = program.hostEntryTarget(3)
                    fun check(row: Row, selectors: IntProgression) {
                        for (selector in selectors)
                            assertEquals(row.actual[selector],
                                function.execute(row.signed, row.unsigned, selector).asLong(),
                                stage + "/" + backend + "/" + selector)
                        assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                    }
                    rows.forEach { check(it, 0..3) }
                    val targets = activeTargets(host)
                    assertSame(host, targets.last(), "$stage/$backend host root")
                    assertTrue(targets.size > 1, "$stage/$backend reachable guest roots")
                    val guestCount = targets.size - 1
                    targets.dropLast(1).forEach { target ->
                        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                        valid(target, "$stage/$backend guest installation")
                    }
                    assertTrue(function.invokeMember("compile").asBoolean(), stage + "/" + backend)
                    valid(host, stage + "/" + backend + "/host installation")
                    for (row in rows.asReversed()) {
                        for (selector in 3 downTo 0) {
                            val label = "$stage/$backend/$selector/${row.signed}/${row.unsigned}"
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            assertEquals(row.actual[selector], function.execute(row.signed, row.unsigned, selector).asLong(), label)
                            val after = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            assertEquals(before + guestCount, after, "$label exact compiled guest entries")
                            assertSame(guest, program.entryTarget(entry), "$label guest identity")
                            assertSame(host, program.hostEntryTarget(3), "$label host identity")
                            val active = activeTargets(host)
                            assertEquals(targets.size, active.size, "$label active root count")
                            assertTrue(targets.zip(active).all { (beforeTarget, afterTarget) -> beforeTarget === afterTarget },
                                "$label active root identities")
                            targets.forEach { valid(it, "$label active root valid") }
                        }
                    }
                } finally { context.leave() }
            }
        }
    }
}
