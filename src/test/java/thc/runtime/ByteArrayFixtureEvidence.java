// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import thc.Json;
import static org.junit.jupiter.api.Assertions.*;

/** Closed inventories for the five related native fixtures, independent of their producer. */
@SuppressWarnings("unchecked")
final class ByteArrayFixtureEvidence {
    private ByteArrayFixtureEvidence() {}
    private static final Map<String, String> modules = Map.of("bytearray", "ByteArrayAudit", "mutable-bytearrays",
        "MutableByteArrayAudit", "resize-bytearrays", "ResizeByteArrayAudit", "mutable-bytearray-size",
        "MutableByteArraySizeAudit", "compare-byte-arrays", "CompareByteArraysAudit");
    private static final Map<String, List<String>> entries =
        Map.of("bytearray", List.of("shortBytes", "orderedBytes", "shortUncons", "copiedBytes"), "mutable-bytearrays",
            List.of("filledBytes", "movedBytes", "disjointBytes", "copiedMutableBytes", "copiedDisjointBytes",
                "publicReplicate"),
            "resize-bytearrays", List.of("resizedBytes", "resizedTwiceWrites"), "mutable-bytearray-size",
            List.of("freshSize", "pureSize", "resizedSizes", "pureAfterResize", "orderedSize"), "compare-byte-arrays",
            List.of("shortCompare", "shortPrefix", "shortSuffix", "rangeCompare", "aliasCompare"));
    private static final Map<String, String> drivers = Map.of("bytearray", "NativeByteArray.hs", "mutable-bytearrays",
        "NativeMutableByteArrays.hs", "compare-byte-arrays", "NativeCompareByteArrays.hs");
    private static final Set<String> originalSources =
        Set.of("nih/pinned/ghc-9.14.1/libraries/ghc-internal/LICENSE", "nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Base.hs",
            "nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/List.hs", "nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Exception/Type.hs-boot",
            "nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/IO.hs-boot", "nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Num.hs-boot",
            "nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Enum.hs-boot", "nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/GHC/Internal/Real.hs-boot");
    private static Map<String, Object> read(File root, String path) throws IOException {
        return (Map<String, Object>) Json.parse(Files.readString(new File(root, path).toPath()));
    }
    private static String digest(File file) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())));
    }
    public static void verify(File root, String group, Map<String, Object> manifest)
        throws Exception {
        String directory = "build/" + group, module = Objects.requireNonNull(modules.get(group));
        var names = Objects.requireNonNull(entries.get(group));
        boolean original = List.of("bytearray", "compare-byte-arrays").contains(group),
                generated = drivers.containsKey(group);
        var commands = new ArrayList<>(List.of("ghc-version", "ghc-info", "bytestring-version",
            "bytestring-description", "native-build", "native-oracle"));
        if (!original)
            commands.addAll(List.of("compiler-build", "primop-coverage"));
        if (!generated)
            commands.add("native-inputs");
        for (var stage : List.of("pre", "post")) {
            commands.add(stage + "-export");
            if (original)
                commands.add(stage + "-original-list");
            for (var name : names) commands.add(stage + "-" + name + "-audit");
        }
        var stages = new LinkedHashMap<String, List<String>>();
        for (var stage : List.of("pre", "post")) {
            var sorted = new ArrayList<>(original
                    ? List.of(module, "THC.InterfaceClosure", "GHC.Internal.Base", "GHC.Internal.List")
                    : List.of(module, "THC.InterfaceClosure"));
            sorted.sort(String::compareTo);
            var paths = new ArrayList<String>();
            for (var name : sorted)
                paths.add(directory + "/" + stage + (original ? "/core/" : "-core/") + name + ".cbd");
            stages.put(stage, paths);
        }
        var sourcePaths = new ArrayList<>(List.of("t/fixtures/compiler/" + module + ".hs",
            "t/haskell-fixtures/ByteArrayFixtures.hs", "t/haskell-fixtures/FixtureSupport.hs",
            "t/haskell-fixtures/Main.hs", "thc.cabal", "bin/audit-core.py", "bin/core-capabilities.json",
            "src/main/resources/thc/scalar-primop-signatures.json", "bin/build-compiler.sh", "bin/export-core.sh",
            "bin/toolchain.sh", "bin/plugin.py"));
        for (var file : Objects.requireNonNull(new File(root, "src/compiler/THC").listFiles()))
            if (file.getName().endsWith(".hs"))
                sourcePaths.add(root.toPath().relativize(file.toPath()).toString());
        for (var file : Objects.requireNonNull(new File(root, "bin").listFiles()))
            if (file.getName().startsWith("core_") && file.getName().endsWith(".py"))
                sourcePaths.add(root.toPath().relativize(file.toPath()).toString());
        if (original) {
            sourcePaths.add("bin/export-boot.py");
            sourcePaths.addAll(originalSources);
        } else
            sourcePaths.add("src/tools/primops/PrimopTools.hs");
        if (!generated)
            sourcePaths.addAll(List.of(
                "t/fixtures/compiler/" + module.substring(0, module.length() - "Audit".length()) + "Native.hs",
                "t/fixtures/compiler/ByteArrayFixtureInputs.hs"));
        var artifacts = new ArrayList<>(List.of(directory + "/requests.tsv", directory + "/oracle.tsv",
            directory + "/native/" + (group.equals("bytearray") ? "bytearray" : group) + "-oracle"));
        if (drivers.containsKey(group))
            artifacts.add(directory + "/" + drivers.get(group));
        for (var paths : stages.values()) artifacts.addAll(paths);
        for (var stage : List.of("pre", "post")) {
            for (var name : names)
                artifacts.add(original ? directory + "/" + stage + "/" + name + ".audit.json"
                                       : directory + "/" + stage + "-" + name + ".audit.json");
            if (original)
                artifacts.add(directory + "/" + stage + "/boot-provenance.json");
        }
        for (var command : commands)
            for (var extension : List.of("stdout", "stderr", "command.json"))
                artifacts.add(directory + "/commands/" + command + "." + extension);
        assertEquals(1L, manifest.get("schema"));
        assertEquals("9.14.1", manifest.get("ghc"));
        assertEquals("0.12.2.0", manifest.get("bytestring"));
        assertEquals(64L, manifest.get("wordBits"));
        assertEquals(names, manifest.get("entries"));
        assertEquals(stages, manifest.get("stages"));
        for (var entry :
            Map.of("inputHashes", new HashSet<>(sourcePaths), "artifactHashes", new HashSet<>(artifacts)).entrySet())
            assertEquals(entry.getValue(), ((Map<String, String>) manifest.get(entry.getKey())).keySet(),
                group + " exact " + entry.getKey() + " inventory");
        for (var kind : List.of("inputHashes", "artifactHashes"))
            for (var entry : ((Map<String, String>) manifest.get(kind)).entrySet())
                assertEquals(
                    entry.getValue(), digest(new File(root, entry.getKey())), group + " stale " + entry.getKey());
        var recorded = (List<Map<String, Object>>) manifest.get("commands");
        assertEquals(commands.size(), recorded.size());
        var actual = new ArrayList<Map<String, Object>>();
        for (var command : commands) actual.add(read(root, directory + "/commands/" + command + ".command.json"));
        assertEquals(new HashSet<>(actual), new HashSet<>(recorded));
        assertEquals(actual.size(), new HashSet<>(actual).size());
        for (var command : recorded) {
            assertEquals(0L, command.get("exit"));
            assertEquals(0L, command.get("expectedExit"));
        }
        for (var stage : stages.entrySet()) {
            String modulePath = null;
            for (var path : stage.getValue())
                if (path.endsWith("/" + module + ".cbd")) {
                    if (modulePath != null)
                        throw new IllegalArgumentException("Collection contains more than one matching element.");
                    modulePath = path;
                }
            if (modulePath == null)
                throw new java.util.NoSuchElementException("Collection contains no element matching the predicate.");
            var core = thc.CoreCbdFixtures.read(new File(root, modulePath).toPath());
            assertEquals(stage.getKey().equals("pre") ? "optimized-Core-before-Tidy"
                                                      : "optimized-Core-after-Tidy-before-CorePrep",
                core.get("boundary"));
            for (var name : names) {
                var audit = read(root,
                    original ? directory + "/" + stage.getKey() + "/" + name + ".audit.json"
                             : directory + "/" + stage.getKey() + "-" + name + ".audit.json");
                assertEquals(true, audit.get("accepted"));
                assertEquals(List.of(), audit.get("issues"));
                assertEquals(List.of(), audit.get("missingGlobals"));
            }
            if (original) {
                var provenance = read(root, directory + "/" + stage.getKey() + "/boot-provenance.json");
                assertEquals("ghc-9.14.1-release", provenance.get("ghcTag"));
                assertEquals(List.of(), provenance.get("sourcePatches"));
                assertEquals("lists", provenance.get("frontier"));
                assertEquals(List.of("GHC.Internal.Base", "GHC.Internal.List"), provenance.get("sourceModules"));
                var sources = (List<Map<String, String>>) provenance.get("sources");
                var paths = new HashSet<String>();
                for (var source : sources) paths.add(Objects.requireNonNull(source.get("path")));
                assertEquals(originalSources, paths);
                assertEquals(originalSources.size(), sources.size());
                for (var source : sources)
                    assertEquals(
                        source.get("sha256"), digest(new File(root, Objects.requireNonNull(source.get("path")))));
            }
        }
    }
    public static void rejectionControls(File root, String group) throws Exception {
        var good = read(root, "build/" + group + "/manifest.json");
        verify(root, group, good);
        for (var kind : List.of("inputHashes", "artifactHashes")) {
            var hashes = (Map<String, String>) good.get(kind);
            for (var path : hashes.keySet())
                assertThrows(AssertionError.class, () -> {
                    var changed = new LinkedHashMap<>(hashes);
                    changed.remove(path);
                    var manifest = new LinkedHashMap<>(good);
                    manifest.put(kind, changed);
                    verify(root, group, manifest);
                }, path);
            assertThrows(AssertionError.class, () -> {
                var changed = new LinkedHashMap<>(hashes);
                changed.put(hashes.keySet().iterator().next(), "0".repeat(64));
                var manifest = new LinkedHashMap<>(good);
                manifest.put(kind, changed);
                verify(root, group, manifest);
            });
        }
        for (var change : new Object[][] {{"entries", List.of()}, {"stages", Map.of()}, {"wordBits", 32L},
                 {"ghc", "other"}, {"bytestring", "other"}, {"commands", List.of()}})
            assertThrows(AssertionError.class, () -> {
                var manifest = new LinkedHashMap<>(good);
                manifest.put((String) change[0], change[1]);
                verify(root, group, manifest);
            }, (String) change[0]);
    }
}
