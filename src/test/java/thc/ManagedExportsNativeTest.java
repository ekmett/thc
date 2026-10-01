// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.interop.*;
import com.oracle.truffle.api.nodes.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreBackendTestSupport.*;

class ManagedExportsNativeTest {
    @TempDir Path temporary;
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final Path directory = root.resolve("build/interface-core");
    private final Path core = directory.resolve("typed-foreign-exports/managed.cbd");
    private final String unit = "thc-interface-fixture-0.1", module = "ForeignExportManaged";
    private void verifyFile(Path file) throws Exception {
        var manifest = object(Json.parse(Files.readString(directory.resolve("manifest.json"))));
        var digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        assertEquals(object(manifest.get("artifactHashes")).get(root.relativize(file).toString()), digest);
    }
    private String verifiedFile(Path file) throws Exception { verifyFile(file); return Files.readString(file); }
    private Map<String, Object> verifiedCore(Path file) throws Exception { verifyFile(file); return CoreCbdFixtures.read(file); }
    private Map<String, Object> original() throws Exception {
        assertFalse(Files.exists(directory.resolve("typed-export-source/" + module + ".hs"))); return verifiedCore(core);
    }
    // Internal decoded-model admission controls; public loading uses CBD requests.
    private Map<String, Object> decodedRequest(Map<String, Object> source, String backend) { return map("mode", "managed-exports", "modules", list(source), "strictLink", true, "backend", backend); }
    private Value exports(Value namespace) { return namespace.getMember(unit).getMember(module); }
    private Value load(Context context, String backend) throws Exception { original(); return exports(Main.loadManagedExports(context, list(core.toAbsolutePath().toString()), backend)); }
    private Context context() { return Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").option("compiler.Inlining", "false").build(); }
    private ManagedExportRegistry registry() { return Language.currentState(null).getManagedExports(); }
    @Test void namespaceMetadataRetainsExactNamesAndContextOwnership() throws Exception {
        var first = context(); var second = context();
        try {
            first.initialize("thc"); second.initialize("thc"); first.enter(); ManagedExportNamespace namespace;
            try {
                namespace = new ManagedExportNamespace(registry(), "metadata probe", () -> map("unit:Module.export", 42L, "distinct_alias", 43L));
                var value = first.asValue(namespace); assertEquals(Set.of("unit:Module.export", "distinct_alias"), value.getMemberKeys());
                assertEquals(42L, value.getMember("unit:Module.export").asLong()); assertEquals(43L, value.getMember("distinct_alias").asLong()); assertFalse(value.hasMember("export"));
                assertThrows(UnknownIdentifierException.class, () -> InteropLibrary.getUncached().readMember(namespace, "export"));
            } finally { first.leave(); }
            second.enter();
            try {
                var interop = InteropLibrary.getUncached(); assertThrows(RuntimeFault.class, () -> interop.getMembers(namespace));
                assertThrows(RuntimeFault.class, () -> interop.isMemberReadable(namespace, "distinct_alias")); assertThrows(RuntimeFault.class, () -> interop.readMember(namespace, "distinct_alias"));
            } finally { second.leave(); }
        } finally { second.close(); first.close(); }
    }
    @Test void genuineRetainedExportsMatchNativeAndShareTheirProgramAndCaf() throws Exception {
        var nativeRows = verifiedFile(directory.resolve("logs/managed-export-native-oracle.stdout")).trim().lines().toList();
        assertEquals(list("-18", "2.25", "-1.5", "17", "3", "7", "9223372036854775828"), nativeRows);
        for (String backend : list("ast", "bytecode")) try (var context = context()) {
            var symbols = load(context, backend);
            assertEquals(Set.of("thc_add_one", "thc_add_alias", "thc_float", "thc_double", "thc_word64", "thc_constant", "thc_next", "thc_next_alias", "thc_unit"), symbols.getMemberKeys());
            assertEquals(Integer.parseInt(nativeRows.get(0)), symbols.getMember("thc_add_one").execute(-19).asInt());
            assertEquals(Integer.parseInt(nativeRows.get(0)), symbols.getMember("thc_add_alias").execute(-19).asInt());
            assertEquals(Float.parseFloat(nativeRows.get(1)), symbols.getMember("thc_float").execute(1.25f).asFloat());
            assertEquals(Double.parseDouble(nativeRows.get(2)), symbols.getMember("thc_double").execute(-3.5).asDouble());
            assertEquals(Integer.parseInt(nativeRows.get(3)), symbols.getMember("thc_constant").execute().asInt());
            var upper = BigInteger.ONE.shiftLeft(63).add(BigInteger.valueOf(19)); assertEquals(new BigInteger(nativeRows.get(6)), symbols.getMember("thc_word64").execute(upper).asBigInteger());
            assertEquals(Integer.parseInt(nativeRows.get(4)), symbols.getMember("thc_next").execute(3).asInt()); assertEquals(Integer.parseInt(nativeRows.get(5)), symbols.getMember("thc_next_alias").execute(4).asInt());
            assertTrue(symbols.getMember("thc_unit").execute().isNull()); assertEquals(symbols.getMemberKeys(), context.getBindings("thc").getMember(unit).getMember(module).getMemberKeys()); context.enter();
            try {
                var interop = InteropLibrary.getUncached(); var raw = interop.readMember(interop.readMember(registry().getScope(), unit), module);
                var first = (ManagedExportValue) interop.readMember(raw, "thc_next"); var second = (ManagedExportValue) interop.readMember(raw, "thc_next_alias");
                assertSame(first.getProgram(), second.getProgram()); assertEquals(0L, first.getProgram().diagnostics().get("unsupportedTraps"));
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                assertEquals(0, language.getHandoffState().get().getResults().getDepth()); assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
            } finally { context.leave(); }
        }
    }
    private void reject(Map<String, Object> changed) { assertThrows(RuntimeException.class, () -> ManagedExportPlan.read(decodedRequest(changed, "ast"))); }
    @Test void exactArchiveAdmissionAndScalarHostProtocolsStaySeparate() throws Exception {
        var source = original(); ManagedExportPlan.read(decodedRequest(source, "ast")); var ticket = ManagedExportAdmission.read(source, null);
        var equivalent = new LinkedHashMap<>(source); assertNotSame(source, equivalent); assertThrows(IllegalArgumentException.class, () -> new CoreModules.Merger().add(equivalent, ticket));
        var registered = CoreModules.merge(list(source)); assertEquals(1, ((List<?>) registered.get("managedRegistrations")).size());
        var proof = object(source.get("staticForeignExportRegistration")); var product = object(source.get("foreign")); var stubs = object(product.get("stubs"));
        reject(with(source, "staticForeignExportRegistration", with(proof, "schema", 1L))); reject(with(source, "staticForeignExportRegistration", with(proof, "status", "unclassified")));
        reject(with(source, "staticForeignExportRegistration", with(proof, "roots", list()))); reject(with(source, "foreign", with(product, "stubs", with(stubs, "source", "changed"))));
        reject(with(source, "foreign", with(product, "stubs", with(stubs, "initializers", list()))));
        var inventory = object(source.get("staticForeignExports")); var declarations = objects(inventory.get("exports"));
        var renamed = new ArrayList<>(declarations); renamed.set(0, with(declarations.getFirst(), "symbol", "different_alias"));
        reject(with(source, "staticForeignExports", with(inventory, "exports", renamed))); reject(with(source, "unit", "another-unit"));
        for (String variant : list("signatures", "foreign-file", "instrumented")) reject(verifiedCore(directory.resolve("typed-foreign-exports/" + variant + ".cbd")));
    }
    @Test void hostValidationPrecedesIoAndFailedLoadsDoNotPublish() throws Exception {
        for (String backend : list("ast", "bytecode")) try (var context = context()) {
            var source = original(); var proof = object(source.get("staticForeignExportRegistration"));
            var malformed = CoreCbdFixtures.write(temporary.resolve("bad-proof.cbd"), with(source, "staticForeignExportRegistration", with(proof, "schema", 1L)));
            assertThrows(RuntimeException.class, () -> context.eval("thc", CoreModules.managedExportRequest(list(malformed.toString()), backend, true)));
            assertTrue(context.getBindings("thc").getMemberKeys().isEmpty()); var symbols = load(context, backend); var next = symbols.getMember("thc_next");
            for (Object[] arguments : List.of(new Object[0], new Object[]{1, 2}, new Object[]{1L << 32}, new Object[]{1.5}, new Object[]{"3"})) assertThrows(RuntimeException.class, () -> next.execute(arguments));
            assertEquals(1, next.execute(1).asInt(), "Rejected arguments must not mutate the original CAF"); assertThrows(RuntimeException.class, () -> load(context, backend));
            assertEquals(2, symbols.getMember("thc_next_alias").execute(1).asInt());
        }
    }
    @Test void cachedLoadSourceStillCreatesContextOwnedProgramsAndRejectsForeignOrClosedValues() throws Exception {
        original(); var request = Source.newBuilder("thc", CoreModules.managedExportRequest(list(core.toString()), "ast", true), "managed-exports").cached(true).buildLiteral();
        try (var engine = Engine.newBuilder().build()) {
            var first = Context.newBuilder("thc").engine(engine).build(); var second = Context.newBuilder("thc").engine(engine).build();
            try {
                var a = exports(first.eval(request)).getMember("thc_next"); var b = exports(second.eval(request)).getMember("thc_next_alias");
                assertEquals(3, a.execute(3).asInt()); assertEquals(4, b.execute(4).asInt(), "A second context must own a fresh original CAF");
                Object raw; first.enter(); try { raw = registry().getScope(); } finally { first.leave(); }
                second.enter(); try { assertThrows(RuntimeFault.class, () -> InteropLibrary.getUncached().getMembers(raw)); } finally { second.leave(); }
                first.close(); assertThrows(RuntimeException.class, () -> a.execute(1)); assertThrows(RuntimeFault.class, () -> InteropLibrary.getUncached().getMembers(raw)); assertEquals(5, b.execute(1).asInt());
            } finally { first.close(); second.close(); }
        }
    }
    private void visit(RootCallTarget target, Set<RootCallTarget> seen, List<RootCallTarget> found) {
        if (!seen.add(target)) return; var root = target.getRootNode(); var nodes = new ArrayList<Node>(); nodes.add(root);
        if (root instanceof BytecodeRoot bytecode) for (var instruction : bytecode.getBytecodeNode().getInstructions()) for (var argument : instruction.getArguments())
            if (argument.getKind() == Instruction.Argument.Kind.NODE_PROFILE && argument.asCachedNode() != null) nodes.add(argument.asCachedNode());
        for (var node : nodes) for (var call : NodeUtil.findAllNodeInstances(node, DirectCallNode.class)) if (call.getCurrentCallTarget() instanceof RootCallTarget child) visit(child, seen, found);
        found.add(target);
    }
    private List<RootCallTarget> targets(RootCallTarget entry) { var found = new ArrayList<RootCallTarget>(); visit(entry, Collections.newSetFromMap(new IdentityHashMap<>()), found); return found; }
    @Test void firstInstalledPublicInvocationKeepsTypedPureAndIoResults() throws Exception {
        for (String backend : list("ast", "bytecode")) try (var context = context()) {
            var symbols = load(context, backend);
            for (String name : list("thc_add_one", "thc_next")) {
                var callable = symbols.getMember(name); for (int i = 0; i < 4; i++) callable.execute(0); context.enter();
                try {
                    var interop = InteropLibrary.getUncached(); var raw = (ManagedExportValue) interop.readMember(interop.readMember(interop.readMember(registry().getScope(), unit), module), name);
                    var targets = new ArrayList<>(targets(raw.getGuestTarget()));
                    if (raw.getIoTarget() != null) targets.addAll(targets(raw.getIoTarget()));
                    var cls = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
                    for (var target : targets.stream().distinct().toList()) { cls.getMethod("compile", boolean.class).invoke(target, true); assertEquals(true, cls.getMethod("isValidLastTier").invoke(target)); }
                    var runtime = Truffle.getRuntime(); runtime.getClass().getMethod("bypassedInstalledCode", cls).invoke(runtime, raw.getGuestTarget());
                    long before = ((Number) raw.getProgram().diagnostics().get("compiledEntries")).longValue();
                    assertEquals(name.equals("thc_add_one") ? 8 : 7, callable.execute(7).asInt());
                    assertTrue(((Number) raw.getProgram().diagnostics().get("compiledEntries")).longValue() > before, backend + "/" + name + " must enter installed guest code on the first public invocation");
                    assertEquals(0L, raw.getProgram().diagnostics().get("unsupportedTraps"));
                } finally { context.leave(); }
            }
        }
    }
}
