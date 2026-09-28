// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Lowering-only proof checks for known inputs; physical carriers never imply a scalar signature. */
@SuppressWarnings("unchecked")
public final class CoreInputCalls {
    private CoreInputCalls() {}
    private record Binding(CoreRepresentation proof, List<CoreRepresentation> inputs) {}
    public static void validate(List<Map<String, Object>> bindings) { validate(bindings, Map.of()); }
    public static void validate(List<Map<String, Object>> bindings, Map<String, Map<String, Object>> constructors) {
        validator(bindings, constructors).accept(bindings);
    }
    public static Consumer<List<Map<String, Object>>> validator(List<Map<String, Object>> bindings) {
        return validator(bindings, Map.of(), null);
    }
    public static Consumer<List<Map<String, Object>>> validator(List<Map<String, Object>> bindings,
            Map<String, Map<String, Object>> constructors) { return validator(bindings, constructors, null); }
    /** Index definition headers once; later admission walks only requested bodies. */
    public static Consumer<List<Map<String, Object>>> validator(List<Map<String, Object>> bindings,
            Map<String, Map<String, Object>> constructors, CoreDemandBindings demand) {
        Validator validator = new Validator(bindings, constructors, demand);
        return requested -> {
            for (Map<String, Object> binding : requested) validator.visit((List<Object>) binding.get("expr"), Map.of());
        };
    }
    private static final class Validator {
        private final Map<String, Map<String, Object>> globals = new LinkedHashMap<>();
        private final Map<String, Map<String, Object>> constructors;
        private final CoreDemandBindings demand;
        Validator(List<Map<String, Object>> bindings, Map<String, Map<String, Object>> constructors, CoreDemandBindings demand) {
            for (Map<String, Object> binding : bindings) globals.put((String) binding.get("id"), binding);
            this.constructors = constructors; this.demand = demand;
        }
        private List<CoreRepresentation> inputs(List<Object> expr, Map<String, Binding> scope) {
            return inputs(expr, scope, Set.of());
        }
        private List<CoreRepresentation> inputs(List<Object> expr, Map<String, Binding> scope, Set<String> seen) {
            Object tag = expr.getFirst();
            if ("lam".equals(tag)) {
                List<CoreRepresentation> result = new ArrayList<>();
                for (Map<String, Object> parameter : (List<Map<String, Object>>) expr.get(1))
                    result.add(CoreRepresentations.binder(parameter));
                return result;
            }
            if ("con".equals(tag)) {
                Map<String, Object> constructor = constructors.get(expr.get(1));
                if (constructor == null || constructor.get("kind") != null && !"boxed".equals(constructor.get("kind"))) return null;
                if (!(constructor.get("arity") instanceof Number number)) throw new RuntimeFault("Missing constructor arity");
                int arity = number.intValue();
                List<?> fields = constructor.get("fieldTypes") instanceof List<?> values ? values : null;
                if (fields != null && fields.size() != arity) throw new RuntimeFault("Constructor field type count mismatch");
                List<CoreRepresentation> result = new ArrayList<>(fields == null ? arity : fields.size());
                if (fields == null) for (int i = 0; i < arity; i++) result.add(CoreRepresentation.UNKNOWN);
                else for (Object field : fields) result.add(CoreRepresentations.parse(field));
                return result;
            }
            if ("var".equals(tag)) {
                String id = (String) expr.get(1);
                if (scope.containsKey(id)) return scope.get(id).inputs;
                if (seen.contains(id)) return null;
                Map<String, Object> binding = globals.get(id);
                if (binding == null) return null;
                Set<String> next = new HashSet<>(seen); next.add(id);
                return inputs((List<Object>) binding.get("expr"), Map.of(), next);
            }
            if ("app".equals(tag)) {
                List<CoreRepresentation> signature = inputs((List<Object>) expr.get(1), scope, seen);
                if (signature == null) return null;
                int count = ((List<?>) expr.get(2)).size();
                return count < signature.size() ? new ArrayList<>(signature.subList(count, signature.size())) : null;
            }
            return null;
        }
        private CoreRepresentation proof(List<Object> expr, Map<String, Binding> scope) {
            CoreRepresentation occurrence = CoreRepresentations.expression(expr);
            Binding binding = "var".equals(expr.getFirst()) ? scope.get(expr.get(1)) : null;
            return binding == null ? occurrence : binding.proof.refine(occurrence);
        }
        private Map<String, Binding> declarations(List<Map<String, Object>> group) {
            Map<String, Binding> result = new LinkedHashMap<>();
            for (Map<String, Object> binding : group)
                result.put((String) binding.get("id"), new Binding(CoreRepresentations.binder(binding), null));
            return result;
        }
        private Map<String, Binding> merged(Map<String, Binding> first, Map<String, Binding> second) {
            Map<String, Binding> result = new LinkedHashMap<>(first);
            result.putAll(second);
            return result;
        }
        void visit(List<Object> expr, Map<String, Binding> scope) {
            Object tag = expr.getFirst();
            if ("app".equals(tag)) {
                List<Object> fn = (List<Object>) expr.get(1);
                List<List<Object>> args = (List<List<Object>>) expr.get(2);
                List<CoreRepresentation> signature = inputs(fn, scope);
                List<CoreRepresentation> actual = new ArrayList<>();
                for (List<Object> arg : args) actual.add(proof(arg, scope));
                if (signature != null) requireArguments(signature, actual);
                else if (!fn.isEmpty() && "var".equals(fn.getFirst())) {
                    String id = (String) fn.get(1);
                    if (!scope.containsKey(id) && !globals.containsKey(id) && demand != null && demand.contains(id)) demand.call(id, actual);
                }
                visit(fn, scope);
                for (List<Object> arg : args) visit(arg, scope);
            } else if ("lam".equals(tag)) {
                List<Map<String, Object>> parameters = (List<Map<String, Object>>) expr.get(1);
                visit((List<Object>) expr.get(2), merged(scope, declarations(parameters)));
            } else if ("let".equals(tag)) {
                List<Map<String, Object>> group = (List<Map<String, Object>>) expr.get(2);
                boolean recursive = Boolean.TRUE.equals(expr.get(1));
                Map<String, Binding> shadowed = merged(scope, declarations(group));
                Map<String, Binding> declarations = declarations(group);
                // Resolve recursive aliases/PAPs with all outer names shadowed first.
                for (int i = 0, count = recursive ? group.size() : 1; i < count; i++) {
                    Map<String, Binding> rhsScope = recursive ? merged(shadowed, declarations) : scope;
                    Map<String, Binding> next = new LinkedHashMap<>();
                    for (Map<String, Object> binding : group) next.put((String) binding.get("id"),
                        new Binding(CoreRepresentations.binder(binding), inputs((List<Object>) binding.get("expr"), rhsScope)));
                    declarations = next;
                }
                Map<String, Binding> local = merged(scope, declarations);
                for (Map<String, Object> binding : group) visit((List<Object>) binding.get("expr"), recursive ? local : scope);
                visit((List<Object>) expr.get(3), local);
            } else if ("case".equals(tag)) {
                VectorReadCase read = CoreVectorMemory.INSTANCE.readCase(expr, constructors);
                if (read != null) {
                    for (List<Object> argument : read.getArguments()) visit(argument, scope);
                    Map<String, Binding> local = new LinkedHashMap<>(scope);
                    local.put(read.getStateBinder(), new Binding(CoreVectorMemory.INSTANCE.getStateProof(), null));
                    local.put(read.getVectorBinder(), new Binding(read.getOperation().getVectorProof(), null));
                    visit(read.getBody(), local);
                    return;
                }
                visit((List<Object>) expr.get(1), scope);
                Map<String, Object> meta = CoreRepresentations.metadata(expr);
                Map<String, Object> binder = meta != null && meta.get("binder") instanceof Map<?, ?> value ? (Map<String, Object>) value : null;
                Map<String, Binding> local = new LinkedHashMap<>(scope);
                local.put((String) expr.get(2), new Binding(binder == null ? proof((List<Object>) expr.get(1), scope) :
                    CoreRepresentations.binder(binder), null));
                for (List<Object> alternative : (List<List<Object>>) expr.get(3)) {
                    Map<String, Object> info = alternative.size() > 4 && alternative.get(4) instanceof Map<?, ?> value ? (Map<String, Object>) value : null;
                    Map<Object, Map<String, Object>> records = new LinkedHashMap<>();
                    if (info != null && info.get("binders") instanceof List<?> values)
                        for (Object raw : values) {
                            Map<String, Object> record = (Map<String, Object>) raw;
                            records.put(record.get("id"), record);
                        }
                    Map<String, Binding> arm = new LinkedHashMap<>(local);
                    for (String id : (List<String>) alternative.get(2)) {
                        Map<String, Object> record = records.get(id);
                        arm.put(id, new Binding(record == null ? CoreRepresentation.UNKNOWN :
                            CoreRepresentations.binder(record), null));
                    }
                    visit((List<Object>) alternative.get(3), arm);
                }
            }
        }
    }
    public static void requireArguments(List<CoreRepresentation> signature, List<CoreRepresentation> arguments) {
        for (int i = 0; i < Math.min(signature.size(), arguments.size()); i++) {
            CoreRepresentation actual = arguments.get(i), expected = signature.get(i);
            if (expected.isTuple() || actual.isTuple()) {
                if (!expected.isTuple() || !actual.isTuple() || !TupleShape.Companion.compatible(expected, actual))
                    throw new UnsupportedCore("Missing or conflicting exact tuple argument proof");
            } else if (expected.isVector() || actual.isVector()) {
                if (!expected.isVector() || !actual.isVector() || !TupleShape.Companion.compatible(expected, actual))
                    throw new UnsupportedCore("Missing or conflicting exact vector argument proof");
            } else if (expected.isSum() || actual.isSum()) {
                if (!expected.isSum() || !actual.isSum() || !TupleShape.Companion.compatible(expected, actual))
                    throw new UnsupportedCore("Missing or conflicting exact sum argument proof");
            }
        }
    }
}
