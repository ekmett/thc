/*
 * SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
 */

import java.lang.reflect.Proxy;
import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import com.oracle.graal.pointsto.meta.HostedProviders;
import com.oracle.svm.hosted.NativeImageOptions;
import com.oracle.svm.shared.option.HostedOptionKey;
import jdk.graal.compiler.api.replacements.SnippetReflectionProvider;
import jdk.graal.compiler.api.replacements.SnippetTemplateCache;
import jdk.graal.compiler.core.common.GraalOptions;
import jdk.graal.compiler.core.common.spi.*;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.core.common.type.StampPair;
import jdk.graal.compiler.nodes.*;
import jdk.graal.compiler.nodes.graphbuilderconf.*;
import jdk.graal.compiler.nodes.spi.*;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.replacements.*;
import jdk.graal.compiler.word.WordTypes;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.runtime.JVMCI;

/** Executes the real snippet invocation plugin; it does not build a native image. */
public final class SnippetProvidersTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static <T> T marker(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> { throw new AssertionError("Unexpected marker call: " + method); }));
    }

    private static final class Registry {
        final Map<Class<?>, SnippetTemplateCache> templates = new HashMap<>();
        OptionValues registeredOptions;
        final Replacements replacements = (Replacements) Proxy.newProxyInstance(Replacements.class.getClassLoader(),
                new Class<?>[]{Replacements.class}, (proxy, called, args) -> switch (called.getName()) {
                    case "registerSnippet" -> { registeredOptions = (OptionValues) args[4]; yield null; }
                    case "getSnippetParameterInfo" -> new SnippetParameterInfo((ResolvedJavaMethod) args[0]);
                    case "getSnippetTemplateCache" -> templates.get(args[0]);
                    default -> throw new AssertionError("Unexpected registry call: " + called);
                });
    }

    private static OptionValues templateOptions(SnippetTemplate.AbstractTemplates template) throws Exception {
        var field = SnippetTemplate.AbstractTemplates.class.getDeclaredField("options");
        field.setAccessible(true);
        return (OptionValues) field.get(template);
    }

    private static SnippetSubstitutionNode invoke(HostedProviders providers, ResolvedJavaMethod target) {
        var emitted = new SnippetSubstitutionNode[1];
        var context = (GraphBuilderContext) Proxy.newProxyInstance(GraphBuilderContext.class.getClassLoader(),
                new Class<?>[]{GraphBuilderContext.class}, (proxy, called, args) -> switch (called.getName()) {
                    case "isPluginEnabled" -> true;
                    case "getReplacements" -> providers.getReplacements();
                    case "getInvokeKind" -> CallTargetNode.InvokeKind.Static;
                    case "getMethod" -> target;
                    case "bci" -> 0;
                    case "getAssumptions" -> null;
                    case "getInvokeReturnStamp" -> StampPair.createSingle(StampFactory.forKind(JavaKind.Int));
                    case "addPush" -> { emitted[0] = (SnippetSubstitutionNode) args[1]; yield args[1]; }
                    default -> throw new AssertionError("Unexpected parser call: " + called);
                });
        var plugin = new SnippetSubstitutionInvocationPlugin<StringLatin1Snippets.Templates>(
                StringLatin1Snippets.Templates.class, "indexOf", byte[].class, int.class, byte[].class, int.class, int.class) {
            @Override public SnippetTemplate.SnippetInfo getSnippet(StringLatin1Snippets.Templates templates) {
                return templates.indexOf;
            }
        };
        ValueNode[] arguments = {ConstantNode.defaultForKind(JavaKind.Object), ConstantNode.forInt(0),
                ConstantNode.defaultForKind(JavaKind.Object), ConstantNode.forInt(0), ConstantNode.forInt(0)};
        check(plugin.execute(context, target, null, arguments), "real invocation plugin accepted");
        check(emitted[0] != null, "real snippet substitution emitted");
        return emitted[0];
    }

    private static Object capturedTemplate(SnippetSubstitutionNode node) throws Exception {
        var field = SnippetSubstitutionNode.class.getDeclaredField("templates");
        field.setAccessible(true);
        return field.get(node);
    }

    private static void checkOverlay(String path) throws Exception {
        String base = "com/oracle/svm/graal/hosted/runtimecompilation/";
        String feature = base + "RuntimeCompilationFeature";
        try (var jar = new JarFile(Path.of(path).toFile())) {
            var payload = jar.stream().filter(entry -> !entry.isDirectory()).map(entry -> entry.getName()).collect(java.util.stream.Collectors.toSet());
            check(payload.equals(Set.of("META-INF/MANIFEST.MF", "META-INF/upstream-toolchain-LICENSE.txt",
                    "META-INF/source/" + feature + ".java", feature + ".class", feature + "$Options.class",
                    feature + "$AllowInliningPredicate.class", feature + "$AllowInliningPredicate$InlineDecision.class",
                    feature + "$AnyRuntimeCompilationEnabled.class", feature + "$OnlyTruffleRuntimeCompilationEnabled.class",
                    feature + "$RuntimeCompilationCandidatePredicate.class", feature + "$RuntimeCompilationAnalysisPolicy.class",
                    feature + "$RuntimeCompilationInlineBeforeAnalysisPolicy.class", feature + "$RuntimeCompilationParsingSupport.class",
                    base + "GraphPrepareMetaAccessExtensionProvider.class", base + "RuntimeCompilationAlwaysInlineScope.class")),
                    "exact one-source-file overlay payload");
            var model = ClassFile.of().parse(jar.getInputStream(jar.getJarEntry(feature + ".class")).readAllBytes());
            var method = model.methods().stream().filter(candidate -> candidate.methodName().equalsString("initializeAnalysisProviders")).findFirst().orElseThrow();
            var calls = method.code().orElseThrow().elementStream().filter(InvokeInstruction.class::isInstance)
                    .map(InvokeInstruction.class::cast).map(call -> call.owner().asInternalName() + "." + call.name().stringValue() + call.type().stringValue()).toList();
            String providers = "com/oracle/graal/pointsto/meta/HostedProviders";
            check(calls.equals(List.of(
                    "com/oracle/graal/pointsto/BigBang.getProviders(Lcom/oracle/svm/common/meta/MethodVariant$MethodVariantKey;)L" + providers + ";",
                    providers + ".getConstantFieldProvider()Ljdk/graal/compiler/core/common/spi/ConstantFieldProvider;",
                    "java/util/function/Function.apply(Ljava/lang/Object;)Ljava/lang/Object;",
                    providers + ".copyWith(Ljdk/graal/compiler/core/common/spi/ConstantFieldProvider;)L" + providers + ";",
                    providers + ".getReplacements()Ljdk/graal/compiler/nodes/spi/Replacements;",
                    providers + ".copyWith(Ljdk/graal/compiler/nodes/spi/Replacements;)L" + providers + ";",
                    providers + ".getGraphBuilderPlugins()Ljdk/graal/compiler/nodes/graphbuilderconf/GraphBuilderConfiguration$Plugins;",
                    providers + ".setGraphBuilderPlugins(Ljdk/graal/compiler/nodes/graphbuilderconf/GraphBuilderConfiguration$Plugins;)V")),
                    "emitted setup retains one wrapper call and ordered replacement/plugin binding");
        }
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 1, "overlay argument");
        checkOverlay(args[0]);
        var backend = JVMCI.getRuntime().getHostJVMCIBackend();
        var meta = backend.getMetaAccess();
        var hostedRegistry = new Registry();
        var runtimeRegistry = new Registry();
        var fieldProvider = marker(ConstantFieldProvider.class);
        var wrappedFieldProvider = marker(ConstantFieldProvider.class);
        var original = new HostedProviders(meta, backend.getCodeCache(), backend.getConstantReflection(), fieldProvider,
                marker(ForeignCallsProvider.class), marker(LoweringProvider.class), hostedRegistry.replacements,
                marker(StampProvider.class), marker(SnippetReflectionProvider.class), new WordTypes(meta, JavaKind.Long),
                marker(PlatformConfigurationProvider.class), marker(MetaAccessExtensionProvider.class), marker(LoopsDataProvider.class));
        var runtime = original.copyWith(runtimeRegistry.replacements);
        var hostedMap = OptionValues.newOptionMap();
        hostedMap.put(NativeImageOptions.NumberOfThreads, 2);
        hostedMap.put(GraalOptions.EarlyGVN, false);
        var hostedOptions = new OptionValues(hostedMap);
        var runtimeMap = OptionValues.newOptionMap();
        runtimeMap.put(GraalOptions.EarlyGVN, true);
        runtimeMap.put(GraalOptions.TrackNodeSourcePosition, true);
        var runtimeOptions = new OptionValues(runtimeMap);
        var hostedTemplates = new StringLatin1Snippets.Templates(hostedOptions, original);
        var runtimeTemplates = new StringLatin1Snippets.Templates(runtimeOptions, runtime);
        hostedRegistry.templates.put(StringLatin1Snippets.Templates.class, hostedTemplates);
        runtimeRegistry.templates.put(StringLatin1Snippets.Templates.class, runtimeTemplates);
        check(hostedRegistry.registeredOptions == hostedOptions, "hosted snippet registration keeps hosted options");
        check(runtimeRegistry.registeredOptions == runtimeOptions, "runtime snippet registration keeps runtime options");
        var plugins = new GraphBuilderConfiguration.Plugins(new InvocationPlugins());
        runtime.setGraphBuilderPlugins(plugins);

        // The stock setup swaps the field provider and plugins, but not replacements.
        var stock = original.copyWith(wrappedFieldProvider);
        stock.setGraphBuilderPlugins(runtime.getGraphBuilderPlugins());
        var target = meta.lookupJavaMethod(StringLatin1Snippets.class.getMethod("indexOf", byte[].class, int.class, byte[].class, int.class, int.class));
        check(capturedTemplate(invoke(stock, target)) == hostedTemplates, "stock parser captures hosted template");
        check(templateOptions(hostedTemplates).containsKey(NativeImageOptions.NumberOfThreads), "stock retains demonstrated hosted-only option");

        // Candidate changes only the replacements cache before the existing plugin write.
        var candidate = original.copyWith(wrappedFieldProvider).copyWith(runtime.getReplacements());
        candidate.setGraphBuilderPlugins(runtime.getGraphBuilderPlugins());
        check(capturedTemplate(invoke(candidate, target)) == runtimeTemplates, "candidate captures runtime template");
        check(templateOptions(runtimeTemplates) == runtimeOptions, "runtime options retained by identity, not filtered");
        check(runtimeOptions.getMap().size() == 2, "all explicit runtime options retained");
        check(GraalOptions.EarlyGVN.getValue(runtimeOptions), "runtime compiler override retained");
        check(GraalOptions.TrackNodeSourcePosition.getValue(runtimeOptions), "runtime source-position override retained");
        for (var key : runtimeOptions.getMap().getKeys()) check(!(key instanceof HostedOptionKey<?>), "no hosted option in runtime fixture");
        check(hostedOptions.containsKey(NativeImageOptions.NumberOfThreads) && !GraalOptions.EarlyGVN.getValue(hostedOptions), "hosted options untouched");
        check(original.getReplacements() == hostedRegistry.replacements, "ORIGINAL replacements untouched");
        check(original.getConstantFieldProvider() == fieldProvider, "ORIGINAL field provider untouched");
        check(candidate.getConstantFieldProvider() == wrappedFieldProvider, "caller field wrapper retained");
        check(candidate.getGraphBuilderPlugins() == plugins, "runtime plugin identity retained");
        for (var getter : HostedProviders.class.getMethods()) {
            if (getter.getParameterCount() == 0 && getter.getName().startsWith("get")
                    && !getter.getName().equals("getClass") && !getter.getName().equals("getReplacements")) {
                check(getter.invoke(stock) == getter.invoke(candidate), "other provider identity retained: " + getter.getName());
            }
        }
        System.out.println("PASS " + checks + " snippet ownership controls (not native-image execution)");
    }
}
