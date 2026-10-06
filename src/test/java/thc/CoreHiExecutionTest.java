// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Ordinary GHC .hi files execute directly, including retained private RHSs,
 * scalar recursion and a separate module. Native results come from the same
 * source build; process creation is denied and the runtime receives no CBD. */
class CoreHiExecutionTest {
    private static final Path INPUT = Path.of("build/native-hi-execution");
    private static final String UNIT = "thc-native-hi-scalar";
    @TempDir Path directory;

    private Path manifest() throws Exception {
        var modules = new ArrayList<Object>();
        for (String module : List.of("NativeHiScalar", "NativeHiDependency")) {
            Path file = directory.resolve(module + ".hi");
            Files.copy(INPUT.resolve(module + ".hi"), file, StandardCopyOption.REPLACE_EXISTING);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            modules.add(Map.of("name", module, "boundary", "optimized-Core-after-Tidy-before-CorePrep", "sha256", hash,
                    "interface", Map.of("format", "ghc-hi", "path", file.toString(), "sha256", hash),
                    "containsDelimitedControl", false, "registrationObligations", false,
                    "mainAlias", false, "packageScalarDeclarations", false));
        }
        Path manifest = directory.resolve("packages.json");
        Files.writeString(manifest, Json.stringify(Map.of("format", "thc-core-packages", "schema", 1,
                "ghc", "9.14.1", "units", List.of(Map.of("id", UNIT, "depends", List.of(), "modules", modules)))));
        return manifest;
    }

    private String request(Path manifest, String entry, String backend) {
        return CoreModules.request(List.of("@" + manifest), UNIT + ":NativeHiScalar." + entry,
                true, false, backend, true, false, null, false, false);
    }

    @Test void retainedCoreRunsWithoutProcessesOrCbd() throws Exception {
        var rows = Files.readAllLines(INPUT.resolve("native.tsv")).stream()
                .map(line -> Arrays.stream(line.split(" ")).mapToLong(Long::parseLong).toArray()).toList();
        assertFalse(rows.isEmpty());
        for (var row : rows) {
            assertEquals(4 * row[0] + 8, row[1], "native result agrees with independent arithmetic");
            assertEquals(row[0] <= 0 ? 0 : row[0] * (row[0] + 1) / 2, row[2], "native recursion agrees with model");
        }
        Path manifest = manifest();
        for (String backend : List.of("ast", "bytecode")) {
            try (var context = Main.executionContext(false, false)) {
                var entry = context.eval("thc", request(manifest, "entry", backend));
                var recursive = context.eval("thc", request(manifest, "recursive", backend));
                for (var row : rows) {
                    assertEquals(row[1], entry.execute(row[0]).asLong(), backend + " cross-module/private call");
                    assertEquals(row[2], recursive.execute(row[0]).asLong(), backend + " recursive case");
                }
                assertEquals(rows.getLast()[1], entry.execute(rows.getLast()[0]).asLong(), backend + " repeated call");
            }
        }
        try (var files = Files.list(directory)) {
            assertEquals(Set.of("NativeHiScalar.hi", "NativeHiDependency.hi", "packages.json"),
                    files.map(file -> file.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test void changedInterfaceIsRejectedBeforeExecution() throws Exception {
        Path manifest = manifest();
        String request = request(manifest, "entry", "bytecode");
        Path source = directory.resolve("NativeHiScalar.hi");
        Files.write(source, new byte[]{0}, StandardOpenOption.APPEND);
        try (var context = Main.executionContext(false, false)) {
            var failure = assertThrows(PolyglotException.class, () -> context.eval("thc", request));
            assertTrue(failure.getMessage().contains("Native interface changed after publication"), failure.getMessage());
        }
    }
}
