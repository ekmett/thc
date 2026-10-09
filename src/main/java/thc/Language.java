// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.debug.DebuggerTags;
import com.oracle.truffle.api.instrumentation.ProvidedTags;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.ExecutionSignature;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.graalvm.options.OptionCategory;
import org.graalvm.options.OptionDescriptors;
import org.graalvm.options.OptionKey;
import org.graalvm.nativeimage.ImageInfo;
import thc.runtime.*;

@ProvidedTags({StandardTags.RootTag.class, StandardTags.RootBodyTag.class, StandardTags.StatementTag.class, DebuggerTags.AlwaysHalt.class})
@TruffleLanguage.Registration(id = "thc", name = "Turbo Haskell Compiler", version = "0.1-experiment",
    characterMimeTypes = "application/x-thc-core", defaultMimeType = "application/x-thc-core",
    dependentLanguages = "llvm", contextPolicy = TruffleLanguage.ContextPolicy.SHARED)
public final class Language extends TruffleLanguage<Language.ContextState> {
    @Option(name = "ThreadHosting", help = "Guest thread host: platform (default) or experimental loom (pinned JDK 25).", category = OptionCategory.USER)
    static final OptionKey<String> THREAD_HOSTING = new OptionKey<>("platform");
    @Option(name = "ByteArrayStorage", help = "Ordinary guest byte-array backing: heap (default) or native (requires native access).", category = OptionCategory.USER)
    static final OptionKey<String> BYTE_ARRAY_STORAGE = new OptionKey<>("heap");
    @Option(name = "SparkQueueCapacity", help = "Bounded async-capable thunk queue for one speculative worker; zero disables sparks (default), maximum 65536.", category = OptionCategory.USER)
    static final OptionKey<Integer> SPARK_QUEUE_CAPACITY = new OptionKey<>(0);
    @Override protected OptionDescriptors getOptionDescriptors() { return new LanguageOptionDescriptors(); }
    // Layout interning belongs to a context even when the language instance is shared.
    public HandoffLayouts getHandoffLayouts() { return currentState(null).handoffLayouts; }
    private final ContextThreadLocal<HandoffState> handoffState = locals.createContextThreadLocal((context, thread) -> new HandoffState());
    public ContextThreadLocal<HandoffState> getHandoffState() { return handoffState; }
    private final ContextThreadLocal<GuestThreads.PollState> threadPollState = locals.createContextThreadLocal((context, thread) -> context.pollStates.get(thread));
    private final ContextThreadLocal<CarrierLocal.Cell<MaskingState>> threadMaskingState = locals.createContextThreadLocal((context, thread) -> context.maskingState.cell$org_intelligence_thc(thread));
    private final ContextThreadLocal<CarrierLocal.Cell<StackAnnotationState>> threadAnnotations = locals.createContextThreadLocal((context, thread) -> context.stackAnnotations.cell$org_intelligence_thc(thread));
    private static final ContextReference<ContextState> CONTEXTS = ContextReference.create(Language.class);
    public static State currentState() { return currentState(null); }
    public static State currentState(Node node) {
        var context = CONTEXTS.get(node);
        if (context == null) throw new IllegalStateException("No entered THC context");
        return context.requireState();
    }

    /** Truffle preserves this object when replacing a preinitialized context's Env. */
    public static final class ContextState {
        private enum Phase { PREPARING, PREPARED, ACTIVE, CLOSED }
        @CompilerDirectives.CompilationFinal private State state;
        private CarrierLocal<MaskingState> maskingState = new CarrierLocal<>(MaskingState.UNMASKED);
        private CarrierLocal<StackAnnotationState> stackAnnotations = new CarrierLocal<>(StackAnnotationState.EMPTY);
        private GuestThreads.PollStates pollStates = new GuestThreads.PollStates();
        private Phase phase;
        private Thread firstThread;
        // Truffle initializes multithreading once for the holder's whole lifetime.
        private boolean multithreaded;

        private ContextState(Env env, Language language) {
            phase = env.isPreInitialization() ? Phase.PREPARING : Phase.ACTIVE;
            state = new State(env, language, maskingState, stackAnnotations, pollStates);
        }
        private State requireState() {
            var current = state;
            if (current == null) throw new IllegalStateException("THC context has no active runtime state");
            return current;
        }
        private synchronized void noteThread(Thread thread) {
            if (state != null) state.noteThread(thread);
            else if (phase == Phase.PREPARED) {
                if (firstThread == null) firstThread = thread;
                else if (firstThread != thread) multithreaded = true;
            }
        }
        private synchronized void markMultithreaded() {
            multithreaded = true;
            if (state != null) state.markMultithreaded();
        }
        private void finishPreparation(Throwable failure) {
            if (phase != Phase.PREPARING) return;
            var preparation = requireState();
            failure = finishState(preparation, failure);
            try { failure = disposeState(preparation, failure); }
            finally {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                state = null;
                // The build carrier's Truffle locals are disposed after initializeContext returns.
                // Runtime factories run before patchContext, so install their empty epoch now.
                maskingState = new CarrierLocal<>(MaskingState.UNMASKED);
                stackAnnotations = new CarrierLocal<>(StackAnnotationState.EMPTY);
                pollStates = new GuestThreads.PollStates();
                firstThread = null;
                phase = failure == null ? Phase.PREPARED : Phase.CLOSED;
            }
            if (failure != null) Language.<RuntimeException, Void>rethrow(failure);
        }
        private synchronized boolean patch(Env env, Language language) {
            if (phase != Phase.PREPARED || env.isPreInitialization()) return false;
            try {
                var runtime = new State(env, language, maskingState, stackAnnotations, pollStates);
                // Entry and its thread callbacks precede patchContext and will not be replayed.
                runtime.noteThread(firstThread == null ? Thread.currentThread() : firstThread);
                if (multithreaded) runtime.markMultithreaded();
                CompilerDirectives.transferToInterpreterAndInvalidate();
                state = runtime;
                firstThread = null;
                phase = Phase.ACTIVE;
                return true;
            } catch (Throwable failure) {
                phase = Phase.CLOSED;
                firstThread = null;
                return Language.<RuntimeException, Boolean>rethrow(failure);
            }
        }
        private void disposed() {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            state = null;
            firstThread = null;
            phase = Phase.CLOSED;
        }
    }

    public static final class State {
        private final Env env;
        private final boolean nativeByteArrays;
        private final AtomicReference<GuestShutdown> shutdown;
        private final ManagedExportRegistry managedExports;
        private final ManagedForeignRoots foreignRoots;
        private final HandoffLayouts handoffLayouts;
        private record ConstructorLayoutKey(String id, CoreFields fields) {}
        private final Map<ConstructorLayoutKey, DataLayout> constructorLayouts = new HashMap<>();
        private final JavaScriptImports javaScriptImports;
        private final PackageScalarLibraries packageCbits;
        private final NativeCallbacks nativeCallbacks;
        private final CarrierLocal<MaskingState> maskingState;
        private final CarrierLocal<StackAnnotationState> stackAnnotations;
        private final GuestThreads threads;
        private final SparkPool sparks;
        private final ContextThreadLocal<GuestThreads.PollState> threadPollState;
        private final ContextThreadLocal<CarrierLocal.Cell<MaskingState>> threadMaskingState;
        private final ContextThreadLocal<CarrierLocal.Cell<StackAnnotationState>> threadAnnotations;
        private final RuntimeTraceServices runtimeTrace;
        private final Object compilationOwner = new Object();
        private final RuntimeJitServices runtimeJit;
        private final GraphRecovery graphRecovery;
        public final ManagedSTM stm;
        private final ManagedFiles files;
        private final RtsFileLocks rtsFileLocks;
        // Installed only by explicit fixed-filesystem factories; ordinary builders retain embedding IO.
        private NativeFileProvider nativeFiles;
        private WindowsDirectoryStreams windowsDirectories;
        private final WindowsCodePages windowsCodePages;
        private final ManagedStdio stdio;
        private final ManagedSignals signals;
        private final SavedTermios savedTermios;
        private final ManagedIconv iconv;
        private final ManagedStackRegistry stackSnapshots;
        public final ClosureInfoTables closureInfo;
        private final CapturedAsyncRequests capturedAsyncRequests;
        private final ForeignExceptionRegistry foreignExceptionRegistry;
        private final ThreadLocal<Boolean> foreignExceptionNormalization;
        private final CopyOnWriteArrayList<CoreUnitProgram> coreUnitPrograms;
        private final StablePointers stablePointers;
        private final CompilerRts compilerRts;
        private final StableNames stableNames;
        public final ManagedCompacts compactRegions;
        public final HeapAddresses heapAddresses;
        public final CompactImages compactImages;
        private final NativeAddresses nativeAddresses;
        private final ManagedNativeAllocations nativeAllocations;
        private final GuestArguments arguments;
        private final GuestEnvironment environment;
        private final ManagedWeaks weaks;
        private final Assumption singleThreadedAssumption;
        private Thread firstThread;
        private final Assumption singleGuestOriginAssumption = Truffle.getRuntime().createAssumption("THC single guest admission origin");
        private Thread firstGuestOrigin;
        // Prepared AOT targets cannot depend on invalidatable context assumptions:
        // they read this irreversible runtime admission state instead.
        private volatile boolean guestConcurrencyAdmitted;
        private final AtomicReference<FutureTask<SulongCbits>> nativeCbits;
        private State(Env env, Language language, CarrierLocal<MaskingState> maskingState,
                CarrierLocal<StackAnnotationState> stackAnnotations, GuestThreads.PollStates pollStates) {
            if (env.isPreInitialization() && env.isNativeAccessAllowed())
                throw new IllegalArgumentException("THC context preinitialization requires native access to be disabled");
            this.env = env;
            nativeByteArrays = switch (env.getOptions().get(BYTE_ARRAY_STORAGE)) {
                case "heap" -> false;
                case "native" -> {
                    if (!env.isNativeAccessAllowed()) throw new IllegalArgumentException("Native byte-array storage requires native access");
                    yield true;
                }
                default -> throw new IllegalArgumentException("ByteArrayStorage must be heap or native");
            };
            // The inert pool owns capacity/permission validation and starts no worker here.
            sparks = new SparkPool(this, language, env.getOptions().get(SPARK_QUEUE_CAPACITY));
            this.maskingState = maskingState;
            this.stackAnnotations = stackAnnotations;
            // Hosting validation precedes allocation of the other runtime service owners.
            threads = new GuestThreads(env, maskingState, env.getOptions().get(THREAD_HOSTING), pollStates);
            shutdown = new AtomicReference<>();
            managedExports = new ManagedExportRegistry(this, language);
            foreignRoots = new ManagedForeignRoots(this);
            handoffLayouts = new HandoffLayouts(language);
            javaScriptImports = new JavaScriptImports();
            packageCbits = new PackageScalarLibraries(env);
            nativeCallbacks = new NativeCallbacks(this);
            threadPollState = language.threadPollState;
            threadMaskingState = language.threadMaskingState;
            threadAnnotations = language.threadAnnotations;
            runtimeTrace = new RuntimeTraceServices(env.err());
            runtimeJit = new RuntimeJitServices(this);
            stm = new ManagedSTM();
            files = new ManagedFiles(env, threads);
            rtsFileLocks = new RtsFileLocks();
            nativeFiles = null;
            windowsDirectories = null;
            windowsCodePages = new WindowsCodePages(this);
            stdio = new ManagedStdio(files);
            signals = new ManagedSignals(this, language);
            savedTermios = new SavedTermios(this);
            iconv = new ManagedIconv(this::cbits, stdio, threads);
            stackSnapshots = new ManagedStackRegistry();
            closureInfo = new ClosureInfoTables();
            capturedAsyncRequests = new CapturedAsyncRequests();
            foreignExceptionRegistry = new ForeignExceptionRegistry();
            foreignExceptionNormalization = ThreadLocal.withInitial(() -> false);
            coreUnitPrograms = new CopyOnWriteArrayList<>();
            stablePointers = new StablePointers();
            compilerRts = new CompilerRts();
            stableNames = new StableNames();
            compactRegions = new ManagedCompacts();
            heapAddresses = new HeapAddresses();
            compactImages = new CompactImages(compactRegions, heapAddresses);
            nativeAddresses = new NativeAddresses(env);
            nativeAllocations = new ManagedNativeAllocations(env);
            arguments = new GuestArguments(env);
            environment = new GuestEnvironment(env);
            weaks = new ManagedWeaks(this, language);
            singleThreadedAssumption = Truffle.getRuntime().createAssumption("THC single-threaded context");
            nativeCbits = new AtomicReference<>();
            // This constructor installs a compiler listener; keep it after the inert service constructors.
            graphRecovery = new GraphRecovery(this);
        }
        public Env getEnv() { return env; }
        public boolean getNativeByteArrays() { return nativeByteArrays; }
        public AtomicReference<GuestShutdown> getShutdown() { return shutdown; }
        public ManagedExportRegistry getManagedExports() { return managedExports; }
        public ManagedForeignRoots getForeignRoots() { return foreignRoots; }
        public HandoffLayouts getHandoffLayouts() { return handoffLayouts; }
        /** Only parsed shape metadata enters this cache; demand loading happens before taking its lock. */
        public synchronized DataLayout constructorLayout(TruffleLanguage<?> language, String id, String name, CoreFields fields) {
            int unitEnd = id.indexOf(':');
            // Symbolic constructor occurrences can contain or end in a dot.
            int moduleEnd = id.indexOf('.', unitEnd + 1);
            if (unitEnd <= 0 || moduleEnd <= unitEnd + 1 || moduleEnd == id.length() - 1)
                return DataLayout.fromFields(language, id, name, fields);
            return constructorLayouts.computeIfAbsent(new ConstructorLayoutKey(id, fields),
                key -> DataLayout.fromFields(language, id, name, fields));
        }
        public JavaScriptImports getJavaScriptImports() { return javaScriptImports; }
        public PackageScalarLibraries getPackageCbits() { return packageCbits; }
        public NativeCallbacks getNativeCallbacks() { return nativeCallbacks; }
        public CarrierLocal<MaskingState> getMaskingState() { return maskingState; }
        public CarrierLocal<StackAnnotationState> getStackAnnotations() { return stackAnnotations; }
        public GuestThreads getThreads() { return threads; }
        public SparkPool getSparks() { return sparks; }
        public ContextThreadLocal<GuestThreads.PollState> getThreadPollState() { return threadPollState; }
        public ContextThreadLocal<CarrierLocal.Cell<MaskingState>> getThreadMaskingState() { return threadMaskingState; }
        public ContextThreadLocal<CarrierLocal.Cell<StackAnnotationState>> getThreadAnnotations() { return threadAnnotations; }
        public RuntimeTraceServices getRuntimeTrace() { return runtimeTrace; }
        public RuntimeJitServices getRuntimeJit() { return runtimeJit; }
        public GraphRecovery getGraphRecovery() { return graphRecovery; }
        public Object getCompilationOwner() { return compilationOwner; }
        public ManagedFiles getFiles() { return files; }
        public RtsFileLocks getRtsFileLocks() { return rtsFileLocks; }
        public NativeFileProvider getNativeFiles() { return nativeFiles; }
        public void setNativeFiles(NativeFileProvider value) { nativeFiles = value; }
        public WindowsDirectoryStreams getWindowsDirectories() { return windowsDirectories; }
        public void setWindowsDirectories(WindowsDirectoryStreams value) { windowsDirectories = value; }
        public WindowsCodePages getWindowsCodePages() { return windowsCodePages; }
        public ManagedStdio getStdio() { return stdio; }
        public ManagedSignals getSignals() { return signals; }
        public SavedTermios getSavedTermios() { return savedTermios; }
        public ManagedIconv getIconv() { return iconv; }
        public ManagedStackRegistry getStackSnapshots() { return stackSnapshots; }
        public CapturedAsyncRequests getCapturedAsyncRequests() { return capturedAsyncRequests; }
        public ForeignExceptionRegistry getForeignExceptionRegistry() { return foreignExceptionRegistry; }
        public ThreadLocal<Boolean> getForeignExceptionNormalization() { return foreignExceptionNormalization; }
        public CopyOnWriteArrayList<CoreUnitProgram> getCoreUnitPrograms() { return coreUnitPrograms; }
        public StablePointers getStablePointers() { return stablePointers; }
        public CompilerRts getCompilerRts() { return compilerRts; }
        public StableNames getStableNames() { return stableNames; }
        public NativeAddresses getNativeAddresses() { return nativeAddresses; }
        public ManagedNativeAllocations getNativeAllocations() { return nativeAllocations; }
        public GuestArguments getArguments() { return arguments; }
        public GuestEnvironment getEnvironment() { return environment; }
        public ManagedWeaks getWeaks() { return weaks; }
        public Assumption getSingleThreadedAssumption() { return singleThreadedAssumption; }
        public synchronized void noteThread(Thread thread) {
            if (firstThread == null) firstThread = thread;
            else if (firstThread != thread) markMultithreaded();
        }
        public void markMultithreaded() { singleThreadedAssumption.invalidate("A second guest thread entered the context"); }

        public Assumption getSingleGuestOriginAssumption() { return singleGuestOriginAssumption; }
        public boolean isGuestConcurrencyAdmitted() { return guestConcurrencyAdmitted; }
        /** The public Context builder's creator is not exposed by Truffle. Pin the first
         * public guest admission, before any internal hosting changes its carrier. */
        @TruffleBoundary public synchronized void admitGuestOrigin() {
            threads.checkEntryAllowed();
            Thread origin = threads.admissionOrigin();
            if (firstGuestOrigin == null) firstGuestOrigin = origin;
            else if (firstGuestOrigin != origin) admitGuestConcurrency();
        }
        /** Must precede child construction/publication or another origin's guest effects. */
        @TruffleBoundary public synchronized void admitGuestConcurrency() {
            guestConcurrencyAdmitted = true;
            singleGuestOriginAssumption.invalidate("Another guest thread or admission origin was admitted");
        }

        @TruffleBoundary public SulongCbits cbits() {
            if (!env.isNativeAccessAllowed()) throw new RuntimeFault("C bitcode requires native access for the Sulong runtime");
            var task = nativeCbits.get();
            if (task == null) {
                var candidate = new FutureTask<>(() -> new SulongCbits(env));
                if (nativeCbits.compareAndSet(null, candidate)) {
                    task = candidate;
                    candidate.run(); // Parsing LLVM can execute guest code; never hold a cache lock here.
                } else task = nativeCbits.get();
            }
            try {
                var selected = Objects.requireNonNull(task);
                return selected.isDone() ? selected.get() : TruffleSafepoint.setBlockedThreadInterruptibleFunction(null,
                    waiting -> { try { return waiting.get(); } catch (ExecutionException failure) { return Language.<RuntimeException, SulongCbits>rethrow(failure); } }, selected);
            } catch (ExecutionException failure) {
                nativeCbits.compareAndSet(task, null);
                return Language.<RuntimeException, SulongCbits>rethrow(failure.getCause() == null ? failure : failure.getCause());
            } catch (InterruptedException failure) { return Language.<RuntimeException, SulongCbits>rethrow(failure); }
        }
    }

    @Override protected ContextState createContext(Env env) { return new ContextState(env, this); }
    @Override protected void initializeContext(ContextState context) {
        if (context.phase != ContextState.Phase.PREPARING) return;
        Throwable failure = null;
        try {
            // Runtime images use the captured factory; the AST lowerer remains hosted/JVM-only.
            if (!ImageInfo.inImageRuntimeCode()) NativeExecutable.prepareForImage(this);
        }
        catch (Throwable caught) { failure = caught; }
        finally { context.finishPreparation(failure); }
    }
    @Override protected boolean patchContext(ContextState context, Env newEnv) { return context.patch(newEnv, this); }
    @Override protected Object getScope(ContextState context) { return context.requireState().managedExports.getScope(); }
    @Override protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) { return true; }
    @Override protected void exitContext(ContextState holder, ExitMode exitMode, int exitCode) {
        var context = holder.state;
        if (context == null) return;
        try { context.files.shutdownEventManagers(); }
        finally {
            context.signals.requestStop();
            context.weaks.requestStop();
            // LLVM calls remain permitted before hard exit unwinds all contexts; dispose is idempotent.
            context.iconv.dispose();
        }
    }
    @Override protected void finalizeContext(ContextState holder) {
        if (holder.state == null) return;
        var failure = finishState(holder.state, null);
        if (failure != null) Language.<RuntimeException, Void>rethrow(failure);
    }
    @Override protected void disposeContext(ContextState holder) {
        Throwable failure = null;
        try {
            if (holder.state != null) failure = disposeState(holder.state, null);
        } finally { holder.disposed(); }
        if (failure != null) Language.<RuntimeException, Void>rethrow(failure);
    }
    private static Throwable finishState(State context, Throwable failure) {
        return closeOwners(failure, context.sparks::stop, context.files::shutdownEventManagers,
            context.signals::requestStop, context.weaks::requestStop, context.threads::stopHostedThreads,
            context.weaks::close, context.signals::close, context.iconv::dispose);
    }
    private static Throwable disposeState(State context, Throwable failure) {
        failure = closeOwners(failure, context.compilerRts::close, context.graphRecovery::close,
            context.runtimeJit::close, context.runtimeTrace::close, context.compactImages::close,
            context.heapAddresses::close, context.managedExports::close, context.nativeCallbacks::close,
            context.packageCbits::close, context.foreignRoots::close, context.savedTermios::close,
            context.stm::close, context.threads::close, context.capturedAsyncRequests::close,
            context.files::dispose, context.stdio::dispose, context.rtsFileLocks::dispose,
            context.stackSnapshots::dispose, context.weaks::close, context.stableNames::close,
            context.stablePointers::close, context.nativeAddresses::close, context.nativeAllocations::close);
        for (var program : context.coreUnitPrograms) failure = closeOwners(failure, program::close);
        context.coreUnitPrograms.clear();
        return failure;
    }
    private static Throwable closeOwners(Throwable failure, Runnable... owners) {
        for (var owner : owners) {
            try { owner.run(); }
            catch (Throwable cleanup) {
                if (failure == null) failure = cleanup;
                else if (failure != cleanup) failure.addSuppressed(cleanup);
            }
        }
        return failure;
    }
    @Override protected void initializeThread(ContextState context, Thread thread) { context.noteThread(thread); }
    @Override protected void initializeMultiThreading(ContextState context) { context.markMultithreaded(); }

    @SuppressWarnings("unchecked") @Override protected CallTarget parse(ParsingRequest request) {
        if (NativeExecutable.IMAGE_BOUND) throw new UnsupportedCore("Application-bound THC image does not accept external sources");
        if (Boolean.getBoolean("thc.requireCachedCode")) throw new UnsupportedCore("Cached THC source required; parsing is disabled");
        var input = (Map<String, Object>) Json.parse(request.getSource().getCharacters().toString());
        require(!input.containsKey("prepareCode") || input.get("prepareCode") instanceof Boolean, "prepareCode must be a Boolean");
        boolean prepareCode = Boolean.TRUE.equals(input.get("prepareCode"));
        var directory = CoreModules.unitDirectory(input);
        if ("managed-exports".equals(input.get("mode"))) {
            require(!prepareCode, "Reusable code does not yet admit managed exports");
            String backend = ManagedExportPlan.backend(input);
            return new RootNode(this) {
                @Override public Object execute(VirtualFrame frame) { return currentState(this).managedExports.load(input, directory, backend); }
                @Override public String getName() { return "THC load managed exports from unit directory"; }
            }.getCallTarget();
        }
        require(!prepareCode || !"program".equals(input.get("mode")), "Reusable code requires a selected entry");
        if (!prepareCode) return unitRoot(input, directory);
        if (!(input.get("entry") instanceof String entry)) throw new IllegalStateException("Expected entry name");
        return preparedRoot(CoreModules.selectedModules(input, entry));
    }

    /** Ordinary cached sources retain their configured preparation parallelism. */
    RootCallTarget preparedRoot(Map<String,Object> input) {
        return preparedRoot(input, Integer.parseInt(System.getProperty("thc.prepareCodeJobs", "4")));
    }

    /** Reader-free application Core uses the same admission and fresh-instance factory. */
    @SuppressWarnings("unchecked") RootCallTarget preparedRoot(Map<String,Object> input, int jobs) {
        var merger = new CoreModules.Merger();
        if (!(input.get("entry") instanceof String entry)) throw new IllegalStateException("Expected entry name");
        String shutdownEntry = input.get("shutdownEntry") instanceof String value ? value : null;
        require(input.get("shutdownEntry") == null || Boolean.TRUE.equals(input.get("ioMain")) && shutdownEntry != null && !blank(shutdownEntry) && !shutdownEntry.equals(entry),
            "Executable shutdown requires a distinct IO entry");
        require(!Boolean.TRUE.equals(input.get("ioMain")) || !Boolean.TRUE.equals(input.get("diagnosticUnsupported")),
            "IO main requires strict unsupported-Core rejection");
        var layout = CoreModules.visitDecodedModules(input, module -> {
            for (var binding : (List<Map<String,Object>>) module.get("bindings")) {
                String id = (String) binding.get("id");
                require(CoreModules.backend(module, id, "ast").equals("ast"),
                    "Reusable AST code cannot honor bytecode backend policy for " + id);
            }
            merger.addDetached(module);
        });
        var linked = new LinkedHashMap<String, Object>(CoreModules.reachable(merger.finish(),
            shutdownEntry == null ? List.of(entry) : List.of(entry, shutdownEntry), Boolean.TRUE.equals(input.get("strictLink"))));
        linked.put("instrument", !Boolean.FALSE.equals(input.get("instrument")));
        linked.put("diagnosticUnsupported", Boolean.TRUE.equals(input.get("diagnosticUnsupported")));
        linked.put("sourceNotesEnabled", !Boolean.FALSE.equals(input.get("sourceNotesEnabled")));
        if (layout != null) linked.put("targetLayout", layout);
        var bindings = (List<Map<String, Object>>) linked.get("bindings");
        var byId = singleOrNull(bindings, binding -> entry.equals(binding.get("id")));
        var selected = byId == null ? single(bindings, binding -> entry.equals(binding.get("name"))) : byId;
        var expression = (List<Object>) selected.get("expr");
        var ioResult = Boolean.TRUE.equals(input.get("ioMain")) ? CoreRepresentations.ioMainResult(selected, bindings) : null;
        CoreRepresentation shutdownResult = null;
        if (shutdownEntry != null) {
            var shutdown = singleOrNull(bindings, binding -> shutdownEntry.equals(binding.get("id")));
            if (shutdown == null) throw new IllegalArgumentException("Missing exact executable shutdown entry: " + shutdownEntry);
            shutdownResult = CoreRepresentations.ioMainResult(shutdown, bindings);
        }
        List<CoreRepresentation> hostInputs = null;
        CoreRepresentation hostResult = null;
        String hostResultFault = null;
        if (ioResult == null) {
            try {
                var signature = CoreHostSignature.select(selected, bindings);
                if (signature != null) { hostInputs = signature.getInputs(); hostResult = signature.getResult(); }
                else if (((Number) selected.get("arity")).intValue() == 0) {
                    hostResult = CoreRepresentations.binder(selected).refine(CoreRepresentations.expression(expression));
                    hostInputs = List.of();
                }
                if (hostInputs != null) {
                    for (var proof : hostInputs) HostAbi.require(proof);
                    HostAbi.require(hostResult);
                }
                if (!expression.isEmpty() && "lam".equals(expression.getFirst())) {
                    for (var parameter : (List<Map<String, Object>>) expression.get(1))
                        HostAbi.require(CoreRepresentations.binder(parameter));
                    HostAbi.require(CoreRepresentations.lambdaResult(expression));
                }
            } catch (UnsupportedCore gap) {
                if (!Boolean.TRUE.equals(input.get("diagnosticUnsupported"))) throw gap;
                hostResultFault = gap.getMessage();
            }
        }
        Object backendValue = input.get("backend");
        if (backendValue == null) backendValue = Main.defaultBackend();
        require("ast".equals(backendValue) || "bytecode".equals(backendValue), "Unknown THC backend: " + backendValue);
        String backend = (String) backendValue;
        require(!input.containsKey("asyncExceptions") || input.get("asyncExceptions") instanceof Boolean, "asyncExceptions must be a Boolean");
        boolean async = Boolean.TRUE.equals(input.get("asyncExceptions"));
        boolean processSignals = bindings.stream().anyMatch(binding -> CoreSignalForeign.dispatcher.equals(binding.get("id")));
        require(backend.equals("ast") && !Boolean.TRUE.equals(input.get("diagnosticUnsupported")),
            "Reusable code requires strict AST preparation");
        linked.put("asyncExceptions", async);
        var entries = shutdownEntry == null ? List.of(entry) : List.of(entry, shutdownEntry);
        return new PreparedRoot(this, Program.prepareCode(this, linked, entries, jobs), entry,
            ((Number) selected.get("arity")).intValue(), hostInputs, hostResult,
            ioResult, shutdownEntry, shutdownResult, processSignals).getCallTarget();
    }

    /** Cached load factory: code is shared, while every execution creates fresh ordinary runtime state. */
    static final class PreparedRoot extends RootNode {
        final Program.PreparedCode code;
        private final String entry, shutdownEntry;
        private final int arity;
        private final List<CoreRepresentation> inputs;
        private final CoreRepresentation result, ioResult, shutdownResult;
        private final boolean processSignals;
        PreparedRoot(Language language, Program.PreparedCode code, String entry, int arity,
                List<CoreRepresentation> inputs, CoreRepresentation result, CoreRepresentation ioResult,
                String shutdownEntry, CoreRepresentation shutdownResult, boolean processSignals) {
            super(language);
            this.code = code; this.entry = entry; this.arity = arity; this.inputs = inputs; this.result = result;
            this.ioResult = ioResult; this.shutdownEntry = shutdownEntry; this.shutdownResult = shutdownResult;
            this.processSignals = processSignals;
        }
        @Override public Object execute(VirtualFrame frame) { return instantiate(); }
        @TruffleBoundary private EntryValue instantiate() {
            if (Boolean.getBoolean("thc.requireCompiledCode")) {
                if (!(getCallTarget() instanceof com.oracle.truffle.runtime.OptimizedCallTarget target) || !target.isValidLastTier())
                    throw new IllegalStateException("Cached compiled target required: " + getName());
                code.requireInstalledCode();
            }
            var language = getLanguage(Language.class);
            // The saved factory owns declarations, never the preparation Context's
            // native functions, CAFs, bridge projectors or registration lifetimes.
            var owner = currentState(this);
            for (var link : code.getForeignLinks()) owner.cbits().declare(link);
            for (var link : code.getPackageScalarLinks()) owner.packageCbits.declare(link);
            var program = code.newInstanceForNativeStartup(language);
            try {
                owner.foreignRoots.register(program, language, code.getManagedRegistrations(), code.getManagedExports(),
                    () -> {
                        for (var link : code.getForeignLinks()) owner.cbits().link(link);
                        for (var link : code.getPackageScalarLinks()) owner.packageCbits.link(link);
                    });
                return new EntryValue(program, entry, arity, null, ioResult, language,
                    shutdownEntry, shutdownResult, processSignals, inputs, result, code);
            } catch (Throwable failure) { owner.foreignRoots.release(program); throw failure; }
        }
        @Override protected ExecutionSignature prepareForAOT() {
            return ExecutionSignature.create(EntryValue.class, new Class<?>[0]);
        }
        @Override public String getName() { return "THC prepared load " + entry; }
    }

    private CallTarget unitRoot(Map<String, Object> input, CoreUnitDirectory directory) {
        boolean explicitProgram = "program".equals(input.get("mode"));
        String entry = input.get("entry") instanceof String value ? value : null;
        require(explicitProgram || entry != null, "Expected entry name");
        String shutdown = input.get("shutdownEntry") instanceof String value ? value : null;
        boolean ioMain = Boolean.TRUE.equals(input.get("ioMain"));
        require(!explicitProgram || entry == null && !ioMain && shutdown == null, "Program load does not select an entry");
        var view = explicitProgram ? null : new ProgramValue.View(entry, ioMain, shutdown);
        Object backendValue = input.get("backend");
        if (backendValue == null) backendValue = Main.defaultBackend();
        require("ast".equals(backendValue) || "bytecode".equals(backendValue), "Unknown THC backend: " + backendValue);
        String backend = (String) backendValue;
        require(input.get("asyncExceptions") == null || input.get("asyncExceptions") instanceof Boolean, "asyncExceptions must be a Boolean");
        boolean async = Boolean.TRUE.equals(input.get("asyncExceptions"));
        boolean diagnostic = Boolean.TRUE.equals(input.get("diagnosticUnsupported"));
        require(!ioMain || !diagnostic, "IO main requires strict unsupported-Core rejection");
        return new RootNode(this) {
            @Override public Object execute(VirtualFrame frame) { return instantiate(); }
            @TruffleBoundary private Object instantiate() {
                var owner = currentState(this);
                var program = new CoreUnitProgram(Language.this, directory, input, entry, backend, async, owner);
                try {
                    // Legacy single-entry loads validate before native startup.
                    // Explicit programs defer only entry selection, not registration.
                    var plan = view == null ? null : ProgramValue.EntryPlan.select(program, view, diagnostic);
                    var registrations = program.registerStartup();
                    var exports = new ArrayList<ManagedExportSignature>();
                    for (var registration : registrations) exports.addAll(registration.getExports());
                    owner.foreignRoots.register(program, Language.this, registrations, ManagedExportPlan.checked(exports, program::signatureBindings), program::linkStartup);
                    Object value = plan == null ? new ProgramValue(program, Language.this, owner, diagnostic)
                        : plan.create(program, Language.this);
                    owner.coreUnitPrograms.add(program);
                    return value;
                } catch (Throwable failure) { owner.foreignRoots.release(program); program.close(); throw failure; }
            }
            @Override public String getName() { return "THC load " + (explicitProgram ? "program" : entry) + " from unit directory"; }
        }.getCallTarget();
    }
    private static <T> T singleOrNull(List<T> values, Predicate<T> selected) {
        T found = null; boolean present = false;
        for (T value : values) if (selected.test(value)) { if (present) return null; found = value; present = true; }
        return found;
    }
    private static <T> T single(List<T> values, Predicate<T> selected) {
        T found = null; boolean present = false;
        for (T value : values) if (selected.test(value)) {
            if (present) throw new IllegalArgumentException("Collection contains more than one matching element.");
            found = value; present = true;
        }
        if (!present) throw new NoSuchElementException("Collection contains no element matching the predicate.");
        return found;
    }
    private static boolean blank(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) return false;
        }
        return true;
    }
    private static void require(boolean accepted, String message) { if (!accepted) throw new IllegalArgumentException(message); }
    @SuppressWarnings("unchecked") private static <T extends Throwable, R> R rethrow(Throwable failure) throws T { throw (T) failure; }
}
