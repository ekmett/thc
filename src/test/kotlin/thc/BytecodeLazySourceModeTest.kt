// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.BytecodeConfig
import com.oracle.truffle.api.source.Source
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.runtime.BytecodeRootGen
import thc.runtime.Calls

/** The source-mode query must precede construction of every lazy debug argument. */
class BytecodeLazySourceModeTest {
    @Test fun defaultConstructionIsColdAndExplicitSourceReplayPreservesCodeAndIdentity() {
        executionContext().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val origin = Any()
                var debugReads = 0
                val replayOrigins = mutableListOf<Any>()
                fun resolveSource(): Source {
                    debugReads++
                    return Source.newBuilder("thc", "answer = 42\n", "Lazy.Source.hs").build()
                }
                val nodes = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT) { b ->
                    replayOrigins += origin
                    if (b.isParsingSources()) {
                        b.beginSource(resolveSource())
                        b.beginSourceSection(0, 11)
                    }
                    b.beginRoot()
                    b.beginReturn(); b.emitLoadArgument(0); b.endReturn()
                    b.endRoot()
                    if (b.isParsingSources()) { b.endSourceSection(); b.endSource() }
                }
                val root = nodes.getNode(0)
                val target = root.callTarget
                fun instructions() = root.bytecodeNode.instructions.map { instruction ->
                    instruction.name to instruction.arguments.map { it.toString() }
                }
                val before = instructions()
                assertEquals(0, debugReads)
                assertFalse(root.bytecodeNode.hasSourceInformation())
                assertNull(root.sourceSection)
                assertEquals(42L, Calls.target(target, arrayOf(42L)))
                assertEquals(0, debugReads, "Ordinary execution must not resolve source arguments")
                // Instruction specialization is independent of source replay.
                val executed = instructions()
                assertEquals(before.map { it.first.substringBefore('$') }, executed.map { it.first.substringBefore('$') })
                root.bytecodeNode.ensureSourceInformation()
                assertEquals(1, debugReads)
                assertEquals(2, replayOrigins.size)
                assertTrue(replayOrigins.all { it === origin })
                assertSame(root, nodes.getNode(0))
                assertSame(target, root.callTarget)
                assertEquals(executed, instructions(), "Source replay must preserve instructions and arguments")
                assertEquals("Lazy.Source.hs", root.sourceSection.source.name)
                assertEquals("answer = 42", root.sourceSection.characters.toString())
                root.bytecodeNode.ensureSourceInformation()
                assertEquals(1, debugReads, "Repeated source access must not reparse")
                assertEquals(77L, Calls.target(target, arrayOf(77L)))
            } finally { context.leave() }
        }
    }
}
