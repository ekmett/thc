// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import thc.CoreModules;
import thc.Json;
import static org.junit.jupiter.api.Assertions.*;

/** Independent expectations; the native preparer only records observations. */
@SuppressWarnings("unchecked")
public final class OriginalStdioChecks {
    private OriginalStdioChecks() {}
    /** Inspect the authentic executable CBD through the canonical record decoder. */
    public static Map<String, Object> module(File file) throws Exception {
        return thc.CoreCbdFixtures.read(file.toPath());
    }
    /** Invoke genuine Core through the existing host entry and typed input boundary. */
    public static Object invoke(ExecutableProgram program, String entry, Object... arguments) {
        return Calls.target(program.hostEntryTarget(arguments.length), new Object[]{program.entryValue(entry), arguments});
    }
    // Ordered nullable documents keep the fixture builders and negative mutations readable.
    public static Map<String, Object> map(Object... fields) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]);
        return result;
    }
    public static Map<String, Object> with(Map<?, ?> original, Object... fields) {
        var result = new LinkedHashMap<String, Object>();
        for (var entry : original.entrySet()) result.put((String) entry.getKey(), entry.getValue());
        result.putAll(map(fields)); return result;
    }
    public static Map<String, Object> without(Map<?, ?> original, Object... fields) {
        var result = with(original); for (var field : fields) result.remove(field); return result;
    }
    public static List<Object> list(Object... values) { return Arrays.asList(values); }
    public static String hex(byte[] value) { return HexFormat.of().formatHex(value); }
    public static void hashes(File root, Object value, Set<String> required) throws Exception { hashes(root, value, required, null); }
    public static void hashes(File root, Object value, Set<String> required, String prefix) throws Exception {
        var base = root.getCanonicalFile();
        var hashes = (Map<String, String>) value;
        var missing = new LinkedHashSet<>(required); missing.removeAll(hashes.keySet());
        assertTrue(hashes.keySet().containsAll(required), "missing provenance: " + missing);
        for (var entry : hashes.entrySet()) {
            var path = entry.getKey(); var expected = entry.getValue(); var file = new File(base, path);
            assertFalse(new File(path).isAbsolute(), path);
            assertEquals(file.getAbsoluteFile(), file.getCanonicalFile(), "noncanonical provenance path: " + path);
            assertTrue(file.toPath().startsWith(base.toPath()), "external provenance: " + path);
            if (prefix != null) assertTrue(path.startsWith(prefix), "unexpected artifact: " + path);
            assertTrue(expected.matches("[0-9a-f]{64}"), path);
            assertEquals(expected, hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()))), "stale fixture: " + path);
        }
    }
    public static List<List<Object>> nodes(Object value) {
        var result = new ArrayList<List<Object>>();
        if (value instanceof Map<?, ?> map) for (var child : map.values()) result.addAll(nodes(child));
        else if (value instanceof List<?> list) {
            result.add((List<Object>) list);
            for (var child : list) result.addAll(nodes(child));
        }
        return result;
    }
    public static List<List<Object>> foreignCalls(Object value) {
        var result = new ArrayList<List<Object>>();
        for (var node : nodes(value)) if (!node.isEmpty() && Objects.equals(node.getFirst(), "app") &&
            node.getLast() instanceof Map<?, ?> metadata && metadata.containsKey("foreignCall")) result.add(node);
        return result;
    }
    public static Map<String, Object> rawModule(List<?> original, Map<String, Object> source) { return rawModule(original, source, null); }
    /** Reuse one genuine FCall for raw-carrier negative controls, not provenance. */
    public static Map<String, Object> rawModule(List<?> original, Map<String, Object> source, Integer storedMutation) {
        var call = (List<Object>) Json.parse(Json.stringify(original));
        var descriptor = (Map<?, ?>) ((Map<?, ?>) call.get(6)).get("foreignCall");
        var reps = new ArrayList<Map<String, Object>>();
        for (var argument : (List<Map<String, Object>>) descriptor.get("argumentReps")) reps.add(with(argument, "evaluated", true));
        var output = (Map<String, Object>) descriptor.get("resultRep");
        var components = (List<Map<String, Object>>) output.get("components");
        var result = components.size() > 1 ? components.get(1) : OriginalStdioFixtures.scalar("IntRep");
        call.set(1, list("var", "foreign", map("rep", OriginalStdioFixtures.closure())));
        var arguments = new ArrayList<Object>(); var formals = new ArrayList<Object>();
        for (int i = 0; i < reps.size(); i++) {
            var rep = reps.get(i);
            arguments.add(list("var", "p" + i, map("rep", rep)));
            formals.add(map("id", "p" + i, "name", "p" + i, "lifted", false,
                "rep", Objects.equals(storedMutation, i) ? OriginalStdioFixtures.scalar("IntRep") : rep));
        }
        call.set(2, arguments);
        var ids = components.size() == 1 ? List.of("s") : List.of("s", "value");
        var returned = components.size() == 1 ? list("lit", "int", "0", map("rep", result)) : list("var", "value", map("rep", result));
        var binders = new ArrayList<Object>();
        for (int i = 0; i < Math.min(ids.size(), components.size()); i++) binders.add(map("id", ids.get(i), "lifted", false, "rep", components.get(i)));
        var body = list("case", call, "pair", list(list("data", "T" + components.size(), ids, returned, map("binders", binders))),
            map("rep", result, "binder", map("id", "pair", "lifted", false, "rep", output)));
        var constructors = new ArrayList<Object>();
        if (source.get("constructors") instanceof List<?> existing)
            for (var constructor : existing)
                if (!Objects.equals(((Map<?, ?>) constructor).get("id"), "T" + components.size())) constructors.add(constructor);
        constructors.add(map("id", "T" + components.size(), "kind", "unboxed-tuple", "arity", components.size(), "tag", 1));
        var bindings = new ArrayList<Object>();
        if (source.get("selectedForeignExceptionBridge") instanceof Map<?, ?> bridge)
            bindings.addAll((List<?>) CoreModules.reachable(source,
                List.of((String) bridge.get("box"), (String) bridge.get("project")), true).get("bindings"));
        bindings.add(map("id", "entry", "name", "entry", "arity", reps.size(), "lifted", true, "rep", OriginalStdioFixtures.closure(),
            "expr", list("lam", formals, body, map("rep", OriginalStdioFixtures.closure(), "resultRep", result))));
        return with(source, "instrument", true, "constructors", constructors, "bindings", bindings);
    }
    public static void audit(Map<String, Object> report, String owner, List<String> symbols) {
        assertEquals(true, report.get("accepted")); assertEquals(List.of(owner), report.get("roots"));
        var reachable = new ArrayList<Object>();
        for (var binding : (List<Map<String, Object>>) report.get("reachableBindings")) reachable.add(binding.get("id"));
        assertEquals(List.of(owner), reachable);
        for (var field : List.of("issues", "missingGlobals", "runtimeExternals")) assertEquals(List.of(), report.get(field), field);
        var calls = (List<Map<String, Object>>) report.get("foreignCalls");
        var actualSymbols = new ArrayList<String>(); for (var call : calls) actualSymbols.add((String) call.get("symbol"));
        var expectedSymbols = new ArrayList<>(symbols); Collections.sort(expectedSymbols); Collections.sort(actualSymbols);
        assertEquals(expectedSymbols, actualSymbols);
        boolean sameOwner = true; for (var call : calls) sameOwner &= Objects.equals(call.get("owner"), owner);
        assertTrue(sameOwner);
    }
    public static <T> T single(Iterable<T> values, java.util.function.Predicate<? super T> predicate) {
        T result = null; boolean found = false;
        for (T value : values) if (predicate.test(value)) {
            if (found) throw new IllegalArgumentException("Collection contains more than one matching element.");
            result = value; found = true;
        }
        if (!found) throw new NoSuchElementException("Collection contains no element matching the predicate.");
        return result;
    }
    /** Discover direct guest callees before their parent, to request compilation of discovered callees. */
    public static List<com.oracle.truffle.api.RootCallTarget> targets(com.oracle.truffle.api.RootCallTarget entry) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<com.oracle.truffle.api.RootCallTarget, Boolean>());
        var found = new ArrayList<com.oracle.truffle.api.RootCallTarget>(); visitTarget(entry, seen, found); return found;
    }
    private static void visitTarget(com.oracle.truffle.api.RootCallTarget target, Set<com.oracle.truffle.api.RootCallTarget> seen,
            List<com.oracle.truffle.api.RootCallTarget> found) {
        if (!seen.add(target)) return;
        var body = target.getRootNode(); var nodes = new ArrayList<com.oracle.truffle.api.nodes.Node>(); nodes.add(body);
        if (body instanceof BytecodeRoot bytecode) for (var instruction : bytecode.getBytecodeNode().getInstructions())
            for (var argument : instruction.getArguments()) if (argument.getKind() == com.oracle.truffle.api.bytecode.Instruction.Argument.Kind.NODE_PROFILE) {
                var node = argument.asCachedNode(); if (node != null) nodes.add(node);
            }
        for (var node : nodes) for (var call : com.oracle.truffle.api.nodes.NodeUtil.findAllNodeInstances(node, com.oracle.truffle.api.nodes.DirectCallNode.class)) {
            if (call.getCurrentCallTarget() instanceof com.oracle.truffle.api.RootCallTarget callee && callee.getRootNode() instanceof GuestRoot)
                visitTarget(callee, seen, found);
        }
        found.add(target);
    }
}
