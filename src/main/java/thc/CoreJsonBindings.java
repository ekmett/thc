// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BiFunction;

/** Runtime projection over immutable JSON. Only demanded scalars are decoded and
 * canonicalized. The caller retains the source; executable state stays per-program.
 * This does not replace foreign admission, dependency discovery or full audit. */
public final class CoreJsonBindings {
    public record Statistics(long bindingHeaders, long bodyMaterializations, long expressionViews, long linkingExpressionViews, long scalarDecodes, long linkingScalarDecodes, long summaryExpressionsVisited, int canonicalStrings, int canonicalLists) {
        public long getBindingHeaders() { return bindingHeaders; }
        public long getBodyMaterializations() { return bodyMaterializations; }
        public long getExpressionViews() { return expressionViews; }
        public long getLinkingExpressionViews() { return linkingExpressionViews; }
        public long getScalarDecodes() { return scalarDecodes; }
        public long getLinkingScalarDecodes() { return linkingScalarDecodes; }
        public long getSummaryExpressionsVisited() { return summaryExpressionsVisited; }
        public int getCanonicalStrings() { return canonicalStrings; }
        public int getCanonicalLists() { return canonicalLists; }
    }
    public static final class Counters {
        public long bindingHeaders;
        public long bodyMaterializations;
        public long expressionViews;
        public long linkingExpressionViews;
        public long scalarDecodes;
        public long linkingScalarDecodes;
        public long summaryExpressionsVisited;
        public int canonicalStrings;
        public int canonicalLists;
        public synchronized Statistics statistics() { return new Statistics(bindingHeaders, bodyMaterializations, expressionViews, linkingExpressionViews, scalarDecodes, linkingScalarDecodes, summaryExpressionsVisited, canonicalStrings, canonicalLists); }
    }

    private final boolean sourceNotesEnabled;
    private final Counters counters = new Counters();
    private final Map<String, String> strings = new HashMap<>();
    private final Map<List<Object>, List<Object>> lists = new HashMap<>();
    public CoreJsonBindings(boolean sourceNotesEnabled) { this.sourceNotesEnabled = sourceNotesEnabled; }
    public Counters getCounters() { return counters; }
    public Statistics statistics() { return counters.statistics(); }
    public Map<String, Object> module(CoreJsonIndex.Span span) {
        synchronized (counters) {
            require(span.getKind() == CoreJsonIndex.Kind.OBJECT, "Core module must be an object");
            return new Record(span, Role.MODULE);
        }
    }
    public List<Map<String, Object>> bindings(CoreJsonIndex.Span span) {
        var result = new ArrayList<Map<String, Object>>();
        for (var child : span.elements()) result.add(binding(child));
        return Collections.unmodifiableList(result);
    }
    public Map<String, Object> binding(CoreJsonIndex.Span span) {
        synchronized (counters) {
            require(span.getKind() == CoreJsonIndex.Kind.OBJECT, "Core binding must be an object");
            counters.bindingHeaders++;
            return new Record(span, Role.BINDING);
        }
    }
    private enum Role { MODULE, BINDING, LOCAL_BINDING, FORMAL, METADATA, FULL }
    private static Set<String> ordered(String... values) { return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values))); }
    private static final Set<String> MODULE_FIELDS = ordered("schema", "ghc", "unit", "module", "boundary", "providedModules",
            "bindings", "constructors", "sourceFiles", "sourceSpans", "foreign", "foreignLink",
            "staticForeignImportStubs", "staticForeignImports", "staticForeignExports", "staticForeignExportRegistration",
            "packageScalarLink", "packageNativeLink", "packageNativeArchive", "foreignExceptionBridge", "foreignExceptionBridgeUnit");
    private static final Set<String> BINDING_FIELDS = ordered("id", "name", "type", "lifted", "coercion", "arity", "expr",
            "rep", "hostSignature", "entryStrict", "joinValueArity", "joinResultRep", "source", "sourceNotes");
    private static final Set<String> FORMAL_FIELDS = ordered("id", "name", "type", "lifted", "coercion", "rep", "source", "sourceNotes");
    private static final Set<String> METADATA_FIELDS = ordered("rep", "resultRep", "entryStrict", "callDemand", "foreignCall",
            "exceptionPayload", "enumFamily", "dataToTagFamily", "binder", "binders", "source", "sourceNotes");
    private static Set<String> fields(Role role) {
        return switch (role) {
            case MODULE -> MODULE_FIELDS; case BINDING, LOCAL_BINDING -> BINDING_FIELDS;
            case FORMAL -> FORMAL_FIELDS; case METADATA -> METADATA_FIELDS; case FULL -> null;
        };
    }
    private boolean admitted(Role role, String key) {
        var fields = fields(role);
        return (fields == null || fields.contains(key)) && (role == Role.FULL || sourceNotesEnabled ||
                !Set.of("source", "sourceNotes", "sourceFiles", "sourceSpans").contains(key));
    }
    @FunctionalInterface private interface Action<V> { V get() throws Exception; }
    private record Result<V>(V value, Exception failure) {
        V get() { return failure == null ? value : rethrow(failure); }
    }
    private static <K,V> V memo(Map<K, Result<V>> cache, K key, Action<V> action) {
        var previous = cache.get(key);
        if (previous != null) return previous.get();
        Result<V> result;
        try { result = new Result<>(action.get(), null); }
        catch (CancellationException cancelled) { throw cancelled; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return rethrow(interrupted); }
        catch (Exception failure) { result = new Result<>(null, failure); }
        cache.put(key, result);
        return result.get();
    }
    private String canonicalString(String value) {
        String previous = strings.get(value);
        if (previous != null) return previous;
        counters.canonicalStrings++;
        strings.put(value, value);
        return value;
    }
    private Object scalar(CoreJsonIndex.Span span) {
        counters.scalarDecodes++;
        Object value = span.decodeUncached();
        return value instanceof String text ? canonicalString(text) : value;
    }
    private Object value(CoreJsonIndex.Span span) {
        return switch (span.getKind()) {
            case OBJECT -> new Record(span, Role.FULL);
            case ARRAY -> new Sequence(span, (index, child) -> value(child));
            default -> scalar(span);
        };
    }
    private Object record(CoreJsonIndex.Span span, Role role) {
        return span.getKind() == CoreJsonIndex.Kind.OBJECT ? new Record(span, role) : value(span);
    }
    /** Only scalar immutable vectors are interned; occurrence maps remain distinct. */
    private Object scalarList(CoreJsonIndex.Span span) {
        if (span.getKind() != CoreJsonIndex.Kind.ARRAY) return value(span);
        var result = new ArrayList<Object>();
        boolean scalarsOnly = true;
        for (var child : span.elements()) {
            Object item = value(child);
            if (item instanceof Map<?,?> || item instanceof List<?>) scalarsOnly = false;
            result.add(item);
        }
        var immutable = Collections.unmodifiableList(result);
        if (!scalarsOnly) return immutable;
        var previous = lists.get(immutable);
        if (previous != null) return previous;
        counters.canonicalLists++;
        lists.put(immutable, immutable);
        return immutable;
    }
    private final class Record extends AbstractMap<String, Object> {
        final CoreJsonIndex.Span span;
        final Role role;
        final Map<String, Result<CoreJsonIndex.Span>> locations = new HashMap<>();
        final Map<String, Result<Object>> decodedFields = new HashMap<>();
        Set<String> allKeys;
        Record(CoreJsonIndex.Span span, Role role) { this.span = span; this.role = role; }
        CoreJsonIndex.Span location(String key) {
            return admitted(role, key) ? memo(locations, key, () -> span.member(key)) : null;
        }
        @Override public boolean containsKey(Object key) {
            synchronized (counters) { return key instanceof String text && location(text) != null; }
        }
        @Override public Object get(Object requested) {
            if (!(requested instanceof String key)) return null;
            synchronized (counters) {
                return memo(decodedFields, key, () -> {
                    var child = location(key);
                    if (child == null) return null;
                    if (key.equals("bindings") && role == Role.MODULE) return bindings(child);
                    if (key.equals("expr") && role == Role.BINDING) return body(child);
                    if (key.equals("expr") && role == Role.LOCAL_BINDING) return expression(child);
                    if (role == Role.METADATA && key.equals("binder")) return record(child, Role.FORMAL);
                    if (role == Role.METADATA && key.equals("binders")) return sequence(child, (index, item) -> record(item, Role.FORMAL));
                    if (Set.of("entryStrict", "primReps", "strictArgs").contains(key)) return scalarList(child);
                    return value(child);
                });
            }
        }
        @Override public Set<String> keySet() {
            synchronized (counters) {
                if (allKeys != null) return allKeys;
                var result = new LinkedHashSet<String>();
                var selected = fields(role);
                if (selected == null) {
                    for (var member : span.members()) {
                        String key = canonicalString(member.name());
                        locations.put(key, new Result<>(member.value(), null));
                        result.add(key);
                    }
                } else for (String key : selected) if (location(key) != null) result.add(key);
                return allKeys = Collections.unmodifiableSet(result);
            }
        }
        @Override public Set<Map.Entry<String,Object>> entrySet() {
            return new AbstractSet<>() {
                @Override public int size() { return keySet().size(); }
                @Override public Iterator<Map.Entry<String,Object>> iterator() {
                    var cursor = keySet().iterator();
                    return new Iterator<>() {
                        public boolean hasNext() { return cursor.hasNext(); }
                        public Map.Entry<String,Object> next() {
                            String key = cursor.next();
                            return new Map.Entry<>() {
                                public String getKey() { return key; }
                                public Object getValue() { return Record.this.get(key); }
                                public Object setValue(Object value) { throw new UnsupportedOperationException(); }
                                public boolean equals(Object other) {
                                    return other instanceof Map.Entry<?,?> entry && Objects.equals(key, entry.getKey()) && Objects.equals(getValue(), entry.getValue());
                                }
                                public int hashCode() { return key.hashCode() ^ Objects.hashCode(getValue()); }
                            };
                        }
                    };
                }
            };
        }
    }
    private final class Sequence extends AbstractList<Object> {
        final List<CoreJsonIndex.Span> children;
        final BiFunction<Integer,CoreJsonIndex.Span,Object> project;
        final Map<Integer,Result<Object>> values = new HashMap<>();
        Sequence(CoreJsonIndex.Span span, BiFunction<Integer,CoreJsonIndex.Span,Object> project) {
            children = new ArrayList<>(span.elements()); this.project = project;
        }
        @Override public int size() { return children.size(); }
        @Override public Object get(int index) {
            synchronized (counters) { return memo(values, index, () -> project.apply(index, children.get(index))); }
        }
    }
    private Object sequence(CoreJsonIndex.Span span, BiFunction<Integer,CoreJsonIndex.Span,Object> project) {
        return span.getKind() == CoreJsonIndex.Kind.ARRAY ? new Sequence(span, project) : value(span);
    }
    private static int metadataIndex(String tag) {
        return switch (tag) {
            case "var", "prim" -> 2; case "lit", "lam", "con" -> 3; case "app" -> 6;
            case "let", "case" -> 4; case "void" -> 1; default -> -1;
        };
    }
    private Object child(String tag, int index, CoreJsonIndex.Span span) {
        if (index == metadataIndex(tag)) return record(span, Role.METADATA);
        if (tag.equals("lam") && index == 1) return sequence(span, (i, item) -> record(item, Role.FORMAL));
        if (tag.equals("lam") && index == 2 || tag.equals("app") && index == 1 ||
                tag.equals("let") && index == 3 || tag.equals("case") && index == 1) return expression(span);
        if (tag.equals("app") && index == 2) return sequence(span, (i, item) -> expression(item));
        if (tag.equals("let") && index == 2) return sequence(span, (i, item) -> record(item, Role.LOCAL_BINDING));
        if (tag.equals("case") && index == 3) return sequence(span, (i, alternative) -> sequence(alternative, (position, item) -> switch (position) {
            case 3 -> expression(item); case 4 -> record(item, Role.METADATA); default -> value(item);
        }));
        return value(span);
    }
    private Object expression(CoreJsonIndex.Span span) {
        if (span.getKind() != CoreJsonIndex.Kind.ARRAY) return value(span);
        List<CoreJsonIndex.Span> parts = new ArrayList<>(span.elements());
        Object head = parts.isEmpty() ? null : value(parts.getFirst());
        String tag = head instanceof String text ? text : null;
        counters.expressionViews++;
        return expression(parts, tag, parts.isEmpty() ? Map.of() : Collections.singletonMap(0, head));
    }
    private List<Object> expression(List<CoreJsonIndex.Span> parts, String tag, Map<Integer,Object> prepared) {
        return new AbstractList<>() {
            final Map<Integer,Result<Object>> values = new HashMap<>();
            { prepared.forEach((key, value) -> values.put(key, new Result<>(value, null))); }
            @Override public int size() { return parts.size(); }
            @Override public Object get(int index) {
                synchronized (counters) {
                    return memo(values, index, () -> tag == null ? value(parts.get(index)) : child(tag, index, parts.get(index)));
                }
            }
        };
    }
    @SuppressWarnings("unchecked")
    private Object body(CoreJsonIndex.Span span) {
        if (span.getKind() != CoreJsonIndex.Kind.ARRAY) return value(span);
        List<CoreJsonIndex.Span> parts = new ArrayList<>(span.elements());
        Object head = !parts.isEmpty() && parts.getFirst().getKind() == CoreJsonIndex.Kind.STRING ? scalar(parts.getFirst()) : null;
        if (!(head instanceof String tag)) return expression(span);
        Map<Integer,Object> header = new LinkedHashMap<>();
        header.put(0, tag);
        int[] shallow = tag.equals("lam") ? new int[] {1, metadataIndex(tag)} : new int[] {metadataIndex(tag)};
        for (int index : shallow) if (index >= 0 && index < parts.size()) header.put(index, child(tag, index, parts.get(index)));
        boolean containsControl = containsDelimitedControl(span);
        return new CoreBindingBody(new CoreBindingBody.Header(parts.size(), header, containsControl), visit -> {
            var inspection = new CoreJsonBindings(sourceNotesEnabled);
            try { visit.accept((List<Object>) inspection.expression(span)); }
            finally {
                var inspected = inspection.statistics();
                synchronized (counters) {
                    counters.linkingExpressionViews += inspected.expressionViews();
                    counters.linkingScalarDecodes += inspected.scalarDecodes();
                }
            }
        }, () -> {
            synchronized (counters) {
                span.getKind();
                counters.bodyMaterializations++; counters.expressionViews++;
                return expression(parts, tag, header);
            }
        });
    }
    private static CoreJsonIndex.Span at(List<CoreJsonIndex.Span> parts, int index) { return index < parts.size() ? parts.get(index) : null; }
    private static void enqueue(ArrayDeque<CoreJsonIndex.Span> pending, CoreJsonIndex.Span span) { if (span != null) pending.add(span); }
    private static void expressions(ArrayDeque<CoreJsonIndex.Span> pending, CoreJsonIndex.Span span) {
        if (span != null && span.getKind() == CoreJsonIndex.Kind.ARRAY) for (var child : span.elements()) pending.add(child);
    }
    /** Root policy summary visits expression positions only, without scalar decoding. */
    private boolean containsDelimitedControl(CoreJsonIndex.Span root) {
        var pending = new ArrayDeque<CoreJsonIndex.Span>();
        pending.add(root);
        while (!pending.isEmpty()) {
            var current = pending.removeLast();
            if (current.getKind() != CoreJsonIndex.Kind.ARRAY) continue;
            counters.summaryExpressionsVisited++;
            var parts = new ArrayList<>(current.elements());
            var tag = at(parts, 0);
            if (tag == null || tag.getKind() != CoreJsonIndex.Kind.STRING) continue;
            if (tag.stringEquals("prim")) {
                var name = at(parts, 1);
                if (name != null && name.getKind() == CoreJsonIndex.Kind.STRING &&
                        (name.stringEquals("prompt#") || name.stringEquals("control0#"))) return true;
            } else if (tag.stringEquals("lam")) enqueue(pending, at(parts, 2));
            else if (tag.stringEquals("app")) { enqueue(pending, at(parts, 1)); expressions(pending, at(parts, 2)); }
            else if (tag.stringEquals("let")) {
                enqueue(pending, at(parts, 3));
                var bindings = at(parts, 2);
                if (bindings != null && bindings.getKind() == CoreJsonIndex.Kind.ARRAY)
                    for (var binding : bindings.elements())
                        if (binding.getKind() == CoreJsonIndex.Kind.OBJECT) enqueue(pending, binding.member("expr"));
            } else if (tag.stringEquals("case")) {
                enqueue(pending, at(parts, 1));
                var alternatives = at(parts, 3);
                if (alternatives != null && alternatives.getKind() == CoreJsonIndex.Kind.ARRAY)
                    for (var alternative : alternatives.elements())
                        if (alternative.getKind() == CoreJsonIndex.Kind.ARRAY) {
                            var cursor = alternative.elements().iterator();
                            for (int i = 0; i < 3 && cursor.hasNext(); i++) cursor.next();
                            if (cursor.hasNext()) pending.add(cursor.next());
                        }
            }
        }
        return false;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    @SuppressWarnings("unchecked")
    private static <T,E extends Throwable> T rethrow(Throwable failure) throws E { throw (E) failure; }
}
