// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import thc.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Cabal C sources, native observations and unchanged retained library artifacts. */
@EnabledOnOs(OS.LINUX)
@EnabledIfSystemProperty(named = "os.arch", matches = "amd64|x86_64")
@SuppressWarnings("unchecked")
public class PackageScalarCbitsTest {
    @TempDir public Path temporary;
    private final File root = new File(System.getProperty("thc.projectRoot"));
    private final String prefix = "build/package-scalar-cbits";
    private final Set<String> names = Set.of("scalarInt32", "scalarInt64", "scalarFloat", "scalarDouble", "scalarMixed", "repeatInt32");
    private Map<String, Object> json(String path) throws Exception { return (Map<String, Object>) Json.parse(Files.readString(new File(root, path).toPath())); }
    private record Fixture(Map<String, Object> merged, List<Map<String, Object>> records, List<PackageScalarLink> links, File programManifest) {}
    private Map<String, Object> with(Map<String, Object> original, String key, Object value) { var result = new LinkedHashMap<>(original); result.put(key, value); return result; }
    private Fixture fixture() throws Exception {
        var manifest = json(prefix + "/manifest.json"); assertEquals(1L, manifest.get("schema")); assertEquals(true, manifest.get("supported")); assertEquals(true, manifest.get("strictAccepted")); assertEquals(true, manifest.get("runtimeVerified")); assertEquals(60L, manifest.get("nativeRows"));
        OriginalStdioChecks.hashes(root, manifest.get("inputHashes"), Set.of("test/fixtures/run-scalar-cbits/src/Scalar.hs", "test/fixtures/run-scalar-cbits/src/ScalarAgain.hs", "test/fixtures/run-scalar-cbits/cbits/scalar.c", "test/fixtures/run-scalar-cbits/cbits/scalar.h", "src/driver/THC/Driver/ScalarBitcode.hs", "src/compiler/THC/Plugin.hs", "test/haskell-fixtures/PackageScalarFixtures.hs"), null);
        var records = (List<Map<String, Object>>) manifest.get("records"); var recordNames = new ArrayList<Object>(); var units = new HashSet<Object>(); var required = new LinkedHashSet<String>();
        for (var name : List.of("first", "second")) { required.add(prefix + "/" + name + "/packages.json"); required.add(prefix + "/" + name + "/audit.json"); }
        for (var record : records) { recordNames.add(record.get("name")); units.add(record.get("unit")); for (var reference : (List<Map<String, String>>) record.get("libraryArtifacts")) required.add(root.toPath().relativize(Path.of(reference.get("path"))).toString().replace(File.separatorChar, '/')); }
        assertEquals(List.of("first", "second"), recordNames); assertEquals(2, units.size()); OriginalStdioChecks.hashes(root, manifest.get("artifactHashes"), required, prefix + "/");
        var modules = new ArrayList<Map<String, Object>>(); Map<String, Object> programDocument = null; var programUnits = new LinkedHashMap<String, Map<String, Object>>();
        for (var record : records) {
            var packages = json((String) record.get("packages")); var packageUnits = (List<Map<String, Object>>) packages.get("units");
            if (programDocument == null) { programDocument = packages; for (var original : packageUnits) programUnits.put((String) original.get("id"), original); }
            assertEquals(programDocument.get("foreignExceptionBridgeUnit"), packages.get("foreignExceptionBridgeUnit"), "both acquisitions use the same actual runtime exception unit"); Map<String, Object> unit = null;
            for (var candidate : packageUnits) if (Objects.equals(candidate.get("id"), record.get("unit"))) { assertNull(unit); unit = candidate; } Objects.requireNonNull(unit);
            List<String> keys; if (unit.containsKey("json")) { assertFalse(unit.containsKey("bundle"), "pair and legacy ZIP cannot both be selected"); keys = List.of("json", "symbols"); } else keys = List.of("bundle");
            var references = (List<Map<String, String>>) record.get("libraryArtifacts"); assertEquals(keys.size(), references.size(), "retain every selected unit artifact"); var relocated = new LinkedHashMap<>(unit);
            for (int i = 0; i < keys.size(); i++) { String key = keys.get(i); var retained = references.get(i); var original = (Map<String, Object>) unit.get(key); assertEquals(original.get("sha256"), retained.get("sha256"), "unchanged " + key + " artifact hash"); String suffix = switch (key) { case "json" -> ".jsons"; case "symbols" -> ".symbols"; default -> ".zip"; }; assertEquals(new File(root, prefix + "/" + record.get("name") + "/library/" + record.get("unit") + suffix).getCanonicalPath(), new File(retained.get("path")).getCanonicalPath(), "retained " + key + " path"); relocated.put(key, with(original, "path", new File(retained.get("path")).getCanonicalPath())); }
            programUnits.put((String) record.get("unit"), relocated);
            // Select unchanged hashed acquisition products, never synthetic providers or payloads.
            var selected = temporary.resolve(record.get("name") + "-packages.json"); var selection = with(packages, "units", List.of(relocated)); Files.writeString(selected, Json.stringify(selection)); var directory = CoreUnitDirectory.read(selection);
            if (directory == null) CorePackageManifest.visitModules(selected.toString(), (module, origin) -> modules.add(module));
            else {
                assertEquals(1, directory.getUnits().size()); var originalUnit = directory.getUnits().getFirst(); var source = Objects.requireNonNull(originalUnit.getJson()); var symbols = Objects.requireNonNull(originalUnit.getSymbols());
                try (var reader = new CoreJsonSymbols(source.getPath(), symbols.getPath(), true, source.getSha256(), symbols.getSha256())) { for (var module : originalUnit.getModules()) reader.verifyModule(module.getSpan(), module.getSha256(), original -> { assertEquals("9.14.1", original.get("ghc")); assertEquals(originalUnit.getId(), original.get("unit")); assertEquals(module.getName(), original.get("module")); assertEquals("optimized-Core-after-Tidy-before-CorePrep", original.get("boundary")); CoreForeignArtifacts.validateArchive(original); for (var binding : (List<Map<String, Object>>) original.get("bindings")) { String id = (String) binding.get("id"); assertTrue(id.startsWith(module.getPrefix()) || id.equals("main::" + module.getName() + ".main"), "original binding remains owned by selected unit/module"); } modules.add(original); }); }
            }
            var rows = (List<Map<String, Object>>) record.get("observations"); assertEquals(30, rows.size()); var observedNames = new HashSet<Object>(); var observedModules = new HashSet<Object>(); Map<String, Object> zero = null;
            for (var row : rows) { observedNames.add(row.get("entry")); observedModules.add(row.get("module")); if (Objects.equals(row.get("entry"), "scalarInt32")) { var args = (List<?>) row.get("arguments"); assertEquals(1, args.size()); if (Objects.equals(((Map<?, ?>) args.getFirst()).get("value"), "0")) { assertNull(zero); zero = row; } } }
            assertEquals(names, observedNames); assertEquals(Set.of("Scalar", "ScalarAgain"), observedModules); assertEquals(Objects.equals(record.get("name"), "first") ? "1" : "2", ((Map<?, ?>) Objects.requireNonNull(zero).get("result")).get("value"), "native copies must exercise different implementations of the same C symbol");
        }
        var merged = CoreModules.merge(modules); var links = (List<PackageScalarLink>) merged.get("packageScalarLinks"); assertEquals(2, links.size());
        var symbols = new ArrayList<List<String>>(); var entryNames = new ArrayList<Set<String>>(); var reps = new HashSet<String>();
        for (var link : links) { boolean close = false; var names = new ArrayList<String>(); var entries = new HashSet<String>(); for (var abi : link.getAbi()) { if (abi.getSymbol().equals("thc_io_v1_close")) close = true; names.add(abi.getSymbol()); entries.add(abi.getEntry()); reps.addAll(abi.getArguments()); reps.add(abi.getResult()); } assertTrue(close, "package-owned leaf must take precedence over the managed adapter with this spelling"); symbols.add(names); entryNames.add(entries); }
        assertEquals(symbols.get(0), symbols.get(1), "same source C names"); var common = new HashSet<>(entryNames.get(0)); common.retainAll(entryNames.get(1)); assertTrue(common.isEmpty(), "separate component entry names"); assertEquals(Set.of("Int32Rep", "Int64Rep", "FloatRep", "DoubleRep"), reps); int importers = 0; for (var module : modules) if (module.containsKey("packageScalarLink") || module.containsKey("packageNativeLink")) importers++; assertEquals(4, importers, "two typed importing modules in each component");
        // Retain dependencies and the original exception bridge for actual execution.
        var programManifest = temporary.resolve("scalar-program-packages.json"); Files.writeString(programManifest, Json.stringify(with(Objects.requireNonNull(programDocument), "units", new ArrayList<>(programUnits.values())))); return new Fixture(merged, records, links, programManifest.toFile());
    }
    private Context context(boolean nativeAccess) { return Main.withContextProfile(Context.newBuilder("thc").allowNativeAccess(nativeAccess), ContextProfile.SYNCHRONOUS_TEST).build(); }
    private void valid(RootCallTarget target, String label) throws Exception { assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target), label); }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> result) { if (!seen.add(target)) return; var body = target.getRootNode(); var nodes = new ArrayList<Node>(); nodes.add(body); if (body instanceof BytecodeRoot bytecode) for (var instruction : bytecode.getBytecodeNode().getInstructions()) for (var arg : instruction.getArguments()) if (arg.getKind() == Instruction.Argument.Kind.NODE_PROFILE) { var cached = arg.asCachedNode(); if (cached != null) nodes.add(cached); } for (var node : nodes) for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class)) if (call.getCurrentCallTarget() instanceof RootCallTarget next && next.getRootNode() instanceof GuestRoot) visit(next, seen, result); result.add(target); }
    private List<RootCallTarget> targets(RootCallTarget entry) { var result = new ArrayList<RootCallTarget>(); visit(entry, Collections.newSetFromMap(new IdentityHashMap<>()), result); return result; }
    private void released(Language language) { var state = language.getHandoffState().get(); assertEquals(0, state.getArguments().getDepth()); assertEquals(0, state.getResults().getDepth()); assertEquals(0, state.getArguments().retainedReferences()); assertEquals(0, state.getResults().retainedReferences()); }
    private String identity(Map<String, Object> record, Map<String, Object> row) { return record.get("unit") + ":" + row.get("module") + "." + row.get("entry"); }
    private static final class ScalarCallRoot extends RootNode {
        final PackageScalarCall call; @Child PackageScalarAccess access;
        ScalarCallRoot(Language language, PackageScalarCall call) { super(language); this.call = call; access = new PackageScalarAccess(call); }
        @Override public String getName() { return "package scalar access " + call.getSignature().getSymbol(); }
        @Override public Object execute(VirtualFrame frame) { var args = (Object[]) frame.getArguments()[0]; var state = frame.getArguments()[1]; return switch (call.getResult()) { case "Int32Rep" -> access.executeInt(args, state); case "Int64Rep" -> access.executeLong(args, state); case "FloatRep" -> access.executeFloat(args, state); case "DoubleRep" -> access.executeDouble(args, state); default -> throw new IllegalStateException("Unexpected test ABI"); }; }
    }
    private void checkNestedForeignScope(GuestThreads threads, Runnable action) { var previous = threads.enterForeign(ForeignSafety.UNSAFE); try { assertEquals(GuestThreads.DeliveryPermission.NONE, previous); action.run(); var nested = threads.enterForeign(ForeignSafety.UNSAFE); try { assertEquals(GuestThreads.DeliveryPermission.FOREIGN, nested, "scalar call restores its outer foreign extent"); } finally { threads.leaveForeign(nested); } } finally { threads.leaveForeign(previous); } var restored = threads.enterForeign(ForeignSafety.UNSAFE); try { assertEquals(GuestThreads.DeliveryPermission.NONE, restored, "scalar call leaves no foreign extent"); } finally { threads.leaveForeign(restored); } }
    private record Observation(Map<String, Object> record, Map<String, Object> row) {}
    private void check(Observation observation, String backend, Map<String, RootCallTarget> entries, Language language) { var row = observation.row; String entry = identity(observation.record, row); var args = (List<Map<String, Object>>) row.get("arguments"); var reps = new ArrayList<Object>(); for (var arg : args) reps.add(arg.get("rep")); assertEquals(List.of("IntRep"), reps); var expected = (Map<String, Object>) row.get("result"); assertEquals("IntRep", expected.get("rep")); assertEquals(1, args.size()); long result = (Long) Calls.target(entries.get(entry), new Object[] {0L, Long.parseLong((String) args.getFirst().get("value"))}); String label = backend + "/" + entry + "/" + args.getFirst().get("value"); switch ((String) row.get("comparison")) { case "exact" -> assertEquals(Long.parseLong((String) expected.get("value")), result, label); case "float-nan" -> assertTrue(Float.isNaN(Float.intBitsToFloat((int) result)), label); case "double-nan" -> assertTrue(Double.isNaN(Double.longBitsToDouble(result)), label); default -> fail("Unknown native comparison: " + row); } released(language); }
    @Test public void genuineCabalLibrariesMatchNativeInBothFirstInstalledBackends() throws Exception {
        var fixture = fixture(); var rows = new ArrayList<Observation>(); for (var record : fixture.records) for (var row : (List<Map<String, Object>>) record.get("observations")) rows.add(new Observation(record, row));
        for (var backend : List.of("ast", "bytecode")) try (var context = context(true)) { context.eval("thc", CoreModules.request(List.of("@" + fixture.programManifest), identity(rows.getFirst().record, rows.getFirst().row), true, false, backend, true, false, null, false, false, true)); context.enter(); try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var programs = Language.currentState(null).getCoreUnitPrograms(); assertEquals(1, programs.size()); var program = programs.getFirst(); var entries = new LinkedHashMap<String, RootCallTarget>(); for (var row : rows) { String name = identity(row.record, row.row); if (!entries.containsKey(name)) entries.put(name, program.entryTarget(name)); }
            for (var row : rows) check(row, backend, entries, language); var installed = new LinkedHashSet<RootCallTarget>(); for (var target : entries.values()) installed.addAll(targets(target)); for (var target : installed) { target.getClass().getMethod("compile", boolean.class).invoke(target, true); valid(target, backend + "/" + target.getRootNode().getName() + " installed"); }
            // No warmup after installation hides a first-entry bailout.
            for (var row : rows.reversed()) { long before = ((Number) program.diagnostics().get("compiledEntries")).longValue(); check(row, backend, entries, language); assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before, backend + "/" + identity(row.record, row.row) + " entered compiled guest code"); for (var target : installed) valid(target, backend + "/" + target.getRootNode().getName() + " after native call"); }
            assertEquals(0L, ((Number) program.diagnostics().get("unsupportedTraps")).longValue()); System.out.println("PackageScalarCbits PASS " + backend + " nativeRows=" + rows.size() + " entries=" + entries.size() + " libraries=" + fixture.links.size());
        } finally { context.leave(); } }
    }
    private record Call(RootCallTarget target, Object[] values) {}
    @Test public void nativeAuthorityContextOwnershipAndScalarCarriersAreCheckedBeforeCalls() throws Exception {
        var fixture = fixture(); try (var context = context(false)) { context.initialize("thc"); context.enter(); try { assertThrows(RuntimeFault.class, () -> Language.currentState(null).getPackageCbits().link(fixture.links.getFirst())); } finally { context.leave(); } }
        try (var first = context(true)) { first.initialize("thc"); first.enter(); try {
            var owner = Language.currentState(null); var language = TruffleLanguage.LanguageReference.create(Language.class).get(null); var registry = owner.getPackageCbits(); for (var link : fixture.links) registry.link(link); for (var link : fixture.links) registry.link(link); var link = fixture.links.getFirst(); assertThrows(RuntimeFault.class, () -> registry.link(new PackageScalarLink(link.getUnit() + ":alias", link.getTarget(), link.getComponentSha256(), link.getBitcodeSha256(), link.getBytes(), link.getAbi()))); var calls = new ArrayList<Call>();
            for (var signature : link.getAbi()) {
                var values = new Object[signature.getArguments().size()]; for (int i = 0; i < values.length; i++) values[i] = switch (signature.getArguments().get(i)) { case "Int32Rep" -> 0; case "Int64Rep" -> 0L; case "FloatRep" -> 0.0f; case "DoubleRep" -> 0.0; default -> throw new IllegalStateException("Unexpected test ABI"); }; var target = new ScalarCallRoot(language, new PackageScalarCall(link, signature)).getCallTarget(); checkNestedForeignScope(owner.getThreads(), () -> Calls.target(target, new Object[] {values, thc.runtime.Unit.INSTANCE})); Calls.target(target, new Object[] {values, thc.runtime.Unit.INSTANCE}); assertThrows(RuntimeFault.class, () -> Calls.target(target, new Object[] {values, 0L}));
                for (int i = 0; i < values.length; i++) { var invalid = values.clone(); invalid[i] = "not a scalar"; assertThrows(RuntimeFault.class, () -> Calls.target(target, new Object[] {invalid, thc.runtime.Unit.INSTANCE})); if (signature.getArguments().get(i).equals("Int32Rep")) { invalid[i] = (long) Integer.MAX_VALUE + 1; assertThrows(RuntimeFault.class, () -> Calls.target(target, new Object[] {invalid, thc.runtime.Unit.INSTANCE})); } } calls.add(new Call(target, values));
            }
            try (var second = context(true)) { second.initialize("thc"); second.enter(); try { assertThrows(RuntimeFault.class, () -> registry.link(link)); for (var call : calls) assertThrows(RuntimeFault.class, () -> Calls.target(call.target, new Object[] {call.values, thc.runtime.Unit.INSTANCE})); } finally { second.leave(); } }
            for (var call : calls) { Calls.target(call.target, new Object[] {call.values, thc.runtime.Unit.INSTANCE}); call.target.getClass().getMethod("compile", boolean.class).invoke(call.target, true); valid(call.target, "cached scalar access before registry close"); }
            registry.close(); assertThrows(RuntimeFault.class, () -> registry.link(link)); for (var call : calls) assertThrows(RuntimeFault.class, () -> Calls.target(call.target, new Object[] {call.values, thc.runtime.Unit.INSTANCE}));
        } finally { first.leave(); } }
    }
}
