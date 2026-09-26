// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.BytecodeConfig
import com.oracle.truffle.api.bytecode.BytecodeRootNode
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

class ForceLocalWritebackTest {
    private fun withLanguage(action: (Language) -> Unit) {
        executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
            finally { context.leave() }
        }
    }

    private fun target(language: Language, binding: Any, resumed: Boolean): RootCallTarget =
        BytecodeRootGen.create(language, BytecodeConfig.DEFAULT) { b ->
            b.beginRoot()
            val local = b.createLocal("forced binding", null)
            b.beginBlock()
            b.beginStoreLocal(local); b.emitLoadConstant(binding); b.endStoreLocal()
            b.beginReturn()
            b.beginBlock()
            if (resumed) {
                val child = binding as Thunk
                b.beginResumeForcedLocal(local, false)
                b.emitLoadConstant(ThunkSuspended(child))
                b.emitLoadConstant(ChildResume(child.value, null))
                b.endResumeForcedLocal()
            } else {
                b.beginForceLocal(Metrics(true), local, false, false)
                b.emitLoadLocal(local)
                b.endForceLocal()
            }
            b.emitLoadLocal(local)
            b.endBlock()
            b.endReturn()
            b.endBlock()
            b.endRoot()
        }.getNode(0).callTarget

    private fun checkIdentity(resumed: Boolean) = withLanguage { language ->
        for (value in listOf(1_000_000L, 1.25f, 2.5, true, Any())) {
            var evaluations = 0
            val body = object : RootNode(null) {
                override fun execute(frame: VirtualFrame): Any {
                    evaluations++
                    return value
                }
            }.callTarget
            val child = Thunk(body, null)
            if (resumed) { child.value = value; child.state = 2 }
            val caller = target(language, child, resumed)
            repeat(20) {
                assertSame(value, Calls.target(caller, arrayOf(0L)),
                    "A thunk's object local must reuse its published result")
                assertSame(value, child.value)
                assertEquals(2, child.state)
            }
            assertEquals(if (resumed) 0 else 1, evaluations, "Writeback must not replay the thunk")
            val root = caller.rootNode as BytecodeRootNode
            assertEquals(FrameSlotKind.Object, root.bytecodeNode.locals.single().typeProfile)
        }
    }

    @Test fun ordinaryForceReusesTheBoxedThunkResult() = checkIdentity(false)

    @Test fun resumedForceReusesTheBoxedThunkResult() = checkIdentity(true)

    @Test fun alreadyScalarBindingsKeepTheirPrimitiveSlots() = withLanguage { language ->
        for ((value, tag) in listOf(
            1_000_000L to FrameSlotKind.Long, 1.25f to FrameSlotKind.Float,
            2.5 to FrameSlotKind.Double, true to FrameSlotKind.Boolean)) {
            val caller = target(language, value, false)
            repeat(20) { assertEquals(value, Calls.target(caller, arrayOf(0L))) }
            val root = caller.rootNode as BytecodeRootNode
            assertEquals(tag, root.bytecodeNode.locals.single().typeProfile)
        }
    }
}
