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

class FloatingByteOffsetTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private data class Row(val floatBits: Long, val doubleBits: Long, val actual: List<Long>)
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

    @Test fun unalignedStorageBoundsPointerCellsAndStateBeforeMutation() {
        val bytes = ByteArray(32)
        val floatBits = 0x7fc12345L
        val doubleBits = 0x7ff8000000001234L
        ManagedByteArray.writeFloatByteOffsetGuest(bytes, 1, java.lang.Float.intBitsToFloat((floatBits).toInt()))
        ManagedByteArray.writeDoubleByteOffsetGuest(bytes, 9, java.lang.Double.longBitsToDouble(doubleBits))
        assertEquals(floatBits, (java.lang.Float.floatToRawIntBits(ManagedByteArray.readFloatByteOffsetGuest(bytes, 1)).toLong() and 0xffffffffL))
        assertEquals(doubleBits, java.lang.Double.doubleToRawLongBits(ManagedByteArray.readDoubleByteOffsetGuest(bytes, 9)))
        val model = ByteBuffer.allocate(32).order(ByteOrder.nativeOrder())
            .putInt(1, floatBits.toInt()).putLong(9, doubleBits).array()
        assertArrayEquals(model, bytes)
        for (offset in listOf(-1L, 29L, Long.MAX_VALUE))
            assertThrows(RuntimeFault::class.java) { ManagedByteArray.readFloatByteOffsetGuest(bytes, offset) }
        for (offset in listOf(-1L, 25L, Long.MAX_VALUE))
            assertThrows(RuntimeFault::class.java) { ManagedByteArray.writeDoubleByteOffsetGuest(bytes, offset, 1.0) }
        val edge = ByteArray(12)
        ManagedByteArray.writeFloatByteOffsetGuest(edge, 8, -0.0f)
        assertEquals(0x80000000L, (java.lang.Float.floatToRawIntBits(ManagedByteArray.readFloatByteOffsetGuest(edge, 8)).toLong() and 0xffffffffL))
        ManagedByteArray.writeDoubleByteOffsetGuest(edge, 4, -0.0)
        assertEquals(Long.MIN_VALUE, java.lang.Double.doubleToRawLongBits(ManagedByteArray.readDoubleByteOffsetGuest(edge, 4)))

        val owner = ManagedAllocation.mutable(32, 8)
        val target = ManagedAddress.fromAllocation(ManagedAllocation.mutable(8, 8))
        owner.writeAddressByteOffset(16, target)
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.readDoubleByteOffsetGuest(owner, 12) }
        assertThrows(RuntimeFault::class.java) { ManagedByteArray.writeFloatByteOffsetGuest(owner, 19, 1.0f) }
        assertSame(target, owner.readAddressByteOffset(16))
        ManagedByteArray.writeDoubleByteOffsetGuest(owner, 16, -0.0)
        assertEquals(Long.MIN_VALUE, java.lang.Double.doubleToRawLongBits(ManagedByteArray.readDoubleByteOffsetGuest(owner, 16)))
        assertThrows(RuntimeFault::class.java) { owner.readAddressByteOffset(16) }

        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), FrameDescriptor.newBuilder().build())
        fun operand(value: Any): Expr = object : Expr() { override fun execute(frame: VirtualFrame): Any = value }
        val write = byteArrayExpression(ByteArrayOp.WRITE_WORD8_AS_FLOAT, CoreRepresentation.UNKNOWN,
            arrayOf(operand(bytes), operand(1L), operand(2.0f), operand("invalid state")))
        assertThrows(RuntimeFault::class.java) { write.execute(frame) }
        assertThrows(RuntimeFault::class.java) { BytecodeRoot.WriteDoubleArray.write(true, bytes, 9L, 2.0, "invalid state") }
        assertArrayEquals(model, bytes)
    }

    @Test fun primitiveMetadataRejectsWrongOffsetsRepsAndFlags() {
        fun proof(kind: CoreKind, rep: String) = CoreRepresentation(kind, primReps = listOf(rep))
        val array = proof(CoreKind.OBJECT, "BoxedRep (Just Unlifted)")
        val offset = proof(CoreKind.LONG, "IntRep")
        val wrongOffset = proof(CoreKind.LONG, "WordRep")
        val float = proof(CoreKind.FLOAT, "FloatRep")
        val double = proof(CoreKind.DOUBLE, "DoubleRep")
        ByteArrayOp.INDEX_WORD8_AS_FLOAT.validate(listOf(array, offset), listOf(false, false), float)
        ByteArrayOp.INDEX_WORD8_AS_DOUBLE.validate(listOf(array, offset), listOf(false, false), double)
        assertThrows(RuntimeFault::class.java) {
            ByteArrayOp.INDEX_WORD8_AS_FLOAT.validate(listOf(array, wrongOffset), listOf(false, false), float)
        }
        assertThrows(RuntimeFault::class.java) {
            ByteArrayOp.INDEX_WORD8_AS_DOUBLE.validate(listOf(array, offset), listOf(false, false), float)
        }
        assertThrows(RuntimeFault::class.java) {
            ByteArrayOp.INDEX_WORD8_AS_FLOAT.validate(listOf(array, offset), listOf(false, true), float)
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun originalGhcFloatingByteOffsetCompositeMatchesNativeInBothBackends() {
        val manifest = Json.parse(File(root, "build/floating-byte-offset/manifest.json").readText()) as Map<String, Any?>
        assertEquals(1L, manifest["schema"])
        assertEquals("9.14.1", manifest["ghc"])
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[kind] as Map<String, String>) {
                val digest = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, digest, "Stale floating byte-offset " + kind + ": " + path)
            }
        val rows = File(root, "build/floating-byte-offset/oracle.tsv").readLines().map { line ->
            val fields = line.split('\t')
            assertEquals(6, fields.size)
            Row(fields[0].toLong(), fields[1].toULong().toLong(),
                fields.drop(2).map { it.toULong().toLong() })
        }
        assertEquals(10, rows.size)
        for (row in rows) {
            val model = ByteBuffer.allocate(64).order(ByteOrder.nativeOrder())
                .putInt(1, row.floatBits.toInt()).putLong(9, row.doubleBits)
            assertEquals(listOf(model.getInt(1).toLong() and 0xffffffffL,
                model.getInt(1).toLong() and 0xffffffffL, model.getLong(9), model.getLong(9)),
                row.actual)
        }
        for (stage in listOf("pre", "post")) {
            val directory = File(root, "build/floating-byte-offset/" + stage)
            val audit = Json.parse(File(directory, "audit.json").readText()) as Map<String, Any?>
            assertEquals(true, audit["accepted"])
            assertEquals(emptyList<Any>(), audit["missingGlobals"])
            val required = setOf("indexWord8ArrayAsFloat#", "readWord8ArrayAsFloat#", "writeWord8ArrayAsFloat#",
                "indexWord8ArrayAsDouble#", "readWord8ArrayAsDouble#", "writeWord8ArrayAsDouble#")
            val primitives = audit["primitives"] as List<Map<String, Any?>>
            for (primitive in required) {
                val evidence = primitives.single { it["name"] == primitive }
                val owners = (evidence["uses"] as List<Map<String, Any?>>).map { it["owner"] }.toSet()
                assertEquals(setOf("main:FloatingByteOffsetAudit.floatingByteOffsetBits"), owners,
                    stage + "/" + primitive)
            }
            val module = Json.parse(File(directory, "core/FloatingByteOffsetAudit.json").readText()) as Map<String, Any?>
            for (backend in listOf("ast", "bytecode")) context().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val entry = "floatingByteOffsetBits"
                    val source = CoreModules.reachable(module, entry) + ("instrument" to true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, source)
                        else BytecodeProgram(language, source)
                    val function = context.asValue(EntryValue(program, entry, 3))
                    val guest = program.entryTarget(entry)
                    val host = program.hostEntryTarget(3)
                    fun check(row: Row, selectors: IntProgression) {
                        for (selector in selectors)
                            assertEquals(row.actual[selector],
                                function.execute(row.floatBits, row.doubleBits, selector).asLong(),
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
                            val label = "$stage/$backend/$selector/${row.floatBits}/${row.doubleBits}"
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            assertEquals(row.actual[selector], function.execute(row.floatBits, row.doubleBits, selector).asLong(), label)
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
