// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.bytecode.BytecodeEncodingException;
import com.oracle.truffle.api.bytecode.BytecodeLabel;
import com.oracle.truffle.api.bytecode.BytecodeLocal;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.source.SourceSection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import thc.Language;

import static thc.runtime.Scalar64Primitives.scalar64PrimitiveOperation;
import static thc.runtime.Scalar64Primitives.word64Literal;
import static thc.runtime.BitPrimitives.scalarBitPrimitiveShift;

/**
 * Constructs and links {@link BytecodeRoot}s from exported GHC Core.
 *
 * This program holder is not an executable node or a guest value. Core lowers to
 * Bytecode DSL control flow and primitive operations. The parser is replayable:
 * targets, layouts and literal constants are prepared once; bytecode locals and
 * labels are created afresh on every replay. Runtime values and application use
 * the same selective captures, lazy update protocol and PAP convention as the AST backend.
 */
@SuppressWarnings("unchecked")
public final class BytecodeProgram implements ExecutableProgram {
    private static final CoreRepresentation UNKNOWN = CoreRepresentation.UNKNOWN;
    private final Language language;
    private final BytecodeCheckpoint checkpoint;
    private final boolean enableAsync;
    private final boolean eagerAsyncPolls;
    private final CoreDemandBindings demand;
    private final ForeignExceptionBridge foreignExceptionBridge;
    private final RubbishLiterals rubbishLiterals;
    private final List<thc.ForeignBitcode> foreignLinks;
    private final List<thc.PackageScalarLink> packageScalarLinks;
    private final List<CoreBoxedForeignDeclarations> boxedForeignDeclarations;
    private final Map<String,thc.ManagedCallbackSignature> nativeCallbacks;
    private final boolean delimited;
    private final boolean containsDelimited;
    private final boolean resumable;
    private final Object stackTargetLayout;
    private final boolean callDemandsEnabled = Boolean.getBoolean("thc.callDemands");
    private final CoreSources sources;
    private final Metrics metrics;
    private final boolean diagnosticUnsupported;
    private final Set<String> deferredUnsupported = new LinkedHashSet<>();
    private final List<Map<String, Object>> bindings;
    private final Map<String, Map<String, Object>> constructors;
    private final Map<String, DataLayout> dataLayouts;
    private final Map<String, GlobalBinding> globals;
    private final String weakFinalizer;
    private final Map<String, Integer> indices = new LinkedHashMap<>();
    private final Map<String, List<Integer>> names = new LinkedHashMap<>();
    private final Map<String, CoreRepresentation> globalProofs = new LinkedHashMap<>();
    private final Map<String, boolean[]> globalEntries = new LinkedHashMap<>();
    private final Map<String, CoreApplicationCertificates.Arity> globalArityCertificates = new LinkedHashMap<>();
    private final Consumer<List<Map<String, Object>>> validateInputs;
    private final Map<Integer, RootCallTarget> hostEntries = new LinkedHashMap<>();
    private final List<BytecodeRoot> roots = new ArrayList<>();
    private int initializedBindingCount;
    private final PreparationLock preparationLock;
    private int nextLocal;
    private int localJoinCount;
    private int nextJoinSource;
    private List<String> pendingInitializers = List.of();

    public static BytecodeProgram forNativeStartup(Language language, Map<String,Object> module, boolean async) {
        return new BytecodeProgram(language, module, null, async,
            module.get("packageScalarLinks") instanceof List<?> links && !links.isEmpty() ||
            module.get("foreignLinks") instanceof List<?> foreign && !foreign.isEmpty());
    }

    public BytecodeProgram(Language language, Map<String, Object> moduleData) {
        this(language, moduleData, null, false);
    }

    public BytecodeProgram(Language language, Map<String, Object> moduleData, boolean enableAsync) {
        this(language, moduleData, null, enableAsync);
    }

    public BytecodeProgram(Language language, Map<String, Object> moduleData, BytecodeCheckpoint checkpoint) {
        this(language, moduleData, checkpoint, false);
    }

    public BytecodeProgram(Language language, Map<String, Object> moduleData,
            BytecodeCheckpoint checkpoint, boolean enableAsync) {
        this(language, moduleData, checkpoint, enableAsync, false);
    }
    private BytecodeProgram(Language language, Map<String, Object> moduleData,
            BytecodeCheckpoint checkpoint, boolean enableAsync, boolean nativeStartup) {
        this.language = language;
        this.checkpoint = checkpoint;
        this.enableAsync = true;
        eagerAsyncPolls = enableAsync;
        thc.CoreForeignArtifacts.INSTANCE.requireExecutableInput(moduleData);
        boxedForeignDeclarations = CoreBoxedForeignDeclarations.admissions(moduleData);
        demand = moduleData.get("demandBindings") instanceof CoreDemandBindings value ? value : null;
        preparationLock = demand == null ? new PreparationLock(thc.Language.currentState().getEnv().getContext()) : demand.getPreparationLock();
        nativeStartup |= demand != null; // Demand preparation publishes cells; execution initializes them.
        foreignExceptionBridge = ForeignExceptionBridge.bind(moduleData, this::entryValue, this::dataLayout);
        weakFinalizer = moduleData.get("selectedWeakFinalizer") instanceof String id ? id : null;
        rubbishLiterals = new RubbishLiterals(language);
        foreignLinks = moduleData.get("foreignLinks") instanceof List<?> value
            ? (List<thc.ForeignBitcode>) value : List.of();
        packageScalarLinks = moduleData.get("packageScalarLinks") instanceof List<?> value
            ? (List<thc.PackageScalarLink>) value : List.of();
        nativeCallbacks = moduleData.get("nativeCallbacks") instanceof Map<?,?> value ? (Map<String,thc.ManagedCallbackSignature>) value : Map.of();
        boolean containsDelimited = false;
        if (moduleData.get("bindings") instanceof List<?> values) {
            for (Object binding : values) {
                Object body = binding instanceof Map<?, ?> map ? map.get("expr") : null;
                if (body instanceof thc.CoreBindingBody lazy ? lazy.getHeader().getContainsDelimitedControl()
                        : DelimitedControl.contains(binding)) {
                    containsDelimited = true;
                    break;
                }
            }
        } else containsDelimited = DelimitedControl.contains(moduleData.get("bindings"));
        this.containsDelimited = containsDelimited;
        delimited = containsDelimited || demand != null && Boolean.TRUE.equals(moduleData.get("captureDelimited"));
        resumable = true;
        stackTargetLayout = moduleData.get("targetLayout");
        sources = new CoreSources(moduleData);
        metrics = demand == null ? new Metrics(!Boolean.FALSE.equals(moduleData.get("instrument"))) : demand.getMetrics();
        diagnosticUnsupported = Boolean.TRUE.equals(moduleData.get("diagnosticUnsupported"));
        if (!(moduleData.get("bindings") instanceof List<?> values)) throw new RuntimeFault("Missing bindings");
        bindings = (List<Map<String, Object>>) (List<?>) values;
        var localConstructors = new LinkedHashMap<String, Map<String, Object>>();
        if (moduleData.get("constructors") instanceof List<?> metadata)
            for (Object raw : metadata) {
                var constructor = (Map<String, Object>) raw;
                localConstructors.put((String) constructor.get("id"), constructor);
            }
        constructors = demand == null ? localConstructors : demand.constructors(localConstructors);
        dataLayouts = demand == null ? new LinkedHashMap<>() : demand.getLayouts();
        var localGlobals = new LinkedHashMap<String, GlobalBinding>();
        for (int i = 0; i < bindings.size(); ++i) {
            var binding = bindings.get(i);
            String id = (String) binding.get("id");
            String name = (String) binding.get("name");
            localGlobals.put(id, new GlobalBinding(name));
            indices.put(id, i);
            names.computeIfAbsent(name, unused -> new ArrayList<>()).add(i);
            var rhs = (List<Object>) binding.get("expr");
            var proof = CoreRepresentations.binder(binding);
            globalProofs.put(id, diagnosticUnsupported ? UNKNOWN : evaluatedProof(proof,
                demand == null && (Boolean.FALSE.equals(binding.get("lifted"))
                    || Set.of("lam", "lit", "con", "void").contains(rhs.getFirst()))));
            globalEntries.put(id, CoreEntries.binding(binding));
            globalArityCertificates.put(id, CoreApplicationCertificates.binding(binding));
        }
        globals = demand == null ? localGlobals : demand.globals(localGlobals);
        if (diagnosticUnsupported) validateInputs = null;
        else validateInputs = CoreInputCalls.validator(bindings, constructors, demand);
        initialize(moduleData, nativeStartup);
    }

    @Override public boolean getAsynchronousExceptions() { return eagerAsyncPolls; }
    @Override public boolean getCapturesContinuations() { return true; }
    @Override public boolean getHasBytecode() { return true; }
    public boolean getEnableAsync() { return enableAsync; }

    private record Local(int id, String name, boolean primitive, CoreRepresentation proof,
            boolean cell, boolean[] entry, CoreApplicationCertificates.Arity arityCertificate) {
        Local(int id, String name, boolean primitive, CoreRepresentation proof) {
            this(id, name, primitive, proof, false, null, null);
        }
        // Raw captures must retain a recursive cell, even when its denoted value is primitive.
        boolean directInt() { return !cell && proof.isInt() && proof.getEvaluated(); }
        boolean directLong() { return !cell && proof.isLong() && proof.getEvaluated(); }
        boolean directFloat() { return !cell && proof.isFloat() && proof.getEvaluated(); }
        boolean directDouble() { return !cell && proof.isDouble() && proof.getEvaluated(); }
    }

    private record VectorCapture(CoreRepresentation proof, List<Local> destinations) {}
    private record TypedArgument(int index, Local local) {}
    private record AggregateLocal(CoreRepresentation proof, List<Local> fields) {}

    // Preparation-only choice. Frozen before the original root is published;
    // source replay must emit the same bytecode, including after a graph retry.
    private static final class CaseRegionEmission {
        boolean inline = true;
    }

    private static final class FunctionContext {
        final int formalArity;
        final boolean[] entryStrict;
        ArgumentLayout inputLayout;
        TypedInputLayout typedInput;
        List<TypedArgument> typedArguments = List.of();
        List<Local> arguments = List.of();
        List<Local> captures = List.of();
        List<VectorCapture> vectorCaptures = List.of();
        CaptureLayout captureLayout;
        boolean mayLoop;
        boolean passThrough;
        boolean entryPoll = true;
        boolean preparingCaseRegion;
        CaseRegionEmission caseEmission;
        final List<BytecodeCaseRegion> caseRegions = new ArrayList<>();
        LeadingCaseReturn leadingCaseReturn;
        TupleShape tuple;

        FunctionContext(int formalArity) { this(formalArity, new boolean[formalArity]); }
        FunctionContext(int formalArity, boolean[] entryStrict) {
            this.formalArity = formalArity;
            this.entryStrict = entryStrict;
        }
    }

    private static final class Scope {
        final FunctionContext function;
        final Map<String, Local> locals;
        final Map<String, JoinTarget> joins;
        final CoreSourceLocation source;
        final Map<String, AggregateLocal> tuples;
        JoinRegion joinRegion;

        Scope(FunctionContext function) { this(function, null); }
        Scope(FunctionContext function, CoreSourceLocation source) {
            this(function, new LinkedHashMap<>(), new LinkedHashMap<>(), source, new LinkedHashMap<>());
        }
        Scope(FunctionContext function, Map<String, Local> locals, Map<String, JoinTarget> joins,
                CoreSourceLocation source, Map<String, AggregateLocal> tuples) {
            this.function = function;
            this.locals = locals;
            this.joins = joins;
            this.source = source;
            this.tuples = tuples;
        }
        Scope child() {
            var child = new Scope(function, new LinkedHashMap<>(locals), new LinkedHashMap<>(joins), source,
                new LinkedHashMap<>(tuples));
            child.joinRegion = joinRegion;
            return child;
        }
        Scope withSource(CoreSourceLocation source) {
            var child = new Scope(function, locals, joins, source, tuples);
            child.joinRegion = joinRegion;
            return child;
        }
        void bindLocal(String name, Local value) { locals.put(name, value); joins.remove(name); tuples.remove(name); }
        void bindVoid(String name, CoreRepresentation proof) {
            bindLocal(name, new Local(-1, name, false, evaluatedProof(proof, true)));
        }
        void bindTuple(String name, CoreRepresentation proof, List<Local> fields) {
            tuples.put(name, new AggregateLocal(proof, fields)); locals.remove(name); joins.remove(name);
        }
        void bindJoin(String name, JoinTarget value) { joins.put(name, value); locals.remove(name); tuples.remove(name); }
    }

    private static final class JoinRegion {
        final boolean recursive;
        final JoinRegion parent;
        final JoinRegion owner;
        final List<JoinTarget> targets = new ArrayList<>();
        final List<Expression> bodies = new ArrayList<>();
        int compilingIndex = -1;
        JoinRegion(boolean recursive, JoinRegion parent, JoinRegion owner) {
            this.recursive = recursive; this.parent = parent; this.owner = owner == null ? this : owner;
        }
    }
    private record JoinTarget(JoinRegion region, int index, List<Map<String, Object>> parameters,
        List<List<Local>> locals, boolean[] entryStrict, CoreRepresentation result,
        List<CapturedLocal> captures, int selectorIndex, JoinSource source) {}
    // Only the free lexical views are retained, not a copy of every enclosing scope.
    private static final class JoinSource {
        final CoreJoinDefinition definition;
        final FunctionContext function;
        final Map<String, Object> bindings;
        final int order;
        JoinSource(CoreJoinDefinition definition, FunctionContext function, Map<String, Object> bindings, int order) {
            this.definition = definition; this.function = function; this.bindings = bindings; this.order = order;
        }
    }
    private static final class JoinEmission {
        final BytecodeLocal selector;
        final BytecodeLabel next;
        final BytecodeLabel dispatch;
        final List<BytecodeLabel> labels;
        int emittedIndex = -1;
        JoinEmission(BytecodeLocal selector, BytecodeLabel next, BytecodeLabel dispatch, List<BytecodeLabel> labels) {
            this.selector = selector; this.next = next; this.dispatch = dispatch; this.labels = labels;
        }
    }
    private static final class Emission {
        final BytecodeRootGen.Builder builder;
        final Map<Integer, BytecodeLocal> locals = new LinkedHashMap<>();
        final Set<Integer> staticLocals = new LinkedHashSet<>();
        final Map<Integer, FrameSlotKind> staticScalars = new HashMap<>();
        final Set<Integer> staticObjectLocals = new LinkedHashSet<>();
        final Map<BytecodeLocal, FrameSlotKind> staticResults = new HashMap<>();
        BytecodeLocal checkpointRootEntry;
        BytecodeLocal annotationRootEntry;
        BytecodeLabel continueLabel;
        BytecodeTypedInputSlots typedInputSlots;
        final Map<JoinRegion, JoinEmission> joins = new LinkedHashMap<>();
        Emission(BytecodeRootGen.Builder builder) { this.builder = builder; }
    }

    @FunctionalInterface
    private interface Expression {
        void emit(Emission emission);
        default void emitTuple(Emission emission, List<BytecodeLocal> destination) {
            throw new RuntimeFault("Tuple expression lacks a destination writer");
        }
        default boolean writesDestination() { return false; }
        default CoreRepresentation proof() { return UNKNOWN; }
        default CoreSourceLocation source() { return null; }
        default boolean loweredCase() { return false; }
    }

    private record LoweredCaseExpression(Expression expression) implements Expression {
        @Override public void emit(Emission emission) { expression.emit(emission); }
        @Override public void emitTuple(Emission emission, List<BytecodeLocal> destination) {
            expression.emitTuple(emission, destination);
        }
        @Override public CoreRepresentation proof() { return expression.proof(); }
        @Override public CoreSourceLocation source() { return expression.source(); }
        @Override public boolean loweredCase() { return true; }
        @Override public boolean writesDestination() { return expression.writesDestination(); }
    }

    private record ProvenExpression(Expression expression, CoreRepresentation proof) implements Expression {
        @Override public void emit(Emission emission) {
            if (!proof.isVector() || !writesDestination()) { expression.emit(emission); return; }
            var b = emission.builder;
            b.beginBlock();
            var lanes = new ArrayList<BytecodeLocal>();
            for (int i = 0; i < TupleShape.flatten(proof).size(); ++i)
                lanes.add(b.createLocal("vector result lane " + i, null));
            expression.emitTuple(emission, lanes);
            b.emitReadVectorSlots(new BytecodeVectorSlots(proof, accessors(lanes)));
            b.endBlock();
        }
        @Override public void emitTuple(Emission emission, List<BytecodeLocal> destination) {
            if (!proof.isVector() || writesDestination()) { expression.emitTuple(emission, destination); return; }
            var slots = new BytecodeVectorSlots(proof, accessors(destination));
            emission.builder.beginWriteVectorSlots(slots);
            expression.emit(emission);
            emission.builder.endWriteVectorSlots();
        }
        @Override public CoreSourceLocation source() { return expression.source(); }
        @Override public boolean loweredCase() { return expression.loweredCase(); }
        @Override public boolean writesDestination() { return expression.writesDestination(); }
    }

    /** Source operations are builder metadata; they emit no guest instruction. */
    private record SourcedExpression(Expression expression, CoreSourceLocation source) implements Expression {
        @Override public CoreRepresentation proof() { return expression.proof(); }
        @Override public boolean loweredCase() { return expression.loweredCase(); }
        @Override public boolean writesDestination() { return expression.writesDestination(); }
        @Override public void emit(Emission emission) { emitSource(emission, () -> expression.emit(emission)); }
        @Override public void emitTuple(Emission emission, List<BytecodeLocal> destination) {
            emitSource(emission, () -> expression.emitTuple(emission, destination));
        }
        private void emitSource(Emission emission, Runnable action) {
            if (!emission.builder.isParsingSources()) {
                emission.builder.beginTag(StandardTags.StatementTag.class);
                action.run();
                emission.builder.endTag(StandardTags.StatementTag.class);
                return;
            }
            var sections = new ArrayList<SourceSection>();
            for (var note : source.getNotes())
                if (!sections.contains(note.getSection())) sections.add(note.getSection());
            var primary = source.getSection();
            if (primary != null && (sections.isEmpty() || !Objects.equals(sections.getLast(), primary)))
                sections.add(primary);
            for (var section : sections) beginSource(emission.builder, section);
            emission.builder.beginTag(StandardTags.StatementTag.class);
            action.run();
            emission.builder.endTag(StandardTags.StatementTag.class);
            for (int i = sections.size() - 1; i >= 0; --i) endSource(emission.builder);
        }
    }

    private record ResultExpression(BiConsumer<Emission, List<BytecodeLocal>> action) implements Expression {
        @Override public boolean writesDestination() { return true; }
        @Override public void emit(Emission emission) { action.accept(emission, null); }
        @Override public void emitTuple(Emission emission, List<BytecodeLocal> destination) {
            action.accept(emission, destination);
        }
    }

    private static void emitResult(Expression value, Emission emission, List<BytecodeLocal> destination) {
        if (destination == null) value.emit(emission); else value.emitTuple(emission, destination);
    }

    /** Honor the destination's existing single-carrier certificate for every tuple writer. */
    private static void storeTupleResult(Emission e, BytecodeLocal slot, Runnable value) {
        var b = e.builder;
        var kind = e.staticResults.get(slot);
        if (kind == null) { b.beginStoreLocal(slot); value.run(); b.endStoreLocal(); return; }
        switch (kind) {
            case Int -> b.beginStaticStoreInt(slot);
            case Long -> b.beginStaticStoreLong(slot);
            case Float -> b.beginStaticStoreFloat(slot);
            case Double -> b.beginStaticStoreDouble(slot);
            case Object -> b.beginStaticStoreObject(slot);
            default -> throw new AssertionError(kind);
        }
        value.run();
        switch (kind) {
            case Int -> b.endStaticStoreInt();
            case Long -> b.endStaticStoreLong();
            case Float -> b.endStaticStoreFloat();
            case Double -> b.endStaticStoreDouble();
            case Object -> b.endStaticStoreObject();
            default -> throw new AssertionError(kind);
        }
    }

    private static Expression tupleExpression(CoreRepresentation proof,
            BiConsumer<Emission, List<BytecodeLocal>> action) {
        return new ProvenExpression(new ResultExpression((emission, destination) -> {
            if (destination == null) throw new RuntimeFault("Tuple result requires a destination");
            action.accept(emission, destination);
        }), evaluatedProof(proof, true));
    }

    private BytecodeTupleSlots tupleSlots(TupleShape shape, List<BytecodeLocal> locals) {
        return tupleSlots(shape, locals, false);
    }

    private BytecodeTupleSlots tupleSlots(TupleShape shape, List<BytecodeLocal> locals, boolean capturesYield) {
        return new BytecodeTupleSlots(shape, accessors(locals), capturesYield, resumable);
    }

    private record LocalExpression(Local local, boolean resolve) implements Expression {
        @Override public CoreRepresentation proof() { return local.proof; }
        @Override public void emit(Emission emission) {
            var b = emission.builder;
            if (emission.staticLocals.contains(local.id)) {
                b.emitStaticLoadLong(Objects.requireNonNull(emission.locals.get(local.id)));
                return;
            }
            var scalar = emission.staticScalars.get(local.id);
            if (scalar != null) {
                var slot = Objects.requireNonNull(emission.locals.get(local.id));
                switch (scalar) {
                    case Int -> b.emitStaticLoadInt(slot);
                    case Float -> b.emitStaticLoadFloat(slot);
                    case Double -> b.emitStaticLoadDouble(slot);
                    default -> throw new AssertionError(scalar);
                }
                return;
            }
            if (emission.staticObjectLocals.contains(local.id)) {
                b.emitStaticLoadObject(Objects.requireNonNull(emission.locals.get(local.id)));
                return;
            }
            boolean narrow = local.directInt() || resolve && local.proof.isInt() && local.proof.getEvaluated();
            boolean integer = local.directLong() || resolve && local.proof.isLong() && local.proof.getEvaluated();
            boolean floating = local.directFloat() || resolve && local.proof.isFloat() && local.proof.getEvaluated();
            boolean wideFloat = local.directDouble() || resolve && local.proof.isDouble() && local.proof.getEvaluated();
            if (narrow) b.beginToInt();
            else if (integer) b.beginToLong();
            else if (floating) b.beginToFloat();
            else if (wideFloat) b.beginToDouble();
            if (resolve && local.cell) b.beginReadCellIfNeeded();
            b.emitLoadLocal(Objects.requireNonNull(emission.locals.get(local.id)));
            if (resolve && local.cell) b.endReadCellIfNeeded();
            if (narrow) b.endToInt();
            else if (integer) b.endToLong();
            else if (floating) b.endToFloat();
            else if (wideFloat) b.endToDouble();
        }
    }

    private record FunctionSpec(RootCallTarget target, CaptureLayout captureLayout, List<Local> captures,
            boolean hasVectorCaptures) {
        FunctionSpec(RootCallTarget target, CaptureLayout captureLayout, List<Local> captures) {
            this(target, captureLayout, captures, false);
        }
    }

    private static CoreRepresentation evaluatedProof(CoreRepresentation proof, boolean evaluated) {
        return new CoreRepresentation(proof.getKind(), evaluated, proof.getPresent(), proof.getPrimReps(),
            proof.getComponents(), proof.getVector(), proof.getAlternatives(), proof.getTagSlot(), proof.getAlternativeSlots());
    }

    private static LocalAccessor[] accessors(List<BytecodeLocal> locals) {
        var result = new LocalAccessor[locals.size()];
        for (int i = 0; i < result.length; ++i) result[i] = LocalAccessor.constantOf(locals.get(i));
        return result;
    }

    /** Immutable source sections can be reused by every replay; builder state cannot. */
    private static void beginSource(BytecodeRootGen.Builder builder, SourceSection section) {
        builder.beginSource(section.getSource());
        if (!section.isAvailable()) builder.beginSourceSectionUnavailable();
        else if (section.hasCharIndex()) builder.beginSourceSection(section.getCharIndex(), section.getCharLength());
        else if (section.hasColumns()) builder.beginSourceSection(section.getStartLine(), section.getStartColumn(),
            section.getEndLine(), section.getEndColumn());
        else builder.beginSourceSection(section.getStartLine());
    }

    private static void endSource(BytecodeRootGen.Builder builder) {
        builder.endSourceSection();
        builder.endSource();
    }

    private void initialize(Map<String, Object> moduleData, boolean nativeStartup) {
        var eager = new ArrayList<Map<String, Object>>();
        for (var binding : bindings) {
            var source = binding.get("expr") instanceof thc.CoreBindingBody body ? body : null;
            // Keep strict and inert-value initialization on the original path.
            // Deferred functions/CAFs are published without executing guest initializers.
            boolean initializeGlobal = demand == null && (source == null || !representation(binding)
                    || Set.of("lit", "con", "void").contains(source.getHeader().getTag()));
            if (initializeGlobal) {
                eager.add(binding);
                if (!nativeStartup) continue;
            }
            Objects.requireNonNull(globals.get((String) binding.get("id"))).defer(preparationLock, () -> {
                try {
                    validateBindings(List.of(binding));
                    Object value = prepareClosedBinding(binding, nativeStartup && initializeGlobal);
                    CoreFunctionIdentity.install(moduleData, binding, value, globalArityCertificates);
                    ++initializedBindingCount;
                    return value;
                } catch (UnsupportedCore failure) {
                    // Keep the original failure/cause/stack and cached identity.
                    failure.addSuppressed(new IllegalStateException("While preparing Core binding " + binding.get("id")));
                    throw failure;
                }
            });
        }
        if (nativeStartup) {
            pendingInitializers = eager.stream().map(binding -> (String) binding.get("id")).toList();
            return;
        }
        validateBindings(eager);
        if (!eager.isEmpty() || bindings.isEmpty()) {
            var scope = new Scope(new FunctionContext(0));
            var initializers = new ArrayList<Expression>();
            for (var binding : eager) {
                CoreRepresentations.requireNoSum(CoreRepresentations.binder(binding), "global binding");
                var expr = (List<Object>) binding.get("expr");
                CoreRepresentations.requireNoSum(CoreRepresentations.expression(expr), "global binding");
                var bindingScope = scope.withSource(sources.binding(binding, null));
                String label = (String) binding.get("name");
                initializers.add(representation(binding) && !Set.of("lam", "lit", "con", "void").contains(expr.getFirst())
                    ? delay(expr, bindingScope, label) : argument(expr, bindingScope, representation(binding), label));
            }
            Expression body = e -> {
                var b = e.builder;
                b.beginBlock();
                for (int i = 0; i < eager.size(); ++i) {
                    b.beginInitializeGlobal(Objects.requireNonNull(globals.get((String) eager.get(i).get("id"))));
                    initializers.get(i).emit(e);
                    b.endInitializeGlobal();
                }
                b.emitLoadConstant(thc.runtime.Unit.INSTANCE);
                b.endBlock();
            };
            // Publication stores lazy values, so the initializer has no WHNF return obligation.
            var initializer = build("Core module initialization", scope.function, body, false);
            Calls.target(initializer, new Object[] {0L});
            for (var binding : eager) {
                CoreFunctionIdentity.install(moduleData, binding,
                    Objects.requireNonNull(globals.get((String) binding.get("id"))).read(), globalArityCertificates);
                ++initializedBindingCount;
            }
        }
    }

    @Override public void initializeGlobals() {
        for (String id : pendingInitializers) Objects.requireNonNull(globals.get(id)).read();
        pendingInitializers = List.of();
    }

    private void validateBindings(List<Map<String, Object>> requested) {
        if (requested.isEmpty()) return;
        ArrayOp.validateApplications(requested);
        CoreForeignOverride.validateHeads(requested);
        if (!diagnosticUnsupported) {
            CoreRepresentations.validateAggregates(requested, constructors);
            Objects.requireNonNull(validateInputs).accept(requested);
        }
    }

    private Object prepareClosedBinding(Map<String, Object> binding, boolean initializeGlobal) {
        CoreRepresentations.requireNoSum(CoreRepresentations.binder(binding), "global binding");
        var expr = (List<Object>) binding.get("expr");
        CoreRepresentations.requireNoSum(CoreRepresentations.expression(expr), "global binding");
        if (demand != null && !representation(binding)) return switch ((String) expr.getFirst()) {
            case "lit" -> "rubbish".equals(expr.get(1)) ? rubbishLiterals.decode(RubbishLiterals.proof(expr)) : literal((String) expr.get(1), expr.get(2), CoreRepresentations.expression(expr));
            case "void" -> thc.runtime.Unit.INSTANCE;
            default -> throw new UnsupportedCore("Demand loading does not yet support effectful strict global initialization");
        };
        var scope = new Scope(new FunctionContext(0), sources.binding(binding, null));
        if (initializeGlobal) {
            String label = (String) binding.get("name");
            var value = representation(binding) && !Set.of("lam", "lit", "con", "void").contains(expr.getFirst())
                ? delay(expr, scope, label) : argument(expr, scope, representation(binding), label);
            return Calls.target(build("Core global initialization", scope.function, value, false), new Object[]{0L});
        }
        if ("lam".equals(expr.getFirst())) {
            CoreRepresentations.requireScalar(CoreRepresentations.expression(expr), "argument");
            var args = (List<Map<String, Object>>) expr.get(1);
            var names = new ArrayList<String>();
            for (var arg : args) names.add(String.valueOf(arg.get("name")));
            var fn = function("lambda " + String.join(", ", names), args, (List<Object>) expr.get(2), scope,
                CoreRepresentations.lambdaResult(expr), CoreEntries.lambda(expr));
            if (fn.captureLayout != null || !fn.captures.isEmpty())
                throw new IllegalStateException("Top-level closure has lexical captures");
            return new Closure(null, args.size(), fn.target);
        }
        var fn = function((String) binding.get("name"), List.of(), expr, scope);
        var tuple = ((GuestRoot) fn.target.getRootNode()).getTupleResult();
        if (tuple != null) CoreRepresentations.requireScalar(tuple.getProof(), "thunk");
        if (fn.captureLayout != null || !fn.captures.isEmpty())
            throw new IllegalStateException("Top-level thunk has lexical captures");
        return new Thunk(fn.target, null);
    }

    private int bindingIndex(String name) {
        Integer direct = indices.get(name);
        if (direct != null) return direct;
        var exact = names.get(name);
        if (exact != null && exact.size() == 1) return exact.getFirst();
        List<Integer> matching = null;
        boolean ambiguous = false;
        for (var item : names.entrySet()) {
            String key = item.getKey();
            if (!key.substring(key.lastIndexOf('.') + 1).equals(name)) continue;
            if (matching != null) { ambiguous = true; break; }
            matching = item.getValue();
        }
        if (!ambiguous && matching != null && matching.size() == 1) return matching.getFirst();
        throw new RuntimeFault("Unknown or ambiguous entry " + name);
    }

    @Override public synchronized RootCallTarget hostEntryTarget(int arity) {
        return hostEntries.computeIfAbsent(arity, count -> new EntryRoot(language, count, metrics).getCallTarget());
    }

    @Override public void initializeNative(thc.Language.State owner) {
        for (var link : foreignLinks) owner.cbits().link(link);
        for (var link : packageScalarLinks) owner.getPackageCbits().link(link);
    }
    @Override public Object entryValue(String name) {
        if (!indices.containsKey(name) && demand != null && demand.contains(name))
            return Objects.requireNonNull(globals.get(name)).read();
        return Objects.requireNonNull(globals.get((String) bindings.get(bindingIndex(name)).get("id"))).read();
    }

    @Override public DataLayout constructorLayout(String id) { return dataLayout(id); }

    @Override public RootCallTarget entryTarget(String name) {
        Object value = entryValue(name);
        while (value instanceof Thunk thunk && thunk.getState() == 2) value = thunk.getValue();
        return switch (value) {
            case Closure closure -> closure.target;
            case Thunk thunk -> thunk.getTarget() != null ? thunk.getTarget() : hostEntryTarget(0);
            case null, default -> hostEntryTarget(0);
        };
    }

    @Override public Map<String, Object> rootCounts() {
        int sourceRootCount = 0;
        for (var root : roots)
            if (root.getBytecodeNode().hasSourceInformation() && root.getSourceSection() != null) ++sourceRootCount;
        return Map.of("bytecodeRootCount", roots.size(), "loweredRootCount", roots.size(),
            "hostEntryRootCount", hostEntries.size(), "initializedBindingCount", initializedBindingCount,
            "sourceRootCount", sourceRootCount);
    }

    @Override public Map<String, Object> diagnostics() {
        var result = new LinkedHashMap<String, Object>();
        result.put("backend", "bytecode");
        result.put("asyncExceptions", eagerAsyncPolls);
        result.putAll(rootCounts());
        result.put("sourceNotesEnabled", sources.getEnabled());
        result.put("sourceSpanCount", sources.getSpanCount());
        result.put("localJoinCount", localJoinCount);
        result.put("localJoinTransfers", metrics.getLocalJoinTransfers());
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
        result.put("selfTailReentries", metrics.getSelfTailReentries());
        result.put("trampolineIterations", metrics.getTrampolineIterations());
        result.put("papAllocations", metrics.getPapAllocations());
        String unsupportedPolicy = "reject-at-load";
        if (diagnosticUnsupported) unsupportedPolicy = "diagnostic-traps";
        else for (var binding : bindings) if (binding.get("expr") instanceof thc.CoreBindingBody) {
            unsupportedPolicy = "reject-at-binding-admission";
            break;
        }
        result.put("unsupportedPolicy", unsupportedPolicy);
        result.put("foreignUnsupportedPolicy", "trap-when-reached");
        result.put("deferredUnsupported", new ArrayList<>(deferredUnsupported));
        result.put("unsupportedTraps", metrics.getUnsupportedTraps());
        result.put("frames", "Bytecode DSL primitive locals; selective StaticShape captures");
        result.put("stackPolicy", enableAsync ? "tail-safe; bounded bytecode activation chains" :
            "tail-safe; non-tail calls and nested thunk forcing use host stack");
        result.put("threadPolicy", enableAsync ? "context-owned Java threads; resumable asynchronous delivery" :
            "context-owned Java threads; external asynchronous delivery disabled");
        return result;
    }

    /** Actual decoded instruction listings, available without a Graal graph viewer. */
    @Override public String bytecodeDump() {
        var dump = new StringBuilder();
        for (var root : roots) {
            if (!dump.isEmpty()) dump.append("\n\n");
            dump.append(root.getName()).append('\n').append(root.getBytecodeNode().dump());
        }
        return dump.toString();
    }

    private Local bind(Scope scope, String name, boolean primitive) {
        return bind(scope, name, primitive, UNKNOWN, false, null, null);
    }
    private Local bind(Scope scope, String name, boolean primitive, CoreRepresentation proof) {
        return bind(scope, name, primitive, proof, false, null, null);
    }
    private Local bind(Scope scope, String name, boolean primitive, CoreRepresentation proof, boolean cell) {
        return bind(scope, name, primitive, proof, cell, null, null);
    }
    private Local bind(Scope scope, String name, boolean primitive, CoreRepresentation proof, boolean cell,
            boolean[] entry, CoreApplicationCertificates.Arity arityCertificate) {
        var local = new Local(nextLocal++, name, !cell && (proof.getPresent() ? proof.isLong() : primitive),
            proof, cell, entry, arityCertificate);
        scope.bindLocal(name, local);
        return local;
    }

    private static boolean representation(Map<String, Object> binding) {
        if (binding.get("lifted") instanceof Boolean lifted) return lifted;
        return CoreRepresentations.mayBeLazy(binding.get("lifted"), CoreRepresentations.binder(binding));
    }

    private DataLayout dataLayout(String id) {
        try (var ownership = preparationLock.acquire()) {
            return dataLayouts.computeIfAbsent(id, key -> {
                var info = constructors.get(key);
                if (info == null) throw new RuntimeFault("Missing constructor metadata " + key);
                return thc.Language.currentState().constructorLayout(language, key, (String) info.get("name"),
                    new CoreFields(info));
            });
        }
    }

    /** The constructor adapter enforces strict fields before storing them. */
    private boolean fieldIsEvaluated(String id, int index) {
        var constructor = Objects.requireNonNull(constructors.get(id));
        Object lifted = constructor.get("fieldLifted") instanceof List<?> fields && index < fields.size()
            ? fields.get(index) : null;
        Object strict = constructor.get("strictFields") instanceof List<?> fields && index < fields.size()
            ? fields.get(index) : null;
        return Boolean.FALSE.equals(lifted) || Boolean.TRUE.equals(strict);
    }

    private boolean[] strictConstructorFields(String id, int arity) {
        var info = constructors.get(id);
        if (info == null) throw new RuntimeFault("Missing constructor metadata " + id);
        Object kind = info.get("kind");
        if (kind != null && !"boxed".equals(kind))
            throw new UnsupportedCore("Unsupported constructor representation " + kind + ": " + id);
        if (((Number) info.get("arity")).intValue() != arity)
            throw new RuntimeFault("Constructor arity mismatch: " + id);
        if (!(info.get("strictFields") instanceof List<?> strict))
            throw new RuntimeFault("Missing constructor strictness metadata: " + id);
        if (!(info.get("fieldLifted") instanceof List<?> lifted))
            throw new RuntimeFault("Missing constructor representation metadata: " + id);
        if (strict.size() != arity || lifted.size() != arity)
            throw new RuntimeFault("Constructor metadata length mismatch: " + id);
        var result = new boolean[arity];
        for (int i = 0; i < arity; ++i) {
            if (!(strict.get(i) instanceof Boolean strictField))
                throw new RuntimeFault("Unknown constructor field strictness: " + id);
            if (strictField) {
                result[i] = CoreRepresentations.mayBeLazy(lifted.get(i), dataLayout(id).logicalProof(i));
            }
        }
        return result;
    }

    private record CapturedLocal(Local source, Local destination) {}

    private FunctionSpec function(String label, List<Map<String, Object>> args, List<Object> expression, Scope outer) {
        return function(label, args, expression, outer, CoreRepresentations.expression(expression), new boolean[args.size()]);
    }

    private FunctionSpec function(String label, List<Map<String, Object>> args, List<Object> expression, Scope outer,
            CoreRepresentation resultProof, boolean[] entryStrict) {
        return function(label, args, expression, outer, resultProof, entryStrict, true, false);
    }

    private FunctionSpec function(String label, List<Map<String, Object>> args, List<Object> expression, Scope outer,
            CoreRepresentation resultProof, boolean[] entryStrict, boolean tail, boolean passThrough) {
        return function(label, args, expression, outer, resultProof, entryStrict, tail, passThrough, true);
    }

    private FunctionSpec function(String label, List<Map<String, Object>> args, List<Object> expression, Scope outer,
            CoreRepresentation resultProof, boolean[] entryStrict, boolean tail, boolean passThrough, boolean entryPoll) {
        if (entryStrict.length != args.size()) throw new RuntimeFault("Function entry contract arity mismatch");
        var context = new FunctionContext(args.size(), entryStrict.clone());
        context.passThrough = passThrough;
        context.entryPoll = entryPoll;
        context.preparingCaseRegion = passThrough;
        var scope = new Scope(context, outer.source);
        var free = CoreFreeVariables.coreFreeVariables(expression);
        var argumentIds = new LinkedHashSet<String>();
        var argumentProofs = new ArrayList<CoreRepresentation>();
        for (var arg : args) {
            argumentIds.add((String) arg.get("id"));
            var proof = CoreRepresentations.binder(arg);
            CoreRepresentations.requireInput(proof);
            argumentProofs.add(proof);
        }
        context.inputLayout = ArgumentLayout.fromProofs(argumentProofs);
        var freeAggregates = new LinkedHashMap<String, AggregateLocal>();
        var freeLocals = new ArrayList<Local>();
        for (String id : free) {
            if (argumentIds.contains(id)) continue;
            var aggregate = outer.tuples.get(id);
            if (aggregate != null) {
                CoreRepresentations.requireInput(aggregate.proof);
                if (aggregate.fields.size() != ArgumentLayout.leaves(aggregate.proof).size())
                    throw new RuntimeFault("Aggregate capture slot count mismatch");
                freeAggregates.put(id, aggregate);
            }
            if (outer.locals.containsKey(id)) freeLocals.add(Objects.requireNonNull(outer.locals.get(id)));
        }
        var scalarSources = new ArrayList<Local>();
        for (var local : freeLocals) {
            if (local.id < 0 && local.proof.getKind() == CoreKind.VOID) scope.bindVoid(local.name, local.proof);
            else scalarSources.add(local);
        }
        for (var local : scalarSources) CoreRepresentations.requireScalar(local.proof, "capture");
        var captureSources = new ArrayList<>(scalarSources);
        var captureDestinations = new ArrayList<Local>();
        for (var local : scalarSources)
            captureDestinations.add(bind(scope, local.name, local.primitive, local.proof, local.cell, local.entry,
                local.arityCertificate));
        var vectorFields = new ArrayList<CapturedLocal>();
        for (var item : freeAggregates.entrySet()) {
            String id = item.getKey();
            var aggregate = item.getValue();
            var destinations = new ArrayList<Local>();
            var leaves = ArgumentLayout.leaves(aggregate.proof);
            for (int i = 0; i < leaves.size(); ++i) {
                var field = leaves.get(i);
                destinations.add(new Local(nextLocal++, id + " captured field " + i, field.isLong(), field));
            }
            scope.bindTuple(id, aggregate.proof, destinations);
            for (int i = 0; i < aggregate.fields.size(); ++i) {
                var destination = destinations.get(i);
                var source = aggregate.fields.get(i);
                if (destination.proof.isVector()) vectorFields.add(new CapturedLocal(source, destination));
                else { captureSources.add(source); captureDestinations.add(destination); }
            }
        }
        context.captures = captureDestinations;
        context.vectorCaptures = new ArrayList<>();
        var vectorSources = new ArrayList<Local>();
        for (var vector : vectorFields) {
            context.vectorCaptures.add(new VectorCapture(vector.destination.proof, List.of(vector.destination)));
            vectorSources.add(vector.source);
        }
        int vectorCount = vectorFields.size();
        int captureCount = captureSources.size() + vectorCount;
        if (captureCount != 0) {
            var vectorProofs = new CoreRepresentation[captureCount];
            var primitive = new boolean[captureCount];
            var directLong = new boolean[captureCount];
            var references = new Class<?>[captureCount];
            var directFloat = new boolean[captureCount];
            var directDouble = new boolean[captureCount];
            var narrow = new NarrowInteger[captureCount];
            for (int i = 0; i < captureSources.size(); ++i) {
                var local = captureSources.get(i);
                primitive[i] = local.primitive;
                directLong[i] = local.directLong();
                references[i] = local.cell ? null : local.proof.referenceCarrier();
                directFloat[i] = local.directFloat();
                directDouble[i] = local.directDouble();
                narrow[i] = !local.cell && local.proof.getEvaluated() ? local.proof.getNarrowInteger() : null;
            }
            for (int i = 0; i < vectorCount; ++i)
                vectorProofs[captureSources.size() + i] = vectorFields.get(i).destination.proof;
            context.captureLayout = CaptureLayout.withVectors(language,
                vectorProofs, primitive, directLong, references, directFloat, directDouble, narrow);
        }
        context.typedInput = TypedInputLayout.create(language, context.inputLayout, context.captureLayout != null);
        var physicalArguments = new ArrayList<TypedArgument>();
        context.arguments = new ArrayList<>();
        for (int index = 0; index < args.size(); ++index) {
            var arg = args.get(index);
            String id = (String) arg.get("id");
            boolean lifted = representation(arg);
            var proof = passThrough ? CoreRepresentations.binder(arg)
                    : evaluatedProof(CoreRepresentations.binder(arg), !lifted || context.entryStrict[index]);
            int offset = ArgumentLayout.offset(context.inputLayout, index);
            if (proof.isTypedTransport()) {
                if (lifted) throw new RuntimeFault(proof.isVector() ? "Vector formal cannot be lifted" : "Tuple formal cannot be lifted");
                var fields = new ArrayList<Local>();
                if (free.contains(id)) {
                    var leaves = ArgumentLayout.leaves(proof);
                    for (int leaf = 0; leaf < leaves.size(); ++leaf) {
                        var field = leaves.get(leaf);
                        var local = new Local(nextLocal++, id + " field " + leaf, field.isLong(), field);
                        fields.add(local);
                        physicalArguments.add(new TypedArgument(offset + leaf, local));
                    }
                }
                scope.bindTuple(id, proof, fields);
                context.arguments.add(null);
            } else if (free.contains(id) || enableAsync && context.entryStrict[index]) {
                var local = bind(scope, id, !lifted && !Boolean.TRUE.equals(arg.get("coercion")), proof);
                physicalArguments.add(new TypedArgument(offset, local));
                context.arguments.add(local);
            } else context.arguments.add(null);
        }
        context.typedArguments = physicalArguments;
        var compiled = compile(expression, scope, tail);
        if (!passThrough && compiled.loweredCase() && context.inputLayout == null) {
            var usedArguments = new LinkedHashSet<>(free);
            usedArguments.retainAll(argumentIds);
            context.leadingCaseReturn = LeadingCaseReturn.discover(args, expression, resultProof,
                context.captureLayout == null ? 1 : 2, usedArguments, context.captureLayout != null,
                this::dataLayout, sources, compiled.source());
        }
        if ((compiled.proof().isSum() || resultProof.isSum()) && (!compiled.proof().isSum() || !resultProof.isSum()))
            throw new RuntimeFault("Sum function requires exact body and declared result proofs");
        var body = new ProvenExpression(compiled, evaluatedProof(compiled.proof().refine(resultProof), compiled.proof().getEvaluated()));
        context.tuple = body.proof.isTypedTransport() ? new TupleShape(body.proof, language) : null;
        var target = build(label, context, body, !passThrough && !body.proof.getEvaluated());
        ((GuestRoot) target.getRootNode()).configureInputProofs(argumentProofs);
        captureSources.addAll(vectorSources);
        return new FunctionSpec(target, context.captureLayout, captureSources, vectorCount != 0);
    }

    private RootCallTarget build(String label, FunctionContext context, Expression body) {
        return build(label, context, body, true);
    }

    private RootCallTarget build(String label, FunctionContext context, Expression body, boolean forceResult) {
        try {
            return buildEncoded(label, context, body, forceResult);
        } catch (BytecodeEncodingException failure) {
            if (!localIndexOverflow(failure) || context.caseEmission == null || !context.caseEmission.inline)
                throw failure;
            // No root was published. Unlike graph-budget recovery, capacity
            // recovery cannot retain an unencodable inline copy or its PCs.
            context.caseEmission.inline = false;
            return buildEncoded(label, context, body, forceResult);
        }
    }

    private static boolean localIndexOverflow(BytecodeEncodingException failure) {
        // The pinned API has no structured encoding reason. Match its exact
        // allocation path, not a message shared by argument/constant encodings.
        var trace = failure.getStackTrace();
        // Native Image can omit the exception factory, but not the identifying
        // allocator sequence. Do not search past an unrecognized leading frame.
        int first = trace.length > 0
                && trace[0].getClassName().equals(BytecodeEncodingException.class.getName())
                && trace[0].getMethodName().equals("create") ? 1 : 0;
        return trace.length >= first + 3
                && trace[first].getClassName().equals("thc.runtime.BytecodeRootGen$Builder")
                && trace[first].getMethodName().equals("safeCastUnsignedShort")
                && trace[first + 1].getClassName().equals("thc.runtime.BytecodeRootGen$Builder$RootStackElement")
                && trace[first + 1].getMethodName().equals("allocateBytecodeLocal")
                && trace[first + 2].getClassName().equals("thc.runtime.BytecodeRootGen$Builder")
                && trace[first + 2].getMethodName().equals("createLocal");
    }

    private RootCallTarget buildEncoded(String label, FunctionContext context, Expression body, boolean forceResult) {
        var source = body.source();
        var config = sources.getEnabled() && sources.getSpanCount() > 0 ? BytecodeConfig.WITH_SOURCE : BytecodeConfig.DEFAULT;
        var typedBloom = new LocalAccessor[1];
        var stackTransaction = new LocalAccessor[1];
        var root = BytecodeRootGen.create(language, config, b -> {
            var section = b.isParsingSources() && source != null ? source.getSection() : null;
            if (section != null) beginSource(b, section);
            b.beginRoot();
            var e = new Emission(b);
            b.emitEnterRoot(metrics);
            if (enableAsync) {
                var transaction = b.createLocal("stack transaction", FrameSlotKind.Object);
                stackTransaction[0] = LocalAccessor.constantOf(transaction);
                b.beginStaticStoreObject(transaction); b.emitCurrentTransaction(); b.endStaticStoreObject();
            }
            if (resumable) {
                // Each snapshot has one exact reference write. Yield parks to the caller's extent.
                e.checkpointRootEntry = b.createLocal("checkpoint root entry mask", FrameSlotKind.Object);
                b.beginStaticStoreObject(e.checkpointRootEntry); b.emitCurrentMask(); b.endStaticStoreObject();
                e.annotationRootEntry = b.createLocal("checkpoint root entry annotations", FrameSlotKind.Object);
                b.beginStaticStoreObject(e.annotationRootEntry); b.emitCurrentAnnotations(); b.endStaticStoreObject();
            }
            var argumentIndices = new HashMap<Integer, Integer>();
            for (int i = 0; i < context.arguments.size(); ++i) {
                var local = context.arguments.get(i);
                if (local != null) argumentIndices.put(local.id, i);
            }
            var locals = new ArrayList<>(context.captures);
            var typedFormals = new HashSet<Integer>();
            for (var argument : context.typedArguments) typedFormals.add(argument.local.id);
            for (var capture : context.vectorCaptures) locals.addAll(capture.destinations);
            if (!context.typedArguments.isEmpty())
                for (var argument : context.typedArguments) locals.add(argument.local);
            else for (var local : context.arguments) if (local != null) locals.add(local);
            for (var local : locals) {
                // Every resumable tail restore checks the same exact scalar carrier.
                // Deferred boxed demands likewise keep both ingress and WHNF as references.
                int argumentIndex = argumentIndices.getOrDefault(local.id, -1);
                boolean typedFormal = context.typedInput != null && typedFormals.contains(local.id) && !enableAsync;
                boolean fixedLoopCarrier = resumable && context.typedInput == null && local.proof.getEvaluated()
                    && (local.proof.isInt() || local.proof.isLong() || local.proof.isFloat() || local.proof.isDouble());
                boolean strictReference = enableAsync && context.typedInput == null && argumentIndex >= 0
                    && context.entryStrict[argumentIndex] && !context.mayLoop && !local.cell
                    && context.captures.isEmpty() && context.vectorCaptures.isEmpty() && staticBoxedReference(local.proof);
                boolean fixedCarrier = (!context.mayLoop || fixedLoopCarrier)
                    && context.captures.isEmpty() && context.vectorCaptures.isEmpty() && !local.cell
                    && (typedFormal || context.typedInput == null && argumentIndex >= 0
                        && !(enableAsync && context.entryStrict[argumentIndex]));
                Object info;
                if (fixedCarrier && (staticWideLong(local.proof)
                        || typedFormal && local.proof.isLong() && local.proof.getEvaluated())) {
                    e.staticLocals.add(local.id);
                    info = FrameSlotKind.Long;
                } else if (fixedCarrier && local.proof.getEvaluated() && !local.proof.isTypedTransport() &&
                        (local.proof.isInt() || local.proof.isFloat() || local.proof.isDouble())) {
                    var kind = FrameLayout.carrierKind(local.proof);
                    e.staticScalars.put(local.id, kind);
                    info = kind;
                } else if (strictReference || fixedCarrier && (staticBoxedReference(local.proof) || local.proof.getKind() == CoreKind.VOID
                        || typedFormal && FrameLayout.carrierKind(local.proof) == FrameSlotKind.Object)) {
                    e.staticObjectLocals.add(local.id);
                    info = FrameSlotKind.Object;
                } else {
                    // Initial carrier metadata does not make a multi-write local static.
                    info = local.cell ? FrameSlotKind.Object : FrameLayout.carrierKind(local.proof);
                }
                e.locals.put(local.id, b.createLocal(local.name, info));
            }
            var typed = context.typedInput;
            if (typed != null) {
                var bloom = LocalAccessor.constantOf(b.createLocal("typed input bloom", FrameSlotKind.Long));
                typedBloom[0] = bloom;
                var physical = context.typedArguments;
                var deferredStrict = new HashSet<Integer>();
                for (int i = 0; i < context.arguments.size(); ++i) {
                    var local = context.arguments.get(i);
                    if (enableAsync && context.entryStrict[i] && local != null && !local.primitive)
                        deferredStrict.add(local.id);
                }
                var arguments = new LocalAccessor[physical.size()];
                var indices = new int[physical.size()];
                var proofs = new CoreRepresentation[physical.size()];
                for (int i = 0; i < physical.size(); ++i) {
                    var argument = physical.get(i);
                    arguments[i] = LocalAccessor.constantOf(Objects.requireNonNull(e.locals.get(argument.local.id)));
                    indices[i] = argument.index;
                    proofs[i] = deferredStrict.contains(argument.local.id)
                        ? evaluatedProof(argument.local.proof, false) : argument.local.proof;
                }
                var captures = new LocalAccessor[context.captures.size()];
                var captureProofs = new CoreRepresentation[captures.length];
                for (int i = 0; i < captures.length; ++i) {
                    var local = context.captures.get(i);
                    captures[i] = LocalAccessor.constantOf(Objects.requireNonNull(e.locals.get(local.id)));
                    captureProofs[i] = local.cell ? UNKNOWN : local.proof;
                }
                var slots = new BytecodeTypedInputSlots(typed, bloom, arguments, indices, proofs,
                    context.captureLayout, captures, captureProofs, vectorCaptureSlots(e, context));
                e.typedInputSlots = slots;
                b.emitRestoreTypedInput(slots);
            } else {
                for (int i = 0; i < context.captures.size(); ++i) {
                    var local = context.captures.get(i);
                    b.beginStoreLocal(Objects.requireNonNull(e.locals.get(local.id)));
                    if (local.directLong()) b.beginCaptureReadLong(Objects.requireNonNull(context.captureLayout), i);
                    else b.beginCaptureRead(Objects.requireNonNull(context.captureLayout), i);
                    b.emitLoadArgument(1);
                    if (local.directLong()) b.endCaptureReadLong(); else b.endCaptureRead();
                    b.endStoreLocal();
                }
                for (var slots : vectorCaptureSlots(e, context)) {
                    b.beginCaptureReadVector(slots); b.emitLoadArgument(1); b.endCaptureReadVector();
                }
                int offset = context.captureLayout == null ? 1 : 2;
                for (int i = 0; i < context.arguments.size(); ++i) {
                    var local = context.arguments.get(i);
                    if (local == null) continue;
                    int argument = ArgumentLayout.offset(context.inputLayout, i) + offset;
                    restoreArgument(e, local, enableAsync && context.entryStrict[i], () -> b.emitLoadArgument(argument));
                }
            }
            if (enableAsync) {
                // The saved PC follows ingress restoration, before any body effects.
                b.beginIfThen(); b.emitStackLimit();
                b.beginBlock();
                var resumed = b.createLocal("stack entry resume value", FrameSlotKind.Object);
                b.beginStaticStoreObject(resumed);
                b.beginYield(); b.emitLoadConstant(AstStackSpill.INSTANCE); b.endYield();
                b.endStaticStoreObject();
                b.endBlock(); b.endIfThen();
            }
            if (context.mayLoop) {
                b.beginWhile();
                b.emitLoadConstant(true);
                b.beginBlock();
                e.continueLabel = b.createLabel();
            }
            if (enableAsync && context.entryPoll) emitAsyncPoll(e);
            if (enableAsync) emitEntryStrictDemands(e, context);
            var tuple = context.tuple;
            if (tuple != null) {
                var result = new ArrayList<BytecodeLocal>();
                for (int i = 0; i < tuple.getWidth(); ++i) {
                    var kind = FrameLayout.carrierKind(tuple.getLeaves()[i]);
                    var slot = b.createLocal("tuple result " + i, kind);
                    result.add(slot);
                    if (!context.mayLoop) e.staticResults.put(slot, kind);
                }
                body.emitTuple(e, result);
                b.beginReturn(); b.emitFinishTuple(tupleSlots(tuple, result)); b.endReturn();
            } else {
                b.beginReturn();
                if (forceResult) force(body).emit(e); else body.emit(e);
                b.endReturn();
            }
            if (context.mayLoop) {
                b.emitLabel(Objects.requireNonNull(e.continueLabel));
                if (enableAsync) emitAsyncPoll(e);
                b.endBlock();
                b.endWhile();
                // The loop condition is true; retain a terminating operation for the builder.
                b.beginReturn(); b.emitFailCase(); b.endReturn();
            }
            b.endRoot();
            if (section != null) endSource(b);
        }).getNode(0);
        root.setLabel(label);
        root.configureForeignExceptionBridge(foreignExceptionBridge);
        root.configureAsync(enableAsync);
        root.configureEagerAsyncPolls(eagerAsyncPolls);
        root.configureStackDriver(metrics);
        root.configureStackTransaction(stackTransaction[0]);
        root.configureDelimited(delimited);
        root.configureEntry(context.entryStrict, context.captureLayout != null);
        root.configureInput(context.inputLayout);
        root.configureTypedInput(context.typedInput);
        root.configureTypedBloom(typedBloom[0]);
        root.configureLeadingCaseReturn(context.leadingCaseReturn);
        root.configureTupleResult(context.tuple);
        root.configureScalarResult(body.proof());
        root.configureCaseRegions(context.passThrough, context.caseEmission != null && context.caseEmission.inline,
                context.caseRegions.toArray(BytecodeCaseRegion[]::new));
        roots.add(root);
        return root.getCallTarget();
    }

    private static BytecodeRoot.VectorCaptureSlots[] vectorCaptureSlots(Emission e, FunctionContext context) {
        var result = new BytecodeRoot.VectorCaptureSlots[context.vectorCaptures.size()];
        for (int i = 0; i < result.length; ++i) {
            var vector = context.vectorCaptures.get(i);
            var lanes = new LocalAccessor[vector.destinations.size()];
            for (int j = 0; j < lanes.length; ++j)
                lanes[j] = LocalAccessor.constantOf(Objects.requireNonNull(e.locals.get(vector.destinations.get(j).id)));
            result[i] = new BytecodeRoot.VectorCaptureSlots(Objects.requireNonNull(context.captureLayout),
                context.captures.size() + i, lanes);
        }
        return result;
    }

    /** Yield skips lexical finally. Save this activation's annotations and park to its caller. */
    private static void beginAnnotationYield(Emission e) {
        beginAnnotationYield(e, e.builder.createLocal("yielded annotations", FrameSlotKind.Object));
    }
    private static void beginAnnotationYield(Emission e, BytecodeLocal active) {
        var b = e.builder;
        b.beginResumeAnnotations(active);
        b.beginYield();
        b.beginParkAnnotations(Objects.requireNonNull(e.annotationRootEntry), active);
    }
    private static void endAnnotationYield(Emission e) {
        e.builder.endParkAnnotations(); e.builder.endYield(); e.builder.endResumeAnnotations();
    }

    private void emitAsyncPoll(Emission e) { emitAsyncPoll(e, null, null); }

    /** The cold Yield carries the exact bytecode frame; ordinary polls allocate no packet. */
    private void emitAsyncPoll(Emission e, BytecodeLocal processResult, BytecodeLocal processErrno) {
        emitAsyncPoll(e, processResult, processErrno, false);
    }
    private void emitAsyncPoll(Emission e, BytecodeLocal processResult, BytecodeLocal processErrno, boolean mandatory) {
        var b = e.builder;
        b.beginBlock();
        var request = b.createLocal("pending async request", FrameSlotKind.Object);
        var active = b.createLocal("async logical mask", FrameSlotKind.Object);
        var annotations = b.createLocal("yielded annotations", FrameSlotKind.Object);
        // Every runtime writer uses setObject for these exact reference carriers.
        for (var local : List.of(request, active, annotations)) {
            b.beginStaticStoreObject(local); b.emitLoadNull(); b.endStaticStoreObject();
        }
        b.beginIfThen();
        if (mandatory) b.emitPollMandatoryAsync(request);
        else if (processResult == null) b.emitPollAsync(request);
        else {
            b.beginPollProcessCompleted(request);
            b.emitLoadLocal(processResult); b.emitLoadLocal(Objects.requireNonNull(processErrno));
            b.endPollProcessCompleted();
        }
        b.beginBlock();
        b.beginReenterPendingAsyncMask(active);
        beginAnnotationYield(e, annotations);
        b.emitParkPendingAsyncMask(request, Objects.requireNonNull(e.checkpointRootEntry), active);
        endAnnotationYield(e);
        b.endReenterPendingAsyncMask();
        b.endBlock();
        b.endIfThen();
        b.endBlock();
    }

    /** Blocking operands are evaluated once; only their uncommitted request is retried. */
    private void emitBlockingRequest(Emission e, List<Expression> operands, boolean result,
            Consumer<List<BytecodeLocal>> operation) {
        var b = e.builder;
        b.beginBlock();
        var values = new ArrayList<BytecodeLocal>();
        for (int i = 0; i < operands.size(); ++i) {
            // The request ABI saves boxed scalar values, without observing or forcing them.
            var local = b.createLocal("Blocking operand " + i, FrameSlotKind.Object);
            b.beginStaticStoreObject(local); operands.get(i).emit(e); b.endStaticStoreObject();
            values.add(local);
        }
        emitOwnerWaitRetry(e, () -> operation.accept(values));
        if (result) b.emitLoadConstant(thc.runtime.Unit.INSTANCE);
        b.endBlock();
    }

    /** Retry only an uncommitted request; the producer/local operand stays saved. */
    private void emitOwnerWaitRetry(Emission e, Runnable attempt) {
        if (!enableAsync) { attempt.run(); return; }
        var b = e.builder;
        b.beginBlock();
        var complete = b.createLabel();
        var request = b.createLocal("owner wait async request", FrameSlotKind.Object);
        var active = b.createLocal("owner wait logical mask", FrameSlotKind.Object);
        var discard = b.createLocal("owner wait resume value", FrameSlotKind.Object);
        b.beginWhile(); b.emitLoadConstant(true); b.beginBlock();
        b.beginTryCatch();
        b.beginBlock();
        attempt.run();
        b.emitBranch(complete);
        b.endBlock();
        b.beginBlock();
        b.beginStaticStoreObject(request);
        b.beginCallSuspensionOnly(); b.emitLoadException(); b.endCallSuspensionOnly();
        b.endStaticStoreObject();
        b.beginStaticStoreObject(active); b.emitCurrentMask(); b.endStaticStoreObject();
        b.beginStaticStoreObject(discard);
        b.beginReenterCallMask();
        beginAnnotationYield(e);
        b.beginParkAsyncMask();
        b.emitStaticLoadObject(request);
        b.emitStaticLoadObject(Objects.requireNonNull(e.checkpointRootEntry));
        b.endParkAsyncMask();
        endAnnotationYield(e);
        b.emitStaticLoadObject(active);
        b.endReenterCallMask();
        b.endStaticStoreObject();
        b.endBlock();
        b.endTryCatch();
        b.endBlock(); b.endWhile();
        b.emitLabel(complete);
        b.endBlock();
    }

    private void restoreArgument(Emission e, Local local, Runnable value) { restoreArgument(e, local, false, value); }

    /** Choose checked reference identities while emitting code, never by a guest-time enum switch. */
    private void restoreArgument(Emission e, Local local, boolean deferStrictDemand, Runnable value) {
        var b = e.builder;
        var scalar = e.staticScalars.get(local.id);
        if (scalar != null) {
            if (deferStrictDemand) throw new IllegalStateException("Check failed.");
            var slot = Objects.requireNonNull(e.locals.get(local.id));
            switch (scalar) {
                case Int -> { b.beginStaticStoreInt(slot); value.run(); b.endStaticStoreInt(); }
                case Float -> { b.beginStaticStoreFloat(slot); value.run(); b.endStaticStoreFloat(); }
                case Double -> { b.beginStaticStoreDouble(slot); value.run(); b.endStaticStoreDouble(); }
                default -> throw new AssertionError(scalar);
            }
            return;
        }
        if (e.staticLocals.contains(local.id)) {
            if (deferStrictDemand) throw new IllegalStateException("Check failed.");
            b.beginStaticStoreLong(Objects.requireNonNull(e.locals.get(local.id)));
            value.run();
            b.endStaticStoreLong();
            return;
        }
        var reference = local.cell || deferStrictDemand ? null : local.proof.referenceCarrier();
        boolean staticObject = e.staticObjectLocals.contains(local.id);
        if (staticObject) {
            b.beginStaticStoreObject(Objects.requireNonNull(e.locals.get(local.id)));
        } else b.beginStoreLocal(Objects.requireNonNull(e.locals.get(local.id)));
        if (local.directInt()) { b.beginToInt(); value.run(); b.endToInt(); }
        else if (local.directLong()) { b.beginToLong(); value.run(); b.endToLong(); }
        else if (local.directFloat()) { b.beginToFloat(); value.run(); b.endToFloat(); }
        else if (local.directDouble()) { b.beginToDouble(); value.run(); b.endToDouble(); }
        else if (reference == DataValue.class) { b.beginRequireData(); value.run(); b.endRequireData(); }
        else if (reference == Closure.class) { b.beginRequireClosure(); value.run(); b.endRequireClosure(); }
        else if (reference == ManagedAddress.class) { b.beginRequireAddress(); value.run(); b.endRequireAddress(); }
        else value.run();
        if (staticObject) b.endStaticStoreObject(); else b.endStoreLocal();
    }

    private static Expression sourced(Expression value, CoreSourceLocation source) {
        return source == null || Objects.equals(value.source(), source) ? value : new SourcedExpression(value, source);
    }

    private static Expression undecorated(Expression value) {
        while (true) {
            if (value instanceof ProvenExpression proven) value = proven.expression;
            else if (value instanceof SourcedExpression sourced) value = sourced.expression;
            else return value;
        }
    }

    private Expression read(Local local) { return read(local, true); }
    private Expression read(Local local, boolean resolve) {
        return local.id < 0 && local.proof.getKind() == CoreKind.VOID
            ? new ProvenExpression(constant(thc.runtime.Unit.INSTANCE), local.proof) : new LocalExpression(local, resolve);
    }
    private static Expression evaluated(Expression value) { return new ProvenExpression(value, evaluatedProof(value.proof(), true)); }

    /** Async callees demand their own CBV formals at a captured bytecode cut. */
    private void emitEntryStrictDemands(Emission e, FunctionContext context) {
        for (int i = 0; i < context.arguments.size(); ++i) {
            var local = context.arguments.get(i);
            if (!context.entryStrict[i] || local == null || local.primitive || local.proof.isTypedTransport()) continue;
            var raw = new ProvenExpression(read(local), evaluatedProof(local.proof, false));
            restoreArgument(e, local, () -> force(raw).emit(e));
        }
    }

    private Expression force(Expression value) {
        if (value.proof().isTypedTransport() || value.proof().getEvaluated()) return value;
        // Generic force and resume return boxed Object carriers, including when
        // the proved WHNF is primitive. Keep that ABI in their private scratch.
        var forced = evaluated(new ResultExpression((e, destination) -> {
            if (destination != null) { value.emitTuple(e, destination); return; }
            var b = e.builder;
            var localValue = undecorated(value);
            if (localValue instanceof LocalExpression localExpression && localExpression.resolve) {
                var local = Objects.requireNonNull(e.locals.get(localExpression.local.id));
                if (!resumable) {
                    b.beginForceLocal(metrics, local, localExpression.local.cell, false);
                    b.emitStaticLoadObject(local); b.endForceLocal();
                } else {
                    var result = b.createLocal("forced local result", FrameSlotKind.Object);
                    var suspended = b.createLocal("forced local suspension", FrameSlotKind.Object);
                    b.beginBlock();
                    emitOwnerWaitRetry(e, () -> {
                        b.beginTryCatch();
                        b.beginStaticStoreObject(result);
                        b.beginForceLocal(metrics, local, localExpression.local.cell, enableAsync);
                        b.emitStaticLoadObject(local); b.endForceLocal();
                        b.endStaticStoreObject();
                        b.beginBlock();
                        b.beginStaticStoreObject(suspended);
                        b.beginSuspensionOnly(); b.emitLoadException(); b.endSuspensionOnly();
                        b.endStaticStoreObject();
                        b.beginStaticStoreObject(result);
                        b.beginResumeForcedLocal(local, localExpression.local.cell);
                        b.emitStaticLoadObject(suspended);
                        beginAnnotationYield(e); b.emitStaticLoadObject(suspended); endAnnotationYield(e);
                        b.endResumeForcedLocal();
                        b.endStaticStoreObject();
                        b.endBlock();
                        b.endTryCatch();
                    });
                    b.emitStaticLoadObject(result);
                    b.endBlock();
                }
            } else if (!resumable) {
                b.beginForceValue(metrics, false); value.emit(e); b.endForceValue();
            } else {
                var operand = b.createLocal("saved force operand", FrameSlotKind.Object);
                var result = b.createLocal("forced value result", FrameSlotKind.Object);
                var suspended = b.createLocal("forced value suspension", FrameSlotKind.Object);
                b.beginBlock();
                // Evaluate the producer once, before any child ownership is claimed.
                b.beginStaticStoreObject(operand); value.emit(e); b.endStaticStoreObject();
                emitOwnerWaitRetry(e, () -> {
                    b.beginTryCatch();
                    b.beginStaticStoreObject(result);
                    b.beginForceValue(metrics, enableAsync); b.emitStaticLoadObject(operand); b.endForceValue();
                    b.endStaticStoreObject();
                    b.beginBlock();
                    b.beginStaticStoreObject(suspended);
                    b.beginSuspensionOnly(); b.emitLoadException(); b.endSuspensionOnly();
                    b.endStaticStoreObject();
                    b.beginStaticStoreObject(result);
                    b.beginResumeForcedValue();
                    b.emitStaticLoadObject(operand);
                    b.emitStaticLoadObject(suspended);
                    beginAnnotationYield(e); b.emitStaticLoadObject(suspended); endAnnotationYield(e);
                    b.endResumeForcedValue();
                    b.endStaticStoreObject();
                    b.endBlock();
                    b.endTryCatch();
                });
                b.emitStaticLoadObject(result);
                b.endBlock();
            }
        }));
        return sourced(new ProvenExpression(forced, evaluatedProof(value.proof(), true)), value.source());
    }

    private Expression requireClosure(Expression value) {
        return e -> { e.builder.beginRequireClosure(); force(value).emit(e); e.builder.endRequireClosure(); };
    }

    private void forceSavedCallback(Emission e, BytecodeLocal slot, CoreRepresentation proof) {
        // Enter the enclosing catch/mask scope before forcing the callback at its resumable cut.
        var saved = new ProvenExpression(emission -> emission.builder.emitLoadLocal(slot), evaluatedProof(proof, false));
        force(saved).emit(e);
    }

    private Expression delay(List<Object> expr, Scope scope, String label) {
        var fn = function(label, List.of(), expr, scope);
        var tuple = ((GuestRoot) fn.target.getRootNode()).getTupleResult();
        if (tuple != null) CoreRepresentations.requireScalar(tuple.getProof(), "thunk");
        var template = new BytecodeRoot.ClosureTemplate(fn.target, 0, fn.captureLayout);
        return sourced(new ProvenExpression(e -> {
            if (fn.hasVectorCaptures) {
                var captures = new LocalAccessor[fn.captures.size()];
                for (int i = 0; i < captures.length; ++i)
                    captures[i] = LocalAccessor.constantOf(Objects.requireNonNull(e.locals.get(fn.captures.get(i).id)));
                e.builder.emitMakeVectorCapture(new BytecodeRoot.VectorCaptureSource(template, captures, true));
            } else {
                e.builder.beginMakeThunk(template);
                for (var capture : fn.captures) read(capture, false).emit(e);
                e.builder.endMakeThunk();
            }
        }, evaluatedProof(CoreRepresentations.expression(expr), false)), sources.expression(expr, scope.source));
    }

    private Expression closure(FunctionSpec fn, int arity) {
        var template = new BytecodeRoot.ClosureTemplate(fn.target, arity, fn.captureLayout);
        return evaluated(e -> {
            if (fn.hasVectorCaptures) {
                var captures = new LocalAccessor[fn.captures.size()];
                for (int i = 0; i < captures.length; ++i)
                    captures[i] = LocalAccessor.constantOf(Objects.requireNonNull(e.locals.get(fn.captures.get(i).id)));
                e.builder.emitMakeVectorCapture(new BytecodeRoot.VectorCaptureSource(template, captures, false));
            } else {
                e.builder.beginMakeClosure(template);
                for (var capture : fn.captures) read(capture, false).emit(e);
                e.builder.endMakeClosure();
            }
        });
    }

    private Expression argument(List<Object> expr, Scope scope, boolean lifted) {
        return argument(expr, scope, lifted, "argument thunk", false, lifted);
    }
    private Expression argument(List<Object> expr, Scope scope, boolean lifted, String label) {
        return argument(expr, scope, lifted, label, false, lifted);
    }
    private Expression argument(List<Object> expr, Scope scope, boolean lifted, String label,
            boolean allowEmpty, boolean declaredLifted) {
        var proof = CoreRepresentations.expression(expr);
        checkArgumentProof(proof, allowEmpty, declaredLifted);
        CoreRepresentation lexical = null;
        if ("var".equals(expr.getFirst())) {
            var aggregate = scope.tuples.get(expr.get(1));
            var local = scope.locals.get(expr.get(1));
            lexical = aggregate != null ? aggregate.proof : local != null ? local.proof : null;
        }
        if (lexical != null) checkArgumentProof(lexical, allowEmpty, declaredLifted);
        Supplier<Expression> lowered = () -> {
            var value = compile(expr, scope, false);
            checkArgumentProof(value.proof(), allowEmpty, declaredLifted);
            return value;
        };
        if (proof.isTypedTransport() || lexical != null && lexical.isTypedTransport()) {
            var value = lowered.get();
            if (!value.proof().isTypedTransport()) throw new RuntimeFault("Missing exact typed argument proof");
            return value;
        }
        // A tuple behind omitted case metadata must remain a destination writer.
        if (!lifted) return force(lowered.get());
        String headId = null;
        if ("app".equals(expr.getFirst()) && expr.size() > 1 && expr.get(1) instanceof List<?> head
                && !head.isEmpty() && "var".equals(head.getFirst()) && head.size() > 1
                && head.get(1) instanceof String id) headId = id;
        CoreApplicationCertificates.Arity arityCertificate = null;
        if (headId != null) arityCertificate = scope.locals.containsKey(headId)
            ? Objects.requireNonNull(scope.locals.get(headId)).arityCertificate : globalArityCertificates.get(headId);
        boolean unopenedHead = headId != null && !scope.locals.containsKey(headId)
            && !globalArityCertificates.containsKey(headId) && demand != null && demand.contains(headId);
        if (unopenedHead && CoreApplicationCertificates.eagerApplication(expr, null)) {
            var application = lowered.get();
            var suspension = delay(expr, scope, label);
            var head = Objects.requireNonNull(demand.cell(headId));
            int arguments = ((List<?>) expr.get(2)).size();
            return sourced(new ProvenExpression(e -> {
                e.builder.beginConditional();
                e.builder.emitCanConstructPap(head, arguments);
                application.emit(e);
                suspension.emit(e);
                e.builder.endConditional();
            }, suspension.proof()), suspension.source());
        }
        if (!unopenedHead && CoreApplicationCertificates.eagerApplication(expr, arityCertificate)) return lowered.get();
        return switch ((String) expr.getFirst()) {
            case "var", "lit", "lam", "con", "prim", "void" -> lowered.get();
            default -> delay(expr, scope, label);
        };
    }

    private static void checkArgumentProof(CoreRepresentation proof, boolean allowEmpty, boolean declaredLifted) {
        if (allowEmpty) CoreRepresentations.requireInput(proof);
        else CoreRepresentations.requireScalar(proof, "argument");
        if (proof.isTypedTransport() && declaredLifted)
            throw new RuntimeFault(proof.isVector() ? "Vector argument cannot be lifted" : "Tuple argument cannot be lifted");
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
                if (!"0".equals(value)) throw new UnsupportedCore("Malformed null Addr# literal");
                yield ManagedAddress.nullAddress();
            }
            case "function-addr" -> nativeCallbacks.containsKey(value)
                ? Language.currentState(null).getNativeCallbacks().helper(nativeCallbacks.get(value), this, language)
                : CFinalizerLabels.fromCore(value, proof);
            case "data-addr" -> CoreDataLabels.fromCore(value, proof,
                stackTargetLayout instanceof TargetLayout layout ? layout : null);
            case "bignat" -> ManagedByteArray.fromFreshBytes(BigNatLiterals.decode(value));
            default -> throw new UnsupportedCore("Unsupported literal kind " + kind);
        };
    }

    private static Expression constant(Object value) {
        CoreKind kind = switch (value) {
            case Integer ignored -> CoreKind.LONG;
            case Long ignored -> CoreKind.LONG;
            case Float ignored -> CoreKind.FLOAT;
            case Double ignored -> CoreKind.DOUBLE;
            case ManagedAddress ignored -> CoreKind.ADDRESS;
            default -> value == thc.runtime.Unit.INSTANCE ? CoreKind.VOID : CoreKind.OBJECT;
        };
        return new ProvenExpression(e -> e.builder.emitLoadConstant(value),
            new CoreRepresentation(kind, true, false, null, null, null, null, null, null));
    }

    private Expression compile(List<Object> expr, Scope scope, boolean tail) {
        var inline = CoreStateApplications.inline(expr);
        if (inline != null) return compile(inline, scope, tail);
        var source = sources.expression(expr, scope.source);
        Expression value;
        try {
            var lowered = compileSupported(expr, scope.withSource(source), tail);
            var proof = CoreRepresentations.expression(expr);
            if ("case".equals(expr.getFirst())) proof = CoreRepresentations.caseResult(expr).refine(proof);
            // The denoted value's Core proof cannot assert that a lowered thunk is WHNF.
            boolean diagnosticGlobal = diagnosticUnsupported && "var".equals(expr.getFirst())
                && !scope.locals.containsKey(expr.get(1)) && !scope.joins.containsKey(expr.get(1))
                && globals.containsKey(expr.get(1));
            value = proof.getPresent() && !diagnosticGlobal
                ? new ProvenExpression(lowered, evaluatedProof(lowered.proof().refine(proof), lowered.proof().getEvaluated())) : lowered;
        } catch (UnsupportedCore gap) {
            if (!diagnosticUnsupported) throw gap;
            String message = gap.getMessage() != null ? gap.getMessage() : "Unsupported Core";
            deferredUnsupported.add(message);
            var body = sourced(e -> e.builder.emitUnsupported(message, metrics), source);
            var target = build("unsupported: " + message, new FunctionContext(0), body);
            var template = new BytecodeRoot.ClosureTemplate(target, 0, null);
            value = new Expression() {
                @Override public void emit(Emission emission) {
                    emission.builder.beginMakeThunk(template); emission.builder.endMakeThunk();
                }
                @Override public void emitTuple(Emission emission, List<BytecodeLocal> destination) {
                    // Unsupported never returns; discard its scalar result without touching a tuple destination.
                    var b = emission.builder;
                    b.beginBlock();
                    b.beginStoreLocal(b.createLocal("unsupported tuple", null));
                    b.emitUnsupported(message, metrics);
                    b.endStoreLocal();
                    b.endBlock();
                }
            };
        }
        return sourced(value, source);
    }

    private void compactArguments(Emission e, Expression function, List<Expression> arguments,
            ArgumentLayout layout, BiConsumer<BytecodeLocal, List<BytecodeLocal>> emit) {
        var b = e.builder;
        b.beginBlock();
        var fn = b.createLocal("compact function", null);
        b.beginStoreLocal(fn); requireClosure(function).emit(e); b.endStoreLocal();
        var values = new ArrayList<BytecodeLocal>();
        for (int i = 0; i < arguments.size(); ++i) {
            var argument = arguments.get(i);
            if (layout.isEmpty(i)) argument.emitTuple(e, List.of());
            else {
                var local = b.createLocal("compact operand " + i, null);
                b.beginStoreLocal(local); argument.emit(e); b.endStoreLocal();
                values.add(local);
            }
        }
        emit.accept(fn, values);
        b.endBlock();
    }

    // BEGIN GENERATED SIMD FAMILIES
    private Expression generatedVectorPrimitive(String name, List<Expression> operands, int[] shuffleIndices) {
        return switch (name) {
            case "packInt8X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginBlock();
                var lanes = new ArrayList<BytecodeLocal>();
                for (int i = 0; i < 32; ++i) lanes.add(b.createLocal());
                operands.get(0).emitTuple(e, lanes);
                b.emitGeneratedInt8X32Pack(new BytecodeVectorLanes(accessors(lanes)));
                b.endBlock();
            }, GeneratedVectors.proofInt8X32);
            case "unpackInt8X32#" -> tupleExpression(GeneratedVectors.unpackedInt8X32, (e, destination) -> {
                e.builder.beginGeneratedInt8X32Unpack(new BytecodeVectorLanes(accessors(destination)));
                operands.get(0).emit(e);
                e.builder.endGeneratedInt8X32Unpack();
            });
            case "broadcastInt8X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt8X32Broadcast(); for (var operand : operands) operand.emit(e); b.endGeneratedInt8X32Broadcast();
            }, GeneratedVectors.proofInt8X32);
            case "plusInt8X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt8X32Plus(); for (var operand : operands) operand.emit(e); b.endGeneratedInt8X32Plus();
            }, GeneratedVectors.proofInt8X32);
            case "minusInt8X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt8X32Minus(); for (var operand : operands) operand.emit(e); b.endGeneratedInt8X32Minus();
            }, GeneratedVectors.proofInt8X32);
            case "timesInt8X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt8X32Times(); for (var operand : operands) operand.emit(e); b.endGeneratedInt8X32Times();
            }, GeneratedVectors.proofInt8X32);
            case "negateInt8X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt8X32Negate(); for (var operand : operands) operand.emit(e); b.endGeneratedInt8X32Negate();
            }, GeneratedVectors.proofInt8X32);
            case "insertInt8X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt8X32Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedInt8X32Insert();
            }, GeneratedVectors.proofInt8X32);
            case "minInt8X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt8X32Min(); for (var operand : operands) operand.emit(e); b.endGeneratedInt8X32Min();
            }, GeneratedVectors.proofInt8X32);
            case "maxInt8X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt8X32Max(); for (var operand : operands) operand.emit(e); b.endGeneratedInt8X32Max();
            }, GeneratedVectors.proofInt8X32);
            case "quotInt8X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt8X32Quot(); for (var operand : operands) operand.emit(e); b.endGeneratedInt8X32Quot();
            }, GeneratedVectors.proofInt8X32);
            case "remInt8X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt8X32Rem(); for (var operand : operands) operand.emit(e); b.endGeneratedInt8X32Rem();
            }, GeneratedVectors.proofInt8X32);
            case "shuffleInt8X32#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.ByteVector.SPECIES_256, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedInt8X32Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedInt8X32Shuffle();
                }, GeneratedVectors.proofInt8X32);
            }
            case "packWord8X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginBlock();
                var lanes = new ArrayList<BytecodeLocal>();
                for (int i = 0; i < 32; ++i) lanes.add(b.createLocal());
                operands.get(0).emitTuple(e, lanes);
                b.emitGeneratedWord8X32Pack(new BytecodeVectorLanes(accessors(lanes)));
                b.endBlock();
            }, GeneratedVectors.proofWord8X32);
            case "unpackWord8X32#" -> tupleExpression(GeneratedVectors.unpackedWord8X32, (e, destination) -> {
                e.builder.beginGeneratedWord8X32Unpack(new BytecodeVectorLanes(accessors(destination)));
                operands.get(0).emit(e);
                e.builder.endGeneratedWord8X32Unpack();
            });
            case "broadcastWord8X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord8X32Broadcast(); for (var operand : operands) operand.emit(e); b.endGeneratedWord8X32Broadcast();
            }, GeneratedVectors.proofWord8X32);
            case "plusWord8X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord8X32Plus(); for (var operand : operands) operand.emit(e); b.endGeneratedWord8X32Plus();
            }, GeneratedVectors.proofWord8X32);
            case "minusWord8X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord8X32Minus(); for (var operand : operands) operand.emit(e); b.endGeneratedWord8X32Minus();
            }, GeneratedVectors.proofWord8X32);
            case "timesWord8X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord8X32Times(); for (var operand : operands) operand.emit(e); b.endGeneratedWord8X32Times();
            }, GeneratedVectors.proofWord8X32);
            case "insertWord8X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord8X32Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedWord8X32Insert();
            }, GeneratedVectors.proofWord8X32);
            case "minWord8X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord8X32Min(); for (var operand : operands) operand.emit(e); b.endGeneratedWord8X32Min();
            }, GeneratedVectors.proofWord8X32);
            case "maxWord8X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord8X32Max(); for (var operand : operands) operand.emit(e); b.endGeneratedWord8X32Max();
            }, GeneratedVectors.proofWord8X32);
            case "quotWord8X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord8X32Quot(); for (var operand : operands) operand.emit(e); b.endGeneratedWord8X32Quot();
            }, GeneratedVectors.proofWord8X32);
            case "remWord8X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord8X32Rem(); for (var operand : operands) operand.emit(e); b.endGeneratedWord8X32Rem();
            }, GeneratedVectors.proofWord8X32);
            case "shuffleWord8X32#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.ByteVector.SPECIES_256, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedWord8X32Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedWord8X32Shuffle();
                }, GeneratedVectors.proofWord8X32);
            }
            case "packInt8X64#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginBlock();
                var lanes = new ArrayList<BytecodeLocal>();
                for (int i = 0; i < 64; ++i) lanes.add(b.createLocal());
                operands.get(0).emitTuple(e, lanes);
                b.emitGeneratedInt8X64Pack(new BytecodeVectorLanes(accessors(lanes)));
                b.endBlock();
            }, GeneratedVectors.proofInt8X64);
            case "unpackInt8X64#" -> tupleExpression(GeneratedVectors.unpackedInt8X64, (e, destination) -> {
                e.builder.beginGeneratedInt8X64Unpack(new BytecodeVectorLanes(accessors(destination)));
                operands.get(0).emit(e);
                e.builder.endGeneratedInt8X64Unpack();
            });
            case "broadcastInt8X64#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt8X64Broadcast(); for (var operand : operands) operand.emit(e); b.endGeneratedInt8X64Broadcast();
            }, GeneratedVectors.proofInt8X64);
            case "plusInt8X64#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt8X64Plus(); for (var operand : operands) operand.emit(e); b.endGeneratedInt8X64Plus();
            }, GeneratedVectors.proofInt8X64);
            case "minusInt8X64#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt8X64Minus(); for (var operand : operands) operand.emit(e); b.endGeneratedInt8X64Minus();
            }, GeneratedVectors.proofInt8X64);
            case "timesInt8X64#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt8X64Times(); for (var operand : operands) operand.emit(e); b.endGeneratedInt8X64Times();
            }, GeneratedVectors.proofInt8X64);
            case "negateInt8X64#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt8X64Negate(); for (var operand : operands) operand.emit(e); b.endGeneratedInt8X64Negate();
            }, GeneratedVectors.proofInt8X64);
            case "insertInt8X64#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt8X64Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedInt8X64Insert();
            }, GeneratedVectors.proofInt8X64);
            case "minInt8X64#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt8X64Min(); for (var operand : operands) operand.emit(e); b.endGeneratedInt8X64Min();
            }, GeneratedVectors.proofInt8X64);
            case "maxInt8X64#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt8X64Max(); for (var operand : operands) operand.emit(e); b.endGeneratedInt8X64Max();
            }, GeneratedVectors.proofInt8X64);
            case "quotInt8X64#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt8X64Quot(); for (var operand : operands) operand.emit(e); b.endGeneratedInt8X64Quot();
            }, GeneratedVectors.proofInt8X64);
            case "remInt8X64#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt8X64Rem(); for (var operand : operands) operand.emit(e); b.endGeneratedInt8X64Rem();
            }, GeneratedVectors.proofInt8X64);
            case "shuffleInt8X64#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.ByteVector.SPECIES_512, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedInt8X64Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedInt8X64Shuffle();
                }, GeneratedVectors.proofInt8X64);
            }
            case "packWord8X64#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginBlock();
                var lanes = new ArrayList<BytecodeLocal>();
                for (int i = 0; i < 64; ++i) lanes.add(b.createLocal());
                operands.get(0).emitTuple(e, lanes);
                b.emitGeneratedWord8X64Pack(new BytecodeVectorLanes(accessors(lanes)));
                b.endBlock();
            }, GeneratedVectors.proofWord8X64);
            case "unpackWord8X64#" -> tupleExpression(GeneratedVectors.unpackedWord8X64, (e, destination) -> {
                e.builder.beginGeneratedWord8X64Unpack(new BytecodeVectorLanes(accessors(destination)));
                operands.get(0).emit(e);
                e.builder.endGeneratedWord8X64Unpack();
            });
            case "broadcastWord8X64#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord8X64Broadcast(); for (var operand : operands) operand.emit(e); b.endGeneratedWord8X64Broadcast();
            }, GeneratedVectors.proofWord8X64);
            case "plusWord8X64#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord8X64Plus(); for (var operand : operands) operand.emit(e); b.endGeneratedWord8X64Plus();
            }, GeneratedVectors.proofWord8X64);
            case "minusWord8X64#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord8X64Minus(); for (var operand : operands) operand.emit(e); b.endGeneratedWord8X64Minus();
            }, GeneratedVectors.proofWord8X64);
            case "timesWord8X64#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord8X64Times(); for (var operand : operands) operand.emit(e); b.endGeneratedWord8X64Times();
            }, GeneratedVectors.proofWord8X64);
            case "insertWord8X64#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord8X64Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedWord8X64Insert();
            }, GeneratedVectors.proofWord8X64);
            case "minWord8X64#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord8X64Min(); for (var operand : operands) operand.emit(e); b.endGeneratedWord8X64Min();
            }, GeneratedVectors.proofWord8X64);
            case "maxWord8X64#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord8X64Max(); for (var operand : operands) operand.emit(e); b.endGeneratedWord8X64Max();
            }, GeneratedVectors.proofWord8X64);
            case "quotWord8X64#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord8X64Quot(); for (var operand : operands) operand.emit(e); b.endGeneratedWord8X64Quot();
            }, GeneratedVectors.proofWord8X64);
            case "remWord8X64#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord8X64Rem(); for (var operand : operands) operand.emit(e); b.endGeneratedWord8X64Rem();
            }, GeneratedVectors.proofWord8X64);
            case "shuffleWord8X64#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.ByteVector.SPECIES_512, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedWord8X64Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedWord8X64Shuffle();
                }, GeneratedVectors.proofWord8X64);
            }
            case "packInt16X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginBlock();
                var lanes = new ArrayList<BytecodeLocal>();
                for (int i = 0; i < 32; ++i) lanes.add(b.createLocal());
                operands.get(0).emitTuple(e, lanes);
                b.emitGeneratedInt16X32Pack(new BytecodeVectorLanes(accessors(lanes)));
                b.endBlock();
            }, GeneratedVectors.proofInt16X32);
            case "unpackInt16X32#" -> tupleExpression(GeneratedVectors.unpackedInt16X32, (e, destination) -> {
                e.builder.beginGeneratedInt16X32Unpack(new BytecodeVectorLanes(accessors(destination)));
                operands.get(0).emit(e);
                e.builder.endGeneratedInt16X32Unpack();
            });
            case "broadcastInt16X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt16X32Broadcast(); for (var operand : operands) operand.emit(e); b.endGeneratedInt16X32Broadcast();
            }, GeneratedVectors.proofInt16X32);
            case "plusInt16X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt16X32Plus(); for (var operand : operands) operand.emit(e); b.endGeneratedInt16X32Plus();
            }, GeneratedVectors.proofInt16X32);
            case "minusInt16X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt16X32Minus(); for (var operand : operands) operand.emit(e); b.endGeneratedInt16X32Minus();
            }, GeneratedVectors.proofInt16X32);
            case "timesInt16X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt16X32Times(); for (var operand : operands) operand.emit(e); b.endGeneratedInt16X32Times();
            }, GeneratedVectors.proofInt16X32);
            case "negateInt16X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt16X32Negate(); for (var operand : operands) operand.emit(e); b.endGeneratedInt16X32Negate();
            }, GeneratedVectors.proofInt16X32);
            case "insertInt16X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt16X32Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedInt16X32Insert();
            }, GeneratedVectors.proofInt16X32);
            case "minInt16X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt16X32Min(); for (var operand : operands) operand.emit(e); b.endGeneratedInt16X32Min();
            }, GeneratedVectors.proofInt16X32);
            case "maxInt16X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt16X32Max(); for (var operand : operands) operand.emit(e); b.endGeneratedInt16X32Max();
            }, GeneratedVectors.proofInt16X32);
            case "quotInt16X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt16X32Quot(); for (var operand : operands) operand.emit(e); b.endGeneratedInt16X32Quot();
            }, GeneratedVectors.proofInt16X32);
            case "remInt16X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt16X32Rem(); for (var operand : operands) operand.emit(e); b.endGeneratedInt16X32Rem();
            }, GeneratedVectors.proofInt16X32);
            case "shuffleInt16X32#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.ShortVector.SPECIES_512, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedInt16X32Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedInt16X32Shuffle();
                }, GeneratedVectors.proofInt16X32);
            }
            case "packWord16X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginBlock();
                var lanes = new ArrayList<BytecodeLocal>();
                for (int i = 0; i < 32; ++i) lanes.add(b.createLocal());
                operands.get(0).emitTuple(e, lanes);
                b.emitGeneratedWord16X32Pack(new BytecodeVectorLanes(accessors(lanes)));
                b.endBlock();
            }, GeneratedVectors.proofWord16X32);
            case "unpackWord16X32#" -> tupleExpression(GeneratedVectors.unpackedWord16X32, (e, destination) -> {
                e.builder.beginGeneratedWord16X32Unpack(new BytecodeVectorLanes(accessors(destination)));
                operands.get(0).emit(e);
                e.builder.endGeneratedWord16X32Unpack();
            });
            case "broadcastWord16X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord16X32Broadcast(); for (var operand : operands) operand.emit(e); b.endGeneratedWord16X32Broadcast();
            }, GeneratedVectors.proofWord16X32);
            case "plusWord16X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord16X32Plus(); for (var operand : operands) operand.emit(e); b.endGeneratedWord16X32Plus();
            }, GeneratedVectors.proofWord16X32);
            case "minusWord16X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord16X32Minus(); for (var operand : operands) operand.emit(e); b.endGeneratedWord16X32Minus();
            }, GeneratedVectors.proofWord16X32);
            case "timesWord16X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord16X32Times(); for (var operand : operands) operand.emit(e); b.endGeneratedWord16X32Times();
            }, GeneratedVectors.proofWord16X32);
            case "insertWord16X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord16X32Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedWord16X32Insert();
            }, GeneratedVectors.proofWord16X32);
            case "minWord16X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord16X32Min(); for (var operand : operands) operand.emit(e); b.endGeneratedWord16X32Min();
            }, GeneratedVectors.proofWord16X32);
            case "maxWord16X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord16X32Max(); for (var operand : operands) operand.emit(e); b.endGeneratedWord16X32Max();
            }, GeneratedVectors.proofWord16X32);
            case "quotWord16X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord16X32Quot(); for (var operand : operands) operand.emit(e); b.endGeneratedWord16X32Quot();
            }, GeneratedVectors.proofWord16X32);
            case "remWord16X32#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord16X32Rem(); for (var operand : operands) operand.emit(e); b.endGeneratedWord16X32Rem();
            }, GeneratedVectors.proofWord16X32);
            case "shuffleWord16X32#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.ShortVector.SPECIES_512, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedWord16X32Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedWord16X32Shuffle();
                }, GeneratedVectors.proofWord16X32);
            }
            case "packWord64X2#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginBlock();
                var lanes = new ArrayList<BytecodeLocal>();
                for (int i = 0; i < 2; ++i) lanes.add(b.createLocal());
                operands.get(0).emitTuple(e, lanes);
                b.beginGeneratedWord64X2Pack(); for (var lane : lanes) b.emitLoadLocal(lane); b.endGeneratedWord64X2Pack();
                b.endBlock();
            }, GeneratedVectors.proofWord64X2);
            case "unpackWord64X2#" -> tupleExpression(GeneratedVectors.unpackedWord64X2, (e, destination) -> {
                e.builder.beginGeneratedWord64X2Unpack(destination.get(0), destination.get(1));
                operands.get(0).emit(e);
                e.builder.endGeneratedWord64X2Unpack();
            });
            case "broadcastWord64X2#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X2Broadcast(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X2Broadcast();
            }, GeneratedVectors.proofWord64X2);
            case "plusWord64X2#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X2Plus(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X2Plus();
            }, GeneratedVectors.proofWord64X2);
            case "minusWord64X2#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X2Minus(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X2Minus();
            }, GeneratedVectors.proofWord64X2);
            case "timesWord64X2#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X2Times(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X2Times();
            }, GeneratedVectors.proofWord64X2);
            case "insertWord64X2#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X2Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X2Insert();
            }, GeneratedVectors.proofWord64X2);
            case "minWord64X2#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X2Min(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X2Min();
            }, GeneratedVectors.proofWord64X2);
            case "maxWord64X2#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X2Max(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X2Max();
            }, GeneratedVectors.proofWord64X2);
            case "quotWord64X2#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X2Quot(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X2Quot();
            }, GeneratedVectors.proofWord64X2);
            case "remWord64X2#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X2Rem(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X2Rem();
            }, GeneratedVectors.proofWord64X2);
            case "shuffleWord64X2#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.LongVector.SPECIES_128, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedWord64X2Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedWord64X2Shuffle();
                }, GeneratedVectors.proofWord64X2);
            }
            case "packWord32X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginBlock();
                var lanes = new ArrayList<BytecodeLocal>();
                for (int i = 0; i < 8; ++i) lanes.add(b.createLocal());
                operands.get(0).emitTuple(e, lanes);
                b.beginGeneratedWord32X8Pack(); for (var lane : lanes) b.emitLoadLocal(lane); b.endGeneratedWord32X8Pack();
                b.endBlock();
            }, GeneratedVectors.proofWord32X8);
            case "unpackWord32X8#" -> tupleExpression(GeneratedVectors.unpackedWord32X8, (e, destination) -> {
                e.builder.beginGeneratedWord32X8Unpack(destination.get(0), destination.get(1), destination.get(2), destination.get(3), destination.get(4), destination.get(5), destination.get(6), destination.get(7));
                operands.get(0).emit(e);
                e.builder.endGeneratedWord32X8Unpack();
            });
            case "broadcastWord32X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord32X8Broadcast(); for (var operand : operands) operand.emit(e); b.endGeneratedWord32X8Broadcast();
            }, GeneratedVectors.proofWord32X8);
            case "plusWord32X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord32X8Plus(); for (var operand : operands) operand.emit(e); b.endGeneratedWord32X8Plus();
            }, GeneratedVectors.proofWord32X8);
            case "minusWord32X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord32X8Minus(); for (var operand : operands) operand.emit(e); b.endGeneratedWord32X8Minus();
            }, GeneratedVectors.proofWord32X8);
            case "timesWord32X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord32X8Times(); for (var operand : operands) operand.emit(e); b.endGeneratedWord32X8Times();
            }, GeneratedVectors.proofWord32X8);
            case "insertWord32X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord32X8Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedWord32X8Insert();
            }, GeneratedVectors.proofWord32X8);
            case "minWord32X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord32X8Min(); for (var operand : operands) operand.emit(e); b.endGeneratedWord32X8Min();
            }, GeneratedVectors.proofWord32X8);
            case "maxWord32X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord32X8Max(); for (var operand : operands) operand.emit(e); b.endGeneratedWord32X8Max();
            }, GeneratedVectors.proofWord32X8);
            case "quotWord32X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord32X8Quot(); for (var operand : operands) operand.emit(e); b.endGeneratedWord32X8Quot();
            }, GeneratedVectors.proofWord32X8);
            case "remWord32X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord32X8Rem(); for (var operand : operands) operand.emit(e); b.endGeneratedWord32X8Rem();
            }, GeneratedVectors.proofWord32X8);
            case "shuffleWord32X8#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.IntVector.SPECIES_256, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedWord32X8Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedWord32X8Shuffle();
                }, GeneratedVectors.proofWord32X8);
            }
            case "packInt32X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginBlock();
                var lanes = new ArrayList<BytecodeLocal>();
                for (int i = 0; i < 8; ++i) lanes.add(b.createLocal());
                operands.get(0).emitTuple(e, lanes);
                b.beginGeneratedInt32X8Pack(); for (var lane : lanes) b.emitLoadLocal(lane); b.endGeneratedInt32X8Pack();
                b.endBlock();
            }, GeneratedVectors.proofInt32X8);
            case "unpackInt32X8#" -> tupleExpression(GeneratedVectors.unpackedInt32X8, (e, destination) -> {
                e.builder.beginGeneratedInt32X8Unpack(destination.get(0), destination.get(1), destination.get(2), destination.get(3), destination.get(4), destination.get(5), destination.get(6), destination.get(7));
                operands.get(0).emit(e);
                e.builder.endGeneratedInt32X8Unpack();
            });
            case "broadcastInt32X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt32X8Broadcast(); for (var operand : operands) operand.emit(e); b.endGeneratedInt32X8Broadcast();
            }, GeneratedVectors.proofInt32X8);
            case "plusInt32X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt32X8Plus(); for (var operand : operands) operand.emit(e); b.endGeneratedInt32X8Plus();
            }, GeneratedVectors.proofInt32X8);
            case "minusInt32X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt32X8Minus(); for (var operand : operands) operand.emit(e); b.endGeneratedInt32X8Minus();
            }, GeneratedVectors.proofInt32X8);
            case "timesInt32X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt32X8Times(); for (var operand : operands) operand.emit(e); b.endGeneratedInt32X8Times();
            }, GeneratedVectors.proofInt32X8);
            case "negateInt32X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt32X8Negate(); for (var operand : operands) operand.emit(e); b.endGeneratedInt32X8Negate();
            }, GeneratedVectors.proofInt32X8);
            case "insertInt32X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt32X8Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedInt32X8Insert();
            }, GeneratedVectors.proofInt32X8);
            case "minInt32X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt32X8Min(); for (var operand : operands) operand.emit(e); b.endGeneratedInt32X8Min();
            }, GeneratedVectors.proofInt32X8);
            case "maxInt32X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt32X8Max(); for (var operand : operands) operand.emit(e); b.endGeneratedInt32X8Max();
            }, GeneratedVectors.proofInt32X8);
            case "quotInt32X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt32X8Quot(); for (var operand : operands) operand.emit(e); b.endGeneratedInt32X8Quot();
            }, GeneratedVectors.proofInt32X8);
            case "remInt32X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt32X8Rem(); for (var operand : operands) operand.emit(e); b.endGeneratedInt32X8Rem();
            }, GeneratedVectors.proofInt32X8);
            case "shuffleInt32X8#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.IntVector.SPECIES_256, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedInt32X8Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedInt32X8Shuffle();
                }, GeneratedVectors.proofInt32X8);
            }
            case "packInt32X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginBlock();
                var lanes = new ArrayList<BytecodeLocal>();
                for (int i = 0; i < 16; ++i) lanes.add(b.createLocal());
                operands.get(0).emitTuple(e, lanes);
                b.emitGeneratedInt32X16Pack(new BytecodeVectorLanes(accessors(lanes)));
                b.endBlock();
            }, GeneratedVectors.proofInt32X16);
            case "unpackInt32X16#" -> tupleExpression(GeneratedVectors.unpackedInt32X16, (e, destination) -> {
                e.builder.beginGeneratedInt32X16Unpack(new BytecodeVectorLanes(accessors(destination)));
                operands.get(0).emit(e);
                e.builder.endGeneratedInt32X16Unpack();
            });
            case "broadcastInt32X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt32X16Broadcast(); for (var operand : operands) operand.emit(e); b.endGeneratedInt32X16Broadcast();
            }, GeneratedVectors.proofInt32X16);
            case "plusInt32X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt32X16Plus(); for (var operand : operands) operand.emit(e); b.endGeneratedInt32X16Plus();
            }, GeneratedVectors.proofInt32X16);
            case "minusInt32X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt32X16Minus(); for (var operand : operands) operand.emit(e); b.endGeneratedInt32X16Minus();
            }, GeneratedVectors.proofInt32X16);
            case "timesInt32X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt32X16Times(); for (var operand : operands) operand.emit(e); b.endGeneratedInt32X16Times();
            }, GeneratedVectors.proofInt32X16);
            case "negateInt32X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt32X16Negate(); for (var operand : operands) operand.emit(e); b.endGeneratedInt32X16Negate();
            }, GeneratedVectors.proofInt32X16);
            case "insertInt32X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt32X16Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedInt32X16Insert();
            }, GeneratedVectors.proofInt32X16);
            case "minInt32X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt32X16Min(); for (var operand : operands) operand.emit(e); b.endGeneratedInt32X16Min();
            }, GeneratedVectors.proofInt32X16);
            case "maxInt32X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt32X16Max(); for (var operand : operands) operand.emit(e); b.endGeneratedInt32X16Max();
            }, GeneratedVectors.proofInt32X16);
            case "quotInt32X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt32X16Quot(); for (var operand : operands) operand.emit(e); b.endGeneratedInt32X16Quot();
            }, GeneratedVectors.proofInt32X16);
            case "remInt32X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt32X16Rem(); for (var operand : operands) operand.emit(e); b.endGeneratedInt32X16Rem();
            }, GeneratedVectors.proofInt32X16);
            case "shuffleInt32X16#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.IntVector.SPECIES_512, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedInt32X16Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedInt32X16Shuffle();
                }, GeneratedVectors.proofInt32X16);
            }
            case "timesInt64X2#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt64X2Times(); for (var operand : operands) operand.emit(e); b.endGeneratedInt64X2Times();
            }, GeneratedVectors.proofInt64X2);
            case "insertInt64X2#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt64X2Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedInt64X2Insert();
            }, GeneratedVectors.proofInt64X2);
            case "minInt64X2#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt64X2Min(); for (var operand : operands) operand.emit(e); b.endGeneratedInt64X2Min();
            }, GeneratedVectors.proofInt64X2);
            case "maxInt64X2#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt64X2Max(); for (var operand : operands) operand.emit(e); b.endGeneratedInt64X2Max();
            }, GeneratedVectors.proofInt64X2);
            case "quotInt64X2#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt64X2Quot(); for (var operand : operands) operand.emit(e); b.endGeneratedInt64X2Quot();
            }, GeneratedVectors.proofInt64X2);
            case "remInt64X2#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt64X2Rem(); for (var operand : operands) operand.emit(e); b.endGeneratedInt64X2Rem();
            }, GeneratedVectors.proofInt64X2);
            case "shuffleInt64X2#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.LongVector.SPECIES_128, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedInt64X2Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedInt64X2Shuffle();
                }, GeneratedVectors.proofInt64X2);
            }
            case "negateFloatX4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedFloatX4Negate(); for (var operand : operands) operand.emit(e); b.endGeneratedFloatX4Negate();
            }, GeneratedVectors.proofFloatX4);
            case "divideFloatX4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedFloatX4Divide(); for (var operand : operands) operand.emit(e); b.endGeneratedFloatX4Divide();
            }, GeneratedVectors.proofFloatX4);
            case "insertFloatX4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedFloatX4Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedFloatX4Insert();
            }, GeneratedVectors.proofFloatX4);
            case "minFloatX4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedFloatX4Min(); for (var operand : operands) operand.emit(e); b.endGeneratedFloatX4Min();
            }, GeneratedVectors.proofFloatX4);
            case "maxFloatX4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedFloatX4Max(); for (var operand : operands) operand.emit(e); b.endGeneratedFloatX4Max();
            }, GeneratedVectors.proofFloatX4);
            case "shuffleFloatX4#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.FloatVector.SPECIES_128, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedFloatX4Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedFloatX4Shuffle();
                }, GeneratedVectors.proofFloatX4);
            }
            case "negateDoubleX2#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedDoubleX2Negate(); for (var operand : operands) operand.emit(e); b.endGeneratedDoubleX2Negate();
            }, GeneratedVectors.proofDoubleX2);
            case "divideDoubleX2#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedDoubleX2Divide(); for (var operand : operands) operand.emit(e); b.endGeneratedDoubleX2Divide();
            }, GeneratedVectors.proofDoubleX2);
            case "insertDoubleX2#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedDoubleX2Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedDoubleX2Insert();
            }, GeneratedVectors.proofDoubleX2);
            case "minDoubleX2#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedDoubleX2Min(); for (var operand : operands) operand.emit(e); b.endGeneratedDoubleX2Min();
            }, GeneratedVectors.proofDoubleX2);
            case "maxDoubleX2#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedDoubleX2Max(); for (var operand : operands) operand.emit(e); b.endGeneratedDoubleX2Max();
            }, GeneratedVectors.proofDoubleX2);
            case "shuffleDoubleX2#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.DoubleVector.SPECIES_128, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedDoubleX2Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedDoubleX2Shuffle();
                }, GeneratedVectors.proofDoubleX2);
            }
            case "packFloatX8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginBlock();
                var lanes = new ArrayList<BytecodeLocal>();
                for (int i = 0; i < 8; ++i) lanes.add(b.createLocal());
                operands.get(0).emitTuple(e, lanes);
                b.beginGeneratedFloatX8Pack(); for (var lane : lanes) b.emitLoadLocal(lane); b.endGeneratedFloatX8Pack();
                b.endBlock();
            }, GeneratedVectors.proofFloatX8);
            case "unpackFloatX8#" -> tupleExpression(GeneratedVectors.unpackedFloatX8, (e, destination) -> {
                e.builder.beginGeneratedFloatX8Unpack(destination.get(0), destination.get(1), destination.get(2), destination.get(3), destination.get(4), destination.get(5), destination.get(6), destination.get(7));
                operands.get(0).emit(e);
                e.builder.endGeneratedFloatX8Unpack();
            });
            case "broadcastFloatX8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedFloatX8Broadcast(); for (var operand : operands) operand.emit(e); b.endGeneratedFloatX8Broadcast();
            }, GeneratedVectors.proofFloatX8);
            case "plusFloatX8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedFloatX8Plus(); for (var operand : operands) operand.emit(e); b.endGeneratedFloatX8Plus();
            }, GeneratedVectors.proofFloatX8);
            case "minusFloatX8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedFloatX8Minus(); for (var operand : operands) operand.emit(e); b.endGeneratedFloatX8Minus();
            }, GeneratedVectors.proofFloatX8);
            case "timesFloatX8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedFloatX8Times(); for (var operand : operands) operand.emit(e); b.endGeneratedFloatX8Times();
            }, GeneratedVectors.proofFloatX8);
            case "negateFloatX8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedFloatX8Negate(); for (var operand : operands) operand.emit(e); b.endGeneratedFloatX8Negate();
            }, GeneratedVectors.proofFloatX8);
            case "divideFloatX8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedFloatX8Divide(); for (var operand : operands) operand.emit(e); b.endGeneratedFloatX8Divide();
            }, GeneratedVectors.proofFloatX8);
            case "insertFloatX8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedFloatX8Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedFloatX8Insert();
            }, GeneratedVectors.proofFloatX8);
            case "minFloatX8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedFloatX8Min(); for (var operand : operands) operand.emit(e); b.endGeneratedFloatX8Min();
            }, GeneratedVectors.proofFloatX8);
            case "maxFloatX8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedFloatX8Max(); for (var operand : operands) operand.emit(e); b.endGeneratedFloatX8Max();
            }, GeneratedVectors.proofFloatX8);
            case "shuffleFloatX8#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.FloatVector.SPECIES_256, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedFloatX8Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedFloatX8Shuffle();
                }, GeneratedVectors.proofFloatX8);
            }
            case "packDoubleX4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginBlock();
                var lanes = new ArrayList<BytecodeLocal>();
                for (int i = 0; i < 4; ++i) lanes.add(b.createLocal());
                operands.get(0).emitTuple(e, lanes);
                b.beginGeneratedDoubleX4Pack(); for (var lane : lanes) b.emitLoadLocal(lane); b.endGeneratedDoubleX4Pack();
                b.endBlock();
            }, GeneratedVectors.proofDoubleX4);
            case "unpackDoubleX4#" -> tupleExpression(GeneratedVectors.unpackedDoubleX4, (e, destination) -> {
                e.builder.beginGeneratedDoubleX4Unpack(destination.get(0), destination.get(1), destination.get(2), destination.get(3));
                operands.get(0).emit(e);
                e.builder.endGeneratedDoubleX4Unpack();
            });
            case "broadcastDoubleX4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedDoubleX4Broadcast(); for (var operand : operands) operand.emit(e); b.endGeneratedDoubleX4Broadcast();
            }, GeneratedVectors.proofDoubleX4);
            case "plusDoubleX4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedDoubleX4Plus(); for (var operand : operands) operand.emit(e); b.endGeneratedDoubleX4Plus();
            }, GeneratedVectors.proofDoubleX4);
            case "minusDoubleX4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedDoubleX4Minus(); for (var operand : operands) operand.emit(e); b.endGeneratedDoubleX4Minus();
            }, GeneratedVectors.proofDoubleX4);
            case "timesDoubleX4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedDoubleX4Times(); for (var operand : operands) operand.emit(e); b.endGeneratedDoubleX4Times();
            }, GeneratedVectors.proofDoubleX4);
            case "negateDoubleX4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedDoubleX4Negate(); for (var operand : operands) operand.emit(e); b.endGeneratedDoubleX4Negate();
            }, GeneratedVectors.proofDoubleX4);
            case "divideDoubleX4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedDoubleX4Divide(); for (var operand : operands) operand.emit(e); b.endGeneratedDoubleX4Divide();
            }, GeneratedVectors.proofDoubleX4);
            case "insertDoubleX4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedDoubleX4Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedDoubleX4Insert();
            }, GeneratedVectors.proofDoubleX4);
            case "minDoubleX4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedDoubleX4Min(); for (var operand : operands) operand.emit(e); b.endGeneratedDoubleX4Min();
            }, GeneratedVectors.proofDoubleX4);
            case "maxDoubleX4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedDoubleX4Max(); for (var operand : operands) operand.emit(e); b.endGeneratedDoubleX4Max();
            }, GeneratedVectors.proofDoubleX4);
            case "shuffleDoubleX4#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.DoubleVector.SPECIES_256, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedDoubleX4Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedDoubleX4Shuffle();
                }, GeneratedVectors.proofDoubleX4);
            }
            case "packInt64X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginBlock();
                var lanes = new ArrayList<BytecodeLocal>();
                for (int i = 0; i < 4; ++i) lanes.add(b.createLocal());
                operands.get(0).emitTuple(e, lanes);
                b.beginGeneratedInt64X4Pack(); for (var lane : lanes) b.emitLoadLocal(lane); b.endGeneratedInt64X4Pack();
                b.endBlock();
            }, GeneratedVectors.proofInt64X4);
            case "unpackInt64X4#" -> tupleExpression(GeneratedVectors.unpackedInt64X4, (e, destination) -> {
                e.builder.beginGeneratedInt64X4Unpack(destination.get(0), destination.get(1), destination.get(2), destination.get(3));
                operands.get(0).emit(e);
                e.builder.endGeneratedInt64X4Unpack();
            });
            case "broadcastInt64X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt64X4Broadcast(); for (var operand : operands) operand.emit(e); b.endGeneratedInt64X4Broadcast();
            }, GeneratedVectors.proofInt64X4);
            case "plusInt64X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt64X4Plus(); for (var operand : operands) operand.emit(e); b.endGeneratedInt64X4Plus();
            }, GeneratedVectors.proofInt64X4);
            case "minusInt64X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt64X4Minus(); for (var operand : operands) operand.emit(e); b.endGeneratedInt64X4Minus();
            }, GeneratedVectors.proofInt64X4);
            case "timesInt64X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt64X4Times(); for (var operand : operands) operand.emit(e); b.endGeneratedInt64X4Times();
            }, GeneratedVectors.proofInt64X4);
            case "negateInt64X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt64X4Negate(); for (var operand : operands) operand.emit(e); b.endGeneratedInt64X4Negate();
            }, GeneratedVectors.proofInt64X4);
            case "insertInt64X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt64X4Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedInt64X4Insert();
            }, GeneratedVectors.proofInt64X4);
            case "minInt64X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt64X4Min(); for (var operand : operands) operand.emit(e); b.endGeneratedInt64X4Min();
            }, GeneratedVectors.proofInt64X4);
            case "maxInt64X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt64X4Max(); for (var operand : operands) operand.emit(e); b.endGeneratedInt64X4Max();
            }, GeneratedVectors.proofInt64X4);
            case "quotInt64X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt64X4Quot(); for (var operand : operands) operand.emit(e); b.endGeneratedInt64X4Quot();
            }, GeneratedVectors.proofInt64X4);
            case "remInt64X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt64X4Rem(); for (var operand : operands) operand.emit(e); b.endGeneratedInt64X4Rem();
            }, GeneratedVectors.proofInt64X4);
            case "shuffleInt64X4#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.LongVector.SPECIES_256, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedInt64X4Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedInt64X4Shuffle();
                }, GeneratedVectors.proofInt64X4);
            }
            case "packInt64X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginBlock();
                var lanes = new ArrayList<BytecodeLocal>();
                for (int i = 0; i < 8; ++i) lanes.add(b.createLocal());
                operands.get(0).emitTuple(e, lanes);
                b.beginGeneratedInt64X8Pack(); for (var lane : lanes) b.emitLoadLocal(lane); b.endGeneratedInt64X8Pack();
                b.endBlock();
            }, GeneratedVectors.proofInt64X8);
            case "unpackInt64X8#" -> tupleExpression(GeneratedVectors.unpackedInt64X8, (e, destination) -> {
                e.builder.beginGeneratedInt64X8Unpack(destination.get(0), destination.get(1), destination.get(2), destination.get(3), destination.get(4), destination.get(5), destination.get(6), destination.get(7));
                operands.get(0).emit(e);
                e.builder.endGeneratedInt64X8Unpack();
            });
            case "broadcastInt64X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt64X8Broadcast(); for (var operand : operands) operand.emit(e); b.endGeneratedInt64X8Broadcast();
            }, GeneratedVectors.proofInt64X8);
            case "plusInt64X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt64X8Plus(); for (var operand : operands) operand.emit(e); b.endGeneratedInt64X8Plus();
            }, GeneratedVectors.proofInt64X8);
            case "minusInt64X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt64X8Minus(); for (var operand : operands) operand.emit(e); b.endGeneratedInt64X8Minus();
            }, GeneratedVectors.proofInt64X8);
            case "timesInt64X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt64X8Times(); for (var operand : operands) operand.emit(e); b.endGeneratedInt64X8Times();
            }, GeneratedVectors.proofInt64X8);
            case "negateInt64X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt64X8Negate(); for (var operand : operands) operand.emit(e); b.endGeneratedInt64X8Negate();
            }, GeneratedVectors.proofInt64X8);
            case "insertInt64X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt64X8Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedInt64X8Insert();
            }, GeneratedVectors.proofInt64X8);
            case "minInt64X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt64X8Min(); for (var operand : operands) operand.emit(e); b.endGeneratedInt64X8Min();
            }, GeneratedVectors.proofInt64X8);
            case "maxInt64X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt64X8Max(); for (var operand : operands) operand.emit(e); b.endGeneratedInt64X8Max();
            }, GeneratedVectors.proofInt64X8);
            case "quotInt64X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt64X8Quot(); for (var operand : operands) operand.emit(e); b.endGeneratedInt64X8Quot();
            }, GeneratedVectors.proofInt64X8);
            case "remInt64X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt64X8Rem(); for (var operand : operands) operand.emit(e); b.endGeneratedInt64X8Rem();
            }, GeneratedVectors.proofInt64X8);
            case "shuffleInt64X8#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.LongVector.SPECIES_512, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedInt64X8Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedInt64X8Shuffle();
                }, GeneratedVectors.proofInt64X8);
            }
            case "packWord64X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginBlock();
                var lanes = new ArrayList<BytecodeLocal>();
                for (int i = 0; i < 4; ++i) lanes.add(b.createLocal());
                operands.get(0).emitTuple(e, lanes);
                b.beginGeneratedWord64X4Pack(); for (var lane : lanes) b.emitLoadLocal(lane); b.endGeneratedWord64X4Pack();
                b.endBlock();
            }, GeneratedVectors.proofWord64X4);
            case "unpackWord64X4#" -> tupleExpression(GeneratedVectors.unpackedWord64X4, (e, destination) -> {
                e.builder.beginGeneratedWord64X4Unpack(destination.get(0), destination.get(1), destination.get(2), destination.get(3));
                operands.get(0).emit(e);
                e.builder.endGeneratedWord64X4Unpack();
            });
            case "broadcastWord64X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X4Broadcast(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X4Broadcast();
            }, GeneratedVectors.proofWord64X4);
            case "plusWord64X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X4Plus(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X4Plus();
            }, GeneratedVectors.proofWord64X4);
            case "minusWord64X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X4Minus(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X4Minus();
            }, GeneratedVectors.proofWord64X4);
            case "timesWord64X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X4Times(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X4Times();
            }, GeneratedVectors.proofWord64X4);
            case "insertWord64X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X4Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X4Insert();
            }, GeneratedVectors.proofWord64X4);
            case "minWord64X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X4Min(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X4Min();
            }, GeneratedVectors.proofWord64X4);
            case "maxWord64X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X4Max(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X4Max();
            }, GeneratedVectors.proofWord64X4);
            case "quotWord64X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X4Quot(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X4Quot();
            }, GeneratedVectors.proofWord64X4);
            case "remWord64X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X4Rem(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X4Rem();
            }, GeneratedVectors.proofWord64X4);
            case "shuffleWord64X4#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.LongVector.SPECIES_256, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedWord64X4Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedWord64X4Shuffle();
                }, GeneratedVectors.proofWord64X4);
            }
            case "packWord64X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginBlock();
                var lanes = new ArrayList<BytecodeLocal>();
                for (int i = 0; i < 8; ++i) lanes.add(b.createLocal());
                operands.get(0).emitTuple(e, lanes);
                b.beginGeneratedWord64X8Pack(); for (var lane : lanes) b.emitLoadLocal(lane); b.endGeneratedWord64X8Pack();
                b.endBlock();
            }, GeneratedVectors.proofWord64X8);
            case "unpackWord64X8#" -> tupleExpression(GeneratedVectors.unpackedWord64X8, (e, destination) -> {
                e.builder.beginGeneratedWord64X8Unpack(destination.get(0), destination.get(1), destination.get(2), destination.get(3), destination.get(4), destination.get(5), destination.get(6), destination.get(7));
                operands.get(0).emit(e);
                e.builder.endGeneratedWord64X8Unpack();
            });
            case "broadcastWord64X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X8Broadcast(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X8Broadcast();
            }, GeneratedVectors.proofWord64X8);
            case "plusWord64X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X8Plus(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X8Plus();
            }, GeneratedVectors.proofWord64X8);
            case "minusWord64X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X8Minus(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X8Minus();
            }, GeneratedVectors.proofWord64X8);
            case "timesWord64X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X8Times(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X8Times();
            }, GeneratedVectors.proofWord64X8);
            case "insertWord64X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X8Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X8Insert();
            }, GeneratedVectors.proofWord64X8);
            case "minWord64X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X8Min(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X8Min();
            }, GeneratedVectors.proofWord64X8);
            case "maxWord64X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X8Max(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X8Max();
            }, GeneratedVectors.proofWord64X8);
            case "quotWord64X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X8Quot(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X8Quot();
            }, GeneratedVectors.proofWord64X8);
            case "remWord64X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord64X8Rem(); for (var operand : operands) operand.emit(e); b.endGeneratedWord64X8Rem();
            }, GeneratedVectors.proofWord64X8);
            case "shuffleWord64X8#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.LongVector.SPECIES_512, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedWord64X8Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedWord64X8Shuffle();
                }, GeneratedVectors.proofWord64X8);
            }
            case "packWord32X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginBlock();
                var lanes = new ArrayList<BytecodeLocal>();
                for (int i = 0; i < 16; ++i) lanes.add(b.createLocal());
                operands.get(0).emitTuple(e, lanes);
                b.emitGeneratedWord32X16Pack(new BytecodeVectorLanes(accessors(lanes)));
                b.endBlock();
            }, GeneratedVectors.proofWord32X16);
            case "unpackWord32X16#" -> tupleExpression(GeneratedVectors.unpackedWord32X16, (e, destination) -> {
                e.builder.beginGeneratedWord32X16Unpack(new BytecodeVectorLanes(accessors(destination)));
                operands.get(0).emit(e);
                e.builder.endGeneratedWord32X16Unpack();
            });
            case "broadcastWord32X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord32X16Broadcast(); for (var operand : operands) operand.emit(e); b.endGeneratedWord32X16Broadcast();
            }, GeneratedVectors.proofWord32X16);
            case "plusWord32X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord32X16Plus(); for (var operand : operands) operand.emit(e); b.endGeneratedWord32X16Plus();
            }, GeneratedVectors.proofWord32X16);
            case "minusWord32X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord32X16Minus(); for (var operand : operands) operand.emit(e); b.endGeneratedWord32X16Minus();
            }, GeneratedVectors.proofWord32X16);
            case "timesWord32X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord32X16Times(); for (var operand : operands) operand.emit(e); b.endGeneratedWord32X16Times();
            }, GeneratedVectors.proofWord32X16);
            case "insertWord32X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord32X16Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedWord32X16Insert();
            }, GeneratedVectors.proofWord32X16);
            case "minWord32X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord32X16Min(); for (var operand : operands) operand.emit(e); b.endGeneratedWord32X16Min();
            }, GeneratedVectors.proofWord32X16);
            case "maxWord32X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord32X16Max(); for (var operand : operands) operand.emit(e); b.endGeneratedWord32X16Max();
            }, GeneratedVectors.proofWord32X16);
            case "quotWord32X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord32X16Quot(); for (var operand : operands) operand.emit(e); b.endGeneratedWord32X16Quot();
            }, GeneratedVectors.proofWord32X16);
            case "remWord32X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord32X16Rem(); for (var operand : operands) operand.emit(e); b.endGeneratedWord32X16Rem();
            }, GeneratedVectors.proofWord32X16);
            case "shuffleWord32X16#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.IntVector.SPECIES_512, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedWord32X16Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedWord32X16Shuffle();
                }, GeneratedVectors.proofWord32X16);
            }
            case "packFloatX16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginBlock();
                var lanes = new ArrayList<BytecodeLocal>();
                for (int i = 0; i < 16; ++i) lanes.add(b.createLocal());
                operands.get(0).emitTuple(e, lanes);
                b.emitGeneratedFloatX16Pack(new BytecodeVectorLanes(accessors(lanes)));
                b.endBlock();
            }, GeneratedVectors.proofFloatX16);
            case "unpackFloatX16#" -> tupleExpression(GeneratedVectors.unpackedFloatX16, (e, destination) -> {
                e.builder.beginGeneratedFloatX16Unpack(new BytecodeVectorLanes(accessors(destination)));
                operands.get(0).emit(e);
                e.builder.endGeneratedFloatX16Unpack();
            });
            case "broadcastFloatX16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedFloatX16Broadcast(); for (var operand : operands) operand.emit(e); b.endGeneratedFloatX16Broadcast();
            }, GeneratedVectors.proofFloatX16);
            case "plusFloatX16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedFloatX16Plus(); for (var operand : operands) operand.emit(e); b.endGeneratedFloatX16Plus();
            }, GeneratedVectors.proofFloatX16);
            case "minusFloatX16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedFloatX16Minus(); for (var operand : operands) operand.emit(e); b.endGeneratedFloatX16Minus();
            }, GeneratedVectors.proofFloatX16);
            case "timesFloatX16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedFloatX16Times(); for (var operand : operands) operand.emit(e); b.endGeneratedFloatX16Times();
            }, GeneratedVectors.proofFloatX16);
            case "negateFloatX16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedFloatX16Negate(); for (var operand : operands) operand.emit(e); b.endGeneratedFloatX16Negate();
            }, GeneratedVectors.proofFloatX16);
            case "divideFloatX16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedFloatX16Divide(); for (var operand : operands) operand.emit(e); b.endGeneratedFloatX16Divide();
            }, GeneratedVectors.proofFloatX16);
            case "insertFloatX16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedFloatX16Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedFloatX16Insert();
            }, GeneratedVectors.proofFloatX16);
            case "minFloatX16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedFloatX16Min(); for (var operand : operands) operand.emit(e); b.endGeneratedFloatX16Min();
            }, GeneratedVectors.proofFloatX16);
            case "maxFloatX16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedFloatX16Max(); for (var operand : operands) operand.emit(e); b.endGeneratedFloatX16Max();
            }, GeneratedVectors.proofFloatX16);
            case "shuffleFloatX16#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.FloatVector.SPECIES_512, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedFloatX16Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedFloatX16Shuffle();
                }, GeneratedVectors.proofFloatX16);
            }
            case "packDoubleX8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginBlock();
                var lanes = new ArrayList<BytecodeLocal>();
                for (int i = 0; i < 8; ++i) lanes.add(b.createLocal());
                operands.get(0).emitTuple(e, lanes);
                b.beginGeneratedDoubleX8Pack(); for (var lane : lanes) b.emitLoadLocal(lane); b.endGeneratedDoubleX8Pack();
                b.endBlock();
            }, GeneratedVectors.proofDoubleX8);
            case "unpackDoubleX8#" -> tupleExpression(GeneratedVectors.unpackedDoubleX8, (e, destination) -> {
                e.builder.beginGeneratedDoubleX8Unpack(destination.get(0), destination.get(1), destination.get(2), destination.get(3), destination.get(4), destination.get(5), destination.get(6), destination.get(7));
                operands.get(0).emit(e);
                e.builder.endGeneratedDoubleX8Unpack();
            });
            case "broadcastDoubleX8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedDoubleX8Broadcast(); for (var operand : operands) operand.emit(e); b.endGeneratedDoubleX8Broadcast();
            }, GeneratedVectors.proofDoubleX8);
            case "plusDoubleX8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedDoubleX8Plus(); for (var operand : operands) operand.emit(e); b.endGeneratedDoubleX8Plus();
            }, GeneratedVectors.proofDoubleX8);
            case "minusDoubleX8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedDoubleX8Minus(); for (var operand : operands) operand.emit(e); b.endGeneratedDoubleX8Minus();
            }, GeneratedVectors.proofDoubleX8);
            case "timesDoubleX8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedDoubleX8Times(); for (var operand : operands) operand.emit(e); b.endGeneratedDoubleX8Times();
            }, GeneratedVectors.proofDoubleX8);
            case "negateDoubleX8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedDoubleX8Negate(); for (var operand : operands) operand.emit(e); b.endGeneratedDoubleX8Negate();
            }, GeneratedVectors.proofDoubleX8);
            case "divideDoubleX8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedDoubleX8Divide(); for (var operand : operands) operand.emit(e); b.endGeneratedDoubleX8Divide();
            }, GeneratedVectors.proofDoubleX8);
            case "insertDoubleX8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedDoubleX8Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedDoubleX8Insert();
            }, GeneratedVectors.proofDoubleX8);
            case "minDoubleX8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedDoubleX8Min(); for (var operand : operands) operand.emit(e); b.endGeneratedDoubleX8Min();
            }, GeneratedVectors.proofDoubleX8);
            case "maxDoubleX8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedDoubleX8Max(); for (var operand : operands) operand.emit(e); b.endGeneratedDoubleX8Max();
            }, GeneratedVectors.proofDoubleX8);
            case "shuffleDoubleX8#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.DoubleVector.SPECIES_512, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedDoubleX8Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedDoubleX8Shuffle();
                }, GeneratedVectors.proofDoubleX8);
            }
            case "insertInt8X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt8X16Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedInt8X16Insert();
            }, GeneratedVectors.proofInt8X16);
            case "minInt8X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt8X16Min(); for (var operand : operands) operand.emit(e); b.endGeneratedInt8X16Min();
            }, GeneratedVectors.proofInt8X16);
            case "maxInt8X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt8X16Max(); for (var operand : operands) operand.emit(e); b.endGeneratedInt8X16Max();
            }, GeneratedVectors.proofInt8X16);
            case "quotInt8X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt8X16Quot(); for (var operand : operands) operand.emit(e); b.endGeneratedInt8X16Quot();
            }, GeneratedVectors.proofInt8X16);
            case "remInt8X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt8X16Rem(); for (var operand : operands) operand.emit(e); b.endGeneratedInt8X16Rem();
            }, GeneratedVectors.proofInt8X16);
            case "shuffleInt8X16#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.ByteVector.SPECIES_128, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedInt8X16Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedInt8X16Shuffle();
                }, GeneratedVectors.proofInt8X16);
            }
            case "insertInt16X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt16X8Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedInt16X8Insert();
            }, GeneratedVectors.proofInt16X8);
            case "minInt16X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt16X8Min(); for (var operand : operands) operand.emit(e); b.endGeneratedInt16X8Min();
            }, GeneratedVectors.proofInt16X8);
            case "maxInt16X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt16X8Max(); for (var operand : operands) operand.emit(e); b.endGeneratedInt16X8Max();
            }, GeneratedVectors.proofInt16X8);
            case "quotInt16X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt16X8Quot(); for (var operand : operands) operand.emit(e); b.endGeneratedInt16X8Quot();
            }, GeneratedVectors.proofInt16X8);
            case "remInt16X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt16X8Rem(); for (var operand : operands) operand.emit(e); b.endGeneratedInt16X8Rem();
            }, GeneratedVectors.proofInt16X8);
            case "shuffleInt16X8#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.ShortVector.SPECIES_128, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedInt16X8Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedInt16X8Shuffle();
                }, GeneratedVectors.proofInt16X8);
            }
            case "insertInt32X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt32X4Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedInt32X4Insert();
            }, GeneratedVectors.proofInt32X4);
            case "minInt32X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt32X4Min(); for (var operand : operands) operand.emit(e); b.endGeneratedInt32X4Min();
            }, GeneratedVectors.proofInt32X4);
            case "maxInt32X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt32X4Max(); for (var operand : operands) operand.emit(e); b.endGeneratedInt32X4Max();
            }, GeneratedVectors.proofInt32X4);
            case "quotInt32X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt32X4Quot(); for (var operand : operands) operand.emit(e); b.endGeneratedInt32X4Quot();
            }, GeneratedVectors.proofInt32X4);
            case "remInt32X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt32X4Rem(); for (var operand : operands) operand.emit(e); b.endGeneratedInt32X4Rem();
            }, GeneratedVectors.proofInt32X4);
            case "shuffleInt32X4#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.IntVector.SPECIES_128, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedInt32X4Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedInt32X4Shuffle();
                }, GeneratedVectors.proofInt32X4);
            }
            case "insertWord8X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord8X16Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedWord8X16Insert();
            }, GeneratedVectors.proofWord8X16);
            case "minWord8X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord8X16Min(); for (var operand : operands) operand.emit(e); b.endGeneratedWord8X16Min();
            }, GeneratedVectors.proofWord8X16);
            case "maxWord8X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord8X16Max(); for (var operand : operands) operand.emit(e); b.endGeneratedWord8X16Max();
            }, GeneratedVectors.proofWord8X16);
            case "quotWord8X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord8X16Quot(); for (var operand : operands) operand.emit(e); b.endGeneratedWord8X16Quot();
            }, GeneratedVectors.proofWord8X16);
            case "remWord8X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord8X16Rem(); for (var operand : operands) operand.emit(e); b.endGeneratedWord8X16Rem();
            }, GeneratedVectors.proofWord8X16);
            case "shuffleWord8X16#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.ByteVector.SPECIES_128, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedWord8X16Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedWord8X16Shuffle();
                }, GeneratedVectors.proofWord8X16);
            }
            case "insertWord16X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord16X8Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedWord16X8Insert();
            }, GeneratedVectors.proofWord16X8);
            case "minWord16X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord16X8Min(); for (var operand : operands) operand.emit(e); b.endGeneratedWord16X8Min();
            }, GeneratedVectors.proofWord16X8);
            case "maxWord16X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord16X8Max(); for (var operand : operands) operand.emit(e); b.endGeneratedWord16X8Max();
            }, GeneratedVectors.proofWord16X8);
            case "quotWord16X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord16X8Quot(); for (var operand : operands) operand.emit(e); b.endGeneratedWord16X8Quot();
            }, GeneratedVectors.proofWord16X8);
            case "remWord16X8#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord16X8Rem(); for (var operand : operands) operand.emit(e); b.endGeneratedWord16X8Rem();
            }, GeneratedVectors.proofWord16X8);
            case "shuffleWord16X8#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.ShortVector.SPECIES_128, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedWord16X8Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedWord16X8Shuffle();
                }, GeneratedVectors.proofWord16X8);
            }
            case "insertWord32X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord32X4Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedWord32X4Insert();
            }, GeneratedVectors.proofWord32X4);
            case "minWord32X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord32X4Min(); for (var operand : operands) operand.emit(e); b.endGeneratedWord32X4Min();
            }, GeneratedVectors.proofWord32X4);
            case "maxWord32X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord32X4Max(); for (var operand : operands) operand.emit(e); b.endGeneratedWord32X4Max();
            }, GeneratedVectors.proofWord32X4);
            case "quotWord32X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord32X4Quot(); for (var operand : operands) operand.emit(e); b.endGeneratedWord32X4Quot();
            }, GeneratedVectors.proofWord32X4);
            case "remWord32X4#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord32X4Rem(); for (var operand : operands) operand.emit(e); b.endGeneratedWord32X4Rem();
            }, GeneratedVectors.proofWord32X4);
            case "shuffleWord32X4#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.IntVector.SPECIES_128, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedWord32X4Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedWord32X4Shuffle();
                }, GeneratedVectors.proofWord32X4);
            }
            case "packInt16X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginBlock();
                var lanes = new ArrayList<BytecodeLocal>();
                for (int i = 0; i < 16; ++i) lanes.add(b.createLocal());
                operands.get(0).emitTuple(e, lanes);
                b.emitGeneratedInt16X16Pack(new BytecodeVectorLanes(accessors(lanes)));
                b.endBlock();
            }, GeneratedVectors.proofInt16X16);
            case "unpackInt16X16#" -> tupleExpression(GeneratedVectors.unpackedInt16X16, (e, destination) -> {
                e.builder.beginGeneratedInt16X16Unpack(new BytecodeVectorLanes(accessors(destination)));
                operands.get(0).emit(e);
                e.builder.endGeneratedInt16X16Unpack();
            });
            case "broadcastInt16X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt16X16Broadcast(); for (var operand : operands) operand.emit(e); b.endGeneratedInt16X16Broadcast();
            }, GeneratedVectors.proofInt16X16);
            case "plusInt16X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt16X16Plus(); for (var operand : operands) operand.emit(e); b.endGeneratedInt16X16Plus();
            }, GeneratedVectors.proofInt16X16);
            case "minusInt16X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt16X16Minus(); for (var operand : operands) operand.emit(e); b.endGeneratedInt16X16Minus();
            }, GeneratedVectors.proofInt16X16);
            case "timesInt16X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt16X16Times(); for (var operand : operands) operand.emit(e); b.endGeneratedInt16X16Times();
            }, GeneratedVectors.proofInt16X16);
            case "negateInt16X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt16X16Negate(); for (var operand : operands) operand.emit(e); b.endGeneratedInt16X16Negate();
            }, GeneratedVectors.proofInt16X16);
            case "insertInt16X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt16X16Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedInt16X16Insert();
            }, GeneratedVectors.proofInt16X16);
            case "minInt16X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt16X16Min(); for (var operand : operands) operand.emit(e); b.endGeneratedInt16X16Min();
            }, GeneratedVectors.proofInt16X16);
            case "maxInt16X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt16X16Max(); for (var operand : operands) operand.emit(e); b.endGeneratedInt16X16Max();
            }, GeneratedVectors.proofInt16X16);
            case "quotInt16X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt16X16Quot(); for (var operand : operands) operand.emit(e); b.endGeneratedInt16X16Quot();
            }, GeneratedVectors.proofInt16X16);
            case "remInt16X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedInt16X16Rem(); for (var operand : operands) operand.emit(e); b.endGeneratedInt16X16Rem();
            }, GeneratedVectors.proofInt16X16);
            case "shuffleInt16X16#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.ShortVector.SPECIES_256, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedInt16X16Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedInt16X16Shuffle();
                }, GeneratedVectors.proofInt16X16);
            }
            case "packWord16X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginBlock();
                var lanes = new ArrayList<BytecodeLocal>();
                for (int i = 0; i < 16; ++i) lanes.add(b.createLocal());
                operands.get(0).emitTuple(e, lanes);
                b.emitGeneratedWord16X16Pack(new BytecodeVectorLanes(accessors(lanes)));
                b.endBlock();
            }, GeneratedVectors.proofWord16X16);
            case "unpackWord16X16#" -> tupleExpression(GeneratedVectors.unpackedWord16X16, (e, destination) -> {
                e.builder.beginGeneratedWord16X16Unpack(new BytecodeVectorLanes(accessors(destination)));
                operands.get(0).emit(e);
                e.builder.endGeneratedWord16X16Unpack();
            });
            case "broadcastWord16X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord16X16Broadcast(); for (var operand : operands) operand.emit(e); b.endGeneratedWord16X16Broadcast();
            }, GeneratedVectors.proofWord16X16);
            case "plusWord16X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord16X16Plus(); for (var operand : operands) operand.emit(e); b.endGeneratedWord16X16Plus();
            }, GeneratedVectors.proofWord16X16);
            case "minusWord16X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord16X16Minus(); for (var operand : operands) operand.emit(e); b.endGeneratedWord16X16Minus();
            }, GeneratedVectors.proofWord16X16);
            case "timesWord16X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord16X16Times(); for (var operand : operands) operand.emit(e); b.endGeneratedWord16X16Times();
            }, GeneratedVectors.proofWord16X16);
            case "insertWord16X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord16X16Insert(); for (var operand : operands) operand.emit(e); b.endGeneratedWord16X16Insert();
            }, GeneratedVectors.proofWord16X16);
            case "minWord16X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord16X16Min(); for (var operand : operands) operand.emit(e); b.endGeneratedWord16X16Min();
            }, GeneratedVectors.proofWord16X16);
            case "maxWord16X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord16X16Max(); for (var operand : operands) operand.emit(e); b.endGeneratedWord16X16Max();
            }, GeneratedVectors.proofWord16X16);
            case "quotWord16X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord16X16Quot(); for (var operand : operands) operand.emit(e); b.endGeneratedWord16X16Quot();
            }, GeneratedVectors.proofWord16X16);
            case "remWord16X16#" -> new ProvenExpression(e -> {
                var b = e.builder;
                b.beginGeneratedWord16X16Rem(); for (var operand : operands) operand.emit(e); b.endGeneratedWord16X16Rem();
            }, GeneratedVectors.proofWord16X16);
            case "shuffleWord16X16#" -> {
                if (shuffleIndices == null) throw RuntimeFault.fault("Missing shuffle indices");
                var shuffle = jdk.incubator.vector.VectorShuffle.fromArray(jdk.incubator.vector.ShortVector.SPECIES_256, shuffleIndices, 0);
                yield new ProvenExpression(e -> {
                    e.builder.beginGeneratedWord16X16Shuffle(shuffle);
                    operands.get(0).emit(e); operands.get(1).emit(e);
                    e.builder.endGeneratedWord16X16Shuffle();
                }, GeneratedVectors.proofWord16X16);
            }
            default -> throw new UnsupportedCore("Unsupported generated vector primitive " + name);
        };
    }
    // END GENERATED SIMD FAMILIES

    private void typedArguments(Emission e, Expression function, List<Expression> arguments,
            ArgumentLayout layout, boolean tail, BytecodeTupleSlots destination, boolean selfTransfer) {
        var b = e.builder;
        b.beginBlock();
        var fn = b.createLocal("typed function", null);
        b.beginStoreLocal(fn); requireClosure(function).emit(e); b.endStoreLocal();
        var values = new ArrayList<BytecodeLocal>();
        for (int i = 0; i < layout.getPhysicalArity(); ++i) values.add(b.createLocal("typed input " + i, null));
        for (int i = 0; i < arguments.size(); ++i) {
            var argument = arguments.get(i);
            int offset = layout.offset(i);
            if (layout.proof(i).isTypedTransport()) argument.emitTuple(e, values.subList(offset, layout.offset(i + 1)));
            else { b.beginStoreLocal(values.get(offset)); argument.emit(e); b.endStoreLocal(); }
        }
        var source = new BytecodeInputSource(layout, accessors(values));
        if (selfTransfer) {
            b.beginConditional();
            b.beginIsTypedSelf(layout.getLogicalArity()); b.emitLoadLocal(fn); b.endIsTypedSelf();
            b.beginBlock();
            b.beginTransferTypedSelf(Objects.requireNonNull(e.typedInputSlots), source, metrics);
            b.emitLoadLocal(fn); b.endTransferTypedSelf();
            b.emitBranch(Objects.requireNonNull(e.continueLabel));
            b.emitLoadConstant(thc.runtime.Unit.INSTANCE);
            b.endBlock();
        }
        if (destination == null) {
            b.beginApplyTypedInput(source, tail, metrics);
            b.emitLoadLocal(fn); b.endApplyTypedInput();
        } else {
            b.beginApplyTypedInputTuple(source, destination, tail, metrics);
            b.emitLoadLocal(fn); b.endApplyTypedInputTuple();
        }
        if (selfTransfer) b.endConditional();
        b.endBlock();
    }

    private void restoreTailArguments(Emission e, FunctionContext context, BytecodeLocal transfer) {
        var b = e.builder;
        var typed = e.typedInputSlots;
        if (typed != null) {
            b.beginRestoreTypedTail(typed); b.emitLoadLocal(transfer); b.endRestoreTypedTail();
            return;
        }
        // Preserve this activation's ancestry when replacing captures/formals.
        for (int i = 0; i < context.captures.size(); ++i) {
            var local = context.captures.get(i);
            b.beginStoreLocal(Objects.requireNonNull(e.locals.get(local.id)));
            if (local.directLong()) b.beginCaptureReadLong(Objects.requireNonNull(context.captureLayout), i);
            else b.beginCaptureRead(Objects.requireNonNull(context.captureLayout), i);
            b.beginTailArgument(1); b.emitLoadLocal(transfer); b.endTailArgument();
            if (local.directLong()) b.endCaptureReadLong(); else b.endCaptureRead();
            b.endStoreLocal();
        }
        for (var slots : vectorCaptureSlots(e, context)) {
            b.beginCaptureReadVector(slots);
            b.beginTailArgument(1); b.emitLoadLocal(transfer); b.endTailArgument();
            b.endCaptureReadVector();
        }
        int offset = context.captureLayout == null ? 1 : 2;
        for (int i = 0; i < context.arguments.size(); ++i) {
            var local = context.arguments.get(i);
            if (local == null) continue;
            int argument = ArgumentLayout.offset(context.inputLayout, i) + offset;
            restoreArgument(e, local, enableAsync && context.entryStrict[i], () -> {
                b.beginTailArgument(argument); b.emitLoadLocal(transfer); b.endTailArgument();
            });
        }
    }

    private void loadSavedInput(Emission e, BytecodeLocal value) {
        if (e.staticResults.get(value) == FrameSlotKind.Object) e.builder.emitStaticLoadObject(value);
        else e.builder.emitLoadLocal(value); // Typed aggregate leaves retain their exact primitive carriers.
    }

    private void savedApply(Emission e, BytecodeLocal fn, List<BytecodeLocal> values,
            ArgumentLayout layout, boolean[] evaluatedArguments, int arity, boolean tail, boolean selfTransfer) {
        var b = e.builder;
        var typedSource = layout != null && layout.getRequiresTyped() ? new BytecodeInputSource(layout, accessors(values)) : null;
        if (typedSource != null) {
            if (selfTransfer) { b.beginBlock(); savedTypedSelf(e, fn, typedSource, arity); }
            b.beginApplyTypedInput(typedSource, tail, metrics);
            b.emitStaticLoadObject(fn); b.endApplyTypedInput();
            if (selfTransfer) b.endBlock();
        } else if (layout != null) {
            b.beginApplyCompact(layout, tail, metrics, evaluatedArguments);
            b.emitStaticLoadObject(fn); for (var value : values) loadSavedInput(e, value);
            b.endApplyCompact();
        } else {
            b.beginApply(arity, tail, metrics, evaluatedArguments);
            b.emitStaticLoadObject(fn); for (var value : values) loadSavedInput(e, value);
            b.endApply();
        }
    }

    /** Completed saved operands are disjoint from formals; exiting scopes retain their finally/clear edges. */
    private void savedTypedSelf(Emission e, BytecodeLocal fn, BytecodeInputSource source, int arity) {
        var b = e.builder;
        b.beginIfThen();
        b.beginIsTypedSelf(arity); b.emitStaticLoadObject(fn); b.endIsTypedSelf();
        b.beginBlock();
        b.beginTransferTypedSelf(Objects.requireNonNull(e.typedInputSlots), source, metrics);
        b.emitStaticLoadObject(fn); b.endTransferTypedSelf();
        b.emitBranch(Objects.requireNonNull(e.continueLabel));
        b.endBlock(); b.endIfThen();
    }

    /** One exact call or PAP step; a yielded callee is captured before any suffix runs. */
    private void checkpointedCall(Emission e, BytecodeLocal fn, List<BytecodeLocal> values,
            ArgumentLayout layout, boolean[] evaluatedArguments, int arity, BytecodeLocal callerMask) {
        var b = e.builder;
        b.beginBlock();
        var result = b.createLocal("captured application result", FrameSlotKind.Object);
        var suspended = b.createLocal("captured application suspension", FrameSlotKind.Object);
        b.beginTryCatch();
        b.beginStaticStoreObject(result);
        b.beginCaptureApplicationResult(arity);
        b.emitStaticLoadObject(fn);
        savedApply(e, fn, values, layout, evaluatedArguments, arity, false, false);
        b.emitStaticLoadObject(callerMask);
        b.endCaptureApplicationResult();
        b.endStaticStoreObject();
        b.beginBlock();
        b.beginStaticStoreObject(suspended);
        b.beginCallSuspensionOnly(); b.emitLoadException(); b.endCallSuspensionOnly();
        b.endStaticStoreObject();
        b.beginStaticStoreObject(result);
        b.beginResumeApplication();
        b.emitStaticLoadObject(suspended);
        b.beginReenterCallMask();
        beginAnnotationYield(e);
        b.beginParkCallMask();
        b.emitStaticLoadObject(suspended);
        b.emitStaticLoadObject(Objects.requireNonNull(e.checkpointRootEntry));
        b.emitStaticLoadObject(callerMask);
        b.endParkCallMask();
        endAnnotationYield(e);
        b.emitStaticLoadObject(callerMask);
        b.endReenterCallMask();
        b.endResumeApplication();
        b.endStaticStoreObject();
        b.endBlock();
        b.endTryCatch();
        b.emitStaticLoadObject(result);
        b.endBlock();
    }

    @FunctionalInterface
    private interface FinishTuple {
        void emit(List<BytecodeLocal> suffix, ArgumentLayout layout, int arity);
    }

    /** A saved application crosses exact call boundaries without replaying operands. */
    private void stagedOverapplication(Emission e, BytecodeLocal fn, List<BytecodeLocal> values,
            ArgumentLayout layout, boolean[] evaluatedArguments, BytecodeLocal callerMask,
            BytecodeLocal result, int count, boolean tail, FinishTuple finishTuple) {
        var b = e.builder;
        b.beginBlock();
        var stages = new ArrayList<BytecodeLabel>();
        for (int i = 0; i < count; ++i) stages.add(b.createLabel());
        var complete = b.createLabel();
        b.emitBranch(stages.getFirst());
        for (int start = 0; start < stages.size(); ++start) {
            b.emitLabel(stages.get(start));
            if (enableAsync) emitAsyncPoll(e);
            // A zero-arity closure consumes no arguments; its backward edge needs a structured loop.
            b.beginWhile();
            b.beginSavedCallArity(0, false); b.emitStaticLoadObject(fn); b.endSavedCallArity();
            b.beginBlock();
            if (enableAsync) emitAsyncPoll(e);
            b.beginStaticStoreObject(result);
            checkpointedCall(e, fn, List.of(), null, new boolean[0], 0, callerMask);
            b.endStaticStoreObject();
            b.beginStaticStoreObject(fn);
            requireClosure(emission -> emission.builder.emitStaticLoadObject(result)).emit(e);
            b.endStaticStoreObject();
            b.endBlock(); b.endWhile();
            int remaining = count - start;
            for (int take = 1; take < remaining; ++take) {
                b.beginIfThen();
                b.beginSavedCallArity(take, false); b.emitStaticLoadObject(fn); b.endSavedCallArity();
                b.beginBlock();
                int from = ArgumentLayout.offset(layout, start);
                int until = ArgumentLayout.offset(layout, start + take);
                ArgumentLayout input = null;
                if (layout != null) {
                    var proofs = new ArrayList<CoreRepresentation>();
                    for (int i = start; i < start + take; ++i) proofs.add(layout.proof(i));
                    input = ArgumentLayout.fromProofs(proofs);
                }
                b.beginStaticStoreObject(result);
                checkpointedCall(e, fn, values.subList(from, until), input,
                    java.util.Arrays.copyOfRange(evaluatedArguments, start, start + take), take, callerMask);
                b.endStaticStoreObject();
                // Resume a yielded force from its saved result, never from the original call.
                b.beginStaticStoreObject(fn);
                requireClosure(emission -> emission.builder.emitStaticLoadObject(result)).emit(e);
                b.endStaticStoreObject();
                b.emitBranch(stages.get(start + take));
                b.endBlock(); b.endIfThen();
            }
            int from = ArgumentLayout.offset(layout, start);
            ArgumentLayout input = null;
            if (layout != null) {
                var proofs = new ArrayList<CoreRepresentation>();
                for (int i = start; i < count; ++i) proofs.add(layout.proof(i));
                input = ArgumentLayout.fromProofs(proofs);
            }
            var suffix = values.subList(from, values.size());
            if (finishTuple != null) finishTuple.emit(suffix, input, remaining);
            else {
                b.beginStaticStoreObject(result);
                var evaluatedSuffix = java.util.Arrays.copyOfRange(evaluatedArguments, start, count);
                if (tail) savedApply(e, fn, suffix, input, evaluatedSuffix, remaining, true, false);
                else checkpointedCall(e, fn, suffix, input, evaluatedSuffix, remaining, callerMask);
                b.endStaticStoreObject();
            }
            b.emitBranch(complete);
        }
        b.emitLabel(complete);
        if (finishTuple == null) b.emitStaticLoadObject(result);
        b.endBlock();
    }

    /** Capture callees after evaluating operands once, including compact and typed inputs. */
    private void checkpointedApplication(Emission e, Expression function, List<Expression> arguments,
            boolean[] evaluatedArguments, ArgumentLayout layout, boolean tail, boolean selfTransfer) {
        var b = e.builder;
        b.beginBlock();
        var fn = b.createLocal("captured application function", FrameSlotKind.Object);
        var callerMask = b.createLocal("captured application caller mask", FrameSlotKind.Object);
        var result = b.createLocal("captured application result", FrameSlotKind.Object);
        b.beginStaticStoreObject(fn); requireClosure(function).emit(e); b.endStaticStoreObject();
        var values = new ArrayList<BytecodeLocal>();
        if (layout != null && layout.getRequiresTyped()) {
            for (int i = 0; i < layout.getPhysicalArity(); ++i) values.add(b.createLocal("captured typed input " + i, null));
            for (int i = 0; i < arguments.size(); ++i) {
                var argument = arguments.get(i);
                int offset = layout.offset(i);
                if (layout.proof(i).isTypedTransport()) argument.emitTuple(e, values.subList(offset, layout.offset(i + 1)));
                else { b.beginStoreLocal(values.get(offset)); argument.emit(e); b.endStoreLocal(); }
            }
        } else {
            for (int i = 0; i < arguments.size(); ++i) {
                var argument = arguments.get(i);
                if (layout != null && layout.isEmpty(i)) argument.emitTuple(e, List.of());
                else {
                    var local = b.createLocal("captured application operand " + i, FrameSlotKind.Object);
                    e.staticResults.put(local, FrameSlotKind.Object);
                    b.beginStaticStoreObject(local); argument.emit(e); b.endStaticStoreObject();
                    values.add(local);
                }
            }
        }
        b.beginStaticStoreObject(callerMask); b.emitCurrentMask(); b.endStaticStoreObject();
        if (arguments.isEmpty()) {
            if (tail) savedApply(e, fn, values, layout, evaluatedArguments, 0, true, false);
            else checkpointedCall(e, fn, values, layout, evaluatedArguments, 0, callerMask);
            b.endBlock();
            return;
        }
        var complete = b.createLabel();
        b.beginIfThen();
        b.beginSavedCallArity(arguments.size(), true); b.emitStaticLoadObject(fn); b.endSavedCallArity();
        b.beginBlock(); b.beginStaticStoreObject(result);
        stagedOverapplication(e, fn, values, layout, evaluatedArguments, callerMask, result, arguments.size(), tail, null);
        b.endStaticStoreObject(); b.emitBranch(complete); b.endBlock(); b.endIfThen();
        b.beginStaticStoreObject(result);
        if (tail) savedApply(e, fn, values, layout, evaluatedArguments, arguments.size(), true, selfTransfer);
        else checkpointedCall(e, fn, values, layout, evaluatedArguments, arguments.size(), callerMask);
        b.endStaticStoreObject();
        b.emitLabel(complete); b.emitStaticLoadObject(result);
        b.endBlock();
    }

    private Expression floatingPrimitive(String name, List<Expression> args) {
        String operation = switch (name) {
            case "fmaddFloat#" -> "FloatFMAdd";
            case "fmsubFloat#" -> "FloatFMSub";
            case "fnmaddFloat#" -> "FloatFNMAdd";
            case "fnmsubFloat#" -> "FloatFNMSub";
            case "fmaddDouble#" -> "DoubleFMAdd";
            case "fmsubDouble#" -> "DoubleFMSub";
            case "fnmaddDouble#" -> "DoubleFNMAdd";
            case "fnmsubDouble#" -> "DoubleFNMSub";
            case "plusFloat#" -> "FloatAdd";
            case "minusFloat#" -> "FloatSubtract";
            case "timesFloat#" -> "FloatMultiply";
            case "divideFloat#" -> "FloatDivide";
            case "negateFloat#" -> "FloatNegate";
            case "sqrtFloat#" -> "FloatSqrt";
            case "fabsFloat#" -> "FloatAbs";
            case "expFloat#" -> "FloatExp";
            case "expm1Float#" -> "FloatExpm1";
            case "logFloat#" -> "FloatLog";
            case "log1pFloat#" -> "FloatLog1p";
            case "sinFloat#" -> "FloatSin";
            case "cosFloat#" -> "FloatCos";
            case "powerFloat#" -> "FloatPower";
            case "tanFloat#" -> "FloatTan";
            case "asinFloat#" -> "FloatAsin";
            case "acosFloat#" -> "FloatAcos";
            case "atanFloat#" -> "FloatAtan";
            case "sinhFloat#" -> "FloatSinh";
            case "coshFloat#" -> "FloatCosh";
            case "tanhFloat#" -> "FloatTanh";
            case "eqFloat#" -> "FloatEqual";
            case "neFloat#" -> "FloatNotEqual";
            case "ltFloat#" -> "FloatLess";
            case "leFloat#" -> "FloatLessEqual";
            case "gtFloat#" -> "FloatGreater";
            case "geFloat#" -> "FloatGreaterEqual";
            case "+##" -> "DoubleAdd";
            case "-##" -> "DoubleSubtract";
            case "*##" -> "DoubleMultiply";
            case "/##" -> "DoubleDivide";
            case "negateDouble#" -> "DoubleNegate";
            case "sqrtDouble#" -> "DoubleSqrt";
            case "fabsDouble#" -> "DoubleAbs";
            case "expDouble#" -> "DoubleExp";
            case "expm1Double#" -> "DoubleExpm1";
            case "logDouble#" -> "DoubleLog";
            case "log1pDouble#" -> "DoubleLog1p";
            case "sinDouble#" -> "DoubleSin";
            case "cosDouble#" -> "DoubleCos";
            case "**##" -> "DoublePower";
            case "tanDouble#" -> "DoubleTan";
            case "asinDouble#" -> "DoubleAsin";
            case "acosDouble#" -> "DoubleAcos";
            case "atanDouble#" -> "DoubleAtan";
            case "sinhDouble#" -> "DoubleSinh";
            case "coshDouble#" -> "DoubleCosh";
            case "tanhDouble#" -> "DoubleTanh";
            case "asinhFloat#" -> "FloatAsinh";
            case "acoshFloat#" -> "FloatAcosh";
            case "atanhFloat#" -> "FloatAtanh";
            case "minFloat#" -> "FloatMin";
            case "maxFloat#" -> "FloatMax";
            case "asinhDouble#" -> "DoubleAsinh";
            case "acoshDouble#" -> "DoubleAcosh";
            case "atanhDouble#" -> "DoubleAtanh";
            case "minDouble#" -> "DoubleMin";
            case "maxDouble#" -> "DoubleMax";
            case "==##" -> "DoubleEqual";
            case "/=##" -> "DoubleNotEqual";
            case "<##" -> "DoubleLess";
            case "<=##" -> "DoubleLessEqual";
            case ">##" -> "DoubleGreater";
            case ">=##" -> "DoubleGreaterEqual";
            case "castFloatToWord32#" -> "CastFloatToWord32";
            case "castWord32ToFloat#" -> "CastWord32ToFloat";
            case "castDoubleToWord64#" -> "CastDoubleToWord64";
            case "castWord64ToDouble#" -> "CastWord64ToDouble";
            case "int2Float#" -> "IntToFloat";
            case "int2Double#" -> "IntToDouble";
            case "word2Float#" -> "WordToFloat";
            case "word2Double#" -> "WordToDouble";
            case "float2Int#" -> "FloatToInt";
            case "double2Int#" -> "DoubleToInt";
            case "float2Double#" -> "FloatToDouble";
            case "double2Float#" -> "DoubleToFloat";
            default -> null;
        };
        if (operation == null) return null;
        boolean unary = Set.of("FloatAsinh", "FloatAcosh", "FloatAtanh", "DoubleAsinh", "DoubleAcosh", "DoubleAtanh", "CastFloatToWord32", "CastWord32ToFloat", "CastDoubleToWord64", "CastWord64ToDouble", "FloatNegate", "DoubleNegate", "FloatSqrt", "DoubleSqrt", "IntToFloat", "WordToFloat", "IntToDouble", "WordToDouble", "FloatToInt", "DoubleToInt", "FloatToDouble", "DoubleToFloat", "FloatAbs", "FloatExp", "FloatExpm1", "FloatLog", "FloatLog1p", "FloatSin", "FloatCos", "DoubleAbs", "DoubleExp", "DoubleExpm1", "DoubleLog", "DoubleLog1p", "DoubleSin", "DoubleCos", "FloatTan", "FloatAsin", "FloatAcos", "FloatAtan", "FloatSinh", "FloatCosh", "FloatTanh", "DoubleTan", "DoubleAsin", "DoubleAcos", "DoubleAtan", "DoubleSinh", "DoubleCosh", "DoubleTanh").contains(operation);
        boolean fused = Set.of("FloatFMAdd", "FloatFMSub", "FloatFNMAdd", "FloatFNMSub", "DoubleFMAdd", "DoubleFMSub", "DoubleFNMAdd", "DoubleFNMSub").contains(operation);
        if (args.size() != (fused ? 3 : unary ? 1 : 2)) throw new RuntimeFault("Primitive arity mismatch: " + name);
        CoreKind kind = switch (operation) {
            case "FloatAsinh", "FloatAcosh", "FloatAtanh", "FloatMin", "FloatMax" -> CoreKind.FLOAT;
            case "DoubleAsinh", "DoubleAcosh", "DoubleAtanh", "DoubleMin", "DoubleMax" -> CoreKind.DOUBLE;
            case "FloatFMAdd", "FloatFMSub", "FloatFNMAdd", "FloatFNMSub" -> CoreKind.FLOAT;
            case "DoubleFMAdd", "DoubleFMSub", "DoubleFNMAdd", "DoubleFNMSub" -> CoreKind.DOUBLE;
            case "FloatAdd", "FloatSubtract", "FloatMultiply", "FloatDivide", "FloatNegate", "FloatSqrt", "IntToFloat", "WordToFloat", "DoubleToFloat", "CastWord32ToFloat", "FloatAbs", "FloatExp", "FloatExpm1", "FloatLog", "FloatLog1p", "FloatSin", "FloatCos", "FloatPower", "FloatTan", "FloatAsin", "FloatAcos", "FloatAtan", "FloatSinh", "FloatCosh", "FloatTanh" -> CoreKind.FLOAT;
            case "DoubleAdd", "DoubleSubtract", "DoubleMultiply", "DoubleDivide", "DoubleNegate", "DoubleSqrt", "IntToDouble", "WordToDouble", "FloatToDouble", "CastWord64ToDouble", "DoubleAbs", "DoubleExp", "DoubleExpm1", "DoubleLog", "DoubleLog1p", "DoubleSin", "DoubleCos", "DoublePower", "DoubleTan", "DoubleAsin", "DoubleAcos", "DoubleAtan", "DoubleSinh", "DoubleCosh", "DoubleTanh" -> CoreKind.DOUBLE;
            default -> CoreKind.LONG;
        };
        return new ProvenExpression(e -> {
            var b = e.builder;
            switch (operation) {
                case "FloatFMAdd" -> { b.beginFloatFMAdd(); for (var arg : args) arg.emit(e); b.endFloatFMAdd(); }
                case "FloatFMSub" -> { b.beginFloatFMSub(); for (var arg : args) arg.emit(e); b.endFloatFMSub(); }
                case "FloatFNMAdd" -> { b.beginFloatFNMAdd(); for (var arg : args) arg.emit(e); b.endFloatFNMAdd(); }
                case "FloatFNMSub" -> { b.beginFloatFNMSub(); for (var arg : args) arg.emit(e); b.endFloatFNMSub(); }
                case "DoubleFMAdd" -> { b.beginDoubleFMAdd(); for (var arg : args) arg.emit(e); b.endDoubleFMAdd(); }
                case "DoubleFMSub" -> { b.beginDoubleFMSub(); for (var arg : args) arg.emit(e); b.endDoubleFMSub(); }
                case "DoubleFNMAdd" -> { b.beginDoubleFNMAdd(); for (var arg : args) arg.emit(e); b.endDoubleFNMAdd(); }
                case "DoubleFNMSub" -> { b.beginDoubleFNMSub(); for (var arg : args) arg.emit(e); b.endDoubleFNMSub(); }
                case "FloatAdd" -> { b.beginFloatAdd(); for (var arg : args) arg.emit(e); b.endFloatAdd(); }
                case "FloatSubtract" -> { b.beginFloatSubtract(); for (var arg : args) arg.emit(e); b.endFloatSubtract(); }
                case "FloatMultiply" -> { b.beginFloatMultiply(); for (var arg : args) arg.emit(e); b.endFloatMultiply(); }
                case "FloatDivide" -> { b.beginFloatDivide(); for (var arg : args) arg.emit(e); b.endFloatDivide(); }
                case "FloatNegate" -> { b.beginFloatNegate(); for (var arg : args) arg.emit(e); b.endFloatNegate(); }
                case "FloatSqrt" -> { b.beginFloatSqrt(); for (var arg : args) arg.emit(e); b.endFloatSqrt(); }
                case "FloatAbs" -> { b.beginFloatAbs(); for (var arg : args) arg.emit(e); b.endFloatAbs(); }
                case "FloatExp" -> { b.beginFloatExp(); for (var arg : args) arg.emit(e); b.endFloatExp(); }
                case "FloatExpm1" -> { b.beginFloatExpm1(); for (var arg : args) arg.emit(e); b.endFloatExpm1(); }
                case "FloatLog" -> { b.beginFloatLog(); for (var arg : args) arg.emit(e); b.endFloatLog(); }
                case "FloatLog1p" -> { b.beginFloatLog1p(); for (var arg : args) arg.emit(e); b.endFloatLog1p(); }
                case "FloatSin" -> { b.beginFloatSin(); for (var arg : args) arg.emit(e); b.endFloatSin(); }
                case "FloatCos" -> { b.beginFloatCos(); for (var arg : args) arg.emit(e); b.endFloatCos(); }
                case "FloatPower" -> { b.beginFloatPower(); for (var arg : args) arg.emit(e); b.endFloatPower(); }
                case "FloatTan" -> { b.beginFloatTan(); for (var arg : args) arg.emit(e); b.endFloatTan(); }
                case "FloatAsin" -> { b.beginFloatAsin(); for (var arg : args) arg.emit(e); b.endFloatAsin(); }
                case "FloatAcos" -> { b.beginFloatAcos(); for (var arg : args) arg.emit(e); b.endFloatAcos(); }
                case "FloatAtan" -> { b.beginFloatAtan(); for (var arg : args) arg.emit(e); b.endFloatAtan(); }
                case "FloatSinh" -> { b.beginFloatSinh(); for (var arg : args) arg.emit(e); b.endFloatSinh(); }
                case "FloatCosh" -> { b.beginFloatCosh(); for (var arg : args) arg.emit(e); b.endFloatCosh(); }
                case "FloatTanh" -> { b.beginFloatTanh(); for (var arg : args) arg.emit(e); b.endFloatTanh(); }
                case "FloatEqual" -> { b.beginFloatEqual(); for (var arg : args) arg.emit(e); b.endFloatEqual(); }
                case "FloatNotEqual" -> { b.beginFloatNotEqual(); for (var arg : args) arg.emit(e); b.endFloatNotEqual(); }
                case "FloatLess" -> { b.beginFloatLess(); for (var arg : args) arg.emit(e); b.endFloatLess(); }
                case "FloatLessEqual" -> { b.beginFloatLessEqual(); for (var arg : args) arg.emit(e); b.endFloatLessEqual(); }
                case "FloatGreater" -> { b.beginFloatGreater(); for (var arg : args) arg.emit(e); b.endFloatGreater(); }
                case "FloatGreaterEqual" -> { b.beginFloatGreaterEqual(); for (var arg : args) arg.emit(e); b.endFloatGreaterEqual(); }
                case "DoubleAdd" -> { b.beginDoubleAdd(); for (var arg : args) arg.emit(e); b.endDoubleAdd(); }
                case "DoubleSubtract" -> { b.beginDoubleSubtract(); for (var arg : args) arg.emit(e); b.endDoubleSubtract(); }
                case "DoubleMultiply" -> { b.beginDoubleMultiply(); for (var arg : args) arg.emit(e); b.endDoubleMultiply(); }
                case "DoubleDivide" -> { b.beginDoubleDivide(); for (var arg : args) arg.emit(e); b.endDoubleDivide(); }
                case "DoubleNegate" -> { b.beginDoubleNegate(); for (var arg : args) arg.emit(e); b.endDoubleNegate(); }
                case "DoubleSqrt" -> { b.beginDoubleSqrt(); for (var arg : args) arg.emit(e); b.endDoubleSqrt(); }
                case "DoubleAbs" -> { b.beginDoubleAbs(); for (var arg : args) arg.emit(e); b.endDoubleAbs(); }
                case "DoubleExp" -> { b.beginDoubleExp(); for (var arg : args) arg.emit(e); b.endDoubleExp(); }
                case "DoubleExpm1" -> { b.beginDoubleExpm1(); for (var arg : args) arg.emit(e); b.endDoubleExpm1(); }
                case "DoubleLog" -> { b.beginDoubleLog(); for (var arg : args) arg.emit(e); b.endDoubleLog(); }
                case "DoubleLog1p" -> { b.beginDoubleLog1p(); for (var arg : args) arg.emit(e); b.endDoubleLog1p(); }
                case "DoubleSin" -> { b.beginDoubleSin(); for (var arg : args) arg.emit(e); b.endDoubleSin(); }
                case "DoubleCos" -> { b.beginDoubleCos(); for (var arg : args) arg.emit(e); b.endDoubleCos(); }
                case "DoublePower" -> { b.beginDoublePower(); for (var arg : args) arg.emit(e); b.endDoublePower(); }
                case "DoubleTan" -> { b.beginDoubleTan(); for (var arg : args) arg.emit(e); b.endDoubleTan(); }
                case "DoubleAsin" -> { b.beginDoubleAsin(); for (var arg : args) arg.emit(e); b.endDoubleAsin(); }
                case "DoubleAcos" -> { b.beginDoubleAcos(); for (var arg : args) arg.emit(e); b.endDoubleAcos(); }
                case "DoubleAtan" -> { b.beginDoubleAtan(); for (var arg : args) arg.emit(e); b.endDoubleAtan(); }
                case "DoubleSinh" -> { b.beginDoubleSinh(); for (var arg : args) arg.emit(e); b.endDoubleSinh(); }
                case "DoubleCosh" -> { b.beginDoubleCosh(); for (var arg : args) arg.emit(e); b.endDoubleCosh(); }
                case "DoubleTanh" -> { b.beginDoubleTanh(); for (var arg : args) arg.emit(e); b.endDoubleTanh(); }
                case "FloatAsinh" -> { b.beginFloatAsinh(); for (var arg : args) arg.emit(e); b.endFloatAsinh(); }
                case "FloatAcosh" -> { b.beginFloatAcosh(); for (var arg : args) arg.emit(e); b.endFloatAcosh(); }
                case "FloatAtanh" -> { b.beginFloatAtanh(); for (var arg : args) arg.emit(e); b.endFloatAtanh(); }
                case "FloatMin" -> { b.beginFloatMin(); for (var arg : args) arg.emit(e); b.endFloatMin(); }
                case "FloatMax" -> { b.beginFloatMax(); for (var arg : args) arg.emit(e); b.endFloatMax(); }
                case "DoubleAsinh" -> { b.beginDoubleAsinh(); for (var arg : args) arg.emit(e); b.endDoubleAsinh(); }
                case "DoubleAcosh" -> { b.beginDoubleAcosh(); for (var arg : args) arg.emit(e); b.endDoubleAcosh(); }
                case "DoubleAtanh" -> { b.beginDoubleAtanh(); for (var arg : args) arg.emit(e); b.endDoubleAtanh(); }
                case "DoubleMin" -> { b.beginDoubleMin(); for (var arg : args) arg.emit(e); b.endDoubleMin(); }
                case "DoubleMax" -> { b.beginDoubleMax(); for (var arg : args) arg.emit(e); b.endDoubleMax(); }
                case "DoubleEqual" -> { b.beginDoubleEqual(); for (var arg : args) arg.emit(e); b.endDoubleEqual(); }
                case "DoubleNotEqual" -> { b.beginDoubleNotEqual(); for (var arg : args) arg.emit(e); b.endDoubleNotEqual(); }
                case "DoubleLess" -> { b.beginDoubleLess(); for (var arg : args) arg.emit(e); b.endDoubleLess(); }
                case "DoubleLessEqual" -> { b.beginDoubleLessEqual(); for (var arg : args) arg.emit(e); b.endDoubleLessEqual(); }
                case "DoubleGreater" -> { b.beginDoubleGreater(); for (var arg : args) arg.emit(e); b.endDoubleGreater(); }
                case "DoubleGreaterEqual" -> { b.beginDoubleGreaterEqual(); for (var arg : args) arg.emit(e); b.endDoubleGreaterEqual(); }
                case "CastFloatToWord32" -> { b.beginCastFloatToWord32(); for (var arg : args) arg.emit(e); b.endCastFloatToWord32(); }
                case "CastWord32ToFloat" -> { b.beginCastWord32ToFloat(); for (var arg : args) arg.emit(e); b.endCastWord32ToFloat(); }
                case "CastDoubleToWord64" -> { b.beginCastDoubleToWord64(); for (var arg : args) arg.emit(e); b.endCastDoubleToWord64(); }
                case "CastWord64ToDouble" -> { b.beginCastWord64ToDouble(); for (var arg : args) arg.emit(e); b.endCastWord64ToDouble(); }
                case "IntToFloat" -> { b.beginIntToFloat(); for (var arg : args) arg.emit(e); b.endIntToFloat(); }
                case "IntToDouble" -> { b.beginIntToDouble(); for (var arg : args) arg.emit(e); b.endIntToDouble(); }
                case "WordToFloat" -> { b.beginWordToFloat(); for (var arg : args) arg.emit(e); b.endWordToFloat(); }
                case "WordToDouble" -> { b.beginWordToDouble(); for (var arg : args) arg.emit(e); b.endWordToDouble(); }
                case "FloatToInt" -> { b.beginFloatToInt(); for (var arg : args) arg.emit(e); b.endFloatToInt(); }
                case "DoubleToInt" -> { b.beginDoubleToInt(); for (var arg : args) arg.emit(e); b.endDoubleToInt(); }
                case "FloatToDouble" -> { b.beginFloatToDouble(); for (var arg : args) arg.emit(e); b.endFloatToDouble(); }
                case "DoubleToFloat" -> { b.beginDoubleToFloat(); for (var arg : args) arg.emit(e); b.endDoubleToFloat(); }
            }
        }, new CoreRepresentation(kind, true, false, null, null, null, null, null, null));
    }

    private Expression application(Expression function, List<Expression> arguments, Scope scope, boolean tail) {
        var context = scope.function;
        var evaluatedArguments = new boolean[arguments.size()];
        var proofs = new ArrayList<CoreRepresentation>();
        for (int i = 0; i < arguments.size(); ++i) {
            var proof = arguments.get(i).proof();
            evaluatedArguments[i] = proof.getEvaluated(); proofs.add(proof);
        }
        var inputLayout = ArgumentLayout.fromProofs(proofs);
        boolean catchesTail = tail && !context.passThrough;
        boolean loop = catchesTail && context.inputLayout == null && inputLayout == null
            && arguments.size() <= context.formalArity && context.formalArity > 0;
        // Roots without direct self-calls can still receive A -> B -> ... -> A.
        if (catchesTail) context.mayLoop = true;
        return evaluated(e -> {
            var b = e.builder;
            if (enableAsync) { b.beginBlock(); emitAsyncPoll(e); }
            BytecodeLocal reentryResult = null;
            if (catchesTail) {
                b.beginBlock();
                reentryResult = b.createLocal("tail result", resumable ? FrameSlotKind.Object : null);
                if (resumable) b.beginStaticStoreObject(reentryResult); else b.beginStoreLocal(reentryResult);
            }
            if (resumable && !loop) checkpointedApplication(e, function, arguments, evaluatedArguments, inputLayout, tail,
                catchesTail && !delimited && inputLayout != null && inputLayout.getRequiresTyped()
                    && TypedInputs.supportsTypedSelf(context.inputLayout, context.entryStrict, inputLayout));
            else if (inputLayout != null && inputLayout.getRequiresTyped())
                typedArguments(e, function, arguments, inputLayout, tail, null, tail && !resumable
                    && !context.passThrough && TypedInputs.supportsTypedSelf(context.inputLayout, context.entryStrict, inputLayout));
            else if (inputLayout != null) compactArguments(e, function, arguments, inputLayout, (fn, values) -> {
                b.beginApplyCompact(inputLayout, tail, metrics, evaluatedArguments);
                b.emitLoadLocal(fn); for (var value : values) b.emitLoadLocal(value);
                b.endApplyCompact();
            });
            else if (!loop) {
                b.beginApply(arguments.size(), tail, metrics, evaluatedArguments);
                requireClosure(function).emit(e);
                for (var argument : arguments) argument.emit(e);
                b.endApply();
            } else {
                // Evaluate all operands before assigning formals: tail calls are parallel moves.
                b.beginBlock();
                var fn = b.createLocal("tail function", null);
                b.beginStoreLocal(fn); requireClosure(function).emit(e); b.endStoreLocal();
                var args = new ArrayList<BytecodeLocal>();
                for (int i = 0; i < arguments.size(); ++i) {
                    var local = b.createLocal("tail operand " + i, null);
                    b.beginStoreLocal(local); arguments.get(i).emit(e); b.endStoreLocal();
                    args.add(local);
                }
                b.beginConditional();
                b.beginIsSelf(arguments.size(), context.formalArity); b.emitLoadLocal(fn); b.endIsSelf();
                b.beginBlock();
                int prefix = context.formalArity - arguments.size();
                // Enforce unused strict formals and the PAP prefix before replacing captures/formals.
                var strictArguments = new BytecodeLocal[context.entryStrict.length];
                for (int i = 0; i < context.entryStrict.length; ++i) {
                    if (!context.entryStrict[i] || i >= prefix && arguments.get(i - prefix).proof().getEvaluated()) continue;
                    var temporary = b.createLocal("strict tail operand " + i, null);
                    strictArguments[i] = temporary;
                    b.beginStoreLocal(temporary);
                    int index = i;
                    if (enableAsync) force(reentry -> {
                        var builder = reentry.builder;
                        if (index < prefix) { builder.beginReadSupplied(index); builder.emitLoadLocal(fn); builder.endReadSupplied(); }
                        else builder.emitLoadLocal(args.get(index - prefix));
                    }).emit(e);
                    else {
                        b.beginForceValue(metrics, false);
                        if (index < prefix) { b.beginReadSupplied(index); b.emitLoadLocal(fn); b.endReadSupplied(); }
                        else b.emitLoadLocal(args.get(index - prefix));
                        b.endForceValue();
                    }
                    b.endStoreLocal();
                }
                for (int i = 0; i < context.captures.size(); ++i) {
                    var local = context.captures.get(i);
                    b.beginStoreLocal(Objects.requireNonNull(e.locals.get(local.id)));
                    if (local.directLong()) b.beginCaptureReadLong(Objects.requireNonNull(context.captureLayout), i);
                    else b.beginCaptureRead(Objects.requireNonNull(context.captureLayout), i);
                    b.beginClosureEnvironment(); b.emitLoadLocal(fn); b.endClosureEnvironment();
                    if (local.directLong()) b.endCaptureReadLong(); else b.endCaptureRead();
                    b.endStoreLocal();
                }
                for (var slots : vectorCaptureSlots(e, context)) {
                    b.beginCaptureReadVector(slots);
                    b.beginClosureEnvironment(); b.emitLoadLocal(fn); b.endClosureEnvironment();
                    b.endCaptureReadVector();
                }
                for (int i = 0; i < context.arguments.size(); ++i) {
                    var local = context.arguments.get(i);
                    if (local == null) continue;
                    int index = i;
                    restoreArgument(e, local, () -> {
                        if (strictArguments[index] != null) b.emitLoadLocal(strictArguments[index]);
                        else if (index < prefix) { b.beginReadSupplied(index); b.emitLoadLocal(fn); b.endReadSupplied(); }
                        else b.emitLoadLocal(args.get(index - prefix));
                    });
                }
                b.emitBranch(Objects.requireNonNull(e.continueLabel));
                b.emitLoadConstant(thc.runtime.Unit.INSTANCE);
                b.endBlock();
                if (resumable) {
                    var savedFunction = new ProvenExpression(emission -> emission.builder.emitLoadLocal(fn),
                        evaluatedProof(function.proof(), true));
                    var savedArguments = new ArrayList<Expression>();
                    for (int i = 0; i < args.size(); ++i) {
                        var local = args.get(i);
                        savedArguments.add(new ProvenExpression(emission -> emission.builder.emitLoadLocal(local),
                            arguments.get(i).proof()));
                    }
                    checkpointedApplication(e, savedFunction, savedArguments, evaluatedArguments, null, true, false);
                } else {
                    b.beginApply(arguments.size(), true, metrics, evaluatedArguments);
                    b.emitLoadLocal(fn); for (var arg : args) b.emitLoadLocal(arg);
                    b.endApply();
                }
                b.endConditional();
                b.endBlock();
            }
            if (reentryResult != null) {
                if (resumable) { b.endStaticStoreObject(); b.beginIfThen(); }
                else { b.endStoreLocal(); b.beginConditional(); }
                b.beginIsTailReentry(false);
                if (resumable) b.emitStaticLoadObject(reentryResult); else b.emitLoadLocal(reentryResult);
                b.endIsTailReentry();
                b.beginBlock();
                restoreTailArguments(e, context, reentryResult);
                b.emitBranch(Objects.requireNonNull(e.continueLabel));
                if (!resumable) b.emitLoadConstant(thc.runtime.Unit.INSTANCE);
                b.endBlock();
                if (resumable) { b.endIfThen(); b.emitStaticLoadObject(reentryResult); }
                else { b.emitLoadLocal(reentryResult); b.endConditional(); }
                b.endBlock();
            }
            if (enableAsync) b.endBlock();
        });
    }

    /** A join transfer is a parallel move followed by bytecode control flow in this activation. */
    private Expression joinCall(JoinTarget target, List<Expression> arguments) {
        if (arguments.size() != target.parameters.size()) throw new UnsupportedCore("Local join is not exactly saturated");
        for (int i = 0; i < arguments.size(); ++i)
            CoreRepresentations.requireJoinArgument(CoreRepresentations.binder(target.parameters.get(i)),
                arguments.get(i).proof());
        // Relocation must not change the original lexical forward/backward poll decision.
        boolean poll = target.region.recursive && target.index <= target.region.compilingIndex;
        return new ProvenExpression(new ResultExpression((e, destination) -> {
            var b = e.builder;
            var region = e.joins.get(target.region.owner);
            if (region == null) throw new RuntimeFault("Local join escapes its owning activation");
            b.beginBlock();
            var temporaries = new ArrayList<List<BytecodeLocal>>();
            for (int i = 0; i < arguments.size(); ++i) {
                var argument = arguments.get(i);
                var slots = new ArrayList<BytecodeLocal>();
                for (int lane = 0; lane < target.locals.get(i).size(); ++lane)
                    slots.add(b.createLocal("join operand " + i + " lane " + lane, null));
                if (CoreRepresentations.binder(target.parameters.get(i)).isTypedTransport()) argument.emitTuple(e, slots);
                else {
                    if (slots.size() != 1) throw new IllegalArgumentException("List has more than one element.");
                    b.beginStoreLocal(slots.getFirst()); argument.emit(e); b.endStoreLocal();
                }
                temporaries.add(slots);
            }
            var captured = new ArrayList<BytecodeLocal>();
            for (var capture : target.captures) {
                var temporary = b.createLocal("join capture", null);
                b.beginStoreLocal(temporary); read(capture.source, false).emit(e); b.endStoreLocal();
                captured.add(temporary);
            }
            for (int i = 0; i < target.locals.size(); ++i) {
                var fields = target.locals.get(i);
                for (int lane = 0; lane < fields.size(); ++lane) {
                    var temporary = temporaries.get(i).get(lane);
                    restoreArgument(e, fields.get(lane), () -> b.emitLoadLocal(temporary));
                }
            }
            for (int i = 0; i < target.captures.size(); ++i) {
                b.beginStoreLocal(Objects.requireNonNull(e.locals.get(target.captures.get(i).destination.id)));
                b.emitLoadLocal(captured.get(i)); b.endStoreLocal();
            }
            // Failed operands are not transfers. Count only after all parallel moves succeed.
            b.emitJoinTransfer(metrics);
            if (region.selector == null) {
                if (target.index <= region.emittedIndex) throw new RuntimeFault("Backward transfer into a nonrecursive join group");
                b.emitBranch(region.labels.get(target.index));
            } else {
                if (region.next == null || region.dispatch == null) throw new RuntimeFault("Missing recursive join continuation");
                // One dispatcher keeps recursive cycles reducible from a saved continuation.
                // Forward transfers retain their original behavior of skipping the async poll.
                b.beginStoreLocal(region.selector); b.emitLoadConstant((long) target.selectorIndex); b.endStoreLocal();
                b.emitBranch(poll ? region.next : region.dispatch);
            }
            if (destination == null) b.emitLoadConstant(thc.runtime.Unit.INSTANCE);
            b.endBlock();
        }), evaluatedProof(target.result, true));
    }

    /** Only one available recursive owner can absorb a forward join without a new continuation. */
    private static JoinRegion sharedJoinOwner(Set<JoinTarget> dependencies) {
        JoinRegion owner = null;
        for (var dependency : dependencies) {
            var candidate = dependency.region.owner;
            if (!candidate.recursive) continue;
            if (owner != null && owner != candidate) return null;
            owner = candidate;
        }
        if (owner == null) return null;
        for (var dependency : dependencies) {
            var available = owner;
            while (available != null && available != dependency.region.owner) available = available.parent;
            if (available == null) return null;
        }
        return owner;
    }

    /** Rebind physical identities, including another lifted join's original capture sources. */
    private static Scope capturedJoinScope(Scope scope, Map<Integer, Local> copies) {
        var result = scope.child();
        result.locals.replaceAll((id, local) -> copies.getOrDefault(local.id, local));
        result.tuples.replaceAll((id, tuple) -> new AggregateLocal(tuple.proof,
            tuple.fields.stream().map(local -> copies.getOrDefault(local.id, local)).toList()));
        result.joins.replaceAll((id, target) -> new JoinTarget(target.region, target.index, target.parameters,
            target.locals, target.entryStrict, target.result, target.captures.stream()
                .map(capture -> new CapturedLocal(copies.getOrDefault(capture.source.id, capture.source), capture.destination))
                .toList(), target.selectorIndex, target.source));
        return result;
    }

    private static Object lexicalBinding(Scope scope, String id) {
        if (scope.locals.containsKey(id)) return scope.locals.get(id);
        if (scope.tuples.containsKey(id)) return scope.tuples.get(id);
        return scope.joins.get(id); // null records an unshadowed global.
    }

    private JoinSource joinSource(CoreJoinDefinition definition, Scope scope) {
        var free = new LinkedHashSet<>(CoreFreeVariables.coreFreeVariables(definition.body()));
        for (var parameter : definition.parameters()) free.remove((String) parameter.get("id"));
        var bindings = new LinkedHashMap<String, Object>();
        for (String id : free) bindings.put(id, lexicalBinding(scope, id));
        return new JoinSource(definition, scope.function, bindings, nextJoinSource++);
    }

    /** Close only inert forward exits whose original lexical views remain available. */
    private static List<Object> closedJoinRegion(List<Object> expression, Scope scope) {
        var pending = new ArrayList<JoinTarget>();
        for (String id : CoreFreeVariables.coreFreeVariables(expression)) {
            var target = scope.joins.get(id);
            if (target != null) pending.add(target);
        }
        var sources = new LinkedHashMap<Integer, JoinSource>();
        for (int i = 0; i < pending.size(); ++i) {
            var target = pending.get(i);
            var source = target.source;
            if (target.region.owner.recursive || source == null || source.function != scope.function) return null;
            if (sources.putIfAbsent(source.order, source) != null) continue;
            for (var binding : source.bindings.entrySet()) {
                // Identity includes the physical owner, proof, cell/entry contract,
                // aggregate components and zero-width bindings, not just a slot number.
                if (lexicalBinding(scope, binding.getKey()) != binding.getValue()) return null;
                if (binding.getValue() instanceof JoinTarget dependency) pending.add(dependency);
            }
        }
        var ordered = new ArrayList<>(sources.values());
        ordered.sort(java.util.Comparator.comparingInt(source -> source.order));
        List<Object> body = expression;
        for (int i = ordered.size() - 1; i >= 0; --i)
            body = List.of("let", false, List.of(ordered.get(i).definition.binding()), body);
        return body;
    }

    private Expression joinRegion(List<Map<String, Object>> group, List<Object> expression, boolean recursive,
            Scope scope, boolean tail) {
        CoreJoins.validate(group, expression, recursive);
        var definitions = CoreJoins.definitions(group);
        if (definitions == null) throw new RuntimeFault("Missing local join definitions");
        var shadowed = new LinkedHashSet<String>();
        if (recursive) for (var definition : definitions) shadowed.add(definition.getId());
        var capturedTupleFields = new LinkedHashMap<Integer, Local>();
        var capturedLocals = new LinkedHashMap<Integer, Local>();
        var dependencies = new LinkedHashSet<JoinTarget>();
        for (var definition : definitions) {
            var formals = new LinkedHashSet<String>();
            for (var parameter : definition.getParameters()) {
                var proof = CoreRepresentations.binder(parameter);
                CoreRepresentations.requireInput(proof);
                if (proof.isTypedTransport() && representation(parameter)) throw new RuntimeFault("Typed join formal must be unlifted");
                formals.add((String) parameter.get("id"));
            }
            for (String id : CoreFreeVariables.coreFreeVariables(definition.getBody())) {
                if (formals.contains(id) || shadowed.contains(id)) continue;
                var captured = scope.locals.get(id);
                if (captured != null && captured.id >= 0) capturedLocals.put(captured.id, captured);
                var dependency = scope.joins.get(id);
                if (dependency != null) {
                    dependencies.add(dependency);
                    for (var capture : dependency.captures) capturedLocals.put(capture.source.id, capture.source);
                }
                var tuple = scope.tuples.get(id);
                if (tuple == null) continue;
                CoreRepresentations.requireInput(tuple.proof);
                var leaves = ArgumentLayout.leaves(tuple.proof);
                if (tuple.fields.size() != leaves.size()) throw new RuntimeFault("Tuple join capture disagrees with its physical slots");
                for (int i = 0; i < tuple.fields.size(); ++i) {
                    var field = tuple.fields.get(i);
                    if (!field.proof.getPresent() || !TupleShape.compatible(leaves.get(i), field.proof))
                        throw new RuntimeFault("Tuple join capture has a mismatched leaf proof");
                    var previous = capturedTupleFields.get(field.id);
                    if (previous != null && !TupleShape.compatible(previous.proof, field.proof))
                        throw new RuntimeFault("Tuple join captures disagree on a shared physical slot");
                    capturedTupleFields.put(field.id, field);
                    capturedLocals.put(field.id, field);
                }
            }
        }
        // The enclosing Core validation requires all references to its joins to be
        // tail calls. Such a NonRec shares that owner's return continuation, not
        // merely its result representation. Ambiguous dependencies stay local.
        var owner = recursive ? null : sharedJoinOwner(dependencies);
        var region = new JoinRegion(recursive, scope.joinRegion, owner);
        var captures = new ArrayList<CapturedLocal>();
        var copies = new LinkedHashMap<Integer, Local>();
        if (owner != null) for (var source : capturedLocals.values()) {
            var copy = new Local(nextLocal++, source.name, source.primitive, source.proof,
                source.cell, source.entry, source.arityCertificate);
            copies.put(source.id, copy); captures.add(new CapturedLocal(source, copy));
        }
        var local = scope.child();
        local.joinRegion = region;
        var targets = new ArrayList<JoinTarget>();
        for (int i = 0; i < definitions.size(); ++i) {
            var definition = definitions.get(i);
            var entryStrict = CoreEntries.join(definition);
            var parameters = new ArrayList<List<Local>>();
            for (int index = 0; index < definition.getParameters().size(); ++index) {
                var parameter = definition.getParameters().get(index);
                var proof = evaluatedProof(CoreRepresentations.binder(parameter), !representation(parameter) || entryStrict[index]);
                String id = (String) parameter.get("id");
                var fields = new ArrayList<Local>();
                if (proof.isTypedTransport()) {
                    var leaves = ArgumentLayout.leaves(proof);
                    for (int lane = 0; lane < leaves.size(); ++lane) {
                        var field = leaves.get(lane);
                        fields.add(new Local(nextLocal++, id + " lane " + lane, field.isLong(), field));
                    }
                } else fields.add(new Local(nextLocal++, id,
                    proof.getPresent() ? proof.isLong() : !representation(parameter) && !Boolean.TRUE.equals(parameter.get("coercion")), proof));
                parameters.add(fields);
            }
            var target = new JoinTarget(region, i, definition.getParameters(), parameters, entryStrict,
                definition.getResult(), captures, region.owner.targets.size(),
                recursive ? null : joinSource(definition, scope));
            region.owner.targets.add(target); region.owner.bodies.add(null);
            targets.add(target);
            local.bindJoin(definition.getId(), target);
        }
        localJoinCount += targets.size();
        var bodies = new ArrayList<Expression>();
        for (int i = 0; i < definitions.size(); ++i) {
            var definition = definitions.get(i);
            var bodyScope = owner == null ? (recursive ? local : scope).child() : capturedJoinScope(scope, copies);
            bodyScope.joinRegion = region;
            var parameters = targets.get(i).locals;
            for (int index = 0; index < parameters.size(); ++index) {
                var fields = parameters.get(index);
                var raw = definition.getParameters().get(index);
                var formal = CoreRepresentations.binder(raw);
                if (formal.isTypedTransport()) bodyScope.bindTuple((String) raw.get("id"), evaluatedProof(formal, true), fields);
                else {
                    if (fields.size() != 1) throw new IllegalArgumentException("List has more than one element.");
                    bodyScope.bindLocal(fields.getFirst().name, fields.getFirst());
                }
            }
            region.compilingIndex = i;
            bodyScope = bodyScope.withSource(sources.binding(definition.getBinding(), scope.source));
            var context = scope.function;
            boolean outlined = !recursive && !context.preparingCaseRegion
                    && context.caseRegions.size() < BytecodeCaseRegion.MAX_REGIONS
                    && joinRegionWork(definition.getBody(), 64) >= 64
                    && CoreFreeVariables.coreFreeVariables(definition.getBody()).stream().noneMatch(bodyScope.joins::containsKey);
            // Preserve normal case preparation first: an optional whole-body
            // side can itself exceed local capacity. Existing nested plans take
            // precedence; never wrap one in another region in this activation.
            int regionMark = context.caseRegions.size();
            Expression body = compile(definition.getBody(), bodyScope, tail);
            if (outlined && context.caseRegions.size() == regionMark) {
                context.preparingCaseRegion = true;
                try {
                    // The existing join transfer has already bound and demanded
                    // its arguments. Capture those exact locals, not a new call
                    // to the join or a restart of an already-saved activation.
                    int rootMark = roots.size(), joinMark = localJoinCount;
                    try {
                        var side = function("join body " + definition.getId(), List.of(), definition.getBody(), bodyScope,
                                body.proof(), new boolean[0], tail, true, false);
                        if (context.caseEmission == null) context.caseEmission = new CaseRegionEmission();
                        int index = context.caseRegions.size();
                        context.caseRegions.add(new BytecodeCaseRegion(side.target, side.captureLayout, tail));
                        body = preparedRegion(body, List.of(side), bodyScope, tail, index,
                                new ProvenExpression(e -> e.builder.emitLoadConstant(thc.runtime.Unit.INSTANCE), UNKNOWN));
                    } catch (BytecodeEncodingException failure) {
                        if (!localIndexOverflow(failure)) throw failure;
                        roots.subList(rootMark, roots.size()).clear();
                        localJoinCount = joinMark;
                    }
                } finally {
                    context.preparingCaseRegion = false;
                }
            }
            var proven = new ProvenExpression(body, body.proof().refine(evaluatedProof(definition.getResult(), false)));
            bodies.add(proven); region.owner.bodies.set(targets.get(i).selectorIndex, proven);
        }
        region.compilingIndex = -1;
        var entry = compile(expression, local, tail);
        var proof = entry.proof().refine(evaluatedProof(CoreRepresentations.expression(expression), false));
        for (var body : bodies) TupleShape.requireCompatible(proof, body.proof(), false);
        return new ProvenExpression(new ResultExpression((e, destination) -> {
            if (proof.isTypedTransport() != (destination != null)) throw new RuntimeFault("Join result destination disagrees with its representation");
            for (var field : capturedTupleFields.values())
                if (!e.locals.containsKey(field.id)) throw new RuntimeFault("Tuple join capture escaped its lexical slots");
            if (owner != null) {
                // Definitions are inert. Their entry stays at its original source
                // position; only transfers enter the common owner's dispatcher.
                emitResult(entry, e, destination);
                return;
            }
            var b = e.builder;
            b.beginBlock();
            var result = destination == null ? b.createLocal("join result", null) : null;
            var selector = recursive ? b.createLocal("join selector", "primitive") : null;
            var exit = b.createLabel();
            var storage = new LinkedHashMap<Integer, Local>();
            for (var target : region.targets) {
                for (var fields : target.locals) for (var field : fields) storage.put(field.id, field);
                for (var capture : target.captures) storage.put(capture.destination.id, capture.destination);
            }
            for (var field : storage.values())
                e.locals.put(field.id, b.createLocal(field.name, field.primitive ? "primitive" : "object"));
            if (selector != null) {
                b.beginStoreLocal(selector); b.emitLoadConstant(-1L); b.endStoreLocal();
                b.beginWhile(); b.emitLoadConstant(true); b.beginBlock();
            }
            var next = recursive ? b.createLabel() : null;
            var dispatch = recursive ? b.createLabel() : null;
            var labels = new ArrayList<BytecodeLabel>();
            for (var ignored : region.targets) labels.add(b.createLabel());
            var active = new JoinEmission(selector, next, dispatch, labels);
            e.joins.put(region, active);
            if (selector != null) for (int i = 0; i < region.targets.size(); ++i) {
                b.beginIfThen();
                b.beginMatchLiteral((long) i); b.emitLoadLocal(selector); b.endMatchLiteral();
                b.emitBranch(labels.get(i));
                b.endIfThen();
            }
            if (result != null) { b.beginStoreLocal(result); entry.emit(e); b.endStoreLocal(); }
            else entry.emitTuple(e, Objects.requireNonNull(destination));
            b.emitBranch(exit);
            for (int i = 0; i < region.targets.size(); ++i) {
                b.emitLabel(labels.get(i)); active.emittedIndex = i;
                TupleShape.requireCompatible(proof, region.bodies.get(i).proof(), false);
                if (result != null) { b.beginStoreLocal(result); region.bodies.get(i).emit(e); b.endStoreLocal(); }
                else region.bodies.get(i).emitTuple(e, Objects.requireNonNull(destination));
                b.emitBranch(exit);
            }
            if (next != null) {
                b.emitLabel(next);
                if (enableAsync) emitAsyncPoll(e);
                b.emitLabel(Objects.requireNonNull(dispatch));
                b.endBlock(); b.endWhile();
            }
            b.emitLabel(exit);
            if (result != null) b.emitLoadLocal(result);
            b.endBlock();
            e.joins.remove(region);
            for (var field : storage.values()) e.locals.remove(field.id);
        }), evaluatedProof(proof, entry.proof().getEvaluated() && allEvaluated(bodies)));
    }

    private Expression vectorWord8Primitive(String name, List<Expression> operands) {
        if (name.equals("unpackWord8X16#")) return tupleExpression(CoreVectors.unpackedWord8, (e, destination) -> {
            e.builder.beginVectorWord8Unpack(destination.get(0), destination.get(1), destination.get(2), destination.get(3), destination.get(4), destination.get(5), destination.get(6), destination.get(7), destination.get(8), destination.get(9), destination.get(10), destination.get(11), destination.get(12), destination.get(13), destination.get(14), destination.get(15));
            operands.getFirst().emit(e);
            e.builder.endVectorWord8Unpack();
        });
        return new ProvenExpression(e -> {
            var b = e.builder;
            switch (name) {
                case "packWord8X16#" -> {
                    b.beginBlock();
                    var lanes = new ArrayList<BytecodeLocal>();
                    for (int i = 0; i < 16; ++i) lanes.add(b.createLocal());
                    operands.getFirst().emitTuple(e, lanes);
                    b.beginVectorWord8Pack(); for (var lane : lanes) b.emitLoadLocal(lane); b.endVectorWord8Pack();
                    b.endBlock();
                }
                case "broadcastWord8X16#" -> { b.beginVectorWord8Broadcast(); operands.getFirst().emit(e); b.endVectorWord8Broadcast(); }
                default -> {
                    int operation = switch (name) {
                        case "plusWord8X16#" -> 0;
                        case "minusWord8X16#" -> 1;
                        case "timesWord8X16#" -> 2;
                        default -> throw new IllegalStateException("Invalid Word8X16 operation");
                    };
                    b.beginVectorWord8Binary(operation);
                    for (var operand : operands) operand.emit(e);
                    b.endVectorWord8Binary();
                }
            }
        }, CoreVectors.proofWord8);
    }

    private Expression vector8Primitive(String name, List<Expression> operands) {
        if (name.equals("unpackInt8X16#")) return tupleExpression(CoreVectors.unpacked8, (e, destination) -> {
            e.builder.beginVector8Unpack(destination.get(0), destination.get(1), destination.get(2), destination.get(3), destination.get(4), destination.get(5), destination.get(6), destination.get(7), destination.get(8), destination.get(9), destination.get(10), destination.get(11), destination.get(12), destination.get(13), destination.get(14), destination.get(15));
            operands.getFirst().emit(e);
            e.builder.endVector8Unpack();
        });
        return new ProvenExpression(e -> {
            var b = e.builder;
            switch (name) {
                case "packInt8X16#" -> {
                    b.beginBlock();
                    var lanes = new ArrayList<BytecodeLocal>();
                    for (int i = 0; i < 16; ++i) lanes.add(b.createLocal());
                    operands.getFirst().emitTuple(e, lanes);
                    b.beginVector8Pack(); for (var lane : lanes) b.emitLoadLocal(lane); b.endVector8Pack();
                    b.endBlock();
                }
                case "broadcastInt8X16#" -> { b.beginVector8Broadcast(); operands.getFirst().emit(e); b.endVector8Broadcast(); }
                case "negateInt8X16#" -> { b.beginVector8Negate(); operands.getFirst().emit(e); b.endVector8Negate(); }
                default -> {
                    int operation = switch (name) {
                        case "plusInt8X16#" -> 0;
                        case "minusInt8X16#" -> 1;
                        case "timesInt8X16#" -> 2;
                        default -> throw new IllegalStateException("Invalid Int8X16 operation");
                    };
                    b.beginVector8Binary(operation);
                    for (var operand : operands) operand.emit(e);
                    b.endVector8Binary();
                }
            }
        }, CoreVectors.proof8);
    }

    private Expression vectorWord32Primitive(String name, List<Expression> operands) {
        if (name.equals("unpackWord32X4#")) return tupleExpression(CoreVectors.unpackedWord32, (e, destination) -> {
            e.builder.beginVectorWord32Unpack(destination.get(0), destination.get(1), destination.get(2), destination.get(3));
            operands.getFirst().emit(e);
            e.builder.endVectorWord32Unpack();
        });
        return new ProvenExpression(e -> {
            var b = e.builder;
            switch (name) {
                case "packWord32X4#" -> {
                    b.beginBlock();
                    var lanes = new ArrayList<BytecodeLocal>();
                    for (int i = 0; i < 4; ++i) lanes.add(b.createLocal());
                    operands.getFirst().emitTuple(e, lanes);
                    b.beginVectorWord32Pack(); for (var lane : lanes) b.emitLoadLocal(lane); b.endVectorWord32Pack();
                    b.endBlock();
                }
                case "broadcastWord32X4#" -> { b.beginVectorWord32Broadcast(); operands.getFirst().emit(e); b.endVectorWord32Broadcast(); }
                default -> {
                    int operation = switch (name) {
                        case "plusWord32X4#" -> 0;
                        case "minusWord32X4#" -> 1;
                        case "timesWord32X4#" -> 2;
                        default -> throw new IllegalStateException("Invalid Word32X4 operation");
                    };
                    b.beginVectorWord32Binary(operation);
                    for (var operand : operands) operand.emit(e);
                    b.endVectorWord32Binary();
                }
            }
        }, CoreVectors.proofWord32);
    }

    private Expression vectorWord16Primitive(String name, List<Expression> operands) {
        if (name.equals("unpackWord16X8#")) return tupleExpression(CoreVectors.unpackedWord16, (e, destination) -> {
            e.builder.beginVectorWord16Unpack(destination.get(0), destination.get(1), destination.get(2), destination.get(3), destination.get(4), destination.get(5), destination.get(6), destination.get(7));
            operands.getFirst().emit(e);
            e.builder.endVectorWord16Unpack();
        });
        return new ProvenExpression(e -> {
            var b = e.builder;
            switch (name) {
                case "packWord16X8#" -> {
                    b.beginBlock();
                    var lanes = new ArrayList<BytecodeLocal>();
                    for (int i = 0; i < 8; ++i) lanes.add(b.createLocal());
                    operands.getFirst().emitTuple(e, lanes);
                    b.beginVectorWord16Pack(); for (var lane : lanes) b.emitLoadLocal(lane); b.endVectorWord16Pack();
                    b.endBlock();
                }
                case "broadcastWord16X8#" -> { b.beginVectorWord16Broadcast(); operands.getFirst().emit(e); b.endVectorWord16Broadcast(); }
                default -> {
                    int operation = switch (name) {
                        case "plusWord16X8#" -> 0;
                        case "minusWord16X8#" -> 1;
                        case "timesWord16X8#" -> 2;
                        default -> throw new IllegalStateException("Invalid Word16X8 operation");
                    };
                    b.beginVectorWord16Binary(operation);
                    for (var operand : operands) operand.emit(e);
                    b.endVectorWord16Binary();
                }
            }
        }, CoreVectors.proofWord16);
    }

    private Expression vector16Primitive(String name, List<Expression> operands) {
        if (name.equals("unpackInt16X8#")) return tupleExpression(CoreVectors.unpacked16, (e, destination) -> {
            e.builder.beginVector16Unpack(destination.get(0), destination.get(1), destination.get(2), destination.get(3), destination.get(4), destination.get(5), destination.get(6), destination.get(7));
            operands.getFirst().emit(e);
            e.builder.endVector16Unpack();
        });
        return new ProvenExpression(e -> {
            var b = e.builder;
            switch (name) {
                case "packInt16X8#" -> {
                    b.beginBlock();
                    var lanes = new ArrayList<BytecodeLocal>();
                    for (int i = 0; i < 8; ++i) lanes.add(b.createLocal());
                    operands.getFirst().emitTuple(e, lanes);
                    b.beginVector16Pack(); for (var lane : lanes) b.emitLoadLocal(lane); b.endVector16Pack();
                    b.endBlock();
                }
                case "broadcastInt16X8#" -> { b.beginVector16Broadcast(); operands.getFirst().emit(e); b.endVector16Broadcast(); }
                case "negateInt16X8#" -> { b.beginVector16Negate(); operands.getFirst().emit(e); b.endVector16Negate(); }
                default -> {
                    int operation = switch (name) {
                        case "plusInt16X8#" -> 0;
                        case "minusInt16X8#" -> 1;
                        case "timesInt16X8#" -> 2;
                        default -> throw new IllegalStateException("Invalid Int16X8 operation");
                    };
                    b.beginVector16Binary(operation);
                    for (var operand : operands) operand.emit(e);
                    b.endVector16Binary();
                }
            }
        }, CoreVectors.proof16);
    }

    private Expression vector32Primitive(String name, List<Expression> operands) {
        if (name.equals("unpackInt32X4#")) return tupleExpression(CoreVectors.unpacked32, (e, destination) -> {
            e.builder.beginVector32Unpack(destination.get(0), destination.get(1), destination.get(2), destination.get(3));
            operands.getFirst().emit(e);
            e.builder.endVector32Unpack();
        });
        return new ProvenExpression(e -> {
            var b = e.builder;
            switch (name) {
                case "packInt32X4#" -> {
                    b.beginBlock();
                    var lanes = new ArrayList<BytecodeLocal>();
                    for (int i = 0; i < 4; ++i) lanes.add(b.createLocal());
                    operands.getFirst().emitTuple(e, lanes);
                    b.beginVector32Pack(); for (var lane : lanes) b.emitLoadLocal(lane); b.endVector32Pack();
                    b.endBlock();
                }
                case "broadcastInt32X4#" -> { b.beginVector32Broadcast(); operands.getFirst().emit(e); b.endVector32Broadcast(); }
                case "negateInt32X4#" -> { b.beginVector32Negate(); operands.getFirst().emit(e); b.endVector32Negate(); }
                case "timesInt32X4#" -> { b.beginVector32Multiply(); for (var operand : operands) operand.emit(e); b.endVector32Multiply(); }
                default -> {
                    b.beginVector32Binary(name.equals("minusInt32X4#"));
                    for (var operand : operands) operand.emit(e);
                    b.endVector32Binary();
                }
            }
        }, CoreVectors.proof32);
    }

    private Expression vectorFloatPrimitive(String name, List<Expression> operands) {
        if (name.equals("unpackFloatX4#")) return tupleExpression(CoreVectors.unpackedFloat, (e, destination) -> {
            e.builder.beginVectorFloatUnpack(destination.get(0), destination.get(1), destination.get(2), destination.get(3));
            operands.getFirst().emit(e);
            e.builder.endVectorFloatUnpack();
        });
        return new ProvenExpression(e -> {
            var b = e.builder;
            switch (name) {
                case "packFloatX4#" -> {
                    b.beginBlock();
                    var lanes = new ArrayList<BytecodeLocal>();
                    for (int i = 0; i < 4; ++i) lanes.add(b.createLocal());
                    operands.getFirst().emitTuple(e, lanes);
                    b.beginVectorFloatPack(); for (var lane : lanes) b.emitLoadLocal(lane); b.endVectorFloatPack();
                    b.endBlock();
                }
                case "broadcastFloatX4#" -> { b.beginVectorFloatBroadcast(); operands.getFirst().emit(e); b.endVectorFloatBroadcast(); }
                default -> {
                    if (CoreVectors.fusedFloat.contains(name)) {
                        b.beginVectorFloatFused(CoreVectors.fusedFloat.indexOf(name));
                        for (var operand : operands) operand.emit(e);
                        b.endVectorFloatFused();
                    } else {
                    int operation = switch (name) {
                        case "plusFloatX4#" -> 0;
                        case "minusFloatX4#" -> 1;
                        case "timesFloatX4#" -> 2;
                        default -> throw new IllegalStateException("Invalid FloatX4 operation");
                    };
                    b.beginVectorFloatBinary(operation);
                    for (var operand : operands) operand.emit(e);
                    b.endVectorFloatBinary();
                    }
                }
            }
        }, CoreVectors.proofFloat);
    }

    private Expression vectorDoublePrimitive(String name, List<Expression> operands) {
        if (name.equals("unpackDoubleX2#")) return tupleExpression(CoreVectors.unpackedDouble, (e, destination) -> {
            e.builder.beginVectorDoubleUnpack(destination.get(0), destination.get(1));
            operands.getFirst().emit(e);
            e.builder.endVectorDoubleUnpack();
        });
        return new ProvenExpression(e -> {
            var b = e.builder;
            switch (name) {
                case "packDoubleX2#" -> {
                    b.beginBlock();
                    var lanes = new ArrayList<BytecodeLocal>();
                    for (int i = 0; i < 2; ++i) lanes.add(b.createLocal());
                    operands.getFirst().emitTuple(e, lanes);
                    b.beginVectorDoublePack(); for (var lane : lanes) b.emitLoadLocal(lane); b.endVectorDoublePack();
                    b.endBlock();
                }
                case "broadcastDoubleX2#" -> { b.beginVectorDoubleBroadcast(); operands.getFirst().emit(e); b.endVectorDoubleBroadcast(); }
                default -> {
                    if (CoreVectors.fusedDouble.contains(name)) {
                        b.beginVectorDoubleFused(CoreVectors.fusedDouble.indexOf(name));
                        for (var operand : operands) operand.emit(e);
                        b.endVectorDoubleFused();
                    } else {
                    int operation = switch (name) {
                        case "plusDoubleX2#" -> 0;
                        case "minusDoubleX2#" -> 1;
                        case "timesDoubleX2#" -> 2;
                        default -> throw new IllegalStateException("Invalid DoubleX2 operation");
                    };
                    b.beginVectorDoubleBinary(operation);
                    for (var operand : operands) operand.emit(e);
                    b.endVectorDoubleBinary();
                    }
                }
            }
        }, CoreVectors.proofDouble);
    }

    private Expression vectorMemory(VectorMemoryOp operation, List<Expression> operands) {
        return new ProvenExpression(e -> {
            var b = e.builder;
            switch (operation.getFamily()) {
                case INT8, WORD8 -> {
                    if (operation.isAddress()) {
                        if (operation.isWrite()) {
                            b.beginWriteVectorByteAddress(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endWriteVectorByteAddress();
                        }
                        else if (operation.isRead()) {
                            b.beginReadVectorByteAddress(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endReadVectorByteAddress();
                        }
                        else {
                            b.beginIndexVectorByteAddress(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endIndexVectorByteAddress();
                        }
                    } else {
                        if (operation.isWrite()) {
                            b.beginWriteVectorByteArray(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endWriteVectorByteArray();
                        }
                        else if (operation.isRead()) {
                            b.beginReadVectorByteArray(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endReadVectorByteArray();
                        }
                        else {
                            b.beginIndexVectorByteArray(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endIndexVectorByteArray();
                        }
                    }
                }
                case INT16, WORD16 -> {
                    if (operation.isAddress()) {
                        if (operation.isWrite()) {
                            b.beginWriteVectorShortAddress(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endWriteVectorShortAddress();
                        }
                        else if (operation.isRead()) {
                            b.beginReadVectorShortAddress(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endReadVectorShortAddress();
                        }
                        else {
                            b.beginIndexVectorShortAddress(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endIndexVectorShortAddress();
                        }
                    } else {
                        if (operation.isWrite()) {
                            b.beginWriteVectorShortArray(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endWriteVectorShortArray();
                        }
                        else if (operation.isRead()) {
                            b.beginReadVectorShortArray(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endReadVectorShortArray();
                        }
                        else {
                            b.beginIndexVectorShortArray(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endIndexVectorShortArray();
                        }
                    }
                }
                case INT64, WORD64 -> {
                    if (operation.isAddress()) {
                        if (operation.isWrite()) {
                            b.beginWriteVectorLongAddress(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endWriteVectorLongAddress();
                        }
                        else if (operation.isRead()) {
                            b.beginReadVectorLongAddress(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endReadVectorLongAddress();
                        }
                        else {
                            b.beginIndexVectorLongAddress(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endIndexVectorLongAddress();
                        }
                    } else {
                        if (operation.isWrite()) {
                            b.beginWriteVectorLongArray(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endWriteVectorLongArray();
                        }
                        else if (operation.isRead()) {
                            b.beginReadVectorLongArray(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endReadVectorLongArray();
                        }
                        else {
                            b.beginIndexVectorLongArray(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endIndexVectorLongArray();
                        }
                    }
                }
                case INT32 -> {
                    if (operation.isAddress()) {
                        if (operation.isWrite()) {
                            b.beginWriteVectorIntAddress(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endWriteVectorIntAddress();
                        }
                        else if (operation.isRead()) {
                            b.beginReadVectorIntAddress(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endReadVectorIntAddress();
                        }
                        else {
                            b.beginIndexVectorIntAddress(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endIndexVectorIntAddress();
                        }
                    } else {
                        if (operation.isWrite()) {
                            b.beginWriteVector32Array(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endWriteVector32Array();
                        }
                        else if (operation.isRead()) {
                            b.beginReadVector32Array(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endReadVector32Array();
                        }
                        else {
                            b.beginIndexVector32Array(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endIndexVector32Array();
                        }
                    }
                }
                case WORD32 -> {
                    if (operation.isAddress()) {
                        if (operation.isWrite()) {
                            b.beginWriteVectorIntAddress(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endWriteVectorIntAddress();
                        }
                        else if (operation.isRead()) {
                            b.beginReadVectorIntAddress(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endReadVectorIntAddress();
                        }
                        else {
                            b.beginIndexVectorIntAddress(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endIndexVectorIntAddress();
                        }
                    } else {
                        if (operation.isWrite()) {
                            b.beginWriteVectorWord32Array(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endWriteVectorWord32Array();
                        }
                        else if (operation.isRead()) {
                            b.beginReadVectorWord32Array(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endReadVectorWord32Array();
                        }
                        else {
                            b.beginIndexVectorWord32Array(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endIndexVectorWord32Array();
                        }
                    }
                }
                case FLOAT32 -> {
                    if (operation.isAddress()) {
                        if (operation.isWrite()) {
                            b.beginWriteVectorFloatAddress(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endWriteVectorFloatAddress();
                        }
                        else if (operation.isRead()) {
                            b.beginReadVectorFloatAddress(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endReadVectorFloatAddress();
                        }
                        else {
                            b.beginIndexVectorFloatAddress(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endIndexVectorFloatAddress();
                        }
                    } else {
                        if (operation.isWrite()) {
                            b.beginWriteVectorFloatArray(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endWriteVectorFloatArray();
                        }
                        else if (operation.isRead()) {
                            b.beginReadVectorFloatArray(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endReadVectorFloatArray();
                        }
                        else {
                            b.beginIndexVectorFloatArray(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endIndexVectorFloatArray();
                        }
                    }
                }
                case DOUBLE64 -> {
                    if (operation.isAddress()) {
                        if (operation.isWrite()) {
                            b.beginWriteVectorDoubleAddress(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endWriteVectorDoubleAddress();
                        }
                        else if (operation.isRead()) {
                            b.beginReadVectorDoubleAddress(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endReadVectorDoubleAddress();
                        }
                        else {
                            b.beginIndexVectorDoubleAddress(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endIndexVectorDoubleAddress();
                        }
                    } else {
                        if (operation.isWrite()) {
                            b.beginWriteVectorDoubleArray(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endWriteVectorDoubleArray();
                        }
                        else if (operation.isRead()) {
                            b.beginReadVectorDoubleArray(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endReadVectorDoubleArray();
                        }
                        else {
                            b.beginIndexVectorDoubleArray(operation.getScalarOffset(), operation.getVectorBytes());
                            for (var operand : operands) operand.emit(e);
                            b.endIndexVectorDoubleArray();
                        }
                    }
                }
            }
        }, operation.isWrite() ? CoreVectorMemory.getStateProof() : operation.getVectorProof());
    }

    private Expression vectorPrimitive(String name, List<Expression> operands, int[] shuffleIndices) {
        if (GeneratedVectors.operations.contains(name)) return generatedVectorPrimitive(name, operands, shuffleIndices);
        if (CoreVectors.operationsWord32.contains(name)) return vectorWord32Primitive(name, operands);
        if (CoreVectors.operationsWord16.contains(name)) return vectorWord16Primitive(name, operands);
        if (CoreVectors.operationsWord8.contains(name)) return vectorWord8Primitive(name, operands);
        if (CoreVectors.operations8.contains(name)) return vector8Primitive(name, operands);
        if (CoreVectors.operations16.contains(name)) return vector16Primitive(name, operands);
        if (CoreVectors.operationsDouble.contains(name)) return vectorDoublePrimitive(name, operands);
        if (CoreVectors.operationsFloat.contains(name)) return vectorFloatPrimitive(name, operands);
        if (CoreVectors.fusedFloat8.contains(name)) return new ProvenExpression(e -> {
            e.builder.beginVectorFloat8Fused(CoreVectors.fusedFloat8.indexOf(name));
            for (var operand : operands) operand.emit(e);
            e.builder.endVectorFloat8Fused();
        }, GeneratedVectors.proofFloatX8);
        if (CoreVectors.fusedFloat16.contains(name)) return new ProvenExpression(e -> {
            e.builder.beginVectorFloat16Fused(CoreVectors.fusedFloat16.indexOf(name));
            for (var operand : operands) operand.emit(e);
            e.builder.endVectorFloat16Fused();
        }, GeneratedVectors.proofFloatX16);
        if (CoreVectors.fusedDouble4.contains(name)) return new ProvenExpression(e -> {
            e.builder.beginVectorDouble4Fused(CoreVectors.fusedDouble4.indexOf(name));
            for (var operand : operands) operand.emit(e);
            e.builder.endVectorDouble4Fused();
        }, GeneratedVectors.proofDoubleX4);
        if (CoreVectors.fusedDouble8.contains(name)) return new ProvenExpression(e -> {
            e.builder.beginVectorDouble8Fused(CoreVectors.fusedDouble8.indexOf(name));
            for (var operand : operands) operand.emit(e);
            e.builder.endVectorDouble8Fused();
        }, GeneratedVectors.proofDoubleX8);
        if (CoreVectors.operations32.contains(name)) return vector32Primitive(name, operands);
        if (name.equals("unpackInt64X2#")) return tupleExpression(CoreVectors.unpacked, (e, destination) -> {
            e.builder.beginVectorUnpack(destination.get(0), destination.get(1));
            operands.getFirst().emit(e);
            e.builder.endVectorUnpack();
        });
        return new ProvenExpression(e -> {
            var b = e.builder;
            switch (name) {
                case "packInt64X2#" -> {
                    b.beginBlock();
                    var lanes = List.of(b.createLocal(), b.createLocal());
                    operands.getFirst().emitTuple(e, lanes);
                    b.beginVectorPack(); for (var lane : lanes) b.emitLoadLocal(lane); b.endVectorPack();
                    b.endBlock();
                }
                case "broadcastInt64X2#" -> { b.beginVectorBroadcast(); operands.getFirst().emit(e); b.endVectorBroadcast(); }
                case "negateInt64X2#" -> { b.beginVectorNegate(); operands.getFirst().emit(e); b.endVectorNegate(); }
                default -> {
                    b.beginVectorBinary(name.equals("minusInt64X2#"));
                    for (var operand : operands) operand.emit(e);
                    b.endVectorBinary();
                }
            }
        }, CoreVectors.proof);
    }

    private Expression tupleApplication(TupleShape shape, Expression function, List<Expression> arguments, Scope scope, boolean tail) {
        var proofs = new ArrayList<CoreRepresentation>();
        for (var argument : arguments) proofs.add(argument.proof());
        var inputLayout = ArgumentLayout.fromProofs(proofs);
        var context = scope.function;
        if (tail && !context.passThrough) context.mayLoop = true;
        return tupleExpression(shape.getProof(), (e, destination) -> {
            var b = e.builder;
            if (!tail) {
                if (resumable) checkpointedTupleApplication(e, shape, function, arguments, inputLayout, destination, false, false);
                else if (inputLayout != null && inputLayout.getRequiresTyped()) {
                    b.beginStoreLocal(b.createLocal("typed tuple call", null));
                    typedArguments(e, function, arguments, inputLayout, false, tupleSlots(shape, destination), false);
                    b.endStoreLocal();
                } else if (inputLayout == null) {
                    b.beginApplyTuple(tupleSlots(shape, destination), arguments.size(), metrics);
                    requireClosure(function).emit(e); for (var argument : arguments) argument.emit(e);
                    b.endApplyTuple();
                } else compactArguments(e, function, arguments, inputLayout, (fn, values) -> {
                    b.beginApplyCompactTuple(tupleSlots(shape, destination), inputLayout, metrics);
                    b.emitLoadLocal(fn); for (var value : values) b.emitLoadLocal(value);
                    b.endApplyCompactTuple();
                });
            } else {
                b.beginBlock();
                var result = b.createLocal("tuple tail result", FrameSlotKind.Object);
                b.beginStaticStoreObject(result);
                if (resumable) checkpointedTupleApplication(e, shape, function, arguments, inputLayout, destination, true,
                    !context.passThrough && !delimited && inputLayout != null && inputLayout.getRequiresTyped()
                        && TypedInputs.supportsTypedSelf(context.inputLayout, context.entryStrict, inputLayout));
                else if (inputLayout != null && inputLayout.getRequiresTyped())
                    typedArguments(e, function, arguments, inputLayout, true, tupleSlots(shape, destination),
                        !context.passThrough && TypedInputs.supportsTypedSelf(context.inputLayout, context.entryStrict, inputLayout));
                else if (inputLayout == null) {
                    b.beginTailApplyTuple(tupleSlots(shape, destination), arguments.size(), metrics);
                    requireClosure(function).emit(e); for (var argument : arguments) argument.emit(e);
                    b.endTailApplyTuple();
                } else compactArguments(e, function, arguments, inputLayout, (fn, values) -> {
                    b.beginTailApplyCompactTuple(tupleSlots(shape, destination), inputLayout, metrics);
                    b.emitLoadLocal(fn); for (var value : values) b.emitLoadLocal(value);
                    b.endTailApplyCompactTuple();
                });
                b.endStaticStoreObject();
                if (resumable) {
                    // Propagate saved tail state before inspecting the normal return value.
                    b.beginIfThen();
                    b.beginIsTailReentry(true); b.emitStaticLoadObject(result); b.endIsTailReentry();
                    b.beginBlock(); b.beginReturn(); b.emitStaticLoadObject(result); b.endReturn(); b.endBlock();
                    b.endIfThen();
                }
                if (!context.passThrough) {
                    b.beginIfThenElse();
                    b.beginIsTailReentry(false); b.emitStaticLoadObject(result); b.endIsTailReentry();
                    b.beginBlock();
                    restoreTailArguments(e, context, result);
                    b.emitBranch(Objects.requireNonNull(e.continueLabel));
                    b.endBlock(); b.beginBlock(); b.endBlock();
                    b.endIfThenElse();
                }
                b.endBlock();
            }
        });
    }

    /** The final exact tuple callee owns the destination; earlier stages return scalar closures. */
    private void checkpointedTupleCall(Emission e, BytecodeTupleSlots slots, BytecodeLocal fn,
            List<BytecodeLocal> values, ArgumentLayout inputLayout, int arity, BytecodeLocal callerMask) {
        var b = e.builder;
        var suspended = b.createLocal("captured tuple suspension", FrameSlotKind.Object);
        b.beginTryCatch();
        if (inputLayout != null && inputLayout.getRequiresTyped()) {
            var source = new BytecodeInputSource(inputLayout, accessors(values));
            b.beginStoreLocal(b.createLocal("typed tuple completion", "object"));
            b.beginApplyTypedInputTuple(source, slots, false, metrics);
            b.emitLoadLocal(fn); b.endApplyTypedInputTuple();
            b.endStoreLocal();
        } else if (inputLayout == null) {
            b.beginApplyTupleCheckpoint(slots, arity, metrics);
            b.emitLoadLocal(fn); for (var value : values) b.emitLoadLocal(value);
            b.endApplyTupleCheckpoint();
        } else {
            b.beginApplyCompactTupleCheckpoint(slots, inputLayout, metrics);
            b.emitLoadLocal(fn); for (var value : values) b.emitLoadLocal(value);
            b.endApplyCompactTupleCheckpoint();
        }
        b.beginBlock();
        b.beginStaticStoreObject(suspended);
        b.beginCallSuspensionOnly(); b.emitLoadException(); b.endCallSuspensionOnly();
        b.endStaticStoreObject();
        b.beginResumeTupleApplication(slots);
        b.emitStaticLoadObject(suspended);
        b.beginReenterCallMask();
        beginAnnotationYield(e);
        b.beginParkCallMask();
        b.emitStaticLoadObject(suspended);
        b.emitStaticLoadObject(Objects.requireNonNull(e.checkpointRootEntry));
        b.emitStaticLoadObject(callerMask);
        b.endParkCallMask();
        endAnnotationYield(e);
        b.emitStaticLoadObject(callerMask);
        b.endReenterCallMask();
        b.endResumeTupleApplication();
        b.endBlock(); b.endTryCatch();
    }

    /** The exact tuple tail writes its destination or forwards a trusted callee Yield. */
    private void savedTailTuple(Emission e, BytecodeTupleSlots slots, BytecodeLocal fn,
            List<BytecodeLocal> values, ArgumentLayout inputLayout, int arity, boolean selfTransfer) {
        var b = e.builder;
        if (inputLayout != null && inputLayout.getRequiresTyped()) {
            var source = new BytecodeInputSource(inputLayout, accessors(values));
            if (selfTransfer) { b.beginBlock(); savedTypedSelf(e, fn, source, arity); }
            b.beginApplyTypedInputTuple(source, slots, true, metrics);
            b.emitLoadLocal(fn); b.endApplyTypedInputTuple();
            if (selfTransfer) b.endBlock();
        } else if (inputLayout == null) {
            b.beginTailApplyTuple(slots, arity, metrics);
            b.emitLoadLocal(fn); for (var value : values) b.emitLoadLocal(value);
            b.endTailApplyTuple();
        } else {
            b.beginTailApplyCompactTuple(slots, inputLayout, metrics);
            b.emitLoadLocal(fn); for (var value : values) b.emitLoadLocal(value);
            b.endTailApplyCompactTuple();
        }
    }

    /** Saved logical and physical arguments survive every saturated prefix call. */
    private void checkpointedTupleApplication(Emission e, TupleShape shape, Expression function,
            List<Expression> arguments, ArgumentLayout inputLayout, List<BytecodeLocal> destination, boolean tail, boolean selfTransfer) {
        var b = e.builder;
        var slots = tupleSlots(shape, destination, true);
        b.beginBlock();
        var fn = b.createLocal("captured tuple function", FrameSlotKind.Object);
        b.beginStaticStoreObject(fn); requireClosure(function).emit(e); b.endStaticStoreObject();
        var values = new ArrayList<BytecodeLocal>();
        if (inputLayout != null && inputLayout.getRequiresTyped()) {
            for (int i = 0; i < inputLayout.getPhysicalArity(); ++i) values.add(b.createLocal("captured typed tuple input " + i, null));
            for (int i = 0; i < arguments.size(); ++i) {
                var argument = arguments.get(i);
                int offset = inputLayout.offset(i);
                if (inputLayout.proof(i).isTypedTransport()) argument.emitTuple(e, values.subList(offset, inputLayout.offset(i + 1)));
                else { b.beginStoreLocal(values.get(offset)); argument.emit(e); b.endStoreLocal(); }
            }
        } else for (int i = 0; i < arguments.size(); ++i) {
            var argument = arguments.get(i);
            if (inputLayout != null && inputLayout.isEmpty(i)) argument.emitTuple(e, List.of());
            else {
                var local = b.createLocal("captured tuple operand " + i, FrameSlotKind.Object);
                e.staticResults.put(local, FrameSlotKind.Object);
                b.beginStaticStoreObject(local); argument.emit(e); b.endStaticStoreObject();
                values.add(local);
            }
        }
        var callerMask = b.createLocal("captured tuple caller mask", FrameSlotKind.Object);
        b.beginStaticStoreObject(callerMask); b.emitCurrentMask(); b.endStaticStoreObject();
        var tailResult = tail ? b.createLocal("captured tuple tail result", FrameSlotKind.Object) : null;
        FinishTuple finish = (suffix, layout, arity) -> {
            if (tail) {
                b.beginStaticStoreObject(Objects.requireNonNull(tailResult));
                // Staged prefixes may return a different closure/PAP: only the original exact call moves locally.
                savedTailTuple(e, slots, fn, suffix, layout, arity, selfTransfer && suffix == values);
                b.endStaticStoreObject();
            } else checkpointedTupleCall(e, slots, fn, suffix, layout, arity, callerMask);
        };
        if (arguments.isEmpty()) finish.emit(values, inputLayout, 0);
        else {
            b.beginIfThenElse(); b.beginMatchLiteral(1L); b.beginLessThan();
            b.beginClosureArity(); b.emitLoadLocal(fn); b.endClosureArity();
            b.emitLoadConstant((long) arguments.size()); b.endLessThan(); b.endMatchLiteral();
            b.beginBlock();
            var result = b.createLocal("tuple prefix result", FrameSlotKind.Object);
            var evaluatedArguments = new boolean[arguments.size()];
            for (int i = 0; i < arguments.size(); ++i) evaluatedArguments[i] = arguments.get(i).proof().getEvaluated();
            stagedOverapplication(e, fn, values, inputLayout, evaluatedArguments, callerMask, result,
                arguments.size(), false, finish);
            b.endBlock(); b.beginBlock();
            finish.emit(values, inputLayout, arguments.size());
            b.endBlock(); b.endIfThenElse();
        }
        if (tail) b.emitStaticLoadObject(Objects.requireNonNull(tailResult));
        b.endBlock();
    }

    private static boolean staticWideLong(CoreRepresentation proof) {
        return proof.getEvaluated() && proof.isLong() && !proof.isTypedTransport()
            && proof.getPrimReps() != null && proof.getPrimReps().size() == 1
            && Set.of("IntRep", "WordRep", "Int64Rep", "Word64Rep").contains(proof.getPrimReps().getFirst());
    }

    private static boolean staticBoxedReference(CoreRepresentation proof) {
        return !proof.isTypedTransport() && Set.of(CoreKind.OBJECT, CoreKind.DATA, CoreKind.CLOSURE).contains(proof.getKind())
            && proof.getPrimReps() != null && proof.getPrimReps().size() == 1
            // Boxed storage is Object regardless of levity; this does not prove WHNF.
            && Set.of("BoxedRep (Just Lifted)", "BoxedRep (Just Unlifted)", "BoxedRep Nothing")
                .contains(proof.getPrimReps().getFirst());
    }

    private Expression primitive(String name, List<Expression> args) { return primitive(name, args, false); }
    private Expression primitive(String name, List<Expression> args, boolean someException) {
        var narrow = NarrowScalarOp.named(name);
        if (narrow != null) {
            if (args.size() != (narrow.getUnary() ? 1 : 2)) throw new RuntimeFault("Primitive arity mismatch: " + name);
            for (int i = 0; i < args.size(); ++i) narrow.validateOperand(args.get(i).proof(), i);
            return new ProvenExpression(e -> {
                var b = e.builder;
                if (narrow.getSourceLong()) b.beginNarrowFromLong(narrow);
                else if (narrow.getResultLong()) b.beginNarrowToLong(narrow);
                else if (narrow.getShift()) b.beginNarrowShift(narrow);
                else b.beginNarrowInt(narrow);
                for (var arg : args) arg.emit(e);
                if (narrow.getUnary() && !narrow.getSourceLong()) b.emitLoadConstant(0);
                if (narrow.getSourceLong()) b.endNarrowFromLong();
                else if (narrow.getResultLong()) b.endNarrowToLong();
                else if (narrow.getShift()) b.endNarrowShift();
                else b.endNarrowInt();
            }, narrow.getResult());
        }
        var floating = floatingPrimitive(name, args);
        if (floating != null) return floating;
        long wordMask = ScalarLiterals.narrowWordPrimitiveMask(name);
        int bitShift = scalarBitPrimitiveShift(name);
        String operation = switch (scalar64PrimitiveOperation(name)) {
            case "popCnt8#", "popCnt16#", "popCnt32#", "popCnt64#" -> "PopulationCountWidth";
            case "clz8#", "clz16#", "clz32#", "clz64#" -> "CountLeadingZerosWidth";
            case "ctz8#", "ctz16#", "ctz32#", "ctz64#" -> "CountTrailingZerosWidth";
            case "byteSwap16#", "byteSwap32#", "byteSwap64#", "byteSwap#" -> "ByteSwapWidth";
            case "bitReverse8#", "bitReverse16#", "bitReverse32#", "bitReverse64#", "bitReverse#" -> "BitReverseWidth";
            case "pdep8#", "pdep16#", "pdep32#", "pdep64#", "pdep#" -> "BitDepositWidth";
            case "pext8#", "pext16#", "pext32#", "pext64#", "pext#" -> "BitExtractWidth";
            case "mulIntMayOflo#" -> "MultiplyIntMayOverflow";
            case "quotWord#" -> "QuotientUnsigned";
            case "remWord#" -> "RemainderUnsigned";
            case "gtWord#" -> "GreaterThanUnsigned";
            case "geWord#" -> "GreaterEqualUnsigned";
            case "+#", "plusWord#" -> "Add";
            case "-#", "minusWord#" -> "Subtract";
            case "*#", "timesWord#" -> "Multiply";
            case "negateInt#" -> "Negate";
            case "quotInt#" -> "Quotient";
            case "remInt#" -> "Remainder";
            case "==#", "eqWord#", "eqChar#" -> "Equal";
            case "reallyUnsafePtrEquality#" -> "PointerEqual";
            case "/=#", "neWord#", "neChar#" -> "NotEqual";
            case "<#", "ltChar#" -> "LessThan";
            case "ltWord#" -> "LessThanUnsigned";
            case "<=#", "leChar#" -> "LessEqual";
            case "leWord#" -> "LessEqualUnsigned";
            case ">#", "gtChar#" -> "GreaterThan";
            case ">=#", "geChar#" -> "GreaterEqual";
            case "and#", "andI#" -> "BitAnd";
            case "or#", "orI#" -> "BitOr";
            case "xor#", "xorI#" -> "BitXor";
            case "not#", "notI#" -> "BitNot";
            case "clz#" -> "CountLeadingZeros";
            case "ctz#" -> "CountTrailingZeros";
            case "popCnt#" -> "PopulationCount";
            case "uncheckedIShiftL#", "uncheckedShiftL#" -> "ShiftLeft";
            case "uncheckedIShiftRA#" -> "ShiftRight";
            case "uncheckedIShiftRL#", "uncheckedShiftRL#" -> "ShiftRightUnsigned";
            case "narrow8Int#" -> "Narrow8";
            case "narrow16Int#" -> "Narrow16";
            case "narrow32Int#" -> "Narrow32";
            case "narrow8Word#", "narrow16Word#", "narrow32Word#" -> "NarrowWord";
            case "int2Word#", "word2Int#", "ord#", "chr#", "intToInt64#", "int64ToInt#" -> "Identity";
            case "raise#" -> "Raise";
            case "addr2Int#" -> "AddressToInt";
            case "int2Addr#" -> "IntToAddress";
            case "plusAddr#" -> "AddressPlus";
            case "minusAddr#" -> "AddressMinus";
            case "remAddr#" -> "AddressRemainder";
            case "eqAddr#" -> "AddressEqual";
            case "neAddr#" -> "AddressNotEqual";
            case "ltAddr#", "leAddr#", "gtAddr#", "geAddr#" -> "AddressOrder";
            case "indexWord8OffAddr#", "indexInt8OffAddr#" -> "AddressIndexByte";
            case "indexCharOffAddr#", "indexWord16OffAddr#", "indexInt16OffAddr#" -> "AddressIndexManagedScalar";
            default -> throw new UnsupportedCore("Unsupported primitive " + name);
        };
        boolean unary = Set.of("PopulationCountWidth", "CountLeadingZerosWidth", "CountTrailingZerosWidth", "ByteSwapWidth", "BitReverseWidth", "Negate", "BitNot", "CountLeadingZeros", "CountTrailingZeros", "PopulationCount", "Narrow8", "Narrow16", "Narrow32", "NarrowWord", "Identity", "Raise", "AddressToInt", "IntToAddress").contains(operation);
        if (args.size() != (unary ? 1 : 2)) throw new RuntimeFault("Primitive arity mismatch: " + name);
        if (operation.equals("MultiplyIntMayOverflow")) for (var arg : args)
            if (!arg.proof().isLong()) throw new RuntimeFault("Primitive requires Long operands: " + name);
        if (operation.equals("Identity")) return evaluated(e -> {
            e.builder.beginToLong(); args.getFirst().emit(e); e.builder.endToLong();
        });
        boolean wideArithmetic = Set.of("Add", "Subtract", "Multiply").contains(operation);
        if (wideArithmetic) for (var arg : args) if (!staticWideLong(arg.proof())) {
            wideArithmetic = false;
            break;
        }
        if (wideArithmetic)
            return evaluated(e -> {
                e.builder.beginStaticLongArithmetic(switch (operation) { case "Add" -> 0; case "Subtract" -> 1; default -> 2; });
                for (var arg : args) arg.emit(e);
                e.builder.endStaticLongArithmetic();
            });
        return evaluated(e -> {
            var b = e.builder;
            switch (operation) {
                case "PopulationCountWidth" -> { b.beginPopulationCountWidth(bitShift); for (var arg : args) arg.emit(e); b.endPopulationCountWidth(); }
                case "CountLeadingZerosWidth" -> { b.beginCountLeadingZerosWidth(bitShift); for (var arg : args) arg.emit(e); b.endCountLeadingZerosWidth(); }
                case "CountTrailingZerosWidth" -> { b.beginCountTrailingZerosWidth(bitShift); for (var arg : args) arg.emit(e); b.endCountTrailingZerosWidth(); }
                case "ByteSwapWidth" -> { b.beginByteSwapWidth(bitShift); for (var arg : args) arg.emit(e); b.endByteSwapWidth(); }
                case "BitReverseWidth" -> { b.beginBitReverseWidth(bitShift); for (var arg : args) arg.emit(e); b.endBitReverseWidth(); }
                case "BitDepositWidth" -> { b.beginBitDepositWidth(bitShift); for (var arg : args) arg.emit(e); b.endBitDepositWidth(); }
                case "BitExtractWidth" -> { b.beginBitExtractWidth(bitShift); for (var arg : args) arg.emit(e); b.endBitExtractWidth(); }
                case "MultiplyIntMayOverflow" -> { b.beginMultiplyIntMayOverflow(); for (var arg : args) arg.emit(e); b.endMultiplyIntMayOverflow(); }
                case "QuotientUnsigned" -> { b.beginQuotientUnsigned(); for (var arg : args) arg.emit(e); b.endQuotientUnsigned(); }
                case "RemainderUnsigned" -> { b.beginRemainderUnsigned(); for (var arg : args) arg.emit(e); b.endRemainderUnsigned(); }
                case "GreaterThanUnsigned" -> { b.beginGreaterThanUnsigned(); for (var arg : args) arg.emit(e); b.endGreaterThanUnsigned(); }
                case "GreaterEqualUnsigned" -> { b.beginGreaterEqualUnsigned(); for (var arg : args) arg.emit(e); b.endGreaterEqualUnsigned(); }
                case "Add" -> { b.beginAdd(); for (var arg : args) arg.emit(e); b.endAdd(); }
                case "Subtract" -> { b.beginSubtract(); for (var arg : args) arg.emit(e); b.endSubtract(); }
                case "Multiply" -> { b.beginMultiply(); for (var arg : args) arg.emit(e); b.endMultiply(); }
                case "Negate" -> { b.beginNegate(); for (var arg : args) arg.emit(e); b.endNegate(); }
                case "Quotient" -> { b.beginQuotient(); for (var arg : args) arg.emit(e); b.endQuotient(); }
                case "Remainder" -> { b.beginRemainder(); for (var arg : args) arg.emit(e); b.endRemainder(); }
                case "Equal" -> { b.beginEqual(); for (var arg : args) arg.emit(e); b.endEqual(); }
                case "PointerEqual" -> { b.beginPointerEqual(); for (var arg : args) arg.emit(e); b.endPointerEqual(); }
                case "NotEqual" -> { b.beginNotEqual(); for (var arg : args) arg.emit(e); b.endNotEqual(); }
                case "LessThan" -> { b.beginLessThan(); for (var arg : args) arg.emit(e); b.endLessThan(); }
                case "LessThanUnsigned" -> { b.beginLessThanUnsigned(); for (var arg : args) arg.emit(e); b.endLessThanUnsigned(); }
                case "LessEqual" -> { b.beginLessEqual(); for (var arg : args) arg.emit(e); b.endLessEqual(); }
                case "LessEqualUnsigned" -> { b.beginLessEqualUnsigned(); for (var arg : args) arg.emit(e); b.endLessEqualUnsigned(); }
                case "GreaterThan" -> { b.beginGreaterThan(); for (var arg : args) arg.emit(e); b.endGreaterThan(); }
                case "GreaterEqual" -> { b.beginGreaterEqual(); for (var arg : args) arg.emit(e); b.endGreaterEqual(); }
                case "BitAnd" -> { b.beginBitAnd(); for (var arg : args) arg.emit(e); b.endBitAnd(); }
                case "BitOr" -> { b.beginBitOr(); for (var arg : args) arg.emit(e); b.endBitOr(); }
                case "BitXor" -> { b.beginBitXor(); for (var arg : args) arg.emit(e); b.endBitXor(); }
                case "BitNot" -> { b.beginBitNot(); for (var arg : args) arg.emit(e); b.endBitNot(); }
                case "CountLeadingZeros" -> { b.beginCountLeadingZeros(); for (var arg : args) arg.emit(e); b.endCountLeadingZeros(); }
                case "CountTrailingZeros" -> { b.beginCountTrailingZeros(); for (var arg : args) arg.emit(e); b.endCountTrailingZeros(); }
                case "PopulationCount" -> { b.beginPopulationCount(); for (var arg : args) arg.emit(e); b.endPopulationCount(); }
                case "ShiftLeft" -> { b.beginShiftLeft(); for (var arg : args) arg.emit(e); b.endShiftLeft(); }
                case "ShiftRight" -> { b.beginShiftRight(); for (var arg : args) arg.emit(e); b.endShiftRight(); }
                case "ShiftRightUnsigned" -> { b.beginShiftRightUnsigned(); for (var arg : args) arg.emit(e); b.endShiftRightUnsigned(); }
                case "Narrow8" -> { b.beginNarrow8(); for (var arg : args) arg.emit(e); b.endNarrow8(); }
                case "Narrow16" -> { b.beginNarrow16(); for (var arg : args) arg.emit(e); b.endNarrow16(); }
                case "Narrow32" -> { b.beginNarrow32(); for (var arg : args) arg.emit(e); b.endNarrow32(); }
                case "NarrowWord" -> { b.beginNarrowWord(wordMask); for (var arg : args) arg.emit(e); b.endNarrowWord(); }
                case "Raise" -> { b.beginRaise(someException); for (var arg : args) arg.emit(e); b.endRaise(); }
                case "AddressToInt" -> { b.beginAddressToInt(); for (var arg : args) arg.emit(e); b.endAddressToInt(); }
                case "IntToAddress" -> { b.beginIntToAddress(); for (var arg : args) arg.emit(e); b.endIntToAddress(); }
                case "AddressPlus" -> { b.beginAddressPlus(); for (var arg : args) arg.emit(e); b.endAddressPlus(); }
                case "AddressMinus" -> { b.beginAddressMinus(); for (var arg : args) arg.emit(e); b.endAddressMinus(); }
                case "AddressRemainder" -> { b.beginAddressRemainder(); for (var arg : args) arg.emit(e); b.endAddressRemainder(); }
                case "AddressEqual" -> { b.beginAddressEqual(); for (var arg : args) arg.emit(e); b.endAddressEqual(); }
                case "AddressNotEqual" -> { b.beginAddressNotEqual(); for (var arg : args) arg.emit(e); b.endAddressNotEqual(); }
                case "AddressOrder" -> { b.beginAddressOrder(switch (name) { case "ltAddr#" -> ManagedAddressOrder.LT; case "leAddr#" -> ManagedAddressOrder.LE; case "gtAddr#" -> ManagedAddressOrder.GT; default -> ManagedAddressOrder.GE; }); for (var arg : args) arg.emit(e); b.endAddressOrder(); }
                case "AddressIndexByte" -> { b.beginAddressIndexByte(name.equals("indexInt8OffAddr#")); for (var arg : args) arg.emit(e); b.endAddressIndexByte(); }
                case "AddressIndexManagedScalar" -> { b.beginAddressIndexManagedScalar(switch (name) { case "indexCharOffAddr#" -> ManagedAddressRead.CHAR; case "indexInt16OffAddr#" -> ManagedAddressRead.INT16; default -> ManagedAddressRead.WORD16; }); for (var arg : args) arg.emit(e); b.endAddressIndexManagedScalar(); }
            }
        });
    }

    private Expression sumCase(List<Object> expr, Expression scrutinee, CoreRepresentation proof, Scope scope, boolean tail) {
        var shape = new TupleShape(proof, language);
        var fields = new ArrayList<Local>();
        for (var field : shape.getLeaves())
            fields.add(new Local(nextLocal++, "sum field " + fields.size(), field.isLong(), field));
        scope.bindTuple((String) expr.get(2), proof, fields);
        record Conversion(Local local, Local physical) {}
        record Arm(Integer tag, Expression body, List<Conversion> conversions) {}
        var seen = new HashSet<Integer>();
        var arms = new ArrayList<Arm>();
        for (var alt : (List<List<Object>>) expr.get(3)) {
            var child = scope.child();
            var conversions = new ArrayList<Conversion>();
            var ids = (List<String>) alt.get(2);
            Integer tag;
            if ("default".equals(alt.getFirst())) {
                if (!ids.isEmpty() || !CoreRepresentations.alternativeBinders(alt).isEmpty())
                    throw new RuntimeFault("Invalid sum DEFAULT alternative");
                tag = null;
            } else {
                if (!"data".equals(alt.getFirst()) || ids.size() != 1) throw new RuntimeFault("Invalid sum alternative");
                int selected = SumShape.constructor(proof, constructors.get(alt.get(1)), ids.size());
                var component = proof.getAlternatives().get(selected - 1);
                var metadata = CoreRepresentations.alternativeBinders(alt);
                if (metadata.size() != 1 || !Objects.equals(metadata.getFirst().get("id"), ids.getFirst()))
                    throw new RuntimeFault("Missing sum payload binder proof");
                var actual = CoreRepresentations.binder(metadata.getFirst());
                Object lifted = metadata.getFirst().get("lifted");
                CoreRepresentations.mayBeLazy(lifted, actual);
                SumShape.payload(component, actual, (Boolean) lifted);
                var logical = evaluatedProof(component.refine(actual), component.getEvaluated());
                var leaves = TupleShape.flatten(logical);
                var projected = new ArrayList<Local>();
                for (int physical : SumShape.projection(proof, selected - 1)) {
                    int index = projected.size();
                    var leaf = leaves.get(index);
                    if (!leaf.isInt()) projected.add(fields.get(physical));
                    else {
                        var local = new Local(nextLocal++, "narrow sum arm " + index, false, leaf);
                        projected.add(local);
                        conversions.add(new Conversion(local, fields.get(physical)));
                    }
                }
                if (component.isTypedTransport()) {
                    var logicalFields = new ArrayList<Local>();
                    for (int i = 0; i < projected.size(); ++i) {
                        var field = projected.get(i);
                        logicalFields.add(new Local(field.id, field.name, field.primitive, leaves.get(i),
                            field.cell, field.entry, field.arityCertificate));
                    }
                    child.bindTuple(ids.getFirst(), logical, logicalFields);
                } else if (component.getKind() == CoreKind.VOID) child.bindVoid(ids.getFirst(), logical);
                else {
                    var field = projected.getFirst();
                    child.bindLocal(ids.getFirst(), new Local(field.id, ids.getFirst(), field.primitive,
                        logical, field.cell, field.entry, field.arityCertificate));
                }
                tag = selected;
            }
            if (!seen.add(tag)) throw new RuntimeFault("Duplicate sum alternative");
            arms.add(new Arm(tag, compile((List<Object>) alt.get(3), child, tail), conversions));
        }
        if (arms.isEmpty()) throw new RuntimeFault("Empty sum case");
        var result = arms.getFirst().body.proof().refine(CoreRepresentations.caseResult(expr));
        for (var arm : arms) result.refine(arm.body.proof());
        var armProofs = new ArrayList<CoreRepresentation>(arms.size());
        for (var arm : arms) armProofs.add(arm.body.proof());
        CoreRepresentations.validateFloatingCaseResult(result, armProofs);
        return new ProvenExpression(new ResultExpression((e, destination) -> {
            var b = e.builder;
            b.beginBlock();
            for (var field : fields) e.locals.put(field.id, b.createLocal(field.name, field.primitive ? "primitive" : "object"));
            scrutinee.emitTuple(e, localSlots(e, fields));
            b.beginStoreLocal(e.locals.get(fields.getFirst().id));
            b.beginCheckSumTag(proof.getAlternatives().size());
            read(fields.getFirst()).emit(e); b.endCheckSumTag(); b.endStoreLocal();
            var explicit = new ArrayList<Arm>();
            Arm defaultArm = null;
            for (var arm : arms) {
                if (arm.tag != null) explicit.add(arm);
                else if (defaultArm == null) defaultArm = arm;
            }
            var fallback = defaultArm;
            class Choice {
                void emitArm(Arm arm) {
                    b.beginBlock();
                    for (var conversion : arm.conversions) {
                        var local = conversion.local;
                        e.locals.put(local.id, b.createLocal(local.name, null));
                        b.beginStoreLocal(e.locals.get(local.id)); b.beginSumWordToNarrow(local.proof.getNarrowInteger());
                        read(conversion.physical).emit(e); b.endSumWordToNarrow(); b.endStoreLocal();
                    }
                    emitResult(arm.body, e, destination);
                    for (var conversion : arm.conversions) e.locals.remove(conversion.local.id);
                    b.endBlock();
                }
                void emit(int index) {
                    if (index == explicit.size()) {
                        if (fallback == null) b.emitFailCase(); else emitArm(fallback);
                        return;
                    }
                    var arm = explicit.get(index);
                    if (destination == null) b.beginConditional(); else b.beginIfThenElse();
                    b.beginMatchLiteral(arm.tag.longValue()); read(fields.getFirst()).emit(e); b.endMatchLiteral();
                    // Each aggregate branch remains one builder child, including zero-slot results.
                    emitArm(arm);
                    b.beginBlock(); emit(index + 1); b.endBlock();
                    if (destination == null) b.endConditional(); else b.endIfThenElse();
                }
            }
            new Choice().emit(0);
            b.endBlock();
            for (var field : fields) e.locals.remove(field.id);
        }), evaluatedProof(result, allProofsEvaluated(armProofs)));
    }

    private Expression vectorReadCase(VectorReadCase read, Scope scope, boolean tail) {
        var operands = compileOperands(read.getArguments(), scope);
        var value = vectorMemory(read.getOperation(), operands);
        var local = scope.child();
        local.bindVoid(read.getStateBinder(), CoreVectorMemory.getStateProof());
        var vectorProof = read.getOperation().getVectorProof();
        var lanes = new ArrayList<Local>();
        for (var proof : TupleShape.flatten(vectorProof))
            lanes.add(new Local(nextLocal++, read.getVectorBinder() + " read lane " + lanes.size(), proof.isLong(), proof));
        local.bindTuple(read.getVectorBinder(), vectorProof, lanes);
        var body = compile(read.getBody(), local, tail);
        // Capturing closures copy the raw vector to owned primitive fields only at capture time.
        return new LoweredCaseExpression(new ProvenExpression(new ResultExpression((e, destination) -> {
            var b = e.builder;
            b.beginBlock();
            for (var lane : lanes) e.locals.put(lane.id, b.createLocal(lane.name, "object"));
            value.emitTuple(e, localSlots(e, lanes));
            emitResult(body, e, destination);
            b.endBlock();
            for (var lane : lanes) e.locals.remove(lane.id);
        }), body.proof()));
    }

    private Expression tupleOrVectorCase(List<Object> expr, Expression scrutinee, CoreRepresentation proof, Scope scope, boolean tail) {
        var shape = new TupleShape(proof, language);
        var fields = new ArrayList<Local>();
        for (var field : shape.getLeaves())
            fields.add(new Local(nextLocal++, "tuple field " + fields.size(), field.isLong(), field));
        scope.bindTuple((String) expr.get(2), proof, fields);
        var alternatives = (List<List<Object>>) expr.get(3);
        if (alternatives.isEmpty()) return new ProvenExpression(new ResultExpression((e, destination) -> {
            var b = e.builder;
            b.beginBlock();
            var slots = new ArrayList<BytecodeLocal>(fields.size());
            for (var field : fields) slots.add(b.createLocal(field.name, FrameLayout.carrierKind(field.proof)));
            scrutinee.emitTuple(e, slots);
            if (destination != null) b.beginStoreLocal(b.createLocal("non-returning empty case", null));
            b.emitFailCase();
            if (destination != null) b.endStoreLocal(); b.endBlock();
        }), CoreRepresentations.caseResult(expr));
        if (alternatives.size() != 1) throw new RuntimeFault("Tuple or vector case requires one alternative");
        var alt = alternatives.getFirst();
        var ids = (List<String>) alt.get(2);
        if (proof.isVector() && (!"default".equals(alt.getFirst()) || !ids.isEmpty()
                || !CoreRepresentations.alternativeBinders(alt).isEmpty()))
            throw new RuntimeFault("Vector case requires a binder-free DEFAULT alternative");
        if ("data".equals(alt.getFirst())) {
            var constructor = constructors.get(alt.get(1));
            if (constructor == null || !"unboxed-tuple".equals(constructor.get("kind"))
                    || ids.size() != shape.getComponents().length
                    || !(constructor.get("arity") instanceof Number arity) || arity.intValue() != ids.size())
                throw new RuntimeFault("Tuple alternative shape mismatch");
            var metadata = CoreRepresentations.alternativeBinders(alt);
            for (int index = 0; index < ids.size(); ++index) {
                var id = ids.get(index);
                var component = shape.getComponents()[index];
                if (index < metadata.size()) TupleShape.requireCompatible(component,
                    CoreRepresentations.binder(metadata.get(index)), true);
                int offset = shape.getOffsets()[index];
                int width = TupleShape.flatten(component).size();
                if (component.isTypedTransport()) scope.bindTuple(id, component, fields.subList(offset, offset + width));
                else if (component.getKind() == CoreKind.VOID) scope.bindVoid(id, component);
                else {
                    var field = fields.get(offset);
                    scope.bindLocal(id, new Local(field.id, id, field.primitive, field.proof,
                        field.cell, field.entry, field.arityCertificate));
                }
            }
        } else if (!"default".equals(alt.getFirst()) || !ids.isEmpty()) throw new RuntimeFault("Invalid tuple alternative");
        int[] dead = CoreFreeVariables.unusedTupleReferences(expr, shape);
        var body = compile((List<Object>) alt.get(3), scope, tail);
        return new ProvenExpression(new ResultExpression((e, destination) -> {
            var b = e.builder;
            b.beginBlock();
            for (var field : fields) {
                var kind = FrameLayout.carrierKind(field.proof);
                var slot = b.createLocal(field.name, kind);
                e.locals.put(field.id, slot);
                if (kind != FrameSlotKind.Illegal && !field.proof.isTypedTransport()) {
                    if (kind == FrameSlotKind.Object) e.staticObjectLocals.add(field.id);
                    else if (kind == FrameSlotKind.Long) e.staticLocals.add(field.id);
                    else e.staticScalars.put(field.id, kind);
                    e.staticResults.put(slot, kind);
                }
            }
            scrutinee.emitTuple(e, localSlots(e, fields));
            for (int index : dead) {
                b.beginStaticStoreObject(e.locals.get(fields.get(index).id)); b.emitLoadNull(); b.endStaticStoreObject();
            }
            emitResult(body, e, destination); b.endBlock();
            for (var field : fields) {
                e.staticResults.remove(e.locals.remove(field.id));
                e.staticObjectLocals.remove(field.id); e.staticLocals.remove(field.id); e.staticScalars.remove(field.id);
            }
        }), body.proof());
    }

    private Expression construct(DataLayout layout, List<Expression> args) {
        return evaluated(e -> {
            var b = e.builder;
            boolean vectors = false;
            for (int i = 0; i < layout.getArity(); ++i) vectors |= layout.isVector(i);
            if (!layout.getHasAggregateFields() && !vectors) {
                b.beginConstruct(layout); for (var arg : args) arg.emit(e); b.endConstruct();
            } else {
                b.beginBlock();
                var fields = new ArrayList<List<BytecodeLocal>>();
                for (int index = 0; index < args.size(); ++index) {
                    var argument = args.get(index);
                    int physical = layout.fieldOffset(index);
                    var proof = layout.logicalProof(index);
                    if (proof != null && proof.isAggregate()) {
                        var slots = new ArrayList<BytecodeLocal>();
                        for (int i = 0; i < layout.logicalWidth(index); ++i)
                            slots.add(b.createLocal("field " + index + " aggregate " + i, null));
                        argument.emitTuple(e, slots);
                        for (var slot : slots) fields.add(List.of(slot));
                    } else if (layout.isVector(physical)) {
                        var lanes = new ArrayList<BytecodeLocal>();
                        for (int lane = 0; lane < layout.fieldWidth(physical); ++lane)
                            lanes.add(b.createLocal("field " + index + " vector " + lane, "object"));
                        argument.emitTuple(e, lanes); fields.add(lanes);
                    } else {
                        var field = b.createLocal("field " + index, null);
                        b.beginStoreLocal(field); argument.emit(e); b.endStoreLocal(); fields.add(List.of(field));
                    }
                }
                var value = b.createLocal("constructed " + layout.getId(), "object");
                b.beginStoreLocal(value); b.emitAllocateData(layout); b.endStoreLocal();
                for (int index = 0; index < fields.size(); ++index) {
                    var slots = fields.get(index);
                    if (layout.isVector(index)) {
                        b.beginTransferDataVector(new BytecodeRoot.DataVectorTransfer(layout, index, accessors(slots), true));
                        b.emitLoadLocal(value); b.endTransferDataVector();
                    } else {
                        b.beginInitializeDataScalar(layout, index); b.emitLoadLocal(value);
                        b.emitLoadLocal(slots.getFirst()); b.endInitializeDataScalar();
                    }
                }
                b.emitLoadLocal(value); b.endBlock();
            }
        });
    }

    private Expression compileSupported(List<Object> expr, Scope scope, boolean tail) {
        return switch ((String) expr.getFirst()) {
            case "var" -> {
                var id = (String) expr.get(1);
                var occurrence = CoreRepresentations.expression(expr);
                var aggregate = scope.tuples.get(id);
                var local = scope.locals.get(id);
                var proof = aggregate != null ? aggregate.proof : local != null ? local.proof
                    : globalProofs.containsKey(id) ? globalProofs.get(id)
                    : demand == null ? null : demand.occurrence(id, occurrence);
                CoreVectors.requireVariableProof(proof, occurrence);
                if (aggregate != null) {
                    TupleShape.requireCompatible(aggregate.proof, occurrence, false);
                    yield tupleExpression(aggregate.proof, (e, destination) -> {
                        for (int index = 0; index < aggregate.fields.size(); ++index) {
                            var field = aggregate.fields.get(index);
                            storeTupleResult(e, destination.get(index), () -> read(field).emit(e));
                        }
                    });
                }
                var join = scope.joins.get(id);
                if (join != null) yield joinCall(join, List.of());
                if (local != null) yield read(local);
                var binding = globals.get(id);
                if (binding == null) throw new UnsupportedCore("Unresolved external binding " + id);
                var stored = proof != null ? proof : Objects.requireNonNull(globalProofs.get(id));
                yield new ProvenExpression(stored.isLong() && stored.getEvaluated()
                    ? e -> e.builder.emitReadGlobalLong(binding) : e -> e.builder.emitReadGlobal(binding), stored);
            }
            case "lit" -> {
                var kind = (String) expr.get(1);
                if (kind.equals("rubbish")) yield new RubbishExpression(RubbishLiterals.proof(expr));
                if (kind.equals("function-addr") && !nativeCallbacks.containsKey(expr.get(2))) {
                    var proof = CoreRepresentations.expression(expr);
                    var symbol = (String) expr.get(2);
                    CFinalizerLabels.validate(symbol, proof);
                    yield new ProvenExpression(e -> e.builder.emitResolveCFunctionLabel(symbol, proof), proof);
                }
                var value = constant(literal(kind, expr.get(2), CoreRepresentations.expression(expr)));
                yield switch (kind) {
                    case "int8", "word8", "int16", "word16", "int32", "word32" ->
                        new ProvenExpression(value, CoreRepresentations.narrowLiteralProof(expr));
                    case "bignat" -> new ProvenExpression(value, BigNatLiterals.proof(expr));
                    default -> value;
                };
            }
            case "void" -> constant(thc.runtime.Unit.INSTANCE);
            case "lam" -> {
                var args = (List<Map<String, Object>>) expr.get(1);
                var names = new ArrayList<String>(args.size());
                for (var arg : args) names.add(String.valueOf(arg.get("name")));
                yield closure(function("lambda " + String.join(", ", names), args, (List<Object>) expr.get(2), scope,
                    CoreRepresentations.lambdaResult(expr), CoreEntries.lambda(expr)), args.size());
            }
            case "app" -> compileApplication(expr, scope, tail);
            case "let" -> compileLet(expr, scope, tail);
            case "case" -> compileCase(expr, scope, tail);
            case "con" -> compileConstructor(expr);
            case "prim" -> throw new UnsupportedCore("Unsaturated primitive " + expr.get(1));
            default -> throw new UnsupportedCore("Unsupported Core node " + expr.getFirst());
        };
    }

    private Expression compileLet(List<Object> expr, Scope scope, boolean tail) {
        boolean recursive = (Boolean) expr.get(1);
        var group = (List<Map<String, Object>>) expr.get(2);
        for (var binding : group) if (CoreRepresentations.joinArity(binding) != null) {
            var context = scope.function;
            if (!context.preparingCaseRegion && context.caseRegions.size() < BytecodeCaseRegion.MAX_REGIONS) {
                if (recursive && joinRegionWork(expr, 64) >= 64) {
                    var closed = closedJoinRegion(expr, scope);
                    if (closed != null) {
                        context.preparingCaseRegion = true;
                        try {
                            var inline = joinRegion(group, (List<Object>) expr.get(3), true, scope, tail);
                            int rootMark = roots.size(), joinMark = localJoinCount;
                            FunctionSpec side;
                            try {
                                side = function("join region " + group.getFirst().get("id"), List.of(), closed, scope,
                                        inline.proof(), new boolean[0], tail, true, false);
                            } catch (BytecodeEncodingException failure) {
                                if (!localIndexOverflow(failure)) throw failure;
                                // This optional side has not escaped preparation. Keep the
                                // usable inline body and any previously accepted region plans.
                                roots.subList(rootMark, roots.size()).clear();
                                localJoinCount = joinMark;
                                return inline;
                            }
                            if (context.caseEmission == null) context.caseEmission = new CaseRegionEmission();
                            int index = context.caseRegions.size();
                            context.caseRegions.add(new BytecodeCaseRegion(side.target, side.captureLayout, tail));
                            return preparedRegion(inline, List.of(side), scope, tail, index,
                                    new ProvenExpression(e -> e.builder.emitLoadConstant(thc.runtime.Unit.INSTANCE), UNKNOWN));
                        } finally { context.preparingCaseRegion = false; }
                    }
                }
                var prefix = new ArrayList<List<Object>>();
                var joins = new LinkedHashSet<String>();
                var body = expr;
                while ("let".equals(body.getFirst()) && Boolean.FALSE.equals(body.get(1))) {
                    var definitions = (List<Map<String, Object>>) body.get(2);
                    if (definitions.isEmpty() || definitions.stream().anyMatch(d -> CoreRepresentations.joinArity(d) == null)) break;
                    prefix.add(body);
                    for (var definition : definitions) joins.add((String) definition.get("id"));
                    body = (List<Object>) body.get(3);
                }
                if (!prefix.isEmpty() && "case".equals(body.getFirst()) && constructorCaseRegion(body)
                        && CoreFreeVariables.coreFreeVariables(expr).stream().noneMatch(scope.joins::containsKey)
                        && CoreFreeVariables.coreFreeVariables((List<Object>) body.get(1)).stream().noneMatch(joins::contains)) {
                    // Move the closed join region, not a case escaping a live join
                    // activation. Its definitions are inert and retain their exact
                    // lexical order in each side; the scrutinee stays in the caller.
                    context.preparingCaseRegion = true;
                    try {
                        var inline = joinRegion(group, (List<Object>) expr.get(3), recursive, scope, tail);
                        return partitionedCase(body, scope, tail, false, inline, prefix);
                    } finally { context.preparingCaseRegion = false; }
                }
            }
            return joinRegion(group, (List<Object>) expr.get(3), recursive, scope, tail);
        }
        for (var binding : group) {
            var proof = CoreRepresentations.binder(binding);
            if (proof.isTypedTransport()) {
                if (recursive || representation(binding)) throw new UnsupportedCore("Aggregate/vector let binding must be nonrecursive and unlifted");
                CoreRepresentations.requireInput(proof);
            } else CoreRepresentations.requireScalar(proof, "let binding");
        }
        var local = scope.child();
        var slots = new ArrayList<List<Local>>();
        for (var binding : group) {
            var proof = CoreRepresentations.binder(binding);
            var id = (String) binding.get("id");
            if (proof.isTypedTransport()) {
                var lanes = new ArrayList<Local>();
                for (var field : TupleShape.flatten(proof))
                    lanes.add(new Local(nextLocal++, id + " let field " + lanes.size(), field.isLong(), field));
                local.bindTuple(id, evaluatedProof(proof, true), lanes); slots.add(lanes);
            } else slots.add(List.of(bind(local, id, !representation(binding), evaluatedProof(proof, false),
                recursive, CoreEntries.binding(binding), CoreApplicationCertificates.binding(binding))));
        }
        var rhs = new ArrayList<Expression>();
        for (var binding : group) {
            var rhsExpr = (List<Object>) binding.get("expr");
            boolean lifted = representation(binding);
            if (recursive && !lifted) throw new UnsupportedCore("Recursive unlifted binding unsupported");
            var rhsScope = (recursive ? local : scope).withSource(sources.binding(binding, scope.source));
            if (recursive && lifted && !Set.of("lam", "lit", "con", "void").contains(rhsExpr.getFirst()))
                rhs.add(delay(rhsExpr, rhsScope, String.valueOf(binding.get("name"))));
            else rhs.add(argument(rhsExpr, rhsScope, lifted, String.valueOf(binding.get("name")),
                CoreRepresentations.binder(binding).isTypedTransport(), lifted));
        }
        for (int index = 0; index < slots.size(); ++index) {
            var fields = slots.get(index);
            if (CoreRepresentations.binder(group.get(index)).isTypedTransport()) {
                if (!rhs.get(index).proof().isTypedTransport()) throw new RuntimeFault("Aggregate/vector let binding requires an exact result shape");
                TupleShape.requireCompatible(CoreRepresentations.binder(group.get(index)), rhs.get(index).proof(), false);
            } else {
                var slot = fields.getFirst();
                var proof = evaluatedProof(slot.proof.refine(rhs.get(index).proof()), rhs.get(index).proof().getEvaluated());
                // RHS captures keep immutable recursive-cell records; only new captures see published values.
                local.locals.put(slot.name, new Local(slot.id, slot.name,
                    proof.getPresent() ? proof.isLong() : slot.primitive, proof, false, slot.entry, slot.arityCertificate));
            }
        }
        var body = compile((List<Object>) expr.get(3), local, tail);
        var physicalSlots = new ArrayList<Local>();
        for (var fields : slots) physicalSlots.addAll(fields);
        return new ProvenExpression(new ResultExpression((e, destination) -> {
            var b = e.builder;
            b.beginBlock();
            for (var slot : physicalSlots) e.locals.put(slot.id, b.createLocal(slot.name, slot.primitive ? "primitive" : "object"));
            if (recursive) {
                for (var slot : physicalSlots) {
                    b.beginStoreLocal(e.locals.get(slot.id)); b.emitNewCell(); b.endStoreLocal();
                }
                for (int index = 0; index < slots.size(); ++index) {
                    b.beginInitializeCell(); read(slots.get(index).getFirst(), false).emit(e);
                    rhs.get(index).emit(e); b.endInitializeCell();
                }
                // Publish only after every RHS has captured the complete group.
                for (var slot : physicalSlots) {
                    b.beginStoreLocal(e.locals.get(slot.id)); read(slot).emit(e); b.endStoreLocal();
                }
            } else for (int index = 0; index < slots.size(); ++index) {
                var fields = slots.get(index);
                if (CoreRepresentations.binder(group.get(index)).isTypedTransport())
                    rhs.get(index).emitTuple(e, localSlots(e, fields));
                else {
                    b.beginStoreLocal(e.locals.get(fields.getFirst().id)); rhs.get(index).emit(e); b.endStoreLocal();
                }
            }
            emitResult(body, e, destination); b.endBlock();
            for (var slot : physicalSlots) e.locals.remove(slot.id);
        }), body.proof());
    }

    private Expression compileConstructor(List<Object> expr) {
        var id = (String) expr.get(1);
        int arity = ((Number) expr.get(2)).intValue();
        var constructor = constructors.get(id);
        if (constructor != null && "unboxed-tuple".equals(constructor.get("kind")) && arity == 0
                && CoreRepresentations.expression(expr).isTuple()) {
            var proof = CoreRepresentations.expression(expr);
            if (proof.getComponents() == null || !proof.getComponents().isEmpty())
                throw new RuntimeFault("Empty tuple constructor has nonempty logical components");
            return tupleExpression(proof, (e, destination) -> { e.builder.beginBlock(); e.builder.endBlock(); });
        }
        var strict = strictConstructorFields(id, arity);
        var layout = dataLayout(id);
        if (arity != 0 && layout.getHasAggregateFields())
            throw new UnsupportedCore("Unsaturated aggregate-field constructor requires aggregate inputs");
        if (arity == 0) return construct(layout, List.of());
        var context = new FunctionContext(arity, strict);
        var constructorScope = new Scope(context);
        var records = constructor.get("fieldTypes") instanceof List<?> list ? list : null;
        if (records != null && records.size() != arity) throw new RuntimeFault("Constructor field type count mismatch: " + id);
        List<CoreRepresentation> proofs;
        if (records == null) proofs = Collections.nCopies(arity, UNKNOWN);
        else {
            proofs = new ArrayList<>(records.size());
            for (var record : records) proofs.add(CoreRepresentations.parse(record));
        }
        context.inputLayout = ArgumentLayout.fromProofs(proofs);
        context.typedInput = TypedInputLayout.create(language, context.inputLayout, false);
        var physical = new ArrayList<TypedArgument>();
        var args = new ArrayList<Expression>();
        for (int index = 0; index < proofs.size(); ++index) {
            var proof = proofs.get(index);
            int offset = ArgumentLayout.offset(context.inputLayout, index);
            var vector = layout.vectorProof(index);
            if (vector != null) {
                var exact = proof.refine(vector);
                var lanes = new ArrayList<Local>();
                for (var leaf : TupleShape.flatten(exact)) {
                    int lane = lanes.size();
                    var local = new Local(nextLocal++, "field" + index + " vector lane " + lane, leaf.isLong(), leaf);
                    lanes.add(local); physical.add(new TypedArgument(offset + lane, local));
                }
                args.add(tupleExpression(exact, (e, destination) -> {
                    for (int lane = 0; lane < lanes.size(); ++lane) {
                        e.builder.beginStoreLocal(destination.get(lane)); read(lanes.get(lane)).emit(e); e.builder.endStoreLocal();
                    }
                }));
            } else {
                var local = bind(constructorScope, "field" + index, layout.isLong(index), proof);
                physical.add(new TypedArgument(offset, local)); args.add(strict[index] ? force(read(local)) : read(local));
            }
        }
        var arguments = new ArrayList<Local>();
        for (int index = 0; index < proofs.size(); ++index) {
            int offset = ArgumentLayout.offset(context.inputLayout, index);
            if (layout.isVector(index)) arguments.add(null);
            else {
                TypedArgument selected = null;
                for (var candidate : physical) if (candidate.index == offset) { selected = candidate; break; }
                if (selected == null) throw new java.util.NoSuchElementException("No value present");
                arguments.add(selected.local);
            }
        }
        context.arguments = arguments; context.typedArguments = physical;
        return closure(new FunctionSpec(build("constructor " + id, context, construct(layout, args)), null, List.of()), arity);
    }

    private static boolean constructorCaseRegion(List<Object> expr) {
        var alternatives = (List<List<Object>>) expr.get(3);
        long dataCount = alternatives.stream().filter(a -> "data".equals(a.getFirst())).count();
        return dataCount >= 64 && dataCount <= BytecodeCaseRegion.MAX_ALTERNATIVES
                && alternatives.stream().allMatch(a -> "data".equals(a.getFirst()) || "default".equals(a.getFirst()))
                && CoreRepresentations.caseBinder(expr).getKind() == CoreKind.DATA;
    }

    private Expression compileCase(List<Object> expr, Scope scope, boolean tail) {
        var alternatives = (List<List<Object>>) expr.get(3);
        var context = scope.function;
        long literalCount = alternatives.stream().filter(a -> "lit".equals(a.getFirst())).count();
        // A default-only continuation can be large even though it has no choice.
        // Preparation is inert; only a real graph bailout moves its suffix out of the caller.
        boolean scalarSuffix = alternatives.size() == 1 && "default".equals(alternatives.getFirst().getFirst());
        boolean scalarPartition = staticWideLong(CoreRepresentations.caseBinder(expr).withEvaluated(true))
                && (scalarSuffix || literalCount >= 2 && literalCount <= 8)
                && alternatives.stream().allMatch(a -> "lit".equals(a.getFirst()) || "default".equals(a.getFirst()))
                && caseRegionWork(scalarSuffix ? alternatives.getFirst().get(3) : expr, 64) >= 64;
        if (!context.preparingCaseRegion && context.caseRegions.size() < BytecodeCaseRegion.MAX_REGIONS
                && (constructorCaseRegion(expr) || scalarPartition)
                && CoreFreeVariables.coreFreeVariables(expr).stream().noneMatch(scope.joins::containsKey)) {
            context.preparingCaseRegion = true;
            try { return partitionedCase(expr, scope, tail, scalarPartition); }
            finally { context.preparingCaseRegion = false; }
        }
        return inlineCase(expr, scope, tail);
    }

    // A bounded preparation-size filter, not a prediction of Graal graph size.
    // Only the real bailout can activate the prepublished alternatives.
    private static int caseRegionWork(Object value, int remaining) {
        if (remaining <= 0) return 0;
        if (value instanceof Map<?, ?> binding) return caseRegionWork(binding.get("expr"), remaining);
        if (!(value instanceof List<?> list) || list.isEmpty() || "lam".equals(list.getFirst())) return 0;
        int count = 1;
        for (Object child : list) {
            count += caseRegionWork(child, remaining - count);
            if (count >= remaining) break;
        }
        return count;
    }

    // Count join bodies, but never count an ordinary nested closure as work in this root.
    private static int joinRegionWork(Object value, int remaining) {
        if (remaining <= 0) return 0;
        if (value instanceof Map<?, ?> raw) {
            var binding = (Map<String, Object>) raw;
            if (CoreRepresentations.joinArity(binding) == null) return 0;
            return joinRegionWork(CoreJoins.definitions(List.of(binding)).getFirst().body(), remaining);
        }
        if (!(value instanceof List<?> list) || list.isEmpty() || "lam".equals(list.getFirst())) return 0;
        int count = 1;
        for (Object child : list) {
            count += joinRegionWork(child, remaining - count);
            if (count >= remaining) break;
        }
        return count;
    }

    private Expression partitionedCase(List<Object> expr, Scope scope, boolean tail, boolean scalar) {
        return partitionedCase(expr, scope, tail, scalar, inlineCase(expr, scope, tail, true), List.of());
    }

    private Expression partitionedCase(List<Object> expr, Scope scope, boolean tail, boolean scalar,
            Expression inline, List<List<Object>> joinPrefix) {
        var alternatives = (List<List<Object>>) expr.get(3);
        var explicit = alternatives.stream().filter(a -> !"default".equals(a.getFirst())).toList();
        var fallback = alternatives.stream().filter(a -> "default".equals(a.getFirst())).toList();
        var binder = (String) expr.get(2);
        var binderMetadata = Map.<String, Object>of("id", binder, "name", binder, "lifted", !scalar,
                "rep", ((Map<String, Object>) expr.getLast()).get("binder") instanceof Map<?, ?> record
                        ? record.get("rep") : Map.of());
        var specs = new ArrayList<FunctionSpec>();
        var guards = new Object[explicit.size()];
        for (int i = 0; i < guards.length; i++) {
            if (scalar) {
                var value = (List<Object>) explicit.get(i).get(1);
                guards[i] = (Long) literal((String) value.getFirst(), value.get(1), UNKNOWN);
            } else guards[i] = dataLayout((String) explicit.get(i).get(1));
        }
        int width = scalar ? 1 : BytecodeCaseRegion.WIDTH;
        int end = explicit.size() + (scalar && !fallback.isEmpty() ? 1 : 0);
        // Every disjoint region shares the root's single monotone preparation
        // choice. A later region must never restore an inline copy after an
        // earlier side has already required capacity extraction.
        if (scope.function.caseEmission == null) scope.function.caseEmission = new CaseRegionEmission();
        var emission = scope.function.caseEmission;
        while (true) {
            int rootMark = roots.size();
            int joinMark = localJoinCount;
            try {
                for (int start = 0; start < end; start += width) {
                    var chunk = new ArrayList<>(explicit.subList(Math.min(start, explicit.size()), Math.min(start + width, explicit.size())));
                    if (start + width >= end) chunk.addAll(fallback);
                    var body = new ArrayList<>(expr);
                    body.set(1, List.of("var", binder));
                    body.set(3, chunk);
                    for (int prefix = joinPrefix.size() - 1; prefix >= 0; prefix--) {
                        var wrapper = new ArrayList<>(joinPrefix.get(prefix));
                        wrapper.set(3, body);
                        body = wrapper;
                    }
                    specs.add(function("case region " + binder + " " + start, List.of(binderMetadata), body, scope,
                            inline.proof(), new boolean[1], tail, true));
                }
                break;
            } catch (BytecodeEncodingException failure) {
                if (!localIndexOverflow(failure) || width == 1) throw failure;
                // The actual emitter, not syntax-size estimates, decides whether
                // a side fits. At most five reductions; no guest code executes.
                width /= 2;
                // These roots belong only to the discarded preparation attempt;
                // none has been installed in a binding or a region call node.
                roots.subList(rootMark, roots.size()).clear();
                localJoinCount = joinMark;
                specs.clear();
                emission.inline = false;
            }
        }
        var targets = new RootCallTarget[specs.size()];
        var layouts = new CaptureLayout[specs.size()];
        for (int i = 0; i < specs.size(); i++) {
            targets[i] = specs.get(i).target; layouts[i] = specs.get(i).captureLayout;
        }
        int index = scope.function.caseRegions.size();
        scope.function.caseRegions.add(new BytecodeCaseRegion(guards, width, targets, layouts, tail));
        var scrutinee = force(compile((List<Object>) expr.get(1), scope, false));
        return new LoweredCaseExpression(preparedRegion(inline, specs, scope, tail, index, scrutinee));
    }

    private Expression preparedRegion(Expression inline, List<FunctionSpec> specs, Scope scope, boolean tail,
            int index, Expression scrutinee) {
        var emission = scope.function.caseEmission;
        return new ProvenExpression(new ResultExpression((e, destination) -> {
            var b = e.builder;
            b.beginBlock();
            var answer = destination == null ? b.createLocal("case choice result", FrameSlotKind.Object) : null;
            var complete = b.createLabel();
            if (emission.inline) {
                b.beginIfThen();
                b.emitInlineCaseRegions();
                b.beginBlock();
                if (answer != null) b.beginStaticStoreObject(answer);
                emitResult(inline, e, destination);
                if (answer != null) b.endStaticStoreObject();
                b.emitBranch(complete);
                b.endBlock(); b.endIfThen();
            }
            var captures = new LocalAccessor[specs.size()][];
            for (int i = 0; i < captures.length; i++) {
                var locals = specs.get(i).captures;
                captures[i] = new LocalAccessor[locals.size()];
                for (int j = 0; j < locals.size(); j++)
                    captures[i][j] = LocalAccessor.constantOf(Objects.requireNonNull(e.locals.get(locals.get(j).id)));
            }
            var result = destination == null ? null : tupleSlots(new TupleShape(inline.proof(), language), destination);
            if (answer != null) b.beginStaticStoreObject(answer);
            emitCaseRegionCall(e, scope.function, index, new BytecodeCaseRegion.Source(captures, result), scrutinee, tail);
            if (answer != null) b.endStaticStoreObject();
            b.emitLabel(complete);
            if (answer != null) b.emitStaticLoadObject(answer);
            b.endBlock();
        }), inline.proof());
    }

    private void emitCaseRegionCall(Emission e, FunctionContext context, int index, BytecodeCaseRegion.Source source,
            Expression scrutinee, boolean tail) {
        var b = e.builder;
        b.beginBlock();
        var mask = b.createLocal("case region caller mask", FrameSlotKind.Object);
        b.beginStaticStoreObject(mask); b.emitCurrentMask(); b.endStaticStoreObject();
        var result = b.createLocal("case region result", FrameSlotKind.Object);
        if (resumable) b.beginTryCatch();
        b.beginStaticStoreObject(result);
        b.beginCallCaseRegion(index, source); scrutinee.emit(e); b.emitStaticLoadObject(mask); b.endCallCaseRegion();
        b.endStaticStoreObject();
        if (resumable) {
            b.beginBlock();
            var suspended = b.createLocal("case region suspension", FrameSlotKind.Object);
            b.beginStaticStoreObject(suspended); b.beginCallSuspensionOnly(); b.emitLoadException();
            b.endCallSuspensionOnly(); b.endStaticStoreObject();
            if (source.destination() == null) { b.beginStaticStoreObject(result); b.beginResumeApplication(); }
            else b.beginResumeTupleApplication(source.destination());
            b.emitStaticLoadObject(suspended);
            b.beginReenterCallMask(); beginAnnotationYield(e);
            b.beginParkCallMask(); b.emitStaticLoadObject(suspended);
            b.emitStaticLoadObject(Objects.requireNonNull(e.checkpointRootEntry)); b.emitStaticLoadObject(mask);
            b.endParkCallMask(); endAnnotationYield(e); b.emitStaticLoadObject(mask); b.endReenterCallMask();
            if (source.destination() == null) { b.endResumeApplication(); b.endStaticStoreObject(); }
            else {
                b.endResumeTupleApplication();
                // Match CallCaseRegion's ordinary tuple completion before the tail check.
                b.beginStaticStoreObject(result); b.emitLoadConstant(thc.runtime.Unit.INSTANCE); b.endStaticStoreObject();
            }
            b.endBlock(); b.endTryCatch();
        }
        if (tail && context.mayLoop) {
            b.beginIfThen();
            b.beginIsTailReentry(false); b.emitStaticLoadObject(result); b.endIsTailReentry();
            b.beginBlock(); restoreTailArguments(e, context, result);
            b.emitBranch(Objects.requireNonNull(e.continueLabel)); b.endBlock(); b.endIfThen();
        }
        if (source.destination() == null) b.emitStaticLoadObject(result);
        b.endBlock();
    }

    private Expression inlineCase(List<Object> expr, Scope scope, boolean tail) {
        return inlineCase(expr, scope, tail, false);
    }

    private Expression inlineCase(List<Object> expr, Scope scope, boolean tail, boolean preparedDecision) {
        var vectorRead = CoreVectorMemory.readCase(expr, constructors);
        if (vectorRead != null) return vectorReadCase(vectorRead, scope, tail);
        var local = scope.child();
        var scrutineeExpr = (List<Object>) expr.get(1);
        var scrutinee = force(compile(scrutineeExpr, scope, false));
        var binderProof = evaluatedProof(scrutinee.proof().refine(CoreRepresentations.caseBinder(expr)), true);
        if (binderProof.isTypedTransport() && ((List<?>) expr.get(3)).isEmpty())
            return tupleOrVectorCase(expr, scrutinee, binderProof, local, tail);
        if (binderProof.isSum()) return sumCase(expr, scrutinee, binderProof, local, tail);
        if (binderProof.isTuple() || binderProof.isVector()) return tupleOrVectorCase(expr, scrutinee, binderProof, local, tail);
        var binder = bind(local, (String) expr.get(2), true, binderProof);
        if ("var".equals(scrutineeExpr.getFirst()) && !Objects.equals(scrutineeExpr.get(1), expr.get(2))) {
            var id = (String) scrutineeExpr.get(1);
            var old = scope.locals.get(id);
            if (old != null) local.locals.put(id, new Local(old.id, old.name, old.primitive,
                evaluatedProof(old.proof, true), old.cell, old.entry, old.arityCertificate));
        }
        record Alternative(String kind, Object value, List<List<Local>> fields, int[] deadReferences, Expression body) {}
        var alternatives = new ArrayList<Alternative>();
        for (var alt : (List<List<Object>>) expr.get(3)) {
            var child = local.child();
            var kind = (String) alt.getFirst();
            Object value = switch (kind) {
                case "lit" -> {
                    var literal = (List<String>) alt.get(1);
                    if (Set.of("bignat", "rubbish").contains(literal.getFirst()))
                        throw new UnsupportedCore("BigNat/rubbish literal alternatives are invalid GHC Core");
                    if (Set.of("float", "double").contains(literal.getFirst()))
                        throw new UnsupportedCore("Floating literal alternatives are invalid GHC Core");
                    yield literal(literal.getFirst(), literal.get(1), UNKNOWN);
                }
                case "data" -> dataLayout((String) alt.get(1));
                case "default" -> alt.get(1);
                default -> throw new RuntimeFault("Invalid Core alternative kind " + kind);
            };
            var ids = (List<String>) alt.get(2);
            var layout = value instanceof DataLayout data ? data : null;
            if (layout != null && layout.getLogicalArity() != ids.size()) throw new RuntimeFault("Constructor field/binder mismatch");
            var metadata = CoreRepresentations.alternativeBinders(alt);
            var fields = new ArrayList<List<Local>>();
            for (int index = 0; index < ids.size(); ++index) {
                var id = ids.get(index);
                int physical = layout == null ? index : layout.fieldOffset(index);
                var logicalProof = layout == null ? null : layout.logicalProof(index);
                var aggregate = logicalProof != null && logicalProof.isAggregate() ? logicalProof : null;
                var vector = aggregate == null && layout != null ? layout.vectorProof(physical) : null;
                if (aggregate != null) {
                    if (index >= metadata.size()) throw new RuntimeFault("Missing aggregate constructor binder proof");
                    var record = metadata.get(index);
                    if (!Boolean.FALSE.equals(record.get("lifted"))) throw new RuntimeFault("Aggregate constructor binder must be unlifted");
                    var actual = CoreRepresentations.binder(record);
                    TupleShape.requireCompatible(aggregate, actual, true);
                    var proof = aggregate.refine(actual);
                    var leaves = proof.isSum() ? SumShape.storage(proof) : TupleShape.flatten(proof);
                    var lanes = new ArrayList<Local>();
                    for (var leaf : leaves) lanes.add(new Local(nextLocal++, id + " aggregate " + lanes.size(), leaf.isLong(), leaf));
                    child.bindTuple(id, proof, lanes);
                    for (var lane : lanes) fields.add(List.of(lane));
                } else if (vector != null) {
                    if (index >= metadata.size()) throw new UnsupportedCore("Missing vector constructor binder proof");
                    var record = metadata.get(index);
                    if (!Boolean.FALSE.equals(record.get("lifted"))) throw new UnsupportedCore("Vector constructor binder must be unlifted");
                    var proof = CoreRepresentations.binder(record).refine(vector);
                    var lanes = new ArrayList<Local>();
                    for (var leaf : TupleShape.flatten(proof))
                        lanes.add(new Local(nextLocal++, id + " vector lane " + lanes.size(), leaf.isLong(), leaf));
                    child.bindTuple(id, proof, lanes); fields.add(lanes);
                } else {
                    var proof = index < metadata.size() ? CoreRepresentations.binder(metadata.get(index)) : UNKNOWN;
                    fields.add(List.of(bind(child, id, layout != null && layout.isLong(physical),
                        evaluatedProof(proof, layout != null && fieldIsEvaluated((String) alt.get(1), index)))));
                }
            }
            alternatives.add(new Alternative(kind, value, fields, CoreFreeVariables.unusedConstructorReferences(alt, layout),
                compile((List<Object>) alt.get(3), child, tail)));
        }
        boolean unusedBinder = ((List<List<Object>>) expr.get(3)).stream()
            .noneMatch(alt -> CoreFreeVariables.coreFreeVariables((List<Object>) alt.get(3)).contains(expr.get(2)));
        var explicit = new ArrayList<Alternative>();
        Alternative defaultArm = null;
        var categories = new ArrayList<Integer>(alternatives.size());
        boolean longLiterals = true;
        for (var alt : alternatives) {
            if ("default".equals(alt.kind)) defaultArm = alt;
            else explicit.add(alt);
            categories.add(switch (alt.kind) { case "default" -> 0; case "data" -> 1; default -> 2; });
            if ("lit".equals(alt.kind) && !(alt.value instanceof Long)) longLiterals = false;
        }
        var fallback = defaultArm;
        var category = CaseCategories.caseCategory(binderProof, categories, longLiterals);
        // Preserve original order for small cases and duplicate labels; signed comparison retains all Word# bits.
        boolean orderLiterals = binderProof.isLong() && explicit.size() > 8;
        if (orderLiterals) {
            var values = new HashSet<Long>();
            for (var alt : explicit) if (!"lit".equals(alt.kind) || !(alt.value instanceof Long value) || !values.add(value)) {
                orderLiterals = false;
                break;
            }
        }
        var ordered = orderLiterals ? new ArrayList<>(explicit) : null;
        if (ordered != null) ordered.sort(java.util.Comparator.comparingLong(a -> (Long) a.value));
        var resultProof = CoreRepresentations.caseResult(expr);
        var results = new ArrayList<CoreRepresentation>(alternatives.size());
        for (var alt : alternatives) results.add(alt.body.proof());
        CoreRepresentations.validateDeclaredCaseResult(resultProof, results);
        CoreRepresentations.validateAggregateCaseResult(resultProof, results);
        CoreRepresentations.validateFloatingCaseResult(resultProof, results);
        CoreRepresentation effectiveResult;
        boolean anyAggregate = false;
        boolean allAggregate = true;
        if (!resultProof.isAggregate()) for (var result : results) {
            if (result.isAggregate()) anyAggregate = true;
            else allAggregate = false;
        }
        if (!resultProof.isAggregate() && anyAggregate) {
            if (!allAggregate)
                throw new UnsupportedCore("Missing exact aggregate case result proof");
            effectiveResult = results.getFirst().refine(resultProof);
        } else effectiveResult = resultProof;
        var vectorResult = CoreVectors.caseResult(results);
        var mergedProof = vectorResult != null ? vectorResult.refine(effectiveResult)
            : evaluatedProof(effectiveResult, allProofsEvaluated(results));
        return new LoweredCaseExpression(new ProvenExpression(new ResultExpression((e, destination) -> {
            var b = e.builder;
            b.beginBlock();
            e.locals.put(binder.id, b.createLocal(binder.name, FrameLayout.carrierKind(binderProof)));
            // A case binder is written once, before its alternatives. A saved
            // activation retains that same slot; resumption does not rebind it.
            if (staticWideLong(binderProof)) e.staticLocals.add(binder.id);
            else if (binderProof.getKind() == CoreKind.VOID || staticBoxedReference(binderProof))
                e.staticObjectLocals.add(binder.id);
            if (category == CaseCategory.GENERIC && !e.staticLocals.contains(binder.id)) {
                if (e.staticObjectLocals.contains(binder.id)) {
                    b.beginStaticStoreObject(e.locals.get(binder.id)); scrutinee.emit(e); b.endStaticStoreObject();
                } else {
                    b.beginStoreLocal(e.locals.get(binder.id)); scrutinee.emit(e); b.endStoreLocal();
                }
            } else restoreArgument(e, binder, () -> scrutinee.emit(e));
            class Choice {
                void emitAlternative(Alternative alt) {
                    b.beginBlock();
                    for (int index = 0; index < alt.fields.size(); ++index) {
                        var fields = alt.fields.get(index);
                        for (var field : fields)
                            e.locals.put(field.id, b.createLocal(field.name, FrameLayout.carrierKind(field.proof)));
                        var layout = (DataLayout) alt.value;
                        if (layout.isVector(index)) {
                            b.beginTransferDataVector(new BytecodeRoot.DataVectorTransfer(layout, index,
                                accessors(localSlots(e, fields)), false));
                            read(binder, false).emit(e); b.endTransferDataVector();
                        } else {
                            var field = fields.getFirst();
                            var kind = layout.isInt(index) ? FrameSlotKind.Int : layout.isLong(index) ? FrameSlotKind.Long
                                : layout.isFloat(index) ? FrameSlotKind.Float : layout.isDouble(index) ? FrameSlotKind.Double
                                : FrameSlotKind.Object;
                            if (kind == FrameSlotKind.Object) e.staticObjectLocals.add(field.id);
                            else if (kind == FrameSlotKind.Long) e.staticLocals.add(field.id);
                            else e.staticScalars.put(field.id, kind);
                            b.beginRestoreDataScalar(layout, index, e.locals.get(field.id));
                            read(binder, false).emit(e); b.endRestoreDataScalar();
                        }
                    }
                    for (int index : alt.deadReferences) for (var field : alt.fields.get(index)) {
                        b.beginStaticStoreObject(e.locals.get(field.id)); b.emitLoadNull(); b.endStaticStoreObject();
                    }
                    if (unusedBinder && !binderProof.isLong() && binderProof.getKind() != CoreKind.FLOAT
                            && binderProof.getKind() != CoreKind.DOUBLE) {
                        if (e.staticObjectLocals.contains(binder.id)) {
                            b.beginStaticStoreObject(e.locals.get(binder.id)); b.emitLoadNull(); b.endStaticStoreObject();
                        } else {
                            b.beginStoreLocal(e.locals.get(binder.id)); b.emitLoadNull(); b.endStoreLocal();
                        }
                    }
                    emitResult(alt.body, e, destination); b.endBlock();
                    for (var fields : alt.fields) for (var field : fields) {
                        e.locals.remove(field.id); e.staticScalars.remove(field.id);
                        e.staticLocals.remove(field.id); e.staticObjectLocals.remove(field.id);
                    }
                }
                void emit(int index) {
                    if (index == explicit.size()) {
                        if (fallback == null) b.emitFailCase(); else emitAlternative(fallback);
                        return;
                    }
                    var alt = explicit.get(index);
                    if (explicit.size() == 1 && fallback == null && "data".equals(alt.kind)) {
                        // This is a checked constructor projection, not a choice
                        // between guest arms. Keep the mismatch failure cold.
                        var matched = b.createLabel();
                        b.beginIfThen();
                        if (category == CaseCategory.DATA) b.beginMatchDataValue((DataLayout) alt.value);
                        else b.beginMatchData((DataLayout) alt.value);
                        read(binder, false).emit(e);
                        if (category == CaseCategory.DATA) b.endMatchDataValue(); else b.endMatchData();
                        b.emitBranch(matched); b.endIfThen();
                        b.emitFailCase(); b.emitLabel(matched); emitAlternative(alt);
                        return;
                    }
                    if (destination == null) b.beginConditional(); else b.beginIfThenElse();
                    if (category == CaseCategory.DATA) b.beginMatchDataValue((DataLayout) alt.value);
                    else if ("data".equals(alt.kind)) b.beginMatchData((DataLayout) alt.value);
                    else b.beginMatchLiteral(Objects.requireNonNull(alt.value));
                    read(binder, false).emit(e);
                    if (category == CaseCategory.DATA) b.endMatchDataValue();
                    else if ("data".equals(alt.kind)) b.endMatchData();
                    else b.endMatchLiteral();
                    emitAlternative(alt); emit(index + 1);
                    if (destination == null) b.endConditional(); else b.endIfThenElse();
                }
            }
            var choice = new Choice();
            if (preparedDecision) {
                // The finite recovery decision must be compilable before any
                // arm has run. Preserve its ordered constructor guards without
                // Conditional's adaptive branch/merge instructions. Scalar
                // results use the same boxed merge contract; aggregate leaves
                // still write their exact caller-owned destination slots.
                var result = destination == null ? b.createLocal("case decision result", FrameSlotKind.Object) : null;
                var complete = b.createLabel();
                for (var alt : explicit) {
                    b.beginIfThen();
                    if ("data".equals(alt.kind)) b.beginMatchDataValue((DataLayout) alt.value);
                    else b.beginLiteralEqual((Long) alt.value);
                    read(binder, false).emit(e);
                    if ("data".equals(alt.kind)) b.endMatchDataValue(); else b.endLiteralEqual();
                    b.beginBlock();
                    if (result != null) b.beginStaticStoreObject(result);
                    choice.emitAlternative(alt);
                    if (result != null) b.endStaticStoreObject();
                    b.emitBranch(complete);
                    b.endBlock(); b.endIfThen();
                }
                if (fallback == null) b.emitFailCase();
                else {
                    if (result != null) b.beginStaticStoreObject(result);
                    choice.emitAlternative(fallback);
                    if (result != null) b.endStaticStoreObject();
                }
                b.emitLabel(complete);
                if (result != null) b.emitStaticLoadObject(result);
            } else if (ordered == null) choice.emit(0);
            else {
                // One copy of each arm and one shared default avoid nested merge growth.
                var result = destination == null ? b.createLocal("literal case result", null) : null;
                var labels = new ArrayList<BytecodeLabel>(ordered.size());
                for (int index = 0; index < ordered.size(); ++index) labels.add(b.createLabel());
                var unmatched = b.createLabel();
                var complete = b.createLabel();
                class Dispatch {
                    void emit(int from, int end) {
                        if (end - from <= 8) {
                            for (int index = from; index < end; ++index) {
                                b.beginIfThen(); b.beginMatchLiteral(Objects.requireNonNull(ordered.get(index).value));
                                read(binder, false).emit(e); b.endMatchLiteral(); b.emitBranch(labels.get(index)); b.endIfThen();
                            }
                            b.emitBranch(unmatched);
                        } else {
                            int middle = (from + end) >>> 1;
                            b.beginIfThenElse(); b.beginLiteralBelow((Long) ordered.get(middle).value);
                            read(binder, false).emit(e); b.endLiteralBelow();
                            b.beginBlock(); emit(from, middle); b.endBlock();
                            b.beginBlock(); emit(middle, end); b.endBlock(); b.endIfThenElse();
                        }
                    }
                    void selected(Alternative alt) {
                        if (result != null) b.beginStoreLocal(result);
                        choice.emitAlternative(alt);
                        if (result != null) b.endStoreLocal();
                        b.emitBranch(complete);
                    }
                }
                var dispatch = new Dispatch();
                dispatch.emit(0, ordered.size());
                for (int index = 0; index < ordered.size(); ++index) {
                    b.emitLabel(labels.get(index)); dispatch.selected(ordered.get(index));
                }
                b.emitLabel(unmatched);
                if (fallback == null) b.emitFailCase(); else dispatch.selected(fallback);
                b.emitLabel(complete);
                if (result != null) b.emitLoadLocal(result);
            }
            b.endBlock(); e.locals.remove(binder.id);
            e.staticLocals.remove(binder.id); e.staticObjectLocals.remove(binder.id);
        }), mergedProof));
    }

    /** Retains the don't-care until bytecode emission supplies its consumer's destination. */
    private final class RubbishExpression implements Expression {
        private final CoreRepresentation proof;
        RubbishExpression(CoreRepresentation proof) { this.proof = proof.withEvaluated(true); }
        @Override public CoreRepresentation proof() { return proof; }
        @Override public boolean writesDestination() { return proof.isTypedTransport(); }
        @Override public void emit(Emission emission) {
            if (proof.isAggregate()) throw new RuntimeFault("Rubbish aggregate requires a typed destination");
            constant(rubbishLiterals.decode(proof)).emit(emission);
        }
        @Override public void emitTuple(Emission emission, List<BytecodeLocal> destination) {
            if (proof.isTuple()) {
                var fields = new ArrayList<Expression>();
                for (var field : proof.getComponents()) fields.add(new RubbishExpression(field));
                tupleConstruct(new TupleShape(proof, language), fields).emitTuple(emission, destination);
            } else if (proof.isSum()) {
                sumConstruct(proof, 1, new RubbishExpression(proof.getAlternatives().getFirst())).emitTuple(emission, destination);
            } else if (proof.isVector()) {
                storeTupleResult(emission, destination.getFirst(), () -> emit(emission));
            } else throw new RuntimeFault("Scalar rubbish does not have a typed destination");
        }
    }

    private Expression sumConstruct(CoreRepresentation tupleProof, int tag, Expression payload) {
        var selected = tupleProof.getAlternatives().get(tag - 1);
        var shape = new TupleShape(tupleProof, language);
        var leaves = TupleShape.flatten(selected);
        return tupleExpression(tupleProof, (e, destination) -> {
            var b = e.builder;
            b.beginBlock();
            for (int index = 0; index < shape.getLeaves().length; ++index) {
                var field = shape.getLeaves()[index];
                b.beginStoreLocal(destination.get(index));
                if (field.isLong()) b.emitLoadConstant(0L);
                else if (field.isFloat()) b.emitLoadConstant(0.0f);
                else if (field.isDouble()) b.emitLoadConstant(0.0);
                else if (field.getKind() == CoreKind.ADDRESS) b.emitLoadConstant(ManagedAddress.nullAddress());
                else if (field.isVector()) b.emitLoadConstant(new VectorLayout(field).getSpecies().zero());
                else b.emitLoadNull();
                b.endStoreLocal();
            }
            var mapped = new ArrayList<BytecodeLocal>();
            for (int index : SumShape.projection(tupleProof, tag - 1)) mapped.add(destination.get(index));
            if (selected.isTypedTransport()) {
                var logical = new ArrayList<BytecodeLocal>();
                for (int index = 0; index < mapped.size(); ++index)
                    logical.add(leaves.get(index).isInt() ? b.createLocal("narrow sum payload " + index, null) : mapped.get(index));
                payload.emitTuple(e, logical);
                for (int index = 0; index < leaves.size(); ++index) {
                    var leaf = leaves.get(index);
                    if (leaf.isInt()) {
                        b.beginStoreLocal(mapped.get(index)); b.beginSumNarrowToWord(leaf.getNarrowInteger());
                        b.beginToInt(); b.emitLoadLocal(logical.get(index)); b.endToInt();
                        b.endSumNarrowToWord(); b.endStoreLocal();
                    }
                }
            } else if (selected.getKind() == CoreKind.VOID) {
                b.beginDiscardVoid(); payload.emit(e); b.endDiscardVoid();
            } else {
                b.beginStoreLocal(mapped.getFirst());
                if (selected.isInt()) b.beginSumNarrowToWord(selected.getNarrowInteger());
                payload.emit(e);
                if (selected.isInt()) b.endSumNarrowToWord();
                b.endStoreLocal();
            }
            b.beginStoreLocal(destination.getFirst()); b.emitLoadConstant((long) tag); b.endStoreLocal(); b.endBlock();
        });
    }

    private Expression tupleConstruct(TupleShape shape, List<Expression> operands) {
        return tupleExpression(shape.getProof(), (e, destination) -> {
            var b = e.builder;
            b.beginBlock();
            for (int index = 0; index < operands.size(); ++index) {
                var component = shape.getComponents()[index];
                int offset = shape.getOffsets()[index];
                var operand = operands.get(index);
                if (component.isTypedTransport()) operand.emitTuple(e, destination.subList(offset, offset + TupleShape.flatten(component).size()));
                else if (component.getKind() == CoreKind.VOID) { b.beginDiscardVoid(); operand.emit(e); b.endDiscardVoid(); }
                else {
                    storeTupleResult(e, destination.get(offset), () -> {
                        if (component.getKind() == CoreKind.ADDRESS) b.beginRequireAddress();
                        operand.emit(e);
                        if (component.getKind() == CoreKind.ADDRESS) b.endRequireAddress();
                    });
                }
            }
            b.endBlock();
        });
    }

    private Expression compileOrdinaryApplication(List<Object> expr, Scope scope, boolean tail,
            List<Object> fn, List<List<Object>> args, List<?> flags, boolean[] callStrict, CoreRepresentation tupleProof) {
        var metadata = (tupleProof.isSum() || tupleProof.isTuple()) && "con".equals(fn.getFirst())
            ? constructors.get(fn.get(1)) : null;
        if (tupleProof.isSum() && "con".equals(fn.getFirst()) && metadata != null && "unboxed-sum".equals(metadata.get("kind"))) {
            int tag = SumShape.constructor(tupleProof, metadata, fn.get(2));
            if (args.size() != 1) throw new RuntimeFault("Sum constructor must be saturated");
            var selected = tupleProof.getAlternatives().get(tag - 1);
            boolean lifted = CoreRepresentations.argumentMayBeLazy(flags.getFirst(), args.getFirst());
            var payload = selected.isTypedTransport() ? compile(args.getFirst(), scope, false) : argument(args.getFirst(), scope, lifted);
            SumShape.payload(selected, payload.proof(), (Boolean) flags.getFirst());
            return sumConstruct(tupleProof, tag, payload);
        }
        if (tupleProof.isTuple() && "con".equals(fn.getFirst()) && metadata != null && "unboxed-tuple".equals(metadata.get("kind"))) {
            var shape = new TupleShape(tupleProof, language);
            if (shape.getComponents().length != args.size() || ((Number) fn.get(2)).intValue() != args.size()
                    || !(metadata.get("arity") instanceof Number arity) || arity.intValue() != args.size())
                throw new RuntimeFault("Tuple constructor arity mismatch");
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) {
                var arg = args.get(index);
                var component = shape.getComponents()[index];
                TupleShape.requireCompatible(component, CoreRepresentations.expression(arg), true);
                if (component.isTypedTransport() && !Boolean.FALSE.equals(flags.get(index)))
                    throw new RuntimeFault("Typed tuple field cannot be lifted");
                if (component.isTypedTransport()) operands.add(compile(arg, scope, false));
                else {
                    boolean lifted = CoreRepresentations.argumentMayBeLazy(flags.get(index), args.get(index));
                    operands.add(argument(arg, scope, lifted));
                }
            }
            return tupleConstruct(shape, operands);
        }
        var constructor = "con".equals(fn.getFirst()) ? dataLayout((String) fn.get(1)) : null;
        if (constructor != null && args.size() > constructor.getLogicalArity())
            throw new RuntimeFault("Constructor arity mismatch: " + fn.get(1));
        var strict = constructor != null && ((Number) fn.get(2)).intValue() == args.size()
            ? strictConstructorFields((String) fn.get(1), args.size()) : null;
        var entry = switch ((String) fn.getFirst()) {
            case "lam" -> CoreEntries.lambda(fn);
            case "var" -> {
                var id = (String) fn.get(1);
                var join = scope.joins.get(id);
                yield join != null ? join.entryStrict : scope.locals.containsKey(id) ? scope.locals.get(id).entry : globalEntries.get(id);
            }
            default -> null;
        };
        var entryStrict = entry != null && args.size() >= entry.length ? entry : null;
        var operands = new ArrayList<Expression>();
        for (int index = 0; index < args.size(); ++index) {
            var arg = args.get(index);
            boolean lifted = CoreRepresentations.argumentMayBeLazy(flags.get(index), args.get(index));
            var logical = constructor == null ? null : constructor.logicalProof(index);
            var aggregate = logical != null && logical.isAggregate() ? logical : null;
            var vectorField = aggregate == null && constructor != null
                ? constructor.vectorProof(constructor.fieldOffset(index)) : null;
            Expression operand;
            if (aggregate != null && strict != null) {
                if (lifted) throw new RuntimeFault("Aggregate constructor operand must be unlifted");
                operand = compile(arg, scope, false);
                TupleShape.requireCompatible(aggregate, operand.proof(), true);
            } else {
                operand = argument(arg, scope, lifted && !callStrict[index] && !(strict != null && strict[index])
                    && !(entryStrict != null && index < entryStrict.length && entryStrict[index]), "argument thunk",
                    vectorField != null || !"prim".equals(fn.getFirst()) && !"con".equals(fn.getFirst()), lifted);
                if (vectorField != null) {
                    if (!operand.proof().isVector()) throw new RuntimeFault("Constructor vector field requires an exact vector operand");
                    TupleShape.requireCompatible(vectorField, operand.proof(), false);
                }
            }
            operands.add(operand);
        }
        if ("var".equals(fn.getFirst()) && scope.joins.containsKey(fn.get(1))) return joinCall(scope.joins.get(fn.get(1)), operands);
        if ("prim".equals(fn.getFirst())) {
            var value = primitive((String) fn.get(1), operands, CoreExceptionPayload.validate(expr));
            if ("raise#".equals(fn.get(1)) && tupleProof.isTypedTransport()) return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                b.beginBlock(); b.beginStoreLocal(b.createLocal("non-returning aggregate", null));
                value.emit(e); b.endStoreLocal(); b.endBlock();
            });
            return value;
        }
        if (strict != null) return construct(dataLayout((String) fn.get(1)), operands);
        return tupleProof.isTypedTransport() ? tupleApplication(new TupleShape(tupleProof, language), compile(fn, scope, false), operands, scope, tail)
            : application(compile(fn, scope, false), operands, scope, tail);
    }

    private Expression compileApplication(List<Object> expr, Scope scope, boolean tail) {
        var fn = (List<Object>) expr.get(1);
        var args = (List<List<Object>>) expr.get(2);
        if (expr.size() <= 3 || !(expr.get(3) instanceof List<?> flags)) throw new RuntimeFault("Application lacks representation flags");
        if (flags.size() != args.size()) throw new RuntimeFault("Application representation flag count mismatch");
        var callStrict = CoreCallDemands.lowerApplication(expr, callDemandsEnabled);
        var tupleProof = CoreRepresentations.expression(expr);
        var tupleOperation = "prim".equals(fn.getFirst()) ? TupleArithmeticOp.named((String) fn.get(1)) : null;
        var floatDecode = "prim".equals(fn.getFirst()) ? FloatDecodeOp.named((String) fn.get(1)) : null;
        var metadata = CoreRepresentations.metadata(expr);
        boolean defined = "var".equals(fn.getFirst()) && (scope.locals.containsKey(fn.get(1)) || scope.joins.containsKey(fn.get(1))
            || (demand != null && metadata != null && metadata.containsKey("foreignCall")
                ? demand.isDefined((String) fn.get(1)) : globals.containsKey(fn.get(1))));
        var cpuAffinity = CoreCpuAffinity.validate(expr, defined || fn.size() > 1 && scope.joins.containsKey(fn.get(1)));
        var runtimeService = CoreRuntimeServices.validate(expr, defined || fn.size() > 1 && scope.joins.containsKey(fn.get(1)));
        var representations = argumentMetadata(args);
        var resultRepresentation = metadata == null ? null : metadata.get("rep");
        var override = CoreForeignOverride.select(metadata);
        var stdioCandidate = override == CoreForeignOverride.STDIO ? CoreOriginalStdio.validate(metadata, representations, flags, resultRepresentation) : null;
        var nativeOpening = CoreOriginalStdio.windowsOpening(stdioCandidate, metadata, representations, flags, resultRepresentation, packageScalarLinks);
        var packageScalar = nativeOpening != null ? nativeOpening : override == null && cpuAffinity == null && runtimeService == null
            ? CorePackageScalarForeign.validate(metadata, representations, flags, resultRepresentation, packageScalarLinks) : null;
        boolean stackClone = override == CoreForeignOverride.STACK && CoreStackForeign.validate(metadata, representations, flags);
        var stackInfo = override == CoreForeignOverride.STACK_INFO ? CoreStackInfoForeign.validate(metadata, representations, flags, resultRepresentation) : null;
        var originalStdio = nativeOpening == null ? stdioCandidate : null;
        var originalProcess = override == CoreForeignOverride.PROCESS ? CoreProcessForeign.validate(metadata, representations, flags, resultRepresentation) : null;
        boolean stableFree = override == CoreForeignOverride.STABLE_FREE && CoreStablePointers.validate(metadata, representations, flags, resultRepresentation);
        var sharedCAF = override == CoreForeignOverride.SHARED_CAF ? CoreSharedCAFStores.validate(metadata, representations, flags, resultRepresentation) : null;
        var shutdown = override == CoreForeignOverride.SHUTDOWN ? CoreRtsShutdown.validate(metadata, representations, flags, resultRepresentation) : null;
        boolean mainThreadForeign = override == CoreForeignOverride.MAIN_THREAD && CoreMainThreadForeign.validate(metadata, representations, flags, resultRepresentation);
        boolean boundThreadForeign = override == CoreForeignOverride.BOUND_THREAD && CoreBoundThreadForeign.validate(metadata, representations, flags, resultRepresentation, false);
        var gcForeign = override == CoreForeignOverride.GC ? CoreGcForeign.validate(metadata, representations, flags, resultRepresentation) : null;
        var rtsEventForeign = override == CoreForeignOverride.RTS_EVENT ? CoreRtsEventForeign.validate(metadata, representations, flags, resultRepresentation) : null;
        boolean allocationCounterForeign = override == CoreForeignOverride.ALLOCATION_COUNTER && CoreBoundThreadForeign.validate(metadata, representations, flags, resultRepresentation, true);
        var stringRts = override == CoreForeignOverride.STRING_RTS ? CoreStringRtsForeign.validate(metadata, representations, flags, resultRepresentation) : null;
        var environment = override == CoreForeignOverride.ENVIRONMENT ? CoreEnvironmentForeign.validate(metadata, representations, flags, resultRepresentation) : null;
        var threadIdForeign = override == CoreForeignOverride.THREAD_ID ? CoreThreadIdForeign.validate(metadata, representations, flags, resultRepresentation) : null;
        var rtsDiagnostic = override == CoreForeignOverride.RTS_DIAGNOSTIC ? CoreRtsDiagnosticForeign.validate(metadata, representations, flags, resultRepresentation) : null;
        if (threadIdForeign != null || mainThreadForeign || rtsDiagnostic == RtsDiagnosticOp.STACK)
            CoreBoxedForeignDeclarations.requireCall(boxedForeignDeclarations, metadata);
        var rtsArguments = override == CoreForeignOverride.RTS_ARGUMENTS ? CoreRtsArgumentsForeign.validate(metadata, representations, flags, resultRepresentation) : null;
        var managedFile = override == CoreForeignOverride.MANAGED_FILE ? CoreManagedFiles.validate(metadata, representations, flags, resultRepresentation) : null;
        var javascript = packageScalar == null && !stackClone && stackInfo == null && originalStdio == null && managedFile == null
            ? CoreJavaScript.validate(expr, defined) : null;
        var processSignal = override == CoreForeignOverride.SIGNAL ? CoreSignalForeign.validate(metadata, representations, flags, resultRepresentation) : null;
        var nativeAllocation = override == CoreForeignOverride.ALLOCATION ? CoreNativeAllocationForeign.validate(metadata, representations, flags, resultRepresentation) : null;
        boolean memmove = override == CoreForeignOverride.MEMMOVE && CoreMemoryCopyForeign.MEMMOVE.validate(metadata, representations, flags, resultRepresentation);
        boolean memcpy = override == CoreForeignOverride.MEMCPY && CoreMemoryCopyForeign.MEMCPY.validate(metadata, representations, flags, resultRepresentation);
        var stringOp = TruffleStringOp.validate(expr, defined);
        if (stringOp != null) {
            var operands = new ArrayList<Expression>();
            for (var arg : args) operands.add(compile(arg, scope, false));
            java.util.function.Consumer<Emission> operation = e -> {
                e.builder.beginTruffleStringOperation(stringOp);
                for (var operand : operands) operand.emit(e);
                e.builder.endTruffleStringOperation();
            };
            return new ProvenExpression(e -> {
                var b = e.builder;
                switch (stringOp.result) {
                    case "IntRep", "Int64Rep" -> { b.beginToLong(); operation.accept(e); b.endToLong(); }
                    case "DoubleRep" -> { b.beginToDouble(); operation.accept(e); b.endToDouble(); }
                    default -> operation.accept(e);
                }
            }, evaluatedProof(tupleProof, true));
        }
        var vectorApi = VectorApiOp.validate(expr, defined);
        if (vectorApi != null) {
            if (vectorApi.javaArray() && foreignExceptionBridge == null)
                throw RuntimeFault.fault("Java vector array access requires a linked genuine THC.Exception runtime bundle");
            var operands = new ArrayList<Expression>();
            for (var arg : args) operands.add(compile(arg, scope, false));
            java.util.function.Consumer<Emission> operation = e -> {
                e.builder.beginVectorApi(vectorApi);
                for (var operand : operands) operand.emit(e);
                e.builder.endVectorApi();
            };
            if (vectorApi.tuple) return tupleExpression(tupleProof, (e, destination) ->
                storeTupleResult(e, destination.getFirst(), () -> operation.accept(e)));
            return new ProvenExpression(e -> {
                var b = e.builder;
                switch (vectorApi.result) {
                    case "Int8Rep", "Int16Rep", "Int32Rep" -> { b.beginToInt(); operation.accept(e); b.endToInt(); }
                    case "IntRep", "Int64Rep", "WordRep" -> { b.beginToLong(); operation.accept(e); b.endToLong(); }
                    case "FloatRep" -> { b.beginToFloat(); operation.accept(e); b.endToFloat(); }
                    case "DoubleRep" -> { b.beginToDouble(); operation.accept(e); b.endToDouble(); }
                    default -> operation.accept(e);
                }
            }, evaluatedProof(tupleProof, true));
        }
        PolyglotOp polyglot;
        try {
            polyglot = override == null && cpuAffinity == null && runtimeService == null &&
                packageScalar == null && javascript == null ? CorePolyglot.validate(expr, defined) : null;
        } catch (UnsupportedCore unavailable) {
            // Only the final unknown-symbol fallback is deferred. Known ABI validation above stays eager.
            String message = unavailable.getMessage();
            deferredUnsupported.add(message);
            return new Expression() {
                @Override public void emit(Emission emission) { emission.builder.emitUnsupportedForeign(message, metrics); }
                @Override public void emitTuple(Emission emission, List<BytecodeLocal> destination) {
                    // The operation never returns, and no tuple destination may be written.
                    var b = emission.builder;
                    b.beginBlock();
                    b.beginStoreLocal(b.createLocal("unavailable foreign tuple", null));
                    b.emitUnsupportedForeign(message, metrics);
                    b.endStoreLocal();
                    b.endBlock();
                }
            };
        }
        if ((packageScalar != null && packageScalar.executesForeign() || javascript != null || polyglot != null || runtimeService == RuntimeServiceCall.EXCEPTION_TEXT)
                && foreignExceptionBridge == null)
            throw RuntimeFault.fault("Foreign execution requires a linked genuine THC.Exception runtime bundle");
        if (runtimeService != null) {
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) {
                var argument = args.get(index);
                var lowered = compile(argument, scope, false);
                CoreRuntimeServices.validateOperand(runtimeService, index, lowered.proof(), lexicalProof(argument, scope));
                operands.add(lowered);
            }
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                switch (runtimeService) {
                    case QUERY -> b.beginRuntimeServiceQuery(destination.getFirst());
                    case CONTROL -> b.beginRuntimeServiceControl(destination.getFirst());
                    case TRACE -> b.beginRuntimeServiceTrace(destination.getFirst());
                    case EXCEPTION_TEXT -> b.beginExceptionText(destination.getFirst());
                }
                for (var operand : operands) operand.emit(e);
                switch (runtimeService) {
                    case QUERY -> b.endRuntimeServiceQuery();
                    case CONTROL -> b.endRuntimeServiceControl();
                    case TRACE -> b.endRuntimeServiceTrace();
                    case EXCEPTION_TEXT -> b.endExceptionText();
                }
                if (enableAsync && runtimeService == RuntimeServiceCall.EXCEPTION_TEXT) emitAsyncPoll(e);
            });
        }
        if (cpuAffinity != null) {
            var state = compile(args.getFirst(), scope, false);
            CoreBoundThreadForeign.validateOperand(state.proof(), lexicalProof(args.getFirst(), scope));
            return tupleExpression(tupleProof, (e, destination) -> {
                e.builder.beginCpuAffinityQuery(destination.getFirst(), cpuAffinity);
                state.emit(e); e.builder.endCpuAffinityQuery();
            });
        }
        if (stackClone) {
            CoreStackForeign.validateHead(fn, defined);
            var state = args.getFirst();
            CoreStackForeign.validateBinding(lexicalProof(state, scope));
            var operand = compile(state, scope, false);
            CoreStackForeign.validateState(operand.proof());
            return tupleExpression(tupleProof, (e, destination) -> {
                e.builder.beginCloneMyStack(destination.getFirst()); operand.emit(e); e.builder.endCloneMyStack();
            });
        }
        if (stackInfo != null) {
            var layout = CoreStackInfoForeign.requireLayout(stackTargetLayout);
            CoreStackInfoForeign.validateHead(fn, defined);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) {
                var argument = args.get(index);
                var operand = compile(argument, scope, false);
                CoreStackInfoForeign.validateOperand(stackInfo, index, operand.proof(), lexicalProof(argument, scope));
                operands.add(operand);
            }
            if (stackInfo == OriginalStackInfoOp.STACK_INFO) return new ProvenExpression(e -> {
                e.builder.beginOriginalStackInfo(layout); operands.getFirst().emit(e); e.builder.endOriginalStackInfo();
            }, evaluatedProof(tupleProof, true));
            if (stackInfo == OriginalStackInfoOp.STACK_FIELDS) return new ProvenExpression(e -> {
                e.builder.beginOriginalStackFields(layout); operands.getFirst().emit(e); e.builder.endOriginalStackFields();
            }, evaluatedProof(tupleProof, true));
            if (stackInfo == OriginalStackInfoOp.WORD) return new ProvenExpression(e -> {
                e.builder.beginOriginalStackWord(layout); for (var operand : operands) operand.emit(e); e.builder.endOriginalStackWord();
            }, evaluatedProof(tupleProof, true));
            if (!stackInfo.getTupleResult()) return new ProvenExpression(e -> {
                e.builder.beginOriginalStackIncompatibleGetter(layout, stackInfo);
                for (var operand : operands) operand.emit(e); e.builder.endOriginalStackIncompatibleGetter();
            }, evaluatedProof(tupleProof, true));
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                if (stackInfo == OriginalStackInfoOp.FRAME_INFO) b.beginOriginalStackFrameInfo(layout, destination.get(0), destination.get(1));
                else if (stackInfo == OriginalStackInfoOp.SMALL_BITMAP) b.beginOriginalStackSmallBitmap(layout, destination.get(0), destination.get(1));
                else if (stackInfo == OriginalStackInfoOp.ADVANCE) b.beginOriginalStackAdvance(layout, destination.get(0), destination.get(1), destination.get(2));
                else if (stackInfo == OriginalStackInfoOp.LOOKUP_IPE) b.beginOriginalStackLookupIpe(layout, destination.getFirst());
                else b.beginOriginalStackIncompatibleTupleGetter(layout, stackInfo);
                for (var operand : operands) operand.emit(e);
                if (stackInfo == OriginalStackInfoOp.FRAME_INFO) b.endOriginalStackFrameInfo();
                else if (stackInfo == OriginalStackInfoOp.SMALL_BITMAP) b.endOriginalStackSmallBitmap();
                else if (stackInfo == OriginalStackInfoOp.ADVANCE) b.endOriginalStackAdvance();
                else if (stackInfo == OriginalStackInfoOp.LOOKUP_IPE) b.endOriginalStackLookupIpe();
                else b.endOriginalStackIncompatibleTupleGetter();
            });
        }
        if (originalProcess != null) {
            CoreProcessForeign.validateHead(fn, defined);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) {
                var argument = args.get(index);
                var operand = compile(argument, scope, false);
                CoreProcessForeign.validateOperand(originalProcess, index, operand.proof(), lexicalProof(argument, scope));
                operands.add(operand);
            }
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                var locals = new ArrayList<BytecodeLocal>();
                for (int index = 0; index < operands.size(); ++index) {
                    var slot = b.createLocal("Original process operand " + index,
                        "Int32Rep".equals(originalProcess.getArguments().get(index)) ? "primitive" : "object");
                    b.beginStoreLocal(slot); operands.get(index).emit(e); b.endStoreLocal(); locals.add(slot);
                }
                var errno = b.createLocal("Completed process errno", "primitive");
                var arguments = new BytecodeProcessArguments(originalProcess, accessors(locals));
                b.emitOriginalProcess(arguments, destination.getFirst(), errno);
                if (enableAsync && originalProcess == ProcessOp.WAIT) {
                    emitAsyncPoll(e, destination.getFirst(), errno);
                    b.beginRestoreProcessErrno(); b.emitLoadLocal(errno); b.endRestoreProcessErrno();
                }
            });
        }
        if (originalStdio != null) return compileOriginalStdio(originalStdio, fn, defined, args, scope, tupleProof);
        if (packageScalar != null) {
            CoreCapiForeign.validateHead(fn, defined);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) {
                var argument = args.get(index);
                var operand = compile(argument, scope, false);
                CorePackageScalarForeign.validateOperand(packageScalar, index, operand.proof(), lexicalProof(argument, scope));
                operands.add(operand);
            }
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                var locals = new ArrayList<LocalAccessor>();
                for (int index = 0; index < operands.size(); ++index) {
                    var local = b.createLocal("Package C operand " + index, null);
                    var operand = operands.get(index);
                    b.beginStoreLocal(local);
                    var representation = index < packageScalar.getArguments().length ? packageScalar.getArguments()[index] : null;
                    switch (representation) {
                        case "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep", "Int32Rep", "Word32Rep" -> {
                            b.beginToInt(); operand.emit(e); b.endToInt();
                        }
                        case "IntRep", "WordRep", "Int64Rep", "Word64Rep" -> { b.beginToLong(); operand.emit(e); b.endToLong(); }
                        case "FloatRep" -> { b.beginToFloat(); operand.emit(e); b.endToFloat(); }
                        case "DoubleRep" -> { b.beginToDouble(); operand.emit(e); b.endToDouble(); }
                        case null, default -> operand.emit(e);
                    }
                    b.endStoreLocal(); locals.add(LocalAccessor.constantOf(local));
                }
                var arguments = new BytecodePackageScalarArguments(packageScalar,
                    locals.subList(0, locals.size() - 1).toArray(LocalAccessor[]::new), locals.getLast());
                switch (packageScalar.getResult()) {
                    case "Int8Rep", "Word8Rep", "Int16Rep", "Word16Rep", "Int32Rep", "Word32Rep" -> b.emitLinkedPackageScalarInt(arguments, destination.getFirst());
                    case "IntRep", "WordRep", "Int64Rep", "Word64Rep" -> b.emitLinkedPackageScalarLong(arguments, destination.getFirst());
                    case "FloatRep" -> b.emitLinkedPackageScalarFloat(arguments, destination.getFirst());
                    case "DoubleRep" -> b.emitLinkedPackageScalarDouble(arguments, destination.getFirst());
                    case "AddrRep" -> b.emitLinkedPackageAddress(arguments, destination.getFirst());
                    case "void" -> {
                        if (!destination.isEmpty()) throw RuntimeFault.fault("Void package C call has result slots");
                        b.emitLinkedPackageVoid(arguments);
                    }
                    default -> throw RuntimeFault.fault("Invalid package C result representation");
                }
                // Commit before the resumable cut: never replay an opaque completed foreign call.
                if (enableAsync && packageScalar.getSafety() == ForeignSafety.SAFE) emitAsyncPoll(e);
            });
        }
        if (stableFree) {
            CoreStablePointers.validateHead(fn, defined);
            var operands = compileOperands(args, scope);
            return tupleExpression(tupleProof, (e, destination) -> {
                if (!destination.isEmpty()) throw new RuntimeFault("StablePtr free has no result field");
                e.builder.beginFreeStablePointer(); for (var operand : operands) operand.emit(e); e.builder.endFreeStablePointer();
            });
        }
        if (sharedCAF != null) {
            CoreSharedCAFStores.validateHead(fn, defined);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) {
                var argument = args.get(index);
                var operand = compile(argument, scope, false);
                CoreSharedCAFStores.validateOperand(index, operand.proof(), lexicalProof(argument, scope));
                operands.add(operand);
            }
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                b.beginRtsSharedCAFStore(destination.getFirst(), sharedCAF);
                for (var operand : operands) operand.emit(e); b.endRtsSharedCAFStore();
            });
        }
        if (rtsArguments != null) {
            CoreRtsArgumentsForeign.validateHead(fn, defined);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) {
                var argument = args.get(index);
                var operand = compile(argument, scope, false);
                CoreRtsArgumentsForeign.validateOperand(rtsArguments, index, operand.proof(), lexicalProof(argument, scope));
                operands.add(operand);
            }
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                if (!destination.isEmpty()) throw RuntimeFault.fault("Program-arguments call has no result field");
                if (rtsArguments == RtsArgumentsOp.GET) b.beginGetProgramArguments(); else b.beginSetProgramArguments();
                for (var operand : operands) operand.emit(e);
                if (rtsArguments == RtsArgumentsOp.GET) b.endGetProgramArguments(); else b.endSetProgramArguments();
            });
        }
        if (threadIdForeign != null) {
            CoreThreadIdForeign.validateHead(fn, defined);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) {
                var argument = args.get(index); var operand = compile(argument, scope, false);
                CoreThreadIdForeign.validateOperand(threadIdForeign, index, operand.proof(), lexicalProof(argument, scope));
                operands.add(operand);
            }
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder; b.beginThreadIdForeign(destination.getFirst(), threadIdForeign);
                operands.getFirst().emit(e);
                if (threadIdForeign.getArguments() == 2) operands.get(1).emit(e); else b.emitLoadConstant(Unit.INSTANCE);
                operands.getLast().emit(e); b.endThreadIdForeign();
            });
        }
        if (rtsDiagnostic != null) {
            CoreRtsDiagnosticForeign.validateHead(fn, defined);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) {
                var argument = args.get(index);
                var operand = compile(argument, scope, false);
                CoreRtsDiagnosticForeign.validateOperand(rtsDiagnostic, index, operand.proof(), lexicalProof(argument, scope));
                operands.add(operand);
            }
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                if (!destination.isEmpty()) throw RuntimeFault.fault("RTS diagnostic has no result field");
                b.beginRtsDiagnostic(rtsDiagnostic);
                if (operands.size() > 1) operands.get(0).emit(e); else b.emitLoadConstant(thc.runtime.Unit.INSTANCE);
                if (operands.size() > 2) operands.get(1).emit(e); else b.emitLoadConstant(thc.runtime.Unit.INSTANCE);
                operands.getLast().emit(e); b.endRtsDiagnostic();
            });
        }
        if (rtsEventForeign != null) {
            CoreRtsEventForeign.validateHead(fn, defined);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) {
                var argument = args.get(index);
                var operand = compile(argument, scope, false);
                CoreRtsEventForeign.validateOperand(rtsEventForeign, index, operand.proof(), lexicalProof(argument, scope));
                operands.add(operand);
            }
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                if (rtsEventForeign.getResult() != null) {
                    b.beginRtsEventQuery(destination.getFirst(), rtsEventForeign); operands.getFirst().emit(e); b.endRtsEventQuery();
                } else {
                    if (!destination.isEmpty()) throw new IllegalStateException("Check failed.");
                    b.beginSetNumCapabilities(); for (var operand : operands) operand.emit(e); b.endSetNumCapabilities();
                }
                if (enableAsync && "safe".equals(rtsEventForeign.getSafety())) emitAsyncPoll(e);
            });
        }
        if (gcForeign != null) {
            CoreGcForeign.validateHead(fn, defined);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) {
                var argument = args.get(index);
                var operand = compile(argument, scope, false);
                CoreGcForeign.validateOperand(gcForeign, index, operand.proof(), lexicalProof(argument, scope));
                operands.add(operand);
            }
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                if (gcForeign.getResult() != null) {
                    b.beginGcForeignQuery(destination.getFirst(), gcForeign); operands.getFirst().emit(e); b.endGcForeignQuery();
                } else if (gcForeign == GcForeignOp.HEAP_HINT) {
                    if (!destination.isEmpty()) throw new IllegalStateException("Check failed.");
                    b.beginIgnoreHeapSizeHint(); for (var operand : operands) operand.emit(e); b.endIgnoreHeapSizeHint();
                } else if (gcForeign == GcForeignOp.STATS) {
                    if (!destination.isEmpty()) throw new IllegalStateException("Check failed.");
                    b.beginUnavailableRtsStats(); for (var operand : operands) operand.emit(e); b.endUnavailableRtsStats();
                } else {
                    if (!destination.isEmpty()) throw new IllegalStateException("Check failed.");
                    b.beginRequestGarbageCollection(gcForeign); operands.getFirst().emit(e); b.endRequestGarbageCollection();
                }
                if (enableAsync && "safe".equals(gcForeign.getSafety())) emitAsyncPoll(e);
            });
        }
        if (boundThreadForeign || allocationCounterForeign) {
            CoreBoundThreadForeign.validateHead(fn, defined);
            var argument = args.getFirst();
            var state = compile(argument, scope, false);
            CoreBoundThreadForeign.validateOperand(state.proof(), lexicalProof(argument, scope));
            return tupleExpression(tupleProof, (e, destination) -> {
                e.builder.beginBoundThreadSupport(destination.getFirst(), allocationCounterForeign);
                state.emit(e); e.builder.endBoundThreadSupport();
            });
        }
        if (environment != null) {
            CoreEnvironmentForeign.validateHead(fn, defined);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) {
                var argument = args.get(index);
                var operand = compile(argument, scope, false);
                CoreEnvironmentForeign.validateOperand(environment, index, operand.proof(), lexicalProof(argument, scope));
                operands.add(operand);
            }
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                switch (environment) {
                    case GET -> b.beginEnvironmentGet(destination.getFirst());
                    case PUT, UNSET, SET, CLEAR -> b.beginEnvironmentChange(environment, destination.getFirst());
                    case ENUMERATE -> b.beginEnvironmentEnumerate(destination.getFirst());
                }
                for (var operand : operands) operand.emit(e);
                switch (environment) {
                    case GET -> b.endEnvironmentGet();
                    case PUT, UNSET, SET, CLEAR -> b.endEnvironmentChange();
                    case ENUMERATE -> b.endEnvironmentEnumerate();
                }
            });
        }
        if (stringRts != null) {
            CoreStringRtsForeign.validateHead(fn, defined);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) {
                var argument = args.get(index);
                var operand = compile(argument, scope, false);
                CoreStringRtsForeign.validateOperand(stringRts, index, operand.proof(), lexicalProof(argument, scope));
                operands.add(operand);
            }
            long constantValue = stringRts.constantValue(stackTargetLayout);
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                if (stringRts.getArguments().size() == 2) b.beginOriginalCStringLength(destination.getFirst());
                else b.beginOriginalRtsConstant(destination.getFirst(), constantValue);
                for (var operand : operands) operand.emit(e);
                if (stringRts.getArguments().size() == 2) b.endOriginalCStringLength(); else b.endOriginalRtsConstant();
            });
        }
        if (shutdown != null) {
            CoreRtsShutdown.validateHead(fn, defined);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) {
                var argument = args.get(index);
                var operand = compile(argument, scope, false);
                CoreRtsShutdown.validateOperand(shutdown, index, operand.proof(), lexicalProof(argument, scope));
                operands.add(operand);
            }
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                if (!destination.isEmpty()) throw new IllegalStateException("Shutdown has no result field");
                b.beginShutdownRuntime(shutdown); for (var operand : operands) operand.emit(e); b.endShutdownRuntime();
            });
        }
        if (mainThreadForeign) {
            CoreMainThreadForeign.validateHead(fn, defined);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) {
                var argument = args.get(index);
                var operand = compile(argument, scope, false);
                CoreMainThreadForeign.validateOperand(index, operand.proof(), lexicalProof(argument, scope));
                operands.add(operand);
            }
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                if (!destination.isEmpty()) throw new RuntimeFault("Main-thread registration has no result field");
                b.beginRegisterMainThread(); for (var operand : operands) operand.emit(e); b.endRegisterMainThread();
            });
        }
        if (managedFile != null) {
            CoreManagedFiles.validateHead(fn, defined);
            var operands = compileOperands(args, scope);
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                var result = destination.getFirst();
                switch (managedFile) {
                    case OPEN -> b.beginFileOpen(result);
                    case READ -> b.beginFileRead(result);
                    case WRITE -> b.beginFileWrite(result);
                    case CLOSE -> b.beginFileClose(result);
                    case ERROR_KIND -> b.beginFileErrorKind(result);
                    case ERROR_MESSAGE -> b.beginFileErrorMessage(result);
                    case SEEK -> b.beginFileSeek(result);
                    case SIZE -> b.beginFileSize(result);
                    case SET_SIZE -> b.beginFileSetSize(result);
                    case IS_TERMINAL -> b.beginFileIsTerminal(result);
                    case DEVICE_TYPE -> b.beginFileDeviceType(result);
                }
                for (var operand : operands) operand.emit(e);
                switch (managedFile) {
                    case OPEN -> b.endFileOpen();
                    case READ -> b.endFileRead();
                    case WRITE -> b.endFileWrite();
                    case CLOSE -> b.endFileClose();
                    case ERROR_KIND -> b.endFileErrorKind();
                    case ERROR_MESSAGE -> b.endFileErrorMessage();
                    case SEEK -> b.endFileSeek();
                    case SIZE -> b.endFileSize();
                    case SET_SIZE -> b.endFileSetSize();
                    case IS_TERMINAL -> b.endFileIsTerminal();
                    case DEVICE_TYPE -> b.endFileDeviceType();
                }
                if (enableAsync) emitAsyncPoll(e);
            });
        }
        if (processSignal != null) {
            CoreSignalForeign.validateHead(fn, defined);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) {
                var argument = args.get(index);
                var operand = compile(argument, scope, false);
                CoreSignalForeign.validateOperand(processSignal, index, operand.proof(), lexicalProof(argument, scope));
                operands.add(operand);
            }
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                b.beginInstallProcessSignal(destination.getFirst()); for (var operand : operands) operand.emit(e); b.endInstallProcessSignal();
            });
        }
        if (nativeAllocation != null) {
            CoreNativeAllocationForeign.validateHead(fn, defined);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) {
                var argument = args.get(index);
                var operand = compile(argument, scope, false);
                CoreNativeAllocationForeign.validateOperand(nativeAllocation, index, operand.proof(), lexicalProof(argument, scope));
                operands.add(operand);
            }
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                if (nativeAllocation == NativeAllocationOp.MALLOC) b.beginNativeMalloc(destination.getFirst());
                else if (nativeAllocation == NativeAllocationOp.REALLOC) b.beginNativeRealloc(destination.getFirst());
                else { if (!destination.isEmpty()) throw RuntimeFault.fault("Native free has no result field"); b.beginNativeFree(); }
                for (var operand : operands) operand.emit(e);
                if (nativeAllocation == NativeAllocationOp.MALLOC) b.endNativeMalloc();
                else if (nativeAllocation == NativeAllocationOp.REALLOC) b.endNativeRealloc(); else b.endNativeFree();
            });
        }
        if (memmove) {
            CoreMemoryCopyForeign.MEMMOVE.validateHead(fn, defined);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) {
                var argument = args.get(index);
                var operand = compile(argument, scope, false);
                CoreMemoryCopyForeign.MEMMOVE.validateOperand(index, operand.proof(), lexicalProof(argument, scope), false);
                operands.add(operand);
            }
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                b.beginOriginalMemmove(destination.getFirst()); for (var operand : operands) operand.emit(e); b.endOriginalMemmove();
            });
        }
        if (memcpy) {
            CoreMemoryCopyForeign.MEMCPY.validateHead(fn, defined);
            boolean byteArrays = CoreMemoryCopyForeign.MEMCPY.byteArrays(metadata);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) {
                var argument = args.get(index);
                var operand = compile(argument, scope, false);
                CoreMemoryCopyForeign.MEMCPY.validateOperand(index, operand.proof(), lexicalProof(argument, scope), byteArrays);
                operands.add(operand);
            }
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                b.beginOriginalMemcpy(destination.getFirst());
                for (int index = 0; index < operands.size(); ++index) {
                    if (byteArrays && index < 2) b.beginByteArrayContents();
                    operands.get(index).emit(e);
                    if (byteArrays && index < 2) b.endByteArrayContents();
                }
                b.endOriginalMemcpy();
            });
        }
        if (javascript != null) {
            var operands = argumentOperands(args, scope);
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                var locals = new ArrayList<LocalAccessor>();
                for (int index = 0; index < operands.size(); ++index) {
                    var local = b.createLocal("JavaScript operand " + index, null);
                    var operand = operands.get(index);
                    b.beginStoreLocal(local);
                    var representation = index < javascript.getArguments().length ? javascript.getArguments()[index] : null;
                    if (representation == CoreKind.LONG) { b.beginToLong(); operand.emit(e); b.endToLong(); }
                    else if (representation == CoreKind.DOUBLE) { b.beginToDouble(); operand.emit(e); b.endToDouble(); }
                    else operand.emit(e);
                    b.endStoreLocal(); locals.add(LocalAccessor.constantOf(local));
                }
                var source = new BytecodeJavaScriptArguments(javascript,
                    locals.subList(0, locals.size() - 1).toArray(LocalAccessor[]::new), locals.getLast());
                switch (javascript.getResult()) {
                    case LONG -> b.emitJavaScriptInt(source, destination.getFirst());
                    case DOUBLE -> b.emitJavaScriptDouble(source, destination.getFirst());
                    case VOID -> b.emitJavaScriptVoid(source);
                    default -> throw new RuntimeFault("Unsupported JavaScript result");
                }
                if (enableAsync && javascript.getSafety() == ForeignSafety.SAFE) emitAsyncPoll(e);
            });
        }
        if (polyglot != null) {
            var operands = new ArrayList<Expression>();
            // Opaque Value handles are demanded before foreign execution, using
            // the same saved force checkpoint as other strict guest operands.
            for (var arg : args) operands.add(argument(arg, scope, false));
            if (polyglot == PolyglotOp.GET_LIBRARY) return new ProvenExpression(e -> {
                e.builder.beginInteropLibraryGet(); operands.getFirst().emit(e); e.builder.endInteropLibraryGet();
            }, evaluatedProof(tupleProof, true));
            if (polyglot.explicitLibrary()) {
                java.util.function.Consumer<Emission> message = e -> {
                    e.builder.beginInteropMessage(polyglot);
                    for (var operand : operands) operand.emit(e);
                    e.builder.endInteropMessage();
                };
                if (polyglot.scalarResult()) return new ProvenExpression(e -> {
                    var b = e.builder;
                    b.beginBlock(); b.beginStoreLocal(b.createLocal()); message.accept(e); b.endStoreLocal();
                    if (enableAsync) emitAsyncPoll(e);
                    b.emitLoadConstant(Unit.INSTANCE); b.endBlock();
                }, evaluatedProof(tupleProof, true));
                return tupleExpression(tupleProof, (e, destination) -> {
                    storeTupleResult(e, destination.getFirst(), () -> {
                        var b = e.builder;
                        switch (polyglot.getResult()) {
                            case "Int8Rep", "Int16Rep", "Int32Rep", "Word8Rep", "Word16Rep", "Word32Rep" -> { b.beginToInt(); message.accept(e); b.endToInt(); }
                            case "IntRep", "Int64Rep" -> { b.beginToLong(); message.accept(e); b.endToLong(); }
                            case "FloatRep" -> { b.beginToFloat(); message.accept(e); b.endToFloat(); }
                            case "DoubleRep" -> { b.beginToDouble(); message.accept(e); b.endToDouble(); }
                            default -> message.accept(e);
                        }
                    });
                    if (enableAsync) emitAsyncPoll(e);
                });
            }
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                switch (polyglot) {
                    case EVAL -> b.beginPolyglotEval(destination.get(0));
                    case READ_MEMBER -> b.beginPolyglotReadMember(destination.get(0));
                    case EXECUTE_INT -> b.beginPolyglotExecuteInt(destination.get(0));
                    default -> b.beginPolyglotStorage(destination.get(0), polyglot);
                }
                for (var operand : operands) operand.emit(e);
                switch (polyglot) {
                    case EVAL -> b.endPolyglotEval(); case READ_MEMBER -> b.endPolyglotReadMember(); case EXECUTE_INT -> b.endPolyglotExecuteInt();
                    default -> b.endPolyglotStorage();
                }
                if (enableAsync) emitAsyncPoll(e);
            });
        }
        if ("prim".equals(fn.getFirst())) {
            var special = compilePrimitiveApplication((String) fn.get(1), expr, args, flags, tupleProof, scope, tail);
            if (special != null) return special;
        }
        if (floatDecode != null) {
            floatDecode.validate(argumentProofs(args), flags, tupleProof);
            var operand = argument(args.getFirst(), scope, false);
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                if (floatDecode == FloatDecodeOp.DOUBLE_WORDS) {
                    b.beginDecodeDoubleWords(destination.get(0), destination.get(1), destination.get(2), destination.get(3));
                    operand.emit(e); b.endDecodeDoubleWords();
                } else if (floatDecode == FloatDecodeOp.FLOAT) {
                    b.beginDecodeFloat(destination.get(0), destination.get(1)); operand.emit(e); b.endDecodeFloat();
                } else {
                    b.beginDecodeDouble(destination.get(0), destination.get(1)); operand.emit(e); b.endDecodeDouble();
                }
            });
        }
        if (tupleOperation != null) {
            tupleOperation.validate(argumentProofs(args), flags, tupleProof);
            var operands = argumentOperands(args, scope);
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                if (tupleOperation == TupleArithmeticOp.QUOT_REM_WORD_2) {
                    b.beginDoubleWordDivision(destination.get(0), destination.get(1));
                    for (var operand : operands) operand.emit(e); b.endDoubleWordDivision();
                } else {
                    b.beginTupleArithmetic(tupleOperation, destination.get(0), destination.get(1), destination.size() > 2 ? destination.get(2) : destination.get(1));
                    for (var operand : operands) operand.emit(e); b.endTupleArithmetic();
                }
            });
        }
        return compileOrdinaryApplication(expr, scope, tail, fn, args, flags, callStrict, tupleProof);
    }

    private static List<CoreRepresentation> argumentProofs(List<List<Object>> args) {
        var proofs = new ArrayList<CoreRepresentation>(args.size());
        for (var arg : args) proofs.add(CoreRepresentations.expression(arg));
        return proofs;
    }

    private static List<CoreRepresentation> loweredProofs(List<Expression> operands) {
        var proofs = new ArrayList<CoreRepresentation>(operands.size());
        for (var operand : operands) proofs.add(operand.proof());
        return proofs;
    }

    private static List<Object> argumentMetadata(List<List<Object>> args) {
        var representations = new ArrayList<Object>(args.size());
        for (var arg : args) {
            var record = CoreRepresentations.metadata(arg);
            representations.add(record == null ? null : record.get("rep"));
        }
        return representations;
    }

    private List<Expression> compileOperands(List<List<Object>> args, Scope scope) {
        var operands = new ArrayList<Expression>(args.size());
        for (var arg : args) operands.add(compile(arg, scope, false));
        return operands;
    }

    private List<Expression> argumentOperands(List<List<Object>> args, Scope scope) {
        var operands = new ArrayList<Expression>(args.size());
        for (var arg : args) operands.add(argument(arg, scope, false));
        return operands;
    }

    private List<CoreRepresentation> lexicalProofs(List<List<Object>> args, Scope scope) {
        var proofs = new ArrayList<CoreRepresentation>(args.size());
        for (var arg : args) proofs.add(lexicalProof(arg, scope));
        return proofs;
    }

    private static List<BytecodeLocal> localSlots(Emission emission, List<Local> fields) {
        var slots = new ArrayList<BytecodeLocal>(fields.size());
        for (var field : fields) slots.add(emission.locals.get(field.id));
        return slots;
    }

    private static boolean allEvaluated(List<Expression> operands) {
        for (var operand : operands) if (!operand.proof().getEvaluated()) return false;
        return true;
    }

    private static boolean allProofsEvaluated(List<CoreRepresentation> proofs) {
        for (var proof : proofs) if (!proof.getEvaluated()) return false;
        return true;
    }

    private CoreRepresentation lexicalProof(List<Object> expr, Scope scope) {
        if (!"var".equals(expr.getFirst())) return null;
        var local = scope.locals.get(expr.get(1));
        return local != null ? local.proof : globalProofs.get(expr.get(1));
    }

    private Expression compileOriginalStdio(OriginalStdioOp originalStdio, List<Object> fn, boolean defined,
            List<List<Object>> args, Scope scope, CoreRepresentation tupleProof) {
        CoreOriginalStdio.validateHead(fn, defined);
        var operands = new ArrayList<Expression>();
        for (int index = 0; index < args.size(); ++index) {
            var argument = args.get(index);
            var operand = compile(argument, scope, false);
            if (originalStdio.getProcessIdentity() || originalStdio == OriginalStdioOp.SET_ERRNO || originalStdio.getEventDescriptor()
                    || originalStdio.getWaitStatus() || originalStdio.getPathRemoval() || originalStdio.getFlagConstant() || originalStdio.getFcntl()
                    || originalStdio.getReadiness() || originalStdio.getSeekConstant()
                    || originalStdio.getStat() || originalStdio.getTermios() || originalStdio.getSavedTermios()
                    || originalStdio.getReadImage() || originalStdio.getPathStat() || originalStdio.getPathMode() || originalStdio == OriginalStdioOp.ACCESS
                    || originalStdio == OriginalStdioOp.UNLINKAT || originalStdio == OriginalStdioOp.FSTATAT || originalStdio.getPathLink()
                    || originalStdio.getCurrentDirectory() || originalStdio.getDirectoryStream() || originalStdio == OriginalStdioOp.TCSETATTR
                    || originalStdio.getOpening() || originalStdio.getIconv() || originalStdio.getDuplication()
                    || originalStdio.getLocking() || originalStdio.getUnixNative())
                CoreOriginalStdio.validateScalarOperand(originalStdio, index, operand.proof(), lexicalProof(argument, scope));
            var integer = NarrowInteger.fromRep(originalStdio.getArguments().get(index));
            operands.add(integer == null ? operand : e -> {
                e.builder.beginForeignIntegerToHost(integer); operand.emit(e); e.builder.endForeignIntegerToHost();
            });
        }
        return tupleExpression(tupleProof, (e, destination) -> {
            var b = e.builder;
            var result = originalStdio.getResult() != null ? destination.getFirst() : b.createLocal("unused original State destination", "primitive");
            if (originalStdio.getUnixNative()) {
                b.beginOriginalUnix(result, originalStdio);
                for (var operand : operands) operand.emit(e);
                b.endOriginalUnix();
                if (enableAsync && "safe".equals(originalStdio.getSafety())) emitAsyncPoll(e);
                return;
            }
            if (originalStdio.getWindowsEncoding()) {
                if (originalStdio == OriginalStdioOp.MULTI_BYTE_TO_WIDE) {
                    b.beginOriginalWindowsMultiByte(result); for (var operand : operands) operand.emit(e); b.endOriginalWindowsMultiByte();
                } else if (originalStdio == OriginalStdioOp.WIDE_TO_MULTI_BYTE || originalStdio == OriginalStdioOp.WIDE_TO_MULTI_BYTE_SAFE) {
                    b.beginOriginalWindowsWideChar(result, ForeignSafety.synchronous(originalStdio.getSafety())); for (var operand : operands) operand.emit(e); b.endOriginalWindowsWideChar();
                } else {
                    var values = new ArrayList<BytecodeLocal>();
                    for (int index = 0; index < operands.size() - 1; ++index) {
                        var value = b.createLocal("Windows operand " + index, "AddrRep".equals(originalStdio.getArguments().get(index)) ? "object" : "primitive");
                        b.beginStoreLocal(value); operands.get(index).emit(e); b.endStoreLocal(); values.add(value);
                    }
                    b.beginOriginalWindowsEncoding(result, originalStdio);
                    if (originalStdio == OriginalStdioOp.CODE_PAGE_INFO || originalStdio == OriginalStdioOp.DBCS_LEAD_BYTE
                            || originalStdio == OriginalStdioOp.MAP_ERRNO_VALUE || originalStdio == OriginalStdioOp.WINDOWS_ERROR_MESSAGE)
                        b.emitLoadLocal(values.get(0)); else b.emitLoadConstant(0L);
                    if (originalStdio == OriginalStdioOp.DBCS_LEAD_BYTE) b.emitLoadLocal(values.get(1)); else b.emitLoadConstant(0L);
                    if (originalStdio == OriginalStdioOp.CODE_PAGE_INFO) b.emitLoadLocal(values.get(1));
                    else if (originalStdio == OriginalStdioOp.LOCAL_FREE) b.emitLoadLocal(values.get(0));
                    else b.emitLoadConstant(ManagedAddress.nullAddress());
                    operands.getLast().emit(e); b.endOriginalWindowsEncoding();
                }
                if (enableAsync && "safe".equals(originalStdio.getSafety())) emitAsyncPoll(e);
                return;
            }
            if (originalStdio.getWindowsDirectory()) {
                b.beginOriginalWindowsDirectory(result, originalStdio);
                if (originalStdio != OriginalStdioOp.LAST_ERROR) operands.get(0).emit(e); else b.emitLoadConstant(ManagedAddress.nullAddress());
                if (originalStdio == OriginalStdioOp.FIND_FIRST || originalStdio == OriginalStdioOp.FIND_NEXT) operands.get(1).emit(e);
                else b.emitLoadConstant(ManagedAddress.nullAddress());
                operands.getLast().emit(e); b.endOriginalWindowsDirectory(); return;
            }
            if (originalStdio.getEventManager()) {
                var values = new ArrayList<BytecodeLocal>();
                for (int index = 0; index < operands.size() - 1; ++index) {
                    var value = b.createLocal("original event operand " + index, "AddrRep".equals(originalStdio.getArguments().get(index)) ? "object" : "primitive");
                    b.beginStoreLocal(value); operands.get(index).emit(e); b.endStoreLocal(); values.add(value);
                }
                b.beginOriginalEvent(result, originalStdio);
                if (originalStdio.getPoll()) {
                    b.emitLoadLocal(values.get(1)); b.emitLoadLocal(values.get(2)); b.emitLoadConstant(0L); b.emitLoadLocal(values.get(0));
                } else if (originalStdio.getEpollWait()) {
                    b.emitLoadLocal(values.get(0)); b.emitLoadLocal(values.get(2)); b.emitLoadLocal(values.get(3)); b.emitLoadLocal(values.get(1));
                } else if (originalStdio == OriginalStdioOp.EPOLL_CTL) for (var value : values) b.emitLoadLocal(value);
                else {
                    b.emitLoadLocal(values.get(0));
                    if (originalStdio == OriginalStdioOp.IO_CONTROL_FD) b.emitLoadLocal(values.get(1)); else b.emitLoadConstant(0L);
                    b.emitLoadConstant(0L); b.emitLoadConstant(ManagedAddress.nullAddress());
                }
                operands.getLast().emit(e); b.endOriginalEvent(); return;
            }
            BytecodeLocal openPath = null;
            if (originalStdio.getOpening()) {
                openPath = b.createLocal("original open path", "object");
                b.beginStoreLocal(openPath); operands.get(0).emit(e); b.endStoreLocal();
            }
            List<BytecodeLocal> termiosArguments = null;
            if (originalStdio == OriginalStdioOp.TCSETATTR) {
                termiosArguments = new ArrayList<>();
                for (int index = 0; index < 2; ++index) {
                    var value = b.createLocal("original tcsetattr integer " + index, "primitive");
                    b.beginStoreLocal(value); operands.get(index).emit(e); b.endStoreLocal(); termiosArguments.add(value);
                }
            }
            boolean status = originalStdio.getProcessIdentity() || originalStdio == OriginalStdioOp.SET_ERRNO || originalStdio == OriginalStdioOp.PIPE
                || originalStdio.getWaitStatus() || originalStdio.getPathRemoval() || originalStdio.getFlagConstant() || originalStdio.getTermios()
                || originalStdio.getSavedTermios() || originalStdio == OriginalStdioOp.ERRNO || originalStdio == OriginalStdioOp.ISATTY
                || originalStdio == OriginalStdioOp.CLOSE || originalStdio == OriginalStdioOp.DUP || originalStdio.getReadImage()
                || originalStdio == OriginalStdioOp.UNLOCK || originalStdio.getSeekConstant() || originalStdio.getStat();
            BytecodeLocal imageAddress = null;
            if (originalStdio == OriginalStdioOp.POKE_LFLAG) {
                imageAddress = b.createLocal("original image address", "object");
                b.beginStoreLocal(imageAddress); operands.get(0).emit(e); b.endStoreLocal();
            }
            if (originalStdio == OriginalStdioOp.LOCALE) b.beginOriginalLocale(result);
            else if (originalStdio.getDirectoryPointer()) b.beginOriginalDirectoryPointer(result, originalStdio);
            else if (originalStdio == OriginalStdioOp.FDOPENDIR) b.beginOriginalFdopendir(result);
            else if (originalStdio == OriginalStdioOp.READDIR) b.beginOriginalReaddir(result);
            else if (originalStdio == OriginalStdioOp.CLOSEDIR || originalStdio == OriginalStdioOp.FREE_DIRENT)
            b.beginOriginalDirectoryRelease(result, originalStdio);
            else if (originalStdio == OriginalStdioOp.CHDIR) b.beginOriginalChdir(result);
            else if (originalStdio == OriginalStdioOp.GETCWD) b.beginOriginalGetcwd(result);
            else if (originalStdio.getPathPair()) b.beginOriginalPathPair(originalStdio, result);
            else if (originalStdio == OriginalStdioOp.READLINK) b.beginOriginalReadlink(result);
            else if (originalStdio == OriginalStdioOp.ACCESS) b.beginOriginalPathAccess(result);
            else if (originalStdio == OriginalStdioOp.UNLINKAT) b.beginOriginalUnlinkAt(result);
            else if (originalStdio == OriginalStdioOp.FSTATAT) b.beginOriginalFstatAt(result);
            else if (originalStdio.getPathMode()) b.beginOriginalPathMode(result, originalStdio);
            else if (originalStdio.getPathStat()) b.beginOriginalPathStat(result, originalStdio);
            else if (originalStdio == OriginalStdioOp.ICONV_OPEN) b.beginOriginalIconvOpen(result);
            else if (originalStdio == OriginalStdioOp.ICONV_CLOSE) b.beginOriginalIconvClose(result);
            else if (originalStdio == OriginalStdioOp.ICONV) b.beginOriginalIconv(result, originalStdio);
            else if (originalStdio.getEventPair() || originalStdio.getFcntl() || originalStdio.getReadiness() || originalStdio == OriginalStdioOp.LOCK) b.beginOriginalStdioReady(result, originalStdio);
            else if (originalStdio == OriginalStdioOp.SEEK) b.beginFileSeek(result);
            else if (originalStdio == OriginalStdioOp.TRUNCATE || originalStdio == OriginalStdioOp.DUP2) b.beginFileSetSize(result);
            else if (status) b.beginOriginalStdioStatus(result, originalStdio);
            else b.beginOriginalStdioTransfer(result, originalStdio);
            if (originalStdio.getOpening()) {
                operands.get(1).emit(e); b.emitLoadLocal(Objects.requireNonNull(openPath)); operands.get(2).emit(e); operands.get(3).emit(e);
            } else if (originalStdio == OriginalStdioOp.TCSETATTR) {
                b.emitLoadLocal(Objects.requireNonNull(termiosArguments).get(0)); operands.get(2).emit(e);
                b.emitLoadLocal(termiosArguments.get(1)); operands.get(3).emit(e);
            } else if (originalStdio.getEventPair()) {
                for (var operand : operands.subList(0, 2)) operand.emit(e);
                b.emitLoadConstant(0L); b.emitLoadConstant(0L); operands.getLast().emit(e);
            } else if (originalStdio.getFcntl()) {
                for (var operand : operands.subList(0, 2)) operand.emit(e);
                if (originalStdio == OriginalStdioOp.FCNTL_WRITE) operands.get(2).emit(e); else b.emitLoadConstant(0L);
                b.emitLoadConstant(0L); operands.getLast().emit(e);
            } else if (originalStdio.getSavedTermios()) {
                operands.get(0).emit(e);
                if (originalStdio == OriginalStdioOp.SET_SAVED_TERMIOS) operands.get(1).emit(e); else b.emitLoadConstant(ManagedAddress.nullAddress());
                operands.getLast().emit(e);
            } else if (originalStdio.getTermios()) {
                if (originalStdio == OriginalStdioOp.POKE_LFLAG) operands.get(1).emit(e); else b.emitLoadConstant(0L);
                if (imageAddress != null) b.emitLoadLocal(imageAddress);
                else if (originalStdio.getTermiosAddress()) operands.get(0).emit(e);
                else b.emitLoadConstant(ManagedAddress.nullAddress());
                operands.getLast().emit(e);
            } else if (status) {
                if (originalStdio.getProcessIdentity() || originalStdio == OriginalStdioOp.PIPE || originalStdio.getPathRemoval()
                        || originalStdio.getFlagConstant() || originalStdio == OriginalStdioOp.ERRNO || originalStdio.getSeekConstant()
                        || originalStdio == OriginalStdioOp.SIZEOF_STAT || originalStdio.getStatField()) b.emitLoadConstant(0L);
                else operands.get(0).emit(e);
                if (originalStdio == OriginalStdioOp.PIPE || originalStdio.getPathRemoval() || originalStdio.getStatField()) operands.get(0).emit(e);
                else if (originalStdio.getReadImage()) operands.get(1).emit(e); else b.emitLoadConstant(ManagedAddress.nullAddress());
                operands.getLast().emit(e);
            } else if (originalStdio == OriginalStdioOp.SEEK) {
                for (var operand : operands.subList(0, 3)) operand.emit(e);
                b.beginBlock(); b.beginRequireIOState(); operands.get(3).emit(e); b.endRequireIOState();
                b.emitLoadConstant(OriginalStdioOp.SEEK); b.endBlock();
            } else if (originalStdio == OriginalStdioOp.TRUNCATE || originalStdio == OriginalStdioOp.DUP2) {
                for (var operand : operands.subList(0, 2)) operand.emit(e);
                b.beginBlock(); b.beginRequireIOState(); operands.get(2).emit(e); b.endRequireIOState(); b.emitLoadConstant(originalStdio); b.endBlock();
            } else for (var operand : operands) operand.emit(e);
            if (originalStdio == OriginalStdioOp.LOCALE) b.endOriginalLocale();
            else if (originalStdio.getDirectoryPointer()) b.endOriginalDirectoryPointer();
            else if (originalStdio == OriginalStdioOp.FDOPENDIR) b.endOriginalFdopendir();
            else if (originalStdio == OriginalStdioOp.READDIR) b.endOriginalReaddir();
            else if (originalStdio == OriginalStdioOp.CLOSEDIR || originalStdio == OriginalStdioOp.FREE_DIRENT)
            b.endOriginalDirectoryRelease();
            else if (originalStdio == OriginalStdioOp.CHDIR) b.endOriginalChdir();
            else if (originalStdio == OriginalStdioOp.GETCWD) b.endOriginalGetcwd();
            else if (originalStdio.getPathPair()) b.endOriginalPathPair();
            else if (originalStdio == OriginalStdioOp.READLINK) b.endOriginalReadlink();
            else if (originalStdio == OriginalStdioOp.ACCESS) b.endOriginalPathAccess();
            else if (originalStdio == OriginalStdioOp.UNLINKAT) b.endOriginalUnlinkAt();
            else if (originalStdio == OriginalStdioOp.FSTATAT) b.endOriginalFstatAt();
            else if (originalStdio.getPathMode()) b.endOriginalPathMode();
            else if (originalStdio.getPathStat()) b.endOriginalPathStat();
            else if (originalStdio == OriginalStdioOp.ICONV_OPEN) b.endOriginalIconvOpen();
            else if (originalStdio == OriginalStdioOp.ICONV_CLOSE) b.endOriginalIconvClose();
            else if (originalStdio == OriginalStdioOp.ICONV) b.endOriginalIconv();
            else if (originalStdio.getEventPair() || originalStdio.getFcntl() || originalStdio.getReadiness() || originalStdio == OriginalStdioOp.LOCK) b.endOriginalStdioReady();
            else if (originalStdio == OriginalStdioOp.SEEK) b.endFileSeek();
            else if (originalStdio == OriginalStdioOp.TRUNCATE || originalStdio == OriginalStdioOp.DUP2) b.endFileSetSize();
            else if (status) b.endOriginalStdioStatus(); else b.endOriginalStdioTransfer();
            if (enableAsync && (originalStdio == OriginalStdioOp.UNLINKAT || originalStdio == OriginalStdioOp.FSTATAT ||
                    originalStdio.getTransfer() && "safe".equals(originalStdio.getSafety()))) emitAsyncPoll(e);
        });
    }

    private Expression compilePrimitiveApplication(String name, List<Object> expr, List<List<Object>> args,
            List<?> flags, CoreRepresentation tupleProof, Scope scope, boolean tail) {
        if (name.equals("tagToEnum#")) {
            if (args.size() != 1) throw new RuntimeFault("tagToEnum#: Exactly one operand required");
            var operand = compile(args.get(0), scope, false);
            var ids = CoreEnums.validate(expr, operand.proof(), constructors);
            var values = new DataValue[ids.size()];
            for (int index = 0; index < values.length; ++index) values[index] = dataLayout(ids.get(index)).allocate();
            var family = new EnumFamily(values);
            return new ProvenExpression(e -> {
                e.builder.beginTagToEnum(family); operand.emit(e); e.builder.endTagToEnum();
            }, evaluatedProof(tupleProof, true));
        }
        if (CoreDataTags.operations.contains(name)) {
            if (args.size() != 1) throw new RuntimeFault("dataToTag: Exactly one operand required");
            var operand = argument(args.get(0), scope, false);
            var ids = CoreDataTags.validate(expr, operand.proof(), constructors);
            var layouts = new DataLayout[ids.size()];
            for (int index = 0; index < layouts.length; ++index) layouts[index] = dataLayout(ids.get(index));
            var family = new DataTagFamily(layouts);
            return new ProvenExpression(e -> {
                e.builder.beginDataToTag(family); operand.emit(e); e.builder.endDataToTag();
            }, evaluatedProof(tupleProof, true));
        }
        if (CoreVectors.operations.contains(name)) {
            var vectorProofs = new ArrayList<CoreRepresentation>(args.size());
            for (var arg : args) vectorProofs.add(CoreVectors.argumentProof(arg));
            CoreVectors.validate(name, vectorProofs, tupleProof);
            CoreVectors.validateFlags(flags);
            var operands = compileOperands(args, scope);
            var shuffle = name.startsWith("shuffle") ? CoreVectors.shuffleIndices(args.get(2), tupleProof.getVector().getLanes()) : null;
            return vectorPrimitive(name, operands, shuffle);
        }
        if (CoreArithmeticExceptions.payload(name) != null) {
            CoreArithmeticExceptions.validateArguments(name, argumentProofs(args), flags);
            var operand = argument(args.getFirst(), scope, false, "argument thunk", true, false);
            CoreArithmeticExceptions.validateArguments(name, List.of(operand.proof()), flags);
            var id = Objects.requireNonNull(CoreArithmeticExceptions.payload(name));
            var payload = globals.get(id);
            if (payload == null) throw new UnsupportedCore("Unresolved implicit exception binding " + id);
            return new ProvenExpression(new ResultExpression((e, destination) -> {
                var b = e.builder;
                b.beginBlock(); operand.emitTuple(e, List.of());
                if (destination != null) b.beginStoreLocal(b.createLocal("non-returning arithmetic exception", null));
                b.beginRaise(true); b.emitReadGlobal(payload); b.endRaise();
                if (destination != null) b.endStoreLocal(); b.endBlock();
            }), evaluatedProof(tupleProof, true));
        }
        if (Set.of("newBCO#", "mkApUpd0#").contains(name)) {
            GhcBCO.validate(name, argumentProofs(args), flags, tupleProof);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) operands.add(argument(args.get(index), scope, CoreRepresentations.argumentMayBeLazy(flags.get(index), args.get(index))));
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                b.beginStoreLocal(destination.getFirst());
                if (name.equals("newBCO#")) {
                    b.beginNewGhcBCO(language, metrics); for (var operand : operands) operand.emit(e); b.endNewGhcBCO();
                } else { b.beginMkApUpd0(); operands.getFirst().emit(e); b.endMkApUpd0(); }
                b.endStoreLocal();
            });
        }
        if (Set.of("newPromptTag#", "prompt#", "control0#").contains(name)
                || delimited && Set.of("annotateStack#", "catch#", "unmaskAsyncExceptions#", "maskAsyncExceptions#", "maskUninterruptible#").contains(name)) {
            var proofs = argumentProofs(args);
            if (Set.of("newPromptTag#", "prompt#", "control0#").contains(name)) DelimitedControl.validate(name, proofs, flags, tupleProof);
            else if (name.equals("annotateStack#")) StackAnnotations.validate(proofs, flags, tupleProof);
            else CoreSynchronousExceptions.validate(name, proofs, flags, tupleProof);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) operands.add(argument(args.get(index), scope, CoreRepresentations.argumentMayBeLazy(flags.get(index), args.get(index))));
            var shape = new TupleShape(tupleProof, language);
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                var slots = tupleSlots(shape, destination);
                switch (name) {
                    case "newPromptTag#" -> {
                        b.beginStaticStoreObject(destination.getFirst()); b.beginNewPromptTag(); operands.getFirst().emit(e);
                        b.endNewPromptTag(); b.endStaticStoreObject();
                    }
                    case "control0#" -> {
                        b.beginConsumeDelimited(slots); beginAnnotationYield(e);
                        b.beginCaptureDelimited(shape); for (var operand : operands) operand.emit(e); b.endCaptureDelimited();
                        endAnnotationYield(e); b.endConsumeDelimited();
                    }
                    default -> {
                        b.beginBlock();
                        var result = b.createLocal("delimited boundary result", FrameSlotKind.Object);
                        b.beginTryCatch(); b.beginStaticStoreObject(result); b.beginDelimitedBoundary(name, shape, language, metrics);
                        operands.get(0).emit(e); if (operands.size() == 3) operands.get(1).emit(e); else b.emitLoadNull();
                        operands.getLast().emit(e); b.endDelimitedBoundary(); b.endStaticStoreObject();
                        b.beginBlock(); b.beginStaticStoreObject(result); beginAnnotationYield(e);
                        b.beginDelimitedOnly(); b.emitLoadException(); b.endDelimitedOnly(); endAnnotationYield(e);
                        b.endStaticStoreObject(); b.endBlock(); b.endTryCatch();
                        b.beginConsumeDelimited(slots); b.emitStaticLoadObject(result); b.endConsumeDelimited(); b.endBlock();
                    }
                }
            });
        }
        if (Set.of("raiseIO#", "catch#", "getMaskingState#", "unmaskAsyncExceptions#", "maskAsyncExceptions#", "maskUninterruptible#").contains(name)) {
            CoreSynchronousExceptions.validate(name, argumentProofs(args), flags, tupleProof);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) operands.add(argument(args.get(index), scope, CoreRepresentations.argumentMayBeLazy(flags.get(index), args.get(index))));
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                if (name.equals("raiseIO#")) {
                    b.beginRaiseIO(CoreExceptionPayload.validate(expr)); for (var operand : operands) operand.emit(e); b.endRaiseIO();
                } else if (name.equals("getMaskingState#")) {
                    b.beginGetMaskingState(destination.get(0)); operands.get(0).emit(e); b.endGetMaskingState();
                } else {
                    // All operands and the state check precede the protected action.
                    var action = b.createLocal("IO action", "object");
                    b.beginStoreLocal(action); operands.get(0).emit(e); b.endStoreLocal();
                    BytecodeLocal handler = null;
                    if (name.equals("catch#")) {
                        handler = b.createLocal("IO handler", "object");
                        b.beginStoreLocal(handler); operands.get(1).emit(e); b.endStoreLocal();
                    }
                    b.beginRequireIOState(); operands.get(handler == null ? 1 : 2).emit(e); b.endRequireIOState();
                    var slots = tupleSlots(new TupleShape(tupleProof, language), destination);
                    if (handler != null) {
                        b.beginTryCatch();
                        if (!resumable) {
                            b.beginInvokeIOAction(slots, metrics); forceSavedCallback(e, action, operands.get(0).proof());
                            b.emitLoadNull(); b.endInvokeIOAction();
                        } else {
                            b.beginBlock();
                            var callerMask = b.createLocal("caught IO action caller mask", "object");
                            var suspended = b.createLocal("caught IO action suspension", "object");
                            b.beginStoreLocal(callerMask); b.emitCurrentMask(); b.endStoreLocal(); b.beginTryCatch();
                            b.beginInvokeIOActionCheckpoint(slots, metrics); forceSavedCallback(e, action, operands.get(0).proof());
                            b.emitLoadConstant(true); b.endInvokeIOActionCheckpoint(); b.beginBlock(); b.beginStoreLocal(suspended);
                            b.beginCallSuspensionOnly(); b.emitLoadException(); b.endCallSuspensionOnly(); b.endStoreLocal();
                            b.beginResumeIOAction(slots); b.emitLoadLocal(suspended); b.beginReenterCallMask(); beginAnnotationYield(e);
                            b.beginParkCallMask(); b.emitLoadLocal(suspended); b.emitStaticLoadObject(Objects.requireNonNull(e.checkpointRootEntry));
                            b.emitLoadLocal(callerMask); b.endParkCallMask(); endAnnotationYield(e); b.emitLoadLocal(callerMask);
                            b.endReenterCallMask(); b.endResumeIOAction(); b.endBlock(); b.endTryCatch(); b.endBlock();
                        }
                        b.beginBlock();
                        var payload = b.createLocal("caught exception payload", "object");
                        b.beginStoreLocal(payload); b.beginRequireCaughtIOFailure(); b.emitLoadException();
                        b.endRequireCaughtIOFailure(); b.endStoreLocal();
                        var prior = b.createLocal("handler caller mask", "object");
                        b.beginStoreLocal(prior); b.emitEnterHandlerMask(); b.endStoreLocal();
                        b.beginTryFinally(() -> { b.beginRestoreMask(); b.emitLoadLocal(prior); b.endRestoreMask(); });
                        if (!resumable) {
                            b.beginInvokeIOHandler(slots, metrics); forceSavedCallback(e, handler, operands.get(1).proof());
                            b.emitLoadLocal(payload); b.emitLoadLocal(prior); b.endInvokeIOHandler();
                        } else {
                            b.beginBlock();
                            var handlerMask = b.createLocal("caught IO handler active mask", "object");
                            var suspended = b.createLocal("caught IO handler suspension", "object");
                            b.beginStoreLocal(handlerMask); b.emitCurrentMask(); b.endStoreLocal(); b.beginTryCatch();
                            b.beginInvokeIOHandlerCheckpoint(slots, metrics); forceSavedCallback(e, handler, operands.get(1).proof());
                            b.emitLoadLocal(payload); b.emitLoadLocal(prior); b.endInvokeIOHandlerCheckpoint();
                            b.beginBlock(); b.beginStoreLocal(suspended); b.beginCallSuspensionOnly(); b.emitLoadException();
                            b.endCallSuspensionOnly(); b.endStoreLocal(); b.beginResumeTupleApplication(slots); b.emitLoadLocal(suspended);
                            b.beginReenterCallMask(); beginAnnotationYield(e); b.beginParkCallMask(); b.emitLoadLocal(suspended);
                            b.emitStaticLoadObject(Objects.requireNonNull(e.checkpointRootEntry)); b.emitLoadLocal(handlerMask);
                            b.endParkCallMask(); endAnnotationYield(e); b.emitLoadLocal(handlerMask); b.endReenterCallMask();
                            b.endResumeTupleApplication(); b.endBlock(); b.endTryCatch(); b.endBlock();
                        }
                        b.endTryFinally(); b.endBlock(); b.endTryCatch();
                    } else {
                        var target = switch (name) {
                            case "maskAsyncExceptions#" -> MaskingState.MASKED_INTERRUPTIBLE;
                            case "maskUninterruptible#" -> MaskingState.MASKED_UNINTERRUPTIBLE;
                            default -> MaskingState.UNMASKED;
                        };
                        var prior = b.createLocal("mask caller state", "object");
                        b.beginStoreLocal(prior); b.emitEnterMask(target); b.endStoreLocal();
                        b.beginTryFinally(() -> { b.beginRestoreMask(); b.emitLoadLocal(prior); b.endRestoreMask(); });
                        if (!resumable) {
                            b.beginInvokeIOAction(slots, metrics); forceSavedCallback(e, action, operands.get(0).proof());
                            b.emitLoadLocal(prior); b.endInvokeIOAction();
                        } else {
                            b.beginBlock();
                            var actionMask = b.createLocal("masked IO action active mask", "object");
                            var suspended = b.createLocal("masked IO action suspension", "object");
                            b.beginStoreLocal(actionMask); b.emitCurrentMask(); b.endStoreLocal(); b.beginTryCatch();
                            b.beginInvokeMaskedIOActionCheckpoint(slots, metrics); forceSavedCallback(e, action, operands.get(0).proof());
                            b.emitLoadLocal(prior); b.endInvokeMaskedIOActionCheckpoint(); b.beginBlock(); b.beginStoreLocal(suspended);
                            b.beginCallSuspensionOnly(); b.emitLoadException(); b.endCallSuspensionOnly(); b.endStoreLocal();
                            b.beginResumeTupleApplication(slots); b.emitLoadLocal(suspended); b.beginReenterCallMask(); beginAnnotationYield(e);
                            b.beginParkCallMask(); b.emitLoadLocal(suspended); b.emitStaticLoadObject(Objects.requireNonNull(e.checkpointRootEntry));
                            b.emitLoadLocal(actionMask); b.endParkCallMask(); endAnnotationYield(e); b.emitLoadLocal(actionMask);
                            b.endReenterCallMask(); b.endResumeTupleApplication(); b.endBlock(); b.endTryCatch(); b.endBlock();
                        }
                        b.endTryFinally();
                    }
                }
            });
        }
        if (name.equals("noDuplicate#")) {
            CoreNoDuplicate.validate(argumentProofs(args), flags, tupleProof);
            var operand = argument(args.get(0), scope, false);
            return new ProvenExpression(e -> {
                var b = e.builder;
                if (checkpoint == null) { b.beginNoDuplicate(); operand.emit(e); b.endNoDuplicate(); }
                else {
                    b.beginBlock(); b.beginNoDuplicate(); operand.emit(e); b.endNoDuplicate();
                    b.beginConditional(); b.emitCheckpointArmed(checkpoint); beginAnnotationYield(e);
                    b.emitLoadConstant(thc.runtime.Unit.INSTANCE); endAnnotationYield(e); b.emitLoadConstant(thc.runtime.Unit.INSTANCE);
                    b.endConditional(); b.endBlock();
                }
            }, evaluatedProof(tupleProof, true));
        }
        if (CoreThreadScheduling.named(name)) {
            CoreThreadScheduling.validate(name, argumentProofs(args), flags, tupleProof);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) operands.add(argument(args.get(index), scope, CoreRepresentations.argumentMayBeLazy(flags.get(index), args.get(index))));
            CoreThreadScheduling.validate(name, loweredProofs(operands), flags, tupleProof);
            if (name.equals("par#")) return new ProvenExpression(e -> {
                var b = e.builder; b.beginBlock();
                b.beginIfThen(); b.emitSparkEnabled();
                b.beginParSpark(); operands.get(0).emit(e); b.endParSpark(); b.endIfThen();
                b.emitLoadConstant(1L); b.endBlock();
            }, evaluatedProof(tupleProof, true));
            if (name.equals("delay#")) return new ProvenExpression(e -> {
                var b = e.builder;
                Expression token = target -> {
                    target.builder.beginPrepareThreadDelay(); for (var operand : operands) operand.emit(target); target.builder.endPrepareThreadDelay();
                };
                if (enableAsync) emitBlockingRequest(e, List.of(token), true, values -> {
                    b.beginAwaitThreadDelay(true); b.emitStaticLoadObject(values.getFirst()); b.endAwaitThreadDelay();
                });
                else {
                    b.beginBlock(); b.beginAwaitThreadDelay(false); token.emit(e); b.endAwaitThreadDelay();
                    b.emitLoadConstant(thc.runtime.Unit.INSTANCE); b.endBlock();
                }
            }, evaluatedProof(tupleProof, true));
            if (name.equals("setThreadAllocationCounter#") || name.equals("setOtherThreadAllocationCounter#")) return new ProvenExpression(e -> {
                var b = e.builder;
                boolean other = name.equals("setOtherThreadAllocationCounter#");
                b.beginBlock(); b.beginSetThreadAllocationCounter(other); operands.get(0).emit(e);
                if (other) operands.get(1).emit(e); else b.emitLoadConstant(thc.runtime.Unit.INSTANCE);
                operands.getLast().emit(e); b.endSetThreadAllocationCounter(); b.emitLoadConstant(thc.runtime.Unit.INSTANCE); b.endBlock();
            }, evaluatedProof(tupleProof, true));
            var empty = name.equals("getSpark#") ? dataLayouts.computeIfAbsent(CoreThreadScheduling.FALSE,
                id -> thc.Language.currentState().constructorLayout(language, id, "False", CoreFields.EMPTY)).allocate() : null;
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                b.beginBlock();
                if (name.equals("spark#")) storeTupleResult(e, destination.get(0), () -> operands.get(0).emit(e));
                b.beginDiscardVoid(); operands.getLast().emit(e); b.endDiscardVoid();
                if (name.equals("spark#")) {
                    b.beginParSpark(); loadSavedInput(e, destination.get(0)); b.endParSpark();
                } else if (name.equals("numSparks#")) {
                    b.emitSparkCount(destination.get(0));
                } else if (empty != null) {
                    b.beginGetSpark(destination.get(0), destination.get(1)); b.emitLoadConstant(empty); b.endGetSpark();
                }
                b.endBlock();
            });
        }
        if (CoreThreadObservation.named(name)) {
            CoreThreadObservation.validate(name, argumentProofs(args), flags, tupleProof);
            var state = argument(args.get(0), scope, false);
            CoreThreadObservation.validate(name, List.of(state.proof()), flags, tupleProof);
            return tupleExpression(tupleProof, (e, destination) -> {
                e.builder.beginObserveThreads(destination.get(0), name.equals("listThreads#")); state.emit(e); e.builder.endObserveThreads();
            });
        }
        if (name.equals("yield#")) {
            CoreYield.validate(argumentProofs(args), flags, tupleProof);
            var operand = argument(args.get(0), scope, false);
            return new ProvenExpression(e -> {
                var b = e.builder;
                b.beginBlock(); b.beginYieldThread(); operand.emit(e); b.endYieldThread();
                if (enableAsync) emitAsyncPoll(e); b.emitLoadConstant(thc.runtime.Unit.INSTANCE); b.endBlock();
            }, evaluatedProof(tupleProof, true));
        }
        if (CoreFileWait.named(name)) {
            CoreFileWait.validate(name, argumentProofs(args), flags, tupleProof);
            var operands = argumentOperands(args, scope);
            CoreFileWait.validate(name, loweredProofs(operands), flags, tupleProof);
            var payload = globals.get(CoreFileWait.badFd);
            if (payload == null) throw new UnsupportedCore(name + " requires original blockedOnBadFD payload");
            boolean writing = name.equals("waitWrite#");
            return new ProvenExpression(e -> {
                var b = e.builder;
                Expression token = target -> {
                    target.builder.beginPrepareFileWait(writing); for (var operand : operands) operand.emit(target); target.builder.endPrepareFileWait();
                };
                if (enableAsync) emitBlockingRequest(e, List.of(token), true, values -> {
                    b.beginAwaitFileWait(payload, true); b.emitStaticLoadObject(values.getFirst()); b.endAwaitFileWait();
                });
                else {
                    b.beginBlock(); b.beginAwaitFileWait(payload, false); token.emit(e); b.endAwaitFileWait();
                    b.emitLoadConstant(thc.runtime.Unit.INSTANCE); b.endBlock();
                }
            }, evaluatedProof(tupleProof, true));
        }
        if (name.equals("annotateStack#")) {
            StackAnnotations.validate(argumentProofs(args), flags, tupleProof);
            var annotation = argument(args.get(0), scope, true);
            var action = argument(args.get(1), scope, true);
            var state = argument(args.get(2), scope, false);
            var call = tupleApplication(new TupleShape(tupleProof, language), action,
                List.of(new ProvenExpression(e -> e.builder.emitLoadConstant(thc.runtime.Unit.INSTANCE), state.proof())), scope, false);
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                b.beginBlock(); b.beginRequireIOState(); state.emit(e); b.endRequireIOState();
                var prior = b.createLocal("annotation return", "object");
                b.beginStoreLocal(prior); b.beginEnterAnnotation(); annotation.emit(e); b.endEnterAnnotation(); b.endStoreLocal();
                b.beginTryFinally(() -> { b.beginRestoreAnnotations(); b.emitLoadLocal(prior); b.endRestoreAnnotations(); });
                call.emitTuple(e, destination); b.endTryFinally(); b.endBlock();
            });
        }
        if (name.equals("clearCCS#")) {
            CoreProfileAction.validate(argumentProofs(args), flags, tupleProof);
            var state = compile(args.get(1), scope, false);
            var checked = new ProvenExpression(e -> {
                var b = e.builder;
                b.beginBlock(); b.beginRequireIOState(); state.emit(e); b.endRequireIOState(); b.emitLoadConstant(thc.runtime.Unit.INSTANCE); b.endBlock();
            }, evaluatedProof(state.proof(), true));
            return tupleApplication(new TupleShape(tupleProof, language), argument(args.get(0), scope, true), List.of(checked), scope, tail);
        }
        if (ClosureInspectOp.named(name) != null) {
            var operation = ClosureInspectOp.named(name);
            operation.validate(argumentProofs(args), flags, tupleProof);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) operands.add(argument(args.get(index), scope, index == 0));
            if (operation == ClosureInspectOp.SIZE) return new ProvenExpression(e -> {
                e.builder.beginClosureSize(); operands.get(0).emit(e); e.builder.endClosureSize();
            }, evaluatedProof(tupleProof, true));
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                switch (operation) {
                    case UNPACK -> { b.beginUnpackClosure(destination.get(0), destination.get(1), destination.get(2)); operands.get(0).emit(e); b.endUnpackClosure(); }
                    case AP_STACK -> { b.beginGetApStackVal(destination.get(0), destination.get(1)); operands.get(0).emit(e); operands.get(1).emit(e); b.endGetApStackVal(); }
                    case CCS -> { b.beginGetCurrentCCS(destination.get(0)); operands.get(1).emit(e); b.endGetCurrentCCS(); }
                    case WHERE -> { b.beginWhereFrom(destination.get(0)); operands.get(1).emit(e); operands.get(2).emit(e); b.endWhereFrom(); }
                    case SIZE -> throw new IllegalStateException("Scalar closureSize#");
                }
            });
        }
        if (name.equals("getCurrentCCS#")) {
            CoreCurrentCCS.validate(argumentProofs(args), flags, tupleProof);
            argument(args.get(0), scope, true); // Prove the lifted dummy without entering it.
            var state = argument(args.get(1), scope, false);
            return tupleExpression(tupleProof, (e, destination) -> {
                e.builder.beginGetCurrentCCS(destination.get(0)); state.emit(e); e.builder.endGetCurrentCCS();
            });
        }
        if (name.equals("labelThread#") || name.equals("threadLabel#")) {
            CoreGuestThreads.validate(name, argumentProofs(args), flags, tupleProof);
            var operands = argumentOperands(args, scope);
            CoreGuestThreads.validate(name, loweredProofs(operands), flags, tupleProof);
            if (name.equals("threadLabel#")) return tupleExpression(tupleProof, (e, destination) -> {
                e.builder.beginThreadLabel(destination.get(0), destination.get(1)); for (var operand : operands) operand.emit(e); e.builder.endThreadLabel();
            });
            return new ProvenExpression(e -> {
                var b = e.builder;
                b.beginBlock(); b.beginLabelThread(); for (var operand : operands) operand.emit(e);
                b.endLabelThread(); b.emitLoadConstant(thc.runtime.Unit.INSTANCE); b.endBlock();
            }, evaluatedProof(tupleProof, true));
        }
        if (name.equals("threadStatus#")) {
            CoreGuestThreads.validate(name, argumentProofs(args), flags, tupleProof);
            var operands = argumentOperands(args, scope);
            CoreGuestThreads.validate(name, loweredProofs(operands), flags, tupleProof);
            return tupleExpression(tupleProof, (e, destination) -> {
                e.builder.beginThreadStatus(destination.get(0), destination.get(1), destination.get(2));
                for (var operand : operands) operand.emit(e); e.builder.endThreadStatus();
            });
        }
        if (Set.of("fork#", "forkOn#", "myThreadId#", "killThread#").contains(name)) {
            CoreGuestThreads.validate(name, argumentProofs(args), flags, tupleProof);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) operands.add(argument(args.get(index), scope, CoreRepresentations.argumentMayBeLazy(flags.get(index), args.get(index))));
            if (name.equals("killThread#")) {
                if (!enableAsync) return new ProvenExpression(e -> {
                    e.builder.beginThreadPrimitive(BytecodeRoot.ThreadPrimitiveKind.SELF_KILL);
                    for (var operand : operands) operand.emit(e); e.builder.endThreadPrimitive();
                }, evaluatedProof(tupleProof, true));
                return new ProvenExpression(e -> {
                    var b = e.builder;
                    b.beginBlock();
                    var values = new ArrayList<BytecodeLocal>();
                    for (int index = 0; index < operands.size(); ++index) {
                        var slot = b.createLocal("killThread operand " + index, null);
                        b.beginStoreLocal(slot); operands.get(index).emit(e); b.endStoreLocal(); values.add(slot);
                    }
                    var sent = b.createLocal("killThread sent request", "object");
                    b.beginStoreLocal(sent); b.beginThreadPrimitive(BytecodeRoot.ThreadPrimitiveKind.BEGIN_KILL);
                    for (var value : values) b.emitLoadLocal(value); b.endThreadPrimitive(); b.endStoreLocal();
                    var retry = b.createLocal("killThread wait pending", "primitive");
                    var incoming = b.createLocal("killThread incoming request", "object");
                    var active = b.createLocal("killThread logical mask", "object");
                    var discard = b.createLocal("killThread resume value", "object");
                    b.beginStoreLocal(retry); b.emitLoadConstant(true); b.endStoreLocal(); b.beginWhile(); b.emitLoadLocal(retry);
                    b.beginBlock(); b.beginTryCatch(); b.beginBlock(); b.beginStoreLocal(discard);
                    b.beginThreadPrimitive(BytecodeRoot.ThreadPrimitiveKind.FINISH_KILL); b.emitLoadLocal(sent);
                    b.emitLoadConstant(thc.runtime.Unit.INSTANCE); b.emitLoadConstant(thc.runtime.Unit.INSTANCE); b.endThreadPrimitive(); b.endStoreLocal();
                    b.beginStoreLocal(retry); b.emitLoadConstant(false); b.endStoreLocal(); b.endBlock();
                    b.beginBlock(); b.beginStoreLocal(incoming); b.beginCallSuspensionOnly(); b.emitLoadException();
                    b.endCallSuspensionOnly(); b.endStoreLocal(); b.beginStoreLocal(active); b.emitCurrentMask(); b.endStoreLocal();
                    b.beginStoreLocal(discard); b.beginReenterCallMask(); beginAnnotationYield(e); b.beginParkAsyncMask();
                    b.emitLoadLocal(incoming); b.emitStaticLoadObject(Objects.requireNonNull(e.checkpointRootEntry)); b.endParkAsyncMask();
                    endAnnotationYield(e); b.emitLoadLocal(active); b.endReenterCallMask(); b.endStoreLocal(); b.endBlock();
                    b.endTryCatch(); b.endBlock(); b.endWhile();
                    emitAsyncPoll(e, null, null, true); // Self-target delivery follows enqueue regardless of ordinary policy.
                    b.emitLoadConstant(thc.runtime.Unit.INSTANCE); b.endBlock();
                }, evaluatedProof(tupleProof, true));
            }
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                b.beginStoreLocal(destination.get(0));
                b.beginThreadPrimitive(switch (name) {
                    case "fork#" -> BytecodeRoot.ThreadPrimitiveKind.FORK;
                    case "forkOn#" -> BytecodeRoot.ThreadPrimitiveKind.FORK_ON;
                    default -> BytecodeRoot.ThreadPrimitiveKind.MY;
                });
                if (name.equals("forkOn#")) for (var operand : operands) operand.emit(e);
                else {
                    if (name.equals("fork#")) operands.get(0).emit(e); else b.emitLoadConstant(thc.runtime.Unit.INSTANCE);
                    operands.getLast().emit(e); b.emitLoadConstant(thc.runtime.Unit.INSTANCE);
                }
                b.endThreadPrimitive(); b.endStoreLocal();
            });
        }
        if (STMOp.named(name) != null) {
            var operation = STMOp.named(name);
            if ((checkpoint != null || containsDelimited) && operation != STMOp.NEW && operation != STMOp.READ_IO)
                throw new UnsupportedCore("STM transaction frames do not support explicit checkpoint/delimited capture");
            operation.validate(argumentProofs(args), flags, tupleProof);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) operands.add(argument(args.get(index), scope, CoreRepresentations.argumentMayBeLazy(flags.get(index), args.get(index))));
            operation.validate(loweredProofs(operands), flags, tupleProof);
            var nested = operation == STMOp.ATOMICALLY ? globals.get(STMOp.NESTED) : null;
            if (operation == STMOp.ATOMICALLY && nested == null) throw new UnsupportedCore("atomically# requires original nestedAtomically payload");
            if (operation == STMOp.WRITE) return new ProvenExpression(e -> {
                e.builder.beginWriteTVar(); for (var operand : operands) operand.emit(e); e.builder.endWriteTVar();
            }, evaluatedProof(tupleProof, true));
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                if (operation.getCallback()) {
                    var slots = tupleSlots(new TupleShape(tupleProof, language), destination);
                    if (enableAsync) emitBlockingRequest(e, operands, false, values -> {
                        // An internal cut finishes the same scope; only STMRestart reaches the outer retry loop.
                        var suspended = b.createLocal("STM scope suspension", FrameSlotKind.Object);
                        var callerMask = b.createLocal("STM caller mask", FrameSlotKind.Object);
                        b.beginStaticStoreObject(callerMask); b.emitCurrentMask(); b.endStaticStoreObject();
                        b.beginTryCatch();
                        b.beginInvokeSTM(operation, slots, metrics, true); b.emitStaticLoadObject(values.get(0));
                        if (values.size() == 3) b.emitStaticLoadObject(values.get(1)); else b.emitLoadNull();
                        if (nested != null) b.emitReadGlobal(nested); else b.emitLoadNull();
                        b.emitStaticLoadObject(values.getLast()); b.endInvokeSTM();
                        b.beginBlock();
                        b.beginStaticStoreObject(suspended);
                        b.beginSTMScopeSuspension(); b.emitLoadException(); b.endSTMScopeSuspension();
                        b.endStaticStoreObject();
                        b.beginResumeTupleApplication(slots); b.emitStaticLoadObject(suspended);
                        b.beginReenterCallMask(); beginAnnotationYield(e);
                        b.beginParkCallMask(); b.emitStaticLoadObject(suspended);
                        b.emitStaticLoadObject(Objects.requireNonNull(e.checkpointRootEntry));
                        b.emitStaticLoadObject(callerMask); b.endParkCallMask();
                        endAnnotationYield(e); b.emitStaticLoadObject(callerMask); b.endReenterCallMask();
                        b.endResumeTupleApplication(); b.endBlock(); b.endTryCatch();
                    });
                    else {
                        b.beginInvokeSTM(operation, slots, metrics, enableAsync); operands.get(0).emit(e);
                        if (operands.size() == 3) operands.get(1).emit(e); else b.emitLoadNull();
                        if (nested != null) b.emitReadGlobal(nested); else b.emitLoadNull();
                        operands.getLast().emit(e); b.endInvokeSTM();
                    }
                } else {
                    b.beginTVarAccess(operation, destination.get(0));
                    if (operation == STMOp.RETRY) b.emitLoadNull(); else operands.get(0).emit(e);
                    operands.getLast().emit(e); b.endTVarAccess();
                }
            });
        }
        if (MVarOp.named(name) != null) {
            var operation = MVarOp.named(name);
            var proofs = argumentProofs(args);
            operation.validate(proofs, flags, tupleProof);
            operation.validateBindings(proofs, lexicalProofs(args, scope));
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) operands.add(argument(args.get(index), scope, CoreRepresentations.argumentMayBeLazy(flags.get(index), args.get(index))));
            operation.validate(loweredProofs(operands), flags, tupleProof);
            if (operation.getTuple()) return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                if (enableAsync && (operation == MVarOp.TAKE || operation == MVarOp.READ)) emitBlockingRequest(e, operands, false, values -> {
                    b.beginReadMVar(destination.get(0), operation == MVarOp.TAKE, true);
                    for (var value : values) b.emitStaticLoadObject(value); b.endReadMVar();
                });
                else {
                    switch (operation) {
                        case NEW -> b.beginNewMVar(destination.get(0));
                        case TAKE, READ -> b.beginReadMVar(destination.get(0), operation == MVarOp.TAKE, false);
                        case TRY_TAKE, TRY_READ -> b.beginTryReadMVar(destination.get(0), destination.get(1), operation == MVarOp.TRY_TAKE);
                        case TRY_PUT -> b.beginTryPutMVar(destination.get(0));
                        case IS_EMPTY -> b.beginIsEmptyMVar(destination.get(0));
                        default -> throw new IllegalStateException("Not a tuple MVar operation");
                    }
                    for (var operand : operands) operand.emit(e);
                    switch (operation) {
                        case NEW -> b.endNewMVar(); case TAKE, READ -> b.endReadMVar();
                        case TRY_TAKE, TRY_READ -> b.endTryReadMVar(); case TRY_PUT -> b.endTryPutMVar(); case IS_EMPTY -> b.endIsEmptyMVar();
                        default -> throw new IllegalStateException("Not a tuple MVar operation");
                    }
                }
            });
            return new ProvenExpression(e -> {
                var b = e.builder;
                if (enableAsync && operation == MVarOp.PUT) emitBlockingRequest(e, operands, true, values -> {
                    b.beginPutMVar(true); for (var value : values) b.emitStaticLoadObject(value); b.endPutMVar();
                });
                else { b.beginPutMVar(false); for (var operand : operands) operand.emit(e); b.endPutMVar(); }
            }, evaluatedProof(tupleProof, true));
        }
        if (CompactImageOp.named(name) != null) {
            var operation = CompactImageOp.named(name);
            operation.validate(argumentProofs(args), flags, tupleProof);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) operands.add(argument(args.get(index), scope, CoreRepresentations.argumentMayBeLazy(flags.get(index), args.get(index))));
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                switch (operation) {
                    case FIRST, NEXT -> {
                        b.beginReadCompactBlock(destination.get(0), destination.get(1), operation == CompactImageOp.FIRST); operands.get(0).emit(e);
                        if (operation == CompactImageOp.FIRST) b.emitLoadConstant(thc.runtime.Unit.INSTANCE); else operands.get(1).emit(e);
                        operands.getLast().emit(e); b.endReadCompactBlock();
                    }
                    case ALLOCATE -> { b.beginAllocateCompactBlock(destination.get(0)); for (var operand : operands) operand.emit(e); b.endAllocateCompactBlock(); }
                    case FIXUP -> { b.beginFixupCompact(destination.get(0), destination.get(1)); for (var operand : operands) operand.emit(e); b.endFixupCompact(); }
                    case TO_ADDRESS, FROM_ADDRESS -> {
                        b.beginObjectAddress(destination.get(0), operation == CompactImageOp.FROM_ADDRESS);
                        for (var operand : operands) operand.emit(e);
                        if (operation == CompactImageOp.FROM_ADDRESS) b.emitLoadConstant(thc.runtime.Unit.INSTANCE); b.endObjectAddress();
                    }
                }
            });
        }
        if (CompactOp.named(name) != null) {
            var operation = CompactOp.named(name);
            operation.validate(argumentProofs(args), flags, tupleProof);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) operands.add(argument(args.get(index), scope, CoreRepresentations.argumentMayBeLazy(flags.get(index), args.get(index))));
            var failures = new ArrayList<GlobalBinding>();
            if (operation.getAdds()) for (var id : CompactOp.getFailures()) {
                var binding = globals.get(id);
                if (binding == null) throw new UnsupportedCore("Compact addition requires original exception payload: " + id);
                failures.add(binding);
            }
            var payloads = failures.toArray(GlobalBinding[]::new);
            if (operation == CompactOp.RESIZE) return new ProvenExpression(e -> {
                e.builder.beginResizeCompact(); for (var operand : operands) operand.emit(e); e.builder.endResizeCompact();
            }, evaluatedProof(tupleProof, true));
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                if (operation.getAdds()) {
                    if (enableAsync) emitBlockingRequest(e, operands, false, values -> {
                        var suspended = b.createLocal("compact addition suspension", FrameSlotKind.Object);
                        var callerMask = b.createLocal("compact addition mask", FrameSlotKind.Object);
                        b.beginStaticStoreObject(callerMask); b.emitCurrentMask(); b.endStaticStoreObject();
                        b.beginTryCatch();
                        b.beginStaticStoreObject(destination.getFirst());
                        b.beginAddCompact(operation == CompactOp.ADD_SHARING, metrics, payloads, true);
                        for (var value : values) b.emitStaticLoadObject(value);
                        b.endAddCompact(); b.endStaticStoreObject();
                        b.beginBlock();
                        b.beginStaticStoreObject(suspended);
                        b.beginCallSuspensionOnly(); b.emitLoadException(); b.endCallSuspensionOnly();
                        b.endStaticStoreObject();
                        b.beginStaticStoreObject(destination.getFirst());
                        b.beginResumeApplication(); b.emitStaticLoadObject(suspended);
                        b.beginReenterCallMask(); beginAnnotationYield(e);
                        b.beginParkCallMask(); b.emitStaticLoadObject(suspended);
                        b.emitStaticLoadObject(Objects.requireNonNull(e.checkpointRootEntry));
                        b.emitStaticLoadObject(callerMask); b.endParkCallMask();
                        endAnnotationYield(e); b.emitStaticLoadObject(callerMask); b.endReenterCallMask();
                        b.endResumeApplication(); b.endStaticStoreObject(); b.endBlock(); b.endTryCatch();
                    });
                    else {
                        b.beginStaticStoreObject(destination.getFirst());
                        b.beginAddCompact(operation == CompactOp.ADD_SHARING, metrics, payloads, false);
                        for (var operand : operands) operand.emit(e);
                        b.endAddCompact(); b.endStaticStoreObject();
                    }
                    return;
                }
                switch (operation) {
                    case CONTAINS -> b.beginContainsCompact(destination.get(0));
                    default -> b.beginInspectCompact(destination.get(0), operation);
                }
                for (var operand : operands) operand.emit(e);
                switch (operation) {
                    case CONTAINS -> b.endContainsCompact(); default -> b.endInspectCompact();
                }
            });
        }
        if (MutVarOp.named(name) != null) {
            var operation = MutVarOp.named(name);
            operation.validate(argumentProofs(args), flags, tupleProof);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) operands.add(argument(args.get(index), scope, CoreRepresentations.argumentMayBeLazy(flags.get(index), args.get(index))));
            if (operation.getTuple()) return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                switch (operation) {
                    case NEW -> b.beginNewMutVar(destination.get(0));
                    case READ -> b.beginReadMutVar(destination.get(0));
                    case SWAP -> b.beginSwapMutVar(destination.get(0));
                    case CAS -> b.beginCasMutVar(destination.get(0), destination.get(1));
                    case MODIFY, MODIFY2 -> b.beginModifyMutVar2(destination.get(0), destination.get(1), language, metrics, enableAsync, operation == MutVarOp.MODIFY2);
                    default -> throw new IllegalStateException("Not a tuple MutVar operation");
                }
                for (var operand : operands) operand.emit(e);
                switch (operation) {
                    case NEW -> b.endNewMutVar();
                    case READ -> b.endReadMutVar();
                    case SWAP -> b.endSwapMutVar();
                    case CAS -> b.endCasMutVar();
                    case MODIFY, MODIFY2 -> b.endModifyMutVar2();
                    default -> throw new IllegalStateException("Not a tuple MutVar operation");
                }
            });
            return new ProvenExpression(e -> {
                var b = e.builder;
                b.beginWriteMutVar(); for (var operand : operands) operand.emit(e); b.endWriteMutVar();
            }, evaluatedProof(tupleProof, true));
        }
        if (WeakOp.named(name) != null) {
            var operation = WeakOp.named(name);
            var proofs = argumentProofs(args);
            operation.validate(proofs, flags, tupleProof);
            operation.validateBindings(proofs, lexicalProofs(args, scope));
            GlobalBinding runner = null;
            if (operation == WeakOp.MAKE) {
                operation.validateAction(CoreRepresentations.knownFunctionSignature(args.get(2), bindings));
                if (weakFinalizer == null || weakFinalizer.isBlank())
                    throw new UnsupportedCore("mkWeak# requires a selected runtime finalizer ABI binding");
                runner = globals.get(weakFinalizer);
                if (runner == null) throw new UnsupportedCore("Unresolved global binding " + weakFinalizer);
            }
            var finalizer = runner;
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) operands.add(argument(args.get(index), scope, CoreRepresentations.argumentMayBeLazy(flags.get(index), args.get(index))));
            operation.validate(loweredProofs(operands), flags, tupleProof);
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                switch (operation) {
                    case MAKE -> b.beginMakeWeak(destination.getFirst());
                    case MAKE_PLAIN -> b.beginMakeWeakPlain(destination.getFirst());
                    case ADD_C_FINALIZER -> b.beginAddCFinalizerToWeak(destination.getFirst());
                    default -> b.beginObserveWeak(destination.get(0), destination.get(1), operation == WeakOp.FINALIZE);
                }
                for (var operand : operands) operand.emit(e);
                if (finalizer != null) b.emitReadGlobal(finalizer);
                switch (operation) {
                    case MAKE -> b.endMakeWeak(); case MAKE_PLAIN -> b.endMakeWeakPlain();
                    case ADD_C_FINALIZER -> b.endAddCFinalizerToWeak(); default -> b.endObserveWeak();
                }
            });
        }
        if (StableNameOp.named(name) != null) {
            var operation = StableNameOp.named(name);
            operation.validate(argumentProofs(args), flags, tupleProof);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) operands.add(argument(args.get(index), scope, CoreRepresentations.argumentMayBeLazy(flags.get(index), args.get(index))));
            if (operation == StableNameOp.MAKE) return tupleExpression(tupleProof, (e, destination) -> {
                e.builder.beginMakeStableName(destination.getFirst()); for (var operand : operands) operand.emit(e); e.builder.endMakeStableName();
            });
            return new ProvenExpression(e -> {
                e.builder.beginHashStableName(); operands.getFirst().emit(e); e.builder.endHashStableName();
            }, evaluatedProof(tupleProof, true));
        }
        if (StablePointerOp.named(name) != null) {
            var operation = StablePointerOp.named(name);
            operation.validate(argumentProofs(args), flags, tupleProof);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) operands.add(argument(args.get(index), scope, CoreRepresentations.argumentMayBeLazy(flags.get(index), args.get(index))));
            if (operation.getTuple()) return tupleExpression(tupleProof, (e, destination) -> {
                e.builder.beginStablePointerTuple(destination.getFirst(), operation); for (var operand : operands) operand.emit(e); e.builder.endStablePointerTuple();
            });
            return new ProvenExpression(e -> {
                e.builder.beginEqualStablePointers(); for (var operand : operands) operand.emit(e); e.builder.endEqualStablePointers();
            }, evaluatedProof(tupleProof, true));
        }
        if (ArrayOp.named(name) != null) {
            var operation = ArrayOp.named(name);
            operation.validate(argumentProofs(args), flags, tupleProof);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) operands.add(argument(args.get(index), scope, CoreRepresentations.argumentMayBeLazy(flags.get(index), args.get(index))));
            if (operation.getTuple()) return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                switch (operation) {
                    case NEW -> b.beginNewArray(destination.get(0));
                    case READ -> b.beginReadArray(destination.get(0));
                    case CAS -> b.beginCasArray(destination.get(0), destination.get(1));
                    case FREEZE, UNSAFE_THAW -> b.beginFreezeArray(destination.get(0), operation == ArrayOp.FREEZE);
                    case FREEZE_COPY, THAW, CLONE_MUTABLE -> b.beginCopyArraySlice(destination.get(0), operation == ArrayOp.FREEZE_COPY);
                    case INDEX -> b.beginIndexArray(destination.get(0));
                    default -> throw new IllegalStateException("Not a tuple array operation");
                }
                for (var operand : operands) operand.emit(e);
                switch (operation) {
                    case NEW -> b.endNewArray();
                    case READ -> b.endReadArray();
                    case CAS -> b.endCasArray();
                    case FREEZE, UNSAFE_THAW -> b.endFreezeArray();
                    case FREEZE_COPY, THAW, CLONE_MUTABLE -> b.endCopyArraySlice();
                    case INDEX -> b.endIndexArray();
                    default -> throw new IllegalStateException("Not a tuple array operation");
                }
            });
            return new ProvenExpression(e -> {
                var b = e.builder;
                switch (operation) {
                    case CLONE -> b.beginCloneArray();
                    case SIZE, SIZE_MUTABLE -> b.beginSizeArray();
                    case COPY, COPY_MUTABLE -> b.beginTransferArray(operation == ArrayOp.COPY_MUTABLE);
                    default -> b.beginWriteArray();
                }
                for (var operand : operands) operand.emit(e);
                switch (operation) {
                    case CLONE -> b.endCloneArray();
                    case SIZE, SIZE_MUTABLE -> b.endSizeArray();
                    case COPY, COPY_MUTABLE -> b.endTransferArray();
                    default -> b.endWriteArray();
                }
            }, evaluatedProof(tupleProof, true));
        }
        if (SmallArrayOp.named(name) != null) {
            var operation = SmallArrayOp.named(name);
            operation.validate(argumentProofs(args), flags, tupleProof);
            var operands = new ArrayList<Expression>();
            for (int index = 0; index < args.size(); ++index) operands.add(argument(args.get(index), scope, CoreRepresentations.argumentMayBeLazy(flags.get(index), args.get(index))));
            if (operation.getTuple()) return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                switch (operation) {
                    case NEW -> b.beginNewSmallArray(destination.get(0));
                    case READ -> b.beginReadSmallArray(destination.get(0));
                    case CAS -> b.beginCasSmallArray(destination.get(0), destination.get(1));
                    case INDEX -> b.beginIndexSmallArray(destination.get(0));
                    case FREEZE, UNSAFE_THAW -> b.beginFreezeSmallArray(destination.get(0), operation == SmallArrayOp.FREEZE);
                    case GET_SIZE_MUTABLE -> b.beginGetSizeSmallMutableArray(destination.get(0));
                    case CLONE_MUTABLE, SAFE_FREEZE, THAW -> b.beginCopySmallArraySlice(destination.get(0), operation == SmallArrayOp.SAFE_FREEZE);
                    default -> throw new IllegalStateException("Not a tuple SmallArray operation");
                }
                for (var operand : operands) operand.emit(e);
                switch (operation) {
                    case NEW -> b.endNewSmallArray();
                    case READ -> b.endReadSmallArray();
                    case CAS -> b.endCasSmallArray();
                    case INDEX -> b.endIndexSmallArray();
                    case FREEZE, UNSAFE_THAW -> b.endFreezeSmallArray();
                    case GET_SIZE_MUTABLE -> b.endGetSizeSmallMutableArray();
                    case CLONE_MUTABLE, SAFE_FREEZE, THAW -> b.endCopySmallArraySlice();
                    default -> throw new IllegalStateException("Not a tuple SmallArray operation");
                }
            });
            return new ProvenExpression(e -> {
                var b = e.builder;
                switch (operation) {
                    case WRITE -> b.beginWriteSmallArray();
                    case SHRINK -> b.beginShrinkSmallArray();
                    case CLONE -> b.beginCloneSmallArray();
                    case COPY, COPY_MUTABLE -> b.beginTransferSmallArray(operation == SmallArrayOp.COPY_MUTABLE);
                    default -> b.beginSizeSmallArray();
                }
                for (var operand : operands) operand.emit(e);
                switch (operation) {
                    case WRITE -> b.endWriteSmallArray();
                    case SHRINK -> b.endShrinkSmallArray();
                    case CLONE -> b.endCloneSmallArray();
                    case COPY, COPY_MUTABLE -> b.endTransferSmallArray();
                    default -> b.endSizeSmallArray();
                }
            }, evaluatedProof(tupleProof, true));
        }
        if (VectorMemoryOp.named(name) != null) {
            var operation = VectorMemoryOp.named(name);
            operation.validate(argumentProofs(args), flags, tupleProof);
            return vectorMemory(operation, compileOperands(args, scope));
        }
        if (PrefetchExpression.ARITIES.containsKey(name)) {
            if (args.size() != PrefetchExpression.ARITIES.get(name)) throw RuntimeFault.fault("Wrong prefetch arity");
            var value = argument(args.get(0), scope, CoreRepresentations.argumentMayBeLazy(flags.get(0), args.get(0)));
            var offset = args.size() == 3 ? compile(args.get(1), scope, false) : null;
            var state = compile(args.getLast(), scope, false);
            return new ProvenExpression(e -> {
                e.builder.beginPrefetch(); value.emit(e);
                if (offset == null) e.builder.emitLoadConstant(0L); else offset.emit(e);
                state.emit(e); e.builder.endPrefetch();
            }, evaluatedProof(tupleProof, true));
        }
        if (TraceOp.named(name) != null) {
            var operation = TraceOp.named(name);
            if (args.size() != operation.getArity()) throw RuntimeFault.fault("Wrong trace arity");
            var operands = compileOperands(args, scope);
            return new ProvenExpression(e -> {
                e.builder.beginTraceEvent(operation); operands.get(0).emit(e);
                if (operation == TraceOp.BINARY) operands.get(1).emit(e); else e.builder.emitLoadConstant(0L);
                operands.getLast().emit(e); e.builder.endTraceEvent();
            }, evaluatedProof(tupleProof, true));
        }
        if (name.equals("touch#")) {
            var metadata = CoreRepresentations.metadata(expr);
            CoreTouch.validateRaw(argumentMetadata(args), flags, metadata == null ? null : metadata.get("rep"));
            var lowered = argument(args.get(0), scope, CoreRepresentations.argumentMayBeLazy(flags.get(0), args.get(0)));
            var kept = new ProvenExpression(lowered, lowered.proof().refine(evaluatedProof(CoreRepresentations.expression(args.get(0)), false)));
            var state = compile(args.get(1), scope, false);
            CoreTouch.validate(List.of(kept.proof(), state.proof()), flags, tupleProof);
            return new ProvenExpression(e -> {
                e.builder.beginTouch(); kept.emit(e); state.emit(e); e.builder.endTouch();
            }, evaluatedProof(tupleProof, true));
        }
        if (name.equals("keepAlive#")) {
            var proofs = argumentProofs(args);
            var signature = args.size() > 2 ? CoreRepresentations.knownFunctionSignature(args.get(2), bindings) : null;
            CoreKeepAlive.validate(proofs, flags, tupleProof,
                signature == null ? null : signature.getInputs(), signature == null ? null : signature.getResult());
            var kept = argument(args.get(0), scope, CoreRepresentations.argumentMayBeLazy(flags.get(0), args.get(0)));
            var state = compile(args.get(1), scope, false);
            var function = argument(args.get(2), scope, true);
            BiConsumer<Emission, List<BytecodeLocal>> emitKeepAlive = (e, destination) -> {
                var b = e.builder;
                b.beginBlock();
                var reference = b.createLocal("kept alive reference", "object");
                var stateLocal = b.createLocal("keepAlive state", "object");
                b.beginStoreLocal(reference); kept.emit(e); b.endStoreLocal();
                b.beginStoreLocal(stateLocal); state.emit(e); b.endStoreLocal();
                b.beginRequireIOState(); b.emitLoadLocal(stateLocal); b.endRequireIOState();
                // The finally owns the fence even if forcing the continuation yields or fails.
                b.beginTryFinally(() -> {
                    b.beginStoreLocal(b.createLocal("keepAlive fence", null)); b.beginTouch();
                    b.emitLoadLocal(reference); b.emitLoadLocal(stateLocal); b.endTouch(); b.endStoreLocal();
                });
                var result = destination == null ? b.createLocal("keepAlive result", null) : null;
                if (result != null) b.beginStoreLocal(result);
                if (resumable) {
                    var stateArgument = new ProvenExpression(target -> target.builder.emitLoadLocal(stateLocal), evaluatedProof(state.proof(), true));
                    if (destination != null) checkpointedTupleApplication(e, new TupleShape(tupleProof, language), function, List.of(stateArgument), null, destination, false, false);
                    else checkpointedApplication(e, function, List.of(stateArgument), new boolean[]{true}, null, false, false);
                } else {
                    if (destination != null) b.beginKeepAliveTuple(tupleSlots(new TupleShape(tupleProof, language), destination), metrics);
                    else b.beginKeepAlive(metrics);
                    b.emitLoadLocal(reference); b.emitLoadLocal(stateLocal); force(function).emit(e);
                    if (destination != null) b.endKeepAliveTuple(); else b.endKeepAlive();
                }
                if (result != null) b.endStoreLocal(); b.endTryFinally();
                if (result != null) b.emitLoadLocal(result); b.endBlock();
            };
            if (tupleProof.isTypedTransport()) return tupleExpression(tupleProof, emitKeepAlive);
            return new ProvenExpression(e -> emitKeepAlive.accept(e, null), evaluatedProof(tupleProof, true));
        }
        if (AtomicAddressOp.named(name) != null) {
            var operation = AtomicAddressOp.named(name);
            operation.validate(argumentProofs(args), flags, tupleProof);
            var operands = compileOperands(args, scope);
            if (operation == AtomicAddressOp.WRITE) return new ProvenExpression(e -> {
                e.builder.beginAtomicAddressWrite(); for (var operand : operands) operand.emit(e); e.builder.endAtomicAddressWrite();
            }, evaluatedProof(tupleProof, true));
            return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                if (operation.getPointer()) b.beginAtomicAddressPointer(operation, destination.get(0)); else b.beginAtomicAddressNumeric(operation, destination.get(0));
                operands.get(0).emit(e);
                if (operation == AtomicAddressOp.READ) b.emitLoadConstant(0L); else operands.get(1).emit(e);
                if (operation.getCas()) operands.get(2).emit(e);
                else if (operation.getPointer()) b.emitLoadConstant(ManagedAddress.nullAddress()); else b.emitLoadConstant(0L);
                operands.getLast().emit(e);
                if (operation.getPointer()) b.endAtomicAddressPointer(); else b.endAtomicAddressNumeric();
            });
        }
        if (FloatingAddressOp.named(name) != null) {
            var operation = FloatingAddressOp.named(name);
            boolean byteOffset = name.contains("Word8") && name.contains("As");
            operation.validate(argumentProofs(args), flags, tupleProof);
            var operands = compileOperands(args, scope);
            if (operation.getTuple()) return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                if (operation.getFloating()) b.beginReadFloatOffAddr(byteOffset, destination.get(0)); else b.beginReadDoubleOffAddr(byteOffset, destination.get(0));
                for (var operand : operands) operand.emit(e);
                if (operation.getFloating()) b.endReadFloatOffAddr(); else b.endReadDoubleOffAddr();
            });
            return new ProvenExpression(e -> {
                var b = e.builder;
                if (operation.getWrite()) {
                    if (operation.getFloating()) b.beginWriteFloatOffAddr(byteOffset); else b.beginWriteDoubleOffAddr(byteOffset);
                } else {
                    if (operation.getFloating()) b.beginIndexFloatOffAddr(byteOffset); else b.beginIndexDoubleOffAddr(byteOffset);
                }
                for (var operand : operands) operand.emit(e);
                if (operation.getWrite()) {
                    if (operation.getFloating()) b.endWriteFloatOffAddr(); else b.endWriteDoubleOffAddr();
                } else {
                    if (operation.getFloating()) b.endIndexFloatOffAddr(); else b.endIndexDoubleOffAddr();
                }
            }, evaluatedProof(tupleProof, true));
        }
        if (AddressArrayCopyOp.named(name) != null) {
            var operation = AddressArrayCopyOp.named(name);
            operation.validate(argumentProofs(args), flags, tupleProof);
            var operands = compileOperands(args, scope);
            return new ProvenExpression(e -> {
                var b = e.builder;
                if (operation.getToArray()) b.beginCopyAddressToByteArray(); else b.beginCopyByteArrayToAddress();
                for (var operand : operands) operand.emit(e);
                if (operation.getToArray()) b.endCopyAddressToByteArray(); else b.endCopyByteArrayToAddress();
            }, evaluatedProof(tupleProof, true));
        }
        if (PinnedMemoryOp.named(name) != null) {
            var operation = PinnedMemoryOp.named(name);
            boolean byteOffset = name.contains("Word8") && name.contains("As");
            operation.validate(argumentProofs(args), flags, tupleProof);
            var operands = compileOperands(args, scope);
            if (operation.getTuple()) return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                switch (operation) {
                    case NEW -> b.beginNewPinnedByteArray(destination.get(0));
                    case NEW_ALIGNED -> b.beginNewAlignedPinnedByteArray(destination.get(0));
                    case READ -> b.beginReadWord8OffAddr(destination.get(0));
                    case READ_CHAR -> b.beginReadCharOffAddr(destination.get(0));
                    case READ_INT8 -> b.beginReadInt8OffAddr(destination.get(0));
                    case READ_ADDR -> b.beginReadAddrOffAddr(byteOffset, destination.get(0));
                    case READ_ADDR_ARRAY -> b.beginReadAddrArray(byteOffset, destination.get(0));
                    case READ_WORD16, READ_INT16, READ_WORD32, READ_WIDE_CHAR, READ_WORD, READ_INT32, READ_INT, READ_INT64, READ_WORD64 -> b.beginReadManagedAddress(Objects.requireNonNull(operation.getAddressRead()), byteOffset, destination.get(0));
                    default -> throw new IllegalStateException("Scalar pinned memory operation");
                }
                for (var operand : operands) operand.emit(e);
                switch (operation) {
                    case NEW -> b.endNewPinnedByteArray();
                    case NEW_ALIGNED -> b.endNewAlignedPinnedByteArray();
                    case READ -> b.endReadWord8OffAddr();
                    case READ_CHAR -> b.endReadCharOffAddr();
                    case READ_INT8 -> b.endReadInt8OffAddr();
                    case READ_ADDR -> b.endReadAddrOffAddr();
                    case READ_ADDR_ARRAY -> b.endReadAddrArray();
                    case READ_WORD16, READ_INT16, READ_WORD32, READ_WIDE_CHAR, READ_WORD, READ_INT32, READ_INT, READ_INT64, READ_WORD64 -> b.endReadManagedAddress();
                    default -> throw new IllegalStateException("Scalar pinned memory operation");
                }
            });
            return new ProvenExpression(e -> {
                var b = e.builder;
                switch (operation) {
                    case CONTENTS, MUTABLE_CONTENTS -> b.beginByteArrayContents();
                    case WRITE_ADDR -> b.beginAddressWrite(byteOffset);
                    case COPY_ADDR_NON_OVERLAPPING -> b.beginAddressWrite(false);
                    case WRITE_ADDR_ARRAY -> b.beginWriteAddrArray(byteOffset);
                    case WRITE_INT16, WRITE_WORD16 -> b.beginWriteWord16OffAddr(byteOffset);
                    case COPY_ADDR -> b.beginMoveAddress();
                    case SET_ADDR -> b.beginFillAddress();
                    case WRITE_INT32, WRITE_WORD32, WRITE_WIDE_CHAR -> b.beginWriteNativeScalarOffAddr(4, byteOffset);
                    case WRITE_INT, WRITE_WORD, WRITE_INT64, WRITE_WORD64 -> b.beginWriteNativeScalarOffAddr(8, byteOffset);
                    case INDEX_ADDR_OFF -> b.beginIndexAddrOffAddr(byteOffset);
                    case INDEX_ADDR_ARRAY -> b.beginIndexAddrArray(byteOffset);
                    case INDEX_WORD8_AS_CHAR, INDEX_WORD8_AS_INT16, INDEX_WORD8_AS_WORD16, INDEX_INT32, INDEX_WORD32, INDEX_WIDE_CHAR, INDEX_INT, INDEX_WORD, INDEX_INT64, INDEX_WORD64 -> b.beginIndexManagedAddress(Objects.requireNonNull(operation.getAddressRead()), byteOffset);
                    default -> b.beginWriteWord8OffAddr();
                }
                for (var operand : operands) operand.emit(e);
                switch (operation) {
                    case CONTENTS, MUTABLE_CONTENTS -> b.endByteArrayContents();
                    case WRITE_ADDR -> b.endAddressWrite();
                    case COPY_ADDR_NON_OVERLAPPING -> b.endAddressWrite();
                    case COPY_ADDR -> b.endMoveAddress();
                    case SET_ADDR -> b.endFillAddress();
                    case WRITE_ADDR_ARRAY -> b.endWriteAddrArray();
                    case WRITE_INT16, WRITE_WORD16 -> b.endWriteWord16OffAddr();
                    case WRITE_INT32, WRITE_WORD32, WRITE_WIDE_CHAR, WRITE_INT, WRITE_WORD, WRITE_INT64, WRITE_WORD64 -> b.endWriteNativeScalarOffAddr();
                    case INDEX_ADDR_OFF -> b.endIndexAddrOffAddr();
                    case INDEX_ADDR_ARRAY -> b.endIndexAddrArray();
                    case INDEX_WORD8_AS_CHAR, INDEX_WORD8_AS_INT16, INDEX_WORD8_AS_WORD16, INDEX_INT32, INDEX_WORD32, INDEX_WIDE_CHAR, INDEX_INT, INDEX_WORD, INDEX_INT64, INDEX_WORD64 -> b.endIndexManagedAddress();
                    default -> b.endWriteWord8OffAddr();
                }
            }, evaluatedProof(tupleProof, true));
        }
        if (AtomicIntArrayOp.named(name) != null) {
            var operation = AtomicIntArrayOp.named(name);
            operation.validate(argumentProofs(args), flags, tupleProof);
            var operands = compileOperands(args, scope);
            if (operation.getTuple()) return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                b.beginAtomicIntArray(operation, destination.get(0)); operands.get(0).emit(e); operands.get(1).emit(e);
                if (operation.getOperands() > 0) operands.get(2).emit(e); else b.emitLoadConstant(0L);
                if (operation.getOperands() == 2) operands.get(3).emit(e); else b.emitLoadConstant(0L);
                operands.getLast().emit(e); b.endAtomicIntArray();
            });
            return new ProvenExpression(e -> {
                e.builder.beginAtomicWriteIntArray(); for (var operand : operands) operand.emit(e); e.builder.endAtomicWriteIntArray();
            }, evaluatedProof(tupleProof, true));
        }
        if (ByteArrayOp.named(name) != null) {
            var operation = ByteArrayOp.named(name);
            boolean byteOffset = name.contains("Word8") && name.contains("As");
            operation.validate(argumentProofs(args), flags, tupleProof);
            var operands = compileOperands(args, scope);
            if (operation.getTuple()) return tupleExpression(tupleProof, (e, destination) -> {
                var b = e.builder;
                switch (operation) {
                    case NEW -> b.beginNewByteArray(destination.get(0));
                    case RESIZE -> b.beginResizeByteArray(false, destination.get(0));
                    case GET_SIZE_MUTABLE -> b.beginGetSizeMutableByteArray(destination.get(0));
                    case FREEZE, UNSAFE_THAW -> b.beginFreezeByteArray(destination.get(0));
                    case READ_INT, READ_WORD, READ_INT64, READ_WORD64 -> b.beginIntArrayAccess(byteOffset, destination.get(0));
                    case READ_DOUBLE, READ_WORD8_AS_DOUBLE -> b.beginReadDoubleArray(operation == ByteArrayOp.READ_WORD8_AS_DOUBLE, destination.get(0));
                    case READ_FLOAT, READ_WORD8_AS_FLOAT -> b.beginReadFloatArray(operation == ByteArrayOp.READ_WORD8_AS_FLOAT, destination.get(0));
                    case READ_CHAR -> b.beginReadCharArray(destination.get(0));
                    case READ_WIDE_CHAR -> b.beginReadWideCharArray(byteOffset, destination.get(0));
                    case READ_INT8, READ_WORD8 -> b.beginReadByteArray(operation != ByteArrayOp.READ_INT8, destination.get(0));
                    case READ_INT16, READ_WORD16, READ_WORD8_AS_INT16, READ_WORD8_AS_WORD16 -> b.beginReadInt16Array( operation == ByteArrayOp.READ_WORD16 || operation == ByteArrayOp.READ_WORD8_AS_WORD16, operation == ByteArrayOp.READ_WORD8_AS_INT16 || operation == ByteArrayOp.READ_WORD8_AS_WORD16, destination.get(0));
                    case READ_INT32, READ_WORD32, READ_WORD8_AS_INT32, READ_WORD8_AS_WORD32 -> b.beginReadInt32Array( operation == ByteArrayOp.READ_WORD32 || operation == ByteArrayOp.READ_WORD8_AS_WORD32, operation == ByteArrayOp.READ_WORD8_AS_INT32 || operation == ByteArrayOp.READ_WORD8_AS_WORD32, destination.get(0));
                    default -> throw new IllegalStateException("Scalar ByteArray operation");
                }
                for (var operand : operands) operand.emit(e);
                switch (operation) {
                    case NEW -> b.endNewByteArray();
                    case RESIZE -> b.endResizeByteArray();
                    case GET_SIZE_MUTABLE -> b.endGetSizeMutableByteArray();
                    case FREEZE, UNSAFE_THAW -> b.endFreezeByteArray();
                    case READ_INT, READ_WORD, READ_INT64, READ_WORD64 -> b.endIntArrayAccess();
                    case READ_DOUBLE, READ_WORD8_AS_DOUBLE -> b.endReadDoubleArray();
                    case READ_FLOAT, READ_WORD8_AS_FLOAT -> b.endReadFloatArray();
                    case READ_CHAR -> b.endReadCharArray();
                    case READ_WIDE_CHAR -> b.endReadWideCharArray();
                    case READ_INT8, READ_WORD8 -> b.endReadByteArray();
                    case READ_INT16, READ_WORD16, READ_WORD8_AS_INT16, READ_WORD8_AS_WORD16 -> b.endReadInt16Array();
                    case READ_INT32, READ_WORD32, READ_WORD8_AS_INT32, READ_WORD8_AS_WORD32 -> b.endReadInt32Array();
                    default -> throw new IllegalStateException("Scalar ByteArray operation");
                }
            });
            return new ProvenExpression(e -> {
                var b = e.builder;
                switch (operation) {
                    case COMPARE -> b.beginCompareByteArrays();
                    case SHRINK -> { b.beginBlock(); b.beginResizeByteArray(true, b.createLocal("shrink has no result", null)); }
                    case COPY -> b.beginCopyByteArray();
                    case SET -> b.beginSetByteArray();
                    case COPY_MUTABLE, COPY_MUTABLE_NON_OVERLAPPING -> b.beginCopyMutableByteArray(operation == ByteArrayOp.COPY_MUTABLE_NON_OVERLAPPING);
                    case WRITE, WRITE_INT8, WRITE_CHAR -> b.beginWriteByteArray();
                    case SIZE, SIZE_MUTABLE -> b.beginSizeByteArray();
                    case IS_PINNED, IS_MUTABLE_PINNED -> b.beginPinnedByteArray(false);
                    case IS_WEAKLY_PINNED, IS_MUTABLE_WEAKLY_PINNED -> b.beginPinnedByteArray(true);
                    case INDEX -> b.beginIndexByteArray();
                    case INDEX_CHAR -> b.beginIndexCharArray();
                    case INDEX_WIDE_CHAR -> b.beginIndexWideCharArray(byteOffset);
                    case WRITE_WIDE_CHAR -> b.beginWriteWideCharArray(byteOffset);
                    case INDEX_INT8 -> b.beginIndexSignedByteArray();
                    case WRITE_INT, WRITE_WORD, WRITE_INT64, WRITE_WORD64 -> b.beginWriteIntArray(byteOffset);
                    case INDEX_INT, INDEX_WORD, INDEX_INT64, INDEX_WORD64 -> b.beginIndexIntArray(byteOffset);
                    case WRITE_DOUBLE, WRITE_WORD8_AS_DOUBLE -> b.beginWriteDoubleArray(operation == ByteArrayOp.WRITE_WORD8_AS_DOUBLE);
                    case INDEX_DOUBLE, INDEX_WORD8_AS_DOUBLE -> b.beginIndexDoubleArray(operation == ByteArrayOp.INDEX_WORD8_AS_DOUBLE);
                    case WRITE_FLOAT, WRITE_WORD8_AS_FLOAT -> b.beginWriteFloatArray(operation == ByteArrayOp.WRITE_WORD8_AS_FLOAT);
                    case INDEX_FLOAT, INDEX_WORD8_AS_FLOAT -> b.beginIndexFloatArray(operation == ByteArrayOp.INDEX_WORD8_AS_FLOAT);
                    case WRITE_INT16, WRITE_WORD16, WRITE_WORD8_AS_INT16, WRITE_WORD8_AS_WORD16 -> b.beginWriteInt16Array( operation == ByteArrayOp.WRITE_WORD8_AS_INT16 || operation == ByteArrayOp.WRITE_WORD8_AS_WORD16);
                    case WRITE_INT32, WRITE_WORD32, WRITE_WORD8_AS_INT32, WRITE_WORD8_AS_WORD32 -> b.beginWriteInt32Array( operation == ByteArrayOp.WRITE_WORD8_AS_INT32 || operation == ByteArrayOp.WRITE_WORD8_AS_WORD32);
                    case INDEX_INT16, INDEX_WORD16, INDEX_WORD8_AS_INT16, INDEX_WORD8_AS_WORD16 -> b.beginIndexInt16Array( operation == ByteArrayOp.INDEX_WORD16 || operation == ByteArrayOp.INDEX_WORD8_AS_WORD16, operation == ByteArrayOp.INDEX_WORD8_AS_INT16 || operation == ByteArrayOp.INDEX_WORD8_AS_WORD16);
                    case INDEX_INT32, INDEX_WORD32, INDEX_WORD8_AS_INT32, INDEX_WORD8_AS_WORD32 -> b.beginIndexInt32Array( operation == ByteArrayOp.INDEX_WORD32 || operation == ByteArrayOp.INDEX_WORD8_AS_WORD32, operation == ByteArrayOp.INDEX_WORD8_AS_INT32 || operation == ByteArrayOp.INDEX_WORD8_AS_WORD32);
                    default -> throw new IllegalStateException("Tuple ByteArray operation");
                }
                for (var operand : operands) operand.emit(e);
                switch (operation) {
                    case COMPARE -> b.endCompareByteArrays();
                    case SHRINK -> { b.endResizeByteArray(); b.emitLoadConstant(thc.runtime.Unit.INSTANCE); b.endBlock(); }
                    case COPY -> b.endCopyByteArray();
                    case SET -> b.endSetByteArray();
                    case COPY_MUTABLE, COPY_MUTABLE_NON_OVERLAPPING -> b.endCopyMutableByteArray();
                    case WRITE, WRITE_INT8, WRITE_CHAR -> b.endWriteByteArray();
                    case SIZE, SIZE_MUTABLE -> b.endSizeByteArray();
                    case IS_PINNED, IS_MUTABLE_PINNED, IS_WEAKLY_PINNED, IS_MUTABLE_WEAKLY_PINNED -> b.endPinnedByteArray();
                    case INDEX -> b.endIndexByteArray();
                    case INDEX_CHAR -> b.endIndexCharArray();
                    case INDEX_WIDE_CHAR -> b.endIndexWideCharArray();
                    case WRITE_WIDE_CHAR -> b.endWriteWideCharArray();
                    case INDEX_INT8 -> b.endIndexSignedByteArray();
                    case WRITE_INT, WRITE_WORD, WRITE_INT64, WRITE_WORD64 -> b.endWriteIntArray();
                    case INDEX_INT, INDEX_WORD, INDEX_INT64, INDEX_WORD64 -> b.endIndexIntArray();
                    case WRITE_DOUBLE, WRITE_WORD8_AS_DOUBLE -> b.endWriteDoubleArray();
                    case INDEX_DOUBLE, INDEX_WORD8_AS_DOUBLE -> b.endIndexDoubleArray();
                    case WRITE_FLOAT, WRITE_WORD8_AS_FLOAT -> b.endWriteFloatArray();
                    case INDEX_FLOAT, INDEX_WORD8_AS_FLOAT -> b.endIndexFloatArray();
                    case WRITE_INT16, WRITE_WORD16, WRITE_WORD8_AS_INT16, WRITE_WORD8_AS_WORD16 -> b.endWriteInt16Array();
                    case WRITE_INT32, WRITE_WORD32, WRITE_WORD8_AS_INT32, WRITE_WORD8_AS_WORD32 -> b.endWriteInt32Array();
                    case INDEX_INT16, INDEX_WORD16, INDEX_WORD8_AS_INT16, INDEX_WORD8_AS_WORD16 -> b.endIndexInt16Array();
                    case INDEX_INT32, INDEX_WORD32, INDEX_WORD8_AS_INT32, INDEX_WORD8_AS_WORD32 -> b.endIndexInt32Array();
                    default -> throw new IllegalStateException("Tuple ByteArray operation");
                }
            }, evaluatedProof(tupleProof, true));
        }
        return null;
    }

}
