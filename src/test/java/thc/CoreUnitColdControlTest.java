// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.graalvm.polyglot.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

/** Small independent protocol model, not native-export or compilation evidence. */
class CoreUnitColdControlTest {
    @TempDir Path directory;
    @AfterEach void releaseIdleMappings() { CoreFileMappings.shared.evictIdleBelow(directory); }
    private final String boundary = "optimized-Core-after-Tidy-before-CorePrep";
    private final Map<String, Object> state = map("kind", "void", "primReps", List.of(), "evaluated", true);
    private final Map<String, Object> integer = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private final Map<String, Object> closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private final Map<String, Object> tag = map("kind", "object", "primReps", list("BoxedRep (Just Unlifted)"), "evaluated", true);
    private Map<String, Object> tuple(Map<String, Object> field) { return map("kind", "unknown", "aggregate", "unboxed-tuple", "primReps", field.get("primReps"), "components", list(state, field), "evaluated", true); }
    private Map<String, Object> binder(String id, Map<String, Object> proof) {
        return map("id", id, "name", id, "type", proof.equals(state) ? "State# RealWorld" : proof.equals(integer) ? "Int#" : "PromptTag# Int#", "lifted", false, "coercion", false, "rep", proof);
    }
    private List<Object> variable(String id, Map<String, Object> proof) { return list("var", id, map("rep", proof)); }
    private List<Object> literal(int value) { return list("lit", "int", Integer.toString(value), map("rep", integer)); }
    private List<Object> application(List<Object> head, List<?> args, List<Boolean> flags, Map<String, Object> proof) { return list("app", head, args, flags, false, false, map("rep", proof)); }
    private List<Object> pair(List<Object> stateValue, List<Object> value) { return list("app", list("con", "uB:B.Pair", 2), list(stateValue, value), list(false, false), true, true, map("rep", tuple(integer))); }
    private List<Object> unpack(List<Object> scrutinee, Map<String, Object> field, String name, List<Object> body) {
        return list("case", scrutinee, name + "-pair", list(list("data", "uB:B.Pair", list(name + "-state", name), body,
            map("binders", list(binder(name + "-state", state), binder(name, field))))), map("rep", integer, "binder", binder(name + "-pair", tuple(field))));
    }
    private List<Object> promptBody() {
        var add = application(list("prim", "+#"), list(variable("x", integer), literal(11)), List.of(false, false), integer);
        var action = list("lam", list(binder("action-state", state)), pair(variable("action-state", state), add), map("rep", closure, "resultRep", tuple(integer)));
        var prompt = application(list("prim", "prompt#"), list(variable("tag", tag), action, variable("tag-state", state)), List.of(false, true, false), tuple(integer));
        var fresh = application(list("prim", "newPromptTag#"), list(list("void", map("rep", state))), List.of(false), tuple(tag));
        return unpack(fresh, tag, "tag", unpack(prompt, integer, "answer", variable("answer", integer)));
    }
    private Map<String, Object> function(String id, List<Object> body) {
        return map("id", id, "name", "entry", "type", "Int# -> Int#", "lifted", true, "arity", 1, "rep", closure,
            "expr", list("lam", list(binder("x", integer)), body, map("rep", closure, "resultRep", integer)));
    }
    private byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    /** Exact unit-pair offsets and summaries, with a separate exhaustive-loader model control. */
    private Map<String, Object> unit(String name, List<Object> body, boolean control) throws Exception {
        var id = "u" + name + ":" + name + ".entry";
        var constructors = control ? list(map("id", "uB:B.Pair", "name", "(#,#)", "kind", "unboxed-tuple", "arity", 2, "tag", 1)) : List.of();
        var metadata = map("schema", 1, "ghc", "9.14.1", "unit", "u" + name, "module", name, "boundary", boundary, "constructors", constructors);
        var serialized = Json.stringify(metadata); var prefix = serialized.substring(0, serialized.length() - 1) + ",\"bindings\":";
        var encoded = Json.stringify(list(function(id, body))); var original = bytes(prefix + encoded + "}");
        var admitted = bytes(Json.stringify(metadata)); var output = new java.io.ByteArrayOutputStream();
        output.writeBytes(original); output.write(10); output.writeBytes(admitted); var bytes = output.toByteArray();
        var json = directory.resolve(name + ".jsons"); var symbols = directory.resolve(name + ".symbols");
        var rows = bytes(id + " " + (bytes(prefix).length + 1) + "\n");
        Files.write(json, bytes); Files.write(symbols, rows); Files.write(directory.resolve(name + ".json"), original);
        return map("id", "u" + name, "depends", List.of(), "json", map("path", json.toString(), "sha256", hash(bytes)),
            "symbols", map("path", symbols.toString(), "sha256", hash(rows)), "modules", list(map("name", name, "path", name + ".json",
                "sha256", hash(original), "boundary", boundary, "start", 0, "end", original.length, "bindingsStart", bytes(prefix).length,
                "bindingsEnd", bytes(prefix).length + bytes(encoded).length, "metadataStart", original.length + 1, "metadataEnd", bytes.length,
                "containsDelimitedControl", control, "registrationObligations", false, "mainAlias", false, "packageScalarDeclarations", false)));
    }
    private Path fixture(boolean callControl) throws Exception {
        var call = application(list("var", "uB:B.entry"), list(variable("x", integer)), List.of(false), integer);
        // Retain ordinary caller suffix: x=2 -> prompt returns13 -> caller returns18.
        var body = callControl ? application(list("prim", "+#"), list(call, literal(5)), List.of(false, false), integer) : literal(7);
        var units = list(unit("A", body, false), unit("B", promptBody(), true));
        return Files.writeString(directory.resolve("packages.json"), Json.stringify(map("format", "thc-core-packages", "schema", 1, "ghc", "9.14.1", "units", units)));
    }
    private String request(String source, String entry, String backend, boolean async) { return CoreFormatTestSupport.request(List.of(source), entry, backend, false, async, false); }
    private long count(Value entry, String name) { return ((Number) ((Map<?, ?>) Json.parse(entry.getMember("diagnostics").asString())).get(name)).longValue(); }
    @Test void exhaustiveLoaderModelPreservesTheExistingPromptPolicy() throws Exception {
        fixture(false);
        for (var backend : List.of("ast", "bytecode")) for (boolean async : new boolean[]{false, true}) try (var context = Context.create("thc")) {
            var source = request(directory.resolve("B.json").toString(), "uB:B.entry", backend, async);
            assertEquals(13L, context.eval("thc", source).execute(2).asLong(), backend + "/" + async);
        }
    }
    @Test void unrelatedControlSummaryDoesNotOpenOrRejectTheOrdinaryEntry() throws Exception {
        var manifest = fixture(false);
        // Missing files prove coldness independently of decoded-value counters.
        Files.delete(directory.resolve("B.jsons")); Files.delete(directory.resolve("B.symbols"));
        for (var backend : List.of("ast", "bytecode")) for (boolean async : new boolean[]{false, true}) try (var context = Context.create("thc")) {
            var entry = context.eval("thc", request("@" + manifest, "uA:A.entry", backend, async));
            assertEquals(7L, entry.execute(2).asLong(), backend + "/" + async);
            assertEquals(1L, count(entry, "coreUnitSourceOpens")); assertEquals(1L, count(entry, "coreUnitDecodedModules")); assertEquals(1L, count(entry, "coreUnitDecodedBindings"));
        }
    }
    @Test void firstCrossModulePromptDemandUsesExistingPolicyAndPreservesTheCallerSuffix() throws Exception {
        var manifest = fixture(true);
        for (var backend : List.of("ast", "bytecode")) for (boolean async : new boolean[]{false, true}) try (var context = Context.create("thc")) {
            var entry = context.eval("thc", request("@" + manifest, "uA:A.entry", backend, async));
            assertEquals(1L, count(entry, "coreUnitSourceOpens")); assertEquals(1L, count(entry, "coreUnitDecodedBindings"));
            assertEquals(18L, entry.execute(2).asLong(), backend + "/" + async + " first demand"); assertEquals(2L, count(entry, "coreUnitDecodedBindings"));
            long reads = count(entry, "coreUnitSourceByteReads"); assertEquals(19L, entry.execute(3).asLong()); assertEquals(reads, count(entry, "coreUnitSourceByteReads"));
        }
    }
    @Test void directAndCrossModulePromptRejectAnActiveTransactionBeforeTheAction() throws Exception {
        var manifest = fixture(true);
        for (var backend : List.of("ast", "bytecode")) for (var selected : List.of("uA:A.entry", "uB:B.entry")) try (var context = Context.create("thc")) {
            var entry = context.eval("thc", request("@" + manifest, selected, backend, false)); context.enter();
            try {
                var stm = Language.currentState(null).stm; boolean[] completed = {false};
                var failure = assertThrows(PolyglotException.class, () -> stm.atomically(null,
                    () -> { throw new IllegalStateException("unexpected nested transaction"); }, false,
                    () -> { entry.execute(2); completed[0] = true; return thc.runtime.Unit.INSTANCE; }));
                assertTrue(Objects.toString(failure.getMessage(), "").contains("STM transaction frames do not support explicit delimited capture"), failure.getMessage());
                assertFalse(completed[0]); assertFalse(stm.hasTransaction());
            } finally { context.leave(); }
            // A failed transaction must not poison the prepared nontransactional definition.
            assertEquals(selected.equals("uA:A.entry") ? 18L : 13L, entry.execute(2).asLong());
        }
    }
}
