// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import static thc.runtime.Alternative.*;
import static thc.runtime.CoreCallDemands.CALL_DEMANDS_PROPERTY;
import static thc.runtime.CoreFreeVariables.coreFreeVariables;
import static thc.runtime.RuntimeFault.fault;
import static thc.runtime.Scalar64Primitives.word64Literal;

/* Indexed frames, selective captures, rooted application and self-tail frame
 * restoration follow Cadenza. See NOTICE.md and LICENSE.txt. */

/** Constructs and links the AST backend's executable roots from exported GHC Core.
 * This holder is not a Truffle node or guest closure. */
@SuppressWarnings("unchecked")
public final class Program implements ExecutableProgram {
    private final TruffleLanguage<?> language;
    private final boolean enableAsync;
    private final boolean outlineCaseArms;
    private final boolean capturesContinuations;
    private final CoreDemandBindings demand;
    private final ForeignExceptionBridge foreignExceptionBridge;
    private final RubbishLiterals rubbishLiterals;
    private final List<thc.ForeignBitcode> foreignLinks;
    private final List<thc.PackageScalarLink> packageScalarLinks;
    private final Object stackTargetLayout;
    private final boolean callDemandsEnabled = Boolean.getBoolean(CALL_DEMANDS_PROPERTY);
    private final Metrics metrics;
    private final Supplier<Map<String, Object>> loadingStatistics;
    private final boolean delimited;
    private final boolean containsDelimited;
    private final CoreSources sources;
    private CoreSourceLocation currentSource;
    private OperandBuilder operandBuilder;
    private int attachedRootCount;
    private int constructedRootCount;
    private int initializedBindingCount;
    private final Object preparationLock = new Object();
    private final boolean diagnosticUnsupported;
    private final Set<String> deferredUnsupported = new LinkedHashSet<>();
    private final List<Map<String, Object>> bindings;
    private final Map<String, Map<String, Object>> constructors;
    private final Map<String, DataLayout> dataLayouts;
    private final Map<String, GlobalBinding> globals;
    private final Map<String, CoreRepresentation> globalProofs = new LinkedHashMap<>();
    private final Map<String, Integer> indices = new LinkedHashMap<>();
    private final Map<String, List<Integer>> names = new LinkedHashMap<>();
    private final Map<Integer, RootCallTarget> hostEntries = new LinkedHashMap<>();
    private final Map<String, boolean[]> globalEntries = new LinkedHashMap<>();
    private final Map<String, CoreApplicationCertificates.Arity> globalArityCertificates = new LinkedHashMap<>();
    private final Consumer<List<Map<String, Object>>> validateInputs;

    public Program(TruffleLanguage<?> language, Map<String, Object> moduleData) { this(language, moduleData, false, false); }
    public Program(TruffleLanguage<?> language, Map<String, Object> moduleData, boolean enableAsync) { this(language, moduleData, enableAsync, false); }
    public Program(TruffleLanguage<?> language, Map<String, Object> moduleData, boolean enableAsync, boolean outlineCaseArms) {
        this.language = language; this.enableAsync = enableAsync; this.outlineCaseArms = outlineCaseArms;
        capturesContinuations = enableAsync || outlineCaseArms;
        thc.CoreForeignArtifacts.INSTANCE.requireExecutableInput(moduleData);
        demand = moduleData.get("demandBindings") instanceof CoreDemandBindings found ? found : null;
        foreignExceptionBridge = ForeignExceptionBridge.bind(moduleData, this::entryValue, this::dataLayout);
        rubbishLiterals = new RubbishLiterals(language);
        foreignLinks = moduleData.get("foreignLinks") instanceof List<?> found ? (List<thc.ForeignBitcode>) found : List.of();
        packageScalarLinks = moduleData.get("packageScalarLinks") instanceof List<?> found ? (List<thc.PackageScalarLink>) found : List.of();
        stackTargetLayout = moduleData.get("targetLayout");
        metrics = demand != null ? demand.getMetrics() : new Metrics(!Boolean.FALSE.equals(moduleData.get("instrument")));
        loadingStatistics = moduleData.get("coreLoadingStatistics") instanceof Supplier<?> found ? (Supplier<Map<String, Object>>) found : null;
        boolean containsDelimited = false;
        if (moduleData.get("bindings") instanceof List<?> values) {
            for (Object binding : values) {
                Object body = binding instanceof Map<?, ?> map ? map.get("expr") : null;
                boolean found = body instanceof thc.CoreBindingBody source ? source.getHeader().getContainsDelimitedControl() :
                    DelimitedControl.contains(binding);
                if (found) { containsDelimited = true; break; }
            }
        } else containsDelimited = DelimitedControl.contains(moduleData.get("bindings"));
        this.containsDelimited = containsDelimited;
        delimited = containsDelimited || demand != null && Boolean.TRUE.equals(moduleData.get("captureDelimited"));
        sources = new CoreSources(moduleData);
        diagnosticUnsupported = Boolean.TRUE.equals(moduleData.get("diagnosticUnsupported"));
        if (!(moduleData.get("bindings") instanceof List<?> found)) throw new RuntimeFault("Missing bindings");
        bindings = (List<Map<String, Object>>) found;
        Map<String, Map<String, Object>> localConstructors = new LinkedHashMap<>();
        if (moduleData.get("constructors") instanceof List<?> rows)
            for (Object row : rows) {
                Map<String, Object> constructor = (Map<String, Object>) row;
                localConstructors.put((String) constructor.get("id"), constructor);
            }
        constructors = demand == null ? localConstructors : demand.constructors(localConstructors);
        dataLayouts = demand == null ? new LinkedHashMap<>() : demand.getLayouts();
        Map<String, GlobalBinding> localGlobals = new LinkedHashMap<>();
        for (Map<String, Object> binding : bindings)
            localGlobals.put((String) binding.get("id"), new GlobalBinding((String) binding.get("name")));
        globals = demand == null ? localGlobals : demand.globals(localGlobals);
        for (Map<String, Object> binding : bindings) {
            List<Object> expression = (List<Object>) binding.get("expr");
            boolean delayed = representation(binding) && !Arrays.asList("lam", "lit", "con", "void").contains(expression.getFirst());
            globalProofs.put((String) binding.get("id"), diagnosticUnsupported ? CoreRepresentation.Companion.getUNKNOWN() :
                evaluated(CoreRepresentations.INSTANCE.binder(binding), demand == null && !delayed &&
                    Arrays.asList("lam", "lit", "con", "void").contains(expression.getFirst())));
        }
        for (int i = 0; i < bindings.size(); i++) indices.put((String) bindings.get(i).get("id"), i);
        for (int i = 0; i < bindings.size(); i++) names.computeIfAbsent((String) bindings.get(i).get("name"), key -> new ArrayList<>()).add(i);
        for (Map<String, Object> binding : bindings) globalEntries.put((String) binding.get("id"), CoreEntries.binding(binding));
        for (Map<String, Object> binding : bindings) globalArityCertificates.put((String) binding.get("id"), CoreApplicationCertificates.binding(binding));
        if (diagnosticUnsupported) validateInputs = null;
        else {
            validateInputs = CoreInputCalls.validator(bindings, constructors, demand);
        }
        List<Map<String, Object>> eager = new ArrayList<>();
        for (Map<String, Object> binding : bindings) {
            thc.CoreBindingBody source = binding.get("expr") instanceof thc.CoreBindingBody body ? body : null;
            if (source == null && demand == null || !representation(binding) || demand == null && source != null &&
                Arrays.asList("lit", "con", "void").contains(source.getHeader().getTag())) {
                eager.add(binding);
                continue;
            }
            required(globals, (String) binding.get("id")).defer(preparationLock, () -> {
                validateBindings(List.of(binding));
                Scope scope = new Scope(new FrameLayout());
                Expr initializer = initializer(binding, scope);
                VirtualFrame frame = Truffle.getRuntime().createVirtualFrame(new Object[0], scope.layout.build());
                Object value = initializer.execute(frame);
                CoreFunctionIdentity.install(moduleData, binding, value, globalArityCertificates);
                initializedBindingCount++;
                return value;
            });
        }
        validateBindings(eager);
        Scope scope = new Scope(new FrameLayout());
        List<Expr> initializers = new ArrayList<>();
        for (Map<String, Object> binding : eager) initializers.add(initializer(binding, scope));
        VirtualFrame frame = Truffle.getRuntime().createVirtualFrame(new Object[0], scope.layout.build());
        for (int i = 0; i < eager.size(); i++) {
            Map<String, Object> binding = eager.get(i);
            Object value = initializers.get(i).execute(frame);
            CoreFunctionIdentity.install(moduleData, binding, value, globalArityCertificates);
            required(globals, (String) binding.get("id")).initialize(value);
            initializedBindingCount++;
        }
    }
    @Override public boolean getAsynchronousExceptions() { return enableAsync; }
    public boolean getEnableAsync() { return enableAsync; }

    private static <K, V> V required(Map<K, V> values, K key) {
        V value = values.get(key);
        if (value == null && !values.containsKey(key)) throw new NoSuchElementException("Key " + key + " is missing in the map.");
        return value;
    }
    private static CoreRepresentation evaluated(CoreRepresentation proof, boolean value) {
        return new CoreRepresentation(proof.getKind(), value, proof.getPresent(), proof.getPrimReps(), proof.getComponents(),
            proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots());
    }
    private static int[] ints(List<Integer> values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < result.length; i++) result[i] = values.get(i);
        return result;
    }
    private static <T> T at(List<T> values, int index) { return index >= 0 && index < values.size() ? values.get(index) : null; }
    private record Local(int slot, boolean primitive, CoreRepresentation proof, boolean cell,
                         boolean[] entry, int[] tupleSlots, CoreApplicationCertificates.Arity arityCertificate) {}
    private static final class Scope {
        final FrameLayout layout;
        final Map<String, Local> locals;
        final Map<String, LocalJoinTarget> joins;
        AstSelfLayout self;
        Scope(FrameLayout layout) { this(layout, new LinkedHashMap<>(), new LinkedHashMap<>(), null); }
        Scope(FrameLayout layout, Map<String, Local> locals, Map<String, LocalJoinTarget> joins, AstSelfLayout self) {
            this.layout = layout; this.locals = locals; this.joins = joins; this.self = self;
        }
        Scope child() { return new Scope(layout.scope(), new LinkedHashMap<>(locals), new LinkedHashMap<>(joins), self); }
        Local bind(String id, boolean primitive, CoreRepresentation proof) {
            return bind(id, primitive, proof, false, null, null, FrameSlotKind.Illegal);
        }
        Local bind(String id, boolean primitive, CoreRepresentation proof, boolean cell, boolean[] entry,
                   CoreApplicationCertificates.Arity arityCertificate, FrameSlotKind kind) {
            Local local = new Local(layout.bind(id, kind), proof.getPresent() ? proof.isLong() : primitive, proof, cell, entry, null, arityCertificate);
            locals.put(id, local); joins.remove(id);
            return local;
        }
        Local bindTuple(String id, CoreRepresentation proof, int[] slots) {
            Local local = new Local(-1, false, proof, false, null, slots, null);
            locals.put(id, local); joins.remove(id);
            return local;
        }
        Local bindVoid(String id, CoreRepresentation proof) {
            Local local = new Local(-1, false, evaluated(proof, true), false, null, null, null);
            locals.put(id, local); joins.remove(id);
            return local;
        }
        void bindSlot(String id, Local local) { locals.put(id, local); joins.remove(id); }
        void refine(String id, CoreRepresentation proof) {
            Local local = locals.get(id);
            if (local != null) locals.put(id, new Local(local.slot, proof.getPresent() ? proof.isLong() : local.primitive,
                proof, local.cell, local.entry, local.tupleSlots, local.arityCertificate));
        }
        void publish(String id, CoreRepresentation proof) {
            refine(id, proof);
            Local local = locals.get(id);
            if (local != null) locals.put(id, new Local(local.slot, local.primitive, local.proof, false, local.entry, local.tupleSlots, local.arityCertificate));
        }
        void bindJoin(String id, LocalJoinTarget target) { joins.put(id, target); locals.remove(id); }
    }
    private record FunctionSpec(RootCallTarget target, CaptureLayout captureLayout, int[] captures) {}
    private final class OperandBuilder {
        private final FrameLayout layout;
        private final List<LocalBinding> bindings = new ArrayList<>();
        private final List<Integer> temporaries = new ArrayList<>();
        OperandBuilder(FrameLayout layout) { this.layout = layout; }
        Expr operand(Expr value) {
            CoreRepresentation proof = value.getRepresentation();
            if (proof.isTypedTransport()) {
                TupleShape shape = new TupleShape(proof, (thc.Language) language);
                int[] slots = new int[shape.getWidth()];
                for (int i = 0; i < slots.length; i++) { slots[i] = layout.bind("<async operand field " + i + ">"); temporaries.add(slots[i]); }
                bindings.add(new LocalBinding(-1, value, false, slots));
                return proof.isVector() ? new VectorLocalRead(shape, slots) : new TupleLocalRead(shape, slots);
            }
            FrameSlotKind kind = proof.isInt() ? FrameSlotKind.Int : proof.isLong() ? FrameSlotKind.Long :
                proof.isFloat() ? FrameSlotKind.Float : proof.isDouble() ? FrameSlotKind.Double :
                proof.isEvaluatedReference() ? FrameSlotKind.Object : FrameSlotKind.Illegal;
            int slot = layout.bind("<async operand " + bindings.size() + ">", kind);
            temporaries.add(slot);
            bindings.add(new LocalBinding(slot, value, proof.isLong()));
            return new LocalRead(slot, false).proven(proof);
        }
        Expr finish(Expr body) {
            return bindings.isEmpty() ? body : new AstOperands(bindings.toArray(LocalBinding[]::new), ints(temporaries), body);
        }
    }
    private <T> T withSource(CoreSourceLocation location, Supplier<T> action) {
        CoreSourceLocation previous = currentSource;
        currentSource = location;
        try { return action.get(); }
        finally { currentSource = previous; }
    }
    private CoreSourceLocation rootSource(Expr body) {
        CoreSourceLocation result = body.getCoreSourceLocation() != null ? body.getCoreSourceLocation() : currentSource;
        if (result != null && sources.getEnabled()) attachedRootCount++;
        return result;
    }

    private void validateBindings(List<Map<String, Object>> requested) {
        if (requested.isEmpty()) return;
        if (capturesContinuations) AstAsyncAdmission.validate(requested);
        ArrayOp.validateApplications(requested);
        CoreStackForeign.validateHeads(requested);
        CoreStackInfoForeign.validateHeads(requested);
        CoreOriginalStdio.INSTANCE.validateHeads(requested);
        CoreProcessForeign.validateHeads(requested);
        CoreStablePointers.validateHeads(requested);
        CoreRtsShutdown.INSTANCE.validateHeads(requested);
        CoreMainThreadForeign.validateHeads(requested);
        CoreBoundThreadForeign.validateHeads(requested);
        CoreStringRtsForeign.validateHeads(requested);
        CoreEnvironmentForeign.validateHeads(requested);
        CoreRtsDiagnosticForeign.validateHeads(requested);
        CoreRtsArgumentsForeign.validateHeads(requested);
        CoreManagedFiles.validateHeads(requested);
        CoreMd5Foreign.validateHeads(requested);
        CoreGmpForeign.validateHeads(requested);
        CoreLibdwForeign.validateHeads(requested);
        CoreNativeAllocationForeign.validateHeads(requested);
        CoreMemoryCopyForeign.MEMMOVE.validateHeads(requested);
        CoreMemoryCopyForeign.MEMCPY.validateHeads(requested);
        CoreSignalForeign.validateHeads(requested);
        if (!diagnosticUnsupported) {
            CoreRepresentations.INSTANCE.validateAggregates(requested, constructors);
            Objects.requireNonNull(validateInputs).accept(requested);
        }
    }
    private Expr initializer(Map<String, Object> binding, Scope scope) {
        CoreRepresentations.INSTANCE.requireNoSum(CoreRepresentations.INSTANCE.binder(binding), "global binding");
        return withSource(sources.binding(binding, null), () -> {
            List<Object> expr = (List<Object>) binding.get("expr");
            CoreRepresentations.INSTANCE.requireNoSum(CoreRepresentations.INSTANCE.expression(expr), "global binding");
            if (demand != null && !representation(binding) && !Arrays.asList("lit", "void").contains(expr.getFirst()))
                throw new UnsupportedCore("Demand loading does not yet support effectful strict global initialization");
            if (representation(binding) && (!Arrays.asList("lam", "lit", "con", "void").contains(expr.getFirst()) ||
                demand != null && !"lam".equals(expr.getFirst()))) return delay(expr, scope, (String) binding.get("name"));
            return argument(expr, scope, representation(binding), (String) binding.get("name"), false, representation(binding));
        });
    }
    private int bindingIndex(String name) {
        Integer direct = indices.get(name);
        if (direct != null) return direct;
        List<Integer> named = names.get(name);
        if (named != null && named.size() == 1) return named.getFirst();
        List<Integer> matching = null;
        boolean ambiguous = false;
        for (var entry : names.entrySet()) {
            String key = entry.getKey();
            if (!key.substring(key.lastIndexOf('.') + 1).equals(name)) continue;
            if (matching != null) { ambiguous = true; break; }
            matching = entry.getValue();
        }
        if (!ambiguous && matching != null && matching.size() == 1) return matching.getFirst();
        throw new RuntimeFault("Unknown or ambiguous entry " + name);
    }
    @Override public synchronized RootCallTarget hostEntryTarget(int arity) {
        RootCallTarget target = hostEntries.get(arity);
        if (target == null) { target = new EntryRoot(language, arity, metrics).getCallTarget(); hostEntries.put(arity, target); }
        return target;
    }
    @Override public Object entryValue(String name) {
        if (!indices.containsKey(name) && demand != null && demand.contains(name)) return required(globals, name).read();
        return required(globals, (String) bindings.get(bindingIndex(name)).get("id")).read();
    }
    @Override public DataLayout constructorLayout(String id) { return dataLayout(id); }
    @Override public RootCallTarget entryTarget(String name) {
        Object value = entryValue(name);
        while (value instanceof Thunk thunk && thunk.getState() == 2) value = thunk.getValue();
        if (value instanceof Closure closure) return closure.target;
        if (value instanceof Thunk thunk && thunk.getTarget() != null) return thunk.getTarget();
        return hostEntryTarget(0);
    }
    @Override public Map<String, Object> diagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("backend", "ast");
        result.put("asyncExceptions", enableAsync);
        result.put("sourceNotesEnabled", sources.getEnabled());
        result.put("sourceSpanCount", sources.getSpanCount());
        result.put("sourceRootCount", attachedRootCount);
        result.put("loweredRootCount", constructedRootCount);
        result.put("hostEntryRootCount", hostEntries.size());
        result.put("initializedBindingCount", initializedBindingCount);
        result.put("instrumented", metrics.getEnabled());
        result.put("thunkEvaluationsByLabel", metrics.thunkCountsSnapshot());
        result.put("compiledEntries", metrics.getCompiledEntries());
        result.put("leadingCaseReturns", metrics.getLeadingCaseReturns());
        result.put("thunkEvaluations", metrics.getThunkEvaluations());
        result.put("thunkHits", metrics.getThunkHits());
        result.put("blackholes", metrics.getBlackholes());
        result.put("directCacheMisses", metrics.getDirectCacheMisses());
        result.put("indirectCalls", metrics.getIndirectCalls());
        result.put("tailBounces", metrics.getTailBounces());
        result.put("papAllocations", metrics.getPapAllocations());
        result.put("localJoinTransfers", metrics.getLocalJoinTransfers());
        result.put("selfTailReentries", metrics.getSelfTailReentries());
        result.put("trampolineIterations", metrics.getTrampolineIterations());
        String unsupportedPolicy = "reject-at-load";
        if (diagnosticUnsupported) unsupportedPolicy = "diagnostic-traps";
        else for (Map<String, Object> binding : bindings) if (binding.get("expr") instanceof thc.CoreBindingBody) {
            unsupportedPolicy = "reject-at-binding-admission"; break;
        }
        result.put("unsupportedPolicy", unsupportedPolicy);
        result.put("deferredUnsupported", new ArrayList<>(deferredUnsupported));
        result.put("unsupportedTraps", metrics.getUnsupportedTraps());
        result.put("frames", "indexed primitive slots; selective StaticShape captures");
        result.put("stackPolicy", capturesContinuations ? "tail-safe; bounded AST activation chains; active STM spilling unsupported" :
            "tail-safe; non-tail calls and nested thunk forcing use host stack");
        result.put("threadPolicy", enableAsync ? "context-owned Java threads; captured asynchronous delivery" :
            "context-owned Java threads; external asynchronous delivery disabled");
        if (loadingStatistics != null) result.putAll(loadingStatistics.get());
        return result;
    }
    private boolean representation(Map<String, Object> binding) {
        if (!(binding.get("lifted") instanceof Boolean lifted)) throw new UnsupportedCore("Unknown levity for " + binding.get("id"));
        return lifted;
    }
    private FunctionSpec function(String label, List<Map<String, Object>> args, List<Object> expression, Scope outer) {
        return function(label, args, expression, outer, CoreRepresentations.INSTANCE.expression(expression),
            new boolean[args.size()], FunctionRootRole.FUNCTION, true);
    }
    private FunctionSpec function(String label, List<Map<String, Object>> args, List<Object> expression, Scope outer,
                                  CoreRepresentation resultProof, boolean[] entryStrict, FunctionRootRole role, boolean bodyTail) {
        if (entryStrict.length != args.size()) throw new RuntimeFault("Function entry contract arity mismatch");
        Scope scope = new Scope(new FrameLayout());
        Set<String> free = coreFreeVariables(expression);
        Set<String> argumentIds = new LinkedHashSet<>();
        for (Map<String, Object> arg : args) argumentIds.add((String) arg.get("id"));
        for (Map<String, Object> arg : args) CoreRepresentations.INSTANCE.requireInput(CoreRepresentations.INSTANCE.binder(arg));
        List<CoreRepresentation> inputProofs = new ArrayList<>();
        for (Map<String, Object> arg : args) inputProofs.add(CoreRepresentations.INSTANCE.binder(arg));
        ArgumentLayout inputLayout = ArgumentLayout.fromProofs(inputProofs);
        List<String> freeLocals = new ArrayList<>();
        for (String id : free) if (!argumentIds.contains(id) && outer.locals.containsKey(id)) freeLocals.add(id);
        for (String id : freeLocals) {
            Local local = required(outer.locals, id);
            if (local.slot < 0 && local.proof.getKind() == CoreKind.VOID) scope.bindVoid(id, local.proof);
        }
        List<String> captured = new ArrayList<>();
        for (String id : freeLocals) {
            Local local = required(outer.locals, id);
            if (local.slot >= 0 || local.proof.getKind() != CoreKind.VOID) captured.add(id);
        }
        List<Local> captureFields = new ArrayList<>();
        List<Integer> captureDestinations = new ArrayList<>();
        List<int[]> vectorDestinations = new ArrayList<>();
        for (String id : captured) {
            Local local = required(outer.locals, id);
            if (local.proof.isTypedTransport()) {
                CoreRepresentations.INSTANCE.requireInput(local.proof);
                List<CoreRepresentation> fields = ArgumentLayout.leaves(local.proof);
                int[] from = local.tupleSlots;
                if (local.cell || from == null || from.length != fields.size())
                    throw new UnsupportedCore("Aggregate capture requires exact typed locals");
                int[] destinations = new int[fields.size()];
                for (int i = 0; i < fields.size(); i++) {
                    CoreRepresentation field = fields.get(i);
                    int destination = scope.layout.bind(id + " captured field " + i, outlinedSlotKind(field, false));
                    captureFields.add(new Local(from[i], field.isLong(), field, false, null, field.isVector() ? new int[] {from[i]} : null, null));
                    vectorDestinations.add(field.isVector() ? new int[] {destination} : null);
                    captureDestinations.add(destination);
                    destinations[i] = destination;
                }
                scope.bindTuple(id, local.proof, destinations);
            } else {
                CoreRepresentations.INSTANCE.requireScalar(local.proof, "capture");
                captureFields.add(local);
                vectorDestinations.add(null);
                captureDestinations.add(scope.bind(id, local.primitive, local.proof, local.cell, local.entry,
                    local.arityCertificate, outlinedSlotKind(local.proof, local.cell)).slot);
            }
        }
        int[] captureSources = new int[captureFields.size()];
        for (int i = 0; i < captureSources.length; i++) {
            Local local = captureFields.get(i);
            captureSources[i] = local.proof.isVector() ? Objects.requireNonNull(local.tupleSlots)[0] : local.slot;
        }
        int[] environmentSlots = ints(captureDestinations);
        int[][] environmentVectorSlots = vectorDestinations.toArray(int[][]::new);
        List<Integer> argumentSlots = new ArrayList<>(), argumentIndices = new ArrayList<>();
        List<CoreRepresentation> argumentProofs = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            Map<String, Object> arg = args.get(i);
            boolean lifted = representation(arg);
            CoreRepresentation proof = CoreRepresentations.INSTANCE.binder(arg);
            if (lifted) proof = evaluated(proof, entryStrict[i]);
            if (proof.isTypedTransport()) {
                if (lifted) throw new RuntimeFault("Typed formal cannot be lifted");
                List<CoreRepresentation> fields = ArgumentLayout.leaves(proof);
                int[] slots = new int[fields.size()];
                for (int leaf = 0; leaf < slots.length; leaf++) slots[leaf] = scope.layout.bind(arg.get("id") + " typed input " + leaf);
                scope.bindTuple((String) arg.get("id"), proof, slots);
                for (int leaf = 0; leaf < fields.size(); leaf++) {
                    argumentIndices.add(ArgumentLayout.offset(inputLayout, i) + leaf);
                    argumentProofs.add(fields.get(leaf));
                    argumentSlots.add(slots[leaf]);
                }
            } else if (free.contains(arg.get("id")) || capturesContinuations && entryStrict[i]) {
                argumentIndices.add(ArgumentLayout.offset(inputLayout, i));
                argumentProofs.add(proof);
                argumentSlots.add(scope.bind((String) arg.get("id"), !lifted && !Boolean.TRUE.equals(arg.get("coercion")), proof).slot);
            }
        }
        CaptureLayout captures = null;
        if (!captureFields.isEmpty()) {
            if (language == null) throw new IllegalArgumentException("Required value was null.");
            int n = captureFields.size();
            CoreRepresentation[] vectors = new CoreRepresentation[n];
            boolean[] eligible = new boolean[n], exactLong = new boolean[n], exactFloat = new boolean[n], exactDouble = new boolean[n];
            Class<?>[] references = new Class<?>[n];
            NarrowInteger[] narrow = new NarrowInteger[n];
            for (int i = 0; i < n; i++) {
                Local field = captureFields.get(i);
                CoreRepresentation proof = field.proof;
                vectors[i] = proof.isVector() ? proof : null;
                eligible[i] = !proof.isVector() && field.primitive;
                exactLong[i] = !field.cell && proof.isLong() && proof.getEvaluated();
                references[i] = field.cell ? null : proof.referenceCarrier();
                exactFloat[i] = !field.cell && proof.isFloat() && proof.getEvaluated();
                exactDouble[i] = !field.cell && proof.isDouble() && proof.getEvaluated();
                narrow[i] = !field.cell && proof.getEvaluated() ? proof.getNarrowInteger() : null;
            }
            captures = CaptureLayout.withVectors(language, vectors, eligible, exactLong, references, exactFloat, exactDouble, narrow);
        }
        int[] allArgumentSlots = new int[args.size()];
        Arrays.fill(allArgumentSlots, -1);
        CoreRepresentation[] allArgumentProofs = new CoreRepresentation[args.size()];
        Arrays.fill(allArgumentProofs, CoreRepresentation.Companion.getUNKNOWN());
        for (int i = 0; i < args.size(); i++) {
            Local local = scope.locals.get(args.get(i).get("id"));
            if (local != null && !local.proof.isTypedTransport()) { allArgumentSlots[i] = local.slot; allArgumentProofs[i] = local.proof; }
        }
        if (role == FunctionRootRole.FUNCTION) scope.self = new AstSelfLayout(captures, environmentSlots,
            allArgumentSlots, allArgumentProofs, entryStrict.clone(), inputLayout, environmentVectorSlots);
        Expr body = compile(expression, scope, bodyTail);
        HandoffEntry handoff = null;
        if (!capturesContinuations && (inputLayout == null || !inputLayout.getRequiresTyped())) {
            List<CoreRepresentation> declared = new ArrayList<>();
            for (Map<String, Object> arg : args) declared.add(CoreRepresentations.INSTANCE.binder(arg));
            handoff = HandoffEntry.create(language, scope.layout, declared, resultProof, captures != null);
        }
        if ((body.getRepresentation().isSum() || resultProof.isSum()) && (!body.getRepresentation().isSum() || !resultProof.isSum()))
            throw new RuntimeFault("Sum function requires exact body and declared result proofs");
        CoreRepresentation effectiveResult = body.getRepresentation().refine(resultProof);
        TupleShape tuple = effectiveResult.isTypedTransport() ? new TupleShape(effectiveResult, (thc.Language) language) : null;
        int[] tupleSlots = new int[tuple == null ? 0 : tuple.getWidth()];
        for (int i = 0; i < tupleSlots.length; i++) tupleSlots[i] = scope.layout.bind("<typed return " + i + ">");
        FunctionRoot root = new FunctionRoot(language, scope.layout.build(), label, captures, environmentSlots,
            ints(argumentSlots), ints(argumentIndices), body, metrics, argumentProofs.toArray(CoreRepresentation[]::new), resultProof,
            rootSource(body), entryStrict, handoff, tuple, tupleSlots, inputLayout, enableAsync, environmentVectorSlots,
            delimited, role, outlineCaseArms);
        constructedRootCount++;
        root.configureForeignExceptionBridge(foreignExceptionBridge);
        if (language instanceof thc.Language thc) root.configureTypedInput(TypedInputLayout.create(thc, inputLayout, captures != null));
        if (role == FunctionRootRole.FUNCTION && !capturesContinuations && body instanceof Case && inputLayout == null) {
            Set<String> used = new LinkedHashSet<>(free);
            used.retainAll(argumentIds);
            root.configureLeadingCaseReturn(LeadingCaseReturn.discover(args, expression, resultProof, root.getEntryArgumentOffset(),
                used, captures != null, this::dataLayout, sources, body.getCoreSourceLocation()));
        }
        return new FunctionSpec(root.getCallTarget(), captures, captureSources);
    }
    private Expr caseArm(List<Object> expression, Scope scope, boolean tail) {
        boolean hasLocalJoin = false;
        if (outlineCaseArms && Arrays.asList("app", "case", "let").contains(expression.getFirst()))
            for (String free : coreFreeVariables(expression)) if (scope.joins.containsKey(free)) { hasLocalJoin = true; break; }
        if (!outlineCaseArms || !Arrays.asList("app", "case", "let").contains(expression.getFirst()) || hasLocalJoin)
            return compile(expression, scope, tail);
        OperandBuilder operands = operandBuilder;
        operandBuilder = null;
        FunctionSpec fn;
        try { fn = function("case arm", List.of(), expression, scope, CoreRepresentations.INSTANCE.expression(expression),
            new boolean[0], FunctionRootRole.PASS_THROUGH, tail); }
        finally { operandBuilder = operands; }
        return new AstCaseArm(fn.target, fn.captureLayout, fn.captures, tail).located(currentSource);
    }
    private FrameSlotKind outlinedSlotKind(CoreRepresentation proof, boolean cell) {
        if (!outlineCaseArms) return FrameSlotKind.Illegal;
        if (cell || proof.isVector()) return FrameSlotKind.Object;
        if (!proof.getEvaluated()) return FrameSlotKind.Illegal;
        if (proof.isInt()) return FrameSlotKind.Int;
        if (proof.isLong()) return FrameSlotKind.Long;
        if (proof.isFloat()) return FrameSlotKind.Float;
        if (proof.isDouble()) return FrameSlotKind.Double;
        if (proof.isEvaluatedReference()) return FrameSlotKind.Object;
        return FrameSlotKind.Illegal;
    }
    private Expr delay(List<Object> expr, Scope scope, String label) {
        FunctionSpec fn = function(label, List.of(), expr, scope);
        TupleShape shape = ((GuestRoot) fn.target.getRootNode()).getTupleResult();
        if (shape != null) CoreRepresentations.INSTANCE.requireScalar(shape.getProof(), "thunk");
        return new Delay(fn.target, fn.captureLayout, fn.captures).proven(evaluated(CoreRepresentations.INSTANCE.expression(expr), false))
            .located(sources.expression(expr, currentSource));
    }
    private Expr argument(List<Object> expr, Scope scope, boolean lifted) { return argument(expr, scope, lifted, "argument thunk", false, lifted); }
    private Expr argument(List<Object> expr, Scope scope, boolean lifted, String label, boolean allowEmpty, boolean declaredLifted) {
        OperandBuilder operands = operandBuilder;
        operandBuilder = null;
        Expr result;
        try { result = argumentUnsequenced(expr, scope, lifted, label, allowEmpty, declaredLifted); }
        finally { operandBuilder = operands; }
        return operands == null ? result : operands.operand(result);
    }
    private void checkArgument(CoreRepresentation proof, boolean allowEmpty, boolean declaredLifted) {
        if (allowEmpty || proof.isVector()) CoreRepresentations.INSTANCE.requireInput(proof);
        else CoreRepresentations.INSTANCE.requireScalar(proof, "argument");
        if (proof.isTypedTransport() && declaredLifted) throw new RuntimeFault("Typed argument cannot be lifted");
    }
    private Expr argumentUnsequenced(List<Object> expr, Scope scope, boolean lifted, String label, boolean allowEmpty, boolean declaredLifted) {
        CoreRepresentation proof = CoreRepresentations.INSTANCE.expression(expr);
        checkArgument(proof, allowEmpty, declaredLifted);
        Local local = "var".equals(expr.getFirst()) ? scope.locals.get(expr.get(1)) : null;
        CoreRepresentation lexical = local == null ? null : local.proof;
        if (lexical != null) checkArgument(lexical, allowEmpty, declaredLifted);
        if (proof.isTypedTransport() || lexical != null && lexical.isTypedTransport()) {
            if (declaredLifted) throw new RuntimeFault("Typed argument cannot be lifted");
            Expr result = compile(expr, scope, false);
            if (!result.getRepresentation().isTypedTransport()) throw new RuntimeFault("Missing exact typed argument proof");
            return result;
        }
        if (!lifted) {
            Expr result = compile(expr, scope, false);
            checkArgument(result.getRepresentation(), allowEmpty, declaredLifted);
            return new Evaluate(result, metrics);
        }
        List<?> head = at(expr, 1) instanceof List<?> candidate && "app".equals(expr.getFirst()) &&
            "var".equals(at(candidate, 0)) ? candidate : null;
        String headId = head != null && at(head, 1) instanceof String id ? id : null;
        CoreApplicationCertificates.Arity certificate = headId == null ? null :
            scope.locals.containsKey(headId) ? required(scope.locals, headId).arityCertificate : globalArityCertificates.get(headId);
        boolean unopenedHead = headId != null && !scope.locals.containsKey(headId) && !globalArityCertificates.containsKey(headId) &&
            demand != null && demand.contains(headId);
        if (!unopenedHead && CoreApplicationCertificates.eagerApplication(expr, certificate)) {
            Expr result = compile(expr, scope, false);
            checkArgument(result.getRepresentation(), allowEmpty, declaredLifted);
            return result;
        }
        Expr result = Arrays.asList("var", "lit", "lam", "con", "prim", "void").contains(expr.getFirst()) ?
            compile(expr, scope, false) : delay(expr, scope, label);
        checkArgument(result.getRepresentation(), allowEmpty, declaredLifted);
        return result;
    }
    private Object literal(String kind, Object encoded, CoreRepresentation proof) {
        if (encoded instanceof CoreFloatingLiteral floating) return floating.decode(kind);
        if (!(encoded instanceof String value)) throw new UnsupportedCore("Malformed Core literal payload");
        return switch (kind) {
            case "rubbish" -> {
                if (proof == null) throw new IllegalArgumentException("Required value was null.");
                yield rubbishLiterals.decode(proof);
            }
            case "int8" -> ProgramKt.int8Literal(value);
            case "int16" -> ProgramKt.int16Literal(value);
            case "int32" -> ProgramKt.int32Literal(value);
            case "int64" -> ProgramKt.int64Literal(value);
            case "word64" -> word64Literal(value);
            case "int", "char" -> Long.parseLong(value);
            case "word" -> Long.parseUnsignedLong(value);
            case "float" -> Float.parseFloat(value);
            case "double" -> Double.parseDouble(value);
            case "word8", "word16", "word32" -> ProgramKt.narrowWordLiteral(kind, value);
            case "string-bytes" -> ManagedAddress.Companion.fromHex(value);
            case "null-addr" -> {
                if (!value.equals("0")) throw new UnsupportedCore("Malformed null Addr# literal");
                yield ManagedAddress.Companion.nullAddress();
            }
            case "function-addr" -> CFinalizerLabels.INSTANCE.fromCore(value, proof);
            case "data-addr" -> CoreDataLabels.fromCore(value, proof, stackTargetLayout instanceof TargetLayout target ? target : null);
            case "bignat" -> BigNatLiterals.decode(value);
            default -> throw new UnsupportedCore("Unsupported literal kind " + kind);
        };
    }
    private Expr compile(List<Object> expr, Scope scope, boolean tail) {
        List<Object> inline = CoreStateApplications.inline(expr);
        if (inline != null) return compile(inline, scope, tail);
        OperandBuilder outer = operandBuilder;
        Object head = at(expr, 1) instanceof List<?> value ? at(value, 0) : null;
        OperandBuilder operands = capturesContinuations && "app".equals(at(expr, 0)) && Arrays.asList("prim", "con").contains(head) ?
            new OperandBuilder(scope.layout) : null;
        operandBuilder = operands;
        Expr result;
        try {
            result = withSource(sources.expression(expr, currentSource), () -> {
                Expr node = compileLocated(expr, scope, tail).located(currentSource);
                return (operands == null ? node : operands.finish(node)).located(currentSource);
            });
        } finally { operandBuilder = outer; }
        return outer == null ? result : outer.operand(result);
    }
    private Expr compileLocated(List<Object> expr, Scope scope, boolean tail) {
        try {
            Expr lowered = compileSupported(expr, scope, tail);
            CoreRepresentation metadata = diagnosticUnsupported && lowered instanceof GlobalRead ? CoreRepresentation.Companion.getUNKNOWN() :
                CoreRepresentations.INSTANCE.expression(expr);
            return lowered.proven(lowered.getRepresentation().refine(evaluated(metadata, false)));
        } catch (UnsupportedCore gap) {
            if (!diagnosticUnsupported) throw gap;
            String message = gap.getMessage() != null ? gap.getMessage() : "Unsupported Core";
            deferredUnsupported.add(message);
            Expr body = new UnsupportedExpression(message, metrics).located(currentSource);
            RootCallTarget target = new FunctionRoot(language, new FrameLayout().build(), "unsupported: " + message, null,
                new int[0], new int[0], new int[0], body, metrics, new CoreRepresentation[0], body.getRepresentation(),
                rootSource(body), new boolean[0], null, null, new int[0], null, false, new int[0][], false,
                FunctionRootRole.FUNCTION, outlineCaseArms).getCallTarget();
            constructedRootCount++;
            return new DiagnosticUnavailable(target, message, metrics);
        }
    }

    private Expr compileSumCase(List<Object> expr, Expr scrutinee, CoreRepresentation proof, Scope scope, boolean tail) {
        TupleShape shape = new TupleShape(proof, (thc.Language) language);
        int[] slots = new int[shape.getWidth()];
        for (int i = 0; i < slots.length; i++) slots[i] = scope.layout.bind("<sum case " + i + ">");
        scope.bindTuple((String) expr.get(2), proof, slots);
        Set<Integer> tags = new LinkedHashSet<>();
        int fallback = -1;
        List<List<Object>> alternatives = (List<List<Object>>) expr.get(3);
        Expr[] arms = new Expr[alternatives.size()];
        for (int index = 0; index < alternatives.size(); index++) {
            List<Object> alt = alternatives.get(index);
            Scope child = scope.child();
            List<Integer> conversionSlots = new ArrayList<>();
            List<Expr> conversionValues = new ArrayList<>();
            List<String> ids = (List<String>) alt.get(2);
            if ("default".equals(alt.getFirst())) {
                if (fallback >= 0 || !ids.isEmpty() || !CoreRepresentations.INSTANCE.alternativeBinders(alt).isEmpty())
                    throw new RuntimeFault("Invalid sum DEFAULT alternative");
                fallback = index;
            } else {
                if (!"data".equals(alt.getFirst()) || ids.size() != 1) throw new RuntimeFault("Invalid sum alternative");
                int tag = SumShape.INSTANCE.constructor(proof, constructors.get(alt.get(1)), ids.size());
                if (!tags.add(tag)) throw new RuntimeFault("Duplicate sum alternative tag");
                CoreRepresentation component = Objects.requireNonNull(proof.getAlternatives()).get(tag - 1);
                List<Map<String, Object>> metadata = CoreRepresentations.INSTANCE.alternativeBinders(alt);
                if (metadata.size() != 1 || !Objects.equals(metadata.getFirst().get("id"), ids.getFirst()))
                    throw new RuntimeFault("Missing sum payload binder proof");
                CoreRepresentation actual = CoreRepresentations.INSTANCE.binder(metadata.getFirst());
                if (!(metadata.getFirst().get("lifted") instanceof Boolean lifted)) throw new RuntimeFault("Unknown sum payload binder levity");
                SumShape.INSTANCE.payload(component, actual, lifted);
                CoreRepresentation field = evaluated(component.refine(actual), component.getEvaluated());
                List<CoreRepresentation> leaves = TupleShape.Companion.flatten(field);
                List<Integer> physical = SumShape.INSTANCE.projection(proof, tag - 1);
                int[] projection = new int[physical.size()];
                for (int i = 0; i < physical.size(); i++) {
                    CoreRepresentation leaf = leaves.get(i);
                    if (!leaf.isInt()) projection[i] = slots[physical.get(i)];
                    else {
                        int destination = child.layout.bind("<narrow sum arm " + i + ">");
                        conversionSlots.add(destination);
                        conversionValues.add(new SumNarrowRead(slots[physical.get(i)], Objects.requireNonNull(leaf.getNarrowInteger()), leaf));
                        projection[i] = destination;
                    }
                }
                if (component.isTypedTransport()) child.bindTuple(ids.getFirst(), field, projection);
                else if (component.getKind() == CoreKind.VOID) child.bindVoid(ids.getFirst(), field);
                else child.bindSlot(ids.getFirst(), new Local(projection[0], component.isLong(), field, false, null, null, null));
            }
            Expr body = caseArm((List<Object>) alt.get(3), child, tail);
            arms[index] = conversionSlots.isEmpty() ? body : new Let(ints(conversionSlots), conversionValues.toArray(Expr[]::new),
                new boolean[conversionSlots.size()], body, false);
        }
        if (arms.length == 0) throw new RuntimeFault("Empty sum case");
        int[] selected = new int[Objects.requireNonNull(proof.getAlternatives()).size()];
        for (int i = 0; i < selected.length; i++) {
            selected[i] = fallback;
            for (int arm = 0; arm < alternatives.size(); arm++) {
                List<Object> alt = alternatives.get(arm);
                if (!"data".equals(alt.getFirst())) continue;
                Map<String, Object> constructor = constructors.get(alt.get(1));
                if (constructor != null && constructor.get("tag") instanceof Number number && number.intValue() == i + 1) {
                    selected[i] = arm; break;
                }
            }
        }
        CoreRepresentation result = arms[0].getRepresentation().refine(CoreRepresentations.INSTANCE.expression(expr));
        for (Expr arm : arms) result.refine(arm.getRepresentation());
        List<CoreRepresentation> armProofs = new ArrayList<>();
        boolean allEvaluated = true;
        for (Expr arm : arms) { armProofs.add(arm.getRepresentation()); allEvaluated = allEvaluated && arm.getRepresentation().getEvaluated(); }
        CoreRepresentations.INSTANCE.validateFloatingCaseResult(result, armProofs);
        return new SumCase(scrutinee, slots, arms, selected, evaluated(result, allEvaluated));
    }
    private Expr compileVectorReadCase(VectorReadCase read, Scope scope, boolean tail) {
        Scope local = scope.child();
        local.bindVoid(read.getStateBinder(), CoreVectorMemory.INSTANCE.getStateProof());
        CoreRepresentation proof = read.getOperation().getVectorProof();
        int[] lanes = new int[TupleShape.Companion.flatten(proof).size()];
        for (int i = 0; i < lanes.length; i++) lanes[i] = local.layout.bind("<vector read lane " + i + ">");
        local.bindTuple(read.getVectorBinder(), proof, lanes);
        Expr[] operands = new Expr[read.getArguments().size()];
        for (int i = 0; i < operands.length; i++) operands[i] = compile(read.getArguments().get(i), scope, false);
        Expr value = (read.getOperation().isAddress() ? new VectorAddressExpression(read.getOperation(), operands) :
            new VectorByteArrayExpression(read.getOperation(), operands)).located(currentSource);
        Expr body = compile(read.getBody(), local, tail);
        return new Let(new int[] {-1}, new Expr[] {value}, new boolean[] {false}, body, false, new int[][] {lanes});
    }
    private Expr compileVectorCase(List<Object> expr, Expr scrutinee, CoreRepresentation proof, Scope scope, boolean tail) {
        CoreRepresentations.INSTANCE.requireInput(proof);
        List<List<Object>> alternatives = (List<List<Object>>) expr.get(3);
        if (alternatives.size() != 1) throw new RuntimeFault("Vector case requires one default alternative");
        List<Object> only = alternatives.getFirst();
        if (!"default".equals(only.getFirst()) || !((List<?>) only.get(2)).isEmpty())
            throw new RuntimeFault("Vector case requires one default alternative");
        int[] lanes = new int[TupleShape.Companion.flatten(proof).size()];
        for (int i = 0; i < lanes.length; i++) lanes[i] = scope.layout.bind("<vector case lane " + i + ">");
        scope.bindTuple((String) expr.get(2), proof, lanes);
        return new Let(new int[] {-1}, new Expr[] {scrutinee}, new boolean[] {false},
            compile((List<Object>) only.get(3), scope, tail), false, new int[][] {lanes});
    }
    private Expr compileTupleCase(List<Object> expr, Expr scrutinee, CoreRepresentation proof, Scope local, boolean tail) {
        TupleShape shape = new TupleShape(proof, (thc.Language) language);
        int[] slots = new int[shape.getWidth()];
        for (int i = 0; i < slots.length; i++) slots[i] = local.layout.bind("<tuple case " + i + ">");
        local.bindTuple((String) expr.get(2), proof, slots);
        List<List<Object>> alternatives = (List<List<Object>>) expr.get(3);
        if (alternatives.isEmpty()) return new TupleCase(scrutinee, slots, new EmptyCaseResult(CoreRepresentations.INSTANCE.expression(expr)));
        if (alternatives.size() != 1) throw new RuntimeFault("Tuple case requires at most one alternative");
        List<Object> alt = alternatives.getFirst();
        List<String> ids = (List<String>) alt.get(2);
        if ("data".equals(alt.getFirst())) {
            Map<String, Object> constructor = constructors.get(alt.get(1));
            if (constructor == null || !"unboxed-tuple".equals(constructor.get("kind")) || ids.size() != shape.getComponents().length ||
                !(constructor.get("arity") instanceof Number number) || number.intValue() != ids.size())
                throw new RuntimeFault("Tuple alternative shape mismatch");
            List<Map<String, Object>> metadata = CoreRepresentations.INSTANCE.alternativeBinders(alt);
            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i);
                CoreRepresentation component = shape.getComponents()[i];
                CoreRepresentation raw = i < metadata.size() ? CoreRepresentations.INSTANCE.binder(metadata.get(i)) : component;
                TupleShape.Companion.requireCompatible(component, raw, true);
                CoreRepresentation field = component.refine(raw);
                int width = TupleShape.Companion.flatten(component).size(), offset = shape.getOffsets()[i];
                if (component.isTypedTransport()) local.bindTuple(id, evaluated(component, true), Arrays.copyOfRange(slots, offset, offset + width));
                else if (component.getKind() == CoreKind.VOID) local.bindVoid(id, field);
                else local.bindSlot(id, new Local(slots[offset], component.isLong(), evaluated(field, component.isLong() || component.getEvaluated()), false, null, null, null));
            }
        } else if (!"default".equals(alt.getFirst()) || !ids.isEmpty()) throw new RuntimeFault("Invalid tuple alternative");
        return new TupleCase(scrutinee, slots, caseArm((List<Object>) alt.get(3), local, tail));
    }
    private Expr joinJump(LocalJoinTarget target, List<List<Object>> args, List<?> flags, Scope scope) {
        return joinJump(target, args, flags, scope, new boolean[args.size()]);
    }
    private Expr joinJump(LocalJoinTarget target, List<List<Object>> args, List<?> flags, Scope scope, boolean[] callStrict) {
        if (args.size() != target.getSlots().length) throw new RuntimeFault("Local join arity mismatch");
        Expr[] nodes = new Expr[args.size()];
        for (int i = 0; i < nodes.length; i++) {
            if (!(at(flags, i) instanceof Boolean lifted)) throw new RuntimeFault("Missing join argument levity");
            nodes[i] = argument(args.get(i), scope, lifted && !callStrict[i] && !target.getEntryStrict()[i],
                "argument thunk", target.getProofs()[i].isTypedTransport(), lifted);
            CoreRepresentations.INSTANCE.requireJoinArgument(target.getProofs()[i], nodes[i].getRepresentation());
        }
        int[][] typedTemps = new int[nodes.length][];
        int[] temps = new int[nodes.length];
        for (int i = 0; i < nodes.length; i++) {
            if (target.getProofs()[i].isTypedTransport()) {
                typedTemps[i] = new int[ArgumentLayout.leaves(target.getProofs()[i]).size()];
                for (int j = 0; j < typedTemps[i].length; j++) typedTemps[i][j] = scope.layout.bind("<join typed argument " + i + " field " + j + ">");
                temps[i] = -1;
            } else temps[i] = scope.layout.bind("<join argument " + i + ">");
        }
        return new LocalJoinCall((thc.Language) language, target, nodes, temps, metrics, typedTemps);
    }
    private Expr compileJoins(List<Object> expr, Scope outer, boolean tail, List<CoreJoinDefinition> definitions) {
        boolean recursive = Boolean.TRUE.equals(expr.get(1));
        Set<String> shadowed = new LinkedHashSet<>();
        if (recursive) for (CoreJoinDefinition definition : definitions) shadowed.add(definition.getId());
        for (CoreJoinDefinition definition : definitions) {
            for (Map<String, Object> parameter : definition.getParameters()) {
                CoreRepresentation proof = CoreRepresentations.INSTANCE.binder(parameter);
                CoreRepresentations.INSTANCE.requireInput(proof);
                if (proof.isTypedTransport() && representation(parameter)) throw new RuntimeFault("Typed join formal must be unlifted");
            }
            Set<String> formals = new LinkedHashSet<>();
            for (Map<String, Object> parameter : definition.getParameters()) formals.add((String) parameter.get("id"));
            for (String id : coreFreeVariables(definition.getBody())) {
                if (formals.contains(id) || shadowed.contains(id)) continue;
                Local captured = outer.locals.get(id);
                if (captured == null) continue;
                if (captured.proof.isTypedTransport()) {
                    CoreRepresentations.INSTANCE.requireInput(captured.proof);
                    int[] slots = captured.tupleSlots;
                    if (slots == null) throw new RuntimeFault("Missing typed join capture slots");
                    boolean invalid = slots.length != ArgumentLayout.leaves(captured.proof).size();
                    if (!invalid) for (int slot : slots) if (slot < 0) { invalid = true; break; }
                    if (invalid) throw new RuntimeFault("Typed join capture disagrees with its physical slots");
                } else CoreRepresentations.INSTANCE.requireScalar(captured.proof, "join capture");
            }
        }
        CoreJoins.INSTANCE.validate((List<Map<String, Object>>) expr.get(2), (List<Object>) expr.get(3), recursive);
        Object identity = new Object();
        Scope local = outer.child();
        List<boolean[]> entryContracts = new ArrayList<>();
        for (CoreJoinDefinition definition : definitions) entryContracts.add(CoreEntries.join(definition));
        List<Scope> bodyScopes = new ArrayList<>();
        for (CoreJoinDefinition definition : definitions) {
            Scope scope = local.child();
            boolean[] entryStrict = CoreEntries.join(definition);
            for (int i = 0; i < definition.getParameters().size(); i++) {
                Map<String, Object> parameter = definition.getParameters().get(i);
                boolean lifted = representation(parameter);
                CoreRepresentation proof = CoreRepresentations.INSTANCE.binder(parameter);
                if (lifted) proof = evaluated(proof, entryStrict[i]);
                if (proof.isTypedTransport()) {
                    int[] lanes = new int[ArgumentLayout.leaves(proof).size()];
                    for (int j = 0; j < lanes.length; j++) lanes[j] = scope.layout.bind(parameter.get("id") + " join typed field " + j);
                    scope.bindTuple((String) parameter.get("id"), evaluated(proof, true), lanes);
                } else scope.bind((String) parameter.get("id"), !lifted && !Boolean.TRUE.equals(parameter.get("coercion")), proof);
            }
            bodyScopes.add(scope);
        }
        List<LocalJoinTarget> targets = new ArrayList<>();
        for (int i = 0; i < definitions.size(); i++) {
            CoreJoinDefinition definition = definitions.get(i);
            int count = definition.getParameters().size();
            int[] slots = new int[count];
            CoreRepresentation[] proofs = new CoreRepresentation[count];
            int[][] typedSlots = new int[count][];
            for (int j = 0; j < count; j++) {
                Local parameter = required(bodyScopes.get(i).locals, (String) definition.getParameters().get(j).get("id"));
                slots[j] = parameter.slot; proofs[j] = parameter.proof;
                typedSlots[j] = parameter.proof.isTypedTransport() ? parameter.tupleSlots : null;
            }
            targets.add(new LocalJoinTarget(identity, i + 1, slots, proofs, entryContracts.get(i), definition.getResult(), typedSlots));
        }
        for (int i = 0; i < definitions.size(); i++) local.bindJoin(definitions.get(i).getId(), targets.get(i));
        if (recursive) for (int i = 0; i < bodyScopes.size(); i++) {
            Set<String> parameters = new LinkedHashSet<>();
            for (Map<String, Object> parameter : definitions.get(i).getParameters()) parameters.add((String) parameter.get("id"));
            for (int j = 0; j < definitions.size(); j++) if (!parameters.contains(definitions.get(j).getId()))
                bodyScopes.get(i).bindJoin(definitions.get(j).getId(), targets.get(j));
        }
        Expr entry = compile((List<Object>) expr.get(3), local, tail);
        List<Expr> bodies = new ArrayList<>();
        for (int i = 0; i < definitions.size(); i++) {
            CoreJoinDefinition definition = definitions.get(i);
            Scope bodyScope = bodyScopes.get(i);
            bodies.add(withSource(sources.binding(definition.getBinding(), currentSource), () -> {
                Expr node = compile(definition.getBody(), bodyScope, tail);
                node.setRepresentation(node.getRepresentation().refine(evaluated(definition.getResult(), false)));
                return node;
            }));
        }
        CoreRepresentation result = entry.getRepresentation().refine(evaluated(CoreRepresentations.INSTANCE.expression(expr), false));
        for (Expr body : bodies) TupleShape.Companion.requireCompatible(result, body.getRepresentation(), false);
        boolean allEvaluated = entry.getRepresentation().getEvaluated();
        for (Expr body : bodies) allEvaluated = allEvaluated && body.getRepresentation().getEvaluated();
        result = evaluated(result, allEvaluated);
        TupleShape tuple = result.isTypedTransport() ? new TupleShape(result, (thc.Language) language) : null;
        int[] tupleSlots = new int[tuple == null ? 0 : tuple.getWidth()];
        for (int i = 0; i < tupleSlots.length; i++) tupleSlots[i] = local.layout.bind("<join tuple result " + i + ">");
        Expr[] nodes = new Expr[bodies.size() + 1]; nodes[0] = entry;
        for (int i = 0; i < bodies.size(); i++) nodes[i + 1] = bodies.get(i);
        return new LocalJoinRegion(identity, local.layout.bind("<join selector>"), local.layout.bind("<join result>"),
            nodes, result, recursive, tuple, tupleSlots, delimited);
    }
    private DataLayout dataLayout(String id) {
        DataLayout layout = dataLayouts.get(id);
        if (layout != null) return layout;
        Map<String, Object> info = constructors.get(id);
        if (info == null) throw new RuntimeFault("Missing constructor metadata " + id);
        CoreFields fields = new CoreFields(info);
        if (language == null) throw new RuntimeFault("Constructor layout requires a guest language");
        layout = DataLayout.Companion.fromFields$org_intelligence_thc(language, id, (String) info.get("name"), fields);
        dataLayouts.put(id, layout);
        return layout;
    }
    private Expr primitive(String name, Expr[] args, boolean someException) {
        NarrowScalarOp narrow = NarrowScalarOp.named(name);
        if (narrow != null) return new NarrowScalarExpression(name, narrow, args);
        Expr floating = FloatingPrimitives.floatingPrimitive(name, args);
        if (floating != null) return floating;
        return switch (name) {
            case "reallyUnsafePtrEquality#" -> { requirePrimitiveArity(name, args, 2); yield new PointerEquality(args[0], args[1]); }
            case "raise#" -> { requirePrimitiveArity(name, args, 1); yield new RaiseException(args[0], someException); }
            case "addr2Int#", "int2Addr#" -> {
                requirePrimitiveArity(name, args, 1);
                yield name.equals("addr2Int#") ? new AddressToInt(args[0]) : new IntToAddress(args[0]);
            }
            case "eqAddr#", "neAddr#" -> { requirePrimitiveArity(name, args, 2); yield new CompareManagedAddress(args[0], args[1], name.equals("neAddr#")); }
            case "ltAddr#", "leAddr#", "gtAddr#", "geAddr#" -> {
                requirePrimitiveArity(name, args, 2);
                ManagedAddressOrder order = switch (name) {
                    case "ltAddr#" -> ManagedAddressOrder.LT;
                    case "leAddr#" -> ManagedAddressOrder.LE;
                    case "gtAddr#" -> ManagedAddressOrder.GT;
                    default -> ManagedAddressOrder.GE;
                };
                yield new CompareOrderedManagedAddress(args[0], args[1], order);
            }
            case "minusAddr#", "remAddr#" -> {
                requirePrimitiveArity(name, args, 2);
                yield name.equals("minusAddr#") ? new SubtractManagedAddress(args[0], args[1]) : new RemainderManagedAddress(args[0], args[1]);
            }
            case "plusAddr#", "indexCharOffAddr#", "indexWord8OffAddr#", "indexInt8OffAddr#" -> {
                requirePrimitiveArity(name, args, 2);
                yield name.equals("plusAddr#") ? new PlusManagedAddress(args[0], args[1]) :
                    name.equals("indexCharOffAddr#") ? new IndexManagedScalarAddress(ManagedAddressRead.CHAR, args[0], args[1]) :
                    new IndexManagedByte(name.equals("indexInt8OffAddr#"), args[0], args[1]);
            }
            case "indexWord16OffAddr#", "indexInt16OffAddr#" -> {
                requirePrimitiveArity(name, args, 2);
                yield new IndexManagedScalarAddress(name.equals("indexInt16OffAddr#") ? ManagedAddressRead.INT16 : ManagedAddressRead.WORD16, args[0], args[1]);
            }
            default -> new Primitive(name, args);
        };
    }
    private static void requirePrimitiveArity(String name, Expr[] args, int arity) {
        if (args.length != arity) throw new RuntimeFault("Primitive arity mismatch: " + name);
    }
    private boolean[] strictConstructorFields(String id, int arity) {
        Map<String, Object> info = constructors.get(id);
        if (info == null) throw new RuntimeFault("Missing constructor metadata " + id);
        if (info.get("kind") != null && !"boxed".equals(info.get("kind")))
            throw new UnsupportedCore("Unsupported constructor representation " + info.get("kind") + ": " + id);
        if (((Number) info.get("arity")).intValue() != arity) throw new RuntimeFault("Constructor arity mismatch: " + id);
        if (!(info.get("strictFields") instanceof List<?> strict)) throw new RuntimeFault("Missing constructor strictness metadata: " + id);
        if (!(info.get("fieldLifted") instanceof List<?> lifted)) throw new RuntimeFault("Missing constructor representation metadata: " + id);
        if (strict.size() != arity || lifted.size() != arity) throw new RuntimeFault("Constructor metadata length mismatch: " + id);
        boolean[] result = new boolean[arity];
        for (int i = 0; i < arity; i++) {
            if (!(strict.get(i) instanceof Boolean strictField)) throw new RuntimeFault("Unknown constructor field strictness: " + id);
            if (strictField) {
                if (!(lifted.get(i) instanceof Boolean liftedField))
                    throw new UnsupportedCore("Unknown strict constructor field levity: " + id + " field " + i);
                result[i] = liftedField;
            }
        }
        return result;
    }
    private int[][] constructorVectorSlots(DataLayout layout, FrameLayout frame) {
        int[][] result = new int[layout.getLogicalArity$org_intelligence_thc()][];
        for (int i = 0; i < result.length; i++) {
            CoreRepresentation proof = layout.logicalProof$org_intelligence_thc(i);
            if (proof != null && proof.isAggregate() || layout.isVector(layout.fieldOffset$org_intelligence_thc(i))) {
                result[i] = new int[layout.logicalWidth$org_intelligence_thc(i)];
                for (int j = 0; j < result[i].length; j++) result[i][j] = frame.bind("<constructor " + layout.getId() + " field " + i + " lane " + j + ">");
            }
        }
        return result;
    }
    private Expr construct(String id, Expr[] args, FrameLayout frame) {
        boolean[] strict = strictConstructorFields(id, args.length);
        Expr[] fields = new Expr[args.length];
        for (int i = 0; i < fields.length; i++) fields[i] = strict[i] ? new Evaluate(args[i], metrics) : args[i];
        DataLayout layout = dataLayout(id);
        return new Construct(layout, fields, constructorVectorSlots(layout, frame));
    }

    private Expr compileSupported(List<Object> expr, Scope scope, boolean tail) {
        return switch ((String) expr.get(0)) {
            case "var" -> {
                String id = (String) expr.get(1);
                CoreRepresentation occurrence = CoreRepresentations.INSTANCE.expression(expr);
                Local local = scope.locals.get(id);
                CoreRepresentation proof = local != null ? local.proof : globalProofs.get(id);
                if (proof == null && demand != null) proof = demand.occurrence(id, occurrence);
                CoreVectors.INSTANCE.requireVariableProof(proof, occurrence);
                if (scope.joins.containsKey(id)) yield joinJump(scope.joins.get(id), List.of(), List.of(), scope);
                if (local != null) {
                    if (local.tupleSlots != null) {
                        TupleShape shape = new TupleShape(local.proof, (thc.Language) language);
                        yield local.proof.isVector() ? new VectorLocalRead(shape, local.tupleSlots) : new TupleLocalRead(shape, local.tupleSlots);
                    }
                    if (local.slot < 0 && local.proof.getKind() == CoreKind.VOID) yield new Literal(kotlin.Unit.INSTANCE).proven(local.proof);
                    yield new LocalRead(local.slot, local.cell).proven(local.proof);
                }
                GlobalBinding global = globals.get(id);
                if (global != null) yield new GlobalRead(global).proven(proof != null ? proof : required(globalProofs, id));
                throw new UnsupportedCore("Unresolved external binding " + id);
            }
            case "lit" -> {
                String tag = (String) expr.get(1);
                Literal value = new Literal(literal(tag, expr.get(2), CoreRepresentations.INSTANCE.expression(expr)));
                if (Arrays.asList("int8", "word8", "int16", "word16", "int32", "word32").contains(tag))
                    yield value.proven(CoreRepresentations.INSTANCE.narrowLiteralProof(expr));
                if (tag.equals("bignat")) yield value.proven(BigNatLiterals.proof(expr));
                if (tag.equals("rubbish")) yield value.proven(RubbishLiterals.proof(expr));
                yield value;
            }
            case "void" -> new Literal(kotlin.Unit.INSTANCE);
            case "lam" -> {
                List<Map<String, Object>> args = (List<Map<String, Object>>) expr.get(1);
                StringJoiner names = new StringJoiner(", ");
                for (Map<String, Object> arg : args) names.add(String.valueOf(arg.get("name")));
                FunctionSpec fn = function("lambda " + names, args, (List<Object>) expr.get(2), scope,
                    CoreRepresentations.INSTANCE.lambdaResult(expr), CoreEntries.lambda(expr), FunctionRootRole.FUNCTION, true);
                yield new MakeClosure(fn.target, args.size(), fn.captureLayout, fn.captures);
            }
            case "app" -> compileApplication(expr, scope, tail);
            case "let" -> compileLet(expr, scope, tail);
            case "case" -> compileCase(expr, scope, tail);
            case "con" -> compileConstructor(expr, scope);
            case "prim" -> throw new UnsupportedCore("Unsaturated primitive " + expr.get(1));
            default -> throw new UnsupportedCore("Unsupported Core node " + expr.get(0));
        };
    }

    private Expr compileLet(List<Object> expr, Scope scope, boolean tail) {
        boolean recursive = (Boolean) expr.get(1);
        List<Map<String, Object>> group = (List<Map<String, Object>>) expr.get(2);
        var definitions = CoreJoins.INSTANCE.definitions(group);
        if (definitions != null) return compileJoins(expr, scope, tail, definitions);
        for (Map<String, Object> binding : group) {
            CoreRepresentation proof = CoreRepresentations.INSTANCE.binder(binding);
            if (proof.isVector()) {
                if (recursive || representation(binding)) throw new UnsupportedCore("Vector let binding must be nonrecursive and unlifted");
                CoreRepresentations.INSTANCE.requireInput(proof);
            } else CoreRepresentations.INSTANCE.requireScalar(proof, "let binding");
        }
        Scope local = scope.child();
        int[][] vectorSlots = new int[group.size()][];
        int[] slots = new int[group.size()];
        for (int index = 0; index < group.size(); index++) {
            Map<String, Object> binding = group.get(index);
            CoreRepresentation proof = CoreRepresentations.INSTANCE.binder(binding);
            if (proof.isVector()) {
                List<CoreRepresentation> fields = TupleShape.Companion.flatten(proof);
                int[] lanes = new int[fields.size()];
                for (int i = 0; i < lanes.length; i++) lanes[i] = local.layout.bind(binding.get("id") + " vector let lane " + i);
                vectorSlots[index] = lanes;
                slots[index] = local.bindTuple((String) binding.get("id"), proof, lanes).slot;
            } else slots[index] = local.bind((String) binding.get("id"), !representation(binding),
                evaluated(proof, false), recursive, CoreEntries.binding(binding),
                CoreApplicationCertificates.binding(binding), FrameSlotKind.Illegal).slot;
        }
        Expr[] rhs = new Expr[group.size()];
        for (int i = 0; i < rhs.length; i++) {
            Map<String, Object> binding = group.get(i);
            rhs[i] = withSource(sources.binding(binding, currentSource), () -> {
                List<Object> body = (List<Object>) binding.get("expr");
                boolean lifted = representation(binding);
                CoreRepresentations.INSTANCE.requireNoSum(CoreRepresentations.INSTANCE.expression(body), "let binding");
                if (recursive && !lifted) throw new UnsupportedCore("Recursive unlifted binding unsupported");
                Expr node = recursive && lifted && !Arrays.asList("lam", "lit", "con", "void").contains(body.get(0))
                    ? delay(body, local, String.valueOf(binding.get("name")))
                    : argument(body, recursive ? local : scope, lifted, String.valueOf(binding.get("name")), false, lifted);
                return node.proven(node.getRepresentation().refine(evaluated(CoreRepresentations.INSTANCE.binder(binding), false)));
            });
        }
        boolean[] unlifted = new boolean[group.size()];
        for (int i = 0; i < group.size(); i++) {
            local.publish((String) group.get(i).get("id"), rhs[i].getRepresentation());
            unlifted[i] = !representation(group.get(i));
        }
        return new Let(slots, rhs, unlifted, compile((List<Object>) expr.get(3), local, tail), recursive, vectorSlots);
    }

    private Expr compileCase(List<Object> expr, Scope scope, boolean tail) {
        var vectorRead = CoreVectorMemory.INSTANCE.readCase(expr, constructors);
        if (vectorRead != null) return compileVectorReadCase(vectorRead, scope, tail);
        List<Object> scrutineeExpr = (List<Object>) expr.get(1);
        Expr scrutinee = compile(scrutineeExpr, scope, false);
        Scope local = scope.child();
        if ("var".equals(scrutineeExpr.get(0))) {
            String id = (String) scrutineeExpr.get(1);
            Local binding = local.locals.get(id);
            if (binding != null) local.refine(id, evaluated(binding.proof, true));
        }
        CoreRepresentation binderProof = evaluated(scrutinee.getRepresentation().refine(
            evaluated(CoreRepresentations.INSTANCE.caseBinder(expr), false)), true);
        if (binderProof.isSum()) return compileSumCase(expr, scrutinee, binderProof, local, tail);
        if (binderProof.isTuple()) return compileTupleCase(expr, scrutinee, binderProof, local, tail);
        if (binderProof.isVector()) return compileVectorCase(expr, scrutinee, binderProof, local, tail);
        int binder = local.bind((String) expr.get(2), !binderProof.getPresent() || binderProof.isLong(), binderProof,
            false, null, null, outlinedSlotKind(binderProof, false)).slot;
        List<List<Object>> rawAlternatives = (List<List<Object>>) expr.get(3);
        Alternative[] alternatives = new Alternative[rawAlternatives.size()];
        List<CoreRepresentation> results = new ArrayList<>();
        List<Integer> kinds = new ArrayList<>();
        boolean allLong = true, anyAggregate = false, anyScalar = false;
        for (int a = 0; a < alternatives.length; a++) {
            List<Object> alt = rawAlternatives.get(a);
            Scope child = local.child();
            String kind = (String) alt.get(0);
            Object value;
            if (kind.equals("lit")) {
                List<String> lit = (List<String>) alt.get(1);
                if (Set.of("bignat", "rubbish").contains(lit.get(0))) throw new UnsupportedCore("BigNat/rubbish literal alternatives are invalid GHC Core");
                if (Set.of("float", "double").contains(lit.get(0))) throw new UnsupportedCore("Floating literal alternatives are invalid GHC Core");
                value = literal(lit.get(0), lit.get(1), null);
            } else value = kind.equals("data") ? dataLayout((String) alt.get(1)) : alt.get(1);
            List<String> ids = (List<String>) alt.get(2);
            DataLayout layout = value instanceof DataLayout found ? found : null;
            if (layout != null && layout.getLogicalArity$org_intelligence_thc() != ids.size()) throw new RuntimeFault("Constructor field/binder mismatch");
            var metadata = CoreRepresentations.INSTANCE.alternativeBinders(alt);
            boolean[] strict = kind.equals("data") ? strictConstructorFields((String) alt.get(1), ids.size()) : null;
            int[][] vectorFields = new int[layout != null ? layout.getArity() : ids.size()][];
            List<Integer> slots = new ArrayList<>();
            for (int index = 0; index < ids.size(); index++) {
                String id = ids.get(index);
                Map<String, Object> meta = at(metadata, index);
                CoreRepresentation raw = meta != null ? CoreRepresentations.INSTANCE.binder(meta) : CoreRepresentation.Companion.getUNKNOWN();
                int physical = layout != null ? layout.fieldOffset$org_intelligence_thc(index) : index;
                CoreRepresentation aggregate = layout != null ? layout.logicalProof$org_intelligence_thc(index) : null;
                if (aggregate != null && !aggregate.isAggregate()) aggregate = null;
                CoreRepresentation vector = aggregate == null && layout != null ? layout.vectorProof$org_intelligence_thc(physical) : null;
                if (aggregate != null) {
                    if (!raw.getPresent() || meta == null || !Boolean.FALSE.equals(meta.get("lifted")))
                        throw new RuntimeFault("Aggregate constructor binder requires an unlifted shape");
                    TupleShape.Companion.requireCompatible(aggregate, raw, true);
                    CoreRepresentation proof = aggregate.refine(raw);
                    int[] lanes = new int[layout.logicalWidth$org_intelligence_thc(index)];
                    for (int i = 0; i < lanes.length; i++) lanes[i] = child.layout.bind(id + " constructor aggregate " + i);
                    child.bindTuple(id, proof, lanes);
                    for (int i = 0; i < lanes.length; i++) {
                        if (layout.isVector(physical + i)) vectorFields[physical + i] = new int[]{lanes[i]};
                        slots.add(lanes[i]);
                    }
                } else {
                    CoreRepresentation proof;
                    if (vector != null) proof = raw.refine(vector);
                    else if (layout != null && layout.isLong(physical)) proof = raw.refine(new CoreRepresentation(CoreKind.LONG, true, false, null, null, null, null, null, null));
                    else {
                        Map<String, Object> constructor = alt.get(1) instanceof String idKey ? constructors.get(idKey) : null;
                        List<?> lifted = constructor != null && constructor.get("fieldLifted") instanceof List<?> found ? found : null;
                        proof = evaluated(raw, strict != null && strict[index] || lifted != null && Boolean.FALSE.equals(at(lifted, index)));
                    }
                    if (vector != null) {
                        if (meta == null || !Boolean.FALSE.equals(meta.get("lifted"))) throw new UnsupportedCore("Vector constructor binder must be unlifted");
                        int[] lanes = {child.layout.bind(id + " constructor vector")};
                        vectorFields[physical] = lanes;
                        slots.add(child.bindTuple(id, proof, lanes).slot);
                    } else slots.add(child.bind(id, layout != null && layout.isLong(physical), proof).slot);
                }
            }
            int tag = switch (kind) {
                case "default" -> DEFAULT_ALTERNATIVE;
                case "data" -> DATA_ALTERNATIVE;
                case "lit" -> LITERAL_ALTERNATIVE;
                default -> throw new RuntimeFault("Invalid Core alternative kind " + kind);
            };
            Expr body = caseArm((List<Object>) alt.get(3), child, tail);
            alternatives[a] = new Alternative(tag, value, ints(slots), body, vectorFields);
            results.add(body.getRepresentation()); kinds.add(tag);
            allLong &= tag != LITERAL_ALTERNATIVE || value instanceof Long;
            anyAggregate |= body.getRepresentation().isAggregate();
            anyScalar |= !body.getRepresentation().isAggregate();
        }
        CoreRepresentation declared = CoreRepresentations.INSTANCE.expression(expr);
        if (!declared.isAggregate() && anyAggregate && anyScalar) throw new RuntimeFault("Missing exact aggregate case result proof");
        CoreRepresentations.INSTANCE.validateDeclaredCaseResult(declared, results);
        CoreRepresentations.INSTANCE.validateAggregateCaseResult(declared, results);
        CoreRepresentations.INSTANCE.validateFloatingCaseResult(declared, results);
        return switch (CaseCategoriesKt.caseCategory(binderProof, kinds, allLong)) {
            case DATA -> new DataCase(scrutinee, binder, alternatives, metrics, binderProof, delimited);
            case LONG -> new LongCase(scrutinee, binder, alternatives, metrics, binderProof, delimited);
            case DEFAULT_ONLY -> new DefaultCase(scrutinee, binder, alternatives, metrics, binderProof, delimited);
            case GENERIC -> new Case(scrutinee, binder, alternatives, metrics, null, delimited);
        };
    }

    private Expr compileConstructor(List<Object> expr, Scope scope) {
        String id = (String) expr.get(1);
        int arity = ((Number) expr.get(2)).intValue();
        Map<String, Object> info = constructors.get(id);
        CoreRepresentation proof = CoreRepresentations.INSTANCE.expression(expr);
        if (info != null && "unboxed-tuple".equals(info.get("kind")) && arity == 0 && proof.isTuple()) {
            if (proof.getComponents() == null || !proof.getComponents().isEmpty()) throw new RuntimeFault("Empty tuple constructor has nonempty logical components");
            return new TupleConstruct(new TupleShape(proof, (thc.Language) language), new Expr[0]);
        }
        if (arity == 0) return construct(id, new Expr[0], scope.layout);
        FrameLayout layout = new FrameLayout();
        DataLayout constructor = dataLayout(id);
        if (constructor.getHasAggregateFields$org_intelligence_thc()) throw new UnsupportedCore("Unsaturated aggregate-field constructor requires aggregate inputs");
        Object fieldData = required(constructors, id).get("fieldTypes");
        List<?> fieldTypes = fieldData instanceof List<?> found ? found : null;
        List<CoreRepresentation> proofs = new ArrayList<>();
        for (int i = 0; i < arity; i++) proofs.add(fieldTypes != null && fieldTypes.get(i) != null ?
            CoreRepresentations.INSTANCE.parse(fieldTypes.get(i)) : CoreRepresentation.Companion.getUNKNOWN());
        ArgumentLayout inputLayout = ArgumentLayout.fromProofs(proofs);
        List<Integer> slots = new ArrayList<>(), indices = new ArrayList<>();
        List<CoreRepresentation> argumentProofs = new ArrayList<>();
        Expr[] fields = new Expr[arity];
        for (int index = 0; index < arity; index++) {
            CoreRepresentation vector = constructor.vectorProof$org_intelligence_thc(index);
            if (vector == null) {
                int slot = layout.bind("field" + index);
                slots.add(slot); indices.add(ArgumentLayout.offset(inputLayout, index)); argumentProofs.add(proofs.get(index));
                fields[index] = new LocalRead(slot, false).proven(proofs.get(index));
            } else {
                int[] lanes = {layout.bind("field" + index + " vector")};
                List<CoreRepresentation> leaves = TupleShape.Companion.flatten(vector);
                for (int lane = 0; lane < leaves.size(); lane++) {
                    slots.add(lanes[lane]); indices.add(ArgumentLayout.offset(inputLayout, index) + lane); argumentProofs.add(leaves.get(lane));
                }
                fields[index] = new VectorLocalRead(new TupleShape(vector, (thc.Language) language), lanes);
            }
        }
        Expr body = construct(id, fields, layout);
        FunctionRoot root = new FunctionRoot(language, layout.build(), "constructor " + id, null, new int[0],
            ints(slots), ints(indices), body, metrics, argumentProofs.toArray(CoreRepresentation[]::new), body.getRepresentation(), rootSource(body),
            strictConstructorFields(id, arity), null, null, new int[0], inputLayout, false, new int[0][], false,
            FunctionRootRole.FUNCTION, outlineCaseArms);
        constructedRootCount++;
        root.configureForeignExceptionBridge(foreignExceptionBridge);
        if (language instanceof thc.Language guest) root.configureTypedInput(TypedInputLayout.create(guest, inputLayout, false));
        return new MakeClosure(root.getCallTarget(), arity, null, new int[0]);
    }


    private CoreRepresentation bindingProof(List<Object> argument, Scope scope) {
        if (!"var".equals(argument.get(0))) return null;
        Local local = scope.locals.get(argument.get(1));
        return local != null ? local.proof : globalProofs.get(argument.get(1));
    }
    private List<Object> argumentMetadata(List<List<Object>> args) {
        List<Object> result = new ArrayList<>(args.size());
        for (List<Object> arg : args) result.add(metadataRepresentation(arg));
        return result;
    }
    private Object metadataRepresentation(List<Object> expr) {
        Map<String, Object> metadata = CoreRepresentations.INSTANCE.metadata(expr);
        return metadata == null ? null : metadata.get("rep");
    }
    private List<CoreRepresentation> argumentProofs(List<List<Object>> args) {
        List<CoreRepresentation> result = new ArrayList<>(args.size());
        for (List<Object> arg : args) result.add(CoreRepresentations.INSTANCE.expression(arg));
        return result;
    }
    private List<CoreRepresentation> loweredProofs(Expr[] args) {
        List<CoreRepresentation> result = new ArrayList<>(args.length);
        for (Expr arg : args) result.add(arg.getRepresentation());
        return result;
    }
    private Expr[] compileOperands(List<List<Object>> args, Scope scope) {
        Expr[] result = new Expr[args.size()];
        for (int i = 0; i < result.length; i++) result[i] = compile(args.get(i), scope, false);
        return result;
    }
    private Expr[] argumentOperands(List<List<Object>> args, Scope scope, List<?> flags) {
        Expr[] result = new Expr[args.size()];
        for (int i = 0; i < result.length; i++) result[i] = argument(args.get(i), scope, (Boolean) flags.get(i));
        return result;
    }
    private Expr[] argumentOperands(List<List<Object>> args, Scope scope, boolean lifted) {
        Expr[] result = new Expr[args.size()];
        for (int i = 0; i < result.length; i++) result[i] = argument(args.get(i), scope, lifted);
        return result;
    }
    private Expr compileApplication(List<Object> expr, Scope scope, boolean tail) {
        List<Object> fn = (List<Object>) expr.get(1);
        List<List<Object>> args = (List<List<Object>>) expr.get(2);
        if (!(at(expr, 3) instanceof List<?> flags)) throw new RuntimeFault("Application lacks representation flags");
        if (flags.size() != args.size()) throw new RuntimeFault("Application representation flag count mismatch");
        boolean[] callStrict = CoreCallDemands.lowerApplication(expr, callDemandsEnabled);
        CoreRepresentation tupleProof = CoreRepresentations.INSTANCE.expression(expr);
        boolean primitive = "prim".equals(fn.get(0));
        var tupleOperation = primitive ? TupleArithmeticOp.named((String) fn.get(1)) : null;
        var floatDecode = primitive ? FloatDecodeOp.named((String) fn.get(1)) : null;
        var metadata = CoreRepresentations.INSTANCE.metadata(expr);
        boolean defined = "var".equals(fn.get(0)) &&
            (scope.locals.containsKey(fn.get(1)) || scope.joins.containsKey(fn.get(1)) ||
                (demand != null && metadata != null && metadata.containsKey("foreignCall") ?
                    demand.isDefined((String) fn.get(1)) : globals.containsKey(fn.get(1))));
        var cpuAffinity = CoreCpuAffinity.validate(expr, defined || scope.joins.containsKey(at(fn, 1)));
        var runtimeService = CoreRuntimeServices.validate(expr, defined || scope.joins.containsKey(at(fn, 1)));
        var packageScalar = cpuAffinity == null && runtimeService == null ?
            CorePackageScalarForeign.INSTANCE.validate(metadata, argumentMetadata(args), flags, metadataRepresentation(expr), packageScalarLinks) : null;
        var foreignMetadata = packageScalar == null ? metadata : null;
        var stackClone = CoreStackForeign.validate(foreignMetadata, argumentMetadata(args), flags);
        var stackInfo = CoreStackInfoForeign.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var originalStdio = CoreOriginalStdio.INSTANCE.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var originalProcess = CoreProcessForeign.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var capi = CoreCapiForeign.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr), foreignLinks);
        var stableFree = CoreStablePointers.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var sharedCAF = CoreSharedCAFStores.INSTANCE.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var shutdown = CoreRtsShutdown.INSTANCE.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var mainThreadForeign = CoreMainThreadForeign.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var boundThreadForeign = CoreBoundThreadForeign.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr), false);
        var gcForeign = CoreGcForeign.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var rtsEventForeign = CoreRtsEventForeign.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var allocationCounterForeign = CoreBoundThreadForeign.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr), true);
        var stringRts = CoreStringRtsForeign.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var floatingForeign = CoreFloatForeign.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var textForeign = CoreTextForeign.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var environment = CoreEnvironmentForeign.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var rtsDiagnostic = CoreRtsDiagnosticForeign.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var rtsArguments = CoreRtsArgumentsForeign.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var managedFile = CoreManagedFiles.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));

        var javascript = packageScalar == null && !stackClone && stackInfo == null && originalStdio == null && managedFile == null
            ? CoreJavaScript.INSTANCE.validate(expr, defined) : null;
        var md5 = javascript == null ? CoreMd5Foreign.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr)) : null;
        var gmp = CoreGmpForeign.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var processSignal = CoreSignalForeign.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var nativeAllocation = CoreNativeAllocationForeign.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var memmove = CoreMemoryCopyForeign.MEMMOVE.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var memcpy = CoreMemoryCopyForeign.MEMCPY.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var byteStringSort = CoreByteStringSort.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var byteStringDecimal = CoreByteStringDecimal.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var byteStringUtf8 = CoreByteStringUtf8Foreign.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var memset = CoreMemsetForeign.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var memorySearch = CoreMemorySearchForeign.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var libdw = CoreLibdwForeign.validate(foreignMetadata, argumentMetadata(args), flags, metadataRepresentation(expr));

        var polyglot = originalProcess == null && rtsEventForeign == null && gcForeign == null && textForeign == null && !byteStringSort &&
            byteStringDecimal == null && byteStringUtf8 == null && memorySearch == null && floatingForeign == null && cpuAffinity == null &&
            runtimeService == null && !allocationCounterForeign && environment == null && packageScalar == null && !stackClone &&
            stackInfo == null && originalStdio == null && capi == null && !stableFree && shutdown == null && !mainThreadForeign &&
            !boundThreadForeign && stringRts == null && rtsDiagnostic == null && rtsArguments == null && sharedCAF == null &&
            managedFile == null && javascript == null && md5 == null && gmp == null && libdw == null && nativeAllocation == null &&
            !memmove && !memcpy && !memset && processSignal == null ? CorePolyglot.INSTANCE.validate(expr, defined) : null;
        if ((packageScalar != null || javascript != null || polyglot != null || runtimeService == RuntimeServiceCall.EXCEPTION_TEXT) &&
            foreignExceptionBridge == null) throw fault("Foreign execution requires a linked genuine THC.Exception runtime bundle");
        if (runtimeService != null) {
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreRuntimeServices.validateOperand(runtimeService, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            Expr result = switch (runtimeService) {
                case QUERY -> new RuntimeQueryExpression(1, operands[0], operands[1], operands[2], operands[3]);
                case CONTROL -> new RuntimeControlExpression(operands[0], operands[1], operands[2]);
                case TRACE -> new RuntimeTraceExpression(operands[0], operands[1], operands[2], operands[3], operands[4]);
                case EXCEPTION_TEXT -> new ExceptionTextExpression(operands[0], operands[1], operands[2], operands[3]);
            };
            return result.proven(evaluated(tupleProof, true));
        }
        if (cpuAffinity != null) {
            List<Object> stateArgument = single(args);
            Expr state = compile(stateArgument, scope, false);
            CoreBoundThreadForeign.validateOperand(state.getRepresentation(), bindingProof(stateArgument, scope));
            return new CpuAffinityQuery(cpuAffinity, state).proven(evaluated(tupleProof, true));
        }
        if (stackClone) {
            CoreStackForeign.validateHead(fn, defined);
            List<Object> state = single(args);
            CoreStackForeign.validateBinding(bindingProof(state, scope));
            Expr operand = compile(state, scope, false);
            CoreStackForeign.validateState(operand.getRepresentation());
            return new CloneStackExpression(operand, tupleProof);
        }
        if (stackInfo != null) {
            var layout = CoreStackInfoForeign.requireLayout(stackTargetLayout);
            CoreStackInfoForeign.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreStackInfoForeign.validateOperand(stackInfo, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new OriginalStackInfoExpression(stackInfo, layout, operands, tupleProof);
        }
        if (originalProcess != null) {
            CoreProcessForeign.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreProcessForeign.validateOperand(originalProcess, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new ProcessForeignExpression(originalProcess, operands, tupleProof);
        }
        if (originalStdio != null) {
            CoreOriginalStdio.INSTANCE.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                if (originalStdio.getProcessIdentity() || originalStdio.getEventDescriptor() || originalStdio.getWaitStatus() || originalStdio.getPathRemoval() || originalStdio.getFlagConstant() || originalStdio.getFcntl() || originalStdio.getReadiness() || originalStdio.getSeekConstant() || originalStdio.getStat() || originalStdio.getTermios() || originalStdio.getSigset() || originalStdio.getSavedTermios() || originalStdio.getReadImage() || originalStdio.getPathStat() || originalStdio.getPathMode() || originalStdio.getPathLink() || originalStdio.getCurrentDirectory() || originalStdio.getDirectoryStream() || originalStdio.getOpening() || originalStdio.getIconv() || originalStdio.getStrerror() || originalStdio.getDuplication() || originalStdio.getLocking() || originalStdio == OriginalStdioOp.SET_ERRNO || originalStdio == OriginalStdioOp.SIGPROCMASK || originalStdio == OriginalStdioOp.ACCESS || originalStdio == OriginalStdioOp.UNLINKAT || originalStdio == OriginalStdioOp.FSTATAT || originalStdio == OriginalStdioOp.TCSETATTR)
                    CoreOriginalStdio.INSTANCE.validateScalarOperand(originalStdio, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new OriginalStdioExpression(originalStdio, operands, tupleProof);
        }
        if (capi != null) {
            CoreCapiForeign.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreCapiForeign.validateOperand(capi, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new CapiExpression(capi, operands, tupleProof);
        }
        if (packageScalar != null) {
            CoreCapiForeign.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CorePackageScalarForeign.INSTANCE.validateOperand(packageScalar, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new PackageScalarExpression(packageScalar, operands, tupleProof);
        }
        if (stableFree) {
            CoreStablePointers.validateHead(fn, defined);
            return new FreeStablePointer(compile(args.get(0), scope, false), compile(args.get(1), scope, false)).proven(evaluated(tupleProof, true));
        }
        if (sharedCAF != null) {
            CoreSharedCAFStores.INSTANCE.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreSharedCAFStores.INSTANCE.validateOperand(i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new SharedCAFStoreExpression(sharedCAF, operands[0], operands[1]).proven(evaluated(tupleProof, true));
        }
        if (rtsArguments != null) {
            CoreRtsArgumentsForeign.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreRtsArgumentsForeign.validateOperand(rtsArguments, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new RtsArgumentsExpression(rtsArguments, operands, tupleProof);
        }
        if (rtsDiagnostic != null) {
            CoreRtsDiagnosticForeign.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreRtsDiagnosticForeign.validateOperand(rtsDiagnostic, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new RtsDiagnosticExpression(rtsDiagnostic, operands, tupleProof);
        }
        if (rtsEventForeign != null) {
            CoreRtsEventForeign.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreRtsEventForeign.validateOperand(rtsEventForeign, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new RtsEventForeignExpression(rtsEventForeign, operands, tupleProof);
        }
        if (gcForeign != null) {
            CoreGcForeign.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreGcForeign.validateOperand(gcForeign, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new GcForeignExpression(gcForeign, operands, tupleProof);
        }
        if (boundThreadForeign || allocationCounterForeign) {
            CoreBoundThreadForeign.validateHead(fn, defined);
            List<Object> argument = single(args);
            Expr state = compile(argument, scope, false);
            CoreBoundThreadForeign.validateOperand(state.getRepresentation(), bindingProof(argument, scope));
            return new BoundThreadSupport(state, allocationCounterForeign).proven(evaluated(tupleProof, true));
        }
        if (environment != null) {
            CoreEnvironmentForeign.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreEnvironmentForeign.validateOperand(environment, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new EnvironmentExpression(environment, operands, tupleProof);
        }
        if (textForeign != null) {
            CoreTextForeign.validateHead(fn, defined || scope.joins.containsKey(at(fn, 1)));
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreTextForeign.validateOperand(textForeign, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new TextForeignExpression(textForeign, operands, tupleProof);
        }
        if (floatingForeign != null) {
            CoreFloatForeign.validateHead(fn, defined || scope.joins.containsKey(at(fn, 1)));
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreFloatForeign.validateOperand(floatingForeign, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new FloatForeignExpression(floatingForeign, operands[0], operands[1], tupleProof);
        }
        if (stringRts != null) {
            CoreStringRtsForeign.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreStringRtsForeign.validateOperand(stringRts, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new StringRtsExpression(stringRts, operands, tupleProof);
        }
        if (shutdown != null) {
            CoreRtsShutdown.INSTANCE.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreRtsShutdown.INSTANCE.validateOperand(shutdown, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new ShutdownRuntime(shutdown, operands[0], operands[1], operands[2]).proven(evaluated(tupleProof, true));
        }
        if (mainThreadForeign) {
            CoreMainThreadForeign.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreMainThreadForeign.validateOperand(i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new RegisterMainThread(operands[0], operands[1]).proven(evaluated(tupleProof, true));
        }
        if (managedFile != null) {
            CoreManagedFiles.validateHead(fn, defined);
            return new ManagedFileExpression(managedFile, compileOperands(args, scope), tupleProof);
        }
        if (processSignal != null) {
            CoreSignalForeign.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreSignalForeign.validateOperand(processSignal, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new InstallProcessSignal(operands, tupleProof);
        }
        if (nativeAllocation != null) {
            CoreNativeAllocationForeign.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreNativeAllocationForeign.validateOperand(nativeAllocation, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new NativeAllocationExpression(nativeAllocation, operands, tupleProof);
        }
        if (byteStringSort) {
            CoreByteStringSort.validateHead(fn, defined || scope.joins.containsKey(at(fn, 1)));
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreByteStringSort.validateOperand(i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new ByteStringSortExpression(operands, tupleProof);
        }
        if (byteStringDecimal != null) {
            CoreByteStringDecimal.validateHead(fn, defined || scope.joins.containsKey(at(fn, 1)));
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreByteStringDecimal.validateOperand(byteStringDecimal, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new ByteStringDecimalExpression(byteStringDecimal, operands, tupleProof);
        }
        if (byteStringUtf8 != null) {
            CoreByteStringUtf8Foreign.validateHead(fn, defined || scope.joins.containsKey(at(fn, 1)));
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreByteStringUtf8Foreign.validateOperand(i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new ByteStringUtf8Expression(byteStringUtf8, operands, tupleProof);
        }
        if (memorySearch != null) {
            CoreMemorySearchForeign.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreMemorySearchForeign.validateOperand(memorySearch, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new MemorySearchExpression(memorySearch, operands, tupleProof);
        }
        if (memset) {
            CoreMemsetForeign.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreMemsetForeign.validateOperand(i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new MemsetExpression(operands, tupleProof);
        }
        if (memmove) {
            CoreMemoryCopyForeign.MEMMOVE.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreMemoryCopyForeign.MEMMOVE.validateOperand(i, operands[i].getRepresentation(), bindingProof(args.get(i), scope), false);
            }
            return new MemmoveExpression(operands, tupleProof);
        }
        if (memcpy) {
            CoreMemoryCopyForeign.MEMCPY.validateHead(fn, defined);
            boolean byteArrays = CoreMemoryCopyForeign.MEMCPY.byteArrays(foreignMetadata);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreMemoryCopyForeign.MEMCPY.validateOperand(i, operands[i].getRepresentation(), bindingProof(args.get(i), scope), byteArrays);
            }
            return new MemcpyExpression(operands, tupleProof, byteArrays);
        }
        if (libdw != null) {
            CoreLibdwForeign.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreLibdwForeign.validateOperand(libdw, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new OriginalLibdwExpression(libdw, operands, tupleProof);
        }
        if (gmp != null) {
            CoreGmpForeign.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreGmpForeign.validateOperand(gmp, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new GmpForeignExpression(gmp, operands, tupleProof);
        }
        if (md5 != null) {
            CoreMd5Foreign.validateHead(fn, defined);
            return new Md5ForeignExpression(md5, compileOperands(args, scope), tupleProof);
        }
        if (javascript != null) return new JavaScriptExpression(javascript, argumentOperands(args, scope, false)).proven(evaluated(tupleProof, true));
        if (polyglot != null) return new PolyglotExpression(polyglot, argumentOperands(args, scope, flags)).proven(evaluated(tupleProof, true));

        if (primitive && Set.of("newBCO#", "mkApUpd0#").contains(fn.get(1))) {
            String name = (String) fn.get(1);
            if (capturesContinuations) throw new UnsupportedCore("GHC BCO frames do not yet preserve AST captures");
            GhcBCO.INSTANCE.validate(name, argumentProofs(args), flags, tupleProof);
            return new GhcBCOExpression(name, argumentOperands(args, scope, flags), (thc.Language) language, metrics, tupleProof);
        }
        if (primitive && Set.of("newPromptTag#", "prompt#", "control0#").contains(fn.get(1))) {
            String name = (String) fn.get(1);
            DelimitedControl.validate(name, argumentProofs(args), flags, tupleProof);
            return new DelimitedPrimitive(name, new TupleShape(tupleProof, (thc.Language) language),
                argumentOperands(args, scope, flags), (thc.Language) language, metrics);
        }
        if (primitive && "tagToEnum#".equals(fn.get(1))) {
            if (args.size() != 1) throw new RuntimeFault("tagToEnum#: Exactly one operand required");
            Expr operand = compile(args.get(0), scope, false);
            var ids = CoreEnums.validate(expr, operand.getRepresentation(), constructors);
            DataValue[] values = new DataValue[ids.size()];
            for (int i = 0; i < values.length; i++) values[i] = dataLayout(ids.get(i)).allocate();
            return new TagToEnum(new EnumFamily(values), operand);
        }
        if (primitive && CoreDataTags.operations.contains(fn.get(1))) {
            if (args.size() != 1) throw new RuntimeFault("dataToTag: Exactly one operand required");
            Expr operand = argument(args.get(0), scope, false);
            var ids = CoreDataTags.validate(expr, operand.getRepresentation(), constructors);
            DataLayout[] layouts = new DataLayout[ids.size()];
            for (int i = 0; i < layouts.length; i++) layouts[i] = dataLayout(ids.get(i));
            return new DataToTag(new DataTagFamily(layouts), operand);
        }
        if (primitive && CoreVectors.INSTANCE.getOperations().contains(fn.get(1))) {
            String name = (String) fn.get(1);
            List<CoreRepresentation> proofs = new ArrayList<>();
            for (List<Object> arg : args) proofs.add(CoreVectors.INSTANCE.argumentProof(arg));
            CoreVectors.INSTANCE.validate(name, proofs, tupleProof);
            CoreVectors.INSTANCE.validateFlags(flags);
            Expr[] operands = compileOperands(args, scope);
            int[] shuffle = name.startsWith("shuffle") ? CoreVectors.INSTANCE.shuffleIndices(args.get(2), tupleProof.getVector().getLanes()) : null;
            if (GeneratedVectors.operations.contains(name))
                return GeneratedVectors.expression(name, operands, shuffle, count -> vectorSlots(scope, count, "<vector lane "));
            if (name.equals("packInt64X2#")) return new VectorPack(operands[0], vectorSlots(scope, 2, "<vector lane "));
            if (name.equals("unpackInt64X2#")) return new VectorUnpack(operands[0]);
            if (name.equals("packInt32X4#")) return new Vector32Pack(operands[0], vectorSlots(scope, 4, "<vector lane "));
            if (name.equals("unpackInt32X4#")) return new Vector32Unpack(operands[0]);
            if (name.equals("packDoubleX2#")) return new VectorDoublePack(operands[0], vectorSlots(scope, 2, "<double vector lane "));
            if (name.equals("unpackDoubleX2#")) return new VectorDoubleUnpack(operands[0]);
            if (CoreVectors.INSTANCE.getOperationsDouble().contains(name)) return new VectorDoubleOperation(name, operands);
            if (name.equals("packFloatX4#")) return new VectorFloatPack(operands[0], vectorSlots(scope, 4, "<float vector lane "));
            if (name.equals("unpackFloatX4#")) return new VectorFloatUnpack(operands[0]);
            if (CoreVectors.INSTANCE.getOperationsFloat().contains(name)) return new VectorFloatOperation(name, operands);
            if (CoreVectors.INSTANCE.getFusedFloat8().contains(name)) return new VectorFloat8Fused(name, operands);
            if (CoreVectors.INSTANCE.getFusedFloat16().contains(name)) return new VectorFloat16Fused(name, operands);
            if (CoreVectors.INSTANCE.getFusedDouble4().contains(name)) return new VectorDouble4Fused(name, operands);
            if (CoreVectors.INSTANCE.getFusedDouble8().contains(name)) return new VectorDouble8Fused(name, operands);
            if (CoreVectors.INSTANCE.getOperations32().contains(name)) return new Vector32Operation(name, operands);
            if (name.equals("packInt16X8#")) return new Vector16Pack(operands[0], vectorSlots(scope, 8, "<int16 vector lane "));
            if (name.equals("unpackInt16X8#")) return new Vector16Unpack(operands[0]);
            if (CoreVectors.INSTANCE.getOperations16().contains(name)) return new Vector16Operation(name, operands);
            if (name.equals("packInt8X16#")) return new Vector8Pack(operands[0], vectorSlots(scope, 16, "<int8 vector lane "));
            if (name.equals("unpackInt8X16#")) return new Vector8Unpack(operands[0]);
            if (CoreVectors.INSTANCE.getOperations8().contains(name)) return new Vector8Operation(name, operands);
            if (name.equals("packWord8X16#")) return new VectorWord8Pack(operands[0], vectorSlots(scope, 16, "<word8 vector lane "));
            if (name.equals("unpackWord8X16#")) return new VectorWord8Unpack(operands[0]);
            if (CoreVectors.INSTANCE.getOperationsWord8().contains(name)) return new VectorWord8Operation(name, operands);
            if (name.equals("packWord16X8#")) return new VectorWord16Pack(operands[0], vectorSlots(scope, 8, "<word16 vector lane "));
            if (name.equals("unpackWord16X8#")) return new VectorWord16Unpack(operands[0]);
            if (CoreVectors.INSTANCE.getOperationsWord16().contains(name)) return new VectorWord16Operation(name, operands);
            if (name.equals("packWord32X4#")) return new VectorWord32Pack(operands[0], vectorSlots(scope, 4, "<word32 vector lane "));
            if (name.equals("unpackWord32X4#")) return new VectorWord32Unpack(operands[0]);
            if (CoreVectors.INSTANCE.getOperationsWord32().contains(name)) return new VectorWord32Operation(name, operands);
            return new VectorOperation(name, operands);
        }
        if (primitive && CoreArithmeticExceptions.payload((String) fn.get(1)) != null) {
            String name = (String) fn.get(1);
            CoreArithmeticExceptions.validate(name, argumentProofs(args), flags, tupleProof);
            Expr operand = argument(single(args), scope, false, "argument thunk", true, false);
            CoreArithmeticExceptions.validate(name, List.of(operand.getRepresentation()), flags, tupleProof);
            String id = Objects.requireNonNull(CoreArithmeticExceptions.payload(name));
            GlobalBinding payload = globals.get(id);
            if (payload == null) throw new UnsupportedCore("Unresolved implicit exception binding " + id);
            return new RaiseArithmeticException(operand, new RaiseException(new GlobalRead(payload), true));
        }
        if (primitive && Set.of("raiseIO#", "catch#", "getMaskingState#", "unmaskAsyncExceptions#", "maskAsyncExceptions#", "maskUninterruptible#").contains(fn.get(1))) {
            String name = (String) fn.get(1);
            CoreSynchronousExceptions.validate(name, argumentProofs(args), flags, tupleProof);
            Expr[] operands = argumentOperands(args, scope, flags);
            if (name.equals("raiseIO#")) return new RaiseIOException(operands[0], operands[1], tupleProof, CoreExceptionPayload.validate(expr));
            if (delimited && !name.equals("getMaskingState#")) return new DelimitedIOBoundary(name, new TupleShape(tupleProof, (thc.Language) language),
                operands, (thc.Language) language, metrics);
            if (name.equals("catch#")) return new CatchException(new TupleShape(tupleProof, (thc.Language) language), operands[0], operands[1], operands[2], metrics);
            if (name.equals("getMaskingState#")) return new GetMaskingState(operands[0], tupleProof);
            MaskingState state = switch (name) {
                case "maskAsyncExceptions#" -> MaskingState.MASKED_INTERRUPTIBLE;
                case "maskUninterruptible#" -> MaskingState.MASKED_UNINTERRUPTIBLE;
                default -> MaskingState.UNMASKED;
            };
            return new MaskAction(new TupleShape(tupleProof, (thc.Language) language), state, operands[0], operands[1], metrics);
        }
        if (primitive && "noDuplicate#".equals(fn.get(1))) {
            CoreNoDuplicate.validate(argumentProofs(args), flags, tupleProof);
            return new NoDuplicate(argument(args.get(0), scope, false), tupleProof);
        }
        if (primitive && CoreThreadScheduling.INSTANCE.named((String) fn.get(1))) {
            String name = (String) fn.get(1);
            CoreThreadScheduling.INSTANCE.validate(name, argumentProofs(args), flags, tupleProof);
            Expr[] operands = argumentOperands(args, scope, flags);
            CoreThreadScheduling.INSTANCE.validate(name, loweredProofs(operands), flags, tupleProof);
            if (name.equals("par#")) return new Literal(1L).proven(evaluated(tupleProof, true));
            if (name.equals("delay#")) return new DelayThread(operands[0], operands[1], enableAsync, tupleProof);
            if (name.equals("setThreadAllocationCounter#")) return new SetThreadAllocationCounter(operands[0], null, operands[1], tupleProof);
            if (name.equals("setOtherThreadAllocationCounter#")) return new SetThreadAllocationCounter(operands[0], operands[1], operands[2], tupleProof);
            DataValue falseValue = null;
            if (name.equals("getSpark#")) {
                DataLayout falseLayout = dataLayouts.get(CoreThreadScheduling.FALSE);
                if (falseLayout == null) {
                    if (language == null) throw fault("Spark constructor requires a guest language");
                    falseLayout = new DataLayout(language, CoreThreadScheduling.FALSE, "False", new String[0], new Class<?>[0]);
                    dataLayouts.put(CoreThreadScheduling.FALSE, falseLayout);
                }
                falseValue = falseLayout.allocate();
            }
            return new SparkResult(name, operands[operands.length - 1], name.equals("spark#") ? operands[0] : null, falseValue, tupleProof);
        }
        if (primitive && CoreThreadObservation.INSTANCE.named((String) fn.get(1))) {
            String name = (String) fn.get(1);
            CoreThreadObservation.INSTANCE.validate(name, argumentProofs(args), flags, tupleProof);
            Expr state = argument(args.get(0), scope, false);
            CoreThreadObservation.INSTANCE.validate(name, List.of(state.getRepresentation()), flags, tupleProof);
            return new ThreadObservation(name.equals("listThreads#"), state, tupleProof);
        }
        if (primitive && "yield#".equals(fn.get(1))) {
            CoreYield.validate(argumentProofs(args), flags, tupleProof);
            return new YieldThread(argument(args.get(0), scope, false), enableAsync, tupleProof);
        }
        if (primitive && CoreFileWait.INSTANCE.named((String) fn.get(1))) {
            String name = (String) fn.get(1);
            CoreFileWait.INSTANCE.validate(name, argumentProofs(args), flags, tupleProof);
            Expr[] operands = argumentOperands(args, scope, false);
            CoreFileWait.INSTANCE.validate(name, loweredProofs(operands), flags, tupleProof);
            GlobalBinding payload = globals.get(CoreFileWait.badFd);
            if (payload == null) throw new UnsupportedCore(name + " requires original blockedOnBadFD payload");
            return new WaitFileDescriptor(operands[0], operands[1], payload, name.equals("waitWrite#"), enableAsync, tupleProof);
        }
        if (primitive && List.of("fork#", "forkOn#", "myThreadId#", "threadStatus#", "killThread#", "labelThread#", "threadLabel#").contains(fn.get(1))) {
            String name = (String) fn.get(1);
            CoreGuestThreads.INSTANCE.validate(name, argumentProofs(args), flags, tupleProof);
            Expr[] operands = argumentOperands(args, scope, flags);
            CoreGuestThreads.INSTANCE.validate(name, loweredProofs(operands), flags, tupleProof);
            return switch (name) {
                case "fork#" -> new ForkThread(operands[0], operands[1], tupleProof, null);
                case "forkOn#" -> new ForkThread(operands[1], operands[2], tupleProof, operands[0]);
                case "myThreadId#" -> new MyThreadId(operands[0], tupleProof);
                case "threadStatus#" -> new ThreadStatus(operands[0], operands[1], tupleProof);
                case "threadLabel#" -> new ThreadLabel(operands[0], operands[1], tupleProof);
                case "killThread#" -> new KillThread(operands[0], operands[1], operands[2], enableAsync, tupleProof);
                default -> new LabelThread(operands[0], operands[1], operands[2], tupleProof);
            };
        }
        if (primitive && "annotateStack#".equals(fn.get(1))) {
            StackAnnotations.INSTANCE.validate(argumentProofs(args), flags, tupleProof);
            if (capturesContinuations) return new AnnotatedAction(new TupleShape(tupleProof, (thc.Language) language),
                argument(args.get(0), scope, true), argument(args.get(1), scope, true), argument(args.get(2), scope, false), metrics, enableAsync);
            return new AnnotatedTuple(argument(args.get(0), scope, true), argument(args.get(2), scope, false),
                new TupleApplication((thc.Language) language, new TupleShape(tupleProof, (thc.Language) language),
                    argument(args.get(1), scope, true), new Expr[]{new Literal(kotlin.Unit.INSTANCE).proven(CoreRepresentations.INSTANCE.expression(args.get(2)))},
                    false, metrics, null));
        }
        if (primitive && "clearCCS#".equals(fn.get(1))) {
            CoreProfileAction.INSTANCE.validate(argumentProofs(args), flags, tupleProof);
            return new TupleApplication((thc.Language) language, new TupleShape(tupleProof, (thc.Language) language),
                argument(args.get(0), scope, true), new Expr[]{new InspectionState(compile(args.get(1), scope, false))},
                tail, metrics, null).proven(evaluated(tupleProof, true));
        }
        if (primitive && ClosureInspectOp.Companion.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(ClosureInspectOp.Companion.named((String) fn.get(1)));
            operation.validate(argumentProofs(args), flags, tupleProof);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) operands[i] = argument(args.get(i), scope, i == 0);
            return new ClosureInspectExpression(operation, operands, tupleProof);
        }
        if (primitive && "getCurrentCCS#".equals(fn.get(1))) {
            CoreCurrentCCS.INSTANCE.validate(argumentProofs(args), flags, tupleProof);
            return new GetCurrentCCS(argument(args.get(0), scope, true), argument(args.get(1), scope, false), tupleProof);
        }

        if (primitive && STMOp.Companion.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(STMOp.Companion.named((String) fn.get(1)));
            if (containsDelimited && operation != STMOp.NEW && operation != STMOp.READ_IO)
                throw new UnsupportedCore("STM transaction frames do not support explicit delimited capture");
            operation.validate(argumentProofs(args), flags, tupleProof);
            Expr[] operands = argumentOperands(args, scope, flags);
            operation.validate(loweredProofs(operands), flags, tupleProof);
            Expr nested = null;
            if (operation == STMOp.ATOMICALLY) {
                GlobalBinding payload = globals.get(STMOp.NESTED);
                if (payload == null) throw new UnsupportedCore("atomically# requires original nestedAtomically payload");
                nested = new GlobalRead(payload);
            }
            return new STMExpression(operation, tupleProof, operands,
                operation.getCallback() ? new TupleShape(tupleProof, (thc.Language) language) : null, metrics, nested, enableAsync);
        }
        if (primitive && MVarOp.Companion.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(MVarOp.Companion.named((String) fn.get(1)));
            operation.validate(argumentProofs(args), flags, tupleProof);
            operation.validateBindings(argumentProofs(args), bindingProofs(args, scope));
            Expr[] operands = argumentOperands(args, scope, flags);
            operation.validate(loweredProofs(operands), flags, tupleProof);
            return ManagedMVarsKt.mVarExpression(operation, tupleProof, operands, enableAsync);
        }
        if (primitive && CompactImageOp.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(CompactImageOp.named((String) fn.get(1)));
            operation.validate(argumentProofs(args), flags, tupleProof);
            return new CompactImageExpression(operation, argumentOperands(args, scope, flags)).proven(evaluated(tupleProof, true));
        }
        if (primitive && CompactOp.Companion.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(CompactOp.Companion.named((String) fn.get(1)));
            if (capturesContinuations && operation.getAdds())
                throw new UnsupportedCore("Compact graph traversal does not yet support resumable asynchronous forcing");
            operation.validate(argumentProofs(args), flags, tupleProof);
            List<GlobalBinding> failures = new ArrayList<>();
            if (operation.getAdds()) for (String id : CompactOp.Companion.getFailures()) {
                GlobalBinding failure = globals.get(id);
                if (failure == null) throw new UnsupportedCore("Compact addition requires original exception payload: " + id);
                failures.add(failure);
            }
            return new CompactExpression(operation, argumentOperands(args, scope, flags), metrics,
                failures.toArray(GlobalBinding[]::new)).proven(evaluated(tupleProof, true));
        }
        if (primitive && MutVarOp.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(MutVarOp.named((String) fn.get(1)));
            operation.validate(argumentProofs(args), flags, tupleProof);
            return MutVarOp.expression(operation, tupleProof, argumentOperands(args, scope, flags), language, metrics, enableAsync);
        }
        if (primitive && WeakOp.Companion.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(WeakOp.Companion.named((String) fn.get(1)));
            operation.validate(argumentProofs(args), flags, tupleProof);
            operation.validateBindings(argumentProofs(args), bindingProofs(args, scope));
            if (operation == WeakOp.MAKE) operation.validateAction(CoreRepresentations.INSTANCE.knownFunctionSignature(args.get(2), bindings));
            Expr[] operands = argumentOperands(args, scope, flags);
            operation.validate(loweredProofs(operands), flags, tupleProof);
            return new WeakExpression(operation, operands).proven(evaluated(tupleProof, true));
        }
        if (primitive && StableNameOp.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(StableNameOp.named((String) fn.get(1)));
            operation.validate(argumentProofs(args), flags, tupleProof);
            Expr[] operands = argumentOperands(args, scope, flags);
            Expr result = switch (operation) {
                case MAKE -> new MakeStableName(operands[0], operands[1]);
                case HASH -> new HashStableName(operands[0]);
            };
            return result.proven(evaluated(tupleProof, true));
        }
        if (primitive && StablePointerOp.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(StablePointerOp.named((String) fn.get(1)));
            operation.validate(argumentProofs(args), flags, tupleProof);
            Expr[] operands = argumentOperands(args, scope, flags);
            Expr result = switch (operation) {
                case MAKE -> new MakeStablePointer(operands[0], operands[1]);
                case DEREFERENCE -> new DereferenceStablePointer(operands[0], operands[1]);
                case EQUAL -> new EqualStablePointers(operands[0], operands[1]);
            };
            return result.proven(evaluated(tupleProof, true));
        }
        if (primitive && ArrayOp.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(ArrayOp.named((String) fn.get(1)));
            operation.validate(argumentProofs(args), flags, tupleProof);
            return ArrayOp.expression(operation, tupleProof, argumentOperands(args, scope, flags));
        }
        if (primitive && SmallArrayOp.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(SmallArrayOp.named((String) fn.get(1)));
            operation.validate(argumentProofs(args), flags, tupleProof);
            return SmallArrayOp.expression(operation, tupleProof, argumentOperands(args, scope, flags));
        }
        if (primitive && VectorMemoryOp.Companion.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(VectorMemoryOp.Companion.named((String) fn.get(1)));
            operation.validate(argumentProofs(args), flags, tupleProof);
            return operation.isAddress() ? new VectorAddressExpression(operation, compileOperands(args, scope)) :
                new VectorByteArrayExpression(operation, compileOperands(args, scope));
        }
        if (primitive && PrefetchExpression.ARITIES.containsKey(fn.get(1))) {
            if (args.size() != PrefetchExpression.ARITIES.get(fn.get(1))) throw fault("Wrong prefetch arity");
            return new PrefetchExpression(argument(args.get(0), scope, (Boolean) flags.get(0)),
                args.size() == 3 ? compile(args.get(1), scope, false) : null,
                compile(args.getLast(), scope, false), tupleProof);
        }
        if (primitive && TraceOp.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(TraceOp.named((String) fn.get(1)));
            if (args.size() != operation.getArity()) throw fault("Wrong trace arity");
            return new TraceExpression(operation, compile(args.get(0), scope, false),
                operation == TraceOp.BINARY ? compile(args.get(1), scope, false) : null,
                compile(args.getLast(), scope, false), tupleProof);
        }
        if (primitive && "touch#".equals(fn.get(1))) {
            CoreTouch.validateRaw(argumentMetadata(args), flags, metadataRepresentation(expr));
            Expr kept = argument(args.get(0), scope, (Boolean) flags.get(0));
            Expr state = compile(args.get(1), scope, false);
            CoreTouch.validate(List.of(kept.getRepresentation(), state.getRepresentation()), flags, tupleProof);
            return new TouchExpression(kept, state, tupleProof);
        }
        if (primitive && "keepAlive#".equals(fn.get(1))) {
            var proofs = argumentProofs(args);
            var signature = at(args, 2) != null ? CoreRepresentations.INSTANCE.knownFunctionSignature(args.get(2), bindings) : null;
            CoreKeepAlive.validate(proofs, flags, tupleProof,
                signature == null ? null : signature.getFirst(), signature == null ? null : signature.getSecond());
            Expr kept = argument(args.get(0), scope, (Boolean) flags.get(0));
            Expr state = compile(args.get(1), scope, false);
            Expr function = compile(args.get(2), scope, false);
            Expr[] stateArgument = {new Literal(kotlin.Unit.INSTANCE)};
            Expr action = tupleProof.isAggregate() ? new TupleApplication((thc.Language) language,
                new TupleShape(tupleProof, (thc.Language) language), function, stateArgument, false, metrics, null) :
                new Application(function, stateArgument, false, metrics);
            return new KeepAliveExpression(kept, state, action, tupleProof);
        }
        if (primitive && AtomicAddressOp.Companion.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(AtomicAddressOp.Companion.named((String) fn.get(1)));
            operation.validate(argumentProofs(args), flags, tupleProof);
            return new AtomicAddressExpression(operation, tupleProof, compileOperands(args, scope));
        }
        if (primitive && FloatingAddressOp.Companion.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(FloatingAddressOp.Companion.named((String) fn.get(1)));
            String name = (String) fn.get(1);
            boolean byteOffset = name.contains("Word8") && name.contains("As");
            operation.validate(argumentProofs(args), flags, tupleProof);
            return new FloatingAddressExpression(operation, tupleProof, compileOperands(args, scope), byteOffset);
        }
        if (primitive && AddressArrayCopyOp.Companion.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(AddressArrayCopyOp.Companion.named((String) fn.get(1)));
            operation.validate(argumentProofs(args), flags, tupleProof);
            Expr[] operands = compileOperands(args, scope);
            return operation.getToArray() ? new AddressToByteArrayExpression(tupleProof, operands[0], operands[1], operands[2], operands[3], operands[4]) :
                new ByteArrayToAddressExpression(tupleProof, operands[0], operands[1], operands[2], operands[3], operands[4]);
        }
        if (primitive && PinnedMemoryOp.Companion.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(PinnedMemoryOp.Companion.named((String) fn.get(1)));
            String name = (String) fn.get(1);
            boolean byteOffset = name.contains("Word8") && name.contains("As");
            operation.validate(argumentProofs(args), flags, tupleProof);
            if (operation == PinnedMemoryOp.CONTENTS || operation == PinnedMemoryOp.MUTABLE_CONTENTS)
                return new PinnedByteArrayContents(tupleProof, compile(args.get(0), scope, false));
            if (operation == PinnedMemoryOp.INDEX_ADDR_OFF || operation == PinnedMemoryOp.INDEX_ADDR_ARRAY)
                return new PinnedPointerIndexExpression(operation, tupleProof, byteOffset, compile(args.get(0), scope, false), compile(args.get(1), scope, false));
            if (!operation.getTuple() && operation.getAddressRead() != null)
                return new PinnedScalarIndexExpression(operation.getAddressRead(), tupleProof, byteOffset, compile(args.get(0), scope, false), compile(args.get(1), scope, false));
            if (operation == PinnedMemoryOp.WRITE_ADDR_ARRAY) return new PinnedPointerArrayWrite(tupleProof, byteOffset,
                compile(args.get(0), scope, false), compile(args.get(1), scope, false), compile(args.get(2), scope, false), compile(args.get(3), scope, false));
            if (operation == PinnedMemoryOp.READ_ADDR_ARRAY) return new PinnedPointerArrayRead(tupleProof, byteOffset,
                compile(args.get(0), scope, false), compile(args.get(1), scope, false), compile(args.get(2), scope, false));
            return new PinnedMemoryExpression(operation, tupleProof, compileOperands(args, scope), byteOffset);
        }
        if (primitive && AtomicIntArrayOp.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(AtomicIntArrayOp.named((String) fn.get(1)));
            operation.validate(argumentProofs(args), flags, tupleProof);
            return new AtomicIntArrayExpression(operation, compileOperands(args, scope)).proven(evaluated(tupleProof, true));
        }
        if (primitive && ByteArrayOp.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(ByteArrayOp.named((String) fn.get(1)));
            String name = (String) fn.get(1);
            boolean byteOffset = name.contains("Word8") && name.contains("As");
            operation.validate(argumentProofs(args), flags, tupleProof);
            return ByteArrayOp.expression(operation, tupleProof, compileOperands(args, scope), byteOffset);
        }
        if (floatDecode != null) {
            floatDecode.validate(argumentProofs(args), flags, tupleProof);
            return new FloatDecodeExpression(floatDecode, tupleProof, argument(single(args), scope, false));
        }
        if (tupleOperation != null) {
            tupleOperation.validate(argumentProofs(args), flags, tupleProof);
            if (tupleOperation == TupleArithmeticOp.QUOT_REM_WORD_2) return new DoubleWordDivisionExpression(tupleProof,
                argument(args.get(0), scope, false), argument(args.get(1), scope, false), argument(args.get(2), scope, false));
            return new TupleArithmeticExpression(tupleOperation, tupleProof, argument(args.get(0), scope, false), argument(args.get(1), scope, false));
        }


        return compileOrdinaryApplication(expr, fn, args, flags, tupleProof, callStrict, scope, tail);
    }
    private static <T> T single(List<T> values) {
        if (values.isEmpty()) throw new NoSuchElementException("List is empty.");
        if (values.size() != 1) throw new IllegalArgumentException("List has more than one element.");
        return values.get(0);
    }


    private List<CoreRepresentation> bindingProofs(List<List<Object>> args, Scope scope) {
        List<CoreRepresentation> result = new ArrayList<>();
        for (List<Object> arg : args) result.add(bindingProof(arg, scope));
        return result;
    }
    private int[] vectorSlots(Scope scope, int count, String prefix) {
        int[] result = new int[count];
        for (int i = 0; i < count; i++) result[i] = scope.layout.bind(prefix + i + ">");
        return result;
    }
    private Expr compileOrdinaryApplication(List<Object> expr, List<Object> fn, List<List<Object>> args, List<?> flags,
                                            CoreRepresentation tupleProof, boolean[] callStrict, Scope scope, boolean tail) {
        var constructor = (tupleProof.isSum() || tupleProof.isTuple()) && "con".equals(fn.get(0))
            ? constructors.get(fn.get(1)) : null;
        if (tupleProof.isSum() && "con".equals(fn.get(0)) && constructor != null && "unboxed-sum".equals(constructor.get("kind"))) {
            int tag = SumShape.INSTANCE.constructor(tupleProof, constructor, fn.get(2));
            if (args.size() != 1) throw new RuntimeFault("Sum constructor must be saturated");
            CoreRepresentation selected = Objects.requireNonNull(tupleProof.getAlternatives()).get(tag - 1);
            if (!(single(flags) instanceof Boolean lifted)) throw new UnsupportedCore("Unknown sum payload levity");
            Expr payload = selected.isTypedTransport() ? compile(single(args), scope, false) : argument(single(args), scope, lifted);
            SumShape.INSTANCE.payload(selected, payload.getRepresentation(), lifted);
            int[] intSlots = new int[0];
            if (selected.isTypedTransport()) {
                var fields = TupleShape.Companion.flatten(selected);
                intSlots = new int[fields.size()];
                for (int i = 0; i < intSlots.length; i++) intSlots[i] = fields.get(i).isInt() ? scope.layout.bind("<narrow sum payload " + i + ">") : -1;
            }
            return new SumConstruct(new TupleShape(tupleProof, (thc.Language) language), tag, payload, intSlots);
        }
        if (tupleProof.isTuple() && "con".equals(fn.get(0)) && constructor != null && "unboxed-tuple".equals(constructor.get("kind"))) {
            TupleShape shape = new TupleShape(tupleProof, (thc.Language) language);
            if (shape.getComponents().length != args.size() || ((Number) fn.get(2)).intValue() != args.size() ||
                !(constructor.get("arity") instanceof Number number) || number.intValue() != args.size()) throw new RuntimeFault("Tuple constructor arity mismatch");
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                CoreRepresentation field = shape.getComponents()[i];
                TupleShape.Companion.requireCompatible(field, CoreRepresentations.INSTANCE.expression(args.get(i)), true);
                if (field.isTypedTransport() && !Boolean.FALSE.equals(flags.get(i))) throw new RuntimeFault("Typed tuple field cannot be lifted");
                if (field.isTypedTransport()) operands[i] = compile(args.get(i), scope, false);
                else {
                    if (!(flags.get(i) instanceof Boolean lifted)) throw new UnsupportedCore("Unknown tuple field levity");
                    operands[i] = argument(args.get(i), scope, lifted);
                }
            }
            return new TupleConstruct(shape, operands);
        }
        if ("var".equals(fn.get(0)) && scope.joins.containsKey(fn.get(1)))
            return joinJump(required(scope.joins, (String) fn.get(1)), args, flags, scope, callStrict);
        boolean[] constructorStrict = "con".equals(fn.get(0)) && ((Number) fn.get(2)).intValue() == args.size()
            ? strictConstructorFields((String) fn.get(1), args.size()) : null;
        DataLayout layout = constructorStrict != null ? dataLayout((String) fn.get(1)) : null;
        boolean[] entryStrict = null;
        if ("lam".equals(fn.get(0))) entryStrict = CoreEntries.lambda(fn);
        else if ("var".equals(fn.get(0))) {
            String id = (String) fn.get(1);
            entryStrict = scope.locals.containsKey(id) ? scope.locals.get(id).entry : globalEntries.get(id);
        }
        if (entryStrict != null && args.size() < entryStrict.length) entryStrict = null;
        Expr[] nodes = new Expr[args.size()];
        for (int i = 0; i < nodes.length; i++) {
            if (!(flags.get(i) instanceof Boolean lifted)) throw new UnsupportedCore("Unknown argument levity");
            CoreRepresentation field = layout != null ? layout.logicalProof$org_intelligence_thc(i) : null;
            if (field != null && field.isAggregate()) {
                if (lifted) throw new RuntimeFault("Aggregate constructor operand must be unlifted");
                nodes[i] = compile(args.get(i), scope, false);
                TupleShape.Companion.requireCompatible(field, nodes[i].getRepresentation(), true);
            } else nodes[i] = argument(args.get(i), scope, lifted && !callStrict[i] &&
                !(constructorStrict != null && constructorStrict[i]) && !(entryStrict != null && i < entryStrict.length && entryStrict[i]),
                "argument thunk", !"prim".equals(fn.get(0)) && !"con".equals(fn.get(0)), lifted);
        }
        if ("prim".equals(fn.get(0))) {
            for (Expr node : nodes) CoreRepresentations.INSTANCE.requireScalar(node.getRepresentation(), "argument");
            return primitive((String) fn.get(1), nodes, CoreExceptionPayload.validate(expr));
        }
        if (constructorStrict != null) {
            DataLayout target = dataLayout((String) fn.get(1));
            return new Construct(target, nodes, constructorVectorSlots(target, scope.layout));
        }
        Expr function = compile(fn, scope, false);
        ArgumentLayout input = ArgumentLayout.fromProofs(loweredProofs(nodes));
        if (input != null && input.getRequiresTyped()) return new AstTypedApplication(function, nodes, scope.layout, tail, metrics,
            tupleProof.isTypedTransport() ? new TupleShape(tupleProof, (thc.Language) language) : null,
            tail && !capturesContinuations && scope.self != null &&
                TypedInputsKt.supportsTypedSelf(scope.self.getInputLayout(), scope.self.getEntryStrict(), input));
        if (tupleProof.isTypedTransport()) {
            TupleShape shape = new TupleShape(tupleProof, (thc.Language) language);
            int[] vectorSlots = tupleProof.isVector() ? vectorSlots(scope, shape.getWidth(), "<vector call result ") : null;
            return new TupleApplication((thc.Language) language, shape, function, nodes, tail, metrics, vectorSlots);
        }
        AstSelfLayout self = scope.self;
        boolean emptyTuple = false;
        for (Expr node : nodes) if (node.getRepresentation().isEmptyTuple()) { emptyTuple = true; break; }
        if (tail && !capturesContinuations && self != null && self.getInputLayout() == null && !emptyTuple && self.getArity() > 0 && nodes.length <= self.getArity())
            return new AstTailApplication(function, nodes, self, vectorSlots(scope, self.getArity(), "<self argument "), metrics);
        return new Application(function, nodes, tail, metrics);
    }


    /** Unavailable scalars stay lazy; a demanded tuple traps before any destination write. */
    private static final class DiagnosticUnavailable extends Expr {
        private final RootCallTarget target;
        @Child private UnsupportedExpression tupleTrap;
        DiagnosticUnavailable(RootCallTarget target, String message, Metrics metrics) {
            this.target = target;
            tupleTrap = new UnsupportedExpression(message, metrics);
        }
        @Override public Thunk execute(VirtualFrame frame) { return new Thunk(target, null); }
        @Override public Object executeTuple(VirtualFrame frame, int[] slots, int offset) {
            return tupleTrap.execute(frame);
        }
    }
    /** Explicit development mode only; execution never fabricates a guest result. */
    private static final class UnsupportedExpression extends Expr {
        private final String message;
        private final Metrics metrics;
        UnsupportedExpression(String message, Metrics metrics) {
            this.message = message; this.metrics = metrics;
            setRepresentation(new CoreRepresentation(CoreKind.UNKNOWN, true, false, null, null, null, null, null, null));
        }
        @Override public Object execute(VirtualFrame frame) {
            com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate();
            metrics.incrementUnsupportedTraps();
            throw new RuntimeFault("Diagnostic unsupported path reached: " + message);
        }
    }
}
