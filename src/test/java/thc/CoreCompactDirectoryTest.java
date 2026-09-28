// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

class CoreCompactDirectoryTest {
    @TempDir Path directory;
    private final String hash = "a".repeat(64);
    private Map<String, Object> module(String name) {
        return map("name", name, "boundary", "optimized-Core-after-Tidy-before-CorePrep", "sha256", hash,
            "compact", map("path", directory.resolve(name + ".cbd").toString(), "sha256", hash, "format", CoreCompactFormat.NAME),
            "containsDelimitedControl", false, "registrationObligations", false, "mainAlias", false, "packageScalarDeclarations", false);
    }
    private Map<String, Object> unit(List<Map<String, Object>> modules) {
        return map("id", "unit", "depends", List.of(), "modules", modules);
    }
    private Map<String, Object> manifest(List<Map<String, Object>> units) {
        return map("format", "thc-core-packages", "schema", 1L, "ghc", "9.14.1", "units", units);
    }
    @Test void directoryConstructionNamesColdModulesWithoutOpeningAnyArtifact() throws Exception {
        var document = manifest(List.of(unit(List.of(module("A"), module("B"), module("C")))));
        var parsed = Objects.requireNonNull(CoreUnitDirectory.read(document));
        assertEquals(3, parsed.getModules().size());
        assertEquals("A", Objects.requireNonNull(parsed.owner("unit:A.entry")).getName());
        assertEquals("B", Objects.requireNonNull(parsed.owner("unit:B.cold")).getName());
        assertNull(parsed.owner("absent:D.f"));
        assertTrue(parsed.getModules().stream().allMatch(it -> it.getStorage() instanceof CoreUnitDirectory.CompactStorage));
        assertTrue(parsed.getUnits().stream().allMatch(it -> it.getJson() == null && it.getSymbols() == null));
        try (var files = Files.list(directory)) { assertEquals(0L, files.count()); }
    }
    @Test void modulelessCompatibilityIdentitySurvivesAlongsideCompactModules() throws Exception {
        var empty = map("id", "rts", "depends", List.of(), "modules", List.of(),
            "bundle", map("path", directory.resolve("unopened.zip").toString(), "sha256", hash));
        var parsed = Objects.requireNonNull(CoreUnitDirectory.read(manifest(List.of(empty,
            with(unit(List.of(module("A"))), "depends", List.of("rts"))))));
        assertEquals(List.of("rts", "unit"), parsed.getUnits().stream().map(it -> it.getId()).toList());
        assertEquals(List.of("rts"), parsed.getUnits().getLast().getDepends());
        try (var files = Files.list(directory)) { assertEquals(0L, files.count()); }
    }
    @Test void ambiguousProtocolsBadFormatsAndDuplicateArtifactPathsRejectWithoutOpening() throws Exception {
        var original = module("A");
        var artifact = (Map<?, ?>) original.get("compact");
        var variants = List.of(with(original, "compact", with(artifact, "format", "unknown")),
            with(original, "compact", without(artifact, "format")), with(original, "start", 0L),
            without(original, "registrationObligations"));
        for (var variant : variants) assertThrows(RuntimeException.class, () ->
            CoreUnitDirectory.read(manifest(List.of(unit(List.of(variant))))));
        assertThrows(IllegalArgumentException.class, () -> CoreUnitDirectory.read(
            manifest(List.of(unit(List.of(original, with(module("B"), "compact", artifact)))))));
        assertThrows(IllegalArgumentException.class, () -> CoreUnitDirectory.read(
            manifest(List.of(with(unit(List.of(original)), "json", artifact)))));
        try (var files = Files.list(directory)) { assertEquals(0L, files.count()); }
    }
}
