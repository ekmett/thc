// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.runtime.OptimizedOSRLoopNode;
import java.util.*;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import thc.Language;
import static org.junit.jupiter.api.Assertions.*;
import static thc.Main.executionContext;

class ScalarTupleTailCallTest {
    private static List<Object> list(Object... values) { return Arrays.asList(values); }
    private static Map<String,Object> map(Object... pairs) {
        var result = new LinkedHashMap<String,Object>();
        for (int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]);
        return result;
    }
    private final Map<String,Object> scalar = map("kind", "long", "primReps", list("IntRep"), "evaluated", true);
    private final Map<String,Object> closure = map("kind", "closure", "primReps", list("BoxedRep (Just Lifted)"), "evaluated", true);
    private final Map<String,Object> tuple = map("kind", "unknown", "aggregate", "unboxed-tuple", "components", list(scalar, scalar),
        "primReps", list("IntRep", "IntRep"), "evaluated", true);
    private List<Object> variable(String id) { return list("var", id, map("rep", scalar)); }
    private List<Object> literal(long n) { return list("lit", "int", Long.toString(n), map("rep", scalar)); }
    private Map<String,Object> parameter(String id) { return map("id", id, "name", id, "lifted", false, "rep", scalar); }
    private List<Object> apply(List<Object> function, List<List<Object>> args, Map<String,Object> result) {
        return list("app", function, args, Collections.nCopies(args.size(), false), false, false, map("rep", result));
    }
    private List<Object> primitive(String name, List<Object> a, List<Object> b) { return apply(list("prim", name), List.of(a,b), scalar); }
    private List<Object> pack(List<Object> a, List<Object> b) { return apply(list("con", "T2", 2), List.of(a,b), tuple); }
    private Map<String,Object> binding(String id, List<Object> body) {
        var args = List.of(parameter("n"), parameter("x"), parameter("y"), parameter("count"));
        return map("id", id, "name", id, "lifted", true, "rep", closure,
            "expr", list("lam", args, body, map("rep", closure, "resultRep", tuple)));
    }
    private Map<String,Object> module(boolean self) {
        var next = apply(list("var", self ? "worker" : "other", map("rep", closure)), List.of(
            primitive("-#", variable("n"), literal(1)), variable("y"),
            primitive("+#", variable("x"), literal(1)), primitive("+#", variable("count"), literal(1))), tuple);
        var body = list("case", variable("n"), "seen", list(
            list("lit", list("int", "0"), list(), pack(variable("x"), variable("count"))),
            list("default", null, list(), next)), map("rep", tuple, "binder", parameter("seen")));
        return map("instrument", true, "constructors", list(map("id", "T2", "name", "T2", "kind", "unboxed-tuple", "arity", 2)),
            "bindings", list(binding("worker", body), binding("other", pack(variable("x"), variable("count")))));
    }
    private long[] result(GuestRoot root, Object answer) {
        var shape = root.getTupleResult(); var owned = TupleResults.ownedTupleResult(answer, shape);
        return new long[]{shape.getLayout().getLong(owned, 0), shape.getLayout().getLong(owned, 1)};
    }
    private long[] invoke(ExecutableProgram program, long n, long x, long y) {
        Closure function = (Closure) program.entryValue("worker");
        return result((GuestRoot) function.target.getRootNode(), Calls.target(function.target, new Object[]{0L,n,x,y,0L}));
    }
    private long[] expected(long n, long x, long y) {
        for (long i=0;i<n;i++) { long next=y; y=x+1; x=next; }
        return new long[]{x,n};
    }
    @FunctionalInterface interface Action { void run(Language language) throws Exception; }
    private void entered(Action action) throws Exception {
        try (var context=executionContext(false)) {
            context.initialize("thc"); context.enter();
            try { action.run(TruffleLanguage.LanguageReference.create(Language.class).get(null)); }
            finally { context.leave(); }
        }
    }
    private static List<Expr> operands(ScalarTupleTailCall call) {
        var children = new ArrayList<Expr>();
        for (var child : call.getChildren()) if (child instanceof Expr expr) children.add(expr);
        return children;
    }
    @Test void parallelScalarInputsAndTupleResultMatchIndependentModelOnBothBackends() throws Exception {
        entered(language -> {
            for (boolean ast : List.of(true,false)) {
                ExecutableProgram program=ast?new Program(language,module(true)):new BytecodeProgram(language,module(true));
                var root=(GuestRoot)program.entryTarget("worker").getRootNode();
                if(ast)assertNotNull(NodeUtil.findFirstNodeInstance(root,ScalarTupleTailCall.class));
                for(long n:new long[]{0,1,2,9,100})assertArrayEquals(expected(n,Long.MIN_VALUE,Long.MAX_VALUE),invoke(program,n,Long.MIN_VALUE,Long.MAX_VALUE));
                if(ast) {
                    assertEquals(0L,program.diagnostics().get("tailBounces"));
                    assertTrue(NodeUtil.findAllNodeInstances(root,TupleDispatch.class).isEmpty(),"Unused fallback stays cold");
                }
            }
        });
    }
    @Test void firstTupleReturnKeepsTheInstalledSelfLoop() {
        try (var context = Context.newBuilder("thc").allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.OSRCompilationThreshold", "1024").build()) {
            context.initialize("thc");
            context.enter();
            try {
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var input = module(true);
                input.put("instrument", false);
                var program = new Program(language, input);
                var root = program.entryTarget("worker").getRootNode();
                var self = NodeUtil.findFirstNodeInstance(root, SelfRepeater.class);
                var loop = (OptimizedOSRLoopNode) self.getParent();
                // The first long activation reaches OSR before the tuple return has ever run.
                assertArrayEquals(expected(4096, 7, 19), invoke(program, 4096, 7, 19));
                var osr = loop.getCompiledOSRLoop();
                assertNotNull(osr);
                assertTrue(osr.isValidLastTier(), "First tuple return must preserve the installed self loop");
                assertArrayEquals(expected(4097, 19, 7), invoke(program, 4097, 19, 7));
                assertTrue(osr.isValidLastTier());
            } finally { context.leave(); }
        }
    }
    @Test void nonselfTargetKeepsOrdinaryTupleDispatchAndResultDestination() throws Exception {
        entered(language -> {
            for (boolean partial : List.of(false, true)) {
                var input = module(false);
                if (partial) {
                    @SuppressWarnings("unchecked")
                    var bindings = (List<Map<String, Object>>) input.get("bindings");
                    @SuppressWarnings("unchecked")
                    var lambda = (List<Object>) bindings.get(1).get("expr");
                    lambda.set(1, List.of(parameter("prefix"), parameter("n"), parameter("x"), parameter("y"), parameter("count")));
                }
                var program = new Program(language, input);
                var root = program.entryTarget("worker").getRootNode();
                assertNotNull(NodeUtil.findFirstNodeInstance(root, ScalarTupleTailCall.class));
                if (partial) {
                    var pap = ((Closure) program.entryValue("other")).pap(new Object[]{99L});
                    var owner = NodeUtil.findFirstNodeInstance(root, ScalarTupleTailCall.class);
                    var value = operands(owner).getFirst().getChildren().iterator().next();
                    value.replace(new Literal(pap).proven(CoreRepresentations.parse(closure)));
                }
                for (int i = 0; i < 3; i++) assertArrayEquals(new long[]{19, 1}, invoke(program, 1, 7, 19));
                assertFalse(NodeUtil.findAllNodeInstances(root, TupleDispatch.class).isEmpty());
            }
        });
    }
    @Test void missingFormalLongProofKeepsOrdinaryTupleLowering() throws Exception {
        entered(language -> {
            var input = module(true);
            @SuppressWarnings("unchecked") var definitions = (List<Map<String, Object>>) input.get("bindings");
            @SuppressWarnings("unchecked") var lambda = (List<Object>) definitions.getFirst().get("expr");
            @SuppressWarnings("unchecked") var parameters = (List<Map<String, Object>>) lambda.get(1);
            parameters.get(1).put("rep", map("kind", "unknown", "evaluated", false));
            var program = new Program(language, input);
            var root = program.entryTarget("worker").getRootNode();
            assertNull(NodeUtil.findFirstNodeInstance(root, ScalarTupleTailCall.class));
            assertNotNull(NodeUtil.findFirstNodeInstance(root, TupleApplication.class));
            assertArrayEquals(expected(9, Long.MIN_VALUE, Long.MAX_VALUE), invoke(program, 9, Long.MIN_VALUE, Long.MAX_VALUE));
        });
    }
    @Test void savedTypedPreparationResumesWithoutBoxedArgumentEntriesOrReplay() throws Exception {
        entered(language -> {
            var program=new Program(language,module(true));
            Closure function=(Closure)program.entryValue("worker");
            var root=(FunctionRoot)function.target.getRootNode();
            var transfer=NodeUtil.findFirstNodeInstance(root,ScalarTupleTailCall.class);
            Expr value=operands(transfer).get(1);
            class CutSecond extends Expr {
                @Child private Expr delegate;
                int typed, generic;
                CutSecond(Expr delegate){this.delegate=delegate;setRepresentation(delegate.getRepresentation());}
                @Override public Object execute(VirtualFrame frame){generic++;return delegate.execute(frame);}
                @Override public long executeLong(VirtualFrame frame)throws UnexpectedResultException {
                    typed++;
                    if(typed==2)throw new AstCapture(Unit.INSTANCE,SynchronousMasking.current(this));
                    return delegate.executeLong(frame);
                }
            }
            var cut=new CutSecond(value);value.replace(cut);
            var saved=assertInstanceOf(AstContinuation.class,Calls.target(function.target,new Object[]{0L,3L,7L,19L,0L}));
            assertEquals(2,cut.typed);assertEquals(0,cut.generic);
            assertArrayEquals(expected(3,7,19),result(root,saved.continueWith(1L)));
            assertEquals(3,cut.typed);assertEquals(0,cut.generic);
            assertThrows(RuntimeFault.class,()->saved.continueWith(1L));
        });
    }
    @Test void savedFunctionPreparationRetainsClosureValidationAndDoesNotReplay() throws Exception {
        entered(language -> {
            for (boolean valid : List.of(true, false)) {
                var program = new Program(language, module(true));
                Closure function = (Closure) program.entryValue("worker");
                var root = (FunctionRoot) function.target.getRootNode();
                var transfer = NodeUtil.findFirstNodeInstance(root, ScalarTupleTailCall.class);
                Expr original = (Expr) operands(transfer).getFirst().getChildren().iterator().next();
                class CutFunction extends Expr {
                    @Child private Expr delegate;
                    int entries;
                    CutFunction(Expr delegate) { this.delegate = delegate; setRepresentation(delegate.getRepresentation()); }
                    @Override public Object execute(VirtualFrame frame) {
                        entries++;
                        if (entries == 2) throw new AstCapture(Unit.INSTANCE, SynchronousMasking.current(this));
                        return delegate.execute(frame);
                    }
                    @Override public Closure executeClosure(VirtualFrame frame) throws UnexpectedResultException {
                        return RuntimeTypesGen.expectClosure(execute(frame));
                    }
                }
                Expr operand = operands(transfer).get(1);
                class CountOperand extends Expr {
                    @Child private Expr delegate;
                    int entries;
                    CountOperand(Expr delegate) { this.delegate = delegate; setRepresentation(delegate.getRepresentation()); }
                    @Override public Object execute(VirtualFrame frame) { return delegate.execute(frame); }
                    @Override public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
                        entries++; return delegate.executeLong(frame);
                    }
                }
                var counter = new CountOperand(operand); operand.replace(counter);
                var capture = new CutFunction(original); original.replace(capture);
                var saved = assertInstanceOf(AstContinuation.class,
                    Calls.target(function.target, new Object[]{0L, 3L, 7L, 19L, 0L}));
                assertEquals(2, capture.entries);
                if (valid) assertArrayEquals(expected(3, 7, 19), result(root, saved.continueWith(function)));
                else assertEquals("Application of a non-function", assertThrows(RuntimeFault.class, () -> saved.continueWith(7L)).getMessage());
                assertEquals(valid ? 3 : 2, capture.entries);
                assertEquals(valid ? 3 : 1, counter.entries, "Invalid saved callee must fail before later operands run");
            }
        });
    }

    @Test void typedTupleOwnerResumesSameDelimitedImageTwice() throws Exception {
        entered(language -> {
            var proof = CoreRepresentations.parse(scalar);
            var resultProof = CoreRepresentations.parse(tuple);
            var shape = new TupleShape(resultProof, language);
            var slots = new FrameLayout();
            int n = slots.bind("n"), x = slots.bind("x"), y = slots.bind("y"), count = slots.bind("count");
            int seen = slots.bind("seen");
            int[] arguments = {n, x, y, count};
            int[] output = {slots.bind("out x"), slots.bind("out count")};
            var metrics = new Metrics(true);
            Closure[] functionHolder = {null};
            Expr callee = new Expr() {
                { setRepresentation(CoreRepresentations.parse(closure)); }
                @Override public Object execute(VirtualFrame frame) { return functionHolder[0]; }
            };
            var layout = new AstSelfLayout(null, new int[0], arguments,
                new CoreRepresentation[]{proof, proof, proof, proof}, new boolean[4]);
            Expr nextN = new Primitive("-#", new Expr[]{new LocalRead(n, false).proven(proof), new Literal(1L)});
            var transfer = new ScalarTupleTailCall(layout, callee, new Expr[]{
                nextN, new LocalRead(y, false).proven(proof),
                new Primitive("+#", new Expr[]{new LocalRead(x, false).proven(proof), new Literal(1L)}),
                new Primitive("+#", new Expr[]{new LocalRead(count, false).proven(proof), new Literal(1L)})}, slots, shape, metrics);
            var body = new Case(new LocalRead(n, false).proven(proof), seen, new Alternative[]{
                new Alternative(Alternative.LITERAL_ALTERNATIVE, 0L, new int[0], new TupleConstruct(shape,
                    new Expr[]{new LocalRead(x, false).proven(proof), new LocalRead(count, false).proven(proof)})),
                new Alternative(Alternative.DEFAULT_ALTERNATIVE, null, new int[0], transfer)}, metrics, proof, true);
            var root = new FunctionRoot(language, slots.build(), "active typed tuple loop", null, new int[0], arguments,
                new int[]{0, 1, 2, 3}, body, metrics, new CoreRepresentation[]{proof, proof, proof, proof}, resultProof,
                null, new boolean[4], null, shape, output, null, true, new int[0][], true, FunctionRootRole.FUNCTION, false);
            root.configureEagerAsyncPolls(false);
            Closure function = new Closure(null, 4, root.getCallTarget()); functionHolder[0] = function;
            root.getCallTarget();
            Expr original = operands(transfer).get(4);
            class CutSecond extends Expr {
                @Child private Expr delegate;
                int typed, generic;
                MaterializedFrame saved;
                CutSecond(Expr delegate) { this.delegate = delegate; setRepresentation(delegate.getRepresentation()); }
                @Override public Object execute(VirtualFrame frame) { generic++; return delegate.execute(frame); }
                @Override public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
                    typed++;
                    if (typed == 2) {
                        saved = frame.materialize();
                        throw new DelimitedCut(new PromptTag(Language.currentState()), null, null,
                            SynchronousMasking.current(this), this);
                    }
                    return delegate.executeLong(frame);
                }
            }
            var capture = new CutSecond(original); original.replace(capture);
            var cut = assertThrows(DelimitedCut.class,
                () -> Calls.target(function.target, new Object[]{0L, 3L, 7L, 19L, 0L}));
            var copies = new ArrayList<MaterializedFrame>();
            cut.append(capture.saved, (saved, input, ambient, outer) -> {
                Object answer = input.get(); copies.add(saved);
                for (int i = 0; i < saved.getFrameDescriptor().getNumberOfSlots(); i++)
                    if (String.valueOf(saved.getFrameDescriptor().getSlotName(i)).startsWith("<tuple self argument "))
                        assertEquals(com.oracle.truffle.api.frame.FrameSlotKind.Illegal.tag, saved.getTag(i));
                return answer;
            });
            var image = new DelimitedStack(cut, null);
            class Owner extends GuestRoot {
                @Child private DelimitedActionSite site = new DelimitedActionSite(language, new Metrics(false));
                long value;
                Owner() { super(language, new FrameLayout().build()); }
                @Override public long bloom(VirtualFrame frame) { return 0L; }
                @Override public Object execute(VirtualFrame frame) { return value; }
                Object resume(long input) {
                    value = input;
                    return image.resume(site, com.oracle.truffle.api.Truffle.getRuntime().createMaterializedFrame(
                        new Object[]{0L}, getFrameDescriptor()), new Closure(null, 1, getCallTarget()));
                }
            }
            var continuationOwner = new Owner(); continuationOwner.getCallTarget();
            assertArrayEquals(new long[]{20, 1}, result(root, continuationOwner.resume(0L)));
            assertArrayEquals(new long[]{20, 2}, result(root, continuationOwner.resume(1L)));
            // Two evaluations precede capture, then each image runs the remaining iteration once.
            assertEquals(4, capture.typed); assertEquals(0, capture.generic);
            assertEquals(2, copies.size()); assertNotSame(copies.get(0), copies.get(1));
            assertNotSame(capture.saved, copies.get(0)); assertNotSame(capture.saved, copies.get(1));
        });
    }

}
