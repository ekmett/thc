/*
 * SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
 */

import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;
import com.oracle.svm.common.meta.MethodVariant;
import com.oracle.svm.hosted.code.SubstrateCompilationDirectives;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.runtime.JVMCI;

/** Executes the emitted decision with strict compiler-service markers, not a native image. */
public final class SimulatedFoldTest {
    private static final String DECODER = "com/oracle/svm/hosted/phases/InlineBeforeAnalysisGraphDecoderImpl";
    private static final String SUPPORT = "com/oracle/svm/graal/hosted/runtimecompilation/RuntimeCompiledMethodSupport";
    private static final String REFLECTION = SUPPORT + "$RuntimeCompilationReflectionProvider";
    private static final String HEAP = "com/oracle/graal/pointsto/heap/ImageHeapConstant";
    private enum Marker { DECODER, LOAD, FIELD, TYPE, SIMULATION, ANALYSIS, GRAPH, INTERCEPTION, PROVIDERS, INTRINSIC }
    private record Heap(JavaConstant hostedObject, boolean array) {
        Heap(JavaConstant hostedObject) { this(hostedObject, false); }
    }
    private record Fold(Object constant) {}
    private record Result(Object value, List<String> calls) {}
    private static int checks;

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    private static ResolvedJavaMethod method(MethodVariant.MethodVariantKey key) {
        Class<?>[] interfaces = key == null ? new Class<?>[]{ResolvedJavaMethod.class}
                : new Class<?>[]{ResolvedJavaMethod.class, MethodVariant.class};
        return (ResolvedJavaMethod) Proxy.newProxyInstance(SimulatedFoldTest.class.getClassLoader(), interfaces,
                (proxy, called, args) -> {
                    if (called.getName().equals("getMethodVariantKey")) return key;
                    throw new AssertionError("Unexpected method metadata read: " + called);
                });
    }

    private static Object pop(List<Object> stack) { return stack.removeLast(); }

    // Evaluate the actual emitted method, including both unchanged fallbacks. Unexpected
    // opcodes, compiler-service calls, fields and receiver/argument identities fail closed.
    private static Result evaluate(CodeModel code, ResolvedJavaMethod method, boolean isStatic,
                    Fold folded, boolean intrinsic) {
        var instructions = new ArrayList<Instruction>();
        var labels = new HashMap<Label, Integer>();
        for (var element : code) {
            if (element instanceof LabelTarget target) labels.put(target.label(), instructions.size());
            if (element instanceof Instruction instruction) instructions.add(instruction);
        }
        var locals = new Object[8];
        locals[0] = Marker.DECODER;
        locals[1] = Marker.LOAD;
        var stack = new ArrayList<Object>();
        var calls = new ArrayList<String>();
        for (int pc = 0, steps = 0; steps < 100; steps++) {
            var instruction = instructions.get(pc++);
            if (instruction instanceof LoadInstruction load) {
                stack.add(locals[load.slot()]);
            } else if (instruction instanceof StoreInstruction store) {
                locals[store.slot()] = pop(stack);
            } else if (instruction instanceof FieldInstruction field) {
                require(field.opcode() == Opcode.GETFIELD && field.owner().asInternalName().equals(DECODER)
                        && pop(stack) == Marker.DECODER, "exact decoder field read");
                stack.add(switch (field.name().stringValue()) {
                    case "simulateClassInitializerSupport" -> Marker.SIMULATION;
                    case "bb" -> Marker.ANALYSIS;
                    case "graph" -> Marker.GRAPH;
                    case "fieldValueInterceptionSupport" -> Marker.INTERCEPTION;
                    case "providers" -> Marker.PROVIDERS;
                    default -> throw new AssertionError("Unexpected field: " + field);
                });
            } else if (instruction instanceof TypeCheckInstruction type) {
                Object value = pop(stack);
                String name = type.type().asInternalName();
                if (type.opcode() == Opcode.INSTANCEOF) {
                    require(name.equals(HEAP), "only heap-constant type test");
                    stack.add(value instanceof Heap ? 1 : 0);
                } else {
                    require(type.opcode() == Opcode.CHECKCAST &&
                            ((name.equals(HEAP) && value instanceof Heap) ||
                            (name.equals("com/oracle/graal/pointsto/meta/AnalysisField") && value == Marker.FIELD)),
                            "original field or heap-constant cast");
                    stack.add(value);
                }
            } else if (instruction instanceof InvokeInstruction call) {
                int count = call.typeSymbol().parameterCount();
                var arguments = new ArrayList<Object>();
                for (int i = 0; i < count; i++) arguments.addFirst(pop(stack));
                Object receiver = call.opcode() == Opcode.INVOKESTATIC ? null : pop(stack);
                String key = call.owner().asInternalName() + "." + call.name().stringValue();
                Object result = switch (key) {
                    case "jdk/graal/compiler/nodes/java/LoadFieldNode.field" -> {
                        require(receiver == Marker.LOAD && arguments.isEmpty(), "one original field read"); yield Marker.FIELD;
                    }
                    case "com/oracle/graal/pointsto/meta/AnalysisField.isStatic" -> {
                        require(receiver == Marker.FIELD && arguments.isEmpty(), "static test receiver"); yield isStatic ? 1 : 0;
                    }
                    case "com/oracle/graal/pointsto/meta/AnalysisField.getDeclaringClass" -> {
                        require(receiver == Marker.FIELD && arguments.isEmpty(), "declaring owner receiver"); yield Marker.TYPE;
                    }
                    case DECODER + ".processClassInitializer" -> {
                        require(receiver == Marker.DECODER && arguments.equals(List.of(Marker.TYPE)), "unchanged initializer processing");
                        calls.add("initialize"); yield null;
                    }
                    case "com/oracle/svm/hosted/classinitialization/SimulateClassInitializerSupport.tryCanonicalize" -> {
                        require(receiver == Marker.SIMULATION && arguments.equals(List.of(Marker.ANALYSIS, Marker.LOAD)), "unchanged simulation inputs");
                        calls.add("simulate"); yield folded;
                    }
                    case "jdk/graal/compiler/nodes/StructuredGraph.method" -> {
                        require(receiver == Marker.GRAPH && arguments.isEmpty(), "root graph method"); yield method;
                    }
                    case "com/oracle/svm/hosted/code/SubstrateCompilationDirectives.isRuntimeCompiledMethod" -> {
                        require(receiver == null && arguments.size() == 1 && arguments.getFirst() == method, "official root variant predicate");
                        yield SubstrateCompilationDirectives.isRuntimeCompiledMethod(method) ? 1 : 0;
                    }
                    case "jdk/graal/compiler/nodes/ConstantNode.asJavaConstant" -> {
                        require(receiver == folded && arguments.isEmpty(), "same candidate constant"); yield folded.constant();
                    }
                    case HEAP + ".getHostedObject" -> {
                        require(receiver == folded.constant() && arguments.isEmpty(), "same heap constant backing"); yield ((Heap) receiver).hostedObject();
                    }
                    case "com/oracle/svm/hosted/ameta/FieldValueInterceptionSupport.tryIntrinsifyFieldLoad" -> {
                        require(receiver == Marker.INTERCEPTION && arguments.equals(List.of(Marker.PROVIDERS, Marker.LOAD)), "unchanged interception inputs");
                        calls.add("intercept"); yield intrinsic ? Marker.INTRINSIC : null;
                    }
                    default -> throw new AssertionError("Unexpected compiler call: " + call);
                };
                if (!call.typeSymbol().returnType().descriptorString().equals("V")) stack.add(result);
            } else if (instruction instanceof BranchInstruction branch) {
                Object value = pop(stack);
                boolean take = switch (branch.opcode()) {
                    case IFEQ -> value.equals(0);
                    case IFNE -> !value.equals(0);
                    case IFNULL -> value == null;
                    case IFNONNULL -> value != null;
                    default -> throw new AssertionError("Unexpected branch: " + branch);
                };
                if (take) pc = labels.get(branch.target());
            } else if (instruction.opcode() == Opcode.ARETURN) {
                Object value = pop(stack);
                require(stack.isEmpty(), "balanced return stack");
                return new Result(value, calls);
            } else {
                throw new AssertionError("Unexpected emitted instruction: " + instruction);
            }
        }
        throw new AssertionError("Unbounded decoder decision");
    }

    private static Result evaluateReflection(CodeModel code, Object candidate, Object fieldReceiver, RuntimeException failure) {
        var instructions = new ArrayList<Instruction>();
        var labels = new HashMap<Label, Integer>();
        for (var element : code) {
            if (element instanceof LabelTarget target) labels.put(target.label(), instructions.size());
            if (element instanceof Instruction instruction) instructions.add(instruction);
        }
        var locals = new Object[8];
        locals[0] = Marker.PROVIDERS;
        locals[1] = Marker.FIELD;
        locals[2] = fieldReceiver;
        var stack = new ArrayList<Object>();
        var calls = new ArrayList<String>();
        for (int pc = 0, steps = 0; steps < 50; steps++) {
            var instruction = instructions.get(pc++);
            if (instruction instanceof LoadInstruction load) {
                stack.add(locals[load.slot()]);
            } else if (instruction instanceof StoreInstruction store) {
                locals[store.slot()] = pop(stack);
            } else if (instruction instanceof ConstantInstruction constant) {
                Object value = constant.opcode() == Opcode.ACONST_NULL ? null : constant.constantValue();
                require(value == null || value.equals(0) || value.equals(1),
                        "only original read flags and rejected null constant");
                stack.add(value);
            } else if (instruction instanceof TypeCheckInstruction type) {
                Object value = pop(stack);
                String name = type.type().asInternalName();
                if (type.opcode() == Opcode.INSTANCEOF) {
                    require(name.equals(HEAP), "late check admits every heap-constant subtype");
                    stack.add(value instanceof Heap ? 1 : 0);
                } else {
                    require(type.opcode() == Opcode.CHECKCAST &&
                            ((name.equals(HEAP) && value instanceof Heap) ||
                            (name.equals("com/oracle/graal/pointsto/meta/AnalysisField") && value == Marker.FIELD)),
                            "original field or heap-constant cast");
                    stack.add(value);
                }
            } else if (instruction instanceof InvokeInstruction call) {
                var arguments = new ArrayList<Object>();
                for (int i = 0; i < call.typeSymbol().parameterCount(); i++) arguments.addFirst(pop(stack));
                require(call.opcode() == Opcode.INVOKEVIRTUAL, "only original read and backing inspection calls");
                Object receiver = pop(stack);
                String key = call.owner().asInternalName() + "." + call.name().stringValue();
                if (key.equals(REFLECTION + ".readValue")) {
                    require(receiver == Marker.PROVIDERS && arguments.size() == 4 && arguments.get(0) == Marker.FIELD &&
                            arguments.get(1) == fieldReceiver && arguments.get(2).equals(1) && arguments.get(3).equals(0),
                            "unchanged field, receiver and simulated/relocatable flags");
                    require(calls.isEmpty(), "exactly one original read before any backing inspection");
                    calls.add("read");
                    if (failure != null) throw failure;
                    stack.add(candidate);
                } else if (key.equals(HEAP + ".getHostedObject")) {
                    require(receiver == candidate && arguments.isEmpty() && calls.equals(List.of("read")),
                            "one backing inspection after original read");
                    calls.add("backing");
                    stack.add(((Heap) receiver).hostedObject());
                } else {
                    throw new AssertionError("Unexpected late reflection call: " + call);
                }
            } else if (instruction instanceof BranchInstruction branch) {
                Object value = pop(stack);
                boolean take = switch (branch.opcode()) {
                    case IFEQ -> value.equals(0);
                    case IFNE -> !value.equals(0);
                    case IFNULL -> value == null;
                    case IFNONNULL -> value != null;
                    default -> throw new AssertionError("Unexpected late branch: " + branch);
                };
                if (take) pc = labels.get(branch.target());
            } else if (instruction.opcode() == Opcode.ARETURN) {
                Object value = pop(stack);
                require(stack.isEmpty(), "balanced late return stack");
                return new Result(value, calls);
            } else {
                throw new AssertionError("Unexpected late instruction: " + instruction);
            }
        }
        throw new AssertionError("Unbounded late reflection decision");
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 1, "overlay argument");
        try (var jar = new JarFile(Path.of(args[0]).toFile())) {
            var payload = jar.stream().filter(entry -> !entry.isDirectory()).map(entry -> entry.getName()).collect(java.util.stream.Collectors.toSet());
            require(payload.equals(Set.of("META-INF/MANIFEST.MF", "META-INF/upstream-toolchain-LICENSE.txt",
                    "META-INF/source/" + DECODER + ".java", DECODER + ".class",
                    "META-INF/source/" + SUPPORT + ".java", REFLECTION + ".class")), "exact two-class overlay payload");
            var model = ClassFile.of().parse(jar.getInputStream(jar.getJarEntry(DECODER + ".class")).readAllBytes());
            var code = model.methods().stream().filter(candidate -> candidate.methodName().equalsString("handleLoadFieldNode"))
                    .findFirst().orElseThrow().code().orElseThrow();
            require(code.exceptionHandlers().isEmpty(), "no suppressed exceptions");
            var methods = List.of(method(MethodVariant.ORIGINAL_METHOD), method(SubstrateCompilationDirectives.DEOPT_TARGET_METHOD),
                    method(SubstrateCompilationDirectives.RUNTIME_COMPILED_METHOD), method(null));
            var hosted = JVMCI.getRuntime().getHostJVMCIBackend().getConstantReflection().forString("hosted marker");
            require(hosted != null && !hosted.isNull(), "real hosted object marker");
            Fold[] candidates = {null, new Fold(JavaConstant.forInt(42)), new Fold(JavaConstant.NULL_POINTER),
                    new Fold(new Heap(hosted)), new Fold(new Heap(null))};
            int cases = 0;
            for (var method : methods) for (boolean isStatic : List.of(false, true)) {
                for (var candidate : candidates) for (boolean intrinsic : List.of(false, true)) {
                    var result = evaluate(code, method, isStatic, candidate, intrinsic);
                    boolean reject = candidate == null || (SubstrateCompilationDirectives.isRuntimeCompiledMethod(method)
                            && candidate.constant() instanceof Heap heap && heap.hostedObject() == null);
                    require(result.value() == (reject ? (intrinsic ? Marker.INTRINSIC : Marker.LOAD) : candidate),
                            "original candidate/fallback identity");
                    var calls = new ArrayList<String>();
                    if (isStatic) calls.add("initialize");
                    calls.add("simulate");
                    if (reject) calls.add("intercept");
                    require(result.calls().equals(calls), "exact initialization/simulation/interception order and count");
                    cases++;
                }
            }
            System.out.println("PASS " + cases + " emitted decision cases / " + checks + " checks; compiler-service markers, not native-image execution");
            int beforeLate = checks;
            var reflection = ClassFile.of().parse(jar.getInputStream(jar.getJarEntry(REFLECTION + ".class")).readAllBytes());
            require(reflection.methods().stream().map(m -> m.methodName().stringValue()).collect(java.util.stream.Collectors.toSet())
                    .equals(Set.of("<init>", "readFieldValue")), "no extra provider methods or initializer");
            var lateCode = reflection.methods().stream().filter(m -> m.methodName().equalsString("readFieldValue"))
                    .findFirst().orElseThrow().code().orElseThrow();
            require(lateCode.exceptionHandlers().isEmpty(), "late exceptions propagate unchanged");
            Object[] lateCandidates = {null, JavaConstant.NULL_POINTER, hosted, JavaConstant.forInt(42),
                    JavaConstant.forLong(-1), JavaConstant.forFloat(Float.NaN), JavaConstant.forDouble(-0.0),
                    new Heap(hosted), new Heap(hosted, true), new Heap(null), new Heap(null, true)};
            int lateCases = 0;
            for (Object receiver : new Object[]{null, new Heap(hosted)}) {
                for (Object candidate : lateCandidates) {
                    var result = evaluateReflection(lateCode, candidate, receiver, null);
                    require(result.value() == (candidate instanceof Heap heap && heap.hostedObject() == null ? null : candidate),
                            "late hostless-only rejection and all other result identities");
                    require(result.calls().equals(candidate instanceof Heap ? List.of("read", "backing") : List.of("read")),
                            "exact late read/backing sequence");
                    lateCases++;
                }
                var failure = new IllegalStateException("original field read failure");
                try {
                    evaluateReflection(lateCode, new Heap(null), receiver, failure);
                    throw new AssertionError("Original read exception swallowed");
                } catch (IllegalStateException actual) {
                    require(actual == failure, "exact original exception identity");
                }
                lateCases++;
            }
            System.out.println("PASS " + lateCases + " late reflection cases / " + (checks - beforeLate)
                    + " checks; emitted method with compiler-service markers, not native-image execution");
        }
    }
}
