// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import thc.Language;
import static thc.runtime.RuntimeServiceStatus.fault;
import static thc.runtime.DelimitedContinuations.copyContinuationFrame;

/** Immutable multi-shot image: each invocation receives its own copied frame graph. */
public final class DelimitedStack {
    private final Language.State owner;
    private final TupleShape inputShape, outputShape;
    private final MaskingState initialMask;
    private final StackAnnotationState initialAnnotations;
    private final List<DelimitedFrame> frames;
    public DelimitedStack(DelimitedCut cut, TupleShape outputShape) {
        owner = cut.getTag().getOwner(); inputShape = cut.getInputShape(); this.outputShape = outputShape;
        initialMask = cut.getCapturedMask(); initialAnnotations = cut.getCapturedAnnotations();
        IdentityHashMap<MaterializedFrame, MaterializedFrame> copies = new IdentityHashMap<>();
        ArrayList<DelimitedFrame> image = new ArrayList<>();
        for (DelimitedFrame entry : cut.getFrames())
            image.add(new DelimitedFrame(copies.computeIfAbsent(entry.getFrame(), DelimitedContinuations::copyContinuationFrame), entry.getStep()));
        frames = List.copyOf(image);
    }
    @TruffleBoundary(transferToInterpreterOnException = false)
    public Object resume(DelimitedActionSite site, MaterializedFrame frame, Object action) {
        if (Language.currentState(site) != owner) throw fault("Continuation belongs to another context");
        MaskingState ambient = SynchronousMasking.current(site);
        StackAnnotationState ambientAnnotations = StackAnnotations.current(site);
        StackAnnotationState outsideAnnotations = null;
        for (DelimitedFrame entry : frames)
            if (entry.getStep() instanceof DelimitedAnnotationStep annotation) outsideAnnotations = annotation.getPrior();
        IdentityHashMap<StackAnnotationState, StackAnnotationState> annotationCopies = new IdentityHashMap<>();
        IdentityHashMap<MaterializedFrame, MaterializedFrame> copies = new IdentityHashMap<>();
        ArrayList<DelimitedFrame> active = new ArrayList<>();
        DelimitedStep outerMask = null;
        for (DelimitedFrame entry : frames) {
            DelimitedStep step = entry.getStep() instanceof DelimitedAnnotationStep annotation ?
                annotation.rebase(java.util.Objects.requireNonNull(outsideAnnotations), ambientAnnotations, annotationCopies) : entry.getStep();
            active.add(new DelimitedFrame(copies.computeIfAbsent(entry.getFrame(), DelimitedContinuations::copyContinuationFrame), step));
            if (step instanceof DelimitedMaskStep) outerMask = step;
        }
        try {
            if (outerMask != null) SynchronousMasking.set(site, initialMask);
            if (outsideAnnotations != null) StackAnnotations.set(site, initialAnnotations.rebase(outsideAnnotations, ambientAnnotations, annotationCopies));
            DelimitedResume input;
            try { input = new DelimitedResume(site.invoke(frame, action, new Object[] {thc.runtime.Unit.INSTANCE}, inputShape)); }
            catch (GuestException failure) { input = new DelimitedResume(null, failure); }
            catch (AsyncDelivery failure) { input = new DelimitedResume(null, failure); }
            catch (AstCapture cut) { input = new DelimitedResume(null, DelimitedControl.asyncFailure(cut, site)); }
            catch (DelimitedCut cut) { return transfer(site, cut, active, ambient, outerMask); }
            return run(site, active, input, ambient, outerMask);
        } finally { SynchronousMasking.set(site, ambient); StackAnnotations.set(site, ambientAnnotations); }
    }
    private Object run(DelimitedActionSite site, List<DelimitedFrame> active, DelimitedResume initial, MaskingState ambient, DelimitedStep outerMask) {
        DelimitedResume input = initial;
        for (int index = 0; index < active.size(); index++) {
            DelimitedFrame entry = active.get(index);
            DelimitedResume current = input;
            try {
                Object answer;
                try { answer = entry.getStep().resume(entry.getFrame(), current, ambient, outerMask); }
                catch (AstCapture cut) { answer = site.finishCapture(entry.getFrame(), null, cut); }
                input = new DelimitedResume(entry.getStep().finish(answer, site));
            } catch (GuestException failure) { input = new DelimitedResume(null, failure); }
            catch (AsyncDelivery failure) { input = new DelimitedResume(null, failure); }
            catch (AstCapture cut) { input = new DelimitedResume(null, DelimitedControl.asyncFailure(cut, site)); }
            catch (DelimitedCut cut) { return transfer(site, cut, active.subList(index + 1, active.size()), ambient, outerMask); }
            catch (ControlFlowException flow) { return transferControl(site, flow, active.subList(index, active.size()), ambient, outerMask); }
        }
        return input.get();
    }
    private Object transferControl(DelimitedActionSite site, ControlFlowException flow, List<DelimitedFrame> remaining, MaskingState ambient, DelimitedStep outerMask) {
        int ownerIndex = -1;
        for (int i = 0; i < remaining.size(); i++)
            if (remaining.get(i).getStep() instanceof DelimitedTransferStep step && step.accepts(flow)) { ownerIndex = i; break; }
        if (ownerIndex < 0) throw flow;
        DelimitedFrame entry = remaining.get(ownerIndex);
        DelimitedTransferStep step = (DelimitedTransferStep) entry.getStep();
        for (int i = 0; i < ownerIndex; i++)
            if (remaining.get(i).getStep() instanceof DelimitedAnnotationStep annotation) annotation.unwind();
        List<DelimitedFrame> after = remaining.subList(ownerIndex + 1, remaining.size());
        DelimitedResume input;
        try {
            Object answer;
            try { answer = step.transfer(entry.getFrame(), flow, site); }
            catch (AstCapture cut) { answer = site.finishCapture(entry.getFrame(), null, cut); }
            input = new DelimitedResume(step.finish(answer, site));
        } catch (GuestException failure) { input = new DelimitedResume(null, failure); }
        catch (AsyncDelivery failure) { input = new DelimitedResume(null, failure); }
        catch (AstCapture cut) { input = new DelimitedResume(null, DelimitedControl.asyncFailure(cut, site)); }
        catch (DelimitedCut cut) { return transfer(site, cut, after, ambient, outerMask); }
        catch (ControlFlowException next) { return transferControl(site, next, after, ambient, outerMask); }
        return run(site, after, input, ambient, outerMask);
    }
    private Object transfer(DelimitedActionSite site, DelimitedCut cut, List<DelimitedFrame> remaining, MaskingState ambient, DelimitedStep outerMask) {
        for (int index = 0; index < remaining.size(); index++) {
            DelimitedFrame entry = remaining.get(index);
            DelimitedStep step = entry.getStep();
            if (step instanceof DelimitedPromptStep prompt && prompt.getTag() == cut.getTag()) {
                DelimitedResume input;
                try { input = new DelimitedResume(prompt.handle(entry.getFrame(), cut)); }
                catch (GuestException failure) { input = new DelimitedResume(null, failure); }
                catch (AsyncDelivery failure) { input = new DelimitedResume(null, failure); }
                catch (AstCapture captured) { input = new DelimitedResume(null, DelimitedControl.asyncFailure(captured, site)); }
                catch (DelimitedCut next) { return transfer(site, next, remaining.subList(index + 1, remaining.size()), ambient, outerMask); }
                return run(site, remaining.subList(index + 1, remaining.size()), input, ambient, outerMask);
            }
            if (step instanceof DelimitedMaskStep mask) {
                cut.getFrames().add(new DelimitedFrame(entry.getFrame(), mask.recapture(ambient, outerMask)));
                mask.unwind(ambient, outerMask);
            } else {
                cut.getFrames().add(entry);
                if (step instanceof DelimitedAnnotationStep annotation) annotation.unwind();
            }
        }
        throw cut;
    }
    @TruffleBoundary public Closure closure(Language language, Metrics metrics) {
        return new Closure(null, 2, new DelimitedContinuationRoot(language, this, outputShape, metrics).getCallTarget());
    }
}
