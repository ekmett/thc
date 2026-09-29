/*
 * SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
 */

import java.lang.classfile.*;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarFile;

/** Only four method changes; the existing scoped-access/close implementation is retained. */
public final class BuildOverlay {
    private static final String PACKAGE = "com/oracle/svm/core/foreign/";
    private static final String TARGET = PACKAGE + "Target_jdk_internal_misc_ScopedMemoryAccess.class";
    private static final String PHASE_SUPPORT = "com/oracle/svm/hosted/foreign/ForeignFunctionsFeature$SharedArenaSupportImpl.class";
    private static final List<String> WRAPPERS = List.of("loadFromMemorySegment", "loadFromMemorySegmentMasked",
            "storeIntoMemorySegment", "storeIntoMemorySegmentMasked");

    public static void main(String[] args) throws Exception {
        Path templates = Path.of(args[1]);
        Path output = Path.of(args[2]);
        ClassFile classFile = ClassFile.of();
        Map<String, MethodModel> replacements = new HashMap<>();
        for (MethodModel method : classFile.parse(templates.resolve(PACKAGE + "VectorAccess.class")).methods())
            if (WRAPPERS.contains(method.methodName().stringValue())) replacements.put(method.methodName().stringValue(), method);
        if (!replacements.keySet().equals(Set.copyOf(WRAPPERS))) throw new AssertionError("Exactly four wrapper templates required");
        byte[] original;
        byte[] phaseSupport;
        try (JarFile jar = new JarFile(args[0]); var input = jar.getInputStream(jar.getJarEntry(TARGET))) {
            original = input.readAllBytes();
            try (var phase = jar.getInputStream(jar.getJarEntry(PHASE_SUPPORT))) { phaseSupport = phase.readAllBytes(); }
        }
        Set<String> replaced = new HashSet<>();
        ClassTransform replace = (builder, element) -> {
            if (element instanceof MethodModel method && WRAPPERS.contains(method.methodName().stringValue())) {
                MethodModel replacement = replacements.get(method.methodName().stringValue());
                if (!method.methodType().equalsString(replacement.methodType().stringValue()))
                    throw new AssertionError("Pinned method descriptor changed: " + method.methodName());
                replaced.add(method.methodName().stringValue());
                builder.with(replacement);
            } else builder.with(element);
        };
        byte[] patched = classFile.transformClass(classFile.parse(original), replace.andThen(ClassTransform.endHandler(builder -> {
            for (String name : WRAPPERS) if (!replaced.contains(name)) builder.with(replacements.get(name));
        })));
        if (!replaced.equals(Set.of("storeIntoMemorySegment", "storeIntoMemorySegmentMasked")))
            throw new AssertionError("Pinned substitution method set changed: " + replaced);
        if (!classFile.verify(patched).isEmpty()) throw new AssertionError(classFile.verify(patched));
        Files.createDirectories(output.resolve(PACKAGE));
        Files.write(output.resolve(TARGET), patched);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(patched));
        int[] phaseGuards = {0};
        byte[] patchedPhase = classFile.transformClass(classFile.parse(phaseSupport),
                ClassTransform.transformingMethods(method -> method.methodName().equalsString("createOptimizeSharedArenaAccessPhase"),
                        MethodTransform.transformingCode((builder, element) -> {
                            if (element instanceof InvokeInstruction call &&
                                    call.owner().asInternalName().equals("com/oracle/svm/core/jdk/VectorAPIEnabled") &&
                                    call.name().equalsString("getValue") && call.type().equalsString("()Z")) {
                                phaseGuards[0]++;
                                builder.invokestatic(ClassDesc.of("com.oracle.svm.core.foreign.SharedArenaVectorSupport"),
                                        "unsupportedCombination", MethodTypeDesc.ofDescriptor("()Z"));
                            } else builder.with(element);
                        })));
        if (phaseGuards[0] != 1 || !classFile.verify(patchedPhase).isEmpty())
            throw new AssertionError("Exactly one verified phase compatibility predicate required: count=" +
                    phaseGuards[0] + ", errors=" + classFile.verify(patchedPhase));
        Files.createDirectories(output.resolve(PHASE_SUPPORT).getParent());
        Files.write(output.resolve(PHASE_SUPPORT), patchedPhase);
        String phaseDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(patchedPhase));
        int[] constants = {0};
        byte[] capability = classFile.transformClass(classFile.parse(templates.resolve(PACKAGE + "SharedArenaVectorSupport.class")),
                ClassTransform.transformingMethodBodies((builder, element) -> {
                    if (element instanceof ConstantInstruction constant &&
                            (constant.constantValue().equals("THC_SHARED_ARENA_VECTOR_DIGEST") ||
                            constant.constantValue().equals("THC_SHARED_ARENA_PHASE_DIGEST"))) {
                        constants[0]++;
                        builder.ldc(constant.constantValue().equals("THC_SHARED_ARENA_VECTOR_DIGEST") ? digest : phaseDigest);
                    } else builder.with(element);
                }));
        if (constants[0] != 2 || !classFile.verify(capability).isEmpty())
            throw new AssertionError("Exactly two verified installed-class digests required");
        Files.write(output.resolve(PACKAGE + "SharedArenaVectorSupport.class"), capability);
        System.out.println("Prepared four direct vector wrappers; installed-class SHA-256 " + digest);
        System.out.println("Prepared guarded, otherwise unchanged scalar phase suite; installed-class SHA-256 " + phaseDigest);
    }
}
