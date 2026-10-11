// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
package com.oracle.svm.core.jam;

import java.util.Map;
import static jdk.graal.compiler.core.common.spi.ForeignCallDescriptor.CallSideEffect.HAS_SIDE_EFFECT;
import com.oracle.svm.core.graal.meta.SubstrateForeignCallsProvider;
import com.oracle.svm.core.snippets.SnippetRuntime;
import com.oracle.svm.core.snippets.SnippetRuntime.SubstrateForeignCallDescriptor;
import com.oracle.svm.core.snippets.SubstrateForeignCallTarget;
import com.oracle.svm.shared.Uninterruptible;
import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;
import jdk.graal.compiler.graph.Node.ConstantNodeParameter;
import jdk.graal.compiler.graph.Node.NodeIntrinsic;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;
import org.graalvm.word.LocationIdentity;
import org.graalvm.word.Pointer;
import com.oracle.svm.core.graal.snippets.NodeLoweringProvider;
import com.oracle.svm.core.graal.snippets.SubstrateTemplates;
import jdk.graal.compiler.api.replacements.Snippet;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.api.replacements.Snippet.ConstantParameter;
import jdk.graal.compiler.nodes.memory.address.AddressNode.Address;
import jdk.graal.compiler.word.WordCastNode;
import static jdk.graal.compiler.replacements.gc.WriteBarrierSnippets.getPointerToFirstArrayElement;
import jdk.graal.compiler.nodes.extended.FixedValueAnchorNode;
import jdk.graal.compiler.nodes.gc.SerialArrayRangeWriteBarrierNode;
import jdk.graal.compiler.nodes.gc.SerialWriteBarrierNode;
import jdk.graal.compiler.nodes.memory.address.OffsetAddressNode;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.util.Providers;
import jdk.graal.compiler.replacements.SnippetTemplate;
import jdk.graal.compiler.replacements.SnippetTemplate.Arguments;
import jdk.graal.compiler.replacements.SnippetTemplate.SnippetInfo;
import jdk.graal.compiler.replacements.Snippets;

/** Record exact source slots; holder metadata preserves Java reference policy. */
final class JamBarrierSnippets extends SubstrateTemplates implements Snippets {
    private static final SubstrateForeignCallDescriptor REMEMBER = SnippetRuntime.findForeignCall(JamBarrierSnippets.class, "remember", HAS_SIDE_EFFECT, LocationIdentity.any());

    private static final SubstrateForeignCallDescriptor RECORD_START = SnippetRuntime.findForeignCall(JamBarrierSnippets.class, "recordStartNative", HAS_SIDE_EFFECT, LocationIdentity.any());

    @Uninterruptible(reason = Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    static void recordStart(Object object) { call(RECORD_START, object); }

    @NodeIntrinsic(ForeignCallNode.class)
    private static native void call(@ConstantNodeParameter ForeignCallDescriptor descriptor, Object object);

    @SubstrateForeignCallTarget(stubCallingConvention = false, fullyUninterruptible = true)
    @Uninterruptible(reason = "Publish a fully formatted object before a safepoint.")
    private static void recordStartNative(Object object) {
        JamHeap heap = JamHeap.get();
        JamNative.recordStart(heap.nativeHeap(), heap.encode(object));
    }

    static void registerForeignCalls(SubstrateForeignCallsProvider calls) {
        calls.register(REMEMBER, RECORD_START);
    }

    @NodeIntrinsic(ForeignCallNode.class)
    private static native void call(@ConstantNodeParameter ForeignCallDescriptor descriptor, Object owner, Pointer first, long count, int stride);

    @SubstrateForeignCallTarget(stubCallingConvention = false, fullyUninterruptible = true)
    @Uninterruptible(reason = "Publish exact slots before the mutator can reach a safepoint.")
    private static void remember(Object owner, Pointer first, long count, int stride) {
        JamHeap.get().remember(owner, first, count, stride, true);
    }

    private final SnippetInfo postWrite;
    private final SnippetInfo postRange;

    @SuppressWarnings("this-escape")
    JamBarrierSnippets(OptionValues options, Providers providers) {
        super(options, providers);
        postWrite = snippet(providers, JamBarrierSnippets.class, "postWrite", LocationIdentity.any());
        postRange = snippet(providers, JamBarrierSnippets.class, "postRange", LocationIdentity.any());
    }

    @Snippet
    public static void postWrite(Object owner, Address address) {
        Object anchored = FixedValueAnchorNode.getObject(owner);
        Pointer slot = WordCastNode.castToWord(address);
        if (slot.belowThan(JamHeap.get().youngBegin())) {
            call(REMEMBER, anchored, slot, 1, 4);
        }
    }

    @Snippet
    public static void postRange(Object owner, Address address, long length, @ConstantParameter int stride) {
        if (length == 0) return;
        Object anchored = FixedValueAnchorNode.getObject(owner);
        Pointer first = getPointerToFirstArrayElement(WordCastNode.castToWord(address), length, stride);
        if (first.belowThan(JamHeap.get().youngBegin())) {
            call(REMEMBER, anchored, first, length, stride < 0 ? -stride : stride);
        }
    }

    void registerLowerings(Map<Class<? extends Node>, NodeLoweringProvider<?>> lowerings) {
        lowerings.put(SerialWriteBarrierNode.class, (NodeLoweringProvider<SerialWriteBarrierNode>) (node, tool) -> {
            Arguments args = new Arguments(postWrite, node.graph(), tool.getLoweringStage());
            args.add("owner", ((OffsetAddressNode) node.getAddress()).getBase());
            args.add("address", node.getAddress());
            template(tool, node, args).instantiate(tool.getMetaAccess(), node, SnippetTemplate.DEFAULT_REPLACER, args);
        });
        lowerings.put(SerialArrayRangeWriteBarrierNode.class, (NodeLoweringProvider<SerialArrayRangeWriteBarrierNode>) (node, tool) -> {
            Arguments args = new Arguments(postRange, node.graph(), tool.getLoweringStage());
            args.add("owner", ((OffsetAddressNode) node.getAddress()).getBase());
            args.add("address", node.getAddress());
            args.add("length", node.getLengthAsLong());
            args.add("stride", node.getElementStride());
            template(tool, node, args).instantiate(tool.getMetaAccess(), node, SnippetTemplate.DEFAULT_REPLACER, args);
        });
    }
}
