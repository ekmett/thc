// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import com.oracle.truffle.api.TruffleLanguage;
import thc.runtime.UnsupportedCore;

/**
 * Native Image retains an application's prepared AST factory from Truffle
 * preinitialization. Runtime loads allocate fresh CAFs and native linkage under
 * the same Language and a freshly patched State. Compilation and native bundling
 * are separate from this body-free preparation boundary.
 */
public final class NativeExecutable {
    // Hosted initialization freezes this guard; JVM and generic images keep source parsing.
    static final boolean IMAGE_BOUND = ImageInfo.inImageBuildtimeCode()
        && Boolean.getBoolean("thc.nativeImage.executable");
    private static NativeExecutable application;
    private Map<String,Object> core;
    private Language.PreparedRoot factory;
    private final Main.ProgramArguments arguments;
    private final Map<String,String> properties;

    private NativeExecutable(Map<String,Object> core, Main.ProgramArguments arguments, Map<String,String> properties) {
        this.core = core; this.arguments = arguments; this.properties = properties;
    }

    /** The hosted feature captures reader-free Core before Truffle preinitializes THC. */
    public static void captureForImage() throws IOException {
        if (application != null) throw new IllegalStateException("Native executable already captured");
        try (var input = NativeExecutable.class.getResourceAsStream("/thc-native-executable.json")) {
            if (input == null) throw new IllegalStateException("Native executable binding is missing");
            var configuration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            // Apply the trusted image binding before selecting Core or initializing lowering classes.
            initializeProperties(configuration);
            application = capture(configuration);
        }
    }

    @SuppressWarnings("unchecked") static NativeExecutable capture(String configuration) {
        var fixed = Main.launcherArtifactVerification(launcherArguments(configuration, new String[0]));
        var args = fixed.arguments();
        var properties = properties(configuration);
        String backend = properties.getOrDefault("thc.backend", "ast");
        String async = properties.getOrDefault("thc.asyncExceptions", "false");
        if (!backend.equals("ast") || !async.equals("false"))
            throw new IllegalArgumentException("Captured executable Core currently requires synchronous AST preparation");
        var request = (Map<String,Object>) Json.parse(CoreModules.request(Arrays.asList(args[1].split(",", -1)),
            args[2], true, false, backend, false, true, args[3], false, fixed.verifyArtifacts()));
        // The shared selector closes readers and retains neither archive authority nor lazy bodies.
        var core = CoreModules.selectedModules(request, args[2]);
        return new NativeExecutable(core, Main.launcherArguments(args, 4), properties);
    }

    /** Called inside THC's real preinitialization while its preparation State is entered. */
    static void prepareForImage(Language language) {
        var captured = application;
        if (captured == null) return;
        if (captured.core == null) throw new IllegalStateException("Native executable preparation is already complete or failed");
        var core = captured.core;
        captured.core = null;
        // No workers enter the preinitialized context, and no guest factory/body runs here.
        captured.factory = (Language.PreparedRoot) language.preparedRoot(core, 1).getRootNode();
    }

    /** Captured application loads require the factory saved by real context preinitialization. */
    Value load(Context context) {
        var prepared = factory;
        if (prepared == null)
            throw new IllegalStateException("Native executable factory was not prepared during context preinitialization");
        context.initialize("thc"); context.enter();
        try {
            var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
            if (prepared.getLanguage(Language.class) != language)
                throw new UnsupportedCore("Reusable AST instance requires its prepared and current language");
            return context.asValue(prepared.getCallTarget().call());
        } finally { context.leave(); }
    }

    public static void main(String[] guest) {
        if (application == null) throw new IllegalStateException("Native executable Core was not captured during image construction");
        application.properties.forEach(System::setProperty);
        var defaults = application.arguments.arguments();
        var combined = Arrays.copyOf(defaults, defaults.length + guest.length);
        System.arraycopy(guest, 0, combined, defaults.length, guest.length);
        var arguments = new Main.ProgramArguments(application.arguments.programName(), combined);
        Main.processExit(() -> Main.runExecutable(arguments, false, application::load));
    }

    static String[] launcherArguments(String configuration, String[] guest) {
        var value = Json.parse(configuration);
        if (value instanceof Map<?, ?> binding) value = binding.get("arguments");
        if (!(value instanceof List<?> fixed))
            throw new IllegalArgumentException("Expected a bound --run-executable prefix");
        var result = new String[fixed.size() + guest.length];
        for (int i = 0; i < fixed.size(); i++) {
            if (!(fixed.get(i) instanceof String argument) || argument.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Invalid native executable argument " + i);
            result[i] = argument;
        }
        var prefix = Main.launcherArtifactVerification(java.util.Arrays.copyOf(result, fixed.size())).arguments();
        if (prefix.length < 6 || !"--run-executable".equals(prefix[0]) || !"--".equals(prefix[4]))
            throw new IllegalArgumentException("Expected a bound --run-executable prefix");
        for (int i = 0; i < 6; i++) if (prefix[i].isBlank())
            throw new IllegalArgumentException("Invalid native executable argument " + i);
        System.arraycopy(guest, 0, result, fixed.size(), guest.length);
        return result;
    }

    /** The trusted image binding configures THC, never consuming guest -D flags. */
    static void initializeProperties(String configuration) { properties(configuration).forEach(System::setProperty); }

    private static Map<String,String> properties(String configuration) {
        if (!(Json.parse(configuration) instanceof Map<?, ?> binding) || !binding.containsKey("properties")) return Map.of();
        if (!(binding.get("properties") instanceof Map<?, ?> properties))
            throw new IllegalArgumentException("Expected native executable properties");
        var selected = new LinkedHashMap<String,String>();
        for (var entry : properties.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isEmpty() || !(entry.getValue() instanceof String value))
                throw new IllegalArgumentException("Invalid native executable property");
            selected.put(key, value);
        }
        return Map.copyOf(selected);
    }
}
