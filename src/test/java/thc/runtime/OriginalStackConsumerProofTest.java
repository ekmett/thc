// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import thc.CoreModules;
import thc.Json;
import static org.junit.jupiter.api.Assertions.*;

/** Source/ABI frontier evidence only: no claim that the original decoder runs on THC yet. */
@SuppressWarnings("unchecked")
public class OriginalStackConsumerProofTest {
    private final File root = new File(System.getProperty("thc.projectRoot")); private final File directory = new File(root, "build/reports/original-stack");
    private final String proofPath = "t/fixtures/compiler/OriginalStackProof.json", proofHash = "db63661c12a6ecb757697e759fcb95e4d51f3689619bdb7682a041788eb41d4f";
    private final List<String> entries = List.of("captureOriginal", "decodeOriginal", "renderOriginal", "peekOriginalInfoTable", "lookupOriginalIPE", "peekOriginalInfoProv");
    private final Map<String, String> retainedHashes = Map.of("GHC.Internal.Stack.CloneStack", "d0733836485a57ebc40a4ae52ce77319e4dbc44f617cbd396335ae977e5810e4",
        "GHC.Internal.Stack.Decode", "c3762b0e2ed8bb2bb50b748144fcc7da01dec204c0cc48adade79962e8b35c42", "GHC.Internal.InfoProv.Types", "63fe524cfd81c88ebd4f835c8718a30b86828c9e53549a2c001cbffac5ab2d1e", "GHC.Internal.Heap.InfoTable", "1065f91361835bb3cf0cf2547ee320c59ba542e65e26ef2ec55c297cdcf9855a");
    private final List<String> pinnedSources = List.of("GHC/Internal/Stack/CloneStack.hs", "GHC/Internal/Stack/Decode.hs", "GHC/Internal/InfoProv/Types.hsc", "GHC/Internal/Heap/InfoTable.hsc");
    private final Set<String> requiredInputs = requiredInputs();
    private Set<String> requiredInputs() {
        var result = new LinkedHashSet<>(List.of("t/fixtures/compiler/OriginalStackAudit.hs", "t/fixtures/compiler/OriginalStackAuditNative.hs", "t/haskell-fixtures/StackFixtures.hs", "t/haskell-fixtures/FixtureSupport.hs", "t/haskell-fixtures/Main.hs", "thc.cabal", proofPath,
            "bin/export-core.sh", "bin/build-compiler.sh", "bin/toolchain.sh", "src/compiler/THC/Plugin.hs", "src/compiler/THC/CBV.hs", "src/compiler/THC/Demands.hs", "src/compiler/THC/Sources.hs", "src/compiler/THC/Wired.hs", "bin/plugin.py", "nih/pinned/ghc-9.14.1/libraries/ghc-internal/LICENSE"));
        for (var source : pinnedSources) result.add("nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/" + source); return result;
    }
    private static void require(boolean condition) { require(condition, "Failed requirement."); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static Map<String, Object> map(Object... entries) { var result = new LinkedHashMap<String, Object>(); for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]); return result; }
    private static Map<String, Object> plus(Map<String, ?> source, String key, Object value) { var result = new LinkedHashMap<String, Object>(source); result.put(key, value); return result; }
    private Map<String, Object> read(String path) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(new File(root, path).toPath())); }
    private String hash(File file) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()))); }
    private File contained(String path, boolean input) throws Exception {
        require(!new File(path).isAbsolute() && !Arrays.asList(path.split("/", -1)).contains("..")); require(input ? requiredInputs.contains(path) : path.startsWith("build/original-stack/"));
        var canonicalRoot = root.getCanonicalFile().toPath(); var file = new File(root, path).getCanonicalFile(); require(file.toPath().startsWith(input ? canonicalRoot : canonicalRoot.resolve("build/original-stack"))); return file;
    }
    private Map<String, Object> checked(Map<String, Object> manifest) throws Exception {
        require("thc-original-stack-fixture".equals(manifest.get("format")) && Long.valueOf(1).equals(manifest.get("schema"))); require(entries.equals(manifest.get("entries")));
        var ghc = (Map<String, Object>) manifest.get("ghc"); require("9.14.1".equals(ghc.get("version")) && Boolean.FALSE.equals(ghc.get("installedArtifactsHashed")) && !((String) ghc.get("path")).isEmpty());
        var stages = (Map<String, List<String>>) manifest.get("stages"); require(stages.keySet().equals(Set.of("pre", "post"))); require(proofPath.equals(manifest.get("proofResource")));
        var inputs = (Map<String, String>) manifest.get("inputHashes"); require(inputs.keySet().equals(requiredInputs) && proofHash.equals(inputs.get(proofPath))); var artifacts = (Map<String, String>) manifest.get("artifactHashes");
        for (var paths : stages.values()) { require(paths.size() == new LinkedHashSet<>(paths).size()); int roots = 0; for (var path : paths) { if (path.endsWith("/OriginalStackAudit.json")) roots++; require(artifacts.containsKey(path)); } require(roots == 1); }
        require(artifacts.containsKey(manifest.get("nativeOutput")));
        for (var group : List.of("inputHashes", "artifactHashes")) {
            var records = (Map<String, String>) manifest.get(group); require(!records.isEmpty());
            for (var record : records.entrySet()) {
                // Resolve symlinks before reading; never hash an installed toolchain.
                require(hash(contained(record.getKey(), group.equals("inputHashes"))).equals(record.getValue()), "Stale " + group + ": " + record.getKey());
            }
        }
        var commands = (List<Map<String, Object>>) manifest.get("commands"); require(commands.size() == 6); for (var command : commands) require(Long.valueOf(0).equals(command.get("exit")) && !Boolean.TRUE.equals(command.get("timedOut"))); return manifest;
    }
    private Map<String, Object> manifest() throws Exception { return checked(read("build/original-stack/manifest.json")); }
    private List<List<Object>> nodes(Object value) { var result = new ArrayList<List<Object>>(); visit(value, result); return result; }
    private void visit(Object value, List<List<Object>> nodes) { if (value instanceof Map<?, ?> map) for (var child : map.values()) visit(child, nodes); else if (value instanceof List<?> list) { nodes.add((List<Object>) list); for (var child : list) visit(child, nodes); } }
    private record Call(String owner, List<Object> expression) {
        Map<String, Object> metadata() { return (Map<String, Object>) expression.get(6); }
        Map<String, Object> descriptor() { return (Map<String, Object>) metadata().get("foreignCall"); }
        String symbol() { return (String) ((Map<String, Object>) descriptor().get("target")).get("symbol"); }
    }
    private List<Call> calls(Map<String, Object> module, String entry) { return callsIn((List<Map<String, Object>>) CoreModules.reachable(module, entry).get("bindings")); }
    private List<Call> callsIn(Collection<Map<String, Object>> bindings) {
        var result = new ArrayList<Call>(); for (var binding : bindings) for (var node : nodes(binding.get("expr")))
            if (!node.isEmpty() && "app".equals(node.getFirst()) && node.size() > 6 && node.get(6) instanceof Map<?, ?> metadata && metadata.get("foreignCall") instanceof Map<?, ?>) result.add(new Call((String) binding.get("id"), node)); return result;
    }
    private Map<String, Object> scalar(String rep) { return scalar(rep, false); }
    private Map<String, Object> scalar(String rep, boolean evaluated) { return map("kind", switch (rep) { case null -> "void"; case "AddrRep" -> "address"; case "BoxedRep (Just Unlifted)", "BoxedRep (Just Lifted)" -> "object"; default -> "long"; }, "primReps", rep == null ? List.of() : List.of(rep), "evaluated", evaluated); }
    private Map<String, Object> tuple(String... reps) { var physical = new ArrayList<String>(); var fields = new ArrayList<Map<String, Object>>(); for (var rep : reps) { if (rep != null) physical.add(rep); fields.add(scalar(rep, true)); } return map("kind", "unknown", "primReps", physical, "evaluated", false, "aggregate", "unboxed-tuple", "components", fields); }
    private record Spec(List<String> arguments, Map<String, Object> result) {}
    private final String snapshot = "BoxedRep (Just Unlifted)";
    private final Map<String, Spec> specs = specs();
    private Map<String, Spec> specs() {
        var result = new LinkedHashMap<String, Spec>();
        result.put("stg_cloneMyStackzh", new Spec(Arrays.asList((String) null), tuple(null, snapshot)));
        result.put("lookupIPE", new Spec(Arrays.asList("AddrRep", "AddrRep", null), tuple(null, "Word8Rep")));
        result.put("getStackInfoTableAddrzh", new Spec(List.of(snapshot), scalar("AddrRep"))); result.put("getStackFieldszh", new Spec(List.of(snapshot), scalar("Word32Rep")));
        result.put("getInfoTableAddrszh", new Spec(List.of(snapshot, "WordRep"), tuple("AddrRep", "AddrRep"))); result.put("advanceStackFrameLocationzh", new Spec(List.of(snapshot, "WordRep"), tuple(snapshot, "WordRep", "IntRep")));
        for (var name : List.of("getSmallBitmapzh", "getRetFunSmallBitmapzh")) result.put(name, new Spec(List.of(snapshot, "WordRep"), tuple("WordRep", "WordRep")));
        for (var name : List.of("getLargeBitmapzh", "getBCOLargeBitmapzh", "getRetFunLargeBitmapzh")) result.put(name, new Spec(List.of(snapshot, "WordRep"), tuple("AddrRep", "WordRep")));
        for (var pair : List.of(List.of("getWordzh", "WordRep"), List.of("getStackClosurezh", "BoxedRep (Just Lifted)"), List.of("getUnderflowFrameNextChunkzh", snapshot), List.of("isArgGenBigRetFunTypezh", "IntRep"))) result.put(pair.get(0), new Spec(List.of(snapshot, "WordRep"), scalar(pair.get(1)))); return result;
    }
    private void checkCall(Call call) {
        var spec = specs.get(call.symbol()); var args = spec.arguments(); var result = spec.result(); var declared = new ArrayList<Map<String, Object>>(); for (var rep : args) declared.add(scalar(rep));
        var expected = map("schema", 1L, "target", map("kind", "static", "symbol", call.symbol(), "unit", "ghc-internal", "isFunction", true), "convention", call.symbol().equals("lookupIPE") ? "ccall" : "prim", "safety", "safe",
            "arity", (long) args.size(), "suppliedArity", (long) args.size(), "argumentReps", declared, "resultRep", result);
        require(call.descriptor().equals(expected), "Changed original descriptor: " + call.symbol() + " in " + call.owner());
        require(result.equals(call.metadata().get("rep")) && call.expression().get(3).equals(java.util.Collections.nCopies(args.size(), false)));
        var actual = (List<List<Object>>) call.expression().get(2); require(actual.size() == args.size());
        for (int i = 0; i < actual.size(); i++) { var metadata = CoreRepresentations.metadata(actual.get(i)); var proof = (Map<String, Object>) (metadata == null ? null : metadata.get("rep")); require(proof.get("evaluated") instanceof Boolean && proof.equals(scalar(args.get(i), (Boolean) proof.get("evaluated")))); }
    }
    private Map<String, Object> proof() throws Exception { var proof = read(proofPath); require(hash(contained(proofPath, true)).equals(proofHash)); return proof; }
    private record Location(Object owner, Object path) {}
    private void located(Map<String, Object> record, Map<String, Map<String, Object>> owners) {
        var owner = owners.get((String) record.get("owner")); var path = (List<?>) record.get("path"); var expected = new ArrayList<Object>((List<?>) owner.get("path")); expected.add("expr");
        require(path.subList(0, Math.min(3, path.size())).equals(expected)); for (var step : path) require(step instanceof String || step instanceof Long number && number >= 0);
    }
    private Map<String, Integer> counts(List<Call> calls) { var result = new LinkedHashMap<String, Integer>(); for (var call : calls) result.merge(call.symbol(), 1, Integer::sum); return result; }
    private Map<Call, Integer> fullCounts(List<Call> calls) { var result = new LinkedHashMap<Call, Integer>(); for (var call : calls) result.merge(call, 1, Integer::sum); return result; }
    private List<Call> inventory(Map<String, Object> proof) throws Exception {
        require("thc-original-stack-core-excerpts".equals(proof.get("format")) && Long.valueOf(1).equals(proof.get("schema")) && Boolean.FALSE.equals(proof.get("fresh")));
        require("62e3400c5b889d3971cb4047709c408fd270255f".equals(proof.get("exporterRevision")) && "902339d332fb4ce2b3c87dcac1ee6495d41ad886".equals(proof.get("ghcSourceRevision")) && "9.14.1".equals(proof.get("ghc")) && "optimized-Core-after-Tidy-before-CorePrep".equals(proof.get("boundary")));
        require("BSD-3-Clause".equals(proof.get("license")) && "compiler/pinned-ghc-internal/LICENSE".equals(proof.get("licenseFile")));
        var originals = (List<Map<String, Object>>) proof.get("originals"); var modules = new LinkedHashSet<>(); for (var record : originals) modules.add(record.get("module")); require(originals.size() == retainedHashes.size() && modules.equals(retainedHashes.keySet()));
        for (int i = 0; i < Math.min(originals.size(), pinnedSources.size()); i++) {
            var record = originals.get(i); var source = pinnedSources.get(i); require("ghc-internal".equals(record.get("unit")) && Objects.equals(record.get("sha256"), retainedHashes.get(record.get("module"))));
            require((record.get("module") + "/" + record.get("module") + ".json").equals(record.get("exportPath")) && ("libraries/ghc-internal/src/" + source).equals(record.get("sourcePath")) && hash(contained("nih/pinned/ghc-9.14.1/libraries/ghc-internal/src/" + source, true)).equals(record.get("sourceSha256")));
        }
        var ownerList = (List<Map<String, Object>>) proof.get("owners"); var owners = new LinkedHashMap<String, Map<String, Object>>(); for (var owner : ownerList) owners.put((String) owner.get("id"), owner); require(owners.size() == ownerList.size());
        for (var entry : owners.entrySet()) {
            var id = entry.getKey(); var owner = entry.getValue(); require(retainedHashes.containsKey(owner.get("module")) && id.startsWith("ghc-internal:" + owner.get("module") + ".")); var path = (List<?>) owner.get("path");
            require(path.size() == 2 && "bindings".equals(path.get(0)) && path.get(1) instanceof Long index && index >= 0); require(((String) owner.get("canonicalSha256")).matches("[0-9a-f]{64}"));
        }
        var records = (List<Map<String, Object>>) proof.get("calls"); var locations = new LinkedHashSet<Location>(); for (var record : records) locations.add(new Location(record.get("owner"), record.get("path"))); require(locations.size() == records.size());
        var calls = new ArrayList<Call>(); for (var record : records) { located(record, owners); calls.add(new Call((String) record.get("owner"), (List<Object>) record.get("expression"))); }
        var expectedCounts = new LinkedHashMap<String, Integer>(); for (var symbol : specs.keySet()) expectedCounts.put(symbol, symbol.equals("getStackClosurezh") ? 12 : symbol.equals("getWordzh") ? 3 : 1); require(counts(calls).equals(expectedCounts), "Original reachable stack ABI occurrence inventory changed");
        var roots = (List<String>) proof.get("roots"); require(roots.equals(List.of("ghc-internal:GHC.Internal.Stack.CloneStack.cloneMyStack", "ghc-internal:GHC.Internal.Stack.Decode.decodeStackWithIpe", "ghc-internal:GHC.Internal.Stack.Decode.prettyStackFrameWithIpe", "ghc-internal:GHC.Internal.Heap.InfoTable.peekItbl", "ghc-internal:GHC.Internal.InfoProv.Types.lookupIPE", "ghc-internal:GHC.Internal.InfoProv.Types.peekInfoProv")));
        var routes = (List<Map<String, Object>>) proof.get("routes"); var routeOwners = new LinkedHashSet<>(); for (var route : routes) routeOwners.add(route.get("owner")); var callOwners = new LinkedHashSet<String>(); for (var call : calls) callOwners.add(call.owner()); require(routeOwners.equals(callOwners) && routeOwners.size() == routes.size());
        for (var route : routes) { var target = (String) route.get("owner"); var hops = (List<Map<String, Object>>) route.get("hops");
            for (int i = hops.size() - 1; i >= 0; i--) { var hop = hops.get(i); located(hop, owners); var reference = (List<?>) hop.get("expression"); require("var".equals(reference.get(0)) && target.equals(reference.get(1))); target = (String) hop.get("owner"); } require(roots.contains(target));
        }
        for (var call : calls) checkCall(call); return calls;
    }
    private List<Long> nativeShape(String text) {
        int end = text.length(); while (end > 0 && text.charAt(end - 1) == '\n') end--; var row = text.substring(0, end).split("\t", -1); require(row.length == 5 && row[0].equals("native-shape"));
        var values = new ArrayList<Long>(); for (int i = 1; i < row.length; i++) { long value = Long.parseLong(row[i]); require(value >= 0); values.add(value); } require(values.get(0) > 0 && values.get(1) <= values.get(0) && values.get(2) <= values.get(0)); return values;
    }
    private Object at(Object value, List<?> path) { var node = value; for (var step : path) node = step instanceof Long index ? ((List<?>) node).get(index.intValue()) : ((Map<?, ?>) node).get(step); return node; }
    /** Optional extra comparison, never a prerequisite or a skipped normal proof. */
    private boolean compareOriginals(Map<String, Object> proof) throws Exception {
        var retainedRoot = System.getenv("THC_STACK_RETAINED_ROOT"); if (retainedRoot == null) return false;
        var originals = new LinkedHashMap<String, Map<String, Object>>(); for (var record : (List<Map<String, Object>>) proof.get("originals")) { var file = new File(retainedRoot, (String) record.get("exportPath")); require(hash(file).equals(record.get("sha256"))); originals.put((String) record.get("module"), (Map<String, Object>) Json.parse(Files.readString(file.toPath()))); }
        var owners = new LinkedHashMap<Object, Map<String, Object>>(); for (var owner : (List<Map<String, Object>>) proof.get("owners")) owners.put(owner.get("id"), owner);
        for (var owner : owners.values()) { var binding = at(originals.get((String) owner.get("module")), (List<?>) owner.get("path")); var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.stringify(binding).getBytes(StandardCharsets.UTF_8))); require(digest.equals(owner.get("canonicalSha256"))); }
        var excerpts = new ArrayList<>((List<Map<String, Object>>) proof.get("calls")); for (var route : (List<Map<String, Object>>) proof.get("routes")) excerpts.addAll((List<Map<String, Object>>) route.get("hops"));
        for (var excerpt : excerpts) { var owner = owners.get(excerpt.get("owner")); require(Objects.equals(at(originals.get((String) owner.get("module")), (List<?>) excerpt.get("path")), excerpt.get("expression"))); }
        var module = CoreModules.merge(new ArrayList<>(originals.values())); var reached = new LinkedHashMap<Object, Map<String, Object>>();
        for (var name : (List<String>) proof.get("roots")) for (var binding : (List<Map<String, Object>>) CoreModules.reachable(module, name).get("bindings")) reached.put(binding.get("id"), binding);
        require(fullCounts(callsIn(reached.values())).equals(fullCounts(inventory(proof)))); return true;
    }
    @Test public void originalsRetainEveryColdGetterAndFreshConsumersRemainSeparateEvidence() throws Exception {
        var manifest = manifest(); var proof = proof(); var all = inventory(proof); boolean originalArchiveCompared = compareOriginals(proof); var reports = new ArrayList<Map<String, Object>>();
        for (var stage : ((Map<String, List<String>>) manifest.get("stages")).entrySet()) {
            String selected = null; for (var path : stage.getValue()) if (path.endsWith("/OriginalStackAudit.json")) { if (selected != null) throw new IllegalArgumentException("Multiple roots"); selected = path; } if (selected == null) throw new java.util.NoSuchElementException();
            var fresh = read(selected); require("OriginalStackAudit".equals(fresh.get("module")) && "9.14.1".equals(fresh.get("ghc"))); require((stage.getKey().equals("pre") ? "optimized-Core-before-Tidy" : "optimized-Core-after-Tidy-before-CorePrep").equals(fresh.get("boundary")));
            var names = new ArrayList<>(); for (var binding : (List<Map<String, Object>>) fresh.get("bindings")) names.add(binding.get("name")); require(names.containsAll(entries));
            var modules = new ArrayList<Map<String, Object>>(); for (var path : stage.getValue()) modules.add(read(path)); var linked = CoreModules.merge(modules);
            // Portable excerpts are not modules and must never substitute for an unfolding.
            for (var entry : entries) {
                var id = fresh.get("unit") + ":OriginalStackAudit." + entry; var reached = (List<Map<String, Object>>) CoreModules.reachable(linked, id).get("bindings"); var foreign = calls(linked, id); for (var call : foreign) if (specs.containsKey(call.symbol())) checkCall(call);
                // Missing Haskell definitions are distinct from explicitly declared FCallIds.
                String frontier; try { CoreModules.reachable(linked, id, true); frontier = null; } catch (IllegalArgumentException missing) { frontier = missing.getMessage(); }
                var foreignCalls = new ArrayList<Map<String, Object>>(); for (var call : foreign) foreignCalls.add(map("owner", call.owner(), "descriptor", call.descriptor()));
                reports.add(map("stage", stage.getKey(), "entry", entry, "evidence", "fresh-interface", "reachableBindings", reached.size(), "foreignCounts", counts(foreign), "missingDefinitions", frontier, "foreignCalls", foreignCalls));
            }
        }
        var shape = nativeShape(Files.readString(new File(root, (String) manifest.get("nativeOutput")).toPath())); directory.mkdirs();
        var originalCalls = new ArrayList<Map<String, Object>>(); for (var call : all) originalCalls.add(map("owner", call.owner(), "descriptor", call.descriptor(), "source", call.metadata().get("source")));
        Files.writeString(new File(directory, "proof.json").toPath(), Json.stringify(map("kind", "structural-original-source-frontier-not-runtime-success", "consumerFrontiers", reports, "originalArchiveCompared", originalArchiveCompared, "retainedForeignCounts", counts(all), "originalCalls", originalCalls, "nativeShapeOnly", shape)) + "\n"); assertEquals(15, specs.size());
    }
    private Call first(List<Call> calls, String symbol) { for (var call : calls) if (call.symbol().equals(symbol)) return call; throw new java.util.NoSuchElementException(symbol); }
    @Test public void descriptorAndInventoryCorruptionCannotHideColdUnsupportedOperations() throws Exception {
        manifest(); var proof = proof(); var all = inventory(proof); var cold = first(all, "getBCOLargeBitmapzh"); var badDescriptor = plus(cold.descriptor(), "safety", "unsafe"); var badCall = new ArrayList<>(cold.expression()); badCall.set(6, plus(cold.metadata(), "foreignCall", badDescriptor));
        assertThrows(IllegalArgumentException.class, () -> checkCall(new Call(cold.owner(), badCall))); var records = (List<Map<String, Object>>) proof.get("calls"); var filtered = new ArrayList<Map<String, Object>>(); for (var record : records) if (!cold.owner().equals(record.get("owner"))) filtered.add(record);
        assertThrows(IllegalArgumentException.class, () -> inventory(plus(proof, "calls", filtered))); var duplicate = new ArrayList<>(records); duplicate.add(records.getFirst()); assertThrows(IllegalArgumentException.class, () -> inventory(plus(proof, "calls", duplicate)));
        var path = new ArrayList<Object>((List<?>) records.getFirst().get("path")); path.add(0L); var otherLocation = plus(records.getFirst(), "path", path); var more = new ArrayList<>(records); more.add(otherLocation);
        assertThrows(IllegalArgumentException.class, () -> inventory(plus(proof, "calls", more))); var routes = (List<Map<String, Object>>) proof.get("routes"); var noPath = new ArrayList<Map<String, Object>>(); for (var route : routes) noPath.add(cold.owner().equals(route.get("owner")) ? plus(route, "hops", List.of()) : route);
        assertThrows(IllegalArgumentException.class, () -> inventory(plus(proof, "routes", noPath)));
        for (var symbol : specs.keySet()) { var call = first(all, symbol); var changed = new ArrayList<>(call.expression()); changed.set(3, java.util.Collections.nCopies(((List<?>) call.expression().get(3)).size(), true)); assertThrows(IllegalArgumentException.class, () -> checkCall(new Call(call.owner(), changed))); }
    }
    @Test public void provenanceAndNativeShapeFailClosedWithoutTreatingCountsAsAnOracle() throws Exception {
        var manifest = manifest(); var proof = proof(); for (var change : List.of(Map.entry("fresh", true), Map.entry("exporterRevision", "other"), Map.entry("ghcSourceRevision", "other"))) assertThrows(IllegalArgumentException.class, () -> inventory(plus(proof, change.getKey(), change.getValue())));
        var inputs = (Map<String, String>) manifest.get("inputHashes");
        for (var path : requiredInputs) assertThrows(IllegalArgumentException.class, () -> { var missing = new LinkedHashMap<>(inputs); missing.remove(path); checked(plus(manifest, "inputHashes", missing)); });
        assertThrows(IllegalArgumentException.class, () -> checked(plus(manifest, "inputHashes", plus(inputs, proofPath, "0".repeat(64)))));
        assertThrows(IllegalArgumentException.class, () -> checked(plus(manifest, "artifactHashes", map("/installed/ghc", "unhashed"))));
        assertThrows(IllegalArgumentException.class, () -> checked(plus(manifest, "ghc", plus((Map<String, Object>) manifest.get("ghc"), "installedArtifactsHashed", true))));
        var outside = Files.createTempDirectory(new File(root, "build").toPath(), "stack-proof-outside-"); var linkDirectory = Files.createTempDirectory(new File(root, "build/original-stack").toPath(), "containment-"); var link = linkDirectory.resolve("outside-link");
        try { Files.createSymbolicLink(link, outside); var path = root.toPath().relativize(link).toString().replace(File.separatorChar, '/'); assertThrows(IllegalArgumentException.class, () -> contained(path + "/no-file", false)); }
        finally { Files.deleteIfExists(link); Files.delete(linkDirectory); Files.delete(outside); }
        assertEquals(List.of(3L, 0L, 0L, 0L), nativeShape("native-shape\t3\t0\t0\t0\n")); assertEquals(List.of(9L, 7L, 7L, 88L), nativeShape("native-shape\t9\t7\t7\t88\n"));
        for (var row : List.of("native-shape\t0\t0\t0\t0", "native-shape\t1\t2\t0\t0", "native-shape\t1\t0\t2\t0", "native-shape\t1\t0\t0\t-1", "native-shape\t1\t0\t0", "native-shape\t1\t0\t0\t0\nextra")) assertThrows(IllegalArgumentException.class, () -> nativeShape(row));
    }
}
