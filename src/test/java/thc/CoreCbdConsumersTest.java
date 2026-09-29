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

/** Explicit CBD consumers preserve interface ownership and declaration order. */
@SuppressWarnings("unchecked")
class CoreCbdConsumersTest {
    @TempDir Path directory;
    private byte[] resource(String name) throws Exception {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/core/" + name))) { return input.readAllBytes(); }
    }
    private String digest(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private Path file(String name) throws Exception {
        return CoreCbdFixtures.write(directory.resolve(name.replace(".json", ".cbd")),
            document(new String(resource(name), java.nio.charset.StandardCharsets.UTF_8)));
    }
    private Path support() throws Exception {
        var module = CoreCbdFixtures.module(directory.resolve("package.cbd"),
            document(new String(resource("lazy-json-package.json"), java.nio.charset.StandardCharsets.UTF_8)));
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
        assertEquals("52b23334d8e304332840f20bccf03ba11d1509f1168a2e0b71b1d91cd9b07706", digest(resource("package-consumer.json")));
        assertEquals("1d1fdc9ff95b601846ac0154800adeba1e9594cdd1b94a11c258f82d2e5defcf", digest(resource("interface-closure.json")));
        {
            var manifest = support(); var keys = new ArrayList<>(consumers);
            for (var order : List.of(keys, keys.reversed())) {
                var paths = List.of(order.get(0), "@" + manifest, order.get(1));
                String serialized = request(paths); var input = document(serialized);
                assertFalse(input.containsKey("modules")); assertTrue(input.containsKey("moduleFiles"));
                var selected = modules(serialized); var expected = new ArrayList<>(List.of("synthetic"));
                for (String path : order) expected.add(path.endsWith("package-consumer.cbd") ? "main" : "dependency-closure");
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
                        assertEquals(1L, count(value, "initializedBindingCount")); // The explicit entry is admitted first.
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
