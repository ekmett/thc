// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

import java.io.InputStream;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.graalvm.nativeimage.VMRuntime;

/** Enable starts lazily, move them, and allocate after diagnostic borrows end. */
public final class HeapWalkSmoke {
    public static void main(String[] args) throws Exception {
        Object[] keep = {new byte[400], new Object(), new Object[17]};
        Path directory = Files.createTempDirectory("jam-heap-walk-");
        try {
            for (int round = 0; round != 2; ++round) {
                Path dump = directory.resolve("heap-" + round + ".hprof");
                VMRuntime.dumpHeap(dump.toString(), false);
                try (InputStream input = Files.newInputStream(dump)) {
                    String header = new String(input.readNBytes(19), StandardCharsets.US_ASCII);
                    if (!header.equals("JAVA PROFILE 1.0.2" + (char) 0))
                        throw new AssertionError("invalid heap dump: " + header);
                }
                Files.delete(dump);
                System.gc();
                keep[1] = new Object[31];
            }
        } finally {
            try (var paths = Files.list(directory)) {
                for (Path path : paths.toList()) Files.delete(path);
            }
            Files.delete(directory);
        }
        Reference.reachabilityFence(keep);
        System.out.println("Jam Native Image heap enumeration passed");
    }
}
