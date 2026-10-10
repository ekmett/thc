// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import static thc.runtime.Alternative.*;
import static thc.runtime.CoreCallDemands.CALL_DEMANDS_PROPERTY;
import static thc.runtime.CoreFreeVariables.coreFreeVariables;
import static thc.runtime.RuntimeFault.fault;
import static thc.runtime.Scalar64Primitives.word64Literal;

/* Indexed frames, selective captures, rooted application and self-tail frame
 * restoration follow Cadenza. See NOTICE.md and LICENSE.md. */

/** Constructs and links the AST backend's executable roots from exported GHC Core.
 * This holder is not a Truffle node or guest closure. */
@SuppressWarnings("unchecked")
public final class Program implements ExecutableProgram {
    private static final TruffleLanguage.LanguageReference<thc.Language> LANGUAGES =
        TruffleLanguage.LanguageReference.create(thc.Language.class);
    private final TruffleLanguage<?> language;
    private final boolean enableAsync;
    private final boolean eagerAsyncPolls;
    private final boolean outlineCaseArms;
    private final boolean deferDefaultArm;
    private final boolean capturesContinuations;
    private final CoreDemandBindings demand;
    private final ForeignExceptionBridge foreignExceptionBridge;
    private final RubbishLiterals rubbishLiterals;
    private final List<thc.ForeignBitcode> foreignLinks;
    private final List<thc.PackageScalarLink> packageScalarLinks;
    private final List<CoreBoxedForeignDeclarations> boxedForeignDeclarations;
    private final List<PackageScalarCall> packageCalls;
    private final PackageScalarFunction[] packageFunctions;
    private final Map<String,thc.ManagedCallbackSignature> nativeCallbacks;
    private final Object stackTargetLayout;
    private final boolean callDemandsEnabled = Boolean.getBoolean(CALL_DEMANDS_PROPERTY);
    private final Metrics metrics;
    private final boolean reusableCode;
    private final Object codeIdentity;
    private final thc.Language.State contextOwner;
    private final List<RootCallTarget> codeTargets;
    private final boolean delimited;
    private final boolean containsDelimited;
    private final CoreSources sources;
    private final Map<String,Object> sourceModule;
    private final class LoweringState {
        CoreSourceLocation source;
        OperandBuilder operands;
        final Set<String> dependencies = new LinkedHashSet<>();
    }
    private final LoweringState serialLowering = new LoweringState();
    private ThreadLocal<LoweringState> parallelLowering;
    private LoweringState lowering() { return parallelLowering == null ? serialLowering : parallelLowering.get(); }
    private final AtomicInteger attachedRootCount = new AtomicInteger();
    private final AtomicInteger constructedRootCount = new AtomicInteger();
    private final AtomicInteger initializedBindingCount = new AtomicInteger();
    private final PreparationLock preparationLock;
    private final boolean diagnosticUnsupported;
    private final Set<String> deferredUnsupported = new LinkedHashSet<>();
    private final List<Map<String, Object>> bindings;
    private final Map<String, Map<String, Object>> constructors;
    private final Map<String, DataLayout> dataLayouts;
    private final Map<String, Integer> constructorIndices = Collections.synchronizedMap(new LinkedHashMap<>());
    private final DataLayout[] indexedLayouts;
    private final Map<String, GlobalBinding> globals;
    private final String weakFinalizer;
    private final GlobalBinding[] indexedGlobals;
    private final Map<String, CoreRepresentation> globalProofs = new LinkedHashMap<>();
    private final Map<String, Integer> indices = new LinkedHashMap<>();
    private final Map<String, List<Integer>> names = new LinkedHashMap<>();
    private final Map<Integer, RootCallTarget> hostEntries = new LinkedHashMap<>();
    private final Map<String, boolean[]> globalEntries = new LinkedHashMap<>();
    private final Map<String, CoreApplicationCertificates.Arity> globalArityCertificates = new LinkedHashMap<>();
    private final Consumer<List<Map<String, Object>>> validateInputs;
    private List<String> pendingInitializers = List.of();

    /** Publish all ordinary global cells before native constructors can call back. */
    public static Program forNativeStartup(TruffleLanguage<?> language, Map<String,Object> module, boolean async) {
        return new Program(language, module, async, false, false, false, null,
            !absentOrEmpty(module.get("packageScalarLinks")) || !absentOrEmpty(module.get("foreignLinks")));
    }

    public Program(TruffleLanguage<?> language, Map<String, Object> moduleData) { this(language, moduleData, false, false); }
    public Program(TruffleLanguage<?> language, Map<String, Object> moduleData, boolean enableAsync) { this(language, moduleData, enableAsync, false); }
    public Program(TruffleLanguage<?> language, Map<String, Object> moduleData, boolean enableAsync, boolean outlineCaseArms) {
        this(language, moduleData, enableAsync, outlineCaseArms, false);
    }
    public Program(TruffleLanguage<?> language, Map<String, Object> moduleData, boolean enableAsync,
                   boolean outlineCaseArms, boolean deferDefaultArm) {
        this(language, moduleData, enableAsync, outlineCaseArms, deferDefaultArm, false, null);
    }
    private Program(TruffleLanguage<?> language, Map<String, Object> moduleData, boolean enableAsync,
                    boolean outlineCaseArms, boolean deferDefaultArm, boolean reusableCode, PreparedCode prepared) {
        this(language, moduleData, enableAsync, outlineCaseArms, deferDefaultArm, reusableCode, prepared, false);
    }
    private Program(TruffleLanguage<?> language, Map<String, Object> moduleData, boolean enableAsync,
                    boolean outlineCaseArms, boolean deferDefaultArm, boolean reusableCode, PreparedCode prepared, boolean nativeStartup) {
        if (prepared == null && Boolean.getBoolean("thc.requireCachedCode"))
            throw new UnsupportedCore("Runtime THC lowering is disabled; prepared code is required");
        // Capture is a first-lowering capability, including reusable AOT code.
        // The public option controls only ordinary poll eagerness.
        this.language = language; this.enableAsync = true; this.outlineCaseArms = outlineCaseArms;
        eagerAsyncPolls = enableAsync;
        this.reusableCode = reusableCode;
        this.codeTargets = reusableCode && prepared == null ? Collections.synchronizedList(new ArrayList<>()) : List.of();
        this.codeIdentity = prepared == null ? new Object() : prepared.identity;
        this.contextOwner = reusableCode ? thc.Language.currentState() : null;
        this.deferDefaultArm = deferDefaultArm;
        capturesContinuations = this.enableAsync || outlineCaseArms || deferDefaultArm;
        thc.CoreForeignArtifacts.INSTANCE.requireExecutableInput(moduleData);
        boxedForeignDeclarations = CoreBoxedForeignDeclarations.admissions(moduleData);
        demand = moduleData.get("demandBindings") instanceof CoreDemandBindings found ? found : null;
        preparationLock = demand == null ? new PreparationLock(thc.Language.currentState().getEnv().getContext()) : demand.getPreparationLock();
        nativeStartup |= demand != null; // Demand preparation publishes cells; execution initializes them.
        foreignExceptionBridge = ForeignExceptionBridge.bind(moduleData, this::entryValue, this::dataLayout);
        weakFinalizer = moduleData.get("selectedWeakFinalizer") instanceof String id ? id : null;
        rubbishLiterals = new RubbishLiterals(language);
        foreignLinks = moduleData.get("foreignLinks") instanceof List<?> found ? (List<thc.ForeignBitcode>) found : List.of();
        packageScalarLinks = moduleData.get("packageScalarLinks") instanceof List<?> found ? (List<thc.PackageScalarLink>) found : List.of();
        packageCalls = prepared != null ? prepared.packageCalls : reusableCode ? new ArrayList<>() : List.of();
        packageFunctions = prepared == null ? null : new PackageScalarFunction[prepared.packageCalls.size()];
        nativeCallbacks = moduleData.get("nativeCallbacks") instanceof Map<?,?> found ? (Map<String,thc.ManagedCallbackSignature>) found : Map.of();
        stackTargetLayout = moduleData.get("targetLayout");
        metrics = demand != null ? demand.getMetrics() : new Metrics(!Boolean.FALSE.equals(moduleData.get("instrument")));
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
        sourceModule = moduleData;
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
        dataLayouts = demand == null ? Collections.synchronizedMap(new LinkedHashMap<>()) : demand.getLayouts();
        indexedLayouts = prepared == null ? null : new DataLayout[prepared.layouts.size()];
        if (prepared != null) for (DataLayout.Reusable storage : prepared.layouts) {
            int index = constructorIndices.size();
            constructorIndices.put(storage.id, index);
            DataLayout layout = storage.instantiate();
            indexedLayouts[index] = layout; dataLayouts.put(storage.id, layout);
        }
        Map<String, GlobalBinding> localGlobals = new LinkedHashMap<>();
        for (Map<String, Object> binding : bindings)
            localGlobals.put((String) binding.get("id"), new GlobalBinding((String) binding.get("name")));
        globals = demand == null ? localGlobals : demand.globals(localGlobals);
        indexedGlobals = reusableCode ? bindings.stream()
            .map(binding -> required(localGlobals, (String) binding.get("id"))).toArray(GlobalBinding[]::new) : null;
        for (Map<String, Object> binding : bindings) {
            List<Object> expression = (List<Object>) binding.get("expr");
            boolean delayed = representation(binding) && !Arrays.asList("lam", "lit", "con", "void").contains(expression.getFirst());
            globalProofs.put((String) binding.get("id"), diagnosticUnsupported ? CoreRepresentation.UNKNOWN :
                evaluated(CoreRepresentations.binder(binding), demand == null && !delayed &&
                    Arrays.asList("lam", "lit", "con", "void").contains(expression.getFirst())));
        }
        for (int i = 0; i < bindings.size(); i++) indices.put((String) bindings.get(i).get("id"), i);
        for (int i = 0; i < bindings.size(); i++) names.computeIfAbsent((String) bindings.get(i).get("name"), key -> new ArrayList<>()).add(i);
        for (Map<String, Object> binding : bindings) globalEntries.put((String) binding.get("id"), CoreEntries.binding(binding));
        for (Map<String, Object> binding : bindings) globalArityCertificates.put((String) binding.get("id"), CoreApplicationCertificates.binding(binding));
        if (diagnosticUnsupported || prepared != null) validateInputs = null;
        else {
            validateInputs = CoreInputCalls.validator(bindings, constructors, demand);
        }
        if (prepared != null) {
            List<String> pending = new ArrayList<>();
            for (Map<String, Object> binding : bindings) {
                String id = (String) binding.get("id");
                CodeValue code = prepared.values.get(id);
                required(globals, id).defer(preparationLock, () -> {
                    if (code == null) throw new UnsupportedCore("Binding was not prepared for reusable execution: " + id);
                    return code.instantiate(this);
                });
                if (code != null) pending.add(id);
            }
            // Every cell exists before effects or native constructors can demand a dependency.
            pendingInitializers = List.copyOf(pending);
            if (!nativeStartup) initializeGlobals();
            return;
        }
        if (reusableCode) return;
        List<Map<String, Object>> eager = new ArrayList<>();
        for (Map<String, Object> binding : bindings) {
            thc.CoreBindingBody source = binding.get("expr") instanceof thc.CoreBindingBody body ? body : null;
            if (!reusableCode && (source == null && demand == null || !representation(binding) || demand == null && source != null &&
                Arrays.asList("lit", "con", "void").contains(source.getHeader().getTag()))) {
                eager.add(binding);
                if (!nativeStartup) continue;
            }
            required(globals, (String) binding.get("id")).defer(preparationLock, () -> {
                validateBindings(List.of(binding));
                Scope scope = new Scope(new FrameLayout());
                Expr body = initializer(binding, scope);
                // These two owning constructors only publish immutable code/captures.
                // All other initializers use the guest execution boundary, without rejection.
                if (body instanceof MakeClosure || body instanceof Delay) {
                    Object value = body.execute(Truffle.getRuntime().createVirtualFrame(new Object[0], scope.layout.build()));
                    CoreFunctionIdentity.install(moduleData, binding, value, globalArityCertificates);
                    initializedBindingCount.incrementAndGet();
                    return value;
                }
                FunctionSpec plan = initializerPlan(binding, body, scope);
                return new GlobalBinding.Initializer(plan.target,
                    new Object[] {0L}, metrics, initializedBindingCount::incrementAndGet);
            });
        }
        if (nativeStartup) {
            pendingInitializers = eager.stream().map(binding -> (String) binding.get("id")).toList();
            return;
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
            initializedBindingCount.incrementAndGet();
        }
    }

    @Override public void initializeGlobals() {
        for (String id : pendingInitializers) required(globals, id).read();
        pendingInitializers = List.of();
        if (packageFunctions != null) for (int i = 0; i < packageFunctions.length; i++) {
            PackageScalarCall call = packageCalls.get(i);
            if (call.getKind() == PackageScalarCall.Kind.STATIC && call.getLink().getCallSeeds().containsKey(call.getSignature().getEntry())) continue;
            packageFunctions[i] = resolvePackageFunction(call);
        }
    }

    /** Explicit experimental admission using the ordinary AST lowerer, without executing guest bodies.
     * Unselected definitions stay unprepared. Context sharing and AOT preparation remain separate. */
    public static PreparedCode prepareCode(TruffleLanguage<?> language, Map<String, Object> module, List<String> entries) {
        return prepareCode(language, module, entries, Integer.parseInt(System.getProperty("thc.prepareCodeJobs", "4")));
    }

    /** Prepare reachable bindings with a bounded pool; CAF bodies remain unevaluated. */
    public static PreparedCode prepareCode(TruffleLanguage<?> language, Map<String, Object> module, List<String> entries, int jobs) {
        if (jobs < 1 || jobs > 64) throw new IllegalArgumentException("Core preparation jobs must be between 1 and 64");
        if (language != LANGUAGES.get(null)) throw new UnsupportedCore("Reusable AST preparation requires the current language");
        if (module.containsKey("demandBindings"))
            throw new UnsupportedCore("Reusable AST preparation requires detached binding bodies");
        // Validate with the real module descriptors, retaining only storage
        // metadata for constructors actually reached during selected lowering.
        if (module.containsKey("asyncExceptions") && !(module.get("asyncExceptions") instanceof Boolean))
            throw new IllegalArgumentException("asyncExceptions must be a Boolean");
        // The linker includes runtime-entered service roots as well as explicit Core references.
        var linked = thc.CoreModules.reachable(module, entries);
        var preparation = new LinkedHashMap<>(module);
        preparation.put("selectedWeakFinalizer", linked.get("selectedWeakFinalizer"));
        Program builder = new Program(language, preparation, Boolean.TRUE.equals(module.get("asyncExceptions")), false, false, true, null);
        Map<String, CodeValue> values = new LinkedHashMap<>();
        var selected = (List<Map<String,Object>>) linked.get("bindings");
        ArrayDeque<String> pending = new ArrayDeque<>();
        for (var binding : selected) pending.add((String) binding.get("id"));
        if (selected.stream().anyMatch(binding -> CoreSignalForeign.dispatcher.equals(binding.get("id"))))
            SignalDispatchRoot.prepareLayouts(builder);
        if (module.get("selectedForeignExceptionBridge") instanceof Map<?,?> bridge) {
            for (String helper : List.of("box", "project")) {
                if (!(bridge.get(helper) instanceof String id) || !builder.globals.containsKey(id))
                    throw new UnsupportedCore("Reusable foreign exception helper is absent: " + helper);
                pending.add(id);
            }
        }
        List<thc.ManagedExportAdmission> registrations = module.get("managedRegistrations") instanceof List<?> found
            ? List.copyOf((List<thc.ManagedExportAdmission>) found) : List.of();
        var exports = new ArrayList<thc.ManagedExportSignature>();
        for (var registration : registrations) for (var export : registration.getExports()) {
            pending.add(export.getBinder()); exports.add(export);
        }
        var checkedExports = thc.ManagedExportPlan.checked(exports, ignored -> builder.bindings);
        for (var export : checkedExports) {
            for (var type : export.arguments())
                ManagedExportScalar.fromNormalizedType(type, ManagedExportScalar.Role.ARGUMENT, export.wordBits(), builder::dataLayout);
            ManagedExportScalar.fromNormalizedType(export.result(), ManagedExportScalar.Role.RESULT, export.wordBits(), builder::dataLayout);
        }
        // Dynamic callback closures come from StablePtr; their boxed scalar converters
        // still need immutable constructor storage before source descriptors are dropped.
        for (var callback : builder.nativeCallbacks.values()) {
            var signature = callback.signature();
            for (var type : signature.arguments())
                ManagedExportScalar.fromNormalizedType(type, ManagedExportScalar.Role.ARGUMENT, signature.wordBits(), builder::dataLayout);
            ManagedExportScalar.fromNormalizedType(signature.result(), ManagedExportScalar.Role.RESULT, signature.wordBits(), builder::dataLayout);
        }
        builder.prepareBindings(pending, values, jobs);
        List<Map<String, Object>> headers = new ArrayList<>();
        for (Map<String, Object> binding : builder.bindings) {
            // Deliberately do not retain source bodies, demand suppliers or the preparation instance.
            headers.add(Map.of("id", binding.get("id"), "name", binding.get("name"),
                "lifted", builder.representation(binding), "expr", List.of("void")));
        }
        Map<String,Object> declarations = new LinkedHashMap<>();
        declarations.put("bindings", List.copyOf(headers)); declarations.put("constructors", List.of());
        declarations.put("instrument", builder.metrics.getEnabled());
        declarations.put("asyncExceptions", builder.eagerAsyncPolls);
        declarations.put("foreignLinks", List.copyOf(builder.foreignLinks));
        declarations.put("packageScalarLinks", List.copyOf(builder.packageScalarLinks));
        declarations.put("boxedForeignDeclarations", builder.boxedForeignDeclarations);
        declarations.put("nativeCallbacks", Map.copyOf(builder.nativeCallbacks));
        if (builder.stackTargetLayout != null) declarations.put("targetLayout", builder.stackTargetLayout);
        if (module.get("selectedForeignExceptionBridge") instanceof Map<?,?> bridge)
            declarations.put("selectedForeignExceptionBridge", Map.copyOf(bridge));
        if (linked.get("selectedWeakFinalizer") instanceof String finalizer)
            declarations.put("selectedWeakFinalizer", finalizer);
        return new PreparedCode(Map.copyOf(declarations), Map.copyOf(values), List.copyOf(builder.codeTargets),
            builder.dataLayouts.values().stream().map(DataLayout::reusableStorage).toList(),
            List.copyOf(builder.packageCalls), registrations, List.copyOf(checkedExports), builder.codeIdentity, language);
    }
    private record PreparedBinding(String id, CodeValue value, List<String> dependencies) {}

    private PreparedBinding prepareSelected(Map<String,Object> binding) {
        CodeValue value = prepareBinding(binding);
        var dependencies = List.copyOf(lowering().dependencies);
        lowering().dependencies.clear();
        return new PreparedBinding((String) binding.get("id"), value, dependencies);
    }

    private void prepareBindings(ArrayDeque<String> pending, Map<String,CodeValue> values, int jobs) {
        // A narrow dependency chain needs no worker threads. Start the pool only
        // when discovery exposes independent bindings to prepare.
        while (!pending.isEmpty() && (jobs == 1 || pending.size() == 1)) {
            var binding = bindings.get(bindingIndex(pending.removeFirst()));
            if (values.containsKey(binding.get("id"))) continue;
            var prepared = prepareSelected(binding);
            values.put(prepared.id, prepared.value); pending.addAll(prepared.dependencies);
        }
        if (pending.isEmpty()) return;
        parallelLowering = ThreadLocal.withInitial(LoweringState::new);
        var context = contextOwner.getEnv().getContext();
        var claimed = new HashSet<>(values.keySet());
        try (var workers = Executors.newFixedThreadPool(jobs)) {
            var completed = new ExecutorCompletionService<PreparedBinding>(workers);
            int active = 0;
            try {
                while (!pending.isEmpty() || active != 0) {
                    while (active < jobs && !pending.isEmpty()) {
                        var binding = bindings.get(bindingIndex(pending.removeFirst()));
                        if (!claimed.add((String) binding.get("id"))) continue;
                        completed.submit(() -> {
                            Object previous = context.enter(null);
                            try { return prepareSelected(binding); }
                            finally { parallelLowering.remove(); context.leave(null, previous); }
                        });
                        active++;
                    }
                    if (active == 0) break;
                    var prepared = completed.take().get(); active--;
                    values.put(prepared.id, prepared.value); pending.addAll(prepared.dependencies);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Core preparation interrupted");
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
                if (failed.getCause() instanceof Error error) throw error;
                throw new RuntimeException("Core preparation failed", failed.getCause());
            } finally {
                // No workers or half-prepared result may escape a failed request.
                workers.shutdownNow();
            }
        }
    }

    private CodeValue prepareBinding(Map<String,Object> binding) {
        validateBindings(List.of(binding));
        FunctionSpec plan = initializerPlan(binding);
        return new CodeValue(plan.target, plan.captureLayout);
    }
    private FunctionSpec initializerPlan(Map<String,Object> binding) {
        return function((String) binding.get("name") + " initializer", List.of(),
            (List<Object>) binding.get("expr"), new Scope(new FrameLayout()), CoreRepresentations.binder(binding),
            new boolean[0], FunctionRootRole.INITIALIZER, false, binding);
    }

    private FunctionSpec initializerPlan(Map<String,Object> binding, Expr body, Scope scope) {
        return function((String) binding.get("name") + " initializer", List.of(),
            (List<Object>) binding.get("expr"), scope, CoreRepresentations.binder(binding),
            new boolean[0], FunctionRootRole.INITIALIZER, false, binding, body);
    }

    private static boolean absentOrEmpty(Object value) { return value == null || value instanceof List<?> list && list.isEmpty() || value instanceof Map<?,?> map && map.isEmpty(); }
    public static final class PreparedCode {
        private final Map<String, Object> module;
        private final Map<String, CodeValue> values;
        private final List<RootCallTarget> targets;
        private final List<DataLayout.Reusable> layouts;
        private final List<PackageScalarCall> packageCalls;
        private final List<thc.ManagedExportAdmission> managedRegistrations;
        private final List<thc.ManagedExportSignature> managedExports;
        private final Object identity;
        private final TruffleLanguage<?> language;
        private PreparedCode(Map<String, Object> module, Map<String, CodeValue> values, List<RootCallTarget> targets, List<DataLayout.Reusable> layouts,
                             List<PackageScalarCall> packageCalls, List<thc.ManagedExportAdmission> managedRegistrations,
                             List<thc.ManagedExportSignature> managedExports,
                             Object identity, TruffleLanguage<?> language) {
            this.module = module; this.values = values; this.targets = targets; this.layouts = layouts; this.identity = identity; this.language = language;
            this.packageCalls = packageCalls; this.managedRegistrations = managedRegistrations; this.managedExports = managedExports;
        }
        public List<thc.ForeignBitcode> getForeignLinks() { return (List<thc.ForeignBitcode>) module.get("foreignLinks"); }
        public List<thc.PackageScalarLink> getPackageScalarLinks() { return (List<thc.PackageScalarLink>) module.get("packageScalarLinks"); }
        public List<thc.ManagedExportAdmission> getManagedRegistrations() { return managedRegistrations; }
        public List<thc.ManagedExportSignature> getManagedExports() { return managedExports; }
        public Program newInstance(TruffleLanguage<?> language) {
            return newInstance(language, false);
        }
        public Program newInstanceForNativeStartup(TruffleLanguage<?> language) {
            return newInstance(language, true);
        }
        private Program newInstance(TruffleLanguage<?> language, boolean nativeStartup) {
            if (language != this.language || language != LANGUAGES.get(null))
                throw new UnsupportedCore("Reusable AST instance requires its prepared and current language");
            return new Program(language, module, Boolean.TRUE.equals(module.get("asyncExceptions")), false, false, true, this, nativeStartup);
        }
        /** Observe existing installation only: no binding demand, execution or compilation. */
        public void requireInstalledCode() {
            for (RootCallTarget value : targets) {
                if (!(value instanceof com.oracle.truffle.runtime.OptimizedCallTarget target) || !target.isValidLastTier())
                    throw new IllegalStateException("Cached compiled target required: " + value.getRootNode().getName());
            }
        }
    }
    /** Ordinary initializer code; never a value or authority obtained from preparation. */
    private record CodeValue(RootCallTarget target, CaptureLayout captures) {
        Object instantiate(Program instance) {
            return new GlobalBinding.Initializer(target,
                new Object[] {0L, captures.captureValues(new Object[0], instance)}, instance.metrics,
                instance.initializedBindingCount::incrementAndGet);
        }
    }
    private static boolean representationLifted(Map<String, Object> binding) {
        return binding.get("lifted") instanceof Boolean lifted ? lifted :
            CoreRepresentations.mayBeLazy(binding.get("lifted"), CoreRepresentations.binder(binding));
    }
    private Metrics codeMetrics() { return reusableCode ? null : metrics; }
    Metrics instanceMetrics() { return metrics; }
    PackageScalarFunction packageFunction(int index) {
        var function = packageFunctions[index];
        if (function != null) return function;
        // A constructor callback may use its component's already initialized
        // symbols. Do not cache that early receiver: after successful startup,
        // initializeGlobals installs the completed library's canonical handles.
        PackageScalarCall call = packageCalls.get(index);
        return resolvePackageFunction(call);
    }
    Object readGlobal(int index) { return indexedGlobals[index].read(); }
    Object rubbishValue(CoreRepresentation proof) { return rubbishLiterals.decode(proof); }
    private PackageScalarFunction resolvePackageFunction(PackageScalarCall call) {
        return call.getKind() == PackageScalarCall.Kind.STATIC
            ? contextOwner.getPackageCbits().resolve(call.getLink(), call.getSignature())
            : contextOwner.getNativeCallbacks().function(call);
    }
    ForeignExceptionBridge foreignExceptionBridge() { return foreignExceptionBridge; }
    DataLayout constructorLayout(int index) { return indexedLayouts[index]; }
    boolean usesCode(Object identity) { return codeIdentity == identity; }
    boolean belongsToCurrentContext(com.oracle.truffle.api.nodes.Node node) {
        return contextOwner == thc.Language.currentState(node);
    }
    static Program instance(VirtualFrame frame, int slot) {
        if (slot < 0 || !(frame.getObject(slot) instanceof Program program))
            throw fault("Missing explicit program instance");
        return program;
    }
    @Override public boolean getAsynchronousExceptions() { return eagerAsyncPolls; }
    @Override public boolean getCapturesContinuations() { return capturesContinuations; }
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
        int programSlot = -1;
        final List<Object> deferredExpression;
        final List<DeferredArm> deferredArms;
        Scope(FrameLayout layout) { this(layout, null); }
        Scope(FrameLayout layout, List<Object> deferredExpression) {
            this(layout, new LinkedHashMap<>(), new LinkedHashMap<>(), null, deferredExpression,
                deferredExpression == null ? null : new ArrayList<>());
        }
        Scope(FrameLayout layout, Map<String, Local> locals, Map<String, LocalJoinTarget> joins, AstSelfLayout self,
              List<Object> deferredExpression, List<DeferredArm> deferredArms) {
            this.layout = layout; this.locals = locals; this.joins = joins; this.self = self;
            this.deferredExpression = deferredExpression; this.deferredArms = deferredArms;
        }
        Scope child() { return new Scope(layout.scope(), new LinkedHashMap<>(locals), new LinkedHashMap<>(joins), self,
            deferredExpression, deferredArms).withProgramSlot(programSlot); }
        Scope withProgramSlot(int slot) { programSlot = slot; return this; }
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
    private record DeferredArm(AstDeferredArm node, CaptureLayout captures, int[] slots) {}
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
                for (int i = 0; i < slots.length; i++) {
                    slots[i] = layout.bind("<async operand field " + i + ">", FrameLayout.carrierKind(shape.getLeaves()[i]));
                    temporaries.add(slots[i]);
                }
                bindings.add(new LocalBinding(-1, value, false, slots));
                return proof.isVector() ? new VectorLocalRead(shape, slots) : new TupleLocalRead(shape, slots);
            }
            FrameSlotKind kind = FrameLayout.carrierKind(proof);
            // Unknown proof still has an owned boxed carrier. Prepared code
            // must not discover its frame kind from the first guest operand.
            if (reusableCode && kind == FrameSlotKind.Illegal) kind = FrameSlotKind.Object;
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
        CoreSourceLocation previous = lowering().source;
        lowering().source = location;
        try { return action.get(); }
        finally { lowering().source = previous; }
    }
    private CoreSourceLocation rootSource(Expr body) {
        CoreSourceLocation result = body.getCoreSourceLocation() != null ? body.getCoreSourceLocation() : lowering().source;
        if (result != null && sources.getEnabled()) attachedRootCount.incrementAndGet();
        return result;
    }

    private void validateBindings(List<Map<String, Object>> requested) {
        if (requested.isEmpty()) return;
        if (capturesContinuations) AstAsyncAdmission.validate(requested);
        ArrayOp.validateApplications(requested);
        CoreForeignOverride.validateHeads(requested);
        if (!diagnosticUnsupported) {
            CoreRepresentations.validateAggregates(requested, constructors);
            Objects.requireNonNull(validateInputs).accept(requested);
        }
    }
    private Expr initializer(Map<String, Object> binding, Scope scope) {
        CoreRepresentations.requireNoSum(CoreRepresentations.binder(binding), "global binding");
        return withSource(sources.binding(binding, null), () -> {
            List<Object> expr = (List<Object>) binding.get("expr");
            CoreRepresentations.requireNoSum(CoreRepresentations.expression(expr), "global binding");
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
    @Override public void initializeNative(thc.Language.State owner) {
        for (var link : foreignLinks) owner.cbits().link(link);
        for (var link : packageScalarLinks) owner.getPackageCbits().link(link);
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
    @Override public Map<String, Object> rootCounts() {
        return Map.of("sourceRootCount", attachedRootCount.get(), "loweredRootCount", constructedRootCount.get(),
            "hostEntryRootCount", hostEntries.size(), "initializedBindingCount", initializedBindingCount.get());
    }
    @Override public Map<String, Object> diagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("backend", "ast");
        result.put("asyncExceptions", eagerAsyncPolls);
        result.put("sourceNotesEnabled", sources.getEnabled());
        result.put("sourceSpanCount", sources.getSpanCount());
        result.putAll(rootCounts());
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
        result.put("foreignUnsupportedPolicy", "trap-when-reached");
        result.put("deferredUnsupported", new ArrayList<>(deferredUnsupported));
        result.put("unsupportedTraps", metrics.getUnsupportedTraps());
        result.put("frames", "indexed primitive slots; selective StaticShape captures");
        result.put("stackPolicy", capturesContinuations ? "tail-safe; bounded AST activation chains" :
            "tail-safe; non-tail calls and nested thunk forcing use host stack");
        result.put("threadPolicy", enableAsync ? "context-owned Java threads; captured asynchronous delivery" :
            "context-owned Java threads; external asynchronous delivery disabled");
        return result;
    }
    private boolean representation(Map<String, Object> binding) {
        if (binding.get("lifted") instanceof Boolean lifted) return lifted;
        return CoreRepresentations.mayBeLazy(binding.get("lifted"), CoreRepresentations.binder(binding));
    }
    private FunctionSpec function(String label, List<Map<String, Object>> args, List<Object> expression, Scope outer) {
        return function(label, args, expression, outer, CoreRepresentations.expression(expression),
            new boolean[args.size()], FunctionRootRole.FUNCTION, true);
    }
    private FunctionSpec function(String label, List<Map<String, Object>> args, List<Object> expression, Scope outer,
                                  CoreRepresentation resultProof, boolean[] entryStrict, FunctionRootRole role, boolean bodyTail) {
        return function(label, args, expression, outer, resultProof, entryStrict, role, bodyTail, null);
    }
    private FunctionSpec function(String label, List<Map<String, Object>> args, List<Object> expression, Scope outer,
                                  CoreRepresentation resultProof, boolean[] entryStrict, FunctionRootRole role, boolean bodyTail,
                                  Map<String,Object> initialization) {
        return function(label, args, expression, outer, resultProof, entryStrict, role, bodyTail, initialization, null);
    }
    private FunctionSpec function(String label, List<Map<String, Object>> args, List<Object> expression, Scope outer,
                                  CoreRepresentation resultProof, boolean[] entryStrict, FunctionRootRole role, boolean bodyTail,
                                  Map<String,Object> initialization, Expr initializedBody) {
        if (entryStrict.length != args.size()) throw new RuntimeFault("Function entry contract arity mismatch");
        List<Object> defaultArm = null;
        if (deferDefaultArm && !outlineCaseArms && !delimited &&
                role == FunctionRootRole.FUNCTION && expression.getFirst().equals("case")) {
            List<List<Object>> alternatives = (List<List<Object>>) expression.get(3);
            if (alternatives.size() == 1) {
                List<Object> alternative = alternatives.getFirst();
                if (alternative.getFirst().equals("default") && ((List<?>) alternative.get(2)).isEmpty())
                    defaultArm = (List<Object>) alternative.get(3);
            }
        }
        Scope scope = initializedBody == null ? new Scope(new FrameLayout(), defaultArm) : outer;
        if (reusableCode) scope.programSlot = scope.layout.bind("<program instance>", FrameSlotKind.Object);
        Set<String> free = coreFreeVariables(expression);
        Set<String> argumentIds = new LinkedHashSet<>();
        for (Map<String, Object> arg : args) argumentIds.add((String) arg.get("id"));
        for (Map<String, Object> arg : args) CoreRepresentations.requireInput(CoreRepresentations.binder(arg));
        List<CoreRepresentation> inputProofs = new ArrayList<>();
        for (Map<String, Object> arg : args) inputProofs.add(CoreRepresentations.binder(arg));
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
                CoreRepresentations.requireInput(local.proof);
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
                CoreRepresentations.requireScalar(local.proof, "capture");
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
            CoreRepresentation proof = CoreRepresentations.binder(arg);
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
            } else if (proof.getKind() == CoreKind.VOID) {
                scope.bindVoid((String)arg.get("id"), proof);
            } else if (free.contains(arg.get("id")) || capturesContinuations && entryStrict[i]) {
                argumentIndices.add(ArgumentLayout.offset(inputLayout, i));
                argumentProofs.add(proof);
                argumentSlots.add(scope.bind((String) arg.get("id"), !lifted && !Boolean.TRUE.equals(arg.get("coercion")), proof).slot);
            }
        }
        CaptureLayout captures = null;
        if (reusableCode && captureFields.isEmpty()) captures = new CaptureLayout(language, new boolean[0]);
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
        Arrays.fill(allArgumentProofs, CoreRepresentation.UNKNOWN);
        for (int i = 0; i < args.size(); i++) {
            Local local = scope.locals.get(args.get(i).get("id"));
            if (local != null && !local.proof.isTypedTransport()) { allArgumentSlots[i] = local.slot; allArgumentProofs[i] = local.proof; }
        }
        if (role == FunctionRootRole.FUNCTION) scope.self = new AstSelfLayout(captures, environmentSlots,
            allArgumentSlots, allArgumentProofs, entryStrict.clone(), inputLayout, environmentVectorSlots);
        Expr body = initializedBody != null ? initializedBody : initialization == null ? compile(expression, scope, bodyTail) : initializer(initialization, scope);
        if (initialization != null) {
            RootCallTarget ownTarget = body instanceof MakeClosure closure ? closure.target() : body instanceof Delay thunk ? thunk.target() : null;
            if (ownTarget != null && ownTarget.getRootNode() instanceof GuestRoot ownRoot)
                ownRoot.configureCoreIdentity(CoreFunctionIdentity.from(sourceModule, initialization));
        }
        HandoffEntry handoff = null;
        if (!reusableCode && (inputLayout == null || !inputLayout.getRequiresTyped())) {
            List<CoreRepresentation> declared = new ArrayList<>();
            for (Map<String, Object> arg : args) declared.add(CoreRepresentations.binder(arg));
            handoff = HandoffEntry.create(language, scope.layout, declared, resultProof, captures != null);
        }
        if ((body.getRepresentation().isSum() || resultProof.isSum()) && (!body.getRepresentation().isSum() || !resultProof.isSum()))
            throw new RuntimeFault("Sum function requires exact body and declared result proofs");
        CoreRepresentation effectiveResult = body.getRepresentation().refine(resultProof);
        TupleShape tuple = effectiveResult.isTypedTransport() ? new TupleShape(effectiveResult, (thc.Language) language) : null;
        int[] tupleSlots = new int[tuple == null ? 0 : tuple.getWidth()];
        for (int i = 0; i < tupleSlots.length; i++) tupleSlots[i] = scope.layout.bind("<typed return " + i + ">");
        FrameDescriptor descriptor = scope.layout.build();
        FunctionRoot root = new FunctionRoot(language, descriptor, label, captures, environmentSlots,
            ints(argumentSlots), ints(argumentIndices), body, codeMetrics(), argumentProofs.toArray(CoreRepresentation[]::new), resultProof,
            rootSource(body), entryStrict, handoff, tuple, tupleSlots, inputLayout, enableAsync, environmentVectorSlots,
            delimited, role, outlineCaseArms || deferDefaultArm,
            scope.deferredArms != null && !scope.deferredArms.isEmpty(), false);
        // Same-frame regions deliberately expose the running activation to side roots.
        // Keep their frame identity even before any graph-budget extraction occurs.
        root.configureInitialFrameCopy(!delimited &&
            com.oracle.truffle.api.nodes.NodeUtil.findFirstNodeInstance(body, AstSameFrameArm.class) == null);
        root.configureInitialClears(scope.layout.initialClears());
        root.configureInputProofs(inputProofs);
        root.configureScalarVoidInputs(inputProofs);
        root.configureEagerAsyncPolls(eagerAsyncPolls);
        if (reusableCode) root.configureProgramSlot(scope.programSlot, codeIdentity);
        if (scope.deferredArms != null) for (DeferredArm candidate : scope.deferredArms) {
            AstDeferredArm.PreparedBody prepared = new AstDeferredArm.PreparedBody(
                candidate.node.getRepresentation(), candidate.node.getCoreSourceLocation());
            FunctionRoot side = new FunctionRoot(language, descriptor.copy(), label + " default arm",
                candidate.captures, candidate.slots, new int[0], new int[0], prepared, metrics,
                new CoreRepresentation[0], candidate.node.getRepresentation(), prepared.getCoreSourceLocation(), new boolean[0],
                null, null, new int[0], null, false, new int[0][], false,
                FunctionRootRole.PASS_THROUGH, true, false, true);
            side.configureForeignExceptionBridge(foreignExceptionBridge);
            candidate.node.prepare(side.getCallTarget(), prepared);
            constructedRootCount.incrementAndGet();
        }
        constructedRootCount.incrementAndGet();
        root.configureForeignExceptionBridge(reusableCode ? null : foreignExceptionBridge);
        if (language instanceof thc.Language thc) root.configureTypedInput(TypedInputLayout.create(thc, inputLayout, captures != null));
        // Leading-case plans retain concrete layouts. Reusable cases resolve
        // their invocation's constructor owner through the indexed program slot.
        if (!reusableCode && role == FunctionRootRole.FUNCTION && body instanceof Case && inputLayout == null) {
            Set<String> used = new LinkedHashSet<>(free);
            used.retainAll(argumentIds);
            root.configureLeadingCaseReturn(LeadingCaseReturn.discover(args, expression, resultProof, root.getEntryArgumentOffset(),
                used, captures != null, this::dataLayout, sources, body.getCoreSourceLocation()));
        }
        RootCallTarget target = root.getCallTarget();
        if (reusableCode) codeTargets.add(target);
        return new FunctionSpec(target, captures, captureSources);
    }
    private boolean sameFrameCandidate(List<Object> expression, Scope scope) {
        if (!enableAsync || outlineCaseArms || deferDefaultArm || scope.joins.isEmpty() ||
                !Arrays.asList("app", "case", "let").contains(expression.getFirst())) return false;
        for (String free : coreFreeVariables(expression))
            if (scope.joins.containsKey(free)) return false;
        return true;
    }
    private boolean substantialSiblings(List<List<Object>> alternatives) {
        if (!reusableCode || delimited || alternatives.size() < 2 || alternatives.size() > 32) return false;
        int remaining = 128;
        for (List<Object> alternative : alternatives) {
            remaining -= boundedCoreSize((List<?>) alternative.get(3), remaining);
            if (remaining == 0) return true;
        }
        return false;
    }
    /** A bounded syntax estimate, not a compiler graph budget. Metadata is not code. */
    private static int boundedCoreSize(List<?> expression, int limit) {
        if (limit <= 0) return 0;
        int size = 1;
        switch ((String) expression.getFirst()) {
            case "lam" -> size += boundedCoreSize((List<?>) expression.get(2), limit - size);
            case "app" -> {
                size += boundedCoreSize((List<?>) expression.get(1), limit - size);
                for (Object argument : (List<?>) expression.get(2)) {
                    size += boundedCoreSize((List<?>) argument, limit - size);
                    if (size == limit) break;
                }
            }
            case "case" -> {
                size += boundedCoreSize((List<?>) expression.get(1), limit - size);
                for (Object alternative : (List<?>) expression.get(3)) {
                    size += boundedCoreSize((List<?>) ((List<?>) alternative).get(3), limit - size);
                    if (size == limit) break;
                }
            }
            case "let" -> {
                for (Object binding : (List<?>) expression.get(2)) {
                    size += boundedCoreSize((List<?>) ((Map<?,?>) binding).get("expr"), limit - size);
                    if (size == limit) break;
                }
                size += boundedCoreSize((List<?>) expression.get(3), limit - size);
            }
            default -> { }
        }
        return size;
    }
    private Expr caseArm(List<Object> expression, Scope scope, boolean tail, int alternativeCount, boolean substantialSiblings) {
        if (sameFrameCandidate(expression, scope)) {
            OperandBuilder operands = lowering().operands;
            lowering().operands = null;
            try { return new AstSameFrameArm(compile(expression, scope, tail)); }
            finally { lowering().operands = operands; }
        }
        if (expression == scope.deferredExpression) {
            CoreRepresentation proof = CoreRepresentations.expression(expression);
            Set<String> free = coreFreeVariables(expression);
            List<Local> locals = new ArrayList<>();
            boolean eligible = deferredScalar(proof);
            for (String id : free) {
                if (scope.joins.containsKey(id)) eligible = false;
                Local local = scope.locals.get(id);
                if (local != null) {
                    locals.add(local);
                    if (local.cell || local.slot < 0 || !deferredScalar(local.proof)) eligible = false;
                }
            }
            if (eligible) {
                int count = locals.size();
                boolean[] exactLong = new boolean[count], exactFloat = new boolean[count], exactDouble = new boolean[count];
                NarrowInteger[] narrow = new NarrowInteger[count];
                int[] slots = new int[count];
                for (int i = 0; i < count; i++) {
                    Local local = locals.get(i);
                    exactLong[i] = local.proof.isLong();
                    exactFloat[i] = local.proof.isFloat();
                    exactDouble[i] = local.proof.isDouble();
                    narrow[i] = local.proof.getNarrowInteger();
                    slots[i] = local.slot;
                }
                CaptureLayout captures = count == 0 ? null : CaptureLayout.withVectors(Objects.requireNonNull(language),
                    new CoreRepresentation[count], exactLong, exactLong, new Class<?>[count], exactFloat, exactDouble, narrow);
                OperandBuilder operands = lowering().operands;
                lowering().operands = null;
                Scope child = scope.child();
                child.self = null;
                Expr body;
                try { body = compile(expression, child, tail); }
                finally { lowering().operands = operands; }
                AstDeferredArm node = new AstDeferredArm(body, captures, slots, tail);
                scope.deferredArms.add(new DeferredArm(node, captures, slots));
                return node;
            }
        }
        // Split wide cold dispatches into side roots while retaining continuation
        // capability; references to outer lexical joins remain in their frame.
        // Split modest bodies of an expensive sibling dispatch, not giant
        // singleton prefixes that would merely move the same large graph.
        int size = substantialSiblings ? boundedCoreSize(expression, 129) : 0;
        boolean outline = outlineCaseArms || reusableCode && !delimited &&
            (alternativeCount > 32 || substantialSiblings && size >= 12 && size <= 128);
        boolean hasLocalJoin = false;
        if (outline && Arrays.asList("app", "case", "let").contains(expression.getFirst()))
            for (String free : coreFreeVariables(expression)) if (scope.joins.containsKey(free)) { hasLocalJoin = true; break; }
        if (!outline || !Arrays.asList("app", "case", "let").contains(expression.getFirst()) || hasLocalJoin)
            return compile(expression, scope, tail);
        OperandBuilder operands = lowering().operands;
        lowering().operands = null;
        FunctionSpec fn;
        try { fn = function("case arm", List.of(), expression, scope, CoreRepresentations.expression(expression),
            new boolean[0], FunctionRootRole.PASS_THROUGH, tail); }
        finally { lowering().operands = operands; }
        return new AstCaseArm(fn.target, fn.captureLayout, fn.captures, tail, scope.programSlot).located(lowering().source);
    }
    private FrameSlotKind outlinedSlotKind(CoreRepresentation proof, boolean cell) {
        if (reusableCode) return cell || !proof.getEvaluated() || proof.getKind() == CoreKind.UNKNOWN
            ? FrameSlotKind.Object : FrameLayout.carrierKind(proof);
        if (!outlineCaseArms && !deferDefaultArm) return FrameSlotKind.Illegal;
        if (cell || proof.isVector()) return FrameSlotKind.Object;
        if (!proof.getEvaluated()) return FrameSlotKind.Illegal;
        if (proof.isInt()) return FrameSlotKind.Int;
        if (proof.isLong()) return FrameSlotKind.Long;
        if (proof.isFloat()) return FrameSlotKind.Float;
        if (proof.isDouble()) return FrameSlotKind.Double;
        if (proof.isEvaluatedReference()) return FrameSlotKind.Object;
        return FrameSlotKind.Illegal;
    }
    private static boolean deferredScalar(CoreRepresentation proof) {
        return proof.getEvaluated() && (proof.isInt() || proof.isLong() || proof.isFloat() || proof.isDouble());
    }
    private Expr delay(List<Object> expr, Scope scope, String label) {
        FunctionSpec fn = function(label, List.of(), expr, scope);
        TupleShape shape = ((GuestRoot) fn.target.getRootNode()).getTupleResult();
        if (shape != null) CoreRepresentations.requireScalar(shape.getProof(), "thunk");
        return new Delay(fn.target, fn.captureLayout, fn.captures, scope.programSlot).proven(evaluated(CoreRepresentations.expression(expr), false))
            .located(sources.expression(expr, lowering().source));
    }
    private Expr argument(List<Object> expr, Scope scope, boolean lifted) { return argument(expr, scope, lifted, "argument thunk", false, lifted); }
    private Expr argument(List<Object> expr, Scope scope, boolean lifted, String label, boolean allowEmpty, boolean declaredLifted) {
        OperandBuilder operands = lowering().operands;
        lowering().operands = null;
        Expr result;
        try { result = argumentUnsequenced(expr, scope, lifted, label, allowEmpty, declaredLifted); }
        finally { lowering().operands = operands; }
        return operands == null ? result : operands.operand(result);
    }
    private void checkArgument(CoreRepresentation proof, boolean allowEmpty, boolean declaredLifted) {
        if (allowEmpty || proof.isVector()) CoreRepresentations.requireInput(proof);
        else CoreRepresentations.requireScalar(proof, "argument");
        if (proof.isTypedTransport() && declaredLifted) throw new RuntimeFault("Typed argument cannot be lifted");
    }
    private Expr argumentUnsequenced(List<Object> expr, Scope scope, boolean lifted, String label, boolean allowEmpty, boolean declaredLifted) {
        CoreRepresentation proof = CoreRepresentations.expression(expr);
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
            return new Evaluate(result, codeMetrics());
        }
        List<?> head = at(expr, 1) instanceof List<?> candidate && "app".equals(expr.getFirst()) &&
            "var".equals(at(candidate, 0)) ? candidate : null;
        String headId = head != null && at(head, 1) instanceof String id ? id : null;
        CoreApplicationCertificates.Arity certificate = headId == null ? null :
            scope.locals.containsKey(headId) ? required(scope.locals, headId).arityCertificate : globalArityCertificates.get(headId);
        boolean unopenedHead = headId != null && !scope.locals.containsKey(headId) && !globalArityCertificates.containsKey(headId) &&
            demand != null && demand.contains(headId);
        if (unopenedHead && CoreApplicationCertificates.eagerApplication(expr, null)) {
            Expr application = compile(expr, scope, false);
            checkArgument(application.getRepresentation(), allowEmpty, declaredLifted);
            return new DeferredPap(Objects.requireNonNull(demand.cell(headId)), ((List<?>) expr.get(2)).size(),
                application, delay(expr, scope, label)).located(sources.expression(expr, lowering().source));
        }
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
        if (encoded instanceof Map<?,?> document) encoded = CoreFloatingLiteral.fromDocument(document);
        if (encoded instanceof CoreFloatingLiteral floating) return floating.decode(kind);
        if (!(encoded instanceof String value)) throw new UnsupportedCore("Malformed Core literal payload");
        return switch (kind) {
            case "int8" -> ScalarLiterals.int8Literal(value);
            case "int16" -> ScalarLiterals.int16Literal(value);
            case "int32" -> ScalarLiterals.int32Literal(value);
            case "int64" -> ScalarLiterals.int64Literal(value);
            case "word64" -> word64Literal(value);
            case "int", "char" -> Long.parseLong(value);
            case "word" -> Long.parseUnsignedLong(value);
            case "float" -> Float.parseFloat(value);
            case "double" -> Double.parseDouble(value);
            case "word8", "word16", "word32" -> ScalarLiterals.narrowWordLiteral(kind, value);
            case "string-bytes" -> ManagedAddress.fromHex(value);
            case "null-addr" -> {
                if (!value.equals("0")) throw new UnsupportedCore("Malformed null Addr# literal");
                yield ManagedAddress.nullAddress();
            }
            case "function-addr" -> nativeCallbacks.containsKey(value)
                ? thc.Language.currentState(null).getNativeCallbacks().helper(nativeCallbacks.get(value), this, (thc.Language) language)
                : CFinalizerLabels.fromCore(value, proof);
            case "data-addr" -> CoreDataLabels.fromCore(value, proof, stackTargetLayout instanceof TargetLayout target ? target : null);
            case "bignat" -> {
                byte[] bytes = BigNatLiterals.decode(value);
                yield reusableCode ? bytes : ManagedByteArray.fromFreshBytes(bytes);
            }
            default -> throw new UnsupportedCore("Unsupported literal kind " + kind);
        };
    }
    private Expr compile(List<Object> expr, Scope scope, boolean tail) {
        List<Object> inline = CoreStateApplications.inline(expr);
        if (inline != null) return compile(inline, scope, tail);
        OperandBuilder outer = lowering().operands;
        Object head = at(expr, 1) instanceof List<?> value ? at(value, 0) : null;
        var metadata = CoreRepresentations.metadata(expr);
        boolean foreign = "var".equals(head) && metadata != null && metadata.containsKey("foreignCall");
        // A foreign operand can suspend before the call, just like a primop operand.
        OperandBuilder operands = capturesContinuations && "app".equals(at(expr, 0)) && (Arrays.asList("prim", "con").contains(head) || foreign) ?
            new OperandBuilder(scope.layout) : null;
        lowering().operands = operands;
        Expr result;
        try {
            result = withSource(sources.expression(expr, lowering().source), () -> {
                Expr node = compileLocated(expr, scope, tail).located(lowering().source);
                return (operands == null ? node : operands.finish(node)).located(lowering().source);
            });
        } finally { lowering().operands = outer; }
        return outer == null ? result : outer.operand(result);
    }
    private Expr compileLocated(List<Object> expr, Scope scope, boolean tail) {
        try {
            Expr lowered = compileSupported(expr, scope, tail);
            CoreRepresentation metadata = diagnosticUnsupported && lowered instanceof GlobalRead ? CoreRepresentation.UNKNOWN :
                CoreRepresentations.expression(expr);
            if ("case".equals(expr.getFirst())) metadata = CoreRepresentations.caseResult(expr).refine(metadata);
            return lowered.proven(lowered.getRepresentation().refine(evaluated(metadata, false)));
        } catch (UnsupportedCore gap) {
            if (!diagnosticUnsupported) throw gap;
            String message = gap.getMessage() != null ? gap.getMessage() : "Unsupported Core";
            deferredUnsupported.add(message);
            Expr body = new UnsupportedExpression(message, metrics).located(lowering().source);
            RootCallTarget target = new FunctionRoot(language, new FrameLayout().build(), "unsupported: " + message, null,
                new int[0], new int[0], new int[0], body, metrics, new CoreRepresentation[0], body.getRepresentation(),
                rootSource(body), new boolean[0], null, null, new int[0], null, false, new int[0][], false,
                FunctionRootRole.FUNCTION, outlineCaseArms).getCallTarget();
            constructedRootCount.incrementAndGet();
            return new DiagnosticUnavailable(target, message, metrics);
        }
    }

    private Expr compileSumCase(List<Object> expr, Expr scrutinee, CoreRepresentation proof, Scope scope, boolean tail) {
        TupleShape shape = new TupleShape(proof, (thc.Language) language);
        int[] slots = new int[shape.getWidth()];
        for (int i = 0; i < slots.length; i++) slots[i] = scope.layout.bind("<sum case " + i + ">", FrameLayout.carrierKind(shape.getLeaves()[i]));
        scope.bindTuple((String) expr.get(2), proof, slots);
        Set<Integer> tags = new LinkedHashSet<>();
        int fallback = -1;
        List<List<Object>> alternatives = (List<List<Object>>) expr.get(3);
        boolean substantialSiblings = substantialSiblings(alternatives);
        Expr[] arms = new Expr[alternatives.size()];
        for (int index = 0; index < alternatives.size(); index++) {
            List<Object> alt = alternatives.get(index);
            Scope child = scope.child();
            List<Integer> conversionSlots = new ArrayList<>();
            List<Expr> conversionValues = new ArrayList<>();
            List<String> ids = (List<String>) alt.get(2);
            if ("default".equals(alt.getFirst())) {
                if (fallback >= 0 || !ids.isEmpty() || !CoreRepresentations.alternativeBinders(alt).isEmpty())
                    throw new RuntimeFault("Invalid sum DEFAULT alternative");
                fallback = index;
            } else {
                if (!"data".equals(alt.getFirst()) || ids.size() != 1) throw new RuntimeFault("Invalid sum alternative");
                int tag = SumShape.constructor(proof, constructors.get(alt.get(1)), ids.size());
                if (!tags.add(tag)) throw new RuntimeFault("Duplicate sum alternative tag");
                CoreRepresentation component = Objects.requireNonNull(proof.getAlternatives()).get(tag - 1);
                List<Map<String, Object>> metadata = CoreRepresentations.alternativeBinders(alt);
                if (metadata.size() != 1 || !Objects.equals(metadata.getFirst().get("id"), ids.getFirst()))
                    throw new RuntimeFault("Missing sum payload binder proof");
                CoreRepresentation actual = CoreRepresentations.binder(metadata.getFirst());
                Object lifted = metadata.getFirst().get("lifted");
                CoreRepresentations.mayBeLazy(lifted, actual);
                SumShape.payload(component, actual, (Boolean) lifted);
                CoreRepresentation field = evaluated(component.refine(actual), component.getEvaluated());
                List<CoreRepresentation> leaves = TupleShape.flatten(field);
                List<Integer> physical = SumShape.projection(proof, tag - 1);
                int[] projection = new int[physical.size()];
                for (int i = 0; i < physical.size(); i++) {
                    CoreRepresentation leaf = leaves.get(i);
                    if (!leaf.isInt()) projection[i] = slots[physical.get(i)];
                    else {
                        int destination = child.layout.bind("<narrow sum arm " + i + ">", FrameSlotKind.Int);
                        conversionSlots.add(destination);
                        conversionValues.add(new SumNarrowRead(slots[physical.get(i)], Objects.requireNonNull(leaf.getNarrowInteger()), leaf));
                        projection[i] = destination;
                    }
                }
                if (component.isTypedTransport()) child.bindTuple(ids.getFirst(), field, projection);
                else if (component.getKind() == CoreKind.VOID) child.bindVoid(ids.getFirst(), field);
                else child.bindSlot(ids.getFirst(), new Local(projection[0], component.isLong(), field, false, null, null, null));
            }
            Expr body = caseArm((List<Object>) alt.get(3), child, tail, alternatives.size(), substantialSiblings);
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
        CoreRepresentation result = arms[0].getRepresentation().refine(CoreRepresentations.caseResult(expr));
        for (Expr arm : arms) result.refine(arm.getRepresentation());
        List<CoreRepresentation> armProofs = new ArrayList<>();
        boolean allEvaluated = true;
        for (Expr arm : arms) { armProofs.add(arm.getRepresentation()); allEvaluated = allEvaluated && arm.getRepresentation().getEvaluated(); }
        CoreRepresentations.validateFloatingCaseResult(result, armProofs);
        return new SumCase(scrutinee, slots, arms, selected, evaluated(result, allEvaluated), !reusableCode);
    }
    private Expr compileVectorReadCase(VectorReadCase read, Scope scope, boolean tail) {
        Scope local = scope.child();
        local.bindVoid(read.getStateBinder(), CoreVectorMemory.getStateProof());
        CoreRepresentation proof = read.getOperation().getVectorProof();
        int[] lanes = new int[TupleShape.flatten(proof).size()];
        for (int i = 0; i < lanes.length; i++) lanes[i] = local.layout.bind("<vector read lane " + i + ">");
        local.bindTuple(read.getVectorBinder(), proof, lanes);
        Expr[] operands = new Expr[read.getArguments().size()];
        for (int i = 0; i < operands.length; i++) operands[i] = compile(read.getArguments().get(i), scope, false);
        Expr value = (read.getOperation().isAddress() ? new VectorAddressExpression(read.getOperation(), operands) :
            new VectorByteArrayExpression(read.getOperation(), operands)).located(lowering().source);
        Expr body = compile(read.getBody(), local, tail);
        return new Let(new int[] {-1}, new Expr[] {value}, new boolean[] {false}, body, false, new int[][] {lanes});
    }
    private Expr compileVectorCase(List<Object> expr, Expr scrutinee, CoreRepresentation proof, Scope scope, boolean tail) {
        CoreRepresentations.requireInput(proof);
        List<List<Object>> alternatives = (List<List<Object>>) expr.get(3);
        if (alternatives.size() != 1) throw new RuntimeFault("Vector case requires one default alternative");
        List<Object> only = alternatives.getFirst();
        if (!"default".equals(only.getFirst()) || !((List<?>) only.get(2)).isEmpty())
            throw new RuntimeFault("Vector case requires one default alternative");
        int[] lanes = new int[TupleShape.flatten(proof).size()];
        for (int i = 0; i < lanes.length; i++) lanes[i] = scope.layout.bind("<vector case lane " + i + ">", FrameLayout.carrierKind(TupleShape.flatten(proof).get(i)));
        scope.bindTuple((String) expr.get(2), proof, lanes);
        return new Let(new int[] {-1}, new Expr[] {scrutinee}, new boolean[] {false},
            compile((List<Object>) only.get(3), scope, tail), false, new int[][] {lanes});
    }
    private Expr compileTupleCase(List<Object> expr, Expr scrutinee, CoreRepresentation proof, Scope local, boolean tail) {
        TupleShape shape = new TupleShape(proof, (thc.Language) language);
        int[] slots = new int[shape.getWidth()];
        for (int i = 0; i < slots.length; i++) slots[i] = local.layout.bind("<tuple case " + i + ">",
            FrameLayout.carrierKind(shape.getLeaves()[i]));
        local.bindTuple((String) expr.get(2), proof, slots);
        List<List<Object>> alternatives = (List<List<Object>>) expr.get(3);
        if (alternatives.isEmpty()) return new TupleCase(scrutinee, slots, new EmptyCaseResult(CoreRepresentations.caseResult(expr)));
        if (alternatives.size() != 1) throw new RuntimeFault("Tuple case requires at most one alternative");
        List<Object> alt = alternatives.getFirst();
        List<String> ids = (List<String>) alt.get(2);
        if ("data".equals(alt.getFirst())) {
            Map<String, Object> constructor = constructors.get(alt.get(1));
            if (constructor == null || !"unboxed-tuple".equals(constructor.get("kind")) || ids.size() != shape.getComponents().length ||
                !(constructor.get("arity") instanceof Number number) || number.intValue() != ids.size())
                throw new RuntimeFault("Tuple alternative shape mismatch");
            List<Map<String, Object>> metadata = CoreRepresentations.alternativeBinders(alt);
            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i);
                CoreRepresentation component = shape.getComponents()[i];
                CoreRepresentation raw = i < metadata.size() ? CoreRepresentations.binder(metadata.get(i)) : component;
                TupleShape.requireCompatible(component, raw, true);
                CoreRepresentation field = component.refine(raw);
                int width = TupleShape.flatten(component).size(), offset = shape.getOffsets()[i];
                if (component.isTypedTransport()) local.bindTuple(id, evaluated(component, true), Arrays.copyOfRange(slots, offset, offset + width));
                else if (component.getKind() == CoreKind.VOID) local.bindVoid(id, field);
                else local.bindSlot(id, new Local(slots[offset], component.isLong(), evaluated(field, component.isLong() || component.getEvaluated()), false, null, null, null));
            }
        } else if (!"default".equals(alt.getFirst()) || !ids.isEmpty()) throw new RuntimeFault("Invalid tuple alternative");
        int[] dead = CoreFreeVariables.unusedTupleReferences(expr, shape);
        for (int i = 0; i < dead.length; i++) dead[i] = slots[dead[i]];
        local.layout.clearInitially(dead);
        return new TupleCase(scrutinee, slots, caseArm((List<Object>) alt.get(3), local, tail, 1, false), dead);
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
            CoreRepresentations.requireJoinArgument(target.getProofs()[i], nodes[i].getRepresentation());
            // The returning argument may be larger than any one nested case arm.
            // Keep argument preparation and the actual local jump in this region.
            if (sameFrameCandidate(args.get(i), scope)) nodes[i] = new AstSameFrameArm(nodes[i]);
        }
        int[][] typedTemps = new int[nodes.length][];
        int[] temps = new int[nodes.length];
        for (int i = 0; i < nodes.length; i++) {
            if (target.getProofs()[i].isTypedTransport()) {
                typedTemps[i] = new int[ArgumentLayout.leaves(target.getProofs()[i]).size()];
                for (int j = 0; j < typedTemps[i].length; j++) typedTemps[i][j] = scope.layout.bind("<join typed argument " + i + " field " + j + ">", FrameLayout.carrierKind(ArgumentLayout.leaves(target.getProofs()[i]).get(j)));
                temps[i] = -1;
            } else temps[i] = scope.layout.bind("<join argument " + i + ">", reusableCode ?
                FrameLayout.carrierKind(target.getProofs()[i]) : FrameSlotKind.Illegal);
        }
        return new LocalJoinCall((thc.Language) language, target, nodes, temps, codeMetrics(), typedTemps);
    }
    private Expr compileJoins(List<Object> expr, Scope outer, boolean tail, List<CoreJoinDefinition> definitions) {
        boolean recursive = Boolean.TRUE.equals(expr.get(1));
        Set<String> shadowed = new LinkedHashSet<>();
        if (recursive) for (CoreJoinDefinition definition : definitions) shadowed.add(definition.getId());
        for (CoreJoinDefinition definition : definitions) {
            for (Map<String, Object> parameter : definition.getParameters()) {
                CoreRepresentation proof = CoreRepresentations.binder(parameter);
                CoreRepresentations.requireInput(proof);
                if (proof.isTypedTransport() && representation(parameter)) throw new RuntimeFault("Typed join formal must be unlifted");
            }
            Set<String> formals = new LinkedHashSet<>();
            for (Map<String, Object> parameter : definition.getParameters()) formals.add((String) parameter.get("id"));
            for (String id : coreFreeVariables(definition.getBody())) {
                if (formals.contains(id) || shadowed.contains(id)) continue;
                Local captured = outer.locals.get(id);
                if (captured == null) continue;
                if (captured.proof.isTypedTransport()) {
                    CoreRepresentations.requireInput(captured.proof);
                    int[] slots = captured.tupleSlots;
                    if (slots == null) throw new RuntimeFault("Missing typed join capture slots");
                    boolean invalid = slots.length != ArgumentLayout.leaves(captured.proof).size();
                    if (!invalid) for (int slot : slots) if (slot < 0) { invalid = true; break; }
                    if (invalid) throw new RuntimeFault("Typed join capture disagrees with its physical slots");
                } else CoreRepresentations.requireScalar(captured.proof, "join capture");
            }
        }
        CoreJoins.validate((List<Map<String, Object>>) expr.get(2), (List<Object>) expr.get(3), recursive);
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
                CoreRepresentation proof = CoreRepresentations.binder(parameter);
                if (lifted) proof = evaluated(proof, entryStrict[i]);
                if (proof.isTypedTransport()) {
                    int[] lanes = new int[ArgumentLayout.leaves(proof).size()];
                    for (int j = 0; j < lanes.length; j++) lanes[j] = scope.layout.bind(parameter.get("id") + " join typed field " + j, FrameLayout.carrierKind(ArgumentLayout.leaves(proof).get(j)));
                    scope.bindTuple((String) parameter.get("id"), evaluated(proof, true), lanes);
                } else scope.bind((String) parameter.get("id"), !lifted && !Boolean.TRUE.equals(parameter.get("coercion")),
                    proof, false, null, null, reusableCode ? FrameLayout.carrierKind(proof) : FrameSlotKind.Illegal);
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
        // Formals and outer captures precede this range. Result/selector slots
        // follow it, so a join transfer clears only completed entry/body locals.
        int firstBodySlot = local.layout.nextSlot();
        Expr entry = compile((List<Object>) expr.get(3), local, tail);
        List<Expr> bodies = new ArrayList<>();
        for (int i = 0; i < definitions.size(); i++) {
            CoreJoinDefinition definition = definitions.get(i);
            Scope bodyScope = bodyScopes.get(i);
            bodies.add(withSource(sources.binding(definition.getBinding(), lowering().source), () -> {
                Expr node = compile(definition.getBody(), bodyScope, tail);
                node.setRepresentation(node.getRepresentation().refine(evaluated(definition.getResult(), false)));
                return node;
            }));
        }
        int bodySlotLimit = local.layout.nextSlot();
        for (LocalJoinTarget target : targets) target.setBodySlots(firstBodySlot, bodySlotLimit);
        CoreRepresentation result = entry.getRepresentation().refine(evaluated(CoreRepresentations.expression(expr), false));
        for (Expr body : bodies) TupleShape.requireCompatible(result, body.getRepresentation(), false);
        boolean allEvaluated = entry.getRepresentation().getEvaluated();
        for (Expr body : bodies) allEvaluated = allEvaluated && body.getRepresentation().getEvaluated();
        result = evaluated(result, allEvaluated);
        TupleShape tuple = result.isTypedTransport() ? new TupleShape(result, (thc.Language) language) : null;
        int[] tupleSlots = new int[tuple == null ? 0 : tuple.getWidth()];
        for (int i = 0; i < tupleSlots.length; i++) tupleSlots[i] = local.layout.bind("<join tuple result " + i + ">", FrameLayout.carrierKind(tuple.getLeaves()[i]));
        Expr[] nodes = new Expr[bodies.size() + 1]; nodes[0] = entry;
        for (int i = 0; i < bodies.size(); i++) nodes[i + 1] = bodies.get(i);
        return new LocalJoinRegion(identity, local.layout.bind("<join selector>", reusableCode ? FrameSlotKind.Long : FrameSlotKind.Illegal),
            local.layout.bind("<join result>", reusableCode ? FrameLayout.carrierKind(result) : FrameSlotKind.Illegal),
            nodes, result, recursive, tuple, tupleSlots, delimited);
    }
    private DataLayout dataLayout(String id) {
        try (var ownership = preparationLock.acquire()) {
            DataLayout layout = dataLayouts.get(id);
            if (layout != null) return layout;
            Map<String, Object> info = constructors.get(id);
            if (info == null) throw new RuntimeFault("Missing constructor metadata " + id);
            CoreFields fields = new CoreFields(info);
            if (language == null) throw new RuntimeFault("Constructor layout requires a guest language");
            return constructorStorage(id, (String) info.get("name"), fields);
        }
    }
    private DataLayout constructorStorage(String id, String name, CoreFields fields) {
        try (var ownership = preparationLock.acquire()) {
            DataLayout layout = dataLayouts.get(id);
            if (layout != null) return layout;
            if (reusableCode) {
                layout = new DataLayout.Reusable(language, id, name, fields).instantiate();
                int index = constructorIndices.size(); constructorIndices.put(id, index); if (indexedLayouts != null) indexedLayouts[index] = layout;
            } else layout = thc.Language.currentState().constructorLayout(language, id, name, fields);
            dataLayouts.put(id, layout);
            return layout;
        }
    }
    private Expr globalRead(String id, Scope scope) {
        GlobalBinding global = globals.get(id);
        if (global == null) throw new UnsupportedCore("Unresolved global binding " + id);
        if (reusableCode) lowering().dependencies.add(id);
        return reusableCode ? new GlobalRead(required(indices, id), scope.programSlot) : new GlobalRead(global);
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
                result[i] = CoreRepresentations.mayBeLazy(lifted.get(i), dataLayout(id).logicalProof(i));
            }
        }
        return result;
    }
    private int[][] constructorVectorSlots(DataLayout layout, FrameLayout frame) {
        int[][] result = new int[layout.getLogicalArity()][];
        for (int i = 0; i < result.length; i++) {
            CoreRepresentation proof = layout.logicalProof(i);
            if (proof != null && proof.isTypedTransport()) {
                List<CoreRepresentation> leaves = ArgumentLayout.leaves(proof);
                result[i] = new int[layout.logicalWidth(i)];
                for (int j = 0; j < result[i].length; j++) result[i][j] = frame.bind("<constructor " + layout.getId() + " field " + i + " lane " + j + ">",
                    FrameLayout.carrierKind(leaves.get(j)));
            }
        }
        return result;
    }
    private Expr construct(String id, Expr[] args, FrameLayout frame, int programSlot) {
        boolean[] strict = strictConstructorFields(id, args.length);
        Expr[] fields = new Expr[args.length];
        for (int i = 0; i < fields.length; i++) fields[i] = strict[i] ? new Evaluate(args[i], codeMetrics()) : args[i];
        DataLayout layout = dataLayout(id);
        return reusableCode ? new Construct(layout.reusableStorage(), programSlot, required(constructorIndices, id), fields, constructorVectorSlots(layout, frame)) :
            new Construct(layout, fields, constructorVectorSlots(layout, frame));
    }

    private Expr rubbish(CoreRepresentation proof, Scope scope) {
        Expr materializer = null;
        if (proof.isTuple()) {
            var fields = new Expr[proof.getComponents().size()];
            for (int i = 0; i < fields.length; i++) fields[i] = rubbish(proof.getComponents().get(i), scope);
            materializer = new TupleConstruct(new TupleShape(proof, (thc.Language) language), fields);
        } else if (proof.isSum()) {
            var selected = proof.getAlternatives().getFirst();
            int[] intSlots = new int[0];
            if (selected.isTypedTransport()) {
                var fields = TupleShape.flatten(selected);
                intSlots = new int[fields.size()];
                for (int i = 0; i < intSlots.length; i++) intSlots[i] = fields.get(i).isInt()
                    ? scope.layout.bind("<narrow rubbish sum payload " + i + ">", FrameSlotKind.Int) : -1;
            }
            materializer = new SumConstruct(new TupleShape(proof, (thc.Language) language), 1, rubbish(selected, scope), intSlots);
        }
        return new Rubbish(proof, reusableCode ? null : rubbishLiterals, materializer, reusableCode ? scope.programSlot : -1);
    }

    private Expr compileSupported(List<Object> expr, Scope scope, boolean tail) {
        return switch ((String) expr.get(0)) {
            case "var" -> {
                String id = (String) expr.get(1);
                CoreRepresentation occurrence = CoreRepresentations.expression(expr);
                Local local = scope.locals.get(id);
                CoreRepresentation proof = local != null ? local.proof : globalProofs.get(id);
                if (proof == null && demand != null) proof = demand.occurrence(id, occurrence);
                CoreVectors.requireVariableProof(proof, occurrence);
                if (scope.joins.containsKey(id)) yield joinJump(scope.joins.get(id), List.of(), List.of(), scope);
                if (local != null) {
                    if (local.tupleSlots != null) {
                        TupleShape shape = new TupleShape(local.proof, (thc.Language) language);
                        yield local.proof.isVector() ? new VectorLocalRead(shape, local.tupleSlots) : new TupleLocalRead(shape, local.tupleSlots);
                    }
                    if (local.slot < 0 && local.proof.getKind() == CoreKind.VOID) yield new Literal(thc.runtime.Unit.INSTANCE).proven(local.proof);
                    yield new LocalRead(local.slot, local.cell).proven(local.proof);
                }
                GlobalBinding global = globals.get(id);
                if (global != null) {
                    yield globalRead(id, scope)
                        .proven(proof != null ? proof : required(globalProofs, id));
                }
                throw new UnsupportedCore("Unresolved external binding " + id);
            }
            case "lit" -> {
                String tag = (String) expr.get(1);
                if (tag.equals("function-addr"))
                    yield new CFinalizerLabels((String) expr.get(2), CoreRepresentations.expression(expr),
                        nativeCallbacks.get(expr.get(2)), reusableCode ? scope.programSlot : -1, reusableCode ? null : this, (thc.Language) language);
                if (tag.equals("data-addr"))
                    yield new CoreDataLabels((String) expr.get(2), CoreRepresentations.expression(expr),
                        stackTargetLayout instanceof TargetLayout target ? target : null);
                if (tag.equals("rubbish")) yield rubbish(RubbishLiterals.proof(expr), scope);
                Literal value = new Literal(literal(tag, expr.get(2), CoreRepresentations.expression(expr)), reusableCode && tag.equals("bignat"));
                if (Arrays.asList("int8", "word8", "int16", "word16", "int32", "word32").contains(tag))
                    yield value.proven(CoreRepresentations.narrowLiteralProof(expr));
                if (tag.equals("bignat")) yield value.proven(BigNatLiterals.proof(expr));
                yield value;
            }
            case "void" -> new Literal(thc.runtime.Unit.INSTANCE);
            case "lam" -> {
                List<Map<String, Object>> args = (List<Map<String, Object>>) expr.get(1);
                StringJoiner names = new StringJoiner(", ");
                for (Map<String, Object> arg : args) names.add(String.valueOf(arg.get("name")));
                FunctionSpec fn = function("lambda " + names, args, (List<Object>) expr.get(2), scope,
                    CoreRepresentations.lambdaResult(expr), CoreEntries.lambda(expr), FunctionRootRole.FUNCTION, true);
                yield new MakeClosure(fn.target, args.size(), fn.captureLayout, fn.captures, scope.programSlot);
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
        var definitions = CoreJoins.definitions(group);
        if (definitions != null) return compileJoins(expr, scope, tail, definitions);
        for (Map<String, Object> binding : group) {
            CoreRepresentation proof = CoreRepresentations.binder(binding);
            if (proof.isTypedTransport()) {
                if (recursive || representation(binding)) throw new UnsupportedCore("Aggregate/vector let binding must be nonrecursive and unlifted");
                CoreRepresentations.requireInput(proof);
            } else CoreRepresentations.requireScalar(proof, "let binding");
        }
        Scope local = scope.child();
        int[][] typedSlots = new int[group.size()][];
        int[] slots = new int[group.size()];
        for (int index = 0; index < group.size(); index++) {
            Map<String, Object> binding = group.get(index);
            CoreRepresentation proof = CoreRepresentations.binder(binding);
            if (proof.isTypedTransport()) {
                List<CoreRepresentation> fields = TupleShape.flatten(proof);
                int[] lanes = new int[fields.size()];
                for (int i = 0; i < lanes.length; i++) lanes[i] = local.layout.bind(binding.get("id") + " let field " + i, FrameLayout.carrierKind(fields.get(i)));
                typedSlots[index] = lanes;
                slots[index] = local.bindTuple((String) binding.get("id"), proof, lanes).slot;
            } else slots[index] = local.bind((String) binding.get("id"), !representation(binding),
                evaluated(proof, false), recursive, CoreEntries.binding(binding),
                CoreApplicationCertificates.binding(binding), reusableCode ?
                    recursive || representation(binding) || proof.getKind() == CoreKind.UNKNOWN
                        ? FrameSlotKind.Object : FrameLayout.carrierKind(proof) : FrameSlotKind.Illegal).slot;
        }
        Expr[] rhs = new Expr[group.size()];
        for (int i = 0; i < rhs.length; i++) {
            Map<String, Object> binding = group.get(i);
            rhs[i] = withSource(sources.binding(binding, lowering().source), () -> {
                List<Object> body = (List<Object>) binding.get("expr");
                boolean lifted = representation(binding);
                if (recursive && !lifted) throw new UnsupportedCore("Recursive unlifted binding unsupported");
                Expr node = recursive && lifted && !Arrays.asList("lam", "lit", "con", "void").contains(body.get(0))
                    ? delay(body, local, String.valueOf(binding.get("name")))
                    : argument(body, recursive ? local : scope, lifted, String.valueOf(binding.get("name")),
                        CoreRepresentations.binder(binding).isTypedTransport(), lifted);
                return node.proven(node.getRepresentation().refine(evaluated(CoreRepresentations.binder(binding), false)));
            });
        }
        boolean[] unlifted = new boolean[group.size()];
        for (int i = 0; i < group.size(); i++) {
            local.publish((String) group.get(i).get("id"), rhs[i].getRepresentation());
            unlifted[i] = !representation(group.get(i));
        }
        return new Let(slots, rhs, unlifted, compile((List<Object>) expr.get(3), local, tail), recursive, typedSlots);
    }

    private Expr compileCase(List<Object> expr, Scope scope, boolean tail) {
        var vectorRead = CoreVectorMemory.readCase(expr, constructors);
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
            evaluated(CoreRepresentations.caseBinder(expr), false)), true);
        if (binderProof.isTypedTransport() && ((List<?>) expr.get(3)).isEmpty())
            return compileTupleCase(expr, scrutinee, binderProof, local, tail);
        if (binderProof.isSum()) return compileSumCase(expr, scrutinee, binderProof, local, tail);
        if (binderProof.isTuple()) return compileTupleCase(expr, scrutinee, binderProof, local, tail);
        if (binderProof.isVector()) return compileVectorCase(expr, scrutinee, binderProof, local, tail);
        int binder = local.bind((String) expr.get(2), !binderProof.getPresent() || binderProof.isLong(), binderProof,
            false, null, null, FrameLayout.carrierKind(binderProof)).slot;
        List<List<Object>> rawAlternatives = (List<List<Object>>) expr.get(3);
        boolean substantialSiblings = substantialSiblings(rawAlternatives);
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
            if (layout != null && layout.getLogicalArity() != ids.size()) throw new RuntimeFault("Constructor field/binder mismatch");
            var metadata = CoreRepresentations.alternativeBinders(alt);
            boolean[] strict = kind.equals("data") ? strictConstructorFields((String) alt.get(1), ids.size()) : null;
            int[][] vectorFields = new int[layout != null ? layout.getArity() : ids.size()][];
            List<Integer> slots = new ArrayList<>();
            for (int index = 0; index < ids.size(); index++) {
                String id = ids.get(index);
                Map<String, Object> meta = at(metadata, index);
                CoreRepresentation raw = meta != null ? CoreRepresentations.binder(meta) : CoreRepresentation.UNKNOWN;
                int physical = layout != null ? layout.fieldOffset(index) : index;
                CoreRepresentation aggregate = layout != null ? layout.logicalProof(index) : null;
                if (aggregate != null && !aggregate.isAggregate()) aggregate = null;
                CoreRepresentation vector = aggregate == null && layout != null ? layout.vectorProof(physical) : null;
                if (aggregate != null) {
                    if (!raw.getPresent() || meta == null || !Boolean.FALSE.equals(meta.get("lifted")))
                        throw new RuntimeFault("Aggregate constructor binder requires an unlifted shape");
                    TupleShape.requireCompatible(aggregate, raw, true);
                    CoreRepresentation proof = aggregate.refine(raw);
                    List<CoreRepresentation> leaves = ArgumentLayout.leaves(proof);
                    int[] lanes = new int[layout.logicalWidth(index)];
                    for (int i = 0; i < lanes.length; i++) lanes[i] = child.layout.bind(id + " constructor aggregate " + i,
                        FrameLayout.carrierKind(leaves.get(i)));
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
                        int[] lanes = {child.layout.bind(id + " constructor vector", FrameSlotKind.Object)};
                        vectorFields[physical] = lanes;
                        slots.add(child.bindTuple(id, proof, lanes).slot);
                    } else slots.add(child.bind(id, layout != null && layout.isLong(physical), proof,
                        false, null, null, FrameLayout.carrierKind(proof)).slot);
                }
            }
            int tag = switch (kind) {
                case "default" -> DEFAULT_ALTERNATIVE;
                case "data" -> DATA_ALTERNATIVE;
                case "lit" -> LITERAL_ALTERNATIVE;
                default -> throw new RuntimeFault("Invalid Core alternative kind " + kind);
            };
            Expr body = caseArm((List<Object>) alt.get(3), child, tail, alternatives.length, substantialSiblings);
            alternatives[a] = reusableCode && layout != null ?
                new Alternative(tag, layout.reusableStorage(), ints(slots), body, vectorFields, false,
                    scope.programSlot, required(constructorIndices, layout.getId())) :
                new Alternative(tag, value, ints(slots), body, vectorFields, !reusableCode && alternatives.length > 1);
            int[] dead = CoreFreeVariables.unusedConstructorReferences(alt, layout);
            for (int i = 0; i < dead.length; i++) dead[i] = slots.get(dead[i]);
            child.layout.clearInitially(dead);
            alternatives[a].discardUnusedFields(dead);
            results.add(body.getRepresentation()); kinds.add(tag);
            allLong &= tag != LITERAL_ALTERNATIVE || value instanceof Long;
            anyAggregate |= body.getRepresentation().isAggregate();
            anyScalar |= !body.getRepresentation().isAggregate();
        }
        CoreRepresentation declared = CoreRepresentations.caseResult(expr);
        if (!declared.isAggregate() && anyAggregate && anyScalar) throw new RuntimeFault("Missing exact aggregate case result proof");
        CoreRepresentations.validateDeclaredCaseResult(declared, results);
        CoreRepresentations.validateAggregateCaseResult(declared, results);
        CoreRepresentations.validateFloatingCaseResult(declared, results);
        Case selection = switch (CaseCategories.caseCategory(binderProof, kinds, allLong)) {
            case DATA -> new DataCase(scrutinee, binder, alternatives, codeMetrics(), binderProof, delimited);
            case LONG -> new LongCase(scrutinee, binder, alternatives, codeMetrics(), binderProof, delimited);
            case DEFAULT_ONLY -> new DefaultCase(scrutinee, binder, alternatives, codeMetrics(), binderProof, delimited);
            case GENERIC -> new Case(scrutinee, binder, alternatives, codeMetrics(), null, delimited);
        };
        selection.prepareLiteralIndex(binderProof);
        for (List<Object> alt : rawAlternatives)
            if (coreFreeVariables((List<Object>) alt.get(3)).contains(expr.get(2))) return selection;
        scope.layout.clearInitially(binder);
        return selection.discardUnusedBinder();
    }

    private Expr compileConstructor(List<Object> expr, Scope scope) {
        String id = (String) expr.get(1);
        int arity = ((Number) expr.get(2)).intValue();
        Map<String, Object> info = constructors.get(id);
        CoreRepresentation proof = CoreRepresentations.expression(expr);
        if (info != null && "unboxed-tuple".equals(info.get("kind")) && arity == 0 && proof.isTuple()) {
            if (proof.getComponents() == null || !proof.getComponents().isEmpty()) throw new RuntimeFault("Empty tuple constructor has nonempty logical components");
            return new TupleConstruct(new TupleShape(proof, (thc.Language) language), new Expr[0]);
        }
        if (arity == 0) return construct(id, new Expr[0], scope.layout, scope.programSlot);
        DataLayout constructor = dataLayout(id);
        Object fieldData = required(constructors, id).get("fieldTypes");
        List<?> fieldTypes = fieldData instanceof List<?> found ? found : null;
        List<?> lifted = (List<?>) required(info, "fieldLifted");
        List<Map<String,Object>> parameters = new ArrayList<>();
        List<List<Object>> arguments = new ArrayList<>();
        for (int i = 0; i < arity; i++) {
            String name = "<constructor field " + i + ">";
            Map<String,Object> parameter = new LinkedHashMap<>();
            parameter.put("id", name); parameter.put("name", name); parameter.put("lifted", lifted.get(i));
            if (fieldTypes != null) parameter.put("rep", fieldTypes.get(i));
            parameters.add(parameter);
            arguments.add(List.of("var", name));
        }
        // Constructor functions are ordinary functions: typed packets, PAP
        // prefixes and reusable program captures all use the same lowering.
        List<Object> body = Arrays.asList("app", expr, arguments, lifted, false, true);
        CoreRepresentation result = new CoreRepresentation(CoreKind.DATA, true, false, null, null, null, null, null, null);
        FunctionSpec function = function("constructor " + id, parameters, body, scope, result,
            strictConstructorFields(id, arity), FunctionRootRole.FUNCTION, false);
        return new MakeClosure(function.target, arity, function.captureLayout, function.captures, scope.programSlot);
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
        Map<String, Object> metadata = CoreRepresentations.metadata(expr);
        return metadata == null ? null : metadata.get("rep");
    }
    private List<CoreRepresentation> argumentProofs(List<List<Object>> args) {
        List<CoreRepresentation> result = new ArrayList<>(args.size());
        for (List<Object> arg : args) result.add(CoreRepresentations.expression(arg));
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
        for (int i = 0; i < result.length; i++) result[i] = argument(args.get(i), scope, CoreRepresentations.argumentMayBeLazy(flags.get(i), args.get(i)));
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
        CoreRepresentation tupleProof = CoreRepresentations.expression(expr);
        boolean primitive = "prim".equals(fn.get(0));
        var tupleOperation = primitive ? TupleArithmeticOp.named((String) fn.get(1)) : null;
        var floatDecode = primitive ? FloatDecodeOp.named((String) fn.get(1)) : null;
        var metadata = CoreRepresentations.metadata(expr);
        boolean defined = "var".equals(fn.get(0)) &&
            (scope.locals.containsKey(fn.get(1)) || scope.joins.containsKey(fn.get(1)) ||
                (demand != null && metadata != null && metadata.containsKey("foreignCall") ?
                    demand.isDefined((String) fn.get(1)) : globals.containsKey(fn.get(1))));
        var cpuAffinity = CoreCpuAffinity.validate(expr, defined || scope.joins.containsKey(at(fn, 1)));
        var runtimeService = CoreRuntimeServices.validate(expr, defined || scope.joins.containsKey(at(fn, 1)));
        var override = CoreForeignOverride.select(metadata);
        var stdioCandidate = override == CoreForeignOverride.STDIO ? CoreOriginalStdio.validate(metadata, argumentMetadata(args), flags, metadataRepresentation(expr)) : null;
        var nativeOpening = CoreOriginalStdio.windowsOpening(stdioCandidate, metadata, argumentMetadata(args), flags, metadataRepresentation(expr), packageScalarLinks);
        var packageScalar = nativeOpening != null ? nativeOpening : override == null && cpuAffinity == null && runtimeService == null ?
            CorePackageScalarForeign.validate(metadata, argumentMetadata(args), flags, metadataRepresentation(expr), packageScalarLinks) : null;
        var stackClone = override == CoreForeignOverride.STACK && CoreStackForeign.validate(metadata, argumentMetadata(args), flags);
        var stackInfo = override == CoreForeignOverride.STACK_INFO ? CoreStackInfoForeign.validate(metadata, argumentMetadata(args), flags, metadataRepresentation(expr)) : null;
        var originalStdio = nativeOpening == null ? stdioCandidate : null;
        var originalProcess = override == CoreForeignOverride.PROCESS ? CoreProcessForeign.validate(metadata, argumentMetadata(args), flags, metadataRepresentation(expr)) : null;
        var stableFree = override == CoreForeignOverride.STABLE_FREE && CoreStablePointers.validate(metadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var sharedCAF = override == CoreForeignOverride.SHARED_CAF ? CoreSharedCAFStores.validate(metadata, argumentMetadata(args), flags, metadataRepresentation(expr)) : null;
        var shutdown = override == CoreForeignOverride.SHUTDOWN ? CoreRtsShutdown.validate(metadata, argumentMetadata(args), flags, metadataRepresentation(expr)) : null;
        var mainThreadForeign = override == CoreForeignOverride.MAIN_THREAD && CoreMainThreadForeign.validate(metadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var boundThreadForeign = override == CoreForeignOverride.BOUND_THREAD && CoreBoundThreadForeign.validate(metadata, argumentMetadata(args), flags, metadataRepresentation(expr), false);
        var gcForeign = override == CoreForeignOverride.GC ? CoreGcForeign.validate(metadata, argumentMetadata(args), flags, metadataRepresentation(expr)) : null;
        var rtsEventForeign = override == CoreForeignOverride.RTS_EVENT ? CoreRtsEventForeign.validate(metadata, argumentMetadata(args), flags, metadataRepresentation(expr)) : null;
        var windowsIo = override == CoreForeignOverride.WINDOWS_IO ? CoreWindowsIoForeign.validate(metadata, argumentMetadata(args), flags, metadataRepresentation(expr)) : null;
        var allocationCounterForeign = override == CoreForeignOverride.ALLOCATION_COUNTER && CoreBoundThreadForeign.validate(metadata, argumentMetadata(args), flags, metadataRepresentation(expr), true);
        var stringRts = override == CoreForeignOverride.STRING_RTS ? CoreStringRtsForeign.validate(metadata, argumentMetadata(args), flags, metadataRepresentation(expr)) : null;
        var environment = override == CoreForeignOverride.ENVIRONMENT ? CoreEnvironmentForeign.validate(metadata, argumentMetadata(args), flags, metadataRepresentation(expr)) : null;
        var threadIdForeign = override == CoreForeignOverride.THREAD_ID ? CoreThreadIdForeign.validate(metadata, argumentMetadata(args), flags, metadataRepresentation(expr)) : null;
        var rtsDiagnostic = override == CoreForeignOverride.RTS_DIAGNOSTIC ? CoreRtsDiagnosticForeign.validate(metadata, argumentMetadata(args), flags, metadataRepresentation(expr)) : null;
        if (threadIdForeign != null || mainThreadForeign || rtsDiagnostic == RtsDiagnosticOp.STACK)
            CoreBoxedForeignDeclarations.requireCall(boxedForeignDeclarations, metadata);
        var rtsArguments = override == CoreForeignOverride.RTS_ARGUMENTS ? CoreRtsArgumentsForeign.validate(metadata, argumentMetadata(args), flags, metadataRepresentation(expr)) : null;
        var managedFile = override == CoreForeignOverride.MANAGED_FILE ? CoreManagedFiles.validate(metadata, argumentMetadata(args), flags, metadataRepresentation(expr)) : null;

        var javascript = packageScalar == null && !stackClone && stackInfo == null && originalStdio == null && managedFile == null
            ? CoreJavaScript.validate(expr, defined) : null;
        var processSignal = override == CoreForeignOverride.SIGNAL ? CoreSignalForeign.validate(metadata, argumentMetadata(args), flags, metadataRepresentation(expr)) : null;
        var nativeAllocation = override == CoreForeignOverride.ALLOCATION ? CoreNativeAllocationForeign.validate(metadata, argumentMetadata(args), flags, metadataRepresentation(expr)) : null;
        var memmove = override == CoreForeignOverride.MEMMOVE && CoreMemoryCopyForeign.MEMMOVE.validate(metadata, argumentMetadata(args), flags, metadataRepresentation(expr));
        var memcpy = override == CoreForeignOverride.MEMCPY && CoreMemoryCopyForeign.MEMCPY.validate(metadata, argumentMetadata(args), flags, metadataRepresentation(expr));

        var stringOp = TruffleStringOp.validate(expr, defined);
        var vectorApi = stringOp == null ? VectorApiOp.validate(expr, defined) : null;
        if (stringOp != null || vectorApi != null) {
            if (vectorApi != null && vectorApi.javaArray() && foreignExceptionBridge == null)
                throw fault("Java vector array access requires a linked genuine THC.Exception runtime bundle");
            Expr[] lowered = compileOperands(args, scope);
            return (stringOp != null ? new TruffleStringExpression(stringOp, lowered, reusableCode ? scope.programSlot : -1) :
                new VectorApiExpression(vectorApi, lowered, reusableCode ? scope.programSlot : -1)).proven(evaluated(tupleProof, true));
        }
        PolyglotOp polyglot;
        try {
            polyglot = override == null && cpuAffinity == null && runtimeService == null &&
                packageScalar == null && javascript == null ? CorePolyglot.validate(expr, defined) : null;
        } catch (UnsupportedCore unavailable) {
            // Only the final unknown-symbol fallback is deferred. Known ABI validation above stays eager.
            deferredUnsupported.add(unavailable.getMessage());
            return new UnsupportedForeignCall(unavailable.getMessage(), codeMetrics());
        }
        if ((packageScalar != null && packageScalar.executesForeign() || javascript != null || polyglot != null || runtimeService == RuntimeServiceCall.EXCEPTION_TEXT) &&
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
                case EXCEPTION_TEXT -> new ExceptionTextExpression(operands[0], operands[1], operands[2], operands[3], reusableCode ? scope.programSlot : -1);
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
            CoreOriginalStdio.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                if (originalStdio.getUnixNative() || originalStdio.getProcessIdentity() || originalStdio.getEventDescriptor() || originalStdio.getWaitStatus() || originalStdio.getPathRemoval() || originalStdio.getFlagConstant() || originalStdio.getFcntl() || originalStdio.getReadiness() || originalStdio.getSeekConstant() || originalStdio.getStat() || originalStdio.getTermios() || originalStdio.getSavedTermios() || originalStdio.getReadImage() || originalStdio.getPathStat() || originalStdio.getPathMode() || originalStdio.getPathLink() || originalStdio.getCurrentDirectory() || originalStdio.getDirectoryStream() || originalStdio.getOpening() || originalStdio.getIconv() || originalStdio.getDuplication() || originalStdio.getLocking() || originalStdio == OriginalStdioOp.SET_ERRNO || originalStdio == OriginalStdioOp.ACCESS || originalStdio == OriginalStdioOp.UNLINKAT || originalStdio == OriginalStdioOp.FSTATAT || originalStdio == OriginalStdioOp.TCSETATTR)
                    CoreOriginalStdio.validateScalarOperand(originalStdio, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new OriginalStdioExpression(originalStdio, operands, tupleProof);
        }
        if (packageScalar != null) {
            CoreCapiForeign.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CorePackageScalarForeign.validateOperand(packageScalar, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            if (reusableCode) {
                int index;
                synchronized (packageCalls) { index = packageCalls.size(); packageCalls.add(packageScalar); }
                return new PackageScalarExpression(packageScalar, operands, tupleProof, scope.programSlot, index);
            }
            return new PackageScalarExpression(packageScalar, operands, tupleProof);
        }
        if (stableFree) {
            CoreStablePointers.validateHead(fn, defined);
            return new FreeStablePointer(compile(args.get(0), scope, false), compile(args.get(1), scope, false)).proven(evaluated(tupleProof, true));
        }
        if (sharedCAF != null) {
            CoreSharedCAFStores.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreSharedCAFStores.validateOperand(i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
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
        if (threadIdForeign != null) {
            CoreThreadIdForeign.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreThreadIdForeign.validateOperand(threadIdForeign, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new ThreadIdForeignExpression(threadIdForeign, operands, tupleProof);
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
        if (windowsIo != null) {
            CoreWindowsIoForeign.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreWindowsIoForeign.validateOperand(windowsIo, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new WindowsIoExpression(windowsIo, operands, tupleProof);
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
        if (stringRts != null) {
            CoreStringRtsForeign.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreStringRtsForeign.validateOperand(stringRts, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
            }
            return new StringRtsExpression(stringRts, operands, tupleProof, stringRts.constantValue(stackTargetLayout));
        }
        if (shutdown != null) {
            CoreRtsShutdown.validateHead(fn, defined);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreRtsShutdown.validateOperand(shutdown, i, operands[i].getRepresentation(), bindingProof(args.get(i), scope));
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
            boolean byteArrays = CoreMemoryCopyForeign.MEMCPY.byteArrays(metadata);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) {
                operands[i] = compile(args.get(i), scope, false);
                CoreMemoryCopyForeign.MEMCPY.validateOperand(i, operands[i].getRepresentation(), bindingProof(args.get(i), scope), byteArrays);
            }
            return new MemcpyExpression(operands, tupleProof, byteArrays);
        }
        if (javascript != null) return new JavaScriptExpression(javascript, argumentOperands(args, scope, false), reusableCode ? scope.programSlot : -1).proven(evaluated(tupleProof, true));
        if (polyglot != null) {
            // Every lifted operand in this ABI is an opaque Value consumed by the
            // foreign operation. Demand it at an ordinary resumable guest cut,
            // retaining earlier operands and the not-yet-executed foreign suffix.
            var lowered = argumentOperands(args, scope, false);
            return (polyglot.explicitLibrary() ? new InteropExpression(polyglot, lowered) :
                new PolyglotExpression(polyglot, lowered, reusableCode ? scope.programSlot : -1)).proven(evaluated(tupleProof, true));
        }

        if (primitive && Set.of("newBCO#", "mkApUpd0#").contains(fn.get(1))) {
            String name = (String) fn.get(1);
            GhcBCO.validate(name, argumentProofs(args), flags, tupleProof);
            return new GhcBCOExpression(name, argumentOperands(args, scope, flags), (thc.Language) language, codeMetrics(), tupleProof);
        }
        if (primitive && Set.of("newPromptTag#", "prompt#", "control0#").contains(fn.get(1))) {
            String name = (String) fn.get(1);
            DelimitedControl.validate(name, argumentProofs(args), flags, tupleProof);
            return new DelimitedPrimitive(name, new TupleShape(tupleProof, (thc.Language) language),
                argumentOperands(args, scope, flags), (thc.Language) language, codeMetrics());
        }
        if (primitive && "tagToEnum#".equals(fn.get(1))) {
            if (args.size() != 1) throw new RuntimeFault("tagToEnum#: Exactly one operand required");
            Expr operand = compile(args.get(0), scope, false);
            var ids = CoreEnums.validate(expr, operand.getRepresentation(), constructors);
            if (reusableCode) {
                int[] indices = new int[ids.size()];
                for (int i = 0; i < indices.length; i++) {
                    dataLayout(ids.get(i));
                    indices[i] = required(constructorIndices, ids.get(i));
                }
                return new TagToEnum(scope.programSlot, indices, operand);
            }
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
            if (reusableCode) {
                int[] indices = new int[ids.size()];
                for (int i = 0; i < indices.length; i++) indices[i] = required(constructorIndices, ids.get(i));
                return new DataToTag(scope.programSlot, indices, operand);
            }
            return new DataToTag(new DataTagFamily(layouts), operand);
        }
        if (primitive && CoreVectors.operations.contains(fn.get(1))) {
            String name = (String) fn.get(1);
            List<CoreRepresentation> proofs = new ArrayList<>();
            for (List<Object> arg : args) proofs.add(CoreVectors.argumentProof(arg));
            CoreVectors.validate(name, proofs, tupleProof);
            CoreVectors.validateFlags(flags);
            Expr[] operands = compileOperands(args, scope);
            int[] shuffle = name.startsWith("shuffle") ? CoreVectors.shuffleIndices(args.get(2), tupleProof.getVector().getLanes()) : null;
            if (GeneratedVectors.operations.contains(name))
                return GeneratedVectors.expression(name, operands, shuffle, count -> vectorSlots(scope, count, "<vector lane ", operands[0].getRepresentation()));
            if (name.equals("packInt64X2#")) return new VectorPack(operands[0], vectorSlots(scope, 2, "<vector lane ", operands[0].getRepresentation()));
            if (name.equals("unpackInt64X2#")) return new VectorUnpack(operands[0]);
            if (name.equals("packInt32X4#")) return new Vector32Pack(operands[0], vectorSlots(scope, 4, "<vector lane ", operands[0].getRepresentation()));
            if (name.equals("unpackInt32X4#")) return new Vector32Unpack(operands[0]);
            if (name.equals("packDoubleX2#")) return new VectorDoublePack(operands[0], vectorSlots(scope, 2, "<double vector lane ", operands[0].getRepresentation()));
            if (name.equals("unpackDoubleX2#")) return new VectorDoubleUnpack(operands[0]);
            if (CoreVectors.operationsDouble.contains(name)) return new VectorDoubleOperation(name, operands);
            if (name.equals("packFloatX4#")) return new VectorFloatPack(operands[0], vectorSlots(scope, 4, "<float vector lane ", operands[0].getRepresentation()));
            if (name.equals("unpackFloatX4#")) return new VectorFloatUnpack(operands[0]);
            if (CoreVectors.operationsFloat.contains(name)) return new VectorFloatOperation(name, operands);
            if (CoreVectors.fusedFloat8.contains(name)) return new VectorFloat8Fused(name, operands);
            if (CoreVectors.fusedFloat16.contains(name)) return new VectorFloat16Fused(name, operands);
            if (CoreVectors.fusedDouble4.contains(name)) return new VectorDouble4Fused(name, operands);
            if (CoreVectors.fusedDouble8.contains(name)) return new VectorDouble8Fused(name, operands);
            if (CoreVectors.operations32.contains(name)) return new Vector32Operation(name, operands);
            if (name.equals("packInt16X8#")) return new Vector16Pack(operands[0], vectorSlots(scope, 8, "<int16 vector lane ", operands[0].getRepresentation()));
            if (name.equals("unpackInt16X8#")) return new Vector16Unpack(operands[0]);
            if (CoreVectors.operations16.contains(name)) return new Vector16Operation(name, operands);
            if (name.equals("packInt8X16#")) return new Vector8Pack(operands[0], vectorSlots(scope, 16, "<int8 vector lane ", operands[0].getRepresentation()));
            if (name.equals("unpackInt8X16#")) return new Vector8Unpack(operands[0]);
            if (CoreVectors.operations8.contains(name)) return new Vector8Operation(name, operands);
            if (name.equals("packWord8X16#")) return new VectorWord8Pack(operands[0], vectorSlots(scope, 16, "<word8 vector lane ", operands[0].getRepresentation()));
            if (name.equals("unpackWord8X16#")) return new VectorWord8Unpack(operands[0]);
            if (CoreVectors.operationsWord8.contains(name)) return new VectorWord8Operation(name, operands);
            if (name.equals("packWord16X8#")) return new VectorWord16Pack(operands[0], vectorSlots(scope, 8, "<word16 vector lane ", operands[0].getRepresentation()));
            if (name.equals("unpackWord16X8#")) return new VectorWord16Unpack(operands[0]);
            if (CoreVectors.operationsWord16.contains(name)) return new VectorWord16Operation(name, operands);
            if (name.equals("packWord32X4#")) return new VectorWord32Pack(operands[0], vectorSlots(scope, 4, "<word32 vector lane ", operands[0].getRepresentation()));
            if (name.equals("unpackWord32X4#")) return new VectorWord32Unpack(operands[0]);
            if (CoreVectors.operationsWord32.contains(name)) return new VectorWord32Operation(name, operands);
            return new VectorOperation(name, operands);
        }
        if (primitive && CoreArithmeticExceptions.payload((String) fn.get(1)) != null) {
            String name = (String) fn.get(1);
            CoreArithmeticExceptions.validateArguments(name, argumentProofs(args), flags);
            Expr operand = argument(single(args), scope, false, "argument thunk", true, false);
            CoreArithmeticExceptions.validateArguments(name, List.of(operand.getRepresentation()), flags);
            String id = Objects.requireNonNull(CoreArithmeticExceptions.payload(name));
            GlobalBinding payload = globals.get(id);
            if (payload == null) throw new UnsupportedCore("Unresolved implicit exception binding " + id);
            Expr value = globalRead(id, scope);
            return new RaiseArithmeticException(operand, new RaiseException(value, true));
        }
        if (primitive && Set.of("raiseIO#", "catch#", "getMaskingState#", "unmaskAsyncExceptions#", "maskAsyncExceptions#", "maskUninterruptible#").contains(fn.get(1))) {
            String name = (String) fn.get(1);
            CoreSynchronousExceptions.validate(name, argumentProofs(args), flags, tupleProof);
            Expr[] operands = argumentOperands(args, scope, flags);
            if (name.equals("raiseIO#")) return new RaiseIOException(operands[0], operands[1], tupleProof, CoreExceptionPayload.validate(expr));
            if (delimited && !name.equals("getMaskingState#")) return new DelimitedIOBoundary(name, new TupleShape(tupleProof, (thc.Language) language),
                operands, (thc.Language) language, codeMetrics());
            if (name.equals("catch#")) return new CatchException(new TupleShape(tupleProof, (thc.Language) language), operands[0], operands[1], operands[2], codeMetrics());
            if (name.equals("getMaskingState#")) return new GetMaskingState(operands[0], tupleProof);
            MaskingState state = switch (name) {
                case "maskAsyncExceptions#" -> MaskingState.MASKED_INTERRUPTIBLE;
                case "maskUninterruptible#" -> MaskingState.MASKED_UNINTERRUPTIBLE;
                default -> MaskingState.UNMASKED;
            };
            return new MaskAction(new TupleShape(tupleProof, (thc.Language) language), state, operands[0], operands[1], codeMetrics());
        }
        if (primitive && "noDuplicate#".equals(fn.get(1))) {
            CoreNoDuplicate.validate(argumentProofs(args), flags, tupleProof);
            return new NoDuplicate(argument(args.get(0), scope, false), tupleProof);
        }
        if (primitive && CoreThreadScheduling.named((String) fn.get(1))) {
            String name = (String) fn.get(1);
            CoreThreadScheduling.validate(name, argumentProofs(args), flags, tupleProof);
            Expr[] operands = argumentOperands(args, scope, flags);
            CoreThreadScheduling.validate(name, loweredProofs(operands), flags, tupleProof);
            if (name.equals("par#")) return new SparkResult(name, null, operands[0], null, tupleProof);
            if (name.equals("delay#")) return new DelayThread(operands[0], operands[1], enableAsync, tupleProof);
            if (name.equals("setThreadAllocationCounter#")) return new SetThreadAllocationCounter(operands[0], null, operands[1], tupleProof);
            if (name.equals("setOtherThreadAllocationCounter#")) return new SetThreadAllocationCounter(operands[0], operands[1], operands[2], tupleProof);
            DataValue falseValue = null;
            if (name.equals("getSpark#")) {
                DataLayout falseLayout = constructors.containsKey(CoreThreadScheduling.FALSE)
                    ? dataLayout(CoreThreadScheduling.FALSE) : constructorStorage(CoreThreadScheduling.FALSE, "False", CoreFields.EMPTY);
                if (reusableCode) return new SparkResult(name, operands[operands.length - 1], null, tupleProof,
                    scope.programSlot, required(constructorIndices, CoreThreadScheduling.FALSE));
                falseValue = falseLayout.allocate();
            }
            return new SparkResult(name, operands[operands.length - 1], name.equals("spark#") ? operands[0] : null, falseValue, tupleProof);
        }
        if (primitive && CoreThreadObservation.named((String) fn.get(1))) {
            String name = (String) fn.get(1);
            CoreThreadObservation.validate(name, argumentProofs(args), flags, tupleProof);
            Expr state = argument(args.get(0), scope, false);
            CoreThreadObservation.validate(name, List.of(state.getRepresentation()), flags, tupleProof);
            return new ThreadObservation(name.equals("listThreads#"), state, tupleProof);
        }
        if (primitive && "yield#".equals(fn.get(1))) {
            CoreYield.validate(argumentProofs(args), flags, tupleProof);
            return new YieldThread(argument(args.get(0), scope, false), enableAsync, tupleProof);
        }
        if (primitive && CoreFileWait.named((String) fn.get(1))) {
            String name = (String) fn.get(1);
            CoreFileWait.validate(name, argumentProofs(args), flags, tupleProof);
            Expr[] operands = argumentOperands(args, scope, false);
            CoreFileWait.validate(name, loweredProofs(operands), flags, tupleProof);
            GlobalBinding payload = globals.get(CoreFileWait.badFd);
            if (payload == null) throw new UnsupportedCore(name + " requires original blockedOnBadFD payload");
            return new WaitFileDescriptor(operands[0], operands[1], globalRead(CoreFileWait.badFd, scope), name.equals("waitWrite#"), enableAsync, tupleProof);
        }
        if (primitive && List.of("fork#", "forkOn#", "myThreadId#", "threadStatus#", "killThread#", "labelThread#", "threadLabel#").contains(fn.get(1))) {
            String name = (String) fn.get(1);
            CoreGuestThreads.validate(name, argumentProofs(args), flags, tupleProof);
            Expr[] operands = argumentOperands(args, scope, flags);
            CoreGuestThreads.validate(name, loweredProofs(operands), flags, tupleProof);
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
            StackAnnotations.validate(argumentProofs(args), flags, tupleProof);
            if (capturesContinuations) return new AnnotatedAction(new TupleShape(tupleProof, (thc.Language) language),
                argument(args.get(0), scope, true), argument(args.get(1), scope, true), argument(args.get(2), scope, false), codeMetrics(), enableAsync);
            return new AnnotatedTuple(argument(args.get(0), scope, true), argument(args.get(2), scope, false),
                new TupleApplication((thc.Language) language, new TupleShape(tupleProof, (thc.Language) language),
                    argument(args.get(1), scope, true), new Expr[]{new Literal(thc.runtime.Unit.INSTANCE).proven(CoreRepresentations.expression(args.get(2)))},
                    false, codeMetrics(), null));
        }
        if (primitive && "clearCCS#".equals(fn.get(1))) {
            CoreProfileAction.validate(argumentProofs(args), flags, tupleProof);
            return new TupleApplication((thc.Language) language, new TupleShape(tupleProof, (thc.Language) language),
                argument(args.get(0), scope, true), new Expr[]{new InspectionState(compile(args.get(1), scope, false))},
                tail, codeMetrics(), null).proven(evaluated(tupleProof, true));
        }
        if (primitive && ClosureInspectOp.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(ClosureInspectOp.named((String) fn.get(1)));
            operation.validate(argumentProofs(args), flags, tupleProof);
            Expr[] operands = new Expr[args.size()];
            for (int i = 0; i < operands.length; i++) operands[i] = argument(args.get(i), scope, i == 0);
            return new ClosureInspectExpression(operation, operands, tupleProof);
        }
        if (primitive && "getCurrentCCS#".equals(fn.get(1))) {
            CoreCurrentCCS.validate(argumentProofs(args), flags, tupleProof);
            return new GetCurrentCCS(argument(args.get(0), scope, true), argument(args.get(1), scope, false), tupleProof);
        }

        if (primitive && STMOp.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(STMOp.named((String) fn.get(1)));
            if (containsDelimited && operation != STMOp.NEW && operation != STMOp.READ_IO)
                throw new UnsupportedCore("STM transaction frames do not support explicit delimited capture");
            operation.validate(argumentProofs(args), flags, tupleProof);
            Expr[] operands = argumentOperands(args, scope, flags);
            operation.validate(loweredProofs(operands), flags, tupleProof);
            Expr nested = null;
            if (operation == STMOp.ATOMICALLY) {
                GlobalBinding payload = globals.get(STMOp.NESTED);
                if (payload == null) throw new UnsupportedCore("atomically# requires original nestedAtomically payload");
                nested = globalRead(STMOp.NESTED, scope);
            }
            return new STMExpression(operation, tupleProof, operands,
                operation.getCallback() ? new TupleShape(tupleProof, (thc.Language) language) : null, codeMetrics(), nested, enableAsync, operation == STMOp.ATOMICALLY ? globalRead(CoreBlockedExceptions.STM, scope) : null);
        }
        if (primitive && MVarOp.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(MVarOp.named((String) fn.get(1)));
            operation.validate(argumentProofs(args), flags, tupleProof);
            operation.validateBindings(argumentProofs(args), bindingProofs(args, scope));
            Expr[] operands = argumentOperands(args, scope, flags);
            operation.validate(loweredProofs(operands), flags, tupleProof);
            String blocked = CoreBlockedExceptions.payload(operation.getPrimitive());
            return ManagedMVars.expression(operation, tupleProof, operands, enableAsync, blocked == null ? null : globalRead(blocked, scope));
        }
        if (primitive && CompactImageOp.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(CompactImageOp.named((String) fn.get(1)));
            operation.validate(argumentProofs(args), flags, tupleProof);
            return new CompactImageExpression(operation, argumentOperands(args, scope, flags)).proven(evaluated(tupleProof, true));
        }
        if (primitive && CompactOp.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(CompactOp.named((String) fn.get(1)));
            operation.validate(argumentProofs(args), flags, tupleProof);
            List<Expr> failures = new ArrayList<>();
            if (operation.getAdds()) for (String id : CompactOp.getFailures()) {
                GlobalBinding failure = globals.get(id);
                if (failure == null) throw new UnsupportedCore("Compact addition requires original exception payload: " + id);
                failures.add(globalRead(id, scope));
            }
            return new CompactExpression(operation, argumentOperands(args, scope, flags), codeMetrics(),
                failures.toArray(Expr[]::new)).proven(evaluated(tupleProof, true));
        }
        if (primitive && MutVarOp.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(MutVarOp.named((String) fn.get(1)));
            operation.validate(argumentProofs(args), flags, tupleProof);
            if (reusableCode) {
                MutVarModifySite site = operation == MutVarOp.MODIFY || operation == MutVarOp.MODIFY2
                    ? new MutVarModifySite((thc.Language) language, codeIdentity, codeTargets, operation == MutVarOp.MODIFY2) : null;
                return MutVarOp.expression(operation, tupleProof, argumentOperands(args, scope, flags), site, scope.programSlot);
            }
            return MutVarOp.expression(operation, tupleProof, argumentOperands(args, scope, flags), language, metrics, enableAsync);
        }
        if (primitive && WeakOp.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(WeakOp.named((String) fn.get(1)));
            operation.validate(argumentProofs(args), flags, tupleProof);
            operation.validateBindings(argumentProofs(args), bindingProofs(args, scope));
            if (operation == WeakOp.MAKE) operation.validateAction(CoreRepresentations.knownFunctionSignature(args.get(2), bindings));
            Expr[] operands = argumentOperands(args, scope, flags);
            operation.validate(loweredProofs(operands), flags, tupleProof);
            Expr runner = null;
            if (operation == WeakOp.MAKE) {
                if (weakFinalizer == null || weakFinalizer.isBlank())
                    throw new UnsupportedCore("mkWeak# requires a selected runtime finalizer ABI binding");
                runner = globalRead(weakFinalizer, scope);
            }
            return new WeakExpression(operation, operands, runner).proven(evaluated(tupleProof, true));
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
        if (primitive && VectorMemoryOp.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(VectorMemoryOp.named((String) fn.get(1)));
            operation.validate(argumentProofs(args), flags, tupleProof);
            return operation.isAddress() ? new VectorAddressExpression(operation, compileOperands(args, scope)) :
                new VectorByteArrayExpression(operation, compileOperands(args, scope));
        }
        if (primitive && PrefetchExpression.ARITIES.containsKey(fn.get(1))) {
            if (args.size() != PrefetchExpression.ARITIES.get(fn.get(1))) throw fault("Wrong prefetch arity");
            return new PrefetchExpression(argument(args.get(0), scope, CoreRepresentations.argumentMayBeLazy(flags.get(0), args.get(0))),
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
            Expr kept = argument(args.get(0), scope, CoreRepresentations.argumentMayBeLazy(flags.get(0), args.get(0)));
            Expr state = compile(args.get(1), scope, false);
            CoreTouch.validate(List.of(kept.getRepresentation(), state.getRepresentation()), flags, tupleProof);
            return new TouchExpression(kept, state, tupleProof);
        }
        if (primitive && "keepAlive#".equals(fn.get(1))) {
            var proofs = argumentProofs(args);
            var signature = at(args, 2) != null ? CoreRepresentations.knownFunctionSignature(args.get(2), bindings) : null;
            CoreKeepAlive.validate(proofs, flags, tupleProof,
                signature == null ? null : signature.getInputs(), signature == null ? null : signature.getResult());
            Expr kept = argument(args.get(0), scope, CoreRepresentations.argumentMayBeLazy(flags.get(0), args.get(0)));
            Expr state = compile(args.get(1), scope, false);
            Expr function = compile(args.get(2), scope, false);
            Expr[] stateArgument = {new Literal(thc.runtime.Unit.INSTANCE)};
            Expr action;
            if (tupleProof.isTypedTransport()) {
                var shape = new TupleShape(tupleProof, (thc.Language) language);
                int[] slots = tupleProof.isVector() ? vectorSlots(scope, shape.getWidth(), "<keepAlive vector result ") : null;
                action = new TupleApplication((thc.Language) language, shape, function, stateArgument, false, codeMetrics(), slots);
            } else action = new Application(function, stateArgument, false, codeMetrics());
            return new KeepAliveExpression(kept, state, action, tupleProof);
        }
        if (primitive && AtomicAddressOp.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(AtomicAddressOp.named((String) fn.get(1)));
            operation.validate(argumentProofs(args), flags, tupleProof);
            return new AtomicAddressExpression(operation, tupleProof, compileOperands(args, scope));
        }
        if (primitive && FloatingAddressOp.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(FloatingAddressOp.named((String) fn.get(1)));
            String name = (String) fn.get(1);
            boolean byteOffset = name.contains("Word8") && name.contains("As");
            operation.validate(argumentProofs(args), flags, tupleProof);
            return new FloatingAddressExpression(operation, tupleProof, compileOperands(args, scope), byteOffset);
        }
        if (primitive && AddressArrayCopyOp.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(AddressArrayCopyOp.named((String) fn.get(1)));
            operation.validate(argumentProofs(args), flags, tupleProof);
            Expr[] operands = compileOperands(args, scope);
            return operation.getToArray() ? new AddressToByteArrayExpression(tupleProof, operands[0], operands[1], operands[2], operands[3], operands[4]) :
                new ByteArrayToAddressExpression(tupleProof, operands[0], operands[1], operands[2], operands[3], operands[4]);
        }
        if (primitive && PinnedMemoryOp.named((String) fn.get(1)) != null) {
            var operation = Objects.requireNonNull(PinnedMemoryOp.named((String) fn.get(1)));
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
    private int[] vectorSlots(Scope scope, int count, String prefix, CoreRepresentation proof) {
        List<CoreRepresentation> fields = TupleShape.flatten(proof);
        if (fields.size() != count) throw new RuntimeFault("Vector scratch shape mismatch");
        int[] result = new int[count];
        for (int i = 0; i < count; i++) result[i] = scope.layout.bind(prefix + i + ">", FrameLayout.carrierKind(fields.get(i)));
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
            int tag = SumShape.constructor(tupleProof, constructor, fn.get(2));
            if (args.size() != 1) throw new RuntimeFault("Sum constructor must be saturated");
            CoreRepresentation selected = Objects.requireNonNull(tupleProof.getAlternatives()).get(tag - 1);
            boolean lifted = CoreRepresentations.argumentMayBeLazy(single(flags), single(args));
            Expr payload = selected.isTypedTransport() ? compile(single(args), scope, false) : argument(single(args), scope, lifted);
            SumShape.payload(selected, payload.getRepresentation(), (Boolean) single(flags));
            int[] intSlots = new int[0];
            if (selected.isTypedTransport()) {
                var fields = TupleShape.flatten(selected);
                intSlots = new int[fields.size()];
                for (int i = 0; i < intSlots.length; i++) intSlots[i] = fields.get(i).isInt() ? scope.layout.bind("<narrow sum payload " + i + ">", FrameSlotKind.Int) : -1;
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
                TupleShape.requireCompatible(field, CoreRepresentations.expression(args.get(i)), true);
                if (field.isTypedTransport() && !Boolean.FALSE.equals(flags.get(i))) throw new RuntimeFault("Typed tuple field cannot be lifted");
                if (field.isTypedTransport()) operands[i] = compile(args.get(i), scope, false);
                else {
                    boolean lifted = CoreRepresentations.argumentMayBeLazy(flags.get(i), args.get(i));
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
            boolean lifted = CoreRepresentations.argumentMayBeLazy(flags.get(i), args.get(i));
            CoreRepresentation field = layout != null ? layout.logicalProof(i) : null;
            if (field != null && field.isAggregate()) {
                if (lifted) throw new RuntimeFault("Aggregate constructor operand must be unlifted");
                nodes[i] = compile(args.get(i), scope, false);
                TupleShape.requireCompatible(field, nodes[i].getRepresentation(), true);
            } else nodes[i] = argument(args.get(i), scope, lifted && !callStrict[i] &&
                !(constructorStrict != null && constructorStrict[i]) && !(entryStrict != null && i < entryStrict.length && entryStrict[i]),
                "argument thunk", !"prim".equals(fn.get(0)), lifted);
        }
        if ("prim".equals(fn.get(0))) {
            for (Expr node : nodes) CoreRepresentations.requireScalar(node.getRepresentation(), "argument");
            return primitive((String) fn.get(1), nodes, CoreExceptionPayload.validate(expr));
        }
        if (constructorStrict != null) {
            DataLayout target = dataLayout((String) fn.get(1));
            return reusableCode ? new Construct(target.reusableStorage(), scope.programSlot, required(constructorIndices, target.getId()), nodes, constructorVectorSlots(target, scope.layout)) :
                new Construct(target, nodes, constructorVectorSlots(target, scope.layout));
        }
        Expr function = compile(fn, scope, false);
        ArgumentLayout input = ArgumentLayout.fromProofs(loweredProofs(nodes));
        if (input != null && input.getRequiresTyped()) return new AstTypedApplication(function, nodes, scope.layout, tail, codeMetrics(),
            tupleProof.isTypedTransport() ? new TupleShape(tupleProof, (thc.Language) language) : null,
            !reusableCode && tail && scope.self != null &&
                TypedInputs.supportsTypedSelf(scope.self.getInputLayout(), scope.self.getEntryStrict(), input));
        if (tupleProof.isTypedTransport()) {
            TupleShape shape = new TupleShape(tupleProof, (thc.Language) language);
            int[] vectorSlots = tupleProof.isVector() ? vectorSlots(scope, shape.getWidth(), "<vector call result ", tupleProof) : null;
            return new TupleApplication((thc.Language) language, shape, function, nodes, tail, codeMetrics(), vectorSlots);
        }
        AstSelfLayout self = scope.self;
        boolean emptyTuple = false;
        for (Expr node : nodes) if (node.getRepresentation().isEmptyTuple()) { emptyTuple = true; break; }
        if (!reusableCode && tail && self != null && self.getInputLayout() == null && !emptyTuple && self.getArity() > 0 && nodes.length <= self.getArity()) {
            int[] temporaries = vectorSlots(scope, self.getArity(), "<self argument ");
            scope.layout.clearInitially(temporaries);
            return new AstTailApplication(function, nodes, self, temporaries, metrics);
        }
        return new Application(function, nodes, tail, codeMetrics());
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
