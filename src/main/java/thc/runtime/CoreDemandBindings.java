// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/** Context-owned cold cells. Merely referencing a global reserves a cell; it
 * does not look up a symbol row, decode its header, or lower its body. */
public final class CoreDemandBindings {
    private final Predicate<String> owns, defined;
    private final Function<String,Map<String,Object>> readBinding, readConstructor;
    private final BiFunction<String,Map<String,Object>,ExecutableProgram> prepare;
    private final thc.Language.State owner = thc.Language.currentState();
    private final PreparationLock lock = new PreparationLock(owner.getEnv().getContext());
    private final Metrics metrics;
    private final Map<String,DataLayout> layouts = new LinkedHashMap<>();
    private final Map<String,Map<String,Object>> definitions = new HashMap<>();
    private final Map<String,GlobalBinding> cells = new HashMap<>();
    private final Map<String,ExecutableProgram> programs = new LinkedHashMap<>();
    private final Map<String,List<CoreRepresentation>> uses = new HashMap<>();
    private final Map<String,List<List<CoreRepresentation>>> calls = new HashMap<>();
    public CoreDemandBindings(Predicate<String> owns, Function<String,Map<String,Object>> readBinding,
            Function<String,Map<String,Object>> readConstructor, BiFunction<String,Map<String,Object>,ExecutableProgram> prepare,
            boolean instrument, Predicate<String> defined) {
        this.owns = owns; this.readBinding = readBinding; this.readConstructor = readConstructor;
        this.prepare = prepare; this.defined = defined; metrics = new Metrics(instrument);
    }
    public PreparationLock getPreparationLock() { return lock; }
    public Metrics getMetrics() { return metrics; }
    public Map<String,DataLayout> getLayouts() { return layouts; }
    public boolean contains(String id) { return owns.test(id); }
    /** Foreign lowering requires exact definition membership without decoding its RHS. */
    public boolean isDefined(String id) { try (var ownership = lock.acquire()) { return defined.test(id); } }
    public Map<String,Object> definition(String id) {
        try (var ownership = lock.acquire()) {
            var binding = definitions.get(id);
            if (binding == null) {
                binding = readBinding.apply(id);
                if (!Objects.equals(binding.get("id"), id)) throw new IllegalArgumentException("Core binding identity mismatch: " + id);
                definitions.put(id, binding);
            }
            return binding;
        }
    }
    public GlobalBinding cell(String id) {
        try (var ownership = lock.acquire()) {
            if (!owns.test(id)) return null;
            var cell = cells.get(id);
            if (cell == null) {
                cell = new GlobalBinding(id);
                cell.defer(lock, () -> {
                    var binding = definition(id); validateUses(id, binding);
                    var selected = prepare.apply(id, binding);
                    programs.put(id, selected);
                    return new GlobalBinding.Initializer(selected, owner, id, () -> {});
                });
                cells.put(id, cell);
            }
            return cell;
        }
    }
    public Map<String,GlobalBinding> globals(Map<String,GlobalBinding> local) {
        return new AbstractMap<>() {
            @Override public Set<Entry<String,GlobalBinding>> entrySet() { return local.entrySet(); }
            @Override public boolean containsKey(Object key) { return key instanceof String id && (local.containsKey(id) || contains(id)); }
            @Override public GlobalBinding get(Object key) { if (!(key instanceof String id)) return null; var found = local.get(id); return found != null ? found : cell(id); }
        };
    }
    public Map<String,Map<String,Object>> constructors(Map<String,Map<String,Object>> local) {
        return new AbstractMap<>() {
            @Override public Set<Entry<String,Map<String,Object>>> entrySet() { return local.entrySet(); }
            @Override public boolean containsKey(Object key) { return get(key) != null; }
            @Override public Map<String,Object> get(Object key) {
                if (!(key instanceof String id)) return null; var found = local.get(id); if (found != null) return found;
                try (var ownership = lock.acquire()) { return readConstructor.apply(id); }
            }
        };
    }
    /** Occurrence layout selects code generation, not an evaluatedness claim
     * about an unopened definition. Validate before a cell publishes its value. */
    public CoreRepresentation occurrence(String id, CoreRepresentation proof) {
        try (var ownership = lock.acquire()) {
            if (!owns.test(id)) return null;
            uses.computeIfAbsent(id, ignored -> new ArrayList<>()).add(proof);
            var binding = definitions.get(id); if (binding != null) validateOccurrence(CoreRepresentations.binder(binding), proof);
            return new CoreRepresentation(proof.getKind(), false, proof.getPresent(), proof.getPrimReps(), proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots());
        }
    }
    public void call(String id, List<CoreRepresentation> arguments) {
        try (var ownership = lock.acquire()) { calls.computeIfAbsent(id, ignored -> new ArrayList<>()).add(arguments); if (definitions.containsKey(id)) validateCall(id, arguments); }
    }
    private static void validateOccurrence(CoreRepresentation expected, CoreRepresentation actual) {
        CoreVectors.requireVariableProof(expected, actual);
        if (expected.isTypedTransport() || actual.isTypedTransport()) {
            if (!expected.isTypedTransport() || !actual.isTypedTransport() || !TupleShape.Companion.compatible(expected, actual)) throw new UnsupportedCore("Conflicting demanded global representation proof");
        }
    }
    @SuppressWarnings("unchecked")
    private List<CoreRepresentation> resolve(List<Object> expression, Set<String> seen) {
        if (expression.isEmpty()) return null;
        return switch (Objects.toString(expression.getFirst(), "")) {
            case "lam" -> {
                var arguments = new ArrayList<CoreRepresentation>();
                for (var binding : (List<Map<String,Object>>) expression.get(1)) arguments.add(CoreRepresentations.binder(binding));
                yield Collections.unmodifiableList(arguments);
            }
            case "var" -> { String next = (String) expression.get(1); yield !owns.test(next) || !seen.add(next) ? null : resolve((List<Object>) definition(next).get("expr"), seen); }
            case "app" -> {
                var inputs = resolve((List<Object>) expression.get(1), seen);
                if (inputs == null) yield null;
                int supplied = ((List<?>) expression.get(2)).size();
                yield supplied < inputs.size() ? new ArrayList<>(inputs.subList(supplied, inputs.size())) : null;
            }
            default -> null;
        };
    }
    @SuppressWarnings("unchecked")
    private List<CoreRepresentation> signature(String id) {
        var seen = new HashSet<String>(); seen.add(id); return resolve((List<Object>) definition(id).get("expr"), seen);
    }
    private void validateCall(String id, List<CoreRepresentation> arguments) {
        var expected = signature(id); if (expected != null) CoreInputCalls.requireArguments(expected, arguments);
    }
    private void validateUses(String id, Map<String,Object> binding) {
        var proof = CoreRepresentations.binder(binding);
        for (var occurrence : uses.getOrDefault(id, List.of())) validateOccurrence(proof, occurrence);
        for (var arguments : calls.getOrDefault(id, List.of())) validateCall(id, arguments);
    }
    /** Prepare one demanded program and its local code, leaving native startup
     * and guest value initialization at the ordinary read boundary. */
    public ExecutableProgram prepareCode(String id) {
        var selected = cell(id); if (selected == null) throw new UnsupportedCore("Unresolved external binding " + id);
        selected.prepareCode();
        ExecutableProgram program;
        try (var ownership = lock.acquire()) { program = programs.get(id); }
        if (program == null) throw new NoSuchElementException("Key " + id + " is missing in the map.");
        program.prepareCode();
        return program;
    }
    public ExecutableProgram program(String id) {
        var selected = cell(id); if (selected == null) throw new UnsupportedCore("Unresolved external binding " + id);
        selected.read();
        try (var ownership = lock.acquire()) {
            if (!programs.containsKey(id)) throw new NoSuchElementException("Key " + id + " is missing in the map.");
            return programs.get(id);
        }
    }
    public List<ExecutableProgram> preparedPrograms() { try (var ownership = lock.acquire()) { return new ArrayList<>(programs.values()); } }
}
