// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.io.*;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.runtime.TargetLayout;
import static java.nio.charset.StandardCharsets.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

class CoreZipBundleTest {
    @TempDir Path temporary;
    private final String boundary = "optimized-Core-after-Tidy-before-CorePrep";
    private String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private record Member(String name, byte[] bytes) {}
    private byte[] zipped(List<Member> entries) throws Exception {
        var output = new ByteArrayOutputStream(); try (var zip = new ZipOutputStream(output)) { for (var entry : entries) { zip.putNextEntry(new ZipEntry(entry.name)); zip.write(entry.bytes); zip.closeEntry(); } } return output.toByteArray();
    }
    private List<Member> unzip(byte[] bytes) throws Exception {
        var result = new ArrayList<Member>(); try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) { for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) { result.add(new Member(entry.getName(), zip.readAllBytes())); zip.closeEntry(); } } return result;
    }
    private byte[] module(String unit) { return module(unit, null); }
    private byte[] module(String unit, String target) {
        var expression = target == null ? list("lit", "int", "51") : list("var", target, map("rep", map("primReps", list("IntRep"), "kind", "long", "evaluated", true)));
        return Json.stringify(map("schema", 1, "ghc", "9.14.1", "unit", unit, "module", "Shared", "boundary", boundary, "constructors", List.of(), "bindings", list(map("id", unit + ":Shared.entry", "name", "entry", "type", "Int#", "arity", 0, "lifted", false, "expr", expression)))).getBytes(UTF_8);
    }
    // Follow the producer catalog: newly pinned HSCs must exercise this boundary.
    private List<Map<String, Object>> generatedReceipts() throws Exception {
        var result = new ArrayList<Map<String, Object>>();
        if (hostPlatform().equals("x86_64-windows")) {
            var catalog = document(Files.readString(Path.of(System.getProperty("thc.projectRoot"), "etc/ghc/9.14.1/windows-ghc-internal.json")));
            for (var file : (List<?>) catalog.get("files")) { var path = (String) ((Map<?, ?>) file).get("path"); if (path.endsWith(".hsc")) result.add(map("path", path, "sha256", "a".repeat(64))); } assertEquals(27, result.size());
        } else {
            var source = Files.readString(Path.of(System.getProperty("thc.projectRoot"), "src/driver/THC/Driver/Wired.hs"));
            var catalog = source.substring(source.indexOf("moduleSources =") + "moduleSources =".length()); catalog = catalog.substring(0, catalog.indexOf("sourceHashes ::"));
            var matcher = Pattern.compile("\\(\"([^\"]+\\.hsc)\", \"[^\"]+\"\\)").matcher(catalog); while (matcher.find()) result.add(map("path", matcher.group(1), "sha256", "a".repeat(64))); assertEquals(7, result.size());
        }
        return result;
    }
    private Map<String, Object> unit(String id) throws Exception { return unit(id, Map.of()); }
    /** Named model options preserve absent versus explicitly null receipt fields. */
    private Map<String, Object> unit(String id, Map<String, Object> options) throws Exception {
        var source = (byte[]) options.getOrDefault("source", module(id)); boolean omit = (boolean) options.getOrDefault("omit", false), extra = (boolean) options.getOrDefault("extra", false);
        var innerUnit = (String) options.getOrDefault("innerUnit", id); var layout = (Map<?, ?>) options.get("layout"); var generated = options.containsKey("generated") ? options.get("generated") : generatedReceipts(); var inputGenerated = options.getOrDefault("inputGenerated", generated);
        var core = "core/Shared.json"; var inventory = list(map("name", "Shared", "boundary", boundary, "path", core, "sha256", hash(source)));
        var fields = map("format", "thc-core-bundle", "schema", 1, "unit", innerUnit, "buildKey", "0".repeat(64), "exportKey", "1".repeat(64), "modules", inventory);
        if (layout != null) { fields.put("targetLayout", layout); if (generated != null) fields.put("generatedSources", generated); }
        var inputs = (byte[]) options.get("inputs");
        if (inputs == null && layout != null) inputs = Json.stringify(map("format", "thc-core-build-inputs", "schema", 1, "unit", id, "buildKey", "0".repeat(64), "exportKey", "1".repeat(64),
            "compiler", map("id", "ghc-9.14.1", "abi", options.getOrDefault("abi", "bcbf"), "platform", options.getOrDefault("platform", hostPlatform()), "way", options.getOrDefault("way", hostWay())), "targetLayout", layout, "generatedSources", inputGenerated)).getBytes(UTF_8);
        if (inputs != null) fields.put("buildInputs", map("path", "inplace-manifest.json", "sha256", hash(inputs)));
        var entries = new ArrayList<Member>(); entries.add(new Member("manifest.json", Json.stringify(fields).getBytes(UTF_8))); if (!omit) entries.add(new Member(core, source)); if (inputs != null) entries.add(new Member("inplace-manifest.json", inputs)); if (extra) entries.add(new Member("extra.json", source));
        var archive = temporary.resolve(id + ".zip"); Files.write(archive, zipped(entries));
        return map("id", id, "depends", List.of(), "bundle", map("path", archive.toString(), "sha256", hash(Files.readAllBytes(archive))), "modules", inventory);
    }
    private String hostPlatform() {
        var arch = Set.of("arm64", "aarch64").contains(System.getProperty("os.arch").toLowerCase(Locale.ROOT)) ? "aarch64" : "x86_64";
        var os = System.getProperty("os.name"); return arch + "-" + (os.startsWith("Mac") ? "osx" : os.startsWith("Windows") ? "windows" : "linux");
    }
    private String hostWay() { return hostPlatform().endsWith("-windows") ? "vanilla-nonprofiling" : "dynamic-nonprofiling"; }
    private Map<String, Object> targetLayout() {
        return map("schema", 1, "profiled", false, "wordBytes", 8, "targetPlatform", hostPlatform(), "tablesNextToCode", true, "endianness", ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? "little" : "big",
            "infoTableBytes", 16, "infoTablePtrsOffset", 0, "infoTablePtrsBytes", 4, "infoTableNptrsOffset", 4, "infoTableNptrsBytes", 4, "infoTableTypeOffset", 8, "infoTableTypeBytes", 4, "infoTableSrtOffset", 12, "infoTableSrtBytes", 4,
            "infoProvEntBytes", 72, "infoProvBytes", 64, "infoProvEntInfoOffset", 0, "infoProvEntProvOffset", 8, "infoProvNameOffset", 0, "infoProvDescOffset", 8, "infoProvDescBytes", 4, "infoProvTyDescOffset", 16, "infoProvLabelOffset", 24, "infoProvUnitOffset", 32, "infoProvModuleOffset", 40, "infoProvFileOffset", 48, "infoProvSpanOffset", 56,
            "closureRetBco", 29, "closureRetSmall", 30, "closureRetBig", 31, "closureRetFun", 32, "closureUpdateFrame", 33, "closureCatchFrame", 34, "closureUnderflowFrame", 35, "closureStopFrame", 36, "closureStack", 53, "closureAtomicallyFrame", 55, "closureCatchRetryFrame", 56, "closureCatchStmFrame", 57, "closureAnnFrame", 65,
            "stackHeaderBytes", 8, "stackCatchHandlerBytes", 8, "stackCatchFrameBytes", 16, "stackCatchStmCodeBytes", 8, "stackCatchStmHandlerBytes", 16, "stackCatchStmFrameBytes", 24, "stackUpdateeBytes", 8, "stackUpdateFrameBytes", 16, "stackAtomicallyCodeBytes", 8, "stackAtomicallyResultBytes", 16, "stackAtomicallyFrameBytes", 24,
            "stackCatchRetryAltCodeBytes", 8, "stackCatchRetryFirstCodeBytes", 16, "stackCatchRetryAltBytes", 24, "stackCatchRetryFrameBytes", 32, "stackRetFunSizeBytes", 8, "stackRetFunFunBytes", 16, "stackRetFunPayloadBytes", 24, "stackRetFunFrameBytes", 24, "stackAnnPayloadBytes", 8, "stackAnnFrameBytes", 16, "stackClosurePayloadBytes", 8);
    }
    private Path manifest(List<Map<String, Object>> units) throws Exception { var path = temporary.resolve("packages.json"); Files.writeString(path, Json.stringify(map("format", "thc-core-packages", "schema", 1, "ghc", "9.14.1", "units", units))); return path; }
    private String request(Path path) { return request(path, Main.defaultBackend(), false); }
    private String request(Path path, String backend, boolean verify) { return CoreModules.request(List.of("@" + path), "pkg-a:Shared.entry", true, false, backend, true, false, null, null, verify); }
    private RuntimeException rejected(List<Map<String, Object>> units) { return assertThrows(RuntimeException.class, () -> request(manifest(units))); }
    private byte[] inputs(String buildKey) { return Json.stringify(map("format", "thc-core-build-inputs", "schema", 1, "unit", "pkg-a", "buildKey", buildKey, "exportKey", "1".repeat(64))).getBytes(UTF_8); }
    @Test void nativeBuildInputsAreVerifiedWhenPresent() throws Exception {
        var good = manifest(List.of(unit("pkg-a", map("inputs", inputs("0".repeat(64)))))); assertEquals(1, ((List<?>) document(request(good)).get("modules")).size());
        var bad = manifest(List.of(unit("pkg-a", map("inputs", inputs("f".repeat(64)))))); assertTrue(assertThrows(RuntimeException.class, () -> request(bad)).getMessage().contains("build inputs record"));
    }
    @Test void wiredTargetLayoutIsValidatedAndCarriedToBothBackends() throws Exception {
        var layout = targetLayout(); var path = manifest(List.of(unit("pkg-a", map("layout", layout))));
        for (var backend : List.of("ast", "bytecode")) {
            var request = request(path, backend, false); var input = document(request); var record = TargetLayout.fromDocument(input.get("targetLayout")); assertEquals(8, record.getWordBytes()); assertTrue(record.getTablesNextToCode()); assertEquals(8, record.offset("infoProvEntProvOffset"));
            try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).build()) { assertEquals(51L, context.eval("thc", request).execute().asLong(), backend); var original = (Map<?, ?>) input.get("targetLayout"); var source = (Map<?, ?>) original.get("layout"); var malformed = with(input, "targetLayout", with(original, "layout", with(source, "infoProvSpanOffset", 64))); assertThrows(RuntimeException.class, () -> context.eval("thc", Json.stringify(malformed))); }
        }
        assertTrue(rejected(List.of(unit("pkg-a", map("layout", with(layout, "endianness", "invalid"))))).getMessage().contains("endianness"));
        assertTrue(rejected(List.of(unit("pkg-a", map("layout", with(layout, "infoProvSpanOffset", 64))))).getMessage().contains("exceeds"));
        assertTrue(rejected(List.of(unit("pkg-a", map("layout", layout, "platform", "other-os")))).getMessage().contains("identity or way"));
        assertTrue(rejected(List.of(unit("pkg-a", map("layout", layout, "way", "profiling")))).getMessage().contains("identity or way"));
        var otherLayout = with(layout, "tablesNextToCode", false); var other = document(request(manifest(List.of(unit("pkg-a", map("layout", otherLayout)))))); assertFalse(TargetLayout.fromDocument(other.get("targetLayout")).getTablesNextToCode());
        assertTrue(rejected(List.of(unit("pkg-a", map("layout", with(layout, "tablesNextToCode", "false"))))).getMessage().contains("tables-next-to-code"));
        assertTrue(rejected(List.of(unit("pkg-a", map("layout", layout)), unit("pkg-b", map("layout", otherLayout)))).getMessage().contains("Conflicting GHC target layouts"));
        assertTrue(rejected(List.of(unit("pkg-a", map("layout", layout)), unit("pkg-b", map("layout", layout, "abi", "other")))).getMessage().contains("Conflicting GHC target layouts"));
        assertTrue(rejected(List.of(unit("pkg-a", map("layout", layout, "inputs", inputs("0".repeat(64)))))).getMessage().contains("receipts differ"));
    }
    private byte[] selectedInputs(String rts, String recipe, String digest, Map<String, Object> layout) {
        var fields = map("format", "thc-core-build-inputs", "schema", 1, "unit", "pkg-a", "buildKey", "0".repeat(64), "exportKey", "1".repeat(64), "compiler", map("id", "ghc-9.14.1", "abi", "bcbf", "platform", hostPlatform(), "way", hostWay()), "component", map("kind", "installed-interface", "registration", "selected"), "targetLayout", layout);
        if (rts != null) fields.put("rtsRegistration", rts); if (recipe != null) fields.put("recipeArtifacts", list(map("path", recipe, "sha256", digest))); return Json.stringify(fields).getBytes(UTF_8);
    }
    private String selectedRequest(String rts, String recipe, String digest, Map<String, Object> receiptLayout) throws Exception { return request(manifest(List.of(unit("pkg-a", map("layout", targetLayout(), "generated", null, "inputs", selectedInputs(rts, recipe, digest, receiptLayout)))))); }
    @Test void selectedInstalledLayoutRequiresItsRtsAndRecipeReceipt() throws Exception {
        var layout = targetLayout(); var accepted = document(selectedRequest("rts-1.0.3", "src/driver/cbits/target-layout.c", "a".repeat(64), layout)); assertEquals(8, TargetLayout.fromDocument(accepted.get("targetLayout")).getWordBytes());
        for (var invalid : List.of(list(null, "src/driver/cbits/target-layout.c", "a".repeat(64)), list("", "src/driver/cbits/target-layout.c", "a".repeat(64)), list("rts-1.0.3", null, "a".repeat(64)), list("rts-1.0.3", "other.c", "a".repeat(64)), list("rts-1.0.3", "src/driver/cbits/target-layout.c", "z".repeat(64)))) {
            var error = assertThrows(RuntimeException.class, () -> selectedRequest((String) invalid.get(0), (String) invalid.get(1), (String) invalid.get(2), layout)); assertTrue(error.getMessage().contains("selected-GHC layout provenance"));
        }
        var mismatch = assertThrows(RuntimeException.class, () -> selectedRequest("rts-1.0.3", "src/driver/cbits/target-layout.c", "a".repeat(64), with(layout, "tablesNextToCode", false))); assertTrue(mismatch.getMessage().contains("receipts differ"));
    }
    @Test void generatedReceiptCatalogRejectsMissingDuplicateStaleMalformedAndMismatchedSources() throws Exception {
        var generated = generatedReceipts(); var layout = targetLayout(); var reordered = manifest(List.of(unit("pkg-a", map("layout", layout, "generated", generated.reversed())))); assertEquals(8, TargetLayout.fromDocument(document(request(reordered)).get("targetLayout")).getWordBytes());
        var malformed = new ArrayList<List<?>>(); malformed.add(null); malformed.add(List.of()); var duplicate = new ArrayList<>(generated); duplicate.add(generated.getFirst()); malformed.add(duplicate); var replaceLast = new ArrayList<>(generated.subList(0, generated.size() - 1)); replaceLast.add(generated.getFirst()); malformed.add(replaceLast);
        var invented = new ArrayList<>(); for (int i = 0; i < 6; i++) invented.add(map("path", "source" + i + ".hsc", "sha256", "a".repeat(64))); malformed.add(invented); malformed.add(generated.stream().map(it -> with(it, "path", "other/" + Objects.requireNonNull(it.get("path")))).toList());
        for (int missing = 0; missing < generated.size(); missing++) { var fewer = new ArrayList<>(generated); fewer.remove(missing); malformed.add(fewer); }
        for (var bad : list(null, "invalid", Map.of(), with(generated.getFirst(), "sha256", "f".repeat(63)), with(generated.getFirst(), "sha256", "g".repeat(64)), with(generated.getFirst(), "unused", true), with(generated.getFirst(), "path", "../GHC/Internal/Heap/Constants.hsc"), with(generated.getFirst(), "path", "GHC/Internal/Heap/Constants.hs"), with(generated.getFirst(), "path", 7))) { var badList = new ArrayList<>(list(bad)); badList.addAll(generated.subList(1, generated.size())); malformed.add(badList); }
        for (int i = 0; i < malformed.size(); i++) { var path = manifest(List.of(unit("pkg-a", map("layout", layout, "generated", malformed.get(i))))); var error = assertThrows(RuntimeException.class, () -> request(path), "malformed generated receipt " + i); assertTrue(error.getMessage().contains("GHC source receipts"), error.getMessage()); }
        var changed = new ArrayList<>(generated); changed.set(0, with(generated.getFirst(), "sha256", "b".repeat(64))); var mismatch = manifest(List.of(unit("pkg-a", map("layout", layout, "inputGenerated", changed)))); assertTrue(assertThrows(RuntimeException.class, () -> request(mismatch)).getMessage().contains("receipts differ"));
    }
    @Test void sameModuleNameInDifferentUnitsLinksAndExecutesInBothBackends() throws Exception {
        var path = manifest(List.of(unit("pkg-b"), unit("pkg-a", map("source", module("pkg-a", "pkg-b:Shared.entry")))));
        for (var backend : List.of("ast", "bytecode")) { var request = request(path, backend, false); var input = document(request); assertEquals(true, input.get("strictLink")); assertEquals(2, ((List<?>) input.get("modules")).size()); try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).build()) { assertEquals(51L, context.eval("thc", request).execute().asLong(), backend); } }
    }
    @Test void looseConsumersUseCheckedSmallAndStreamedDependenciesWithoutLosingConflictChecks() throws Exception {
        for (boolean large : new boolean[]{false, true}) {
            var dependency = document(new String(module("dependency"), UTF_8)); var source = Json.stringify(large ? with(dependency, "sourceFiles", list(map("id", "large-source", "path", "Shared.hs", "content", "x".repeat(2 * 1024 * 1024)))) : dependency).getBytes(UTF_8);
            var path = manifest(List.of(unit("dependency", map("source", source, "layout", targetLayout())))); var consumer = temporary.resolve("loose consumer.json"); Files.write(consumer, module("consumer", "dependency:Shared.entry"));
            for (var backend : List.of("ast", "bytecode")) {
                var request = CoreFormatTestSupport.request(List.of(consumer.toString(), "@" + path), "consumer:Shared.entry", backend, true, null, false); var input = document(request); assertEquals(true, input.get("strictLink")); assertEquals(large, input.containsKey("consumerModules"));
                try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).build()) { assertEquals(51L, context.eval("thc", request).execute().asLong()); var duplicate = CoreFormatTestSupport.request(List.of(consumer.toString(), consumer.toString(), "@" + path), "consumer:Shared.entry", backend, true, null, false); assertTrue(assertThrows(RuntimeException.class, () -> context.eval("thc", duplicate)).getMessage().contains("Duplicate")); }
            }
            assertThrows(IllegalArgumentException.class, () -> CoreFormatTestSupport.request(List.of("@" + path, "@" + path), "dependency:Shared.entry", Main.defaultBackend(), true, null, false));
            // Metadata transport control only; no fabricated bridge is executed.
            Files.writeString(path, Json.stringify(with(document(Files.readString(path)), "foreignExceptionBridgeUnit", "selected-runtime")));
            var selected = document(CoreFormatTestSupport.request(List.of(consumer.toString(), "@" + path), "consumer:Shared.entry", Main.defaultBackend(), true, null, false)); var visited = new ArrayList<Map<String, Object>>(); visit(selected, visited::add); assertEquals(list("dependency", "consumer"), visited.stream().map(it -> it.get("unit")).toList()); assertTrue(visited.stream().allMatch(it -> "selected-runtime".equals(it.get("foreignExceptionBridgeUnit"))));
            if (large) assertTrue(assertThrows(IllegalArgumentException.class, () -> visit(with(selected, "foreignExceptionBridgeUnit", "another-runtime"), it -> {})).getMessage().contains("bridge selection changed"));
        }
    }
    private String archiveRequest(Map<String, Object> original, Path archive, byte[] bytes) throws Exception { Files.write(archive, bytes); var reference = (Map<?, ?>) original.get("bundle"); return request(manifest(List.of(with(original, "bundle", with(reference, "sha256", hash(bytes)))))); }
    @Test void producerOrderedAndReorderedArchivesPreserveTheSameVerifiedModules() throws Exception {
        var original = unit("pkg-a", map("layout", targetLayout())); var archive = temporary.resolve("pkg-a.zip"); var entries = unzip(Files.readAllBytes(archive)); var order = List.of("manifest.json", "inplace-manifest.json", "core/Shared.json"); assertEquals(new HashSet<>(order), new HashSet<>(entries.stream().map(Member::name).toList()));
        var orderedEntries = order.stream().map(name -> { var matching = entries.stream().filter(it -> it.name.equals(name)).toList(); assertEquals(1, matching.size()); return matching.getFirst(); }).toList(); var bytes = zipped(orderedEntries); assertEquals(order, unzip(bytes).stream().map(Member::name).toList()); var ordered = Json.parse(archiveRequest(original, archive, bytes)); var reordered = archiveRequest(original, archive, zipped(orderedEntries.reversed())); assertEquals(ordered, Json.parse(reordered));
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).build()) { assertEquals(51L, context.eval("thc", reordered).execute().asLong()); }
    }
    private Path fixture(String name) throws Exception { var path = temporary.resolve(name); try (var source = Objects.requireNonNull(getClass().getResourceAsStream("/core/" + name))) { Files.copy(source, path); } return path; }
    @Test void explicitConsumerKeepsCheckedPackageLayoutAndBridgeSelection() throws Exception {
        var layout = targetLayout(); var path = manifest(List.of(unit("dependency", map("layout", layout)))); var json = fixture("lazy-json-package.json"); Files.writeString(path, Json.stringify(with(document(Files.readString(path)), "foreignExceptionBridgeUnit", "selected-runtime")));
        var request = document(CoreFormatTestSupport.request(List.of(json.toString(), "@" + path), "synthetic:LazyJson.entry", Main.defaultBackend(), true, null, false)); var visited = new ArrayList<Map<String, Object>>();
        var actualLayout = CoreModules.visitRequestModules(request, visited::add); assertNotNull(actualLayout); assertEquals(layout, actualLayout.document().get("layout")); assertEquals(list("dependency", "synthetic"), visited.stream().map(it -> it.get("unit")).toList()); assertTrue(visited.stream().allMatch(it -> "selected-runtime".equals(it.get("foreignExceptionBridgeUnit"))));
    }
    @Test void largePackageRequestStreamsVerifiedModulesAndBindsItsManifestIdentity() throws Exception {
        var first = document(new String(module("pkg-a", "pkg-b:Shared.entry"), UTF_8)); var source = Json.stringify(with(first, "sourceFiles", list(map("id", "pkg-a-source", "path", "Shared.hs", "content", "x".repeat(2 * 1024 * 1024))))).getBytes(UTF_8);
        var path = manifest(List.of(unit("pkg-b", map("layout", targetLayout())), unit("pkg-a", map("source", source)))); var request = request(path, Main.defaultBackend(), true); var input = document(request);
        assertEquals(true, input.get("strictLink")); assertFalse(input.containsKey("modules")); assertEquals(path.toRealPath().toString(), input.get("packageManifest")); assertEquals(64, ((String) input.get("packageManifestSha256")).length());
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).allowAllAccess(true).build()) { assertTrue(assertThrows(RuntimeException.class, () -> context.eval("thc", Json.stringify(without(input, "packageCapability")))).getMessage().contains("capability")); assertTrue(assertThrows(RuntimeException.class, () -> context.eval("thc", Json.stringify(with(input, "packageManifest", temporary.resolve("unreadable.json").toString())))).getMessage().contains("capability")); }
        var merger = new CoreModules.Merger(); var layout = CoreModules.visitRequestModules(input, merger::add); assertNotNull(layout); assertEquals(8, layout.getWordBytes());
        var linked = CoreModules.reachable(merger.finish(), "pkg-a:Shared.entry", true); assertEquals(2, ((List<?>) linked.get("bindings")).size()); var files = (List<?>) linked.get("sourceFiles"); assertEquals(1, files.size()); assertEquals(2 * 1024 * 1024, ((String) ((Map<?, ?>) files.getFirst()).get("content")).length());
        for (var backend : List.of("ast", "bytecode")) { var selected = request(path, backend, false); try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).build()) { assertEquals(51L, context.eval("thc", selected).execute().asLong(), backend); } }
        var archive = temporary.resolve("pkg-a.zip"); var original = Files.readAllBytes(archive); Files.write(archive, Arrays.copyOf(original, original.length + 1)); assertTrue(assertThrows(RuntimeException.class, () -> { try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).build()) { context.eval("thc", request); } }).getMessage().contains("hash mismatch"));
        Files.write(archive, original); Files.writeString(path, "{}"); assertTrue(assertThrows(RuntimeException.class, () -> { try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).build()) { context.eval("thc", request); } }).getMessage().contains("manifest changed after request"));
    }
    private RuntimeException rejectedVerified(Path path) { return assertThrows(RuntimeException.class, () -> request(path, Main.defaultBackend(), true)); }
    private void check(Map<String, Object> record) throws Exception { rejectedVerified(manifest(List.of(record))); }
    @Test void archiveInventoryIdentityAndBothHashesAreRequired() throws Exception {
        var original = unit("pkg-a"); var path = manifest(List.of(original)); var archive = temporary.resolve("pkg-a.zip"); var valid = Files.readAllBytes(archive); Files.write(archive, Arrays.copyOf(valid, valid.length + 1)); assertTrue(rejectedVerified(path).getMessage().contains("hash mismatch")); Files.write(archive, valid);
        var truncated = Arrays.copyOf(valid, valid.length - 10); Files.write(archive, truncated); manifest(List.of(with(original, "bundle", map("path", archive.toString(), "sha256", hash(truncated))))); rejectedVerified(path); Files.write(archive, valid);
        var changed = zipped(unzip(valid).stream().map(it -> new Member(it.name, it.name.equals("core/Shared.json") ? "{}".getBytes(UTF_8) : it.bytes)).toList()); Files.write(archive, changed); manifest(List.of(with(original, "bundle", map("path", archive.toString(), "sha256", hash(changed))))); assertTrue(rejectedVerified(path).getMessage().contains("artifact hash mismatch")); Files.write(archive, valid);
        check(unit("pkg-a", map("omit", true))); check(unit("pkg-a", map("extra", true))); check(unit("pkg-a", map("innerUnit", "pkg-b"))); check(unit("pkg-a", map("source", module("pkg-b"))));
        var modules = (List<?>) unit("pkg-a").get("modules"); assertEquals(1, modules.size()); var module = (Map<?, ?>) modules.getFirst(); var reference = (Map<?, ?>) original.get("bundle"); check(with(original, "bundle", null)); check(with(original, "bundle", with(reference, "path", "pkg-a.zip"))); check(with(original, "bundle", with(reference, "unused", true)));
        check(with(unit("pkg-a"), "modules", list(with(module, "sha256", "f".repeat(64))))); check(with(unit("pkg-a"), "modules", list(with(module, "path", "../Shared.json")))); check(with(unit("pkg-a"), "modules", list(with(module, "path", "C:Shared.json")))); check(with(unit("pkg-a"), "modules", list(with(module, "path", "core/\nShared.json")))); check(with(unit("pkg-a"), "modules", list(module, module)));
    }
    @Test void duplicateZipMemberNamesAreRejected() throws Exception {
        var original = unit("pkg-a"); var archive = temporary.resolve("pkg-a.zip"); var entries = zipped(List.of(new Member("manifest.json", "{}".getBytes(UTF_8)), new Member("core/a.json", "{}".getBytes(UTF_8)), new Member("core/b.json", "{}".getBytes(UTF_8))));
        // ZIP writer rejects duplicates; rename an equal-length local-header name.
        var changed = new String(entries, ISO_8859_1).replace("core/b.json", "core/a.json").getBytes(ISO_8859_1); Files.write(archive, changed); var path = manifest(List.of(with(original, "bundle", map("path", archive.toString(), "sha256", hash(changed))))); var failure = assertThrows(RuntimeException.class, () -> request(path)); assertTrue(failure.getMessage().contains("Duplicate ZIP entry"));
    }
}
