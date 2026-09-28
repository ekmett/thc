// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Reuse the native receipts, original Core and first-installed-call checks. */
@Timeout(180)
public class LoomNativeTest {
    @FunctionalInterface private interface Check { void run(ThreadAsyncNativeTest fixture) throws Exception; }
    private void loom(Check check) throws Exception {
        String property = "polyglot.thc.ThreadHosting";
        String previous = System.getProperty(property);
        System.setProperty(property, "loom");
        try { check.run(new ThreadAsyncNativeTest()); }
        finally { if (previous == null) System.clearProperty(property); else System.setProperty(property, previous); }
    }
    @Test public void originalForkAndMVarThrowToResumeSharedThunkOnBothBackends() throws Exception {
        loom(fixture -> {
            fixture.publicForkAndThrowResumeTheSharedThunk();
            fixture.astPublicForkAndThrowResumeTheSharedThunk();
            fixture.forkedChildOwnsAndResumesTheSharedLazyActionHead();
            fixture.astForkedChildOwnsAndResumesTheSharedLazyActionHead();
        });
    }
    @Test public void originalSavedSchedulingRetainsEachInvocationOnBothBackends() throws Exception {
        loom(fixture -> { fixture.savedSchedulingOwnsEachInvocation(); fixture.astSavedSchedulingOwnsEachInvocation(); });
    }
    @Test public void originalNativeForkOnRunsOnBothBackends() throws Exception {
        loom(_ -> new ThreadSchedulingTest().nativePinnedForkOnBothBackends());
    }
    @Test public void originalMaskAndMultiShotDeliveryOnBothBackends() throws Exception {
        loom(fixture -> {
            fixture.outerUninterruptibleMaskStillCapturesCalleeThatUnmasksAndSelfThrows();
            fixture.astSelfDirectedThrowBypassesTheOuterUninterruptibleMask();
            fixture.savedSelfDeliveryRestoresMaskBeforeSavedHandler();
            fixture.astSavedSelfDeliveryRestoresMaskBeforeSavedHandler();
            fixture.savedExternalDeliveryOwnsEachInterruptedInvocation();
            fixture.astSavedExternalDeliveryOwnsEachInterruptedInvocation();
        });
    }

    @Test public void falseEpicycleCompactionRetainsPrefixesMasksAndTypedResultsOnAVirtualThread() {
        try (var context = org.graalvm.polyglot.Context.newBuilder("thc").allowCreateThread(true)
                .allowExperimentalOptions(true).option("thc.ThreadHosting", "loom").build()) {
            context.initialize("thc"); context.enter();
            try {
                thc.Language.currentState().getThreads().hostEntry(null, () -> {
                    org.junit.jupiter.api.Assertions.assertTrue(Thread.currentThread().isVirtual());
                    var fixture = new AstTailSpillTest();
                    fixture.firstInstalledEntrySpillsWithoutASettlingCall();
                    fixture.savedMaskCleanupUnwindsBeforeTheMatchingTailAnchor();
                    fixture.referenceLoopKeepsMaskCleanupAndNonTailSuffixOnce();
                    return null;
                });
            } finally { context.leave(); }
        }
    }
}
