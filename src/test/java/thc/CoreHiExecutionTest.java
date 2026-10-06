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
 * scalar recursion, raw byte and IEEE literals, erased polymorphism and parameterized
 * recursive boxed fields, parameterized newtypes, GADTs, unpacking and nested
 * tuple/sum results across modules.
 * Polymorphic IO runs through its real installed declaration and the ordinary runIO
 * boundary. Native results come from the same source build; process creation is denied
 * and the runtime receives no CBD. */
class CoreHiExecutionTest {
    private static final Path INPUT = Path.of("build/native-hi-execution");
    private static final String UNIT = "thc-native-hi-scalar";
    private static final List<String> MODULES = List.of("NativeHiScalar", "NativeHiDependency", "NativeHiBox", "NativeHiBoxType", "GHC.Internal.Types");
    @TempDir Path directory;

    private List<String> interfaces() throws Exception {
        var paths = new ArrayList<String>();
        for (String module : MODULES) {
            Path file = directory.resolve(module + ".hi");
            Files.copy(INPUT.resolve(module + ".hi"), file, StandardCopyOption.REPLACE_EXISTING);
            paths.add(file.toString());
        }
        return paths;
    }

    private Path manifest() throws Exception {
        interfaces();
        var units = new LinkedHashMap<String,List<Object>>();
        for (String module : MODULES) {
            Path file = directory.resolve(module + ".hi");
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            var identity = CoreHiReader.read(file).module;
            units.computeIfAbsent(identity.unit(), ignored -> new ArrayList<>()).add(Map.of("name", identity.name(), "boundary", "optimized-Core-after-Tidy-before-CorePrep", "sha256", hash,
                    "interface", Map.of("format", "ghc-hi", "path", file.toString(), "sha256", hash),
                    "containsDelimitedControl", false, "registrationObligations", false,
                    "mainAlias", false, "packageScalarDeclarations", false));
        }
        Path manifest = directory.resolve("packages.json");
        Files.writeString(manifest, Json.stringify(Map.of("format", "thc-core-packages", "schema", 1,
                "ghc", "9.14.1", "units", units.entrySet().stream().map(unit ->
                    Map.of("id", unit.getKey(), "depends", List.of(), "modules", unit.getValue())).toList())));
        return manifest;
    }

    private String request(List<String> paths, String module, String entry, String backend) {
        return CoreModules.request(paths, UNIT + ":" + module + "." + entry,
                true, false, backend, true, false, null, false, false);
    }

    @Test void retainedCoreRunsWithoutProcessesOrCbd() throws Exception {
        var rows = Files.readAllLines(INPUT.resolve("native.tsv")).stream()
                .map(line -> Arrays.stream(line.split(" ")).mapToLong(Long::parseLong).toArray()).toList();
        assertFalse(rows.isEmpty());
        int[] literalBytes = {65, 0, 206, 187};
        // Thirds, ties to even on either side, negative subnormals and overflow.
        var ieee = Map.of(
                -3L, new long[]{0x3eaaaaabL, 0x3fd5555555555555L},
                0L, new long[]{0x3f800000L, 0x3ff0000000000000L},
                2L, new long[]{0x3f800002L, 0x3ff0000000000002L},
                7L, new long[]{0x80000001L, 0x8000000000000001L},
                31L, new long[]{0x7f800000L, 0x7ff0000000000000L});
        for (var row : rows) {
            assertArrayEquals(ieee.get(row[0]), new long[]{row[4], row[5]}, "native IEEE bits agree with independently derived boundaries");
            assertEquals(4 * row[0] + 8 + literalBytes[(int) (row[0] & 3)], row[1], "native result agrees with independent arithmetic and raw bytes");
            assertEquals(row[0] <= 0 ? 0 : row[0] * (row[0] + 1) / 2, row[2], "native recursion agrees with model");
            assertEquals(row[0] < 0 ? -7 : 3 * (row[0] + 2), row[3], "native constructor/case agrees with model");
        }
        List<String> paths = interfaces();
        for (String backend : List.of("ast", "bytecode")) {
            try (var context = Main.executionContext(false, false)) {
                var entry = context.eval("thc", request(paths, "NativeHiScalar", "entry", backend));
                var recursive = context.eval("thc", request(paths, "NativeHiScalar", "recursive", backend));
                var boxed = context.eval("thc", request(paths, "NativeHiBox", "entry", backend));
                var floatBits = context.eval("thc", request(paths, "NativeHiBox", "floatBits", backend));
                var doubleBits = context.eval("thc", request(paths, "NativeHiBox", "doubleBits", backend));
                for (var row : rows) {
                    assertEquals(row[1], entry.execute(row[0]).asLong(), backend + " cross-module/private call");
                    assertEquals(row[2], recursive.execute(row[0]).asLong(), backend + " recursive case");
                    assertEquals(row[3], boxed.execute(row[0]).asLong(), backend + " constructor/case");
                    assertEquals(row[4], floatBits.execute(row[0]).asLong(), backend + " float literal bits");
                    assertEquals(row[5], doubleBits.execute(row[0]).asLong(), backend + " double literal bits");
                }
                assertEquals(rows.getLast()[1], entry.execute(rows.getLast()[0]).asLong(), backend + " repeated call");
                for (int resultColumn : new int[]{1, 3, 4, 5}) {
                    var function = switch (resultColumn) {
                        case 1 -> entry; case 3 -> boxed; case 4 -> floatBits; default -> doubleBits;
                    };
                    // Exercise the suspended boxed field on its very first compiled call.
                    var first = resultColumn == 3 ? rows.getLast() : rows.getFirst();
                    var alternative = resultColumn == 3 ? rows.getFirst() : rows.getLast();
                    String label = backend + switch (resultColumn) {
                        case 1 -> " raw bytes"; case 3 -> " polymorphic boxed case";
                        case 4 -> " float literal"; default -> " double literal";
                    };
                    assertTrue(function.invokeMember("compile").asBoolean(), label + " native interface compilation");
                    var before = (Map<?, ?>) Json.parse(function.getMember("diagnostics").asString());
                    assertEquals(first[resultColumn], function.execute(first[0]).asLong(), label + " first installed call");
                    var after = (Map<?, ?>) Json.parse(function.getMember("diagnostics").asString());
                    assertTrue(((Number) after.get("compiledEntries")).longValue() > ((Number) before.get("compiledEntries")).longValue(),
                            label + " first installed call enters compiled guest code");
                    Main.installed(after);
                    assertEquals(alternative[resultColumn], function.execute(alternative[0]).asLong(), label + " installed alternative");
                }
            }
        }
        try (var files = Files.list(directory)) {
            assertEquals(MODULES.stream().map(module -> module + ".hi").collect(java.util.stream.Collectors.toSet()),
                    files.map(file -> file.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
        // Published native manifests retain the same input route and ownership.
        try (var context = Main.executionContext(false, false)) {
            var entry = context.eval("thc", request(List.of("@" + manifest()), "NativeHiScalar", "entry", "bytecode"));
            assertEquals(rows.getLast()[1], entry.execute(rows.getLast()[0]).asLong());
        }
    }

    @Test void polymorphicIoExecutesEffectsThroughTheOrdinaryRuntime() throws Exception {
        assertEquals(List.of("completed", "throws"), Files.readAllLines(INPUT.resolve("io.tsv")));
        var paths = interfaces();
        for (String backend : List.of("ast", "bytecode")) {
            try (var context = Main.executionContext(false, false)) {
                var actions = new ArrayList<org.graalvm.polyglot.Value>();
                for (String entry : List.of("goodMain", "badMain")) {
                    var action = context.eval("thc", CoreModules.request(paths, UNIT + ":NativeHiScalar." + entry,
                            true, false, backend, true, true, null, false, false));
                    assertFalse(action.canExecute());
                    assertTrue(action.canInvokeMember("runIO"));
                    actions.add(action);
                }
                var good = actions.get(0);
                var bad = actions.get(1);
                assertTrue(good.invokeMember("runIO").asBoolean(), backend + " writes and reads mutable state");
                var failure = assertThrows(PolyglotException.class, () -> bad.invokeMember("runIO"));
                assertTrue(failure.isGuestException());
                assertFalse(failure.isHostException());
                assertTrue(good.invokeMember("runIO").asBoolean(), backend + " failure preserves later action execution");
            }
        }
    }

    @Test void changedInterfaceIsRejectedBeforeExecution() throws Exception {
        Path manifest = manifest();
        var requests = List.of(request(List.of("@" + manifest), "NativeHiScalar", "entry", "bytecode"),
                request(MODULES.stream().map(module -> directory.resolve(module + ".hi").toString()).toList(), "NativeHiScalar", "entry", "bytecode"));
        Path source = directory.resolve("NativeHiScalar.hi");
        Files.write(source, new byte[]{0}, StandardOpenOption.APPEND);
        for (String request : requests) try (var context = Main.executionContext(false, false)) {
            var failure = assertThrows(PolyglotException.class, () -> context.eval("thc", request));
            assertTrue(failure.getMessage().contains("Native interface changed after ") && failure.getMessage().contains("NativeHiScalar.hi"), failure.getMessage());
        }
    }

    @Test void looseInputsCannotForgeAuthorityOrDuplicateOwners() throws Exception {
        var paths = interfaces();
        var duplicatePath = assertThrows(IllegalArgumentException.class,
                () -> request(List.of(paths.getFirst(), paths.getFirst()), "NativeHiScalar", "entry", "bytecode"));
        assertTrue(duplicatePath.getMessage().contains("Duplicate native interface path"));

        Path alias = directory.resolve("alias.hi");
        Files.copy(Path.of(paths.getFirst()), alias);
        String duplicate = request(List.of(paths.getFirst(), alias.toString()), "NativeHiScalar", "entry", "bytecode");
        try (var context = Main.executionContext(false, false)) {
            var failure = assertThrows(PolyglotException.class, () -> context.eval("thc", duplicate));
            assertTrue(failure.getMessage().contains("Duplicate or invalid GHC module"), failure.getMessage());
        }

        Path manifest = manifest();
        var document = (Map<?,?>) Json.parse(Files.readString(manifest));
        var unit = (Map<?,?>) ((List<?>) document.get("units")).getFirst();
        var module = (Map<String,Object>) ((List<?>) unit.get("modules")).getFirst();
        module.put("containsDelimitedControl", true);
        Files.writeString(manifest, Json.stringify(document));
        var summary = assertThrows(IllegalArgumentException.class,
                () -> request(List.of("@" + manifest), "NativeHiScalar", "entry", "bytecode"));
        assertTrue(summary.getMessage().contains("Native interface startup summary mismatch"), summary.getMessage());

        var forged = new LinkedHashMap<Object,Object>((Map<?, ?>) Json.parse(request(paths, "NativeHiScalar", "entry", "bytecode")));
        var artifact = new LinkedHashMap<Object,Object>((Map<?, ?>) ((List<?>) forged.get("nativeInterfaceFiles")).getFirst());
        artifact.put("capability", "invalid");
        forged.put("nativeInterfaceFiles", List.of(artifact));
        try (var context = Main.executionContext(false, false)) {
            var failure = assertThrows(PolyglotException.class, () -> context.eval("thc", Json.stringify(forged)));
            assertTrue(failure.getMessage().contains("Invalid native interface request capability"), failure.getMessage());
        }
    }

}
