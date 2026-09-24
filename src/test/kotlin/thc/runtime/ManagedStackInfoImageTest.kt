// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteOrder

class ManagedStackInfoImageTest {
    private val platform = (if (System.getProperty("os.arch").lowercase() in setOf("arm64", "aarch64")) "aarch64" else "x86_64") +
        (if (System.getProperty("os.name").startsWith("Mac")) "-osx" else "-linux")
    private val endian = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) "little" else "big"

    // Same complete synthetic typed-layout construction used by CoreZipBundleTest.
    private fun fields(): Map<String, Any?> = mapOf(
        "schema" to 1, "profiled" to false, "wordBytes" to 8, "targetPlatform" to platform,
        "tablesNextToCode" to true, "endianness" to endian,
        "infoTableBytes" to 16, "infoTablePtrsOffset" to 0, "infoTablePtrsBytes" to 4,
        "infoTableNptrsOffset" to 4, "infoTableNptrsBytes" to 4,
        "infoTableTypeOffset" to 8, "infoTableTypeBytes" to 4, "infoTableSrtOffset" to 12, "infoTableSrtBytes" to 4,
        "infoProvEntBytes" to 72, "infoProvBytes" to 64, "infoProvEntInfoOffset" to 0, "infoProvEntProvOffset" to 8,
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
    private fun document(fields: Map<String, Any?> = fields()): Map<String, Any?> = mapOf(
        "format" to "thc-target-layout", "schema" to 1,
        "compiler" to mapOf("id" to "ghc-9.14.1", "abi" to "info-image-test", "platform" to platform, "way" to "dynamic-nonprofiling"),
        "layout" to fields)
    private fun layout(changes: Map<String, Any?> = emptyMap()) = TargetLayout.fromDocument(document(fields() + changes))
    private val factories = listOf<(TargetLayout) -> ManagedStackInfoImage>(ManagedStackInfoImage::stack, ManagedStackInfoImage::frame)

    @Test fun exactStackAndZeroPayloadDiagnosticFrameBytes() {
        val layout = layout()
        for ((factory, ordinal) in factories.zip(listOf(53, 30))) {
            val image = factory(layout)
            val expected = ByteArray(16)
            expected[if (endian == "little") 8 else 11] = ordinal.toByte()
            assertEquals(16, image.byteSize)
            assertArrayEquals(expected, image.copyBytes()) // includes all zero fields and padding
        }
    }

    @Test fun targetOffsetsAndIntegerWidthsAreAppliedExactlyOnce() {
        for (width in listOf(1, 2, 4, 8)) {
            val changes = mutableMapOf<String, Any?>("infoTableBytes" to 4 * width + 4)
            for ((index, name) in listOf("Srt", "Type", "Ptrs", "Nptrs").withIndex()) {
                changes["infoTable${name}Offset"] = 2 + index * width
                changes["infoTable${name}Bytes"] = width
            }
            val layout = layout(changes)
            for ((factory, ordinal) in factories.zip(listOf(53, 30))) {
                val expected = ByteArray(4 * width + 4)
                expected[2 + width + if (endian == "little") 0 else width - 1] = ordinal.toByte()
                assertArrayEquals(expected, factory(layout).copyBytes(), "width=$width/$ordinal")
            }
        }
    }

    @Test fun returnedBytesNeverExposeOrShareImageStorage() {
        for (factory in factories) {
            val first = factory(layout()); val second = factory(layout())
            val expected = first.copyBytes(); val changed = first.copyBytes()
            assertNotSame(expected, changed)
            changed.fill(0x7f)
            assertArrayEquals(expected, first.copyBytes()); assertArrayEquals(expected, second.copyBytes())
        }
    }

    @Test fun nonTncUnsupportedWidthsAndEveryFieldOverlapReject() {
        for (factory in factories) {
            assertTrue(assertThrows(IllegalArgumentException::class.java) { factory(layout(mapOf("tablesNextToCode" to false))) }
                .message!!.contains("tables-next-to-code"))
            val names = listOf("Ptrs", "Nptrs", "Type", "Srt")
            for (name in names) for (width in listOf(3, 5, 6, 7, 9, 16)) {
                val changes = names.withIndex().associate { (index, field) -> "infoTable${field}Offset" to index * 16 } +
                    mapOf("infoTableBytes" to 64, "infoTable${name}Bytes" to width)
                assertTrue(assertThrows(IllegalArgumentException::class.java) { factory(layout(changes)) }.message!!.contains("width"))
            }
            for (i in names.indices) for (j in i + 1 until names.size) for (displacement in listOf(0, 3))
                assertTrue(assertThrows(IllegalArgumentException::class.java) {
                    factory(layout(mapOf("infoTable${names[j]}Offset" to 4 * i + displacement)))
                }.message!!.contains("Overlapping"))
        }
    }

    @Test fun missingLayoutAndAlreadyInvalidTypedFieldsFailAtTheCallerBoundary() {
        assertNull(TargetLayout.fromReceipts(emptyMap<String, Any?>(), emptyMap<String, Any?>()))
        assertThrows(RuntimeException::class.java) { TargetLayout.fromDocument(null) }
        for (missing in listOf("tablesNextToCode", "infoTableTypeBytes", "infoTableTypeOffset"))
            assertThrows(RuntimeException::class.java) { TargetLayout.fromDocument(document(fields() - missing)) }
        for (changes in listOf(mapOf("tablesNextToCode" to "true"), mapOf("infoTableTypeBytes" to 0),
            mapOf("infoTableTypeOffset" to 16), mapOf("closureStack" to 256), mapOf("closureRetSmall" to -1),
            mapOf("wordBytes" to 4), mapOf("endianness" to if (endian == "little") "big" else "little")))
            assertThrows(RuntimeException::class.java) { layout(changes) }
    }
}
