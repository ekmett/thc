// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import thc.Language;
import thc.Main;
import static org.junit.jupiter.api.Assertions.*;

public class BytecodeProcessArgumentsTest {
    @Test public void nullableMetadataPreservesPrimitiveAndObjectLocals() {
        try (var context = Main.executionContext()) {
            context.initialize("thc"); context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                for (var operation : ProcessOp.values()) {
                    var locals = new ArrayList<LocalAccessor>();
                    var root = BytecodeRootGen.create(language, BytecodeConfig.DEFAULT, b -> {
                        b.beginRoot();
                        for (int i = 0; i < operation.getArguments().size(); i++) locals.add(LocalAccessor.constantOf(b.createLocal("operand " + i, null)));
                        b.beginReturn(); b.emitLoadConstant(0L); b.endReturn(); b.endRoot();
                    }).getNode(0);
                    var bytecode = root.getBytecodeNode();
                    var frame = Truffle.getRuntime().createVirtualFrame(new Object[]{0L}, root.getFrameDescriptor());
                    var reader = new BytecodeProcessArguments(operation, locals.toArray(LocalAccessor[]::new));
                    var address = ManagedAddress.fromHex("1122");
                    Object[] previous = null;
                    // Null metadata means object-carried State#, not an absent operand.
                    for (var state : new Object[]{thc.runtime.Unit.INSTANCE, null}) {
                        var expected = new ArrayList<Object>();
                        for (int index = 0; index < operation.getArguments().size(); index++) {
                            var representation = operation.getArguments().get(index);
                            Object value = switch (representation) {
                                case "Int32Rep" -> state == null ? (long) Integer.MIN_VALUE + index : (long) Integer.MAX_VALUE - index;
                                case "AddrRep" -> address;
                                case null -> state;
                                default -> throw new IllegalStateException("Unexpected process argument metadata: " + representation);
                            };
                            if (value instanceof Long number) locals.get(index).setInt(bytecode, frame, number.intValue());
                            else locals.get(index).setObject(bytecode, frame, value);
                            expected.add(value);
                        }
                        var values = reader.read(bytecode, frame);
                        assertNotSame(previous, values);
                        assertEquals(expected.size(), values.length);
                        for (int index = 0; index < expected.size(); index++) {
                            var value = expected.get(index);
                            if (value instanceof Long) assertEquals(value, values[index]); else assertSame(value, values[index]);
                        }
                        previous = values;
                    }
                }
            } finally { context.leave(); }
        }
    }
}
