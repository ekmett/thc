/*
 * Copyright (c) 2022, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.jam;

import static jdk.graal.compiler.core.common.spi.ForeignCallDescriptor.CallSideEffect.NO_SIDE_EFFECT;

import org.graalvm.word.UnsignedWord;
import org.graalvm.word.impl.Word;

import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.core.graal.meta.SubstrateForeignCallsProvider;
import com.oracle.svm.core.graal.snippets.GCAllocationSupport;
import com.oracle.svm.core.heap.Pod;
import com.oracle.svm.core.snippets.SnippetRuntime;
import com.oracle.svm.core.snippets.SnippetRuntime.SubstrateForeignCallDescriptor;
import com.oracle.svm.core.snippets.SubstrateForeignCallTarget;
import com.oracle.svm.core.stack.StackOverflowCheck;
import com.oracle.svm.core.thread.ContinuationSupport;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.BuildtimeAccessOnly;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;

import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;

/** Allocation slow paths; initializing reference stores carry exact slot barriers. */
@SingletonTraits(access = BuildtimeAccessOnly.class, layeredCallbacks = NoLayeredCallbacks.class)
public class JamAllocationSupport implements GCAllocationSupport {
    private static final SubstrateForeignCallDescriptor SLOW_NEW_INSTANCE = SnippetRuntime.findForeignCall(JamAllocationSupport.class, "slowNewInstance", NO_SIDE_EFFECT);
    private static final SubstrateForeignCallDescriptor SLOW_NEW_ARRAY = SnippetRuntime.findForeignCall(JamAllocationSupport.class, "slowNewArray", NO_SIDE_EFFECT);
    private static final SubstrateForeignCallDescriptor SLOW_NEW_STORED_CONTINUATION = SnippetRuntime.findForeignCall(JamAllocationSupport.class, "slowNewStoredContinuation", NO_SIDE_EFFECT);
    private static final SubstrateForeignCallDescriptor SLOW_NEW_POD_INSTANCE = SnippetRuntime.findForeignCall(JamAllocationSupport.class, "slowNewPodInstance", NO_SIDE_EFFECT);
    private static final SubstrateForeignCallDescriptor[] UNCONDITIONAL_FOREIGN_CALLS = new SubstrateForeignCallDescriptor[]{SLOW_NEW_INSTANCE, SLOW_NEW_ARRAY};

    public static void registerForeignCalls(SubstrateForeignCallsProvider foreignCalls) {
        foreignCalls.register(UNCONDITIONAL_FOREIGN_CALLS);
        if (ContinuationSupport.isSupported()) {
            foreignCalls.register(SLOW_NEW_STORED_CONTINUATION);
        }
        if (Pod.RuntimeSupport.isPresent()) {
            foreignCalls.register(SLOW_NEW_POD_INSTANCE);
        }
    }

    @Override
    public ForeignCallDescriptor getNewInstanceStub() {
        return SLOW_NEW_INSTANCE;
    }

    @Override
    public ForeignCallDescriptor getNewArrayStub() {
        return SLOW_NEW_ARRAY;
    }

    @Override
    public ForeignCallDescriptor getNewStoredContinuationStub() {
        return SLOW_NEW_STORED_CONTINUATION;
    }

    @Override
    public ForeignCallDescriptor getNewPodInstanceStub() {
        return SLOW_NEW_POD_INSTANCE;
    }

    @Override
    public boolean shouldAllocateInTLAB(UnsignedWord size, boolean isArray) {
        return JamThreadLocalAllocation.fitsTlabPolicy(size);
    }

    @Override
    public Word getTLABInfo() {
        return JamThreadLocalAllocation.tlabAddress();
    }

    @Override
    public int tlabTopOffset() {
        return JamThreadLocalAllocation.Descriptor.offsetOfTop();
    }

    @Override
    public int tlabEndOffset() {
        return JamThreadLocalAllocation.Descriptor.offsetOfEnd();
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false)
    @Uninterruptible(reason = "Return the formatted object without an intervening collection.")
    private static Object slowNewInstance(Word objectHeader) {
        StackOverflowCheck.singleton().makeYellowZoneAvailable();
        try {
            Object result = slowNewInstanceInterruptibly(objectHeader);
            return result;
        } finally {
            StackOverflowCheck.singleton().protectYellowZone();
        }
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false)
    @Uninterruptible(reason = "Return the formatted object without an intervening collection.")
    private static Object slowNewArray(Word objectHeader, int length) {
        StackOverflowCheck.singleton().makeYellowZoneAvailable();
        try {
            Object result = slowNewArrayLikeObjectInterruptibly(objectHeader, length, null);
            return result;
        } finally {
            StackOverflowCheck.singleton().protectYellowZone();
        }
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false)
    @Uninterruptible(reason = "Return the formatted object without an intervening collection.")
    private static Object slowNewPodInstance(Word objectHeader, int arrayLength, byte[] referenceMap) {
        StackOverflowCheck.singleton().makeYellowZoneAvailable();
        try {
            Object result = slowNewArrayLikeObjectInterruptibly(objectHeader, arrayLength, referenceMap);
            return result;
        } finally {
            StackOverflowCheck.singleton().protectYellowZone();
        }
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false)
    @Uninterruptible(reason = "Just to be consistent with the other allocation slowpath code.")
    private static Object slowNewStoredContinuation(Word objectHeader, int length) {
        StackOverflowCheck.singleton().makeYellowZoneAvailable();
        try {
            /* Stored continuations only use explicit write barriers, so no dirtying needed. */
            return slowNewArrayLikeObjectInterruptibly(objectHeader, length, null);
        } finally {
            StackOverflowCheck.singleton().protectYellowZone();
        }
    }

    @Uninterruptible(reason = "Switch from uninterruptible to interruptible code.", calleeMustBe = false)
    private static Object slowNewInstanceInterruptibly(Word objectHeader) {
        return JamThreadLocalAllocation.slowPathNewInstance(objectHeader);
    }

    @Uninterruptible(reason = "Switch from uninterruptible to interruptible code.", calleeMustBe = false)
    private static Object slowNewArrayLikeObjectInterruptibly(Word objectHeader, int length, byte[] podReferenceMap) {
        return JamThreadLocalAllocation.slowPathNewArrayLikeObject(objectHeader, length, podReferenceMap);
    }
}
