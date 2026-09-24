// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.source.Source
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import java.lang.ref.WeakReference
import java.nio.ByteOrder

/** Real live Truffle frames with synthetic metadata/layouts; not native GHC frame equivalence. */
class ManagedStackRuntimeTest {
    private val platform = (if (System.getProperty("os.arch").lowercase() in setOf("arm64", "aarch64")) "aarch64" else "x86_64") +
        (if (System.getProperty("os.name").startsWith("Mac")) "-osx" else "-linux")
    private val endian = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) "little" else "big"
    private fun layout(changes: Map<String, Any?> = emptyMap(), abi: String = "stack-service-test"): TargetLayout {
        val fields = mapOf(
            "schema" to 1, "profiled" to false, "wordBytes" to 8, "targetPlatform" to platform,
            "tablesNextToCode" to true, "endianness" to endian,
            "infoTableBytes" to 16, "infoTablePtrsOffset" to 0, "infoTablePtrsBytes" to 4,
            "infoTableNptrsOffset" to 4, "infoTableNptrsBytes" to 4,
            "infoTableTypeOffset" to 8, "infoTableTypeBytes" to 4, "infoTableSrtOffset" to 12, "infoTableSrtBytes" to 4,
            // Deliberately unaligned byte offsets and padding, not primop element indices.
            "infoProvEntBytes" to 91, "infoProvBytes" to 64, "infoProvEntInfoOffset" to 3, "infoProvEntProvOffset" to 19,
            "infoProvNameOffset" to 0, "infoProvDescOffset" to 8, "infoProvDescBytes" to 4,
            "infoProvTyDescOffset" to 16, "infoProvLabelOffset" to 24, "infoProvUnitOffset" to 32,
            "infoProvModuleOffset" to 40, "infoProvFileOffset" to 48, "infoProvSpanOffset" to 56,
            "closureRetBco" to 29, "closureRetSmall" to 30, "closureRetBig" to 31, "closureRetFun" to 32,
            "closureUpdateFrame" to 33, "closureCatchFrame" to 34, "closureUnderflowFrame" to 35, "closureStopFrame" to 36,
            "closureStack" to 53, "closureAtomicallyFrame" to 55, "closureCatchRetryFrame" to 56,
            "closureCatchStmFrame" to 57, "closureAnnFrame" to 65, "stackHeaderBytes" to 8,
            "stackCatchHandlerBytes" to 8, "stackCatchFrameBytes" to 16, "stackCatchStmCodeBytes" to 8,
            "stackCatchStmHandlerBytes" to 16, "stackCatchStmFrameBytes" to 24, "stackUpdateeBytes" to 8,
            "stackUpdateFrameBytes" to 16, "stackAtomicallyCodeBytes" to 8, "stackAtomicallyResultBytes" to 16,
            "stackAtomicallyFrameBytes" to 24, "stackCatchRetryAltCodeBytes" to 8, "stackCatchRetryFirstCodeBytes" to 16,
            "stackCatchRetryAltBytes" to 24, "stackCatchRetryFrameBytes" to 32,
            "stackRetFunSizeBytes" to 8, "stackRetFunFunBytes" to 16, "stackRetFunPayloadBytes" to 24, "stackRetFunFrameBytes" to 24,
            "stackAnnPayloadBytes" to 8, "stackAnnFrameBytes" to 16, "stackClosurePayloadBytes" to 8)
        return TargetLayout.fromDocument(mapOf("format" to "thc-target-layout", "schema" to 1,
            "compiler" to mapOf("id" to "ghc-9.14.1", "abi" to abi, "platform" to platform, "way" to "dynamic-nonprofiling"),
            "layout" to (fields + changes)))
    }

    private class Capture(language: Language?, identity: CoreFunctionIdentity?, location: CoreSourceLocation?) : GuestRoot(language, FrameLayout().build()) {
        @Child var body: Expr = object : Expr() {
            override fun execute(frame: VirtualFrame): Any = ManagedStackSnapshot.capture(this)
        }.located(location)
        init { configureCoreIdentity(identity) }
        override fun execute(frame: VirtualFrame): Any? = body.execute(frame)
        override fun bloom(frame: VirtualFrame) = 0L
        override fun getName() = "invented-unit:Not.Provenance.lambda"
    }
    private fun capture(language: Language?, named: Boolean = true, occurrence: String = "workλ"): ManagedStackSnapshot {
        val source = Source.newBuilder("thc", "", "Actual.hs").content(Source.CONTENT_NONE).build()
        val section = source.createSection(7, 3, 8, 9)
        val location = if (named) CoreSourceLocation(section,
            listOf(CoreSourceNote("real-note", section, "actual note label", 7, 3, 8, 10))) else null
        val root = Capture(language, if (named) CoreFunctionIdentity("real-unit:Actual.Module.$occurrence",
            "real-unit", "Actual.Module", occurrence) else null, location)
        val caller = object : GuestRoot(language, FrameLayout().build()) {
            @Child var call = DirectCallNode.create(root.callTarget)
            override fun execute(frame: VirtualFrame): Any? = Calls.direct(call, arrayOf(0L))
            override fun bloom(frame: VirtualFrame) = 0L
        }
        return Calls.target(caller.callTarget, arrayOf(0L)) as ManagedStackSnapshot
    }
    private fun <T> context(block: (Language) -> T): T = Context.newBuilder("thc").build().use { context ->
        context.initialize("thc"); context.enter()
        try { block(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
        finally { context.leave() }
    }
    private fun unsigned(address: ManagedAddress, offset: Long, width: Int): Long = (0 until width).fold(0L) { value, i ->
        value or (address.readWord8(offset + i) shl (8 * if (endian == "little") i else width - 1 - i))
    }
    private fun text(storage: ManagedAllocation, view: Long, layout: TargetLayout, field: String): ManagedAddress =
        storage.readAddressByteOffset(view + layout.offset("infoProvEntProvOffset") + layout.offset("infoProv${field}Offset"))

    private fun registrations(): Int {
        val field = ManagedStackRegistry::class.java.getDeclaredField("entries").apply { isAccessible = true }
        return (field.get(Language.currentState().stackSnapshots) as ManagedAddress.WeakLocations<*>).size
    }

    private fun reclaimed(references: List<WeakReference<*>>, layout: TargetLayout, remaining: Int = 0) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (true) {
            System.gc()
            // Exercise normal queue draining, including snapshot values that held images
            // until the preceding collection. No private references are cleared by tests.
            assertEquals(0L, ManagedStackRuntime.lookupIpe(ManagedAddress.nullAddress(), ManagedAddress.nullAddress(), layout))
            if (references.all { it.get() == null } && registrations() == remaining) return
            assertTrue(System.nanoTime() < deadline, "Discarded snapshots, addresses or provenance were retained")
            Thread.sleep(10)
        }
    }

    private data class Alias(val address: ManagedAddress, val discarded: List<WeakReference<*>>,
        val provenance: WeakReference<ManagedStackFrame>)

    private fun registeredAlias(language: Language, layout: TargetLayout): Alias {
        val snapshot = capture(language)
        val pair = ManagedStackRuntime.frameInfo(snapshot, 0, layout)
        val output = ManagedAllocation.mutable(91, 8)
        // Populate the cache before discarding the canonical key: cached IPE must
        // not introduce a strong registry -> image -> key -> allocation cycle.
        assertEquals(1L, ManagedStackRuntime.lookupIpe(pair.second, ManagedAddress.fromAllocation(output), layout))
        return Alias(pair.first.plus(7), listOf(WeakReference(snapshot), WeakReference(pair.first),
            WeakReference(pair.second), WeakReference(snapshot.frames[1])), WeakReference(snapshot.frames[0]))
    }

    private fun exerciseRetainedAlias(language: Language, layout: TargetLayout): List<WeakReference<*>> {
        val retained = registeredAlias(language, layout)
        reclaimed(retained.discarded, layout, remaining = 1)
        assertNotNull(retained.provenance.get())
        assertEquals(1, registrations())
        val output = ManagedAllocation.mutable(91, 8)
        val destination = ManagedAddress.fromAllocation(output)
        assertEquals(0L, ManagedStackRuntime.lookupIpe(retained.address, destination, layout)) // Offset is part of identity.
        val forged = ManagedAddress.fromAllocation(ManagedAllocation.immutable(ByteArray(16), 8)).plus(16)
        assertEquals(0L, ManagedStackRuntime.lookupIpe(forged, destination, layout)) // Equal bytes are not identity.
        val key = retained.address.plus(9)
        assertEquals(1L, ManagedStackRuntime.lookupIpe(key, destination, layout))
        val label = text(output, 0, layout, "Label")
        assertEquals("workλ", label.utf8())
        assertEquals(1L, ManagedStackRuntime.lookupIpe(key, destination, layout))
        assertSame(label, text(output, 0, layout, "Label"))
        return retained.discarded + listOf(retained.provenance, WeakReference(retained.address), WeakReference(key))
    }

    @Test fun derivedAliasKeepsOnlyItsRegistrationAliveAfterSnapshotCollection() = context { language ->
        val layout = layout()
        val discarded = exerciseRetainedAlias(language, layout)
        reclaimed(discarded, layout)
        assertEquals(0, registrations())
    }

    private fun copiedKey(language: Language, layout: TargetLayout): Pair<ManagedAllocation, WeakReference<ManagedStackSnapshot>> {
        val snapshot = capture(language)
        val key = ManagedStackRuntime.frameInfo(snapshot, 0, layout).second
        val output = ManagedAllocation.mutable(91, 8)
        assertEquals(1L, ManagedStackRuntime.lookupIpe(key, ManagedAddress.fromAllocation(output), layout))
        return output to WeakReference(snapshot)
    }

    private fun exerciseCopiedKey(language: Language, layout: TargetLayout): List<WeakReference<*>> {
        val (output, snapshot) = copiedKey(language, layout)
        reclaimed(listOf(snapshot), layout, remaining = 1)
        val key = output.readAddressByteOffset(3)
        val label = text(output, 0, layout, "Label")
        assertEquals(1L, ManagedStackRuntime.lookupIpe(key, ManagedAddress.fromAllocation(output), layout))
        assertSame(label, text(output, 0, layout, "Label"))
        return listOf(snapshot, WeakReference(key), WeakReference(output))
    }

    @Test fun copiedOutputPointerRetainsIpeWithoutRetainingItsSnapshot() = context { language ->
        val layout = layout()
        reclaimed(exerciseCopiedKey(language, layout), layout)
        assertEquals(0, registrations())
    }

    private fun discardedRegistrations(language: Language, layout: TargetLayout): List<WeakReference<*>> =
        (0 until 32).flatMap {
            val snapshot = capture(language)
            val key = ManagedStackRuntime.frameInfo(snapshot, 0, layout).second
            val output = ManagedAddress.fromAllocation(ManagedAllocation.mutable(91, 8))
            assertEquals(1L, ManagedStackRuntime.lookupIpe(key, output, layout))
            listOf(WeakReference(snapshot), WeakReference(key), WeakReference(snapshot.frames[0]))
        }

    @Test fun repeatedCaptureAndLookupDoesNotRetainHistoricalFrames() = context { language ->
        val layout = layout()
        repeat(3) {
            reclaimed(discardedRegistrations(language, layout), layout)
            assertEquals(0, registrations())
        }
    }

    @Test fun liveFramesHaveStableDistinctImmutableInfoImagesAndWordOffsets() = context { language ->
        val snapshot = capture(language); val layout = layout()
        assertEquals(2, snapshot.frames.size)
        assertSame(Language.currentState().stackSnapshots.token, snapshot.ownerToken)
        val stack = ManagedStackRuntime.stackInfo(snapshot, layout)
        assertSame(stack, ManagedStackRuntime.stackInfo(snapshot, layout()))
        assertEquals(53L, unsigned(stack, 8, 4))
        val pairs = snapshot.frames.indices.map { ManagedStackRuntime.frameInfo(snapshot, it.toLong(), layout) }
        for ((i, pair) in pairs.withIndex()) {
            assertSame(pair.first, ManagedStackRuntime.frameInfo(snapshot, i.toLong(), layout).first)
            assertSame(pair.second, ManagedStackRuntime.frameInfo(snapshot, i.toLong(), layout).second)
            assertFalse(pair.first.sameLocation(pair.second))
            assertTrue(pair.first.plus(16).sameLocation(pair.second))
            assertEquals(30L, unsigned(pair.first, 8, 4))
            assertEquals(0L, unsigned(pair.first, 0, 8))
            assertThrows(RuntimeFault::class.java) { pair.first.writeWord8(0, 1) }
        }
        assertFalse(pairs[0].second.sameLocation(pairs[1].second))
        for (offset in listOf(-1L, 2L, Long.MIN_VALUE, Long.MAX_VALUE))
            assertThrows(RuntimeFault::class.java) { ManagedStackRuntime.frameInfo(snapshot, offset, layout) }
    }

    @Test fun virtualZeroSlackFramesExposeEmptyBitmapAndExactTerminalTraversal(): Unit = context { language ->
        val snapshot = capture(language); val layout = layout()
        assertEquals(2L, ManagedStackRuntime.stackFields(snapshot, layout))
        for (offset in snapshot.frames.indices) {
            val bitmap = ManagedStackRuntime.smallBitmap(snapshot, offset.toLong(), layout)
            assertEquals(0L, bitmap.bitmap)
            assertEquals(0L, bitmap.size)
            val next = ManagedStackRuntime.advance(snapshot, offset.toLong(), layout)
            if (offset + 1 < snapshot.frames.size) {
                assertSame(snapshot, next.snapshot)
                assertEquals(offset + 1L, next.wordOffset)
                assertEquals(1L, next.hasNext)
            } else {
                assertNull(next.snapshot)
                assertEquals(0L, next.wordOffset)
                assertEquals(0L, next.hasNext)
                assertThrows(RuntimeFault::class.java) { ManagedStackRuntime.stackFields(next.snapshot, layout) }
            }
        }
        for (offset in listOf(-1L, 2L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertThrows(RuntimeFault::class.java) { ManagedStackRuntime.smallBitmap(snapshot, offset, layout) }
            assertThrows(RuntimeFault::class.java) { ManagedStackRuntime.advance(snapshot, offset, layout) }
        }
        val changed = layout(abi = "other-stack-geometry")
        assertThrows(RuntimeFault::class.java) { ManagedStackRuntime.stackFields(snapshot, changed) }
    }

    @Test fun nativePayloadAndOtherFrameKindGettersNeverInventDiagnosticContents() = context { language ->
        val snapshot = capture(language); val layout = layout()
        val operations = listOf(OriginalStackInfoOp.WORD, OriginalStackInfoOp.CLOSURE,
            OriginalStackInfoOp.LARGE_BITMAP, OriginalStackInfoOp.BCO_LARGE_BITMAP,
            OriginalStackInfoOp.RET_FUN_LARGE_BITMAP, OriginalStackInfoOp.RET_FUN_SMALL_BITMAP,
            OriginalStackInfoOp.RET_FUN_BIG, OriginalStackInfoOp.UNDERFLOW)
        for (operation in operations) {
            val error = assertThrows(RuntimeFault::class.java) {
                ManagedStackRuntime.incompatibleGetter(operation, snapshot, 0, layout)
            }
            assertTrue(error.message.orEmpty().contains(operation.symbol))
            assertTrue(error.message.orEmpty().contains("managed diagnostic RET_SMALL"))
            assertThrows(RuntimeFault::class.java) { ManagedStackRuntime.incompatibleGetter(operation, snapshot, -1, layout) }
        }
    }

    @Test fun speculativeScalarGetterInterfacesPreserveValueAndEvaluateOperandOnce(): Unit = context { language ->
        val snapshot = capture(language); val layout = layout()
        val frame = com.oracle.truffle.api.Truffle.getRuntime().createVirtualFrame(emptyArray(), FrameLayout().build())
        var evaluations = 0
        fun operand() = object : Expr() {
            override fun execute(frame: VirtualFrame): Any { evaluations++; return snapshot }
        }
        val info = OriginalStackInfoExpression(OriginalStackInfoOp.STACK_INFO, layout, arrayOf(operand()),
            CoreRepresentation(CoreKind.ADDRESS, evaluated = true))
        val expectedAddress = ManagedStackRuntime.stackInfo(snapshot, layout)
        val addressMiss = assertThrows(com.oracle.truffle.api.nodes.UnexpectedResultException::class.java) {
            info.executeLong(frame)
        }
        assertSame(expectedAddress, addressMiss.result)
        assertEquals(1, evaluations)
        val fields = OriginalStackInfoExpression(OriginalStackInfoOp.STACK_FIELDS, layout, arrayOf(operand()),
            CoreRepresentation(CoreKind.LONG, evaluated = true))
        val longMiss = assertThrows(com.oracle.truffle.api.nodes.UnexpectedResultException::class.java) {
            fields.executeAddress(frame)
        }
        assertEquals(2L, longMiss.result)
        assertEquals(2, evaluations)
    }

    @Test fun newGettersEnforceSnapshotContextAndRegistryLifetime() {
        val layout = layout()
        val snapshot = context { capture(it) }
        context { _ ->
            assertThrows(RuntimeFault::class.java) { ManagedStackRuntime.stackFields(snapshot, layout) }
            assertThrows(RuntimeFault::class.java) { ManagedStackRuntime.smallBitmap(snapshot, 0, layout) }
            assertThrows(RuntimeFault::class.java) { ManagedStackRuntime.advance(snapshot, 0, layout) }
            for (invalid in listOf(null, 0L, "snapshot"))
                assertThrows(RuntimeFault::class.java) { ManagedStackRuntime.stackFields(invalid, layout) }
        }
    }

    @Test fun ipeUsesAbsoluteFieldBytesWithinDestinationViewAndKeepsImmutableStringsAlive() {
        val layout = layout(); val storage = ManagedAllocation.mutable(110, 8)
        val destination = ManagedAddress.fromAllocation(storage).plus(7)
        lateinit var key: ManagedAddress
        lateinit var retained: ManagedStackSnapshot
        lateinit var oldLabel: ManagedAddress
        context { language ->
            retained = capture(language)
            key = ManagedStackRuntime.frameInfo(retained, 0, layout).second
            storage.fill(0, storage.size, 0x5a)
            assertEquals(1L, ManagedStackRuntime.lookupIpe(key.plus(-1).plus(1), destination, layout))
            assertTrue(key.sameLocation(storage.readAddressByteOffset(7 + 3)))
            assertEquals("THC managed diagnostic frame", text(storage, 7, layout, "Name").utf8())
            assertEquals("", text(storage, 7, layout, "TyDesc").utf8())
            oldLabel = text(storage, 7, layout, "Label")
            assertEquals("workλ", oldLabel.utf8())
            assertEquals("real-unit", text(storage, 7, layout, "Unit").utf8())
            assertEquals("Actual.Module", text(storage, 7, layout, "Module").utf8())
            assertEquals("Actual.hs", text(storage, 7, layout, "File").utf8())
            assertEquals("7:3-8:10 (end exclusive)", text(storage, 7, layout, "Span").utf8())
            assertEquals(30L, unsigned(destination, 19 + 8, 4))
            assertThrows(RuntimeFault::class.java) { storage.readAddressByteOffset(7 + 19 + 8) }
            assertEquals(1L, ManagedStackRuntime.lookupIpe(key, destination, layout))
            assertSame(oldLabel, text(storage, 7, layout, "Label"))
            assertThrows(RuntimeFault::class.java) { oldLabel.writeWord8(0, 0) }
            for (i in 0L until 7L) assertEquals(0x5aL, storage.readByte(i))
            for (i in 98L until 110L) assertEquals(0x5aL, storage.readByte(i))
        }
        assertEquals("workλ", oldLabel.utf8())
        assertEquals("Actual.Module", text(storage, 7, layout, "Module").utf8())
        assertTrue(key.sameLocation(storage.readAddressByteOffset(10)))
        assertTrue(retained.renderLines().first().contains("Actual.hs"))
        context { _ ->
            assertThrows(RuntimeFault::class.java) { ManagedStackRuntime.stackInfo(retained, layout) }
            assertEquals(0L, ManagedStackRuntime.lookupIpe(key, destination, layout))
            assertSame(oldLabel, text(storage, 7, layout, "Label"))
        }
    }

    @Test fun anonymousMissingMetadataNeverUsesDebugNamesAsBindingProvenance(): Unit = context { language ->
        val layout = layout(); val snapshot = capture(language, false)
        val storage = ManagedAllocation.mutable(91, 8)
        val key = ManagedStackRuntime.frameInfo(snapshot, 0, layout).second
        assertEquals(1L, ManagedStackRuntime.lookupIpe(key, ManagedAddress.fromAllocation(storage), layout))
        for (field in listOf("Label", "Unit", "Module", "File", "Span", "TyDesc"))
            assertEquals("", text(storage, 0, layout, field).utf8(), field)
        val standalone = capture(null)
        assertNull(standalone.ownerToken)
        assertThrows(RuntimeFault::class.java) { ManagedStackRuntime.stackInfo(standalone, layout) }
        assertThrows(RuntimeFault::class.java) { ManagedStackRuntime.stackInfo(42L, layout) }
    }

    @Test fun rejectedDestinationsAndUnknownKeysNeverPartiallyWrite() = context { language ->
        val layout = layout(); val key = ManagedStackRuntime.frameInfo(capture(language), 0, layout).second
        for (exposure in listOf("raw", "native", "short", "width", "partial")) {
            val storage = ManagedAllocation.mutable(100, if (exposure == "width") 4 else 8)
            storage.fill(0, 100, 0x42)
            val old = ManagedAddress.fromHex("6f6c64")
            if (exposure == "partial") storage.writeAddressByteOffset(0, old)
            val raw = when (exposure) { "raw" -> storage.rawBytesIfPointerFree(); "native" -> storage.exposeToNative(); else -> null }
            val destination = ManagedAddress.fromAllocation(storage).plus(if (exposure == "short") 10 else 3)
            assertThrows(RuntimeFault::class.java, { ManagedStackRuntime.lookupIpe(key, destination, layout) }, exposure)
            raw?.let { assertTrue(it.all { byte -> byte == 0x42.toByte() }) }
            if (exposure == "partial") assertSame(old, storage.readAddressByteOffset(0))
            for (i in (if (exposure == "partial") 8L else 0L) until 100L) assertEquals(0x42L, storage.readByte(i), exposure)
        }
        for (destination in listOf(ManagedAddress.nullAddress(), ManagedAddress.fromHex("00".repeat(100)),
                ManagedAddress.fromByteArray(ByteArray(100)), ManagedAddress.fromAllocation(ManagedAllocation.immutable(ByteArray(100), 8))))
            assertThrows(RuntimeFault::class.java) { ManagedStackRuntime.lookupIpe(key, destination, layout) }
        assertEquals(0L, ManagedStackRuntime.lookupIpe(ManagedAddress.nullAddress(), ManagedAddress.nullAddress(), layout))
    }

    @Test fun layoutMismatchesAndMalformedIpeLayoutsFailBeforeAnyWrite() = context { language ->
        val snapshot = capture(language); val layout = layout()
        val key = ManagedStackRuntime.frameInfo(snapshot, 0, layout).second
        val storage = ManagedAllocation.mutable(100, 8); storage.fill(0, 100, 0x33)
        val destination = ManagedAddress.fromAllocation(storage)
        assertThrows(RuntimeFault::class.java) { ManagedStackRuntime.frameInfo(snapshot, 0, layout(abi = "other")) }
        assertThrows(RuntimeFault::class.java) { ManagedStackRuntime.lookupIpe(key, destination, layout(abi = "other")) }
        for (changes in listOf(mapOf("tablesNextToCode" to false), mapOf("infoProvDescBytes" to 8),
                mapOf("infoProvNameOffset" to 8), mapOf("infoProvEntInfoOffset" to 20))) {
            val invalid = layout(changes)
            assertThrows(RuntimeFault::class.java) { ManagedStackRuntime.stackInfo(snapshot, invalid) }
            assertThrows(RuntimeFault::class.java) { ManagedStackRuntime.lookupIpe(key, destination, invalid) }
        }
        assertTrue(storage.copyBytesOut(0, 100).all { it == 0x33.toByte() })
    }

    @Test fun invalidProvenanceAndAllocationCopyRangesAreTransactional() = context { language ->
        val layout = layout(); val key = ManagedStackRuntime.frameInfo(capture(language, occurrence = "bad\u0000name"), 0, layout).second
        val storage = ManagedAllocation.mutable(100, 8); storage.fill(0, 100, 0x77)
        val destination = ManagedAddress.fromAllocation(storage).plus(3)
        assertThrows(RuntimeFault::class.java) { ManagedStackRuntime.lookupIpe(key, destination, layout) }
        val source = ManagedAllocation.mutable(16, 8)
        source.writeAddressByteOffset(0, ManagedAddress.fromHex("61"))
        for ((offset, count) in listOf(-1L to 1L, 1L to 7L, 0L to -1L, 0L to 17L, Long.MAX_VALUE to 1L))
            assertThrows(RuntimeFault::class.java) { destination.copyFromAllocationBytes(source, offset, count) }
        assertTrue(storage.copyBytesOut(0, 100).all { it == 0x77.toByte() })
    }
}
