// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import org.junit.jupiter.api.Test;
import thc.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("unchecked")
class AddressIdentityNativeTest {
    private final Path root = Path.of(System.getProperty("thc.projectRoot"));
    private final Path output = root.resolve("build/addr-identity");
    private List<List<Object>> nodes(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof List<?> list) {
            result.add((List<Object>) list);
            for (Object item : list) result.addAll(nodes(item)); }
        else if (value instanceof Map<?, ?> map)
            for (Object item : map.values()) result.addAll(nodes(item));
        return result;
    }
    private static <T> T single(List<T> values) {
        if (values.size() != 1)
            throw new IllegalArgumentException("Expected one element");
        return values.getFirst();
    }
    private Map<String, Object> json(Path path) throws Exception {
        return (Map<String, Object>) Json.parse(Files.readString(path));
    }

    @Test
    void nativeNonProfilingResultsMatchBothCompiledBackendsAndCoreProofs() throws Exception {
        var expected = Files.readAllLines(output.resolve("oracle.txt")).stream().map(Long::parseLong).toList();
        assertEquals(List.of(1L, 0L, 1L, 1L, 0L), expected);
        for (String stage : List.of("pre", "post")) {
            var audit = json(output.resolve(stage + ".audit.json"));
            assertEquals(true, audit.get("accepted"), stage);
            assertEquals(List.of(), audit.get("missingGlobals"), stage);
            assertEquals(List.of(), audit.get("issues"), stage);
            var exported = CoreCbdFixtures.read(output.resolve(stage + "-core/AddressIdentityAudit.cbd"));
            var module = CoreModules.reachable(CoreModules.merge(List.of(exported)), "main:AddressIdentityAudit.probe");
            var evidence = new ArrayCoreEvidence(module, "main:AddressIdentityAudit.probe");
            var expression = evidence.getRoot().get("expr");
            assertEquals(2, evidence.guestLambdas(expression).size(), stage + " exported lambda inventory");
            // eb6aa293 lowers this exact immediate State# application in-frame.
            // Keep its source proof separate from the executed guest-root count.
            var lowered = evidence.loweredStateLambdas(expression);
            assertEquals(1, lowered.size(), stage + " lowered root inventory");
            assertSame(expression, single(lowered), stage + " public probe remains the root");
            long expectedEntries = lowered.size();
            var dummy = single(
                evidence.getBindings().stream().filter(binding -> ((String) binding.get("id")).matches("main:AddressIdentityAudit\\.bottomDummy(?:_[A-Za-z0-9]+)?")).toList());
            var dummyExpr = (List<?>) dummy.get("expr");
            assertEquals(List.of("var", dummy.get("id")), dummyExpr.subList(0, Math.min(2, dummyExpr.size())),
                stage + " original nonterminating dummy");
            var literal = single(evidence.getBindings()
                    .stream()
                    .filter(binding -> {
                        var expr = (List<?>) binding.get("expr");
                        return expr.subList(0, Math.min(3, expr.size())).equals(List.of("lit", "string-bytes", "41"));
                    })
                    .toList());
            assertEquals(3, evidence.getBindings().size(), stage + " no additional helper closures");
            assertEquals(Set.of(dummy.get("id"), literal.get("id")),
                new HashSet<>(evidence.globalReferences(expression)),
                stage + " original lazy dummy and static address");
            var calls = nodes(module.get("bindings"))
                            .stream()
                            .filter(node
                                -> !node.isEmpty() && "app".equals(node.getFirst()) && node.size() > 1
                                    && node.get(1) instanceof List<?> callee && !callee.isEmpty()
                                    && "prim".equals(callee.getFirst()))
                            .toList();
            assertEquals(Set.of("getCurrentCCS#", "eqAddr#", "neAddr#"),
                new HashSet<>(calls.stream().map(call -> ((List<?>) call.get(1)).get(1)).toList()),
                stage + " retained primitives");
            var current =
                single(calls.stream().filter(call -> "getCurrentCCS#".equals(((List<?>) call.get(1)).get(1))).toList());
            assertEquals(List.of(true, false), current.get(3));
            var dummyArgument = ((List<List<Object>>) current.get(2)).getFirst();
            assertEquals(List.of("var", dummy.get("id")), dummyArgument.subList(0, Math.min(2, dummyArgument.size())),
                stage + " getCurrentCCS# keeps the original dummy");
            assertFalse(CoreRepresentations.expression(dummyArgument).getEvaluated(),
                stage + " getCurrentCCS# dummy remains lazy");
            var result = (Map<String, Object>) ((Map<?, ?>) current.getLast()).get("rep");
            assertEquals(List.of("AddrRep"), result.get("primReps"));
            assertEquals(List.of("void", "address"),
                ((List<Map<String, Object>>) result.get("components"))
                    .stream()
                    .map(component -> component.get("kind"))
                    .toList());
            assertTrue(nodes(module.get("bindings"))
                    .stream()
                    .anyMatch(
                        node -> node.subList(0, Math.min(3, node.size())).equals(List.of("lit", "null-addr", "0"))));
            for (String backend : List.of("ast", "bytecode"))
                try (var context = PrimopTestContext.primopTestContext()) {
                    context.initialize("thc");
                    context.enter();
                    try {
                        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                        var source = new LinkedHashMap<>(module);
                        source.put("instrument", true);
                        ExecutableProgram program = backend.equals("ast") ? new Program(language, source)
                                                                          : new BytecodeProgram(language, source);
                        var function = context.asValue(new EntryValue(program, "probe", 1));
                        class Checks {
                            void check(int selector, long answer) {
                                assertEquals(answer, function.execute((long) selector).asLong(),
                                    stage + "/" + backend + "/" + selector);
                            }
                            long counter(String name) {
                                return ((Number) program.diagnostics().get(name)).longValue();
                            }
                        }
                        var checks = new Checks();
                        for (int selector = 0; selector < expected.size(); selector++)
                            checks.check(selector, expected.get(selector));
                        assertEquals(0L, checks.counter("compiledEntries"));
                        assertTrue(
                            function.invokeMember("compile").asBoolean(), stage + "/" + backend + " JIT installation");
                        var target = program.entryTarget("main:AddressIdentityAudit.probe");
                        class Valid {
                            void check() throws Exception {
                                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target),
                                    stage + "/" + backend + " installed probe remains valid");
                            }
                        }
                        var valid = new Valid();
                        valid.check();
                        assertEquals(0L, checks.counter("compiledEntries"),
                            stage + "/" + backend + " installation never invokes probe");
                        for (int selector = 0; selector < expected.size(); selector++) {
                            long before = checks.counter("compiledEntries");
                            checks.check(selector, expected.get(selector));
                            assertEquals(before + expectedEntries, checks.counter("compiledEntries"),
                                stage + "/" + backend + "/" + selector + " exact source-derived compiled entries");
                            assertSame(
                                target, program.entryTarget("main:AddressIdentityAudit.probe"), stage + "/" + backend + " retained probe target");
                            valid.check();
                            assertEquals(0L, checks.counter("unsupportedTraps"));
                            var pools = language.getHandoffState().get();
                            assertEquals(0, pools.getArguments().getDepth());
                            assertEquals(0, pools.getResults().getDepth());
                            assertEquals(0, pools.getArguments().retainedReferences());
                            assertEquals(0, pools.getResults().retainedReferences());
                        }
                        assertEquals(expectedEntries * expected.size(), checks.counter("compiledEntries"),
                            stage + "/" + backend + " every selector enters the lowered probe root in installed code");
                    } finally {
                        context.leave();
                    }
                }
        }
    }

    @Test
    void nullAndAllocationIdentityNeverBecomeHostPointers() {
        var nullAddress = ManagedAddress.nullAddress();
        assertSame(nullAddress, nullAddress.plus(0));
        assertTrue(nullAddress.sameLocation(ManagedAddress.nullAddress()));
        assertThrows(RuntimeFault.class, () -> nullAddress.plus(1));
        assertThrows(RuntimeFault.class, () -> nullAddress.indexChar(0));
        assertThrows(RuntimeFault.class, nullAddress::utf8);
        byte[] bytes = {1, 2};
        var first = ManagedAddress.fromByteArray(bytes);
        var alias = ManagedAddress.fromByteArray(bytes);
        assertTrue(first.sameLocation(alias));
        assertTrue(first.plus(1).sameLocation(alias.plus(1)));
        assertFalse(first.sameLocation(alias.plus(1)));
        assertFalse(first.sameLocation(ManagedAddress.fromByteArray(bytes.clone())));
        assertFalse(first.sameLocation(nullAddress));
        assertFalse(ManagedAddress.fromHex("41").sameLocation(ManagedAddress.fromHex("41")));
    }
}