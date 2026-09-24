// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.FrameDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import thc.Language
import thc.executionContext

class BytecodeMaskHostUnwindTest {
    @Test fun hostFaultRestoresMaskAcrossActionAndHandlerCalls() {
        executionContext().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val emptyTuple = CoreRepresentation(CoreKind.UNKNOWN, present = true,
                    primReps = emptyList(), components = emptyList())
                val destination = BytecodeTupleSlots(TupleShape(emptyTuple, language), emptyArray())
                val metrics = Metrics(false)
                val force = Force(metrics)
                val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), FrameDescriptor.newBuilder().build())
                val actionCall = TupleDispatch(destination, metrics, 1, false)
                val handlerCall = TupleDispatch(destination, metrics, 2, false)

                SynchronousMasking.set(force, MaskingState.MASKED_INTERRUPTIBLE)
                assertThrows(RuntimeFault::class.java) {
                    BytecodeRoot.InvokeIOAction.run(frame, destination, metrics, 17L,
                        MaskingState.UNMASKED, actionCall, force)
                }
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(force))

                SynchronousMasking.set(force, MaskingState.MASKED_INTERRUPTIBLE)
                assertThrows(RuntimeFault::class.java) {
                    BytecodeRoot.InvokeIOHandler.run(frame, destination, metrics, 17L, Unit,
                        MaskingState.UNMASKED, handlerCall, force)
                }
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(force))
            } finally { context.leave() }
        }
    }
}
