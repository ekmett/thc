// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.runtime.OriginalStdioChecks.with;

@SuppressWarnings("unchecked")
class NativeCallbacksTest {
    @TempDir Path directory;
    @ParameterizedTest @CsvSource({"ast,false", "bytecode,false", "ast,true", "bytecode,true"})
    void originalStaticExportReturnsThroughPackageCAndReleasesStablePointer(String backend, boolean compiled) throws Exception {
        var root = Path.of(System.getProperty("thc.projectRoot"));
        sources(root, "ast"); // Validate the complete actual producer receipt.
        assertEquals("43", Files.readString(root.resolve("build/dynamic-callback/static-oracle.txt")).trim());
        var inputs = List.of("@" + root.resolve("build/dynamic-callback/runtime-support.json"),
            root.resolve("build/dynamic-callback/NativeExport.json").toString());
        String entry = "static-export-fixture:NativeExport.probe";
        for (String hosting : List.of("platform", "loom")) try (var context = Main.withContextProfile(Context.newBuilder("thc")
                .allowNativeAccess(true).allowCreateThread(true).allowExperimentalOptions(true).option("thc.ThreadHosting", hosting), ContextProfile.SYNCHRONOUS_TEST).build()) {
            Main.loadEntry(context, inputs, entry, true, backend, false, null, true);
            context.enter();
            try {
                var owner = Language.currentState(); var program = owner.getCoreUnitPrograms().getFirst();
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var bindings = program.signatureBindings(entry);
                var selected = bindings.stream().filter(it -> entry.equals(it.get("id"))).findFirst().orElseThrow();
                var signature = CoreRepresentations.knownFunctionSignature((List<Object>) selected.get("expr"), bindings);
                assertNotNull(signature, "Use the original IO state/result proof");
                var io = new ManagedExportIoRoot(language, signature.getResult()).getCallTarget();
                var target = new RootNode(language) {
                    @Child private HostDispatch dispatch = HostDispatch.create();
                    @Override public Object execute(VirtualFrame frame) {
                        return dispatch.executePublic(io, new Object[]{program.entryValue(entry)});
                    }
                }.getCallTarget();
                String worker = (String) bindings.stream().filter(it -> ((List<?>) it.get("expr")).getFirst().equals("lam"))
                    .findFirst().orElseThrow().get("id");
                // The public IO binder is an unforced alias thunk. Compile its
                // original state-transformer body, not that alias's updater.
                var action = assertInstanceOf(Closure.class, program.entryValue(worker));
                var original = action.target;
                if (compiled) {
                    var optimized = (com.oracle.truffle.runtime.OptimizedCallTarget) original;
                    optimized.compile(true); assertTrue(optimized.isValidLastTier());
                    ((com.oracle.truffle.runtime.OptimizedTruffleRuntime) com.oracle.truffle.api.Truffle.getRuntime()).bypassedInstalledCode(optimized);
                }
                long before = ((Number) program.diagnostics().get("compiledEntries")).longValue();
                Runnable invoke = () -> {
                    owner.getThreads().enterCurrent(null, false, true, null);
                    try {
                        var result = (DataValue) Calls.target(target, new Object[0]);
                        assertEquals(43L, result.getLayout().readLong(result, 0), hosting);
                        assertSame(original, program.entryTarget(worker));
                        assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
                        assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                    } finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); }
                };
                if (owner.getThreads().needsHosting()) owner.getThreads().hostEntry(null, () -> { invoke.run(); return null; });
                else invoke.run();
                // Cold async handler/return profiling may deoptimize, but the
                // original installed target must receive the very first call.
                if (compiled) assertTrue(((Number) program.diagnostics().get("compiledEntries")).longValue() > before);
                assertEquals(0L, program.diagnostics().get("unsupportedTraps"));
            } finally { context.leave(); }
        }
    }
    static Map<String,Object> original() throws Exception {
        var response = (Map<String,Object>) Json.parse(Files.readString(Path.of("build/dynamic-callback/interface-response.json")));
        assertEquals("loaded", response.get("status"));
        return (Map<String,Object>) response.get("core");
    }
    @Test void originalWrapperPreservesItsActualCallbackAbiAndHelper() throws Exception {
        var module = original();
        var proof = (Map<String,Object>) module.get("staticForeignImportStubs");
        assertNotNull(ManagedImportAdmission.read(module));
        var wrappers = ManagedCallbackMetadata.read(module, proof);
        assertEquals(2, wrappers.size());
        var signature = wrappers.stream().map(ManagedCallbackSignature::signature)
            .filter(item -> item.binder().endsWith(".wrap")).findFirst().orElseThrow();
        assertEquals("callback-fixture:DynamicCallback.wrap", signature.binder());
        assertTrue(signature.io());
        assertEquals(List.of(Map.of("kind", "tycon", "name", Map.of("unit", "ghc-internal", "module", "GHC.Internal.Int",
            "occurrence", "Int32", "namespace", "type"), "arguments", List.of())), signature.arguments());
        assertEquals(signature.arguments().getFirst(), signature.result());
        var item = (Map<String,Object>) ((List<?>) proof.get("wrappers")).getFirst();
        var altered = with(proof, "wrappers", List.of(with(item, "arguments", List.of())));
        assertThrows(IllegalArgumentException.class, () -> ManagedCallbackMetadata.read(module, altered));
        var root = Path.of(System.getProperty("thc.projectRoot"));
        sources(root, "bytecode"); // Verify the genuine compact artifact identities too.
        var artifact = root.resolve("build/dynamic-callback/DynamicCallback.cbd");
        var identity = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact)));
        try (var file = new CoreCompactFile(artifact, identity, true)) {
            var compact = new CoreCompactRecords(file, identity).header();
            assertEquals(wrappers, ManagedCallbackMetadata.read(compact, (Map<?,?>) compact.get("staticForeignImportStubs")));
            var expected = (List<Map<String,Object>>) module.get("constructors");
            var actual = (List<Map<String,Object>>) compact.get("constructors");
            assertEquals(expected.size(), actual.size());
            for (int i = 0; i < expected.size(); i++) {
                var semantic = new java.util.LinkedHashMap<>(expected.get(i)); semantic.remove("type");
                assertEquals(semantic, actual.get(i), "Mixed JSON/CBD constructor " + semantic.get("id"));
            }
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void originalCallbackRunsDirectlyAndAfterNativeRetention(String backend) throws Exception {
        var root = Path.of(System.getProperty("thc.projectRoot"));
        var sources = sources(root, backend);
        assertEquals("(12,13,2,24)", Files.readAllLines(root.resolve("build/dynamic-callback/oracle.txt")).getFirst());
        try (var context = Main.withContextProfile(Context.newBuilder("thc").allowNativeAccess(true), ContextProfile.SYNCHRONOUS_TEST).build()) {
            context.eval("thc", CoreModules.request(sources, "callback-fixture:DynamicCallback.run", true, false, backend, true, false, null, true));
            context.enter();
            try {
                var owner = Language.currentState(null);
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                ExecutableProgram program = owner.getCoreUnitPrograms().getFirst();
                var ioProof = ManagedCallbackMetadata.read(original(), (Map<?, ?>) original().get("staticForeignImportStubs")).getFirst().signature().ioResult();
                var ioTarget = new ManagedExportIoRoot(language, ioProof).getCallTarget();
                var target = new RootNode(language, new FrameLayout().build()) {
                    @Child private HostDispatch dispatch = HostDispatch.create();
                    @Child private Force force = new Force(new Metrics(false));
                    @Override public Object execute(VirtualFrame frame) {
                        frame.setLong(FrameLayout.BLOOM_FILTER, 0L);
                        var input = program.constructorLayout("ghc-internal:GHC.Internal.Int.I32#").createInt(5);
                        Object action = dispatch.executePublic(program.hostEntryTarget(1), new Object[]{program.entryValue("callback-fixture:DynamicCallback.run"), new Object[]{input}});
                        var tuple = (DataValue) dispatch.executePublic(ioTarget, new Object[]{action});
                        var values = new long[4];
                        for (int i = 0; i < 4; i++) {
                            var value = (DataValue) force.execute(frame, tuple.getLayout().read(tuple, i));
                            values[i] = value.getLayout().isInt(0) ? value.getLayout().readInt(value, 0) : value.getLayout().readLong(value, 0);
                        }
                        return values;
                    }
                }.getCallTarget();
                owner.getThreads().enterCurrent(null, false, true, null);
                try { assertArrayEquals(new long[]{12,13,2,24}, (long[]) Calls.target(target, new Object[0])); }
                finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); }
                assertEquals(0L, program.diagnostics().get("unsupportedTraps"));
                assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
            } finally { context.leave(); }
        }
    }

    private List<String> sources(Path root, String backend) throws Exception {
        var manifest = (Map<String,Object>) Json.parse(Files.readString(root.resolve("build/dynamic-callback/manifest.json")));
        thc.runtime.OriginalStdioChecks.hashes(root.toFile(), manifest.get("inputHashes"), java.util.Set.of(
            "t/fixtures/run-static-exports/NativeExport.hs", "t/fixtures/run-static-exports/Main.hs",
            "t/fixtures/run-static-exports/cbits/callbacks.c", "t/fixtures/run-static-exports/cbits/callbacks.h",
            "src/compiler/THC/ForeignExportProvenance.hs",
            "t/fixtures/compiler/DynamicCallback.hs", "t/fixtures/compiler/DynamicCallbackNative.hs",
            "t/fixtures/compiler/dynamic-callback.c", "t/haskell-fixtures/DynamicCallbackFixtures.hs",
            "src/compiler/THC/ForeignImportProvenance.hs", "src/compiler/THC/Plugin.hs",
            "src/driver/THC/Driver/PackageNative.hs", "bin/audit-core.py", "bin/core_package_manifest.py"), null);
        thc.runtime.OriginalStdioChecks.hashes(root.toFile(), manifest.get("artifactHashes"), java.util.Set.of(
            "build/dynamic-callback/DynamicCallback.json", "build/dynamic-callback/DynamicCallback.cbd",
            "build/dynamic-callback/runtime-support.json", "build/dynamic-callback/interface-response.json",
            "build/dynamic-callback/oracle.txt", "build/dynamic-callback/audit.json", "build/dynamic-callback/NativeExport.json",
            "build/dynamic-callback/static-oracle.txt", "build/dynamic-callback/static-audit.json"), "build/dynamic-callback/");
        var support = root.resolve("build/dynamic-callback/runtime-support.json");
        if (backend.equals("ast")) return List.of("@" + support, root.resolve("build/dynamic-callback/DynamicCallback.json").toString());
        // Compact inputs use the package directory, not the loose JSON input path.
        var document = (Map<String,Object>) Json.parse(Files.readString(support));
        var hashes = (Map<String,Object>) manifest.get("artifactHashes");
        var path = root.resolve("build/dynamic-callback/DynamicCallback.cbd");
        var identity = (String) hashes.get("build/dynamic-callback/DynamicCallback.cbd");
        try (var file = new CoreCompactFile(path, identity, true)) {
            var header = file.header();
            var unit = new java.util.LinkedHashMap<String,Object>();
            unit.put("id", "callback-fixture"); unit.put("depends", List.of("ghc-internal"));
            var facts = new CoreCompactRecords(file, identity).header();
            if (facts.get("targetLayout") != null) unit.put("targetLayout", facts.get("targetLayout"));
            unit.put("modules", List.of(Map.of("name", "DynamicCallback", "boundary", facts.get("boundary"),
                "sha256", hashes.get("build/dynamic-callback/DynamicCallback.json"),
                "compact", Map.of("path", path.toString(), "sha256", identity, "format", CoreCompactFormat.NAME),
                "containsDelimitedControl", header.getContainsDelimitedControl(), "registrationObligations", header.getRegistrationObligations(),
                "mainAlias", header.getMainAlias(), "packageScalarDeclarations", header.getPackageScalarDeclarations())));
            ((List<Object>) document.get("units")).add(unit);
        }
        var packages = directory.resolve("packages.json"); Files.writeString(packages, Json.stringify(document));
        return List.of("@" + packages);
    }

    private static Object io(ExecutableProgram program, Language language, CoreRepresentation ioProof, String name, Object... inputs) {
        Object entry = program.entryValue("callback-fixture:DynamicCallback." + name);
        var ioTarget = new ManagedExportIoRoot(language, ioProof).getCallTarget();
        var target = new RootNode(language) {
            @Child private HostDispatch dispatch = HostDispatch.create();
            @Override public Object execute(VirtualFrame frame) {
                Object action = inputs.length == 0 ? entry : dispatch.executePublic(program.hostEntryTarget(inputs.length), new Object[]{entry, inputs});
                return dispatch.executePublic(ioTarget, new Object[]{action});
            }
        }.getCallTarget();
        return Calls.target(target, new Object[0]);
    }

    private static DataValue pointer(ExecutableProgram program, String type, ManagedAddress address) {
        var layout = program.constructorLayout("ghc-internal:GHC.Internal.Ptr." + type);
        var result = layout.allocate(); layout.initialize(result, 0, address); return result;
    }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void nativeCallbackRetainsAddressIdentityUnmaskedEntryAndExplicitLifetime(String backend) throws Exception {
        var root = Path.of(System.getProperty("thc.projectRoot"));
        assertEquals("(16,1,4)", Files.readAllLines(root.resolve("build/dynamic-callback/oracle.txt")).get(1));
        try (var context = Main.withContextProfile(Context.newBuilder("thc").allowNativeAccess(true), ContextProfile.SYNCHRONOUS_TEST).build()) {
            context.eval("thc", CoreModules.request(sources(root, backend), "callback-fixture:DynamicCallback.makePointer", true, false, backend, true, false, null, true));
            context.enter();
            try {
                var owner = Language.currentState(null);
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                ExecutableProgram program = owner.getCoreUnitPrograms().getFirst();
                var ioProof = ManagedCallbackMetadata.read(original(), (Map<?, ?>) original().get("staticForeignImportStubs")).getFirst().signature().ioResult();
                var allocation = ManagedAllocation.mutable(8, 8, true);
                var address = ManagedAddress.fromAllocation(allocation);
                address.writeNativeInt(0, 4, 7);
                owner.getThreads().enterCurrent(MaskingState.MASKED_INTERRUPTIBLE, false, true, null);
                try {
                    var caller = owner.getThreads().currentIdentity();
                    var callbackValue = (DataValue) io(program, language, ioProof, "makePointer");
                    var callback = (ManagedAddress) callbackValue.getLayout().read(callbackValue, 0);
                    assertNotEquals(0, callback.toNativeBits());
                    var echoedValue = (DataValue) io(program, language, ioProof, "echoPointer", callbackValue);
                    var echoed = (ManagedAddress) echoedValue.getLayout().read(echoedValue, 0);
                    assertEquals(callback.toNativeBits(), echoed.toNativeBits());
                    try (var other = Main.withContextProfile(Context.newBuilder("thc").allowNativeAccess(true), ContextProfile.SYNCHRONOUS_TEST).build()) {
                        other.initialize("thc"); other.enter();
                        try { assertThrows(RuntimeFault.class, callback::toNativeBits); }
                        finally { other.leave(); }
                    }
                    var boxed = pointer(program, "Ptr", address);
                    var returned = (DataValue) io(program, language, ioProof, "callPointer", echoedValue, boxed);
                    var alias = (ManagedAddress) returned.getLayout().read(returned, 0);
                    assertSame(allocation, alias.cbitsOwner());
                    assertTrue(alias.sameLocation(address.plus(4)));
                    assertEquals(16, ManagedAddressRead.WORD32.readInt(address, 0));
                    assertEquals(1, ManagedAddressRead.WORD32.readInt(address, 1));
                    assertSame(caller, owner.getThreads().currentIdentity());
                    assertEquals(MaskingState.MASKED_INTERRUPTIBLE, owner.getMaskingState().get());
                    assertThrows(RuntimeException.class, () -> io(program, language, ioProof, "unsafePointer", callbackValue, boxed));
                    assertEquals(16, ManagedAddressRead.WORD32.readInt(address, 0), "unsafe callback must fail before its guest writes");
                    var cells = ManagedAddress.fromAllocation(ManagedAllocation.mutable(8, 8, true));
                    cells.writeAddressElementIndex(0, address);
                    var blocked = assertThrows(RuntimeException.class, () -> io(program, language, ioProof, "callPointer", callbackValue, pointer(program, "Ptr", cells)));
                    assertTrue(blocked.getMessage().contains("pointer-cell projection"), blocked.toString());
                    assertSame(address, cells.readAddressElementIndex(0), "callback rejection must still reconcile and release its projection");
                    io(program, language, ioProof, "releasePointer", callbackValue);
                    assertThrows(RuntimeFault.class, callback::toNativeBits);
                    assertThrows(RuntimeFault.class, echoed::toNativeBits, "ordinary native results must retain callback lifetime authority");
                    assertSame(callback, echoed);
                    assertThrows(RuntimeException.class, () -> io(program, language, ioProof, "callPointer", echoedValue, boxed));
                    assertEquals(16, ManagedAddressRead.WORD32.readInt(address, 0));
                    assertThrows(RuntimeFault.class, () -> owner.getNativeCallbacks().free(callback));
                } finally { owner.getThreads().leaveCurrent(GuestThreadStatus.FINISHED); }
                assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
            } finally { context.leave(); }
        }
    }
}
