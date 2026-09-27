// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarFile;

// Read-only diagnostic of the pinned JDK ClassFile API. Does not load classes.
public final class ClassInitializationInventory {
    private static final Map<String, ClassModel> classes = new LinkedHashMap<>();
    private static final Map<String, ClassModel> externalClasses = new LinkedHashMap<>();

    private static List<Instruction> instructions(MethodModel method) {
        return method.code().orElseThrow().elementStream().filter(Instruction.class::isInstance)
            .map(Instruction.class::cast).toList();
    }

    private static boolean constructorCall(Instruction instruction, String owner) {
        return instruction instanceof InvokeInstruction invoke && invoke.opcode().name().equals("INVOKESPECIAL")
            && invoke.owner().asInternalName().equals(owner) && invoke.name().equalsString("<init>")
            && invoke.type().equalsString("()V");
    }

    private static boolean companionHolder(ClassModel model) {
        var name = model.thisClass().asInternalName();
        if (!statelessHierarchy(model.superclass().orElseThrow().asInternalName())
            || !model.interfaces().stream().allMatch(i -> statelessHierarchy(i.asInternalName()))) return false;
        var fields = model.fields().stream().filter(f -> f.flags().has(java.lang.reflect.AccessFlag.STATIC)).toList();
        var companionName = name + "$Companion";
        if (fields.size() != 1 || !fields.getFirst().fieldName().equalsString("Companion")
            || !fields.getFirst().fieldType().equalsString("L" + companionName + ";")
            || !fields.getFirst().flags().has(java.lang.reflect.AccessFlag.FINAL)) return false;
        var companion = classes.get(companionName);
        if (companion == null || !companion.fields().isEmpty()
            || !companion.superclass().orElseThrow().asInternalName().equals("java/lang/Object")
            || !statelessHierarchy(companionName)) return false;
        var constructors = companion.methods().stream().filter(m -> m.methodName().equalsString("<init>")).toList();
        if (constructors.size() != 2) return false;
        String marker = "(Lkotlin/jvm/internal/DefaultConstructorMarker;)V";
        for (var ctor : constructors) {
            if (!ctor.code().orElseThrow().exceptionHandlers().isEmpty()) return false;
            var body = instructions(ctor);
            String parent;
            if (ctor.methodType().equalsString("()V")) parent = "java/lang/Object";
            else if (ctor.methodType().equalsString(marker)) parent = companionName;
            else return false;
            if (body.size() != 3 || !body.get(0).opcode().name().equals("ALOAD_0")
                || !constructorCall(body.get(1), parent) || !body.get(2).opcode().name().equals("RETURN")) return false;
        }
        var initializer = model.methods().stream().filter(m -> m.methodName().equalsString("<clinit>")).findFirst();
        if (initializer.isEmpty() || !initializer.get().code().orElseThrow().exceptionHandlers().isEmpty()) return false;
        var init = instructions(initializer.get());
        return init.size() == 6 && init.get(0) instanceof NewObjectInstruction allocation
            && allocation.className().asInternalName().equals(companionName)
            && init.get(1).opcode().name().equals("DUP") && init.get(2).opcode().name().equals("ACONST_NULL")
            && init.get(3) instanceof InvokeInstruction invoke && invoke.opcode().name().equals("INVOKESPECIAL")
            && invoke.owner().asInternalName().equals(companionName) && invoke.name().equalsString("<init>")
            && invoke.type().equalsString(marker) && init.get(4) instanceof FieldInstruction store
            && store.opcode().name().equals("PUTSTATIC") && store.owner().asInternalName().equals(name)
            && store.name().equalsString("Companion") && store.type().equalsString("L" + companionName + ";")
            && init.get(5).opcode().name().equals("RETURN");
    }

    // Exact bytecode proof for fieldless marker/utility singletons and generated
    // non-adoptable operation singletons, not arbitrary Kotlin objects or nodes.
    private static boolean markerSingleton(ClassModel model) {
        var name = model.thisClass().asInternalName();
        var parent = model.superclass().orElseThrow().asInternalName();
        boolean operation = name.startsWith("thc/runtime/BytecodeRootGen$") && name.endsWith("_Node")
            && parent.equals("com/oracle/truffle/api/nodes/Node");
        if ((!parent.equals("java/lang/Object") && !operation)
            || !model.interfaces().stream().allMatch(i -> statelessHierarchy(i.asInternalName()))
            || model.fields().size() != 1) return false;
        if (operation) {
            var adoptable = model.methods().stream().filter(m -> m.methodName().equalsString("isAdoptable")
                && m.methodType().equalsString("()Z")).findFirst();
            if (adoptable.isEmpty()) return false;
            var body = instructions(adoptable.get());
            if (body.size() != 2 || !body.get(0).opcode().name().equals("ICONST_0")
                || !body.get(1).opcode().name().equals("IRETURN")) return false;
        }
        var singletonField = operation ? "SINGLETON" : "INSTANCE";
        var field = model.fields().getFirst();
        if (!field.fieldName().equalsString(singletonField) || !field.fieldType().equalsString("L" + name + ";")
            || !field.flags().has(java.lang.reflect.AccessFlag.STATIC)
            || !field.flags().has(java.lang.reflect.AccessFlag.FINAL)) return false;
        var constructors = model.methods().stream().filter(m -> m.methodName().equalsString("<init>")).toList();
        var initializers = model.methods().stream().filter(m -> m.methodName().equalsString("<clinit>")).toList();
        if (constructors.size() != 1 || initializers.size() != 1) return false;
        var constructor = constructors.getFirst();
        var initializer = initializers.getFirst();
        if (!constructor.methodType().equalsString("()V") || !constructor.code().orElseThrow().exceptionHandlers().isEmpty()
            || !initializer.code().orElseThrow().exceptionHandlers().isEmpty()) return false;
        var ctor = instructions(constructor);
        var init = instructions(initializer);
        return ctor.size() == 3 && ctor.get(0).opcode().name().equals("ALOAD_0")
            && constructorCall(ctor.get(1), parent) && ctor.get(2).opcode().name().equals("RETURN")
            && init.size() == 5 && init.get(0) instanceof NewObjectInstruction allocation
            && allocation.className().asInternalName().equals(name) && init.get(1).opcode().name().equals("DUP")
            && constructorCall(init.get(2), name) && init.get(3) instanceof FieldInstruction store
            && store.opcode().name().equals("PUTSTATIC") && store.owner().asInternalName().equals(name)
            && store.name().equalsString(singletonField) && store.type().equalsString("L" + name + ";")
            && init.get(4).opcode().name().equals("RETURN");
    }

    private static boolean statelessHierarchy(String name) {
        ClassModel model = classes.get(name);
        if (model == null) {
            // The pinned truffle-api native-image.properties explicitly prepares
            // this API/runtime package. Do not generalize that to other libraries.
            if (name.startsWith("com/oracle/truffle/")) return true;
            model = externalClasses.get(name);
            if (model == null) {
                try (var input = ClassLoader.getSystemResourceAsStream(name + ".class")) {
                    if (input == null) return false;
                    model = ClassFile.of().parse(input.readAllBytes());
                    externalClasses.put(name, model);
                } catch (java.io.IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            }
        }
        if (model.methods().stream().anyMatch(m -> m.methodName().equalsString("<clinit>"))) return false;
        if (model.superclass().isPresent() && !statelessHierarchy(model.superclass().get().asInternalName())) return false;
        return model.interfaces().stream().allMatch(i -> statelessHierarchy(i.asInternalName()));
    }

    private static boolean emptyCompanion(String name) {
        var model = classes.get(name);
        if (model == null || !model.fields().isEmpty() || !statelessHierarchy(name)
            || !model.superclass().orElseThrow().asInternalName().equals("java/lang/Object")) return false;
        var ctors = model.methods().stream().filter(m -> m.methodName().equalsString("<init>")).toList();
        if (ctors.size() != 2) return false;
        for (var ctor : ctors) {
            if (!ctor.code().orElseThrow().exceptionHandlers().isEmpty()) return false;
            var body = instructions(ctor);
            var parent = ctor.methodType().equalsString("()V") ? "java/lang/Object" : name;
            if (!ctor.methodType().equalsString("()V")
                && !ctor.methodType().equalsString("(Lkotlin/jvm/internal/DefaultConstructorMarker;)V")) return false;
            if (body.size() != 3 || !body.get(0).opcode().name().equals("ALOAD_0")
                || !constructorCall(body.get(1), parent) || !body.get(2).opcode().name().equals("RETURN")) return false;
        }
        return true;
    }

    // Inspect enum initialization and every constructor/helper it invokes.
    // Only literal metadata, immutable Kotlin lists, exact enum construction and
    // an empty companion are admitted. The two external enum inputs below were
    // independently inspected (CoreKind tags; native-target byte order).
    private static boolean metadataEnum(ClassModel model) {
        if (!model.flags().has(java.lang.reflect.AccessFlag.ENUM)
            || !model.superclass().orElseThrow().asInternalName().equals("java/lang/Enum")
            || !model.interfaces().isEmpty()) return false;
        var name = model.thisClass().asInternalName();
        for (var method : model.methods()) {
            if (!method.methodName().equalsString("<clinit>") && !method.methodName().equalsString("<init>")
                && !method.methodName().equalsString("$values")) continue;
            if (!method.code().orElseThrow().exceptionHandlers().isEmpty()) return false;
            for (var instruction : instructions(method)) {
                if (instruction.opcode().name().equals("INVOKEDYNAMIC")) return false;
                if (instruction instanceof InvokeInstruction call) {
                    var owner = call.owner().asInternalName();
                    var member = call.name().stringValue();
                    if (owner.equals(name) && (member.equals("<init>") || member.equals("$values"))) continue;
                    if (owner.equals(name + "$Companion") && member.equals("<init>") && emptyCompanion(owner)) continue;
                    if (owner.equals("java/lang/Enum") && member.equals("<init>")) continue;
                    if (owner.equals("kotlin/enums/EnumEntriesKt") && member.equals("enumEntries")) continue;
                    if (owner.equals("kotlin/collections/CollectionsKt")
                        && (member.equals("listOf") || member.equals("emptyList"))) continue;
                    return false;
                } else if (instruction instanceof FieldInstruction field) {
                    var owner = field.owner().asInternalName();
                    if (owner.equals(name)) continue;
                    if (field.opcode().name().equals("GETSTATIC")
                        && (owner.equals("thc/runtime/CoreKind") || owner.equals("thc/runtime/ManagedAddressRead"))) continue;
                    return false;
                } else if (instruction instanceof NewObjectInstruction allocation) {
                    var type = allocation.className().asInternalName();
                    if (!type.equals(name) && !(type.equals(name + "$Companion") && emptyCompanion(type))) return false;
                }
            }
        }
        return true;
    }

    private static boolean metadataSwitch(ClassModel model) {
        var name = model.thisClass().asInternalName();
        if (!name.endsWith("$WhenMappings") || !model.superclass().orElseThrow().asInternalName().equals("java/lang/Object")
            || !model.interfaces().isEmpty()) return false;
        if (model.fields().stream().anyMatch(f -> !f.flags().has(java.lang.reflect.AccessFlag.STATIC)
            || !f.flags().has(java.lang.reflect.AccessFlag.FINAL) || !f.fieldType().equalsString("[I"))) return false;
        for (var method : model.methods()) {
            if (!method.methodName().equalsString("<clinit>")) return false;
            if (method.code().orElseThrow().exceptionHandlers().stream().anyMatch(h -> h.catchType().isEmpty()
                || !h.catchType().get().asInternalName().equals("java/lang/NoSuchFieldError"))) return false;
            for (var instruction : instructions(method)) {
                if (instruction.opcode().name().equals("INVOKEDYNAMIC") || instruction instanceof NewObjectInstruction) return false;
                if (instruction instanceof InvokeInstruction call) {
                    var owner = classes.get(call.owner().asInternalName());
                    if (owner == null || !metadataEnum(owner)
                        || !(call.name().equalsString("values") || call.name().equalsString("ordinal"))) return false;
                } else if (instruction instanceof FieldInstruction field) {
                    if (field.owner().asInternalName().equals(name)) continue;
                    var owner = classes.get(field.owner().asInternalName());
                    if (!field.opcode().name().equals("GETSTATIC") || owner == null || !metadataEnum(owner)) return false;
                }
            }
        }
        return true;
    }

    public static void main(String[] args) throws Exception {
        try (var jar = new JarFile(args[0])) {
            for (var entry : jar.stream().filter(e -> e.getName().startsWith("thc/") && e.getName().endsWith(".class")).toList()) {
                try (var input = jar.getInputStream(entry)) {
                    var model = ClassFile.of().parse(input.readAllBytes());
                    classes.put(model.thisClass().asInternalName(), model);
                }
            }
        }
        if (args[1].equals("enums")) {
            System.out.println(String.join(",", classes.values().stream().filter(m -> metadataEnum(m) || metadataSwitch(m))
                .map(m -> m.thisClass().asInternalName().replace('/', '.')).sorted().toList()));
        } else if (args[1].equals("enum-audit")) {
            classes.values().stream().filter(m -> m.flags().has(java.lang.reflect.AccessFlag.ENUM)).forEach(model -> {
                var dependencies = new java.util.TreeSet<String>();
                model.methods().stream().filter(m -> m.methodName().equalsString("<clinit>")
                    || m.methodName().equalsString("<init>")).forEach(method -> {
                    for (var instruction : instructions(method)) {
                        if (instruction instanceof InvokeInstruction call)
                            dependencies.add(call.owner().asInternalName() + "." + call.name() + call.type());
                        else if (instruction instanceof FieldInstruction field
                            && !field.owner().asInternalName().equals(model.thisClass().asInternalName()))
                            dependencies.add(field.opcode() + " " + field.owner().asInternalName() + "." + field.name());
                        else if (instruction instanceof NewObjectInstruction allocation
                            && !allocation.className().asInternalName().equals(model.thisClass().asInternalName()))
                            dependencies.add("NEW " + allocation.className().asInternalName());
                    }
                });
                System.out.println(model.thisClass().asInternalName() + " " + dependencies);
            });
        } else if (args[1].equals("stateless")) {
            System.out.println(String.join(",", classes.keySet().stream().filter(ClassInitializationInventory::statelessHierarchy)
                .map(n -> n.replace('/', '.')).sorted().toList()));
        } else if (args[1].equals("markers")) {
            System.out.println(String.join(",", classes.values().stream().filter(ClassInitializationInventory::markerSingleton)
                .map(m -> m.thisClass().asInternalName().replace('/', '.')).sorted().toList()));
        } else if (args[1].equals("companions")) {
            System.out.println(String.join(",", classes.values().stream().filter(ClassInitializationInventory::companionHolder)
                .map(m -> m.thisClass().asInternalName().replace('/', '.')).sorted().toList()));
        } else if (args[1].equals("external-parents")) {
            var external = new java.util.TreeSet<String>();
            classes.forEach((name, model) -> {
                if (!statelessHierarchy(name)) return;
                model.superclass().ifPresent(s -> { if (!classes.containsKey(s.asInternalName())) external.add(s.asInternalName()); });
                model.interfaces().forEach(i -> { if (!classes.containsKey(i.asInternalName())) external.add(i.asInternalName()); });
            });
            external.forEach(System.out::println);
        } else {
            classes.forEach((name, model) -> {
                if (model.methods().stream().anyMatch(m -> m.methodName().equalsString("<clinit>"))) {
                    System.out.println(name.replace('/', '.'));
                    model.fields().stream().filter(f -> f.flags().has(java.lang.reflect.AccessFlag.STATIC))
                        .forEach(f -> System.out.println("  " + f.fieldName() + " " + f.fieldType()));
                }
            });
        }
    }
}
