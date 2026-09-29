// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import thc.runtime.*;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreBackendTestSupport.*;

/** Genuine exporter declarations survive optimized aliases and both loaders. */
@Tag("foreign-exceptions-full-core")
class RawInteropBoundaryTest {
    private static final String PREFIX = "main:InteropPrimitives.";
    private static final Path DIRECTORY = Path.of(System.getProperty("thc.projectRoot"), "build/foreign-exceptions/runtime-core");

    private static Map<String, Object> source(boolean compact) throws Exception {
        var source = ForeignExceptionFixtureSupport.source("post"); // Checks every producer input and artifact.
        var original = CoreCbdFixtures.read(DIRECTORY.resolve("InteropPrimitives.cbd"));
        var originalBindings = objects(original.get("bindings"));
        var replacements = new LinkedHashMap<String, Map<String, Object>>();
        if (compact) {
            var path = DIRECTORY.resolve("InteropPrimitives.cbd");
            var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
            try (var mappings = new CoreFileMappings(0, 0); var file = new CoreCompactFile(path, hash, true, mappings)) {
                assertTrue(file.header().getContainsHostSignatures());
                var records = new CoreCompactRecords(file, hash);
                for (var binding : originalBindings) {
                    String id = (String) binding.get("id");
                    var restored = records.binding(Objects.requireNonNull(file.lookup(id)));
                    assertEquals(binding.get("hostSignature"), restored.get("hostSignature"), id);
                    replacements.put(id, restored);
                }
                assertEquals(0L, file.getCounters().statistics().debugBytesRead());
                // Use the ordinary snapshot path while the reader is open;
                // deferred debug origins must not outlive this test's mapping.
                var detached = object(CoreCbdFixtures.snapshot(
                    map("modules", list(with(original, "bindings", new ArrayList<>(replacements.values()))))));
                replacements.clear();
                for (var binding : objects(objects(detached.get("modules")).getFirst().get("bindings")))
                    replacements.put((String) binding.get("id"), binding);
            }
        } else {
            for (var binding : originalBindings) replacements.put((String) binding.get("id"), binding);
        }
        var bindings = new ArrayList<Map<String, Object>>();
        for (var binding : objects(source.get("bindings"))) bindings.add(replacements.getOrDefault(binding.get("id"), binding));
        var result = with(source, "bindings", bindings);
        for (String name : List.of("getLibrary", "readOne", "writeOne", "sumBytes", "arrayLong", "caughtRead")) {
            var binding = replacements.get(PREFIX + name);
            assertNotNull(binding.get("hostSignature"), name);
            var signature = CoreHostSignature.select(binding, bindings);
            assertEquals(CoreRepresentation.HostCarrier.OBJECT, signature.inputs().getFirst().getHostCarrier(), name);
            if (name.equals("getLibrary")) assertEquals(CoreRepresentation.HostCarrier.INTEROP_LIBRARY, signature.result().getHostCarrier());
        }
        return result;
    }
    private static Value entry(Context context, String backend, Map<String, Object> source, String name) {
        String id = name.contains(":") ? name : PREFIX + name;
        var selected = CoreModules.reachable(source, id, true);
        var bindings = objects(selected.get("bindings"));
        var binding = bindings.stream().filter(value -> id.equals(value.get("id"))).findFirst().orElseThrow();
        var signature = CoreHostSignature.select(binding, bindings);
        var language = com.oracle.truffle.api.TruffleLanguage.LanguageReference.create(Language.class).get(null);
        // Direct program construction needs the same native-owner linkage as Language.instantiate.
        for (Object link : (List<?>) selected.get("packageScalarLinks"))
            Language.currentState().getPackageCbits().link((PackageScalarLink) link);
        ExecutableProgram program = backend.equals("ast") ? new Program(language, selected, false) : new BytecodeProgram(language, selected, false);
        // Exercise the public EntryValue/HostAbi transport, never Calls.target.
        return context.asValue(new EntryValue(program, id, signature.inputs().size(), null,
            null, language, null, null, false, signature.inputs(), signature.result()));
    }
    private static Context context() {
        return Context.newBuilder("thc").allowHostAccess(HostAccess.ALL).allowNativeAccess(true).build();
    }
    @ParameterizedTest @CsvSource({"ast,false", "bytecode,false", "ast,true", "bytecode,true"})
    void originalPublicGetterAndOperationsRetainTheSuppliedDispatcher(String backend, boolean compact) throws Exception {
        var source = source(compact);
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var bytes = new byte[]{3, -7, 11};
                var view = context.asValue(HostReference.storage(Language.currentState(), bytes, true));
                var library = entry(context, backend, source, "getLibrary").execute(view);
                var reader = entry(context, backend, source, "readOne");
                assertEquals(-7, reader.execute(view, library, 1L, (Object) null).getArrayElement(1).asInt());
                assertTrue(entry(context, backend, source, "writeOne").execute(view, library, 0L, (byte) -12, null).isNull());
                assertEquals(-12, bytes[0]);
                assertEquals(-12, reader.execute(view, library, 0L, (Object) null).getArrayElement(1).asInt());
                assertThrows(PolyglotException.class, () -> reader.execute(view, new Object(), 0L, null));
                var array = org.graalvm.polyglot.proxy.ProxyArray.fromArray(41L, "heterogeneous");
                var arrayView = context.asValue(array);
                var arrayLibrary = entry(context, backend, source, "getLibrary").execute(arrayView);
                assertEquals(41L, entry(context, backend, source, "arrayLong").execute(arrayView, 0L, (Object) null).getArrayElement(1).asLong());
                var writeElement = entry(context, backend, source, "main:THC.Prim.writeArrayElement#");
                assertTrue(writeElement.execute(arrayView, arrayLibrary, 1L, "changed", null).isNull());
                assertEquals("changed", arrayView.getArrayElement(1).asString());
                assertEquals(41L, arrayView.getArrayElement(0).asLong());
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @CsvSource({"ast,false", "bytecode,false", "ast,true", "bytecode,true"})
    void liftedFacadeCatchesDistinctCheckedProtocolFailures(String backend, boolean compact) throws Exception {
        var source = source(compact);
        try (var context = context()) {
            context.initialize("thc"); context.enter();
            try {
                var caught = entry(context, backend, source, "caughtRead");
                var view = context.asValue(HostReference.storage(Language.currentState(), new byte[]{-7}, false));
                assertEquals(0L, caught.execute(view, 0L, (Object) null).getArrayElement(1).asLong());
                assertEquals(1L, caught.execute(view, 9L, (Object) null).getArrayElement(1).asLong());
                assertEquals(2L, caught.execute("not a buffer", 0L, (Object) null).getArrayElement(1).asLong());
                assertEquals(3L, caught.execute(new Object(), 0L, (Object) null).getArrayElement(1).asLong());
            } finally { context.leave(); }
        }
    }
}
