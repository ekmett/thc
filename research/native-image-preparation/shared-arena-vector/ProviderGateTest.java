/*
 * SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
 */

import java.lang.classfile.*;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.reflect.*;

/** Executes the emitted fail-closed capability check without building an image. */
public final class ProviderGateTest {
    public static void main(String[] args) throws Exception {
        boolean expected = Boolean.parseBoolean(args[0]);
        Class<?> options = Class.forName("com.oracle.svm.core.SubstrateOptions");
        boolean actual = false;
        try {
            Method gate = options.getDeclaredMethod("sharedArenaVectorProviderInstalled");
            gate.setAccessible(true);
            actual = (boolean) gate.invoke(null);
            byte[] bytes;
            try (var input = options.getResourceAsStream("SubstrateOptions.class")) { bytes = input.readAllBytes(); }
            long guardCalls = ClassFile.of().parse(bytes).methods().stream()
                    .filter(method -> method.methodName().stringValue().startsWith("lambda$static$"))
                    .flatMap(method -> method.code().orElseThrow().elementStream())
                    .filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast)
                    .filter(call -> call.owner().asInternalName().equals("com/oracle/svm/core/SubstrateOptions") &&
                            call.name().equalsString("sharedArenaVectorProviderInstalled")).count();
            if (guardCalls != 1) throw new AssertionError("Capability must be consulted by exactly one option validator");
        } catch (NoSuchMethodException originalProvider) {
            if (expected) throw new AssertionError("Original option guard cannot admit shared vectors", originalProvider);
        }
        if (actual != expected) throw new AssertionError("Expected installed provider " + expected + ", got " + actual);
        if (actual) {
            Class<?> phase = Class.forName("com.oracle.svm.hosted.foreign.ForeignFunctionsFeature$SharedArenaSupportImpl");
            byte[] bytes;
            try (var input = phase.getResourceAsStream("ForeignFunctionsFeature$SharedArenaSupportImpl.class")) {
                bytes = input.readAllBytes();
            }
            var calls = ClassFile.of().parse(bytes).methods().stream()
                    .filter(method -> method.methodName().equalsString("createOptimizeSharedArenaAccessPhase"))
                    .flatMap(method -> method.code().orElseThrow().elementStream())
                    .filter(InvokeInstruction.class::isInstance).map(InvokeInstruction.class::cast).toList();
            long guards = calls.stream().filter(call -> call.owner().asInternalName().equals("com/oracle/svm/shared/util/VMError") &&
                    call.name().equalsString("guarantee")).count();
            long scalarPhases = calls.stream().filter(call -> call.owner().asInternalName().equals(
                    "com/oracle/svm/core/foreign/phases/SubstrateOptimizeSharedArenaAccessPhase") && call.name().equalsString("<init>")).count();
            long predicate = calls.stream().filter(call -> call.owner().asInternalName().equals(
                    "com/oracle/svm/core/foreign/SharedArenaVectorSupport") && call.name().equalsString("unsupportedCombination")).count();
            long appends = calls.stream().filter(call -> call.name().equalsString("appendPhase")).count();
            if (guards != 2 || scalarPhases != 2 || predicate != 1 || appends != 4)
                throw new AssertionError("Original guarantees and both scalar phase configurations must remain");
        }
        System.out.println("PASS emitted option capability: " + actual);
    }
}
