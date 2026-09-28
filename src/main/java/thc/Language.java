// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import thc.runtime.*;

@TruffleLanguage.Registration(id = "thc", name = "Turbo Haskell Compiler", version = "0.1-experiment",
    characterMimeTypes = "application/x-thc-core", defaultMimeType = "application/x-thc-core",
    dependentLanguages = "llvm", contextPolicy = TruffleLanguage.ContextPolicy.EXCLUSIVE)
public final class Language extends TruffleLanguage<Language.State> {
    // Layout interning belongs to a context even when the language instance is shared.
    public HandoffLayouts getHandoffLayouts() { return currentState(null).handoffLayouts; }
    private final ContextThreadLocal<HandoffState> handoffState = locals.createContextThreadLocal((context, thread) -> new HandoffState());
    public ContextThreadLocal<HandoffState> getHandoffState() { return handoffState; }
    private final ContextThreadLocal<GuestThreads.PollState> threadPollState = locals.createContextThreadLocal((context, thread) -> context.threads.pollState$org_intelligence_thc(thread));
    private final ContextThreadLocal<CarrierLocal.Cell<MaskingState>> threadMaskingState = locals.createContextThreadLocal((context, thread) -> context.maskingState.cell$org_intelligence_thc(thread));
    private final ContextThreadLocal<CarrierLocal.Cell<StackAnnotationState>> threadAnnotations = locals.createContextThreadLocal((context, thread) -> context.stackAnnotations.cell$org_intelligence_thc(thread));
    private static final ContextReference<State> CONTEXTS = ContextReference.create(Language.class);
    public static State currentState() { return currentState(null); }
    public static State currentState(Node node) { return CONTEXTS.get(node); }

    public static final class State {
        private final Env env;
        private final AtomicReference<GuestShutdown> shutdown;
        private final ManagedExportRegistry managedExports;
        private final ManagedForeignRoots foreignRoots;
        private final HandoffLayouts handoffLayouts;
        private final JavaScriptImports javaScriptImports;
        private final PackageScalarLibraries packageCbits;
        private final CarrierLocal<MaskingState> maskingState;
        private final CarrierLocal<StackAnnotationState> stackAnnotations;
        private final GuestThreads threads;
        private final ContextThreadLocal<GuestThreads.PollState> threadPollState;
        private final ContextThreadLocal<CarrierLocal.Cell<MaskingState>> threadMaskingState;
        private final ContextThreadLocal<CarrierLocal.Cell<StackAnnotationState>> threadAnnotations;
        private final RuntimeTraceServices runtimeTrace;
        private final RuntimeJitServices runtimeJit;
        public final ManagedSTM stm;
        private final ManagedFiles files;
        private final RtsFileLocks rtsFileLocks;
        // Installed only by explicit fixed-filesystem factories; ordinary builders retain embedding IO.
        private NativeFileProvider nativeFiles;
        private WindowsDirectoryStreams windowsDirectories;
        private final WindowsCodePages windowsCodePages;
        private final ManagedStdio stdio;
        private final ManagedSignalMask signalMask;
        private final ManagedSignals signals;
        private final SavedTermios savedTermios;
        private final ManagedIconv iconv;
        private final ManagedStrerror strerror;
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
        private final AtomicReference<FutureTask<SulongCbits>> nativeCbits;
        private final AtomicReference<FutureTask<LimbProvider>> nativeLimbs;
        public State(Env env, Language language) {
            this.env = env;
            shutdown = new AtomicReference<>();
            managedExports = new ManagedExportRegistry(this, language);
            foreignRoots = new ManagedForeignRoots(this);
            handoffLayouts = new HandoffLayouts(language);
            javaScriptImports = new JavaScriptImports();
            packageCbits = new PackageScalarLibraries(env);
            maskingState = new CarrierLocal<>(MaskingState.UNMASKED);
            stackAnnotations = new CarrierLocal<>(StackAnnotationState.EMPTY);
            threads = new GuestThreads(env, maskingState);
            threadPollState = language.threadPollState;
            threadMaskingState = language.threadMaskingState;
            threadAnnotations = language.threadAnnotations;
            runtimeTrace = new RuntimeTraceServices(env.err());
            runtimeJit = new RuntimeJitServices(language);
            stm = new ManagedSTM();
            files = new ManagedFiles(env, threads);
            rtsFileLocks = new RtsFileLocks();
            nativeFiles = null;
            windowsDirectories = null;
            windowsCodePages = new WindowsCodePages(this);
            stdio = new ManagedStdio(files);
            signalMask = new ManagedSignalMask(this);
            signals = new ManagedSignals(this, language);
            savedTermios = new SavedTermios(this);
            iconv = new ManagedIconv(this::cbits, stdio, threads);
            strerror = new ManagedStrerror(this::cbits, threads);
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
            weaks = new ManagedWeaks();
            singleThreadedAssumption = Truffle.getRuntime().createAssumption("THC single-threaded context");
            nativeCbits = new AtomicReference<>();
            nativeLimbs = new AtomicReference<>();
        }
        public Env getEnv() { return env; }
        public AtomicReference<GuestShutdown> getShutdown() { return shutdown; }
        public ManagedExportRegistry getManagedExports() { return managedExports; }
        public ManagedForeignRoots getForeignRoots() { return foreignRoots; }
        public HandoffLayouts getHandoffLayouts() { return handoffLayouts; }
        public JavaScriptImports getJavaScriptImports() { return javaScriptImports; }
        public PackageScalarLibraries getPackageCbits() { return packageCbits; }
        public CarrierLocal<MaskingState> getMaskingState() { return maskingState; }
        public CarrierLocal<StackAnnotationState> getStackAnnotations() { return stackAnnotations; }
        public GuestThreads getThreads() { return threads; }
        public ContextThreadLocal<GuestThreads.PollState> getThreadPollState() { return threadPollState; }
        public ContextThreadLocal<CarrierLocal.Cell<MaskingState>> getThreadMaskingState() { return threadMaskingState; }
        public ContextThreadLocal<CarrierLocal.Cell<StackAnnotationState>> getThreadAnnotations() { return threadAnnotations; }
        public RuntimeTraceServices getRuntimeTrace() { return runtimeTrace; }
        public RuntimeJitServices getRuntimeJit() { return runtimeJit; }
        public ManagedFiles getFiles() { return files; }
        public RtsFileLocks getRtsFileLocks() { return rtsFileLocks; }
        public NativeFileProvider getNativeFiles() { return nativeFiles; }
        public void setNativeFiles(NativeFileProvider value) { nativeFiles = value; }
        public WindowsDirectoryStreams getWindowsDirectories() { return windowsDirectories; }
        public void setWindowsDirectories(WindowsDirectoryStreams value) { windowsDirectories = value; }
        public WindowsCodePages getWindowsCodePages() { return windowsCodePages; }
        public ManagedStdio getStdio() { return stdio; }
        public ManagedSignalMask getSignalMask() { return signalMask; }
        public ManagedSignals getSignals() { return signals; }
        public SavedTermios getSavedTermios() { return savedTermios; }
        public ManagedIconv getIconv() { return iconv; }
        public ManagedStrerror getStrerror() { return strerror; }
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

        @TruffleBoundary public LimbProvider limbs() {
            if (!env.isNativeAccessAllowed()) throw new RuntimeFault("Native GMP arithmetic requires native access");
            var task = nativeLimbs.get();
            if (task == null) {
                var candidate = new FutureTask<LimbProvider>(() -> new SulongLimbProvider(env));
                if (nativeLimbs.compareAndSet(null, candidate)) {
                    task = candidate;
                    candidate.run(); // Parse LLVM without holding a monitor across guest code.
                } else task = nativeLimbs.get();
            }
            try {
                var selected = Objects.requireNonNull(task);
                return selected.isDone() ? selected.get() : TruffleSafepoint.setBlockedThreadInterruptibleFunction(null,
                    waiting -> { try { return waiting.get(); } catch (ExecutionException failure) { return Language.<RuntimeException, LimbProvider>rethrow(failure); } }, selected);
            } catch (ExecutionException failure) {
                nativeLimbs.compareAndSet(task, null);
                return Language.<RuntimeException, LimbProvider>rethrow(failure.getCause() == null ? failure : failure.getCause());
            } catch (InterruptedException failure) { return Language.<RuntimeException, LimbProvider>rethrow(failure); }
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

    @Override protected State createContext(Env env) { return new State(env, this); }
    @Override protected Object getScope(State context) { return context.managedExports.getScope(); }
    @Override protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) { return true; }
    @Override protected void exitContext(State context, ExitMode exitMode, int exitCode) {
        try { context.files.shutdownEventManagers$org_intelligence_thc(); }
        finally {
            context.signals.requestStop();
            // LLVM calls remain permitted before hard exit unwinds all contexts; dispose is idempotent.
            context.iconv.dispose();
        }
    }
    @Override protected void finalizeContext(State context) {
        try { context.files.shutdownEventManagers$org_intelligence_thc(); }
        finally { try { context.signals.close(); } finally { context.iconv.dispose(); } }
    }
    @Override protected void disposeContext(State context) {
        context.compilerRts.close();
        try { context.runtimeJit.close(); } finally { context.runtimeTrace.close(); }
        context.compactImages.close();
        context.heapAddresses.close();
        context.managedExports.close();
        context.packageCbits.close();
        context.foreignRoots.close();
        context.savedTermios.close();
        try {
            try { try { context.stm.close(); } finally { context.threads.close(); } }
            finally {
                try { context.capturedAsyncRequests.close(); }
                finally {
                    try { context.files.dispose(); }
                    finally {
                        try { context.stdio.dispose(); }
                        finally { try { context.rtsFileLocks.dispose(); } finally { context.stackSnapshots.dispose(); } }
                    }
                }
            }
        } finally {
            try { try { context.weaks.close(); } finally { context.stableNames.close(); } }
            finally {
                try { context.stablePointers.close(); }
                finally {
                    try { context.nativeAddresses.close(); }
                    finally {
                        try { context.nativeAllocations.close(); }
                        finally {
                            try { context.coreUnitPrograms.forEach(CoreUnitProgram::close); }
                            finally { context.coreUnitPrograms.clear(); }
                        }
                    }
                }
            }
        }
    }
    @Override protected void initializeThread(State context, Thread thread) { context.noteThread(thread); }
    @Override protected void initializeMultiThreading(State context) { context.markMultithreaded(); }

    @SuppressWarnings("unchecked") @Override protected CallTarget parse(ParsingRequest request) {
        var input = (Map<String, Object>) Json.parse(request.getSource().getCharacters().toString());
        if ("managed-exports".equals(input.get("mode"))) {
            String backend = ManagedExportPlan.backend(input);
            var directory = CoreModules.unitDirectory(input);
            if (directory != null) return new RootNode(this) {
                @Override public Object execute(VirtualFrame frame) { return currentState(this).managedExports.load(input, directory, backend); }
                @Override public String getName() { return "THC load managed exports from unit directory"; }
            }.getCallTarget();
            var plan = ManagedExportPlan.read(input);
            return new RootNode(this) {
                @Override public Object execute(VirtualFrame frame) { return currentState(this).managedExports.load(plan); }
                @Override public String getName() { return "THC load managed exports"; }
            }.getCallTarget();
        }
        var directory = CoreModules.unitDirectory(input);
        if (directory != null) return unitRoot(input, directory);
        var merger = new CoreModules.Merger();
        if (!(input.get("entry") instanceof String entry)) throw new IllegalStateException("Expected entry name");
        String shutdownEntry = input.get("shutdownEntry") instanceof String value ? value : null;
        require(input.get("shutdownEntry") == null || Boolean.TRUE.equals(input.get("ioMain")) && shutdownEntry != null && !blank(shutdownEntry) && !shutdownEntry.equals(entry),
            "Executable shutdown requires a distinct IO entry");
        require(!Boolean.TRUE.equals(input.get("ioMain")) || !Boolean.TRUE.equals(input.get("diagnosticUnsupported")),
            "IO main requires strict unsupported-Core rejection");
        var loadingStatistics = new CoreJsonLoadingStatistics();
        var layout = CoreModules.visitRequestModules(input, loadingStatistics::include, merger::add);
        var linked = new LinkedHashMap<String, Object>(CoreModules.reachable(merger.finish(),
            shutdownEntry == null ? List.of(entry) : List.of(entry, shutdownEntry), Boolean.TRUE.equals(input.get("strictLink"))));
        linked.put("instrument", !Boolean.FALSE.equals(input.get("instrument")));
        linked.put("diagnosticUnsupported", Boolean.TRUE.equals(input.get("diagnosticUnsupported")));
        linked.put("sourceNotesEnabled", !Boolean.FALSE.equals(input.get("sourceNotesEnabled")));
        if (layout != null) linked.put("targetLayout", layout);
        if (!loadingStatistics.isEmpty()) linked.put("coreLoadingStatistics", loadingStatistics);
        var bindings = (List<Map<String, Object>>) linked.get("bindings");
        var byId = singleOrNull(bindings, binding -> entry.equals(binding.get("id")));
        var selected = byId == null ? single(bindings, binding -> entry.equals(binding.get("name"))) : byId;
        var expression = (List<Object>) selected.get("expr");
        var ioResult = Boolean.TRUE.equals(input.get("ioMain")) ? CoreRepresentations.ioUnitMainResult(selected, bindings) : null;
        CoreRepresentation shutdownResult = null;
        if (shutdownEntry != null) {
            var shutdown = singleOrNull(bindings, binding -> shutdownEntry.equals(binding.get("id")));
            if (shutdown == null) throw new IllegalArgumentException("Missing exact executable shutdown entry: " + shutdownEntry);
            shutdownResult = CoreRepresentations.ioUnitMainResult(shutdown, bindings);
        }
        List<CoreRepresentation> hostInputs = null;
        CoreRepresentation hostResult = null;
        String hostResultFault = null;
        if (ioResult == null) {
            try {
                var signature = CoreRepresentations.knownFunctionSignature(expression, bindings);
                if (signature != null) { hostInputs = signature.getInputs(); hostResult = signature.getResult(); }
                else if (((Number) selected.get("arity")).intValue() == 0) {
                    hostResult = CoreRepresentations.binder(selected).refine(CoreRepresentations.expression(expression));
                    hostInputs = List.of();
                }
                if (hostInputs != null) {
                    for (var proof : hostInputs) CoreRepresentations.requireScalar(proof, "host argument");
                    CoreRepresentations.requireScalar(hostResult, "host result");
                }
                if (!expression.isEmpty() && "lam".equals(expression.getFirst())) {
                    for (var parameter : (List<Map<String, Object>>) expression.get(1))
                        CoreRepresentations.requireScalar(CoreRepresentations.binder(parameter), "host argument");
                    CoreRepresentations.requireScalar(CoreRepresentations.lambdaResult(expression), "host result");
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
        boolean async = input.get("asyncExceptions") instanceof Boolean value ? value : backend.equals("bytecode");
        var registrations = (List<ManagedExportAdmission>) linked.get("managedRegistrations");
        var acceptedInputs = hostInputs;
        var acceptedResult = hostResult;
        var resultFault = hostResultFault;
        var shutdownProof = shutdownResult;
        return new RootNode(this) {
            @Override public Object execute(VirtualFrame frame) {
                // Parsed roots may be Engine-shared; programs, CAFs and registrations are context-owned.
                var owner = currentState(this);
                for (var link : (List<ForeignBitcode>) linked.get("foreignLinks")) owner.cbits().link(link);
                for (var link : (List<PackageScalarLink>) linked.get("packageScalarLinks")) owner.packageCbits.link(link);
                ExecutableProgram program = backend.equals("ast") ? new Program(Language.this, linked, async, false)
                    : new BytecodeProgram(Language.this, linked, async);
                int argumentCount = ((Number) selected.get("arity")).intValue();
                boolean processSignals = false;
                for (var binding : bindings) if (CoreSignalForeign.dispatcher.equals(binding.get("id"))) { processSignals = true; break; }
                var value = new EntryValue(program, entry, argumentCount, resultFault,
                    ioResult, Language.this, shutdownEntry, shutdownProof,
                    processSignals, acceptedInputs, acceptedResult);
                owner.foreignRoots.retain(program, registrations);
                return value;
            }
            @Override public String getName() { return "THC load " + entry; }
        }.getCallTarget();
    }

    @SuppressWarnings("unchecked") private CallTarget unitRoot(Map<String, Object> input, CoreUnitDirectory directory) {
        if (!(input.get("entry") instanceof String entry)) throw new IllegalStateException("Expected entry name");
        String shutdown = input.get("shutdownEntry") instanceof String value ? value : null;
        require(shutdown == null || Boolean.TRUE.equals(input.get("ioMain")) && !blank(shutdown) && !shutdown.equals(entry),
            "Executable shutdown requires a distinct IO entry");
        Object backendValue = input.get("backend");
        if (backendValue == null) backendValue = Main.defaultBackend();
        require("ast".equals(backendValue) || "bytecode".equals(backendValue), "Unknown THC backend: " + backendValue);
        String backend = (String) backendValue;
        require(input.get("asyncExceptions") == null || input.get("asyncExceptions") instanceof Boolean, "asyncExceptions must be a Boolean");
        boolean async = input.get("asyncExceptions") instanceof Boolean value ? value : backend.equals("bytecode");
        require(!Boolean.TRUE.equals(input.get("ioMain")) || !Boolean.TRUE.equals(input.get("diagnosticUnsupported")), "IO main requires strict unsupported-Core rejection");
        return new RootNode(this) {
            @Override public Object execute(VirtualFrame frame) {
                var owner = currentState(this);
                var program = new CoreUnitProgram(Language.this, directory, input, entry, backend, async, owner);
                try {
                    var bindings = program.signatureBindings(entry);
                    var selected = single(bindings, binding -> entry.equals(binding.get("id")));
                    var expression = (List<Object>) selected.get("expr");
                    var io = Boolean.TRUE.equals(input.get("ioMain")) ? CoreRepresentations.ioUnitMainResult(selected, bindings) : null;
                    CoreRepresentation shutdownResult = null;
                    if (shutdown != null) {
                        var definitions = program.signatureBindings(shutdown);
                        shutdownResult = CoreRepresentations.ioUnitMainResult(single(definitions, binding -> shutdown.equals(binding.get("id"))), definitions);
                    }
                    List<CoreRepresentation> hostInputs = null;
                    CoreRepresentation hostResult = null;
                    if (io == null) {
                        var signature = CoreRepresentations.knownFunctionSignature(expression, bindings);
                        if (signature != null) { hostInputs = signature.getInputs(); hostResult = signature.getResult(); }
                        else if (((Number) selected.get("arity")).intValue() == 0) {
                            hostResult = CoreRepresentations.binder(selected).refine(CoreRepresentations.expression(expression));
                            hostInputs = List.of();
                        }
                    }
                    if (hostInputs != null) {
                        for (var proof : hostInputs) CoreRepresentations.requireScalar(proof, "host argument");
                        CoreRepresentations.requireScalar(hostResult, "host result");
                    }
                    var registrations = program.registerStartup();
                    var value = new EntryValue(program, entry, ((Number) selected.get("arity")).intValue(), null,
                        io, Language.this, shutdown, shutdownResult, async && program.contains(CoreSignalForeign.dispatcher), hostInputs, hostResult);
                    owner.coreUnitPrograms.add(program);
                    owner.foreignRoots.retain(program, registrations);
                    return value;
                } catch (Throwable failure) { program.close(); throw failure; }
            }
            @Override public String getName() { return "THC load " + entry + " from unit directory"; }
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
