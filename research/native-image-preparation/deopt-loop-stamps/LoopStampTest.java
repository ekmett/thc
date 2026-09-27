/*
 * SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
 */

import java.lang.reflect.Proxy;
import com.oracle.svm.core.graal.isolated.ClientHandle;
import com.oracle.svm.hosted.nodes.DeoptProxyNode;
import jdk.graal.compiler.bytecode.BytecodeStream;
import jdk.graal.compiler.bytecode.ResolvedJavaMethodBytecode;
import jdk.graal.compiler.core.common.type.Stamp;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.StampPair;
import jdk.graal.compiler.core.common.type.TypeReference;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.java.BciBlockMapping;
import jdk.graal.compiler.java.FrameStateBuilder;
import jdk.graal.compiler.java.LocalLiveness;
import jdk.graal.compiler.nodes.*;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.gc.NoBarrierSet;
import jdk.graal.compiler.nodes.java.MonitorIdNode;
import jdk.graal.compiler.nodes.java.StoreIndexedNode;
import jdk.graal.compiler.nodes.type.StampTool;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.word.WordOperationPlugin;
import jdk.graal.compiler.word.WordTypes;
import jdk.vm.ci.code.BailoutException;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.runtime.JVMCI;

/** Compiler-API controls, not a machine-code deoptimization or native-image test. */
public final class LoopStampTest {
    private static int checks;

    // Real bytecode supplies liveness. Local 0 is unchanged; 2 and 3 are changed.
    public static Object fixture(ClientHandle<?>[] handles, int count) {
        Object changing = new String[1];
        for (int index = 0; index < count; index++) {
            handles[index] = null;
            changing = (index & 1) == 0 ? new Object[1] : null;
        }
        return changing;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static ParameterNode parameter(StructuredGraph graph, int index, Stamp stamp) {
        return graph.addWithoutUnique(new ParameterNode(index, StampPair.createSingle(stamp)));
    }

    private static Stamp objectStamp(MetaAccessProvider meta, Class<?> type) {
        return StampFactory.objectNonNull(TypeReference.createExactTrusted(meta.lookupJavaType(type)));
    }

    private static void rejects(Runnable operation, Class<? extends Throwable> expected, String label) {
        try {
            operation.run();
        } catch (Throwable failure) {
            check(expected.isInstance(failure), label + ": " + failure);
            return;
        }
        throw new AssertionError(label + " did not reject");
    }

    private static void run(boolean preserveUnchanged) throws Exception {
        var meta = JVMCI.getRuntime().getHostJVMCIBackend().getMetaAccess();
        var method = meta.lookupJavaMethod(LoopStampTest.class.getMethod("fixture", ClientHandle[].class, int.class));
        var code = new ResolvedJavaMethodBytecode(method);
        var options = new OptionValues(OptionValues.newOptionMap());
        try (var debug = DebugContext.disabled(options)) {
            var blocks = BciBlockMapping.create(new BytecodeStream(code.getCode()), code, options, debug, false);
            check(blocks.getLoopCount() == 1, "one fixture loop");
            var liveness = LocalLiveness.compute(debug, new BytecodeStream(code.getCode()), blocks,
                    code.getMaxLocals(), blocks.getLoopCount(), false);
            int loopId = blocks.getLoopHeaders()[0].getLoopId();
            check(!liveness.localIsChangedInLoop(loopId, 0), "unchanged word-array local");
            check(!liveness.localIsChangedInLoop(loopId, 1), "unchanged count local");
            check(liveness.localIsChangedInLoop(loopId, 2), "changed reference local");
            check(liveness.localIsChangedInLoop(loopId, 3), "changed induction local");
            var graph = new StructuredGraph.Builder(options, debug).method(method).build();
            var state = new FrameStateBuilder(null, method, graph);
            var handles = parameter(graph, 0, objectStamp(meta, ClientHandle[].class));
            var count = parameter(graph, 1, StampFactory.forInteger(JavaKind.Int, 1, 7));
            var changing = parameter(graph, 2, objectStamp(meta, String[].class));
            var index = ConstantNode.forInt(0, graph);
            state.storeLocal(0, JavaKind.Object, handles);
            state.storeLocal(1, JavaKind.Int, count);
            state.storeLocal(2, JavaKind.Object, changing);
            state.storeLocal(3, JavaKind.Int, index);
            state.push(JavaKind.Object, handles);
            state.pushLock(changing, graph.add(new MonitorIdNode(0)));
            var loop = graph.add(new LoopBeginNode());
            state.insertLoopPhis(liveness, loopId, loop, true, preserveUnchanged);
            var arrayPhi = (ValuePhiNode) state.loadLocal(0, JavaKind.Object);
            var changedPhi = (ValuePhiNode) state.loadLocal(2, JavaKind.Object);
            for (int i = 0; i < 4; i++) {
                var local = state.loadLocal(i, (i & 1) == 0 ? JavaKind.Object : JavaKind.Int);
                check(local instanceof ValuePhiNode, "forced phi retained: " + i);
            }
            check(arrayPhi.valueAt(0) == handles, "incoming array identity retained");
            check(arrayPhi.stamp(NodeView.DEFAULT).equals(preserveUnchanged
                    ? handles.stamp(NodeView.DEFAULT) : handles.stamp(NodeView.DEFAULT).unrestricted()),
                    "unchanged array stamp follows hook");
            check(state.loadLocal(1, JavaKind.Int).stamp(NodeView.DEFAULT).equals(preserveUnchanged
                    ? count.stamp(NodeView.DEFAULT) : count.stamp(NodeView.DEFAULT).unrestricted()),
                    "unchanged numeric stamp follows hook");
            check(changedPhi.stamp(NodeView.DEFAULT).equals(changing.stamp(NodeView.DEFAULT).unrestricted()),
                    "changed reference remains unrestricted");
            check(state.loadLocal(3, JavaKind.Int).stamp(NodeView.DEFAULT).equals(index.stamp(NodeView.DEFAULT).unrestricted()),
                    "changed induction remains unrestricted");
            check(state.pop(JavaKind.Object).stamp(NodeView.DEFAULT).equals(handles.stamp(NodeView.DEFAULT).unrestricted()),
                    "stack remains unrestricted");
            check(state.popLock().stamp(NodeView.DEFAULT).equals(changing.stamp(NodeView.DEFAULT).unrestricted()),
                    "monitor remains unrestricted");

            // Changed-loop inputs may widen to Object[] or null; never retain String[].
            var objects = parameter(graph, 4, objectStamp(meta, Object[].class));
            changedPhi.addInput(objects);
            changedPhi.addInput(ConstantNode.defaultForKind(JavaKind.Object, graph));
            changedPhi.inferStamp();
            check(!changedPhi.stamp(NodeView.DEFAULT).equals(changing.stamp(NodeView.DEFAULT)),
                    "changed reference accepts broader backedges");
            check(changedPhi.stamp(NodeView.DEFAULT).meet(objects.stamp(NodeView.DEFAULT)).equals(changedPhi.stamp(NodeView.DEFAULT)),
                    "changed reference includes Object[] backedge");
            check(changedPhi.stamp(NodeView.DEFAULT).meet(StampFactory.alwaysNull()).equals(changedPhi.stamp(NodeView.DEFAULT)),
                    "changed reference includes null backedge");
            var beforeProxy = state.loadLocal(0, JavaKind.Object);
            state.insertProxies(value -> graph.addOrUniqueWithInputs(DeoptProxyNode.create(value, graph.start(), 1)));
            var firstProxy = (DeoptProxyNode) state.loadLocal(0, JavaKind.Object);
            check(firstProxy.getOriginalNode() == beforeProxy, "first deopt reload identity retained");
            check(firstProxy.stamp(NodeView.DEFAULT).equals(beforeProxy.stamp(NodeView.DEFAULT)), "proxy preserves stamp");
            state.insertProxies(value -> graph.addOrUniqueWithInputs(DeoptProxyNode.create(value, graph.start(), 2)));
            var secondProxy = (DeoptProxyNode) state.loadLocal(0, JavaKind.Object);
            check(secondProxy != firstProxy && secondProxy.getOriginalNode() == firstProxy,
                    "distinct deopt points retain proxy chain");

            var plugin = new WordOperationPlugin(null, null, new WordTypes(meta, JavaKind.Long), new NoBarrierSet());
            var context = (GraphBuilderContext) Proxy.newProxyInstance(LoopStampTest.class.getClassLoader(),
                    new Class<?>[]{GraphBuilderContext.class}, (proxy, called, args) -> switch (called.getName()) {
                        case "add" -> graph.addWithoutUnique((Node) args[0]);
                        case "getCode" -> code;
                        case "bci" -> blocks.getLoopHeaders()[0].getStartBci();
                        case "bailout" -> new BailoutException((String) args[0]);
                        default -> throw new AssertionError("Unexpected graph context call: " + called);
                    });
            var word = ConstantNode.forLong(1, graph);
            Runnable store = () -> plugin.handleStoreIndexed(context, secondProxy, index, null, null, JavaKind.Object, word);
            if (preserveUnchanged) {
                check(StampTool.typeOrNull(secondProxy).equals(meta.lookupJavaType(ClientHandle[].class)),
                        "word-array type survives phi and both deopt proxies");
                store.run();
                check(graph.getNodes().filter(StoreIndexedNode.class).count() == 1, "primitive word-array store emitted");
            } else {
                check(StampTool.typeOrNull(secondProxy) == null, "stock forced phi loses word-array type");
                rejects(store, NullPointerException.class, "original stock word-array failure");
            }
            rejects(() -> plugin.handleStoreIndexed(context, objects, index, null, null, JavaKind.Object, word),
                    BailoutException.class, "word into object array");
            rejects(() -> plugin.handleStoreIndexed(context, handles, index, null, null, JavaKind.Object, objects),
                    BailoutException.class, "object into word array");
            System.out.println((preserveUnchanged ? "candidate" : "stock") + " graph controls passed");
        }
    }

    public static void main(String[] args) throws Exception {
        run(false);
        run(true);
        System.out.println("PASS " + checks + " graph checks (not native-image execution)");
    }
}
