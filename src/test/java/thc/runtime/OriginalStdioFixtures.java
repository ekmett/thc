// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.*;
import java.util.function.Consumer;
import static thc.runtime.OriginalStdioChecks.map;
import static thc.runtime.OriginalStdioChecks.list;

/** Synthetic descriptors, independent of the production operation signature table. */
public final class OriginalStdioFixtures {
    private OriginalStdioFixtures() {}
    public static final Map<String, String> symbols = new LinkedHashMap<>();
    public static final Map<String, List<String>> signatures = new LinkedHashMap<>();
    static {
        symbols.put("safe_write", "ghczuwrapperZC20ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCwrite");
        symbols.put("unsafe_write", "ghczuwrapperZC21ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCwrite");
        symbols.put("errno", "__hscore_get_errno"); symbols.put("set_errno", "__hscore_set_errno");
        symbols.put("dup", "dup"); symbols.put("dup2", "dup2"); symbols.put("unlink", "unlink");
        symbols.put("seek_set", "ghczuwrapperZC1ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSEEKzuSET");
        symbols.put("seek_cur", "ghczuwrapperZC2ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSEEKzuCUR");
        symbols.put("seek_end", "ghczuwrapperZC0ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSEEKzuEND");
        symbols.put("strerror", "base_strerror_r");
        signatures.put("safe_write", Arrays.asList("Int32Rep", "AddrRep", "Word64Rep", null));
        signatures.put("unsafe_write", Arrays.asList("Int32Rep", "AddrRep", "Word64Rep", null));
        signatures.put("set_errno", Arrays.asList("Int32Rep", null));
        signatures.put("errno", Arrays.asList((String) null));
        signatures.put("dup", Arrays.asList("Int32Rep", null));
        signatures.put("dup2", Arrays.asList("Int32Rep", "Int32Rep", null));
        signatures.put("unlink", Arrays.asList("AddrRep", null));
        signatures.put("seek_set", Arrays.asList((String) null));
        signatures.put("seek_cur", Arrays.asList((String) null));
        signatures.put("seek_end", Arrays.asList((String) null));
        signatures.put("strerror", Arrays.asList("Int32Rep", "AddrRep", "Word64Rep", null));
    }
    // Open is an explicitly requested declaration, not part of the shared stdio safety matrix.
    private static List<String> signature(String name) {
        return name.equals("open") ? Arrays.asList("AddrRep", "Int32Rep", "Word32Rep", null)
            : Objects.requireNonNull(signatures.get(name));
    }
    public static String convention(String name) {
        return Set.of("errno", "set_errno", "dup", "dup2", "strerror", "unlink", "open").contains(name) ? "ccall" : "capi";
    }
    public static String safety(String name) { return name.equals("safe_write") || name.equals("strerror") ? "safe" : "unsafe"; }
    public static Map<String, Object> scalar(String rep) { return scalar(rep, true); }
    public static Map<String, Object> scalar(String rep, boolean evaluated) {
        String kind = switch (rep) {
            case null -> "void"; case "AddrRep" -> "address";
            case "BoxedRep (Just Lifted)" -> "closure"; default -> "long";
        };
        return map("kind", kind, "primReps", rep == null ? List.of() : List.of(rep), "evaluated", evaluated);
    }
    public static String output(String name) { return name.equals("safe_write") || name.equals("unsafe_write") ? "Int64Rep" : "Int32Rep"; }
    public static Map<String, Object> tuple(String name) { return tuple(name, true); }
    public static Map<String, Object> tuple(String name, boolean evaluated) {
        return map("kind", "unknown", "primReps", name.equals("set_errno") ? List.of() : List.of(output(name)),
            "aggregate", "unboxed-tuple", "components", name.equals("set_errno") ? new ArrayList<>(List.of(scalar(null))) :
                new ArrayList<>(List.of(scalar(null), scalar(output(name)))), "evaluated", evaluated);
    }
    public static Map<String, Object> closure() { return scalar("BoxedRep (Just Lifted)"); }
    public static Map<String, Object> descriptor(String name) {
        var reps = signature(name);
        var arguments = new ArrayList<Object>();
        for (var rep : reps) arguments.add(scalar(rep, false));
        return map("schema", 1L, "target", map("kind", "static", "symbol", name.equals("open") ? "__hscore_open" : Objects.requireNonNull(symbols.get(name)),
            "unit", "ghc-internal", "isFunction", true), "convention", convention(name), "safety", safety(name),
            "arity", (long) reps.size(), "suppliedArity", (long) reps.size(), "argumentReps", arguments, "resultRep", tuple(name, false));
    }
    public static List<Object> call(String name) {
        var reps = signature(name);
        var arguments = new ArrayList<Object>();
        for (int i = 0; i < reps.size(); i++) arguments.add(list("var", "p" + i, map("rep", scalar(reps.get(i)))));
        return new ArrayList<>(list("app", list("var", "foreign-" + name, map("rep", closure())), arguments,
            new ArrayList<>(Collections.nCopies(reps.size(), false)), false, false,
            map("rep", tuple(name), "foreignCall", descriptor(name))));
    }
    private static Set<String> defaultNames() {
        var names = new LinkedHashSet<>(signatures.keySet()); names.removeAll(Set.of("strerror", "unlink", "set_errno")); return names;
    }
    public static Map<String, Object> module() { return module(defaultNames(), ignored -> {}); }
    public static Map<String, Object> module(Iterable<String> names) { return module(names, ignored -> {}); }
    public static Map<String, Object> module(Consumer<List<Object>> mutate) { return module(defaultNames(), mutate); }
    public static Map<String, Object> module(Iterable<String> names, Consumer<List<Object>> mutate) {
        var bindings = new ArrayList<Object>();
        for (String name : names) {
            var reps = signature(name);
            var formals = new ArrayList<Object>();
            for (int i = 0; i < reps.size(); i++) formals.add(map("id", "p" + i, "name", name + "_" + i,
                "lifted", false, "rep", scalar(reps.get(i))));
            var expression = call(name); mutate.accept(expression);
            var body = list("case", expression, "pair", list(list("data", "T2", list("s", "value"),
                list("var", "value", map("rep", scalar(output(name)))),
                map("binders", list(map("id", "s", "lifted", false, "rep", scalar(null)),
                    map("id", "value", "lifted", false, "rep", scalar(output(name))))))),
                map("rep", scalar(output(name)), "binder", map("id", "pair", "lifted", false, "rep", tuple(name))));
            bindings.add(map("id", name, "name", name, "arity", reps.size(), "lifted", true, "rep", closure(),
                "expr", list("lam", formals, body, map("rep", closure(), "resultRep", scalar(output(name))))));
        }
        return map("instrument", true, "constructors", list(map("id", "T2", "kind", "unboxed-tuple", "arity", 2, "tag", 1)),
            "bindings", bindings);
    }
}
