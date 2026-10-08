// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.nio.file.*;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import thc.runtime.CoreKind;
import thc.runtime.CoreRepresentations;
import static thc.CoreExecutionTestSupport.*;

/** Native aggregate metadata preserves logical shape separately from storage. */
class AggregateLayoutTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final Map<String, Object> boundaries = map(
        "polymorphicTuple", "unboxed-tuple",
        "polymorphicSum", "unboxed-sum", "polymorphicNested", "unboxed-tuple",
        "abstractTupleRep", "unboxed-tuple",
        "abstractFixedTupleIdentity", "unboxed-tuple", "abstractEmptyIdentity", "unboxed-tuple",
        "abstractSumIdentity", "unboxed-sum", "familyTupleIdentity", "unboxed-tuple",
        "abstractSumRep", "unboxed-sum", "abstractComponentIdentity", "unboxed-tuple");
    private Map<String, Object> module(String stage) throws Exception {
        var module = CoreCbdFixtures.read(root.resolve("build/aggregate-layout/" + stage + "-core/AggregateLayoutAudit.cbd"));
        assertEquals("9.14.1", module.get("ghc"));
        assertEquals(stage.equals("pre") ? "optimized-Core-before-Tidy" : "optimized-Core-after-Tidy-before-CorePrep", module.get("boundary"));
        return module;
    }
    private String request(String stage, String entry, String backend) {
        return CoreModules.request(list(root.resolve("build/aggregate-layout/" + stage + "-core/AggregateLayoutAudit.cbd").toString()), "main:AggregateLayoutAudit." + entry, true, false, backend);
    }
    private List<?> expression(Map<?, ?> module, String name) {
        return (List<?>) ((List<?>) module.get("bindings")).stream().map(value -> (Map<?, ?>) value)
            .filter(value -> ("main:AggregateLayoutAudit." + name).equals(value.get("id")))
            .findFirst().orElseThrow().get("expr");
    }
    @Test void unknownBoxedLevityKeepsItsPhysicalPointerInsideTuples() throws Exception {
        for (String stage : list("pre", "post")) {
            var module = module(stage);
            for (String name : list("levityPolymorphic", "boxedTupleThrough", "boxedThrough")) {
                var expression = expression(module, name);
                var proof = (Map<?, ?>) ((Map<?, ?>) expression.get(3)).get("resultRep");
                assertEquals(name.equals("boxedThrough") ? list("BoxedRep Nothing") : list("BoxedRep Nothing", "IntRep"), proof.get("primReps"));
                var pointer = name.equals("boxedThrough") ? proof : (Map<?, ?>) ((List<?>) proof.get("components")).getFirst();
                assertEquals("object", pointer.get("kind"));
                assertEquals(false, pointer.get("evaluated"), "A known pointer is not a WHNF certificate");
            }
        }
    }
    @Test void strictLoadingRejectsUnresolvedRuntimeRepsAndAbstractLogicalComponents() throws Exception {
        for (String stage : list("pre", "post")) {
            var module = module(stage);
            // Zero-width primitive aliases are scalar State/Proxy, not empty tuples.
            // Recursive newtypes retain a scalar pointer without inventing a layout.
            for (String name : list("stateAliasIdentity", "proxyIdentity", "recursiveNewtypeIdentity")) {
                var expression = expression(module, name);
                for (var proof : list(object(((List<?>) expression.get(1)).getFirst()).get("rep"), object(expression.get(3)).get("resultRep"))) {
                    var parsed = CoreRepresentations.parse(object(proof));
                    boolean recursive = name.equals("recursiveNewtypeIdentity");
                    assertEquals(recursive ? CoreKind.OBJECT : CoreKind.VOID, parsed.getKind(), stage + "/" + name);
                    assertEquals(recursive ? list("BoxedRep (Just Lifted)") : list(), parsed.getPrimReps());
                    assertEquals(!recursive, parsed.getEvaluated());
                    assertFalse(parsed.isTuple() || parsed.isSum());
                }
            }
            for (String backend : list("ast", "bytecode")) try (var context = Main.executionContext(false)) {
                for (var boundary : boundaries.entrySet()) {
                    var error = assertThrows(PolyglotException.class, () -> context.eval("thc", request(stage, boundary.getKey(), backend)));
                    assertTrue(Objects.toString(error.getMessage(), "").contains("Unsupported Core aggregate representation: " + boundary.getValue()),
                        stage + "/" + backend + "/" + boundary.getKey() + ": " + error.getMessage());
                }
                // Recursive scalar newtypes retain object evidence and terminate unwrapping.
                context.eval("thc", request(stage, "recursiveNewtypeIdentity", backend));
                for (String entry : list("stateAliasIdentity", "proxyIdentity")) context.eval("thc", request(stage, entry, backend));
            }
        }
    }
    @Test void retainedNestedAndAliasSignaturesAcceptLogicalHostValues() throws Exception {
        for (String stage : list("pre", "post")) {
            var module = module(stage);
            for (String backend : list("ast", "bytecode")) try (var context = Context.newBuilder("thc").allowHostAccess(HostAccess.ALL).build()) {
                for (String name : list("lazyIdentity", "levityPolymorphic", "boxedTupleThrough"))
                    context.eval("thc", request(stage, name, backend));
                var nested = context.eval("thc", request(stage, "nestedIdentity", backend));
                var value = nested.execute((Object) new Object[]{new Object[0], null, new Object[]{-37L}, new Object[]{1L, 91L}});
                assertEquals(4, value.getArraySize()); assertEquals(0, value.getArrayElement(0).getArraySize());
                assertTrue(value.getArrayElement(1).isNull());
                assertEquals(-37L, value.getArrayElement(2).getArrayElement(0).asLong());
                assertEquals(1L, value.getArrayElement(3).getArrayElement(0).asLong());
                assertEquals(91L, value.getArrayElement(3).getArrayElement(1).asLong());
                for (String name : list("tupleAliasIdentity", "nestedAliasIdentity")) {
                    var alias = context.eval("thc", request(stage, name, backend));
                    var answer = alias.execute((Object) new Object[]{new Object[0], Long.MIN_VALUE});
                    assertEquals(2, answer.getArraySize()); assertEquals(0, answer.getArrayElement(0).getArraySize());
                    assertEquals(Long.MIN_VALUE, answer.getArrayElement(1).asLong());
                    assertThrows(PolyglotException.class, () -> alias.execute((Object) new Object[]{Long.MIN_VALUE}));
                }
                var empty = context.eval("thc", request(stage, "emptyAliasIdentity", backend));
                assertEquals(0, empty.execute((Object) new Object[0]).getArraySize());
                var sum = context.eval("thc", request(stage, "sumAliasIdentity", backend));
                var token = sum.execute((Object) new Object[]{1L, null});
                assertEquals(1L, token.getArrayElement(0).asLong()); assertTrue(token.getArrayElement(1).isNull());
                var alternatives = context.eval("thc", request(stage, "alternativesIdentity", backend));
                var floating = alternatives.execute((Object) new Object[]{4L, new Object[]{-0.0f, Math.PI, Long.MAX_VALUE}});
                assertEquals(4L, floating.getArrayElement(0).asLong());
                var payload = floating.getArrayElement(1);
                assertEquals(3, payload.getArraySize());
                assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits(payload.getArrayElement(0).asFloat()));
                assertEquals(Math.PI, payload.getArrayElement(1).asDouble()); assertEquals(Long.MAX_VALUE, payload.getArrayElement(2).asLong());
                assertThrows(PolyglotException.class, () -> alternatives.execute((Object) new Object[]{0L, null}));
                assertThrows(PolyglotException.class, () -> nested.execute(0L));
                for (String name : list("boxedThroughUse", "boxedTupleThroughUse", "boxedTupleUnliftedThroughUse")) {
                    var through = context.eval("thc", request(stage, name, backend));
                    for (long input : new long[]{Long.MIN_VALUE, 0L, Long.MAX_VALUE}) assertEquals(input, through.execute(input).asLong());
                }
            }
        }
    }
    @Test void boxedTuplesAndUnliftedBoxedProductsRemainObjectsWithLazyPayloads() throws Exception {
        for (String stage : list("pre", "post")) {
            var module = module(stage);
            // Boxed tuple spelling and unlifted boxed products still denote one object.
            for (String name : list("boxedPairIdentity", "boxedUnitIdentity", "boxedSoloIdentity", "unliftedProductIdentity")) {
                var expression = expression(module, name);
                for (var proof : list(object(((List<?>) expression.get(1)).getFirst()).get("rep"), object(expression.get(3)).get("resultRep"))) {
                    var parsed = CoreRepresentations.parse(object(proof));
                    assertEquals(CoreKind.DATA, parsed.getKind(), stage + "/" + name);
                    assertEquals(list(name.equals("unliftedProductIdentity") ? "BoxedRep (Just Unlifted)" : "BoxedRep (Just Lifted)"), parsed.getPrimReps());
                    assertEquals(name.equals("unliftedProductIdentity"), parsed.getEvaluated());
                    assertFalse(parsed.isTuple() || parsed.isSum());
                }
            }
            for (String backend : list("ast", "bytecode")) try (var context = Main.executionContext(false)) {
                for (String entry : list("boxedPairIdentity", "boxedUnitIdentity", "boxedSoloIdentity", "unliftedProductIdentity"))
                    context.eval("thc", request(stage, entry, backend));
                for (String entry : list("boxedLazyUse", "unliftedLazyUse")) {
                    var observer = context.eval("thc", request(stage, entry, backend));
                    for (long input : new long[]{Long.MIN_VALUE, -4097L, 0L, 4097L, Long.MAX_VALUE})
                        assertEquals(input, observer.execute(input).asLong(), stage + "/" + backend + "/" + entry);
                }
            }
        }
    }
}
