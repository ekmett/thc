// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0
package jdk.graal.compiler.hotspot.replacements;

import static jdk.graal.compiler.hotspot.GraalHotSpotVMConfig.INJECTED_VMCONFIG;
import static jdk.graal.compiler.hotspot.meta.HotSpotHostForeignCallsProvider.JAM_REMEMBER;
import static jdk.graal.compiler.hotspot.meta.HotSpotHostForeignCallsProvider.JAM_REMEMBERED_SLOTS_LOCATION;
import static jdk.graal.compiler.replacements.gc.WriteBarrierSnippets.getPointerToFirstArrayElement;
import org.graalvm.word.WordBase;
import org.graalvm.word.Pointer;
import org.graalvm.word.impl.Word;
import jdk.graal.compiler.api.replacements.Fold;
import jdk.graal.compiler.api.replacements.Fold.InjectedParameter;
import jdk.graal.compiler.hotspot.GraalHotSpotVMConfig;
import jdk.graal.compiler.api.replacements.Snippet;
import jdk.graal.compiler.api.replacements.Snippet.ConstantParameter;
import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;
import jdk.graal.compiler.graph.Node.ConstantNodeParameter;
import jdk.graal.compiler.graph.Node.NodeIntrinsic;
import jdk.graal.compiler.hotspot.meta.HotSpotProviders;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;
import jdk.graal.compiler.nodes.gc.SerialArrayRangeWriteBarrierNode;
import jdk.graal.compiler.nodes.gc.SerialWriteBarrierNode;
import jdk.graal.compiler.nodes.memory.address.AddressNode.Address;
import jdk.graal.compiler.nodes.memory.address.OffsetAddressNode;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.replacements.SnippetTemplate;
import jdk.graal.compiler.replacements.SnippetTemplate.AbstractTemplates;
import jdk.graal.compiler.replacements.SnippetTemplate.Arguments;
import jdk.graal.compiler.replacements.SnippetTemplate.SnippetInfo;
import jdk.graal.compiler.replacements.Snippets;
import jdk.graal.compiler.word.WordCastNode;

/** Exact source slots, including old allocations initialized by compiled code. */
public final class HotSpotJamWriteBarrierSnippets implements Snippets {
    @NodeIntrinsic(ForeignCallNode.class)
    private static native void remember(@ConstantNodeParameter ForeignCallDescriptor descriptor, Object owner, WordBase first, long count);

    @Fold
    static long youngBoundary(@InjectedParameter GraalHotSpotVMConfig config) {
        return config.narrowOopBase + (1L << 34);
    }

    @Snippet
    public static void postWrite(Object owner, Address address) {
        Pointer slot = WordCastNode.castToWord(address);
        if (slot.belowThan(Word.unsigned(youngBoundary(INJECTED_VMCONFIG)))) {
            remember(JAM_REMEMBER, owner, slot, 1);
        }
    }

    @Snippet
    public static void postRange(Object owner, Address address, long length, @ConstantParameter int stride) {
        if (length == 0) return;
        Pointer first = getPointerToFirstArrayElement(WordCastNode.castToWord(address), length, stride);
        if (first.belowThan(Word.unsigned(youngBoundary(INJECTED_VMCONFIG)))) {
            remember(JAM_REMEMBER, owner, first, length);
        }
    }

    public static final class Templates extends AbstractTemplates {
        private final SnippetInfo scalar;
        private final SnippetInfo range;
        public Templates(OptionValues options, HotSpotProviders providers) {
            super(options, providers);
            scalar = snippet(providers, HotSpotJamWriteBarrierSnippets.class, "postWrite", JAM_REMEMBERED_SLOTS_LOCATION);
            range = snippet(providers, HotSpotJamWriteBarrierSnippets.class, "postRange", JAM_REMEMBERED_SLOTS_LOCATION);
        }
        public void lower(SerialWriteBarrierNode node, LoweringTool tool) {
            Arguments args = new Arguments(scalar, node.graph(), tool.getLoweringStage());
            args.add("owner", ((OffsetAddressNode) node.getAddress()).getBase());
            args.add("address", node.getAddress());
            template(tool, node, args).instantiate(tool.getMetaAccess(), node, SnippetTemplate.DEFAULT_REPLACER, args);
        }
        public void lower(SerialArrayRangeWriteBarrierNode node, LoweringTool tool) {
            Arguments args = new Arguments(range, node.graph(), tool.getLoweringStage());
            args.add("owner", ((OffsetAddressNode) node.getAddress()).getBase());
            args.add("address", node.getAddress());
            args.add("length", node.getLengthAsLong());
            args.add("stride", node.getElementStride());
            template(tool, node, args).instantiate(tool.getMetaAccess(), node, SnippetTemplate.DEFAULT_REPLACER, args);
        }
    }
}
