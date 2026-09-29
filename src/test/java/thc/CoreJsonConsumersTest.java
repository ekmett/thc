// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreFormatTestSupport.*;

/** Plain JSON consumers preserve interface ownership and declaration order. */
@SuppressWarnings("unchecked")
class CoreJsonConsumersTest {
    @TempDir Path directory;
    private byte[] resource(String name) throws Exception {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/core/" + name))) { return input.readAllBytes(); }
    }
    private String digest(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private Path file(String name) throws Exception { var path = directory.resolve(name); Files.write(path, resource(name)); return path; }
    private Path support() throws Exception {
        var json = file("lazy-json-package.json");
        var module = map("name", "LazyJson", "path", json.getFileName().toString(), "boundary", "optimized-Core-after-Tidy-before-CorePrep",
            "sha256", digest(Files.readAllBytes(json)));
        var path = directory.resolve("packages.json");
        Files.writeString(path, Json.stringify(map("format", "thc-core-packages", "schema", 1, "ghc", "9.14.1",
            "units", List.of(map("id", "synthetic", "depends", List.of(), "modules", List.of(module))))));
        return path;
    }
    private List<String> consumers() throws Exception {
        return List.of(file("package-consumer.json").toString(), file("interface-closure.json").toString());
    }
    private String request(List<String> paths) {
        return CoreFormatTestSupport.request(paths, "main:Main.entry", Main.defaultBackend(), false, null, false);
    }
    private List<Map<String, Object>> modules(String request) {
        var result = new ArrayList<Map<String, Object>>(); visit(document(request), result::add); return result;
    }
    private long count(Value value, String key) { return ((Number) document(value.getMember("diagnostics").asString()).get(key)).longValue(); }
    @Test void plainConsumersKeepInterfaceOwnershipAndOrder() throws Exception {
        var consumers = consumers();
        assertEquals("d2a141b1f35127040ee3358a3332444c4f92b866de3a03854e085b6b979e4b7a", digest(resource("package-consumer.json")));
        assertEquals("c7fb8e4a188e3c62d505225f4769a96d7dac4375882d0d23c9e26ee6cc8dec78", digest(resource("interface-closure.json")));
        {
            var manifest = support(); var keys = new ArrayList<>(consumers);
            for (var order : List.of(keys, keys.reversed())) {
                var paths = List.of(order.get(0), "@" + manifest, order.get(1));
                String serialized = request(paths); var input = document(serialized);
                assertTrue(input.containsKey("modules"));
                var selected = modules(serialized); var expected = new ArrayList<>(List.of("synthetic"));
                for (String path : order) expected.add(path.endsWith("package-consumer.json") ? "main" : "dependency-closure");
                assertEquals(expected, selected.stream().map(it -> it.get("unit")).toList());
                var closures = selected.stream().filter(it -> "dependency-closure".equals(it.get("unit"))).toList();
                assertEquals(1, closures.size()); var closure = closures.getFirst();
                assertEquals("actual-interface-unfoldings", closure.get("boundary"));
                var bindings = (List<Map<String, Object>>) closure.get("bindings");
                assertEquals(1, bindings.size()); assertEquals("dependency:Hidden.cold", bindings.getFirst().get("id"));
                assertEquals(List.of("synthetic:LazyJson"), closure.get("providedModules"));
                CoreModules.merge(selected); // exact provided owner and original binding IDs remain admissible
                for (String backend : List.of("ast", "bytecode")) for (boolean async : new boolean[]{false, true})
                    try (var context = Main.executionContext(false)) {
                        var value = Main.loadEntry(context, paths, "main:Main.entry", true, backend, false, null, async, false);
                        assertEquals(2L, count(value, "initializedBindingCount")); // Both reachable ordinary JSON bindings.
                        assertEquals(10L, value.execute(7L).asLong(), order + "/" + "/" + backend + "/" + async);
                        assertEquals(2L, count(value, "initializedBindingCount"));
                    }
            }
        }
    }
    @Test void consumersRemainSubjectToDuplicateDefinitionAndExactProvidedOwnerChecks() throws Exception {
        var consumers = consumers(); var paths = new ArrayList<>(consumers); paths.add("@" + support());
        var selected = modules(request(paths)); var closure = selected.getLast();
        var duplicate = new ArrayList<>(selected); duplicate.add(closure);
        assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(duplicate));
        var wrongOwner = new ArrayList<>(selected.subList(0, selected.size() - 1));
        wrongOwner.add(with(closure, "providedModules", List.of("forged:LazyJson")));
        assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(wrongOwner));
        var wrongUnit = new ArrayList<>(selected.subList(0, selected.size() - 1)); wrongUnit.add(with(closure, "unit", "forged"));
        assertThrows(IllegalArgumentException.class, () -> CoreModules.merge(wrongUnit));
    }
}
