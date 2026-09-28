// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.nio.charset.MalformedInputException;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.LINUX)
final class GuestArgumentsEncodingTest {
    @Test void malformedTextKeepsTheOriginalExceptionAndDoesNotPublishAnImage() {
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            context.initialize("thc");
            context.enter();
            try {
                var arguments = Language.currentState(null).getArguments();
                var nil = ManagedAddress.Companion.nullAddress();
                arguments.initialize("bad\uD800", new String[0]);
                var failure = assertThrows(MalformedInputException.class, () -> arguments.get(nil, nil));
                assertEquals(1, failure.getInputLength());
                assertDoesNotThrow(() -> arguments.initialize("valid", new String[] {"lambda-\u03bb"}));
                assertDoesNotThrow(() -> arguments.get(nil, nil));
                assertThrows(RuntimeFault.class, () -> arguments.initialize("late", new String[0]));
            } finally {
                context.leave();
            }
        }
    }
}
