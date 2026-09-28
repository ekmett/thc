// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Label;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.BranchInstruction;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.LabelTarget;
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.AccessFlag;
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

    // Exact bytecode proof for fieldless marker/utility singletons and generated
    // non-adoptable operation singletons, not arbitrary objects or nodes.
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

    // Inspect enum initialization and every constructor/helper it invokes.
    // Only literal metadata and exact enum construction are admitted.
    // The two external enum inputs below were
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
                    if (owner.equals("java/lang/Enum") && member.equals("<init>")) continue;
                    return false;
                } else if (instruction instanceof FieldInstruction field) {
                    var owner = field.owner().asInternalName();
                    if (owner.equals(name)) continue;
                    if (field.opcode().name().equals("GETSTATIC")
                        && (owner.equals("thc/runtime/CoreKind") || owner.equals("thc/runtime/ManagedAddressRead"))) continue;
                    return false;
                } else if (instruction instanceof NewObjectInstruction allocation) {
                    var type = allocation.className().asInternalName();
                    if (!type.equals(name)) return false;
                }
            }
        }
        return true;
    }

    private static boolean field(Instruction instruction, Opcode opcode, String owner, String name, String type) {
        return instruction instanceof FieldInstruction access && access.opcode() == opcode
            && access.owner().asInternalName().equals(owner) && access.name().equalsString(name)
            && access.type().equalsString(type);
    }

    private static boolean call(Instruction instruction, Opcode opcode, String owner, String name, String type) {
        return instruction instanceof InvokeInstruction invoke && invoke.opcode() == opcode
            && invoke.owner().asInternalName().equals(owner) && invoke.name().equalsString(name)
            && invoke.type().equalsString(type);
    }

    // A switch holder may depend ONLY on an enum already selected by the other
    // policies. Prove its values() is just the compiler's array clone as well;
    // this category never authorizes an enum initializer itself.
    private static boolean switchEnum(String name, Set<String> approved) {
        var model = classes.get(name);
        if (!approved.contains(name) || model == null || !model.flags().has(AccessFlag.ENUM)
            || model.superclass().isEmpty() || !model.superclass().get().asInternalName().equals("java/lang/Enum")) return false;
        var array = "[L" + name + ";";
        var values = model.methods().stream().filter(m -> m.methodName().equalsString("values")
            && m.methodType().equalsString("()" + array) && m.flags().has(AccessFlag.STATIC)).findFirst();
        if (values.isEmpty() || values.get().code().isEmpty()
            || !values.get().code().get().exceptionHandlers().isEmpty()) return false;
        var body = instructions(values.get());
        return body.size() == 4 && body.get(0) instanceof FieldInstruction source
            && field(source, Opcode.GETSTATIC, name, "$VALUES", array)
            && model.fields().stream().anyMatch(f -> f.fieldName().equalsString("$VALUES")
                && f.fieldType().equalsString(array) && f.flags().has(AccessFlag.STATIC)
                && f.flags().has(AccessFlag.FINAL) && f.flags().has(AccessFlag.SYNTHETIC))
            && call(body.get(1), Opcode.INVOKEVIRTUAL, array, "clone", "()Ljava/lang/Object;")
            && body.get(2) instanceof TypeCheckInstruction cast && cast.opcode() == Opcode.CHECKCAST
            && cast.type().asInternalName().equals(array) && body.get(3).opcode() == Opcode.ARETURN;
    }

    // Exact javac synthetic enum-switch grammar: fresh private-to-the-holder
    // int[] maps, ordinal/literal stores, and only forward NoSuchFieldError
    // handlers. No calls to arbitrary helpers, resource allocation or writes to
    // another class. A changed compiler shape fails closed for manual review.
    private static boolean switchHolder(ClassModel model, Set<String> approved) {
        if (model.flags().flagsMask() != (ClassFile.ACC_SUPER | ClassFile.ACC_SYNTHETIC)
            || model.superclass().isEmpty() || !model.superclass().get().asInternalName().equals("java/lang/Object")
            || !model.interfaces().isEmpty() || model.fields().isEmpty() || model.methods().size() != 1) return false;
        var fields = new java.util.HashSet<String>();
        for (var f : model.fields()) {
            if (f.flags().flagsMask() != (ClassFile.ACC_STATIC | ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC)
                || !f.fieldType().equalsString("[I") || !f.fieldName().stringValue().startsWith("$SwitchMap$")) return false;
            fields.add(f.fieldName().stringValue());
        }
        var initializer = model.methods().getFirst();
        if (!initializer.methodName().equalsString("<clinit>") || !initializer.methodType().equalsString("()V")
            || initializer.flags().flagsMask() != ClassFile.ACC_STATIC || initializer.code().isEmpty()) return false;
        var code = initializer.code().get();
        var body = instructions(initializer);
        var labels = new java.util.HashMap<Label, Integer>();
        int index = 0;
        for (var element : code) {
            if (element instanceof LabelTarget target) labels.put(target.label(), index);
            else if (element instanceof Instruction) index++;
        }
        var owner = model.thisClass().asInternalName();
        var maps = new LinkedHashMap<String, String>();
        int assignments = 0;
        for (int i = 0; i < body.size() - 1;) {
            if (body.get(i) instanceof InvokeInstruction values && values.opcode() == Opcode.INVOKESTATIC) {
                var enumName = values.owner().asInternalName();
                var map = "$SwitchMap$" + enumName.replace('/', '$');
                if (i + 4 >= body.size() || !fields.contains(map) || maps.containsKey(map)
                    || !switchEnum(enumName, approved)
                    || !call(values, Opcode.INVOKESTATIC, enumName, "values", "()[L" + enumName + ";")
                    || body.get(i + 1).opcode() != Opcode.ARRAYLENGTH
                    || !(body.get(i + 2) instanceof NewPrimitiveArrayInstruction array) || array.typeKind() != TypeKind.INT
                    || !field(body.get(i + 3), Opcode.PUTSTATIC, owner, map, "[I")) return false;
                maps.put(map, enumName);
                i += 4;
            } else {
                if (i + 7 >= body.size() || !(body.get(i) instanceof FieldInstruction load)) return false;
                var map = load.name().stringValue();
                var enumName = maps.get(map);
                if (enumName == null || !field(load, Opcode.GETSTATIC, owner, map, "[I")
                    || !(body.get(i + 1) instanceof FieldInstruction constant)
                    || !field(constant, Opcode.GETSTATIC, enumName, constant.name().stringValue(), "L" + enumName + ";")
                    || !classes.get(enumName).fields().stream().anyMatch(f -> f.fieldName().equalsString(constant.name().stringValue())
                        && f.fieldType().equalsString(constant.type().stringValue()) && f.flags().has(AccessFlag.ENUM)
                        && f.flags().has(AccessFlag.STATIC) && f.flags().has(AccessFlag.FINAL))
                    || !call(body.get(i + 2), Opcode.INVOKEVIRTUAL, enumName, "ordinal", "()I")
                    || !(body.get(i + 3) instanceof ConstantInstruction literal)
                    || !(literal.constantValue() instanceof Integer value) || value <= 0
                    || body.get(i + 4).opcode() != Opcode.IASTORE
                    || !(body.get(i + 5) instanceof BranchInstruction branch) || branch.opcode() != Opcode.GOTO
                    || !Integer.valueOf(i + 7).equals(labels.get(branch.target()))
                    || !(body.get(i + 6) instanceof StoreInstruction store)
                    || store.typeKind() != TypeKind.REFERENCE || store.slot() != 0) return false;
                int start = i;
                if (code.exceptionHandlers().stream().filter(handler -> handler.catchType().isPresent()
                    && handler.catchType().get().asInternalName().equals("java/lang/NoSuchFieldError")
                    && Integer.valueOf(start).equals(labels.get(handler.tryStart()))
                    && Integer.valueOf(start + 5).equals(labels.get(handler.tryEnd()))
                    && Integer.valueOf(start + 6).equals(labels.get(handler.handler()))).count() != 1) return false;
                assignments++;
                i += 7;
            }
        }
        return !body.isEmpty() && body.getLast().opcode() == Opcode.RETURN && maps.keySet().equals(fields)
            && assignments > 0 && code.exceptionHandlers().size() == assignments;
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
        if (args[1].equals("switches")) {
            var approved = new java.util.HashSet<String>();
            for (var name : args[2].split(",")) if (!name.isEmpty()) approved.add(name.replace('.', '/'));
            System.out.println(String.join(",", classes.values().stream().filter(m -> switchHolder(m, approved))
                .map(m -> m.thisClass().asInternalName().replace('/', '.')).filter(n -> !approved.contains(n.replace('.', '/')))
                .sorted().toList()));
        } else if (args[1].equals("enums")) {
            System.out.println(String.join(",", classes.values().stream().filter(ClassInitializationInventory::metadataEnum)
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
