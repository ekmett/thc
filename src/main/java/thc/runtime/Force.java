// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleSafepoint;
import com.oracle.truffle.api.bytecode.ContinuationResult;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.BranchProfile;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import static thc.runtime.RuntimeFault.fault;
import static thc.runtime.SavedGuestContinuations.savedGuestContinuation;
import static thc.runtime.SavedGuestContinuations.asyncRequest;
import static thc.runtime.AstStacks.astStackScope;
import static thc.runtime.AstStacks.stackSpill;

public final class Force extends Node {
    private static final Object RETRY = new Object();
    private record Parked(Object boundary, SavedGuestContinuation continuation) {}
    /** Retain failure data, never a shared mutable Truffle stack trace. */
    private record MemoizedGuestFailure(Object payload, Node location, boolean someException) {}
    private final Metrics metrics;
    private final boolean asyncMode;
    @Child private ThunkTargetCache calls;
    @Child private TailCallLoop trampoline;
    private final BranchProfile tailCallProfile = BranchProfile.create();
    private final BranchProfile resumeProfile = BranchProfile.create();
    private final BranchProfile suspensionProfile = BranchProfile.create();
    @CompilationFinal private volatile boolean seenThunk;

    public Force(Metrics metrics) { this(metrics, false); }
    public Force(Metrics metrics, boolean asyncMode) {
        this.metrics = metrics; this.asyncMode = asyncMode;
        calls = new ThunkTargetCache(metrics);
        trampoline = new TailCallLoop(metrics);
    }
    public Object execute(VirtualFrame frame, Object value) {
        if (!(value instanceof Thunk original)) return value;
        Metrics invocationMetrics = metrics;
        if (invocationMetrics == null) {
            if (!(getRootNode() instanceof FunctionRoot root)) throw fault("Missing reusable force owner");
            invocationMetrics = root.invocationMetrics(frame);
        }
        if (!seenThunk) {
            // An untouched compiled demand has no target/profile history. Keep its
            // real thunk execution cold without training or retiring the caller.
            if (CompilerDirectives.inCompiledCode()) return executeCold(original, invocationMetrics);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            seenThunk = true;
        }
        return executeThunk(original, invocationMetrics);
    }
    @TruffleBoundary(transferToInterpreterOnException = false)
    private Object executeCold(Thunk original, Metrics invocationMetrics) { return executeThunk(original, invocationMetrics); }

    private Object executeThunk(Thunk original, Metrics invocationMetrics) {
        // Successful updates already verified WHNF before publishing state 2.
        while (true) {
            switch (original.getState()) {
                case 2 -> { if (invocationMetrics.getEnabled()) invocationMetrics.incrementThunkHits(); return original.getValue(); }
                case 3 -> throw rethrowFailure(original);
                case 4 -> throw fault("Interrupted thunk has no resumable continuation");
            }
            SavedGuestContinuation observed = null;
            if (original.getState() == 5) {
                resumeProfile.enter();
                observed = savedGuestContinuation(original.getValue());
            }
            PendingWait pending = PendingWait.of(observed);
            if (pending != null && !pending.abandoned() && pending.owner != GuestThreads.executingCurrent(this)) {
                awaitOwner(original); continue;
            }
            // The continuation owns its callee frame; never materialize this caller.
            if (suspendedChild(observed) != null) return resumeChain(original, false, false, invocationMetrics);
            Object result = executeOne(original, observed, thc.runtime.Unit.INSTANCE, invocationMetrics);
            if (result != RETRY) return result;
        }
    }

    /** Deliver at this exact captured caller while its shared child remains parked. */
    @TruffleBoundary public Object deliverAtCapturedHandler(Thunk original, Thunk child, Object payload) {
        boolean claimed = false;
        try {
            ContinuationResult saved;
            synchronized (original.getMonitor()) {
                saved = original.getValue() instanceof ContinuationResult value ? value : null;
                if (original.getState() != 5 || saved == null ||
                    !(saved.getResult() instanceof ThunkSuspended suspension) || suspension.getThunk() != child || child.getState() != 5)
                    throw fault("Async handler cut requires the exact parked shared child");
                original.setValue(null);
                original.setOwner(Thread.currentThread());
                original.setState(1);
                claimed = true;
            }
            return evaluateOwned(original, savedGuestContinuation(saved), new AsyncThunkUnwind(payload));
        } catch (Throwable failure) {
            if (claimed) suspendOwned(original);
            throw failure;
        }
    }
    public Object deliverAtCapturedIOHandler(CapturedAsyncRequest request) { return deliverAtCapturedIOHandler(request, null); }
    public Object deliverAtCapturedIOHandler(CapturedAsyncRequest request, Runnable afterClaim) {
        try {
            Object answer = deliverAtCapturedIOHandler(request.getParent(), request.getChild(), request.getPayload(), afterClaim, request);
            if (request.getState() != CapturedRequestState.ACKNOWLEDGED)
                throw new IllegalStateException("Captured handler returned without acknowledging delivery");
            return answer;
        } catch (Throwable failure) {
            // An observer can advance the parent before this exact cut claims it.
            request.fail$org_intelligence_thc();
            throw failure;
        }
    }
    public Object deliverAtCapturedIOHandler(Object original, CallSegment child, Object payload) {
        return deliverAtCapturedIOHandler(original, child, payload, null, null);
    }
    public Object deliverAtCapturedIOHandler(Object original, CallSegment child, Object payload, Runnable afterClaim) {
        return deliverAtCapturedIOHandler(original, child, payload, afterClaim, null);
    }
    private static boolean exact(ContinuationResult saved, CallSegment child) {
        return saved != null && saved.getContinuationRootNode().getSourceRootNode() instanceof BytecodeRoot &&
            saved.getResult() instanceof CallSegmentSuspended cut && cut.getSegment() == child &&
            child.getCaughtIOAction() && child.getTupleShape() != null;
    }
    @TruffleBoundary public Object deliverAtCapturedIOHandler(Object original, CallSegment child, Object payload,
                                                              Runnable afterClaim, CapturedAsyncRequest request) {
        if (original instanceof Thunk thunk) {
            boolean claimed = false;
            try {
                ContinuationResult saved;
                synchronized (thunk.getMonitor()) {
                    saved = thunk.getValue() instanceof ContinuationResult value ? value : null;
                    if (thunk.getState() != 5 || !exact(saved, child)) throw fault("Async IO handler cut requires the exact parked action");
                    if (request != null && !request.commit$org_intelligence_thc(thunk, child))
                        throw fault("Async IO handler cut requires a pending request for this continuation");
                    thunk.setValue(null); thunk.setOwner(Thread.currentThread()); thunk.setState(1); claimed = true;
                }
                if (afterClaim != null) afterClaim.run();
                return evaluateOwned(thunk, savedGuestContinuation(saved), new PrivateIOUnwind(child, payload, request));
            } catch (Throwable failure) {
                if (claimed) suspendOwned(thunk);
                if (claimed && request != null) request.fail$org_intelligence_thc();
                throw failure;
            }
        }
        if (original instanceof CallSegment segment) {
            boolean claimed = false;
            try {
                ContinuationResult saved;
                MaskingState mask;
                synchronized (segment.getMonitor()) {
                    saved = segment.getValue() instanceof ContinuationResult value ? value : null;
                    if (segment.getState() != 5 || !exact(saved, child)) throw fault("Async IO handler cut requires the exact parked action");
                    if (request != null && !request.commit$org_intelligence_thc(segment, child))
                        throw fault("Async IO handler cut requires a pending request for this continuation");
                    mask = segment.getLogicalMask();
                    segment.setValue(null); segment.setOwner(Thread.currentThread()); segment.setState(1); claimed = true;
                }
                if (afterClaim != null) afterClaim.run();
                return evaluateCallSegment(segment, savedGuestContinuation(saved), mask, new PrivateIOUnwind(child, payload, request), metrics);
            } catch (Throwable failure) {
                if (claimed) suspendCallOwned(segment);
                if (claimed && request != null) request.fail$org_intelligence_thc();
                throw failure;
            }
        }
        throw fault("Async IO handler cut requires a captured thunk or call segment");
    }

    private boolean asyncUnwind(Object input) {
        AsyncRequest request = input instanceof AstChildSuspension suspended ? suspended.getRequest() :
            input instanceof ChildResume child && child.getFailure() instanceof AsyncDelivery delivered ? delivered.getRequest() : null;
        return request != null && request.getTarget() == Thread.currentThread() &&
            request.getTargetId() == GuestThreads.current(this).currentId() && request.getState() == AsyncRequestState.CLAIMED;
    }
    private Object executeOne(Thunk original, SavedGuestContinuation observed, Object resumeValue, Metrics metrics) {
        boolean capturing = resumeValue instanceof ChildResume input && input.getFailure() instanceof DelimitedCut;
        while (true) {
            if (!capturing) switch (original.getState()) {
                case 2 -> { if (metrics.getEnabled()) metrics.incrementThunkHits(); return original.getValue(); }
                case 3 -> throw rethrowFailure(original);
                case 4 -> throw fault("Interrupted thunk has no resumable continuation");
            }
            SavedGuestContinuation continuation = null;
            boolean claimedHere = false;
            try {
                int claim;
                synchronized (original.getMonitor()) {
                    if (capturing && (observed == null || original.getState() != 5 || original.getValue() != observed.getIdentity()))
                        throw fault("Delimited capture lost its exact parked caller");
                    switch (original.getState()) {
                        case 0 -> {
                            original.setOwner(Thread.currentThread()); original.setState(1); claimedHere = true; claim = 0;
                        }
                        case 5 -> {
                            resumeProfile.enter();
                            if (observed != null && original.getValue() != observed.getIdentity() ||
                                observed == null && suspendedChild(savedGuestContinuation(original.getValue())) != null) claim = 3;
                            else {
                                continuation = savedGuestContinuation(original.getValue());
                                if (continuation == null) throw fault("Suspended thunk has no guest continuation");
                                PendingWait pending = PendingWait.of(continuation);
                                if (pending != null && !pending.abandoned() && pending.owner != GuestThreads.executingCurrent(this) && !asyncUnwind(resumeValue)) claim = 1;
                                else {
                                    original.setValue(null);
                                    original.setOwner(Thread.currentThread()); original.setState(1); claimedHere = true; claim = 0;
                                }
                            }
                        }
                        case 1 -> claim = original.getOwner() == Thread.currentThread() ? 2 : 1;
                        default -> claim = 3;
                    }
                }
                switch (claim) {
                    case 0 -> { return evaluateOwned(original, continuation, resumeValue, metrics); }
                    case 1 -> awaitOwner(original);
                    case 2 -> {
                        if (metrics.getEnabled()) metrics.incrementBlackholes();
                        throw fault("Blackhole: cyclic thunk entered while evaluating");
                    }
                    case 3 -> { return RETRY; }
                }
            } catch (Throwable failure) {
                // A safepoint can transfer control after the ownership store.
                if (claimedHere) suspendOwned(original);
                throw failure;
            }
        }
    }

    private Object resumeChain(Object original, boolean drainSpills, boolean delimitedInvocation, Metrics invocationMetrics) {
        return resumeChain(original, drainSpills, delimitedInvocation, invocationMetrics, null, null);
    }
    @TruffleBoundary private Object resumeChain(Object original, boolean drainSpills, boolean delimitedInvocation,
            Metrics invocationMetrics, AsyncRequest waitingRequest, Thunk awaited) {
        var parked = new ArrayDeque<Parked>();
        var seen = new IdentityHashMap<Object, Boolean>();
        Object leaf = original;
        while (true) {
            SavedGuestContinuation leafContinuation;
            Object injected = Unit.INSTANCE;
            while (true) {
                if (seen.put(leaf, true) != null) throw fault("Suspended thunk dependency cycle");
                leafContinuation = continuationOf(leaf);
                if (delimitedInvocation && leafContinuation != null && leafContinuation.getYielded() instanceof DelimitedCut)
                    throw new UnsupportedCore("control0# cannot recapture a parked one-shot invocation chain");
                Object child = suspendedChild(leafContinuation);
                if (child == null) break;
                if (waitingRequest != null && child == awaited) {
                    injected = unwindCaller(new Parked(leaf, leafContinuation), child, waitingRequest);
                    waitingRequest = null; awaited = null;
                    break;
                }
                if (child instanceof Thunk thunk) {
                    PendingWait pending = PendingWait.of(thunk.getValue());
                    if (pending != null && !pending.abandoned() && pending.owner != GuestThreads.executingCurrent(this)) awaitOwner(thunk);
                }
                parked.addLast(new Parked(leaf, leafContinuation));
                if (drainSpills) {
                    AstStackScope scope = astStackScope(this);
                    scope.setMaxParkedSpillParents(Math.max(scope.getMaxParkedSpillParents(), parked.size()));
                }
                leaf = child;
            }
            Object current = leaf;
            SavedGuestContinuation expected = leafContinuation;
            // Traversal has transferred ownership to the active boundary. These
            // interpreter locals must not root a completed leaf while its parent runs.
            leaf = null;
            leafContinuation = null;
            Object input = injected;
            while (true) {
                AsyncRequest pending = input instanceof AstChildSuspension suspension ? suspension.getRequest() :
                    input instanceof ChildResume child && child.getFailure() instanceof AsyncDelivery delivered ? delivered.getRequest() : null;
                boolean spilled = false;
                Object outcome;
                try {
                    Object answer = switch (current) {
                        case Thunk thunk -> executeOne(thunk, expected, input, invocationMetrics);
                        case CallSegment segment -> executeCallSegment(segment, expected, input, invocationMetrics);
                        default -> throw fault("Invalid suspended continuation boundary");
                    };
                    outcome = answer == RETRY ? null : new ChildResume(answer, null);
                } catch (ThunkSuspended suspension) {
                    if (drainSpills && (suspension.getStackSpill() || delimitedInvocation) && suspension.getAsyncRequest() == null && PendingWait.of(suspension) == null) {
                        spilled = true; outcome = null;
                    } else if (suspension.getThunk() == current && suspension.getAsyncRequest() != null && canUnwindCaller(parked.peekLast(), drainSpills)) {
                        outcome = unwindCaller(parked.peekLast(), current, suspension.getAsyncRequest());
                    } else {
                        resignalParked(original, suspension.getAsyncRequest(), suspension.getStackSpill());
                        throw suspension;
                    }
                } catch (CallSegmentSuspended suspension) {
                    if (drainSpills && (suspension.getStackSpill() || delimitedInvocation) && suspension.getAsyncRequest() == null && PendingWait.of(suspension) == null) {
                        spilled = true; outcome = null;
                    } else if (suspension.getSegment() == current && suspension.getAsyncRequest() != null && canUnwindCaller(parked.peekLast(), drainSpills)) {
                        outcome = unwindCaller(parked.peekLast(), current, suspension.getAsyncRequest());
                    } else {
                        // Resignal the requested boundary, never skip its saved caller.
                        resignalParked(original, suspension.getAsyncRequest(), suspension.getStackSpill());
                        throw suspension;
                    }
                } catch (DelimitedCut cut) {
                    if (delimitedInvocation && (!(current instanceof CallSegment) || !(expected instanceof AstContinuation || expected instanceof AstStackContinuation)))
                        throw new UnsupportedCore("control0# cannot recapture this parked invocation");
                    if (parked.isEmpty()) throw cut;
                    // Abort through each exact private caller, leaf first. Its
                    // consumed child edge throws before running the saved suffix.
                    outcome = new ChildResume(null, cut);
                } catch (GuestException | RuntimeFault | STMRetry | STMConflict | STMRestart failure) {
                    outcome = new ChildResume(null, failure);
                } catch (AsyncDelivery failure) {
                    if (!drainSpills) throw failure;
                    outcome = new ChildResume(null, failure);
                } catch (TailCall tail) {
                    if (!AstTailAnchor.accepts(astStackScope(this).getTailAnchor(), tail)) throw tail;
                    outcome = tail;
                }
                if (pending != null && pending.getState() != AsyncRequestState.CLAIMED) pending = null;
                if (pending != null && (outcome instanceof ChildResume || outcome == null && !spilled)) {
                    // A concurrent evaluator advancing this caller never consumes delivery.
                    if (canUnwindCaller(parked.peekLast(), drainSpills)) outcome = unwindCaller(parked.peekLast(), current, pending);
                    else throw resignalPending(original, pending);
                }
                if (outcome == null) {
                    if (spilled) { leaf = current; seen.remove(current); }
                    else { leaf = original; parked.clear(); seen.clear(); }
                    break;
                }
                seen.remove(current);
                if (parked.isEmpty()) {
                    if (outcome instanceof TailCall tail) throw tail;
                    if (!(outcome instanceof ChildResume completed)) throw fault("AST child cut has no saved caller");
                    if (completed.getFailure() != null) throw completed.takeFailure();
                    return completed.takeValue();
                }
                Parked parent = parked.removeLast();
                current = parent.boundary(); expected = parent.continuation(); input = outcome;
            }
        }
    }
    private boolean astCaller(Parked parked) {
        return parked != null && (parked.continuation() instanceof AstContinuation || parked.continuation() instanceof AstStackContinuation);
    }
    private boolean canUnwindCaller(Parked parked, boolean drainSpills) {
        return astCaller(parked) || drainSpills && parked != null && parked.continuation().getSourceRoot() instanceof BytecodeRoot;
    }
    private Object unwindCaller(Parked parked, Object child, AsyncRequest request) {
        if (astCaller(parked)) return new AstChildSuspension(child, request);
        if (request.getTarget() != Thread.currentThread() || request.getState() != AsyncRequestState.CLAIMED)
            throw new IllegalStateException("Spilled bytecode delivery left its claimed target");
        // Existing resume operations restore the lexical mask before forwarding failure.
        // The real catch# acknowledges this exact request; the driver never does.
        return new ChildResume(null, new AsyncDelivery(request, this));
    }
    private RuntimeException resignalPending(Object original, AsyncRequest request) {
        resignalParked(original, request, false);
        throw new AsyncBlocked(request, this);
    }
    private void resignalParked(Object original, AsyncRequest request, boolean stackSpill) {
        if (original instanceof Thunk thunk && thunk.getState() == 5) throw new ThunkSuspended(thunk, request, stackSpill);
        if (original instanceof CallSegment segment && segment.getState() == 5) throw new CallSegmentSuspended(segment, null, request, stackSpill);
    }
    public Object drainStack(SavedGuestContinuation initial) {
        return drainStack(initial, initial.getSourceRoot() instanceof GuestRoot root ? root.getTupleResult$org_intelligence_thc() : null, false, false);
    }
    public Object drainStack(SavedGuestContinuation initial, TupleShape resultShape) { return drainStack(initial, resultShape, false, false); }
    public Object drainStack(SavedGuestContinuation initial, TupleShape resultShape, boolean delimitedInvocation) {
        return drainStack(initial, resultShape, delimitedInvocation, false);
    }
    public Object drainStack(SavedGuestContinuation initial, TupleShape resultShape,
                             boolean delimitedInvocation, boolean tailSpill) {
        return drainStack(initial, resultShape, delimitedInvocation, tailSpill, metrics);
    }
    @TruffleBoundary public Object drainStack(SavedGuestContinuation initial, TupleShape resultShape,
                                             boolean delimitedInvocation, boolean tailSpill, Metrics invocationMetrics) {
        MaskingState mask = SynchronousMasking.current(this);
        if (!(initial.getSourceRoot() instanceof GuestRoot root)) throw fault("AST stack cut has no guest root");
        // A pattern binding also leaves a synthetic marker local in this long-lived
        // interpreter frame. Transfer the mask without retaining that child edge.
        Object yielded = initial.getYielded();
        MaskingState parkedMask = yielded instanceof CallSegmentSuspended ? ((CallSegmentSuspended) yielded).getParkedActiveMask() : null;
        yielded = null;
        if (parkedMask == null) parkedMask = mask;
        CallSegment segment = new CallSegment(initial.getIdentity(), parkedMask, mask, resultShape, false, tailSpill);
        while (true) {
            try { return resumeChain(segment, true, delimitedInvocation, invocationMetrics); }
            catch (CallSegmentSuspended cut) {
                if (cut.getSegment() != segment) throw cut;
                if (!cut.getStackSpill() || cut.getAsyncRequest() != null) return new AstStackContinuation(root, cut);
            } catch (PendingWait wait) {
                return new DriverWait(this, root, segment, wait, delimitedInvocation, invocationMetrics);
            }
        }
    }
    static final class DriverWait implements SavedGuestContinuation {
        private final Force force;
        private final GuestRoot root;
        private final CallSegment segment;
        private final PendingWait wait;
        private final boolean delimited;
        private final Metrics metrics;
        private boolean claimed;
        DriverWait(Force force, GuestRoot root, CallSegment segment, PendingWait wait, boolean delimited, Metrics metrics) {
            this.force = force; this.root = root; this.segment = segment; this.wait = wait; this.delimited = delimited; this.metrics = metrics;
        }
        @Override public Object getIdentity() { return this; }
        @Override public Object getYielded() { return wait; }
        @Override public Object getSourceRoot() { return root; }
        @Override public Object continueWith(Object input) {
            if (input != Unit.INSTANCE || claimed) throw fault("Invalid pending driver continuation entry");
            claimed = true;
            try {
                try { wait.resume(); }
                catch (AsyncBlocked blocked) {
                    if (!(wait.operation instanceof Force.DemandWait demand)) throw blocked;
                    return force.resumeChain(segment, true, delimited, metrics, blocked.getRequest(), demand.thunk);
                }
                return force.resumeChain(segment, true, delimited, metrics);
            } catch (PendingWait pending) {
                return new DriverWait(force, root, segment, pending, delimited, metrics);
            } catch (CallSegmentSuspended cut) {
                if (cut.getSegment() != segment) throw cut;
                return new AstStackContinuation(root, cut);
            }
        }
    }
    @TruffleBoundary Object executeInitialization(CallSegment segment) { return resumeChain(segment, false, false, metrics); }
    @TruffleBoundary public Object drainDelimitedBoundary(Object boundary, Metrics invocationMetrics) { return resumeChain(boundary, true, true, invocationMetrics); }
    private SavedGuestContinuation continuationOf(Object boundary) {
        return switch (boundary) {
            case Thunk thunk -> thunk.getState() == 5 ? savedGuestContinuation(thunk.getValue()) : null;
            case CallSegment segment -> segment.getState() == 5 ? savedGuestContinuation(segment.getValue()) : null;
            default -> throw fault("Invalid suspended continuation boundary");
        };
    }
    private Object suspendedChild(SavedGuestContinuation continuation) {
        Object signal = continuation == null ? null : continuation.getYielded();
        if (signal instanceof ThunkSuspended suspension) return suspension.getThunk();
        if (signal instanceof CallSegmentSuspended suspension) return suspension.getSegment();
        return null;
    }

    private Object executeCallSegment(CallSegment segment, SavedGuestContinuation observed, Object resumeValue, Metrics invocationMetrics) {
        boolean capturing = resumeValue instanceof ChildResume input && input.getFailure() instanceof DelimitedCut;
        while (true) {
            if (!capturing) switch (segment.getState()) {
                case 2 -> { return segment.getValue(); }
                case 3 -> throw rethrowCallFailure(segment);
                case 4 -> throw fault("Interrupted call segment has no resumable continuation");
            }
            SavedGuestContinuation continuation = null;
            MaskingState resumeMask = MaskingState.UNMASKED;
            boolean claimedHere = false;
            try {
                int claim;
                synchronized (segment.getMonitor()) {
                    if (capturing && (observed == null || segment.getState() != 5 || segment.getValue() != observed.getIdentity()))
                        throw fault("Delimited capture lost its exact parked caller");
                    switch (segment.getState()) {
                        case 5 -> {
                            if (observed != null && segment.getValue() != observed.getIdentity() ||
                                observed == null && suspendedChild(savedGuestContinuation(segment.getValue())) != null) claim = 3;
                            else {
                                continuation = savedGuestContinuation(segment.getValue());
                                if (continuation == null) throw fault("Suspended call segment has no guest continuation");
                                PendingWait pending = PendingWait.of(continuation);
                                if (pending != null && !pending.abandoned() && pending.owner != GuestThreads.executingCurrent(this) && !asyncUnwind(resumeValue)) claim = 4;
                                else {
                                    segment.enterInitial(SynchronousMasking.current(this));
                                    resumeMask = segment.getLogicalMask();
                                    segment.setValue(null);
                                    segment.setOwner(Thread.currentThread()); segment.setState(1); claimedHere = true; claim = 0;
                                }
                            }
                        }
                        case 1 -> claim = segment.getOwner() == Thread.currentThread() ? 2 : 1;
                        default -> claim = 3;
                    }
                }
                switch (claim) {
                    case 0 -> { return evaluateCallSegment(segment, continuation, resumeMask, resumeValue, invocationMetrics); }
                    case 1 -> awaitCallOwner(segment);
                    case 2 -> throw fault("Blackhole: cyclic call segment entered while evaluating");
                    case 3 -> { return RETRY; }
                    case 4 -> {
                        Object child = suspendedChild(continuation);
                        if (child instanceof Thunk thunk) awaitOwner(thunk);
                        else throw fault("Private call segment belongs to another suspended guest");
                    }
                }
            } catch (Throwable failure) {
                if (claimedHere) suspendCallOwned(segment);
                throw failure;
            }
        }
    }
    private Object evaluateCallSegment(CallSegment segment, SavedGuestContinuation continuation,
                                       MaskingState resumeMask, Object resumeValue, Metrics invocationMetrics) {
        MaskingState carrierAmbient = SynchronousMasking.current(this);
        StackAnnotationState carrierAnnotations = StackAnnotations.current(this);
        try {
            SynchronousMasking.set(this, resumeMask);
            if (segment.getTailSpill() && continuation instanceof AstContinuation ast && ast.getTailSpill()) {
                AstTailAnchor anchor = astStackScope(this).getTailAnchor();
                if (anchor != null) ast.rebaseTailBloom(anchor.liveBloom());
            }
            Object returned;
            try { returned = continuation.continueWith(resumeValue); }
            catch (TailCall tail) {
                if (segment.getTailSpill() && AstTailAnchor.accepts(astStackScope(this).getTailAnchor(), tail)) throw tail;
                tailCallProfile.enter(); returned = trampoline.execute(tail, invocationMetrics);
            }
            Object result = returned instanceof TailYield tail ? tail.getContinuation() :
                returned instanceof AstTailYield tail ? tail.getContinuation() : returned;
            SavedGuestContinuation saved = savedGuestContinuation(result);
            if (saved != null) {
                if (!(returned instanceof TailYield) && !(returned instanceof AstTailYield) &&
                    !sameContinuationBody(saved.getSourceRoot(), continuation.getSourceRoot()))
                    throw new IllegalStateException("Nested guest yield has no captured caller segment");
                MaskingState parkedMask = saved.getYielded() instanceof CallSegmentSuspended cut ? cut.getParkedActiveMask() : null;
                if (parkedMask != null && SynchronousMasking.current(this) != segment.getCallerMask())
                    throw new IllegalStateException("Parked call segment did not restore its caller mask");
                AsyncRequest request = asyncRequest(saved);
                publishCallContinuation(segment, saved, parkedMask != null ? parkedMask : SynchronousMasking.current(this));
                throw new CallSegmentSuspended(segment, null, request, stackSpill(saved));
            }
            // Release any producer-thread slab loan even when the callee returned under a wrong mask.
            Object answer = segment.getTupleShape() == null ? result : TupleResults.ownedTupleResult(result, segment.getTupleShape());
            if (SynchronousMasking.current(this) != segment.getCallerMask())
                throw new IllegalStateException("Completed call segment did not restore its caller mask");
            synchronized (segment.getMonitor()) {
                segment.setValue(answer); // Scalar answers may still be lazy.
                segment.setOwner(null); segment.setState(2); segment.getMonitor().notifyAll();
            }
            return answer;
        } catch (CallSegmentSuspended failure) {
            if (failure.getSegment() != segment) suspendCallOwned(segment);
            throw failure;
        } catch (ThunkSuspended | AsyncThunkUnwind failure) {
            suspendCallOwned(segment); throw failure;
        } catch (GuestException failure) {
            if (SynchronousMasking.current(this) != segment.getCallerMask()) {
                suspendCallOwned(segment);
                throw new IllegalStateException("Failed call segment did not restore its caller mask", failure);
            }
            publishCallFailure(segment, new MemoizedGuestFailure(failure.getPayload(),
                failure.getLocation() == null ? this : failure.getLocation(), failure.getSomeException()));
            throw failure;
        } catch (RuntimeFault failure) {
            publishCallFailure(segment, failure); throw failure;
        } catch (Throwable failure) {
            suspendCallOwned(segment); throw failure;
        } finally {
            SynchronousMasking.set(this, carrierAmbient);
            StackAnnotations.set(this, carrierAnnotations);
        }
    }
    private void publishCallContinuation(CallSegment segment, SavedGuestContinuation continuation, MaskingState logicalMask) {
        TruffleSafepoint safepoint = TruffleSafepoint.getCurrent();
        boolean previous = safepoint.setAllowSideEffects(false);
        try {
            synchronized (segment.getMonitor()) {
                segment.setValue(continuation.getIdentity()); segment.setLogicalMask(logicalMask);
                segment.setOwner(null); segment.setState(5); segment.getMonitor().notifyAll();
            }
        } finally { safepoint.setAllowSideEffects(previous); }
    }
    private void publishCallFailure(CallSegment segment, Object failure) {
        synchronized (segment.getMonitor()) {
            segment.setValue(failure); segment.setOwner(null); segment.setState(3); segment.getMonitor().notifyAll();
        }
    }
    private void suspendCallOwned(CallSegment segment) {
        synchronized (segment.getMonitor()) {
            if (segment.getState() != 1 || segment.getOwner() != Thread.currentThread()) return;
            segment.setOwner(null); segment.setState(4); segment.getMonitor().notifyAll();
        }
    }
    private RuntimeException rethrowCallFailure(CallSegment segment) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        Object failure = segment.getValue();
        if (failure instanceof MemoizedGuestFailure saved) throw new GuestException(saved.payload(), saved.location(), saved.someException());
        if (failure instanceof Throwable throwable) throw rethrow(throwable);
        throw fault("Invalid failed call segment");
    }
    @TruffleBoundary private void awaitCallOwner(CallSegment segment) {
        TruffleSafepoint.setBlockedThreadInterruptible(this, (CallSegment waiting) -> {
            GuestThreads.checkpointCurrent(this);
            GuestThreadExtent blocked = null;
            try { synchronized (waiting.getMonitor()) {
                if (waiting.getState() == 1 && waiting.getOwner() != Thread.currentThread()) {
                    if (asyncMode || AstControl.INSTANCE.enabled(this)) {
                        AsyncRequest request = GuestThreads.pollCurrentWithoutYield(this, true);
                        if (request != null) throw new AsyncBlocked(request, this);
                    }
                    blocked = GuestThreads.blocking(GuestThreadStatus.BLACK_HOLE);
                    waiting.getMonitor().wait();
                }
            } } finally { if (blocked != null) blocked.close(); }
        }, segment);
    }

    private Object evaluateOwned(Thunk thunk, SavedGuestContinuation continuation, Object resumeValue) {
        return evaluateOwned(thunk, continuation, resumeValue, metrics);
    }
    private Object evaluateOwned(Thunk thunk, SavedGuestContinuation continuation, Object resumeValue, Metrics metrics) {
        try {
            if (metrics.getEnabled() && continuation == null) {
                RootCallTarget target = thunk.getTarget();
                if (target == null) throw fault("Unevaluated thunk has no body");
                metrics.incrementThunkEvaluations(); metrics.recordThunk(target);
            }
            Object returned;
            if (continuation == null) {
                try {
                    RootCallTarget target = thunk.getTarget();
                    if (target == null) throw fault("Unevaluated thunk has no body");
                    returned = calls.call(target, thunk.getEnvironment(), metrics);
                } catch (TailCall tail) { tailCallProfile.enter(); returned = trampoline.execute(tail, metrics); }
            } else {
                // A saved logical activation may move between host carrier threads.
                MaskingState ambient = SynchronousMasking.current(this);
                StackAnnotationState annotations = StackAnnotations.current(this);
                try {
                    try { returned = continuation.continueWith(resumeValue); }
                    catch (TailCall tail) { tailCallProfile.enter(); returned = trampoline.execute(tail, metrics); }
                } finally {
                    SynchronousMasking.set(this, ambient); StackAnnotations.set(this, annotations);
                }
            }
            Object result = returned instanceof TailYield tail ? tail.getContinuation() :
                returned instanceof AstTailYield tail ? tail.getContinuation() : returned;
            if (result instanceof SavedGuestContinuation || result instanceof ContinuationResult) {
                // Resolve the cold bytecode view only after the suspension profile.
                suspensionProfile.enter();
                SavedGuestContinuation saved = savedGuestContinuation(result);
                if (saved == null) CompilerDirectives.transferToInterpreter();
                java.util.Objects.requireNonNull(saved);
                if (saved.getYielded() instanceof DelimitedCut) throw fault("control0# cannot capture across a thunk update");
                Object expectedRoot = continuation != null ? continuation.getSourceRoot() :
                    thunk.getTarget() == null ? null : thunk.getTarget().getRootNode();
                if (!(returned instanceof TailYield) && !(returned instanceof AstTailYield) && !sameContinuationBody(saved.getSourceRoot(), expectedRoot))
                    throw new IllegalStateException("Nested guest yield has no captured caller segment");
                AsyncRequest request = asyncRequest(saved);
                publishContinuation(thunk, saved);
                throw new ThunkSuspended(thunk, request, stackSpill(saved));
            }
            if (result instanceof Thunk) throw fault("Thunk target violated WHNF convention");
            synchronized (thunk.getMonitor()) {
                thunk.setValue(result); thunk.setTarget(null); thunk.setEnvironment(null);
                thunk.setOwner(null); thunk.setState(2); thunk.notifyUpdate();
            }
            return result;
        } catch (DelimitedCut cut) {
            RuntimeFault failure = new RuntimeFault("control0# cannot capture across a thunk update");
            publishFailure(thunk, failure); throw failure;
        } catch (ThunkSuspended failure) {
            if (failure.getThunk() != thunk) suspendOwned(thunk);
            throw failure;
        } catch (AsyncThunkUnwind failure) {
            suspendOwned(thunk); throw failure;
        } catch (GuestException failure) {
            publishFailure(thunk, new MemoizedGuestFailure(failure.getPayload(),
                failure.getLocation() == null ? this : failure.getLocation(), failure.getSomeException()));
            throw failure;
        } catch (RuntimeFault failure) {
            publishFailure(thunk, failure); throw failure;
        } catch (Throwable failure) {
            // Any host unwind can follow effects; never reset to unevaluated and replay.
            suspendOwned(thunk); throw failure;
        }
    }
    private boolean sameContinuationBody(Object actual, Object expected) {
        return actual == expected || actual instanceof GuestRoot left && expected instanceof GuestRoot right && left.isSelf(right.getCallTarget());
    }
    private void publishContinuation(Thunk thunk, SavedGuestContinuation continuation) {
        // Thread-local side effects cannot unwind between capture and ownership release.
        TruffleSafepoint safepoint = TruffleSafepoint.getCurrent();
        boolean previous = safepoint.setAllowSideEffects(false);
        try {
            synchronized (thunk.getMonitor()) {
                PendingWait pending = PendingWait.of(continuation);
                if (pending != null) pending.retainUpdate(thunk, continuation.getIdentity());
                thunk.setValue(continuation.getIdentity()); thunk.setTarget(null); thunk.setEnvironment(null);
                thunk.setOwner(null); thunk.setState(5); thunk.notifyUpdate();
            }
        } finally { safepoint.setAllowSideEffects(previous); }
    }
    // Keep cold failure monitor/unwind edges out of ordinary compiled force sites.
    @TruffleBoundary private void publishFailure(Thunk thunk, Object failure) {
        synchronized (thunk.getMonitor()) {
            thunk.setValue(failure); thunk.setTarget(null); thunk.setEnvironment(null);
            thunk.setOwner(null); thunk.setState(3); thunk.notifyUpdate();
        }
    }
    @TruffleBoundary private void suspendOwned(Thunk thunk) {
        synchronized (thunk.getMonitor()) {
            if (thunk.getState() != 1 || thunk.getOwner() != Thread.currentThread()) return;
            thunk.setOwner(null); thunk.setState(4); thunk.notifyUpdate();
        }
    }
    private RuntimeException rethrowFailure(Thunk thunk) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        Object failure = thunk.getValue();
        if (failure instanceof MemoizedGuestFailure saved) throw new GuestException(saved.payload(), saved.location(), saved.someException());
        if (failure instanceof Throwable throwable) throw rethrow(throwable);
        throw fault("Invalid failed thunk");
    }
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException rethrow(Throwable failure) throws E { throw (E) failure; }

    final class DemandWait implements PendingWait.Operation {
        private final Thunk thunk;
        private boolean registered;
        private PendingWait suspension;
        DemandWait(Thunk thunk) { this.thunk = thunk; }
        private boolean readyLocked() {
            int state = thunk.getState();
            PendingWait pending = state == 5 ? PendingWait.of(thunk.getValue()) : null;
            return state != 1 && (pending == null || pending.abandoned());
        }
        void changedLocked() { if (readyLocked() && suspension != null) suspension.wake(); }
        private Object await() throws InterruptedException {
            GuestThreads.checkpointCurrent(Force.this);
            while (true) {
                GuestThreadExtent blocked = null;
                try { synchronized (thunk.getMonitor()) {
                    if (!registered) {
                        if (thunk.demandWaiters == null) thunk.demandWaiters = new java.util.ArrayList<>();
                        thunk.demandWaiters.add(this); registered = true;
                    }
                    if (readyLocked()) return Unit.INSTANCE;
                    AsyncRequest request = asyncMode || AstControl.captures(Force.this) ?
                        GuestThreads.pollCurrentWithoutYield(Force.this, true) : null;
                    if (request != null) { cancel(); throw new AsyncBlocked(request, Force.this); }
                    if (suspension == null) suspension = PendingWait.capture(this, Force.this);
                    if (suspension != null && GuestThreads.suspendingCurrent(Force.this) == suspension.owner) throw suspension;
                    blocked = GuestThreads.blocking(GuestThreadStatus.BLACK_HOLE);
                    thunk.awaitUpdate();
                } } finally { if (blocked != null) blocked.close(); }
            }
        }
        @Override public Object resume() {
            boolean captured = false;
            try { return TruffleSafepoint.setBlockedThreadInterruptibleFunction(Force.this, DemandWait::await, this); }
            catch (PendingWait cut) { captured = true; throw cut; }
            finally { if (!captured) cancel(); }
        }
        @Override public boolean cancel() {
            synchronized (thunk.getMonitor()) {
                if (!registered) return false;
                thunk.demandWaiters.remove(this);
                if (thunk.demandWaiters.isEmpty()) thunk.demandWaiters = null;
                registered = false; return true;
            }
        }
        @Override public boolean ready() { synchronized (thunk.getMonitor()) { return readyLocked(); } }
        @Override public GuestThreadStatus status() { return GuestThreadStatus.BLACK_HOLE; }
    }
    @TruffleBoundary private void awaitOwner(Thunk thunk) { new DemandWait(thunk).resume(); }

}
