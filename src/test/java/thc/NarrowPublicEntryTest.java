// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreBackendTestSupport.*;

/** Public ABI controls, independently modeled rather than claimed as GHC exports. */
class NarrowPublicEntryTest {
    @TempDir Path directory;
    @AfterEach void releaseMappings() { CoreFileMappings.shared.evictIdleBelow(directory); }
    private final Map<String, Object> closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private Map<String, Object> integer(String rep) { return map("kind", "long", "primReps", list(rep), "evaluated", true); }
    private List<Object> variable(String id, Map<String, Object> rep) { return list("var", id, map("rep", rep)); }
    private List<Object> literal(String tag, String value, Map<String, Object> rep) { return list("lit", tag, value, map("rep", rep)); }
    private List<Object> primitive(String name, List<List<Object>> inputs, Map<String, Object> rep) { return list("app", list("prim", name, map()), inputs, Collections.nCopies(inputs.size(), false), false, false, map("rep", rep)); }
    private Map<String, Object> binding(String id, int arity, List<Object> body) { return binding(id, arity, body, closure); }
    private Map<String, Object> binding(String id, int arity, List<Object> body, Map<String, Object> rep) { return map("id", "uN:N." + id, "name", id, "arity", arity, "lifted", rep.equals(closure), "rep", rep, "expr", body); }
    private record Input(String id, Map<String, Object> rep) {}
    private Map<String, Object> function(List<Input> inputs, Map<String, Object> result, List<Object> body) {
        return binding("impl", inputs.size(), list("lam", inputs.stream().map(input -> map("id", input.id(), "name", input.id(), "lifted", false, "coercion", false, "rep", input.rep())).toList(), body,
            map("rep", closure, "resultRep", result)));
    }
    private String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private String request(List<Map<String, Object>> bindings, String backend, boolean indexed) throws Exception {
        var metadata = map("schema", 1, "ghc", "9.14.1", "unit", "uN", "module", "N", "boundary", "optimized-Core-after-Tidy-before-CorePrep", "constructors", list());
        var module = with(metadata, "bindings", bindings);
        var path = directory.resolve("N.cbd");
        var record = CoreCbdFixtures.module(path, module);
        if (!indexed) return CoreModules.request(list(path.toString()), "uN:N.entry", true, false, backend, false, false, null, false, true);
        var unit = map("id", "uN", "depends", list(), "modules", list(record));
        var manifest = directory.resolve("packages.json");
        Files.writeString(manifest, Json.stringify(map("format", "thc-core-packages", "schema", 1, "ghc", "9.14.1", "units", list(unit))));
        return CoreModules.request(list("@" + manifest), "uN:N.entry", true, false, backend, false, false, null, false, true);
    }
    private void check(List<Map<String, Object>> bindings, String backend, boolean indexed, Consumer<Value> body) throws Exception {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
                .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
            body.accept(context.eval("thc", request(bindings, backend, indexed)));
        }
    }
    private long entries(Value entry) { return ((Number) object(Json.parse(entry.getMember("diagnostics").asString())).get("thunkEvaluations")).longValue(); }
    @ParameterizedTest @CsvSource({"ast,false", "bytecode,false", "ast,true", "bytecode,true"})
    void thunkAliasRetainsSignedInputProofWithoutForcingBeforeValidation(String backend, boolean indexed) throws Exception {
        var rep = integer("Int32Rep");
        check(list(function(list(new Input("x", rep)), rep, variable("x", rep)), binding("entry", 1, variable("uN:N.impl", closure))), backend, indexed, entry -> {
            long before = entries(entry);
            for (Object bad : list(2147483648L, -2147483649L, 1.5, "1")) assertThrows(PolyglotException.class, () -> entry.execute(bad));
            assertEquals(before, entries(entry), "invalid host inputs must not force the alias");
            for (int i = 0; i < 3; i++) { assertEquals(-1L, entry.execute(-1L).asLong()); assertEquals(-2147483648L, entry.execute(Integer.MIN_VALUE).asLong()); }
            assertTrue(entry.invokeMember("compile").asBoolean()); assertEquals(2147483647L, entry.execute(Integer.MAX_VALUE).asLong());
        });
    }
    @ParameterizedTest @CsvSource({"ast,false", "bytecode,false", "ast,true", "bytecode,true"})
    void aliasResultZeroExtendsWord32WithMachineWordInput(String backend, boolean indexed) throws Exception {
        var word = integer("WordRep"); var narrow = integer("Word32Rep");
        check(list(function(list(new Input("x", word)), narrow, primitive("wordToWord32#", list(variable("x", word)), narrow)), binding("entry", 1, variable("uN:N.impl", closure))), backend, indexed, entry -> {
            for (int i = 0; i < 3; i++) { assertEquals(4294967295L, entry.execute(4294967295L).asLong()); assertEquals(2147483648L, entry.execute(2147483648L).asLong()); }
        });
    }
    @ParameterizedTest @CsvSource({"ast,false", "bytecode,false", "ast,true", "bytecode,true"})
    void partialApplicationUsesRemainingNotOriginalInputProofs(String backend, boolean indexed) throws Exception {
        var wide = integer("IntRep"); var narrow = integer("Word32Rep");
        var pap = list("app", variable("uN:N.impl", closure), list(literal("int", "7", wide)), list(false), false, false, map("rep", closure));
        check(list(function(list(new Input("ignored", wide), new Input("x", narrow)), narrow, variable("x", narrow)), binding("entry", 1, pap)), backend, indexed, entry -> {
            long before = entries(entry); assertThrows(PolyglotException.class, () -> entry.execute(-1L)); assertThrows(PolyglotException.class, () -> entry.execute(4294967296L)); assertEquals(before, entries(entry));
            for (int i = 0; i < 3; i++) { assertEquals(4294967295L, entry.execute(4294967295L).asLong()); assertEquals(0L, entry.execute(0L).asLong()); }
        });
    }
    @ParameterizedTest @CsvSource({"ast,false", "bytecode,false", "ast,true", "bytecode,true"})
    void nonClosureScalarResultRetainsExactWord32Proof(String backend, boolean indexed) throws Exception {
        var rep = integer("Word32Rep");
        check(list(binding("entry", 0, literal("word32", "4294967295", rep), rep)), backend, indexed, entry -> {
            for (int i = 0; i < 3; i++) assertEquals(4294967295L, entry.execute().asLong());
        });
    }
}
