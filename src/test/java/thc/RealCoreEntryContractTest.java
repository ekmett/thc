// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreBackendTestSupport.*;

/** Genuine GHC worker CBV marks must survive export, execution and compilation. */
class RealCoreEntryContractTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private Map<String, Object> exported(String name) throws Exception { return CoreCbdFixtures.read(root.resolve("build/core/" + name + ".cbd")); }
    private List<Map<String, Object>> definitions(Object value) {
        var result = new ArrayList<Map<String, Object>>();
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("expr") && map.containsKey("entryStrict")) result.add(object(map));
            for (var child : map.values()) result.addAll(definitions(child));
        } else if (value instanceof List<?> list) for (var child : list) result.addAll(definitions(child));
        return result;
    }
    private Map<String, Object> named(Map<String, Object> module, String name) {
        var matches = definitions(module).stream().filter(d -> ("main:" + module.get("module") + "." + name).equals(d.get("id"))).toList(); assertEquals(1, matches.size()); return matches.getFirst();
    }
    private long count(Value function, String name) { return ((Number) object(Json.parse(function.getMember("diagnostics").asString())).get(name)).longValue(); }
    private void checkEntry(Map<String, Object> module, String entry, LongUnaryOperator expected) {
        for (String backend : list("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            var function = context.eval("thc", CoreModules.request(list(root.resolve("build/core/" + module.get("module") + ".cbd").toString()), "main:" + module.get("module") + "." + entry, true, false, backend));
            LongConsumer check = input -> assertEquals(expected.applyAsLong(input), function.execute(input).asLong(), backend + " " + entry + "(" + input + ")");
            // Keep nonpositive case alternatives cold until compilation has succeeded.
            for (int i = 0; i < 30; i++) check.accept(i + 1L);
            assertTrue(function.invokeMember("compile").asBoolean(), backend + " " + entry + " compiled");
            long compiled = count(function, "compiledEntries"); check.accept(7L);
            assertTrue(count(function, "compiledEntries") > compiled, backend + " " + entry + " ran compiled code");
            for (long input : new long[]{0L, -1L, 3_000_000_017L, -7_000_000_003L, Long.MIN_VALUE, Long.MAX_VALUE}) check.accept(input);
            assertTrue(function.invokeMember("compile").asBoolean(), backend + " " + entry + " recompiled");
            long recompiled = count(function, "compiledEntries"); check.accept(4_294_967_311L);
            assertTrue(count(function, "compiledEntries") > recompiled, backend + " " + entry + " reran compiled code");
            check.accept(Long.MIN_VALUE + 13); check.accept(Long.MAX_VALUE - 11);
            assertEquals(0L, count(function, "blackholes"), backend); assertEquals(0L, count(function, "unsupportedTraps"), backend);
        }
    }
    @Test void genuineWorkerContractPreservesFullWidthResultsAndColdBranches() throws Exception {
        var module = exported("CBVAudit");
        var marked = definitions(module).stream().filter(d -> ((List<?>) d.get("entryStrict")).contains(true)).toList();
        assertTrue(marked.stream().anyMatch(d -> d.get("name").toString().contains("walk") && "ghc-tidy-proposal".equals(d.get("entryStrictSource"))));
        assertFalse(((List<?>) named(module, "plainStrict").get("entryStrict")).contains(true), "Strict demand alone must not add a contract");
        checkEntry(module, "workerEntry", n -> n <= 0 ? n + 7 : n - 1);
    }
    @Test void genuineWorkerRetainsTheCoercionSlotBeforeItsMarkedBoxedArgument() throws Exception {
        var module = exported("CBVCoercionAudit");
        var reachable = CoreModules.reachable(module, "main:CBVCoercionAudit.witnessed", true);
        var workers = definitions(reachable).stream().filter(d -> ((List<?>) d.get("entryStrict")).contains(true)).toList();
        assertEquals(1, workers.size(), "The actual witness wrapper reaches exactly one marked worker");
        var worker = workers.getFirst(); var parameters = objects(((List<?>) worker.get("expr")).get(1));
        assertEquals(list(false, false, true), worker.get("entryStrict")); assertEquals(true, parameters.get(0).get("coercion")); assertEquals(true, parameters.get(2).get("lifted"));
        assertEquals(false, object(parameters.get(2).get("rep")).get("evaluated"), "The CBV obligation must not rewrite GHC's pre-entry WHNF fact");
        checkEntry(module, "coercionEntry", n -> n + 7);
    }
}
