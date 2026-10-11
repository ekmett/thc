// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
package com.oracle.svm.core.jam;

import java.util.List;
import java.util.Map;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;
import org.graalvm.nativeimage.impl.PinnedObjectSupport;
import com.oracle.svm.core.GCRelatedMXBeans;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.config.ObjectLayout;
import com.oracle.svm.core.config.ObjectLayout.IdentityHashMode;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.graal.meta.RuntimeConfiguration;
import com.oracle.svm.core.graal.meta.SubstrateForeignCallsProvider;
import com.oracle.svm.core.graal.snippets.GCAllocationSupport;
import com.oracle.svm.core.graal.snippets.NodeLoweringProvider;
import com.oracle.svm.core.graal.snippets.SubstrateAllocationSnippets;
import com.oracle.svm.core.heap.AllocationFeature;
import com.oracle.svm.core.heap.BarrierSetProvider;
import com.oracle.svm.core.heap.Heap;
import com.oracle.svm.core.hub.RuntimeClassLoading;
import com.oracle.svm.core.image.ImageHeapLayouter;
import com.oracle.svm.core.imagelayer.ImageLayerBuildingSupport;
import com.oracle.svm.core.os.CommittedMemoryProvider;
import com.oracle.svm.core.util.UserError;
import com.oracle.svm.hosted.HostedConfiguration;
import com.oracle.svm.shared.feature.AutomaticallyRegisteredFeature;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.util.Providers;

/** Install Jam without importing Serial's chunks, object header or GC policy. */
@AutomaticallyRegisteredFeature
final class JamFeature implements InternalFeature {
    @Override public boolean isInConfiguration(IsInConfigurationAccess access) { return SubstrateOptions.useJamGC(); }
    @Override public List<Class<? extends Feature>> getRequiredFeatures() { return List.of(AllocationFeature.class); }

    @Override public void afterRegistration(AfterRegistrationAccess access) {
        UserError.guarantee(SubstrateOptions.useCompressedReferences() && SubstrateOptions.ConcealedOptions.UseCompressedReferenceShift.getValue(),
                        "Jam requires compressed references with a three-bit shift");
        UserError.guarantee(!ImageLayerBuildingSupport.buildingImageLayer(), "Jam does not yet support layered images");
        UserError.guarantee(!RuntimeClassLoading.isSupported(), "Jam does not yet support dynamic class loading");
        UserError.guarantee(JamOptions.JamWorkers.getValue() > 0, "JamWorkers must be positive");
        ObjectLayout layout = HostedConfiguration.createObjectLayout(IdentityHashMode.OBJECT_HEADER);
        UserError.guarantee(layout.getMinRuntimeHeapInstanceSize() == Long.BYTES,
                        "Jam pin padding requires eight-byte minimum instances; additional object-header bytes are unsupported");
        ImageSingletons.add(ObjectLayout.class, layout);
        ImageSingletons.add(BarrierSetProvider.class, new JamBarrierSetProvider());
        ImageSingletons.add(GCRelatedMXBeans.class, new JamRelatedMXBeans());
        ImageSingletons.add(CommittedMemoryProvider.class, new JamCommittedMemoryProvider());
    }

    @Override public void duringSetup(DuringSetupAccess access) {
        JamHeap heap = new JamHeap();
        ImageSingletons.add(Heap.class, heap);
        ImageSingletons.add(JamHeap.class, heap);
        ImageSingletons.add(JamImageHeapInfo.class, heap.imageInfo);
        ImageSingletons.add(GCAllocationSupport.class, new JamAllocationSupport());
        ImageSingletons.add(PinnedObjectSupport.class, new JamPinnedObjectSupport());
        Class<?> weak = access.findClassByName("thc.vm.Weak");
        if (weak != null) { RuntimeClassInitialization.initializeAtRunTime(weak); }
    }

    @Override public void beforeAnalysis(BeforeAnalysisAccess access) { access.registerAsUsed(Object[].class); }
    @Override public void afterAnalysis(AfterAnalysisAccess access) { ImageSingletons.add(ImageHeapLayouter.class, new JamImageHeapLayouter(JamHeap.get().imageInfo)); }
    @Override public void beforeCompilation(BeforeCompilationAccess access) { access.registerAsImmutable(JamHeap.get().imageInfo); }

    @Override
    public void registerLowerings(RuntimeConfiguration config, OptionValues options, Providers providers,
                    Map<Class<? extends Node>, NodeLoweringProvider<?>> lowerings, boolean hosted) {
        new JamBarrierSnippets(options, providers).registerLowerings(lowerings);
        SubstrateAllocationSnippets.Templates base = new SubstrateAllocationSnippets.Templates(options, providers);
        base.registerLowering(lowerings);
        new JamAllocationSnippets.Templates(options, providers, base).registerLowering(lowerings);
    }

    @Override public void registerForeignCalls(SubstrateForeignCallsProvider foreignCalls) {
        JamAllocationSupport.registerForeignCalls(foreignCalls);
        JamThreadContext.registerForeignCalls(foreignCalls);
        JamBarrierSnippets.registerForeignCalls(foreignCalls);
    }
}
