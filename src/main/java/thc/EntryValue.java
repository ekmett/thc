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
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import thc.runtime.*;

/** Signature-driven host entry or one-shot executable IO lifecycle. */
@ExportLibrary(InteropLibrary.class)
public final class EntryValue implements TruffleObject {
    private final ExecutableProgram program;
    private final String entry, hostResultFault;
    private final int argumentCount;
    private final boolean processSignals;
    private final List<CoreRepresentation> hostInputs;
    private final CoreRepresentation hostResult;
    private final RootCallTarget guestTarget, ioTarget, shutdownTarget;
    private final Object guestEntry, shutdownValue;
    private final AtomicBoolean lifecycleStarted;
    private record Compilation(RootCallTarget original, List<CallTarget> targets) {}
    private volatile Compilation installedCompilation;

    public EntryValue(ExecutableProgram program, String entry, int argumentCount) {
        this(program, entry, argumentCount, null, null, null, null, null, false, null, null);
    }
    public EntryValue(ExecutableProgram program, String entry, int argumentCount, String hostResultFault,
            CoreRepresentation ioResult, Language language, String shutdownEntry,
            CoreRepresentation shutdownResult, boolean processSignals,
            List<CoreRepresentation> hostInputs, CoreRepresentation hostResult) {
        this.program = program; this.entry = entry; this.argumentCount = argumentCount;
        this.hostResultFault = hostResultFault; this.processSignals = processSignals;
        var untypedTarget = program.hostEntryTarget(argumentCount);
        guestEntry = program.entryValue(entry);
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
        guestTarget = hostInputs != null && hostResult != null && argumentCount != 0 && (admittedSignature || typed)
            ? ((EntryRoot) untypedTarget.getRootNode()).withSignature(language != null ? language : untypedTarget.getRootNode().getLanguage(Language.class), hostInputs, hostResult).getCallTarget() : untypedTarget;
        if (ioResult != null && language == null) throw new IllegalStateException("Missing IO language");
        ioTarget = ioResult == null ? null : new IoMainRoot(language, ioResult).getCallTarget();
        shutdownValue = shutdownEntry == null ? null : program.entryValue(shutdownEntry);
        if (shutdownResult != null && language == null) throw new IllegalStateException("Missing IO language");
        shutdownTarget = shutdownResult == null ? null : new IoMainRoot(language, shutdownResult).getCallTarget();
        lifecycleStarted = shutdownTarget == null ? null : new AtomicBoolean();
        if ((shutdownValue == null) != (shutdownTarget == null)) throw new IllegalArgumentException("Failed requirement.");
    }

    @ExportMessage public boolean isExecutable() { return ioTarget == null; }
    @ExportMessage public Object execute(Object[] arguments,
            @Cached(value = "create()", uncached = "create()", neverDefault = true) HostDispatch dispatch) {
        if (ioTarget != null) throw new RuntimeFault("IO main must be invoked through runIO");
        if (hostResultFault != null) throw new RuntimeFault("Diagnostic unsupported path reached: " + hostResultFault);
        if (arguments.length != argumentCount) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new IllegalArgumentException("Host kernel " + entry + " expects " + argumentCount + " arguments");
        }
        Closure closure = guestEntry instanceof Closure value ? value : null;
        GuestRoot signature = closure != null && closure.target.getRootNode() instanceof GuestRoot root ? root : null;
        var owner = Language.currentState(dispatch);
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
        var threads = owner.getThreads();
        threads.enterCurrent(null, false, program.getAsynchronousExceptions(), null);
        var outcome = GuestThreadStatus.FINISHED;
        try {
            try {
                Object result = AsyncContinuations.publicResult(dispatch.executePublic(guestTarget, new Object[]{guestEntry, normalized}), dispatch);
                if (hostResult != null) return HostAbi.result(owner, hostResult, result, program);
                var proof = hostResult != null ? hostResult : signature == null ? null : signature.getScalarResultProof();
                NarrowInteger narrow = proof == null ? null : proof.getNarrowInteger();
                if (narrow == null) return result;
                if (!(result instanceof Integer value)) throw new RuntimeFault("Expected narrow integer result at public boundary");
                return narrow.widen(value);
            } catch (ThunkSuspended suspended) { throw AsyncContinuations.publicSuspension(suspended, dispatch); }
            catch (CallSegmentSuspended suspended) { throw AsyncContinuations.publicSuspension(suspended, dispatch); }
            catch (AsyncDelivery delivered) { throw AsyncContinuations.uncaught(delivered.getRequest(), dispatch); }
        } catch (Throwable failure) {
            outcome = GuestThreadStatus.uncaught(failure);
            if (failure instanceof GuestException guest) dispatch.escaping(guest);
            throw failure;
        } finally { threads.leaveCurrent(outcome); }
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
            if (installed == null) return Json.stringify(program.diagnostics());
            var observed = new LinkedHashMap<String, Object>(program.diagnostics());
            observed.put("explicitCompilation", compilationObservation(installed));
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
            if (arguments.length != 0) throw new IllegalArgumentException("runIO takes no arguments");
            if (lifecycleStarted != null && !lifecycleStarted.compareAndSet(false, true)) throw new RuntimeFault("Executable IO lifecycle already started");
            var owner = Language.currentState(dispatch);
            var threads = owner.getThreads();
            threads.enterCurrent(null, false, program.getAsynchronousExceptions(), null);
            var outcome = GuestThreadStatus.FINISHED;
            try {
                if (processSignals) owner.getSignals().bind(program);
                try {
                    dispatch.execute(ioTarget, new Object[]{guestEntry});
                    // Run the original Handle action over this program's CAFs.
                    // TopHandler itself flushes on its exceptional path.
                    if (shutdownTarget != null) dispatch.execute(shutdownTarget, new Object[]{shutdownValue});
                } catch (ThunkSuspended suspended) { throw AsyncContinuations.publicSuspension(suspended, dispatch); }
                catch (CallSegmentSuspended suspended) { throw AsyncContinuations.publicSuspension(suspended, dispatch); }
                catch (AsyncDelivery delivered) { throw AsyncContinuations.uncaught(delivered.getRequest(), dispatch); }
            } catch (Throwable failure) {
                outcome = GuestThreadStatus.uncaught(failure);
                if (failure instanceof GuestException guest) dispatch.escaping(guest);
                throw failure;
            } finally {
                try { if (processSignals) owner.getSignals().close(); }
                finally { threads.leaveCurrent(outcome); }
            }
            return true;
        }
        if (!"compile".equals(member) || ioTarget != null) throw UnknownIdentifierException.create(member);
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
