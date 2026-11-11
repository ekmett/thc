// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.interop.*;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedTruffleRuntime;
import com.oracle.truffle.runtime.OptimizedTruffleRuntimeListener;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import thc.runtime.*;

/** Signature-driven host entry or one-shot executable IO lifecycle. */
@ExportLibrary(InteropLibrary.class)
public final class EntryValue implements TruffleObject {
    private final Language.State owner;
    private final ExecutableProgram program;
    private final String entry, hostResultFault;
    private final int argumentCount;
    private final boolean processSignals;
    private final List<CoreRepresentation> hostInputs;
    private final CoreRepresentation hostResult;
    private final RootCallTarget guestTarget, ioTarget, shutdownTarget;
    private final Object guestEntry, shutdownValue;
    private final Program.PreparedCode requiredCode;
    private final AtomicBoolean lifecycleStarted;
    private record Compilation(RootCallTarget original, List<CallTarget> targets) {}
    private volatile Compilation installedCompilation;
    private record CheckpointTarget(RootCallTarget target, boolean aotPrepared, String status,
            String failure, long codeAddress, int installations) {}
    private volatile List<CheckpointTarget> checkpointCompilation;
    private volatile boolean checkpointInvocationStarted;

    private void requireCheckpointOwner() {
        if (Language.currentState() != owner) throw new IllegalArgumentException("Checkpoint entry belongs to another context");
    }

    private List<RootCallTarget> checkpointTargets() {
        var targets = new ArrayList<RootCallTarget>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>());
        for (var target : program.compilationTargets()) if (seen.add(target)) targets.add(target);
        for (var target : Arrays.asList(guestTarget, ioTarget, shutdownTarget,
                valueTarget(guestEntry), valueTarget(shutdownValue)))
            if (target != null && seen.add(target)) targets.add(target);
        for (int index = 0; index < targets.size(); index++)
            for (var call : NodeUtil.findAllNodeInstances(targets.get(index).getRootNode(), DirectCallNode.class))
                if (call.getCurrentCallTarget() instanceof RootCallTarget target && seen.add(target)) targets.add(target);
        return List.copyOf(targets);
    }

    private static RootCallTarget valueTarget(Object value) {
        if (value instanceof Closure closure) return closure.target;
        return value instanceof Thunk thunk ? thunk.getTarget() : null;
    }

    /** Attempt every retained executable target once without executing guest code.
     * Failed or rejected targets are reported as partial coverage, never as installed code. */
    @TruffleBoundary public synchronized Map<String,Object> prepareCheckpointCompilation() {
        requireCheckpointOwner();
        if (checkpointInvocationStarted || lifecycleStarted != null && lifecycleStarted.get())
            throw new IllegalStateException("Checkpoint compilation requires an unstarted guest entry");
        if (checkpointCompilation != null) return checkpointCompilationObservation(false);
        var failures = new java.util.concurrent.ConcurrentHashMap<OptimizedCallTarget,String>();
        OptimizedTruffleRuntime runtime = Truffle.getRuntime() instanceof OptimizedTruffleRuntime found ? found : null;
        var listener = new OptimizedTruffleRuntimeListener() {
            @Override public void onCompilationFailed(OptimizedCallTarget target, String reason, boolean bailout,
                    boolean permanent, int tier, java.util.function.Supplier<String> stack) {
                failures.put(target, Objects.toString(reason, "Compilation failed"));
            }
        };
        if (runtime != null) runtime.addListener(listener);
        var observations = new ArrayList<CheckpointTarget>();
        try {
            var inventory = checkpointTargets();
            var aotPrepared = new IdentityHashMap<RootCallTarget, Boolean>();
            var preparationFailures = new IdentityHashMap<RootCallTarget, String>();
            // Prepare all profiles first: preparing a later callee must not invalidate
            // machine code already installed in an earlier caller.
            for (var value : inventory) {
                boolean aot = false;
                if (value instanceof OptimizedCallTarget target && !target.wasExecuted()) {
                    try { aot = target.prepareForAOT(); }
                    catch (RuntimeException unavailable) { preparationFailures.put(target, "AOT preparation: " + unavailable); }
                }
                aotPrepared.put(value, aot);
            }
            for (var value : inventory) {
                String failure = "", status = "unsupported-runtime";
                long address = 0; int installations = 0;
                if (value instanceof OptimizedCallTarget target) {
                    try {
                        target.compile(true);
                        // This also waits when the ordinary engine permits background compilation.
                        target.waitForCompilation();
                        address = target.getCodeAddress();
                        installations = target.getSuccessfulCompilationCount();
                        failure = failures.getOrDefault(target, preparationFailures.getOrDefault(target, ""));
                        status = target.isValidLastTier() && address != 0 ? "installed" :
                            failures.containsKey(target) ? checkpointFailureStatus(failure) : "rejected";
                    } catch (RuntimeException rejected) {
                        failure = failures.getOrDefault(target, rejected.toString());
                        status = failures.containsKey(target) ? checkpointFailureStatus(failure) : "rejected";
                    }
                }
                observations.add(new CheckpointTarget(value, aotPrepared.get(value), status, failure, address, installations));
            }
            // Installation during an earlier attempt is insufficient if a later
            // compilation invalidated it. Keep that outcome explicit, with no retry.
            for (int index = 0; index < observations.size(); index++) {
                var observation = observations.get(index);
                if (observation.status().equals("installed")) {
                    var target = (OptimizedCallTarget) observation.target();
                    boolean valid = target.isValidLastTier() && target.getCodeAddress() != 0;
                    observations.set(index, new CheckpointTarget(target, observation.aotPrepared(),
                        valid ? "installed" : "invalidated", valid ? observation.failure() :
                            "Installation invalidated during checkpoint compilation", target.getCodeAddress(),
                        target.getSuccessfulCompilationCount()));
                }
            }
        } finally { if (runtime != null) runtime.removeListener(listener); }
        checkpointCompilation = List.copyOf(observations);
        return checkpointCompilationObservation(false);
    }

    private static String checkpointFailureStatus(String reason) {
        return reason.startsWith("jdk.graal.compiler.core.common.CancellationBailoutException:") ? "cancelled" : "failed";
    }

    /** Verify retained installations after the checkpoint; this never compiles or repairs code. */
    @TruffleBoundary public synchronized Map<String,Object> verifyCheckpointCompilation() {
        requireCheckpointOwner();
        if (checkpointCompilation == null) throw new IllegalStateException("Checkpoint code was not compiled");
        return checkpointCompilationObservation(true);
    }

    private String checkpointRole(RootCallTarget target) {
        if (target == ioTarget) return "ioBridge";
        if (target == shutdownTarget) return "shutdownBridge";
        if (target == valueTarget(guestEntry)) return "guestEntry";
        if (target == valueTarget(shutdownValue)) return "shutdownEntry";
        return target == guestTarget ? "hostBridge" : "program";
    }

    private Map<String,Object> checkpointCompilationObservation(boolean requireSurvival) {
        var retained = checkpointCompilation;
        var current = checkpointTargets();
        var currentIdentities = Collections.newSetFromMap(new IdentityHashMap<RootCallTarget, Boolean>());
        currentIdentities.addAll(current);
        boolean same = retained.size() == current.size(), survived = true;
        int installed = 0, failed = 0, rejected = 0, invalidated = 0, cancelled = 0, unsupported = 0;
        var observations = new ArrayList<Map<String,Object>>();
        for (int index = 0; index < retained.size(); index++) {
            var observation = retained.get(index); var target = observation.target();
            same &= currentIdentities.contains(target);
            boolean valid = target instanceof OptimizedCallTarget optimized && optimized.isValidLastTier();
            long address = target instanceof OptimizedCallTarget optimized ? optimized.getCodeAddress() : 0;
            int installations = target instanceof OptimizedCallTarget optimized ? optimized.getSuccessfulCompilationCount() : 0;
            switch (observation.status()) {
                case "installed" -> {
                    installed++;
                    survived &= valid && address == observation.codeAddress() && installations == observation.installations();
                }
                case "failed" -> failed++;
                case "rejected" -> rejected++;
                case "invalidated" -> invalidated++;
                case "cancelled" -> cancelled++;
                default -> unsupported++;
            }
            var detail = new LinkedHashMap<String,Object>();
            detail.put("index", index); detail.put("name", target.getRootNode().getName());
            detail.put("kind", target.getRootNode().getClass().getName()); detail.put("role", checkpointRole(target));
            detail.put("aotPrepared", observation.aotPrepared()); detail.put("status", observation.status());
            detail.put("failure", observation.failure()); detail.put("codeAddressBefore", observation.codeAddress());
            detail.put("codeAddressNow", address); detail.put("validLastTier", valid);
            detail.put("installationsBefore", observation.installations()); detail.put("installationsNow", installations);
            detail.put("wasExecuted", target instanceof OptimizedCallTarget optimized && optimized.wasExecuted());
            observations.add(Collections.unmodifiableMap(detail));
        }
        var report = new LinkedHashMap<String,Object>();
        report.put("targetCount", retained.size()); report.put("installed", installed); report.put("failed", failed);
        report.put("rejected", rejected); report.put("unsupportedRuntime", unsupported); report.put("sameTargets", same);
        report.put("invalidated", invalidated);
        report.put("cancelled", cancelled);
        report.put("installedCodeSurvived", survived); report.put("targets", List.copyOf(observations));
        if (requireSurvival && (!same || !survived))
            throw new IllegalStateException("Checkpoint installations changed before guest entry: " + Json.stringify(report));
        return Collections.unmodifiableMap(report);
    }

    public EntryValue(ExecutableProgram program, String entry, int argumentCount) {
        this(program, entry, argumentCount, null, null, null, null, null, false, null, null);
    }
    public EntryValue(ExecutableProgram program, String entry, int argumentCount, String hostResultFault,
            CoreRepresentation ioResult, Language language, String shutdownEntry,
            CoreRepresentation shutdownResult, boolean processSignals,
            List<CoreRepresentation> hostInputs, CoreRepresentation hostResult) {
        this(program, entry, argumentCount, hostResultFault, ioResult, language, shutdownEntry,
            shutdownResult, processSignals, hostInputs, hostResult, null);
    }
    EntryValue(ExecutableProgram program, String entry, int argumentCount, String hostResultFault,
            CoreRepresentation ioResult, Language language, String shutdownEntry,
            CoreRepresentation shutdownResult, boolean processSignals,
            List<CoreRepresentation> hostInputs, CoreRepresentation hostResult, Program.PreparedCode preparedCode) {
        // Load factories create entries per context, including when their prepared code is shared.
        owner = Language.currentState();
        requiredCode = Boolean.getBoolean("thc.requireCachedCode") && Boolean.getBoolean("thc.requireCompiledCode")
            ? Objects.requireNonNull(preparedCode, "Cached compiled entry requires its prepared code") : null;
        // Optimizer arity may be zero for a callable OPAQUE binding. Host
        // transport follows the checked physical signature, not that hint.
        if (hostInputs != null) argumentCount = hostInputs.size();
        this.program = program; this.entry = entry; this.argumentCount = argumentCount;
        this.hostResultFault = hostResultFault; this.processSignals = processSignals;
        // An explicitly loaded program may not have admitted any entry yet.
        // Reading its cell prepares the lazy value without evaluating a CAF.
        guestEntry = program.entryValue(entry);
        var untypedTarget = program.hostEntryTarget(argumentCount);
        boolean admittedSignature = hostInputs != null;
        if (hostInputs == null && guestEntry instanceof Closure closure && closure.target.getRootNode() instanceof GuestRoot root) {
            var complete = root.getInputProofs();
            if (complete != null && complete.size() - closure.suppliedCount == argumentCount) {
                hostInputs = complete.subList(closure.suppliedCount, complete.size());
                hostResult = root.getTupleResult() == null ? root.getScalarResultProof() : root.getTupleResult().getProof();
            }
        }
        this.hostInputs = hostInputs; this.hostResult = hostResult;
        boolean typed = hostResult != null && hostResult.isTypedTransport();
        if (hostInputs != null) for (var proof : hostInputs) typed |= proof.isTypedTransport();
        guestTarget = hostInputs != null && hostResult != null && (argumentCount != 0 || guestEntry instanceof Closure) && (admittedSignature || typed)
            ? ((EntryRoot) untypedTarget.getRootNode()).withSignature(language != null ? language : untypedTarget.getRootNode().getLanguage(Language.class), hostInputs, hostResult, guestEntry instanceof Closure closure && closure.target.getRootNode() instanceof GuestRoot root
                ? root.getTupleResult() : null).getCallTarget() : untypedTarget;
        if (ioResult != null && language == null) throw new IllegalStateException("Missing IO language");
        ioTarget = ioResult == null ? null : new IoMainRoot(language, ioResult).getCallTarget();
        shutdownValue = shutdownEntry == null ? null : program.entryValue(shutdownEntry);
        if (shutdownResult != null && language == null) throw new IllegalStateException("Missing IO language");
        shutdownTarget = shutdownResult == null ? null : new IoMainRoot(language, shutdownResult).getCallTarget();
        lifecycleStarted = shutdownTarget == null ? null : new AtomicBoolean();
        if ((shutdownValue == null) != (shutdownTarget == null)) throw new IllegalArgumentException("Failed requirement.");
    }

    private void requireOwner(HostDispatch dispatch) {
        if (Language.currentState(dispatch) != owner) throw new IllegalArgumentException("Host entry belongs to another context");
    }
    @ExportMessage public boolean isExecutable() { return ioTarget == null; }
    @ExportMessage public Object execute(Object[] arguments,
            @Cached(value = "create()", uncached = "create()", neverDefault = true) HostDispatch dispatch) {
        requireOwner(dispatch);
        if (ioTarget != null) throw new RuntimeFault("IO main must be invoked through runIO");
        if (hostResultFault != null) throw new RuntimeFault("Diagnostic unsupported path reached: " + hostResultFault);
        if (arguments.length != argumentCount) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new IllegalArgumentException("Host kernel " + entry + " expects " + argumentCount + " arguments");
        }
        owner.admitGuestOrigin();
        checkpointInvocationStarted = true;
        var threads = owner.getThreads();
        if (threads.needsHosting()) return threads.hostEntry(dispatch, () -> execute(arguments, dispatch));
        Closure closure = guestEntry instanceof Closure value ? value : null;
        GuestRoot signature = closure != null && closure.target.getRootNode() instanceof GuestRoot root ? root : null;
        Object[] normalized;
        if (hostInputs != null) normalized = HostAbi.arguments(owner, hostInputs, arguments);
        else {
            normalized = new Object[arguments.length];
            for (int index = 0; index < arguments.length; index++) {
                Object input = arguments[index];
                if (!(input instanceof Long || input instanceof Integer || input instanceof Short || input instanceof Byte)) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    throw new IllegalStateException("Host arguments without retained signatures must be signed 64-bit integers");
                }
                long value = ((Number) input).longValue();
                CoreRepresentation proof = signature == null || signature.getInputLayout() == null ? null : signature.getInputLayout().proof(index + closure.suppliedCount);
                NarrowInteger narrow = proof == null ? null : proof.getNarrowInteger();
                if (narrow == null) normalized[index] = value;
                else normalized[index] = narrow.fromHost(value);
            }
        }
        threads.enterCurrent(null, false, program.getCapturesContinuations(), null);
        var outcome = GuestThreadStatus.FINISHED;
        try {
            try {
                Object result = AsyncContinuations.publicResult(dispatch.executePublic(guestTarget, new Object[]{guestEntry, normalized}), dispatch);
                if (hostResult != null) return checkedResult(HostAbi.result(owner, hostResult, result, program));
                var proof = hostResult != null ? hostResult : signature == null ? null : signature.getScalarResultProof();
                NarrowInteger narrow = proof == null ? null : proof.getNarrowInteger();
                if (narrow == null) return checkedResult(result);
                if (!(result instanceof Integer value)) throw new RuntimeFault("Expected narrow integer result at public boundary");
                return checkedResult(narrow.widen(value));
            } catch (ThunkSuspended suspended) { throw AsyncContinuations.publicSuspension(suspended, dispatch); }
            catch (CallSegmentSuspended suspended) { throw AsyncContinuations.publicSuspension(suspended, dispatch); }
            catch (AsyncDelivery delivered) { throw AsyncContinuations.uncaught(delivered.getRequest(), dispatch); }
        } catch (Throwable failure) {
            outcome = GuestThreadStatus.uncaught(failure);
            if (failure instanceof GuestException guest) dispatch.escaping(guest);
            throw failure;
        } finally { threads.leaveCurrent(outcome); }
    }

    private Object checkedResult(Object result) {
        if (requiredCode != null) requireCachedReturn();
        return result;
    }
    @TruffleBoundary private void requireCachedReturn() {
        // This runs after HostAbi consumes any result loan, on the actual hosted
        // guest-return thread and before leaveCurrent, not the host caller thread.
        requiredCode.requireInstalledCode();
        var state = guestTarget.getRootNode().getLanguage(Language.class).getHandoffState().get();
        if (state.getPending() != null || state.getArguments().getDepth() != 0 || state.getResults().getDepth() != 0 ||
                state.getArguments().retainedReferences() != 0 || state.getResults().retainedReferences() != 0)
            throw new IllegalStateException("Cached guest returned with outstanding handoff state");
    }

    @ExportMessage public boolean hasMembers() { return true; }
    @ExportMessage public Object getMembers(boolean includeInternal) {
        return new MemberNames(program.getHasBytecode()
            ? new String[]{"diagnostics", ioTarget == null ? "compile" : "runIO", "bytecode"}
            : new String[]{"diagnostics", ioTarget == null ? "compile" : "runIO"});
    }
    @ExportMessage public boolean isMemberReadable(String member) { return "diagnostics".equals(member) || "bytecode".equals(member) && program.getHasBytecode(); }
    @ExportMessage @TruffleBoundary public Object readMember(String member) throws UnknownIdentifierException {
        if ("diagnostics".equals(member)) {
            var installed = installedCompilation;
            var observed = new LinkedHashMap<String, Object>(program.diagnostics());
            if (installed != null) observed.put("explicitCompilation", compilationObservation(installed));
            if (checkpointCompilation != null) observed.put("checkpointCompilation", checkpointCompilationObservation(false));
            return Json.stringify(observed);
        }
        if ("bytecode".equals(member) && program.getHasBytecode()) return program.bytecodeDump();
        throw UnknownIdentifierException.create(member);
    }
    private List<CallTarget> compilationTargets(RootCallTarget original) {
        var selected = new LinkedHashSet<CallTarget>();
        for (var call : NodeUtil.findAllNodeInstances(guestTarget.getRootNode(), DirectCallNode.class))
            if (call.getCallTarget() == original) selected.add(call.getCurrentCallTarget());
        if (selected.isEmpty()) selected.add(original);
        selected.add(guestTarget);
        return new ArrayList<>(selected);
    }
    /** Observe installation without executing, compiling or repairing any target. */
    private Map<String, Object> compilationObservation(Compilation installed) {
        try {
            var current = compilationTargets(installed.original());
            var targets = installed.targets();
            var validity = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("isValidLastTier");
            boolean same = current.size() == targets.size(), valid = true;
            for (var target : targets) {
                boolean found = false;
                for (var candidate : current) if (candidate == target) { found = true; break; }
                same &= found;
                // Preserve short-circuiting of the original all predicate.
                if (valid) valid = Boolean.TRUE.equals(validity.invoke(target));
            }
            var observation = new LinkedHashMap<String, Object>();
            observation.put("targetCount", targets.size()); observation.put("sameTargets", same); observation.put("validLastTier", valid);
            return observation;
        } catch (ReflectiveOperationException failure) { return EntryValue.<RuntimeException, Map<String, Object>>rethrow(failure); }
    }
    @ExportMessage public boolean isMemberInvocable(String member) { return (ioTarget == null ? "compile" : "runIO").equals(member); }
    @ExportMessage @TruffleBoundary public Object invokeMember(String member, Object[] arguments,
            @Cached(value = "create()", uncached = "create()", neverDefault = true) HostDispatch dispatch) throws UnknownIdentifierException {
        if ("runIO".equals(member) && ioTarget != null) {
            requireOwner(dispatch);
            if (arguments.length != 0) throw new IllegalArgumentException("runIO takes no arguments");
            if (lifecycleStarted != null && lifecycleStarted.get()) throw new RuntimeFault("Executable IO lifecycle already started");
            checkpointInvocationStarted = true;
            owner.admitGuestOrigin();
            var threads = owner.getThreads();
            if (threads.needsHosting()) return threads.hostEntry(dispatch, () -> invokeMember(member, arguments, dispatch));
            // Hosting may race another valid entry; reserve the one-shot lifecycle only here.
            if (lifecycleStarted != null && !lifecycleStarted.compareAndSet(false, true)) throw new RuntimeFault("Executable IO lifecycle already started");
            threads.enterCurrent(null, false, program.getCapturesContinuations(), null);
            var outcome = GuestThreadStatus.FINISHED;
            try {
                if (processSignals) {
                    if (owner.getWindowsNativeIo() != null) owner.getWindowsNativeIo().console().bind(program);
                    else owner.getSignals().bind(program);
                }
                try {
                    dispatch.execute(ioTarget, new Object[]{guestEntry});
                    // Run the original Handle action over this program's CAFs.
                    // TopHandler itself flushes on its exceptional path.
                    if (shutdownTarget != null) dispatch.execute(shutdownTarget, new Object[]{shutdownValue});
                    if (requiredCode != null) requireCachedReturn();
                } catch (ThunkSuspended suspended) { throw AsyncContinuations.publicSuspension(suspended, dispatch); }
                catch (CallSegmentSuspended suspended) { throw AsyncContinuations.publicSuspension(suspended, dispatch); }
                catch (AsyncDelivery delivered) { throw AsyncContinuations.uncaught(delivered.getRequest(), dispatch); }
            } catch (Throwable failure) {
                outcome = GuestThreadStatus.uncaught(failure);
                if (failure instanceof GuestException guest) dispatch.escaping(guest);
                throw failure;
            } finally {
                try {
                    if (processSignals) {
                        if (owner.getWindowsNativeIo() != null) owner.getWindowsNativeIo().console().close();
                        else owner.getSignals().close();
                    }
                }
                finally { threads.leaveCurrent(outcome); }
            }
            return true;
        }
        if (!"compile".equals(member) || ioTarget != null) throw UnknownIdentifierException.create(member);
        requireOwner(dispatch);
        if (arguments.length != 0) throw new IllegalArgumentException("compile takes no arguments");
        installedCompilation = null;
        var original = program.entryTarget(entry);
        var targets = compilationTargets(original);
        try {
            var cls = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget");
            // Compile the active guest splits and the stable host bridge, not only closure identity.
            for (var target : targets) {
                if (!cls.isInstance(target)) throw new IllegalArgumentException("Graal optimizing Truffle runtime required");
                cls.getMethod("compile", boolean.class).invoke(target, true);
                cls.getMethod("waitForCompilation").invoke(target);
                if (!Boolean.TRUE.equals(cls.getMethod("isValidLastTier").invoke(target))) throw new IllegalStateException("Guest code was not installed");
            }
            // Restore a retired call-boundary stub without executing guest code or settling a call.
            var runtime = Truffle.getRuntime();
            runtime.getClass().getMethod("bypassedInstalledCode", cls).invoke(runtime, guestTarget);
        } catch (ReflectiveOperationException failure) { return EntryValue.<RuntimeException, Object>rethrow(failure); }
        installedCompilation = new Compilation(original, targets);
        return true;
    }
    @SuppressWarnings("unchecked") private static <T extends Throwable, R> R rethrow(Throwable failure) throws T { throw (T) failure; }
}
