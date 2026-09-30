// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.nio.file.*;
import java.util.*;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.Json;
import thc.Main;
import static org.junit.jupiter.api.Assertions.*;

/** Ordinary genuine Haskell catch/finally, not a fabricated exception dictionary. */
@Tag("truffle-strings-full-core")
class TruffleStringExceptionTest {
    @SuppressWarnings("unchecked") private static List<String> inputs() throws Exception {
        var root = Path.of(System.getProperty("thc.projectRoot"));
        var directory = root.resolve("build/truffle-strings");
        var manifest = (Map<String, Object>) Json.parse(Files.readString(directory.resolve("manifest.json")));
        for (String key : List.of("inputHashes", "artifactHashes"))
            for (var entry : ((Map<String, String>) manifest.get(key)).entrySet()) {
                var bytes = Files.readAllBytes(root.resolve(entry.getKey()));
                assertEquals(entry.getValue(), HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
            }
        String installed = System.getenv("THC_FOREIGN_EXCEPTION_INSTALLED");
        if (installed == null) installed = root.resolve("build/foreign-exceptions/installed/packages.json").toString();
        // Select the actual freshly compiled exception type without substituting
        // or rewriting any installed module or its provenance hashes.
        var packages = (Map<String, Object>) Json.parse(Files.readString(Path.of(installed)));
        packages.put("foreignExceptionBridgeUnit", "main");
        var selected = directory.resolve("exception-packages.json");
        Files.writeString(selected, Json.stringify(packages));
        var paths = new ArrayList<String>(); paths.add("@" + selected);
        for (String file : List.of("THC.Prim.cbd", "StringPrimitives.cbd", "TruffleStringExceptions.cbd",
                "THC.Exception.cbd", "THC.Internal.Exception.cbd")) paths.add(directory.resolve("core").resolve(file).toString());
        return paths;
    }
    private static Value entry(Context context, List<String> inputs, String name, String backend) {
        return Main.loadEntry(context, inputs, "main:TruffleStringExceptions." + name, true, backend,
            false, null, false, true);
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void genuineCatchInspectsNativeTypeAndCleanupRunsOnce(String backend) throws Exception {
        var inputs = inputs();
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build()) {
            // One genuine linked unit program; a second independent load would
            // duplicate the installed AST dependency universe in this test JVM.
            var function = entry(context, inputs, "caughtNumber", backend);
            // Both malformed text and integer overflow reach ForeignException;
            // cleanup contributes exactly 100, the genuine handler contributes 42.
            assertEquals(142L, function.execute(0L, 0L).asLong());
            assertEquals(142L, function.execute(0L, 1L).asLong());
            assertEquals(142L, function.execute(1L, 0L).asLong());
            assertEquals(107L, function.execute(0L, 2L).asLong());
            assertEquals(107L, function.execute(1L, 2L).asLong());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void genuineDoubleCatchHasIndependentInstance(String backend) throws Exception {
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            var floating = entry(context, inputs(), "caughtDouble", backend);
            assertEquals(142L, floating.execute(0L).asLong());
            assertEquals(107L, floating.execute(2L).asLong());
        }
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    void genuineRethrowPreservesTheNativeNumberFormatFailure(String backend) throws Exception {
        var inputs = inputs();
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            var function = entry(context, inputs, "rethrowInt", backend);
            for (long choice : new long[]{0, 1}) {
                var failure = assertThrows(PolyglotException.class, () -> function.execute(choice));
                assertTrue(failure.isHostException());
                assertInstanceOf(com.oracle.truffle.api.strings.TruffleString.NumberFormatException.class, failure.asHostException());
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"})
    @SuppressWarnings("unchecked")
    void genuineNumericRoundTripKeepsCompiledExecutionWithLinkedExceptionSupport(String backend) throws Exception {
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            var function = Main.loadEntry(context, inputs(), "main:StringPrimitives.numericRoundTrip", true, backend,
                false, null, false, true);
            for (long value : new long[]{Long.MIN_VALUE, -1, 0, 42, Long.MAX_VALUE})
                assertEquals(value, function.execute(value).asLong());
            assertTrue(function.invokeMember("compile").asBoolean());
            var before = (Map<String, Object>) Json.parse(function.getMember("diagnostics").asString());
            assertEquals(true, ((Map<?, ?>) before.get("explicitCompilation")).get("validLastTier"));
            assertEquals(42L, function.execute(42L).asLong());
            var after = (Map<String, Object>) Json.parse(function.getMember("diagnostics").asString());
            assertTrue(((Number) after.get("compiledEntries")).longValue() > ((Number) before.get("compiledEntries")).longValue());
            assertEquals(true, ((Map<?, ?>) after.get("explicitCompilation")).get("sameTargets"));
        }
    }
}
