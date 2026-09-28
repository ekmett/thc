// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.frame.FrameDescriptor;
import java.util.List;
import kotlin.Unit;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.Main;
import static org.junit.jupiter.api.Assertions.*;

class BytecodeMaskHostUnwindTest {
    @Test void hostFaultRestoresMaskAcrossActionAndHandlerCalls() {
        try (var context = Main.executionContext(false)) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var emptyTuple = new CoreRepresentation(CoreKind.UNKNOWN, false, true, List.of(), List.of(), null, null, null, null);
                var destination = new BytecodeTupleSlots(new TupleShape(emptyTuple, language), new LocalAccessor[0], false, false);
                var metrics = new Metrics(false);
                var force = new Force(metrics);
                var frame = Truffle.getRuntime().createVirtualFrame(new Object[0], FrameDescriptor.newBuilder().build());
                var actionCall = new TupleDispatch(destination, metrics, 1, false);
                var handlerCall = new TupleDispatch(destination, metrics, 2, false);
                SynchronousMasking.set(force, MaskingState.MASKED_INTERRUPTIBLE);
                assertThrows(RuntimeFault.class, () -> BytecodeRoot.InvokeIOAction.run(frame, destination, metrics, 17L,
                    MaskingState.UNMASKED, actionCall, force));
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(force));
                SynchronousMasking.set(force, MaskingState.MASKED_INTERRUPTIBLE);
                assertThrows(RuntimeFault.class, () -> BytecodeRoot.InvokeIOHandler.run(frame, destination, metrics, 17L, Unit.INSTANCE,
                    MaskingState.UNMASKED, handlerCall, force));
                assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(force));
            } finally { context.leave(); }
        }
    }
}
