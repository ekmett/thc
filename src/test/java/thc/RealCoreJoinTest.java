// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.util.*;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static thc.CoreExecutionTestSupport.*;

/** Consumes source-core's pre-Tidy RepresentationAudit CBD and writes only private
 * driver CBDs. Real erased-type joins retain boxed demand and returned-function
 * suffix semantics in both backends; post-Tidy equivalence is outside this suite. */
class RealCoreJoinTest {
    @TempDir Path temporary;
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private Map<String, Object> exported() throws Exception { return CoreCbdFixtures.read(root.resolve("build/source-core/RepresentationAudit.cbd")); }
    private long count(Value function, String key) { return ((Number) object(Json.parse(function.getMember("diagnostics").asString())).get(key)).longValue(); }
    private List<Object> variable(String id) { return list("var", id, map()); }
    private List<Object> integer(long value) { return list("lit", "int", Long.toString(value), map()); }
    private List<Object> apply(List<Object> fn, List<List<Object>> args, List<Boolean> lifted) { return list("app", fn, args, lifted, false, false, map()); }
    private String id(List<Map<String, Object>> definitions, String name) {
        var matches = definitions.stream().filter(item -> ("main:RepresentationAudit." + name).equals(item.get("id"))).toList();
        assertEquals(1, matches.size()); return (String) matches.getFirst().get("id");
    }
    private void checkLoop(Value function, String backend, long n) { assertEquals(n <= 0 ? 0L : n * (n + 1) / 2, function.execute(n).asLong(), backend + " joinLoop(" + n + ")"); }
    @Test void exportedRecursiveJoinIsALocalLoopBeforeAndAfterCompilation() throws Exception {
        var module = exported();
        for (String backend : list("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            var function = context.eval("thc", CoreModules.request(list(root.resolve("build/source-core/RepresentationAudit.cbd").toString()), "main:RepresentationAudit.joinLoop", true, false, backend));
            for (int i = 0; i < 8; i++) checkLoop(function, backend, i);
            checkLoop(function, backend, 100_000);
            assertTrue(function.invokeMember("compile").asBoolean()); long before = count(function, "compiledEntries");
            for (long n : new long[]{-1, 0, 1, 100_000}) checkLoop(function, backend, n);
            assertTrue(count(function, "compiledEntries") > before);
            assertTrue(count(function, "localJoinTransfers") >= 100_000L, "Must execute the exported local join");
            assertEquals(0L, count(function, "tailBounces"), "Local joins do not use function tail transfers");
            assertEquals(0L, count(function, "trampolineIterations"));
        }
    }
    private long emptyTupleSwapExpected(long input) {
        long left = 11, right = 29;
        for (long remaining = input & 31L; remaining > 0; remaining--) {
            long saved = left; left = right; right = saved;
        }
        return left - right;
    }
    /** Uses source-core's existing RepresentationAudit CBD; creates no products.
     * An empty-tuple parameter between swapped scalars must not corrupt recursive
     * join transfers. MAX masks to 31 swaps; the even-depth control masks to 30. */
    @Test void recursiveJoinSwapsAcrossEmptyTupleOnFirstInstalledCall() throws Exception {
        for (String backend : list("ast", "bytecode")) try (var context = Main.executionContext(false)) {
            var function = context.eval("thc", CoreModules.request(list(root.resolve("build/source-core/RepresentationAudit.cbd").toString()),
                "main:RepresentationAudit.emptyTupleSwap", true, false, backend));
            assertEquals(18L, emptyTupleSwapExpected(Long.MAX_VALUE));
            assertEquals(-18L, emptyTupleSwapExpected(Long.MAX_VALUE - 1));
            assertEquals(emptyTupleSwapExpected(Long.MAX_VALUE), function.execute(Long.MAX_VALUE).asLong(), backend + " interpreted odd depth");
            assertTrue(function.invokeMember("compile").asBoolean(), backend);
            long before = count(function, "compiledEntries");
            assertEquals(emptyTupleSwapExpected(Long.MAX_VALUE), function.execute(Long.MAX_VALUE).asLong(), backend + " first installed odd depth");
            assertTrue(count(function, "compiledEntries") > before, backend + " first installed call enters guest code");
            var installed = object(object(Json.parse(function.getMember("diagnostics").asString())).get("explicitCompilation"));
            assertEquals(true, installed.get("sameTargets"), backend);
            assertEquals(true, installed.get("validLastTier"), backend);
            assertEquals(emptyTupleSwapExpected(Long.MAX_VALUE - 1), function.execute(Long.MAX_VALUE - 1).asLong(), backend + " even depth");
            assertTrue(count(function, "localJoinTransfers") > 0, backend);
            assertEquals(0L, count(function, "unsupportedTraps"), backend);
        }
    }
    private List<Map<String, Object>> joinContracts(Object value) {
        var result = new ArrayList<Map<String, Object>>();
        if (value instanceof Map<?, ?> fields) {
            if (fields.containsKey("joinValueArity")) result.add(object(fields));
            for (var child : fields.values()) result.addAll(joinContracts(child));
        } else if (value instanceof List<?> values) for (var child : values) result.addAll(joinContracts(child));
        return result;
    }
    private void checkBoxedContract(Map<String, Object> owner, String name) {
        int prefix = name.equals("polyJoin") ? 2 : 1;
        var marks = name.equals("polyJoin") ? list(false, true) : list(false, false);
        var contract = joinContracts(owner).stream().filter(join ->
            ((Number) join.get("joinValueArity")).intValue() == prefix && marks.equals(join.get("entryStrict")))
            .findFirst().orElseThrow(() -> new AssertionError(name + " lost its erased-value entry contract"));
        var formals = objects(((List<?>) contract.get("expr")).get(1));
        var boxed = formals.get(1);
        assertEquals(true, boxed.get("lifted"), name + " uses a boxed argument");
        assertEquals(false, object(boxed.get("rep")).get("evaluated"), name + " entry obligations do not manufacture WHNF");
        if (name.equals("polyJoin")) assertEquals("ghc-tidy-proposal", contract.get("entryStrictSource"));
    }
    private List<Object> invocation(String name, String id, List<Object> value, String box, List<Object> empty) {
        var args = new ArrayList<>(list(variable("input"), value)); var lifted = new ArrayList<>(list(false, true));
        if (name.equals("functionJoin")) {
            var suffix = apply(list("prim", "+#", map()), list(variable("input"), integer(5)), list(false, false));
            args.add(apply(list("con", box, 2, map()), list(suffix, empty), list(false, true))); lifted.add(true);
        }
        return apply(variable(id), args, lifted);
    }
    private void checkJoin(Value function, String backend, String name, long n) {
        assertEquals(name.equals("polyJoin") ? n + 7 : n + n + 5, function.execute(n).asLong(), backend + " " + name + "(" + n + ")");
    }
    @Test void erasedTypeJoinAndFunctionReturningJoinPreserveTheirValueArity() throws Exception {
        var module = exported(); var bindings = objects(module.get("bindings")); var constructors = objects(module.get("constructors"));
        String end = id(constructors, "End"), box = id(constructors, "Box");
        for (String name : list("polyJoin", "functionJoin")) {
            String id = id(bindings, name);
            checkBoxedContract(bindings.stream().filter(binding -> id.equals(binding.get("id"))).findFirst().orElseThrow(), name);
            List<Object> empty = list("con", end, 0, map());
            var nonempty = apply(list("con", box, 2, map()), list(variable("input"), empty), list(false, true));
            var body = list("case", variable("input"), "choice", list(
                list("lit", list("int", "0"), list(), invocation(name, id, empty, box, empty), map("binders", list())),
                list("default", null, list(), invocation(name, id, nonempty, box, empty), map("binders", list()))), map());
            var lambda = list("lam", list(map("id", "input", "name", "input", "type", "Int#", "lifted", false, "coercion", false, "rep", map("kind", "long", "primReps", list("IntRep"), "evaluated", true))), body, map());
            var driver = map("schema", 1, "ghc", "9.14.1", "module", "Synthetic.RealJoinDriver", "unit", "main", "boundary", "main", "constructors", list(),
                "bindings", list(map("id", "driver", "name", "driver", "type", "Int# -> Int#", "lifted", true, "arity", 1, "expr", lambda)));
            var artifact = CoreCbdFixtures.write(temporary.resolve(name + ".cbd"), driver);
            for (String backend : list("ast", "bytecode")) try (var context = Main.executionContext(false)) {
                var function = context.eval("thc", CoreModules.request(list(root.resolve("build/source-core/RepresentationAudit.cbd").toString(), artifact.toString()), "driver", true, false, backend));
                checkJoin(function, backend, name, 7L);
                assertTrue(function.invokeMember("compile").asBoolean()); long before = count(function, "compiledEntries");
                // Twice MIN_VALUE wraps to zero, so use a wide non-wrapping
                // suffix result to expose first-call truncation in functionJoin.
                checkJoin(function, backend, name, name.equals("functionJoin") ? 3_000_000_000L : Long.MIN_VALUE);
                assertTrue(count(function, "compiledEntries") > before, backend + "/" + name + " first installed call");
                var installed = object(object(Json.parse(function.getMember("diagnostics").asString())).get("explicitCompilation"));
                assertEquals(true, installed.get("sameTargets"), backend + "/" + name);
                assertEquals(true, installed.get("validLastTier"), backend + "/" + name);
                // Cold alternatives retain their results even if profiling invalidates code.
                for (long n : new long[]{0L, 1L, -1L, 3_000_000_000L, Long.MIN_VALUE, Long.MAX_VALUE}) checkJoin(function, backend, name, n);
                assertTrue(count(function, "localJoinTransfers") > 0); assertEquals(0L, count(function, "unsupportedTraps"));
            }
        }
    }
}
