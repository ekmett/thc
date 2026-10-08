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
        assertTrue(parsed.getModules().stream().allMatch(it -> it.getArtifact().path().toString().endsWith(".cbd")));
        try (var files = Files.list(directory)) { assertEquals(0L, files.count()); }
    }
    @Test void publishedOwnershipCannotBeChangedThroughLists() {
        var parsed = CoreUnitDirectory.read(manifest(List.of(unit(List.of(module("A"))))));
        var owner = Objects.requireNonNull(parsed.owner("unit:A.entry"));
        var dependencies = new ArrayList<>(List.of("dependency"));
        var modules = new ArrayList<>(List.of(owner));
        var record = new CoreUnitDirectory.UnitRecord("unit", dependencies, modules);
        dependencies.clear();
        modules.clear();
        assertEquals(List.of("dependency"), record.depends());
        assertEquals(List.of(owner), record.modules());
        assertThrows(UnsupportedOperationException.class, () -> record.depends().clear());
        assertThrows(UnsupportedOperationException.class, () -> parsed.getUnits().clear());
        assertThrows(UnsupportedOperationException.class, () -> parsed.getModules().clear());
        assertThrows(UnsupportedOperationException.class, () -> parsed.getUnits().getFirst().getModules().clear());
        assertEquals(List.of(owner), parsed.getModules());
        assertSame(owner, parsed.owner("unit:A.entry"));
    }
    @Test void modulelessDependencyIdentitySurvivesAlongsideCompactModules() throws Exception {
        var empty = map("id", "rts", "depends", List.of(), "modules", List.of());
        var parsed = Objects.requireNonNull(CoreUnitDirectory.read(manifest(List.of(empty,
            with(unit(List.of(module("A"))), "depends", List.of("rts"))))));
        assertEquals(List.of("rts", "unit"), parsed.getUnits().stream().map(it -> it.getId()).toList());
        assertEquals(List.of("rts"), parsed.getUnits().getLast().getDepends());
        try (var files = Files.list(directory)) { assertEquals(0L, files.count()); }
    }
    @Test void fixedMainAliasResolvesNamedOwnersAndRejectsAmbiguity() {
        for (String name : List.of("Main", "NamedMain")) {
            var parsed = CoreUnitDirectory.read(manifest(List.of(unit(List.of(with(module(name), "mainAlias", true))))));
            assertEquals(name, parsed.owner("main::Main.main").name());
            assertNull(parsed.owner("main::NamedMain.main"));
        }
        var ambiguous = CoreUnitDirectory.read(manifest(List.of(unit(List.of(
            with(module("Main"), "mainAlias", true), with(module("NamedMain"), "mainAlias", true))))));
        assertThrows(IllegalArgumentException.class, () -> ambiguous.owner("main::Main.main"));
        assertNull(CoreUnitDirectory.read(manifest(List.of(unit(List.of(module("NamedMain"))))))
            .owner("main::Main.main"));
    }
    @Test void jsonAndBundleStorageAreNotRuntimeInputs() {
        var artifact = map("path", directory.resolve("old.jsons").toString(), "sha256", hash);
        var empty = unit(List.of());
        for (var old : List.of(with(empty, "json", artifact, "symbols", artifact),
                with(empty, "bundle", artifact),
                unit(List.of(without(module("A"), "compact"))))) {
            assertThrows(IllegalArgumentException.class, () -> CoreUnitDirectory.read(manifest(List.of(old))));
        }
        assertNotNull(CoreUnitDirectory.read(manifest(List.of(empty))));
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
