/*
 * SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
 */

import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;

/** Inspect the emitted hook without initializing hosted SVM classes on a plain JVM. */
public final class OverlayHookTest {
    private static final String PARSER = "com/oracle/svm/hosted/phases/SharedGraphBuilderPhase$SharedBytecodeParser";

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    // Evaluate only the small emitted Boolean hook; unknown instructions fail closed.
    private static boolean evaluate(CodeModel code, boolean deopt, boolean osr, boolean inherited) {
        var instructions = new ArrayList<Instruction>();
        var labels = new HashMap<Label, Integer>();
        for (var element : code) {
            if (element instanceof LabelTarget target) labels.put(target.label(), instructions.size());
            if (element instanceof Instruction instruction) instructions.add(instruction);
        }
        var stack = new ArrayDeque<Object>();
        for (int pc = 0, steps = 0; steps < 32; steps++) {
            var instruction = instructions.get(pc++);
            switch (instruction.opcode()) {
                case ALOAD_0 -> stack.push("parser");
                case GETFIELD -> {
                    var field = (FieldInstruction) instruction;
                    require(stack.pop().equals("parser") && field.name().equalsString("graph")
                            && field.type().equalsString("Ljdk/graal/compiler/nodes/StructuredGraph;"), "graph read");
                    stack.push("graph");
                }
                case INVOKEVIRTUAL, INVOKESPECIAL -> {
                    var call = (InvokeInstruction) instruction;
                    require(call.type().equalsString("()Z"), "boolean method descriptor");
                    var receiver = stack.pop();
                    String name = call.name().stringValue();
                    boolean result = switch (name) {
                        case "isMethodDeoptTarget" -> {
                            require(receiver.equals("parser") && call.opcode() == Opcode.INVOKEVIRTUAL,
                                    "deopt predicate dispatch");
                            yield deopt;
                        }
                        case "isOSR" -> {
                            require(receiver.equals("graph") && call.owner().asInternalName().equals("jdk/graal/compiler/nodes/StructuredGraph"),
                                    "OSR predicate owner");
                            yield osr;
                        }
                        case "stampFromValueForForcedPhis" -> {
                            require(receiver.equals("parser") && call.opcode() == Opcode.INVOKESPECIAL
                                    && call.owner().asInternalName().equals("jdk/graal/compiler/java/BytecodeParser"),
                                    "unchanged superclass fallback");
                            yield inherited;
                        }
                        default -> throw new AssertionError("Unexpected invocation " + name);
                    };
                    stack.push(result ? 1 : 0);
                }
                case IFEQ -> {
                    if (stack.pop().equals(0)) pc = labels.get(((BranchInstruction) instruction).target());
                }
                case GOTO -> pc = labels.get(((BranchInstruction) instruction).target());
                case ICONST_0 -> stack.push(0);
                case ICONST_1 -> stack.push(1);
                case IRETURN -> {
                    boolean result = stack.pop().equals(1);
                    require(stack.isEmpty(), "balanced hook stack");
                    return result;
                }
                default -> throw new AssertionError("Unexpected instruction " + instruction);
            }
        }
        throw new AssertionError("Unbounded hook");
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 1, "overlay JAR argument");
        try (var jar = new JarFile(Path.of(args[0]).toFile())) {
            var payload = jar.stream().filter(entry -> !entry.isDirectory()).map(entry -> entry.getName()).collect(java.util.stream.Collectors.toSet());
            require(payload.equals(Set.of("META-INF/MANIFEST.MF",
                    "META-INF/upstream-toolchain-LICENSE.txt",
                    "META-INF/source/com/oracle/svm/hosted/phases/SharedGraphBuilderPhase.java",
                    "com/oracle/svm/hosted/phases/SharedGraphBuilderPhase.class", PARSER + ".class",
                    PARSER + "$BootstrapMethodHandler.class", PARSER + "$SessionCheck.class")), "exact overlay payload");
            var model = ClassFile.of().parse(jar.getInputStream(jar.getJarEntry(PARSER + ".class")).readAllBytes());
            var hooks = model.methods().stream().filter(method -> method.methodName().equalsString("stampFromValueForForcedPhis")).toList();
            require(hooks.size() == 1 && hooks.getFirst().methodType().equalsString("()Z"), "one emitted hook");
            var code = hooks.getFirst().code().orElseThrow();
            require(code.exceptionHandlers().isEmpty(), "no hook exception handling");
            for (boolean deopt : List.of(false, true)) {
                for (boolean osr : List.of(false, true)) {
                    for (boolean inherited : List.of(false, true)) {
                        require(evaluate(code, deopt, osr, inherited) == ((deopt && !osr) || inherited),
                                "D/OSR/superclass truth table");
                    }
                }
            }
        }
        System.out.println("PASS exact overlay payload and 8 emitted-hook truth-table cases");
    }
}
