// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import org.junit.jupiter.api.Test;
import java.util.List;

public class CompactSerializedNativeTest {
    @Test public void originalSerializedApiRoundTripsCopiedBlocksSharingCyclesAndStaticRoots() throws Exception {
        new CompactLibraryFixture("build/compact-serialization", List.of("roundTrip", "cycleRoundTrip", "multipleBlocks", "emptyRoundTrip"),
            (entry, input) -> switch (entry) {
                case "roundTrip" -> 4 * input + 1006; case "cycleRoundTrip", "emptyRoundTrip" -> input + 100;
                case "multipleBlocks" -> 8192 * input + 33550436; default -> throw new IllegalStateException(entry);
            }, "roundTrip").run();
    }
}
