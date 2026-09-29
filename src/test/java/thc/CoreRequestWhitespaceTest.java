// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoreRequestWhitespaceTest {
    @Test void shutdownIdentityRejectsTheOriginalUnicodeBlankSet() {
        for (String whitespace : List.of("\u00a0", "\u2007", "\u202f", " \t\u00a0\n")) {
            assertThrows(IllegalArgumentException.class, () -> CoreModules.request(
                    List.of(), "entry", true, false, "ast", true, true,
                    whitespace, null, false));
        }
    }
}
