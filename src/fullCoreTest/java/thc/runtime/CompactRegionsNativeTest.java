// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import org.junit.jupiter.api.Test;
import java.util.List;

public class CompactRegionsNativeTest {
    @Test public void originalCompactLibraryMatchesNativeForGraphsCyclesArraysAndExceptionsOnBothBackends() throws Exception {
        new CompactLibraryFixture("build/compact-regions", List.of("ordinary", "sharing", "cycleCase", "rejectedObjects", "frozenArray"),
            (entry, input) -> switch (entry) {
                case "ordinary", "sharing" -> 4 * input + 106; case "cycleCase" -> input + 100;
                case "rejectedObjects" -> input + 1111; case "frozenArray" -> 2 * input; default -> throw new IllegalStateException(entry);
            }, "ordinary").run();
    }
}
