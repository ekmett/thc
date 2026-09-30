// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.util.*;
import java.util.function.LongConsumer;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreBackendTestSupport.*;
import static thc.runtime.CoreCallDemands.CALL_DEMANDS_PROPERTY;

/** Original GHC demand signatures license individual calls without upgrading ordinary function entries. */
class RealCoreCallDemandTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private Map<String, Object> exported() throws Exception { return CoreCbdFixtures.read(root.resolve("build/core/DemandAudit.cbd")); }
    private Map<String, Map<String, Object>> definitions(Map<String, Object> module) {
        var result = new LinkedHashMap<String, Map<String, Object>>();
        for (var binding : objects(module.get("bindings"))) result.put((String) binding.get("id"), binding); return result;
    }
    private List<List<?>> applications(Object value) {
        var result = new ArrayList<List<?>>();
        if (value instanceof Map<?, ?> map) for (var child : map.values()) result.addAll(applications(child));
        else if (value instanceof List<?> list) {
            if (!list.isEmpty() && "app".equals(list.getFirst())) result.add(list);
            for (var child : list) result.addAll(applications(child));
        }
        return result;
    }
    private List<?> call(Map<String, Object> binding, String name) {
        var matches = applications(binding).stream().filter(app -> {
            var head = (List<?>) app.get(1); return !head.isEmpty() && "var".equals(head.getFirst()) && ((String) head.get(1)).endsWith("." + name);
        }).toList();
        assertEquals(1, matches.size()); return matches.getFirst();
    }
    private Map<String, Object> demand(List<?> app) { return object(object(app.get(6)).get("callDemand")); }
    private Object marks(List<?> app) { return demand(app).get("strictArgs"); }
    private long count(Value function, String name) { return ((Number) object(Json.parse(function.getMember("diagnostics").asString())).get(name)).longValue(); }
    private long treeResult(long n) { return n <= 0 ? 0 : n + 7; }
    @Test void exportedCallDemandIsSeparateFromWhnfEntryContractsAndSpeculation() throws Exception {
        var bindings = definitions(exported()); var strict = bindings.get("main:DemandAudit.strictTree");
        assertEquals(list(false), strict.get("entryStrict"));
        var formals = objects(((List<?>) strict.get("expr")).get(1)); assertEquals(1, formals.size());
        assertEquals(false, object(formals.getFirst().get("rep")).get("evaluated"));
        var ordinary = call(bindings.get("main:DemandAudit.ordinaryEntry"), "strictTree");
        assertEquals(1, ((Number) demand(ordinary).get("arity")).intValue()); assertEquals(list(true), marks(ordinary));
        var producer = call(bindings.get("main:DemandAudit.ordinaryEntry"), "makeTree");
        assertEquals(false, producer.get(5), "Caller demand must work for an operand that cannot be speculated");
        assertEquals(list(false), marks(call(bindings.get("main:DemandAudit.lazyBarrier"), "strictTree")));
        assertEquals(list(false), marks(call(bindings.get("main:DemandAudit.absentEntry"), "ignore")));
        var partial = call(bindings.get("main:DemandAudit.polyFunctionEntry"), "strictPair");
        assertEquals(2, ((Number) demand(partial).get("arity")).intValue()); assertEquals(list(false), marks(partial), "The signature's arity must survive an undersaturated application");
    }
    @Test void genuineOrdinaryPolymorphicLazyAndPartialCallsSurviveCompilation() throws Exception {
        var previous = System.getProperty(CALL_DEMANDS_PROPERTY);
        try {
            System.setProperty(CALL_DEMANDS_PROPERTY, "true");
            for (String backend : list("ast", "bytecode")) for (String entry : list("ordinaryEntry", "polyDataEntry", "polyFunctionEntry", "absentEntry", "lazyBarrier", "bottomPAPEntry"))
                try (var context = Main.executionContext(false)) {
                    var function = context.eval("thc", CoreModules.request(list(root.resolve("build/core/DemandAudit.cbd").toString()), "main:DemandAudit." + entry, true, false, backend));
                    LongConsumer check = n -> assertEquals(entry.equals("ordinaryEntry") || entry.equals("lazyBarrier") ? treeResult(n) : 41L,
                        function.execute(n).asLong(), backend + " " + entry + "(" + n + ")");
                    for (int i = 0; i < 12; i++) check.accept(7L);
                    assertTrue(function.invokeMember("compile").asBoolean(), backend + " " + entry + " compiled");
                    long compiled = count(function, "compiledEntries"); check.accept(11L);
                    assertTrue(count(function, "compiledEntries") > compiled, backend + " " + entry + " ran compiled code");
                    for (long n : new long[]{0L, -1L, 3_000_000_017L, -7_000_000_003L, Long.MIN_VALUE, Long.MAX_VALUE}) check.accept(n);
                    assertEquals(0L, count(function, "blackholes"), backend + " " + entry); assertEquals(0L, count(function, "unsupportedTraps"), backend + " " + entry);
                    if (entry.equals("lazyBarrier")) assertTrue(count(function, "thunkEvaluations") > 0, backend + " lazy wrapper prevents the caller from evaluating its operand directly");
                    else assertEquals(0L, count(function, "thunkEvaluations"), backend + " " + entry + " either evaluates a demanded producer directly or leaves it unused");
                }
        } finally { if (previous == null) System.clearProperty(CALL_DEMANDS_PROPERTY); else System.setProperty(CALL_DEMANDS_PROPERTY, previous); }
    }
}
