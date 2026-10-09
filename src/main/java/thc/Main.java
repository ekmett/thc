// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import thc.runtime.NativeSignalTransport;

/** Owning launcher contexts and the existing integer/IO embedding boundaries. */
public final class Main {
    private Main() {}
    // Snapshot before guest pins affect HotSpot's thread-local processor query.
    private static final String LAUNCHER_COMPILER_THREADS = Runtime.getRuntime().availableProcessors() >= 4 ? "2" : "1";

    public static String defaultBackend() {
        String environment = System.getenv("THC_BACKEND");
        return System.getProperty("thc.backend", environment == null ? "bytecode" : environment);
    }

    public static Context.Builder withContextProfile(Context.Builder builder, ContextProfile profile) {
        // Native Haskell providers cannot run under an inherited managed LLVM engine.
        // Do not set llvm.managed=false: Community LLVM does not expose that option.
        var managed = System.getProperty("polyglot.llvm.managed");
        if (managed != null && !managed.equals("false"))
            throw new IllegalArgumentException("Native Haskell contexts require polyglot.llvm.managed to be absent or false");
        builder.allowCreateThread(true).useSystemExit(false);
        if (profile == ContextProfile.LAUNCHER) {
            builder.option("thc.ByteArrayStorage", System.getProperty("thc.byteArrayStorage", "native"));
        }
        if (profile == ContextProfile.NATIVE) return builder;
        builder.allowExperimentalOptions(true)
            .option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw");
        if (profile == ContextProfile.SYNCHRONOUS_TEST)
            return builder.option("engine.BackgroundCompilation", "false");
        return builder.allowEnvironmentAccess(EnvironmentAccess.INHERIT)
            .option("engine.CompilationFailureAction", System.getProperty("polyglot.engine.CompilationFailureAction", "Print"))
            .option("engine.CompilerThreads", System.getProperty("polyglot.engine.CompilerThreads", LAUNCHER_COMPILER_THREADS))
            .option("engine.TraceCompilation", System.getProperty("thc.traceCompilation", "false"))
            .option("engine.SingleTierCompilationThreshold", System.getProperty("polyglot.engine.SingleTierCompilationThreshold", "10000"))
            .option("compiler.CompilationTimeout", System.getProperty("polyglot.compiler.CompilationTimeout", "30"))
            .option("compiler.MaximumGraalGraphSize", System.getProperty("polyglot.compiler.MaximumGraalGraphSize", "100000"));
    }

    /** Values and native resources must not outlive or cross this owning context. */
    public static Context executionContext() { return executionContext(false); }
    public static Context executionContext(boolean fileIO) { return executionContext(fileIO, false); }
    static Context executionContext(boolean fileIO, boolean interfaceHelper) {
        if (fileIO && interfaceHelper && System.getProperty("os.name").startsWith("Windows"))
            throw new IllegalArgumentException("Interface-demand IO launching currently requires macOS or Linux");
        if (fileIO && NativeIO.supportedHost())
            return NativeIO.commandLineContext(interfaceHelper);
        return withContextProfile(Context.newBuilder("thc").allowNativeAccess(true)
            .allowCreateProcess(interfaceHelper).allowIO(fileIO ? IOAccess.ALL : IOAccess.NONE), ContextProfile.LAUNCHER).build();
    }

    /**
     * Load a Core entry owned by the supplied context. Supported signatures carry
     * numeric values, logical tuple/sum arrays, exact vector species and
     * context-owned references/functions. IO entries expose {@code runIO}.
     */
    public static Value loadEntry(Context context, List<String> modules, String entry) { return loadEntry(context, modules, entry, true); }
    public static Value loadEntry(Context context, List<String> modules, String entry, boolean instrument) { return loadEntry(context, modules, entry, instrument, defaultBackend()); }
    public static Value loadEntry(Context context, List<String> modules, String entry, boolean instrument, String backend) { return loadEntry(context, modules, entry, instrument, backend, false); }
    public static Value loadEntry(Context context, List<String> modules, String entry, boolean instrument, String backend, boolean ioMain) { return loadEntry(context, modules, entry, instrument, backend, ioMain, null); }
    public static Value loadEntry(Context context, List<String> modules, String entry, boolean instrument, String backend, boolean ioMain, String shutdownEntry) { return loadEntry(context, modules, entry, instrument, backend, ioMain, shutdownEntry, configuredAsyncExceptions()); }
    public static Value loadEntry(Context context, List<String> modules, String entry, boolean instrument, String backend, boolean ioMain, String shutdownEntry, Boolean asyncExceptions) { return loadEntry(context, modules, entry, instrument, backend, ioMain, shutdownEntry, asyncExceptions, false); }
    public static Value loadEntry(Context context, List<String> modules, String entry, boolean instrument, String backend, boolean ioMain, String shutdownEntry, Boolean asyncExceptions, boolean verifyArtifacts) {
        return context.eval("thc", CoreModules.request(modules, entry, instrument,
            !ioMain && Boolean.getBoolean("thc.diagnosticUnsupported"), backend,
            strictBoolean(System.getProperty("thc.sourceNotesEnabled", "true")), ioMain,
            shutdownEntry, asyncExceptions, verifyArtifacts));
    }
    /**
     * Load one application for repeated entry lookup. Its lazy cells, native
     * registrations and input readers belong to this context until it closes.
     * Independent loads never share program identity, even for identical paths.
     */
    public static Value loadProgram(Context context, List<String> modules) {
        return loadProgram(context, modules, true, defaultBackend(), configuredAsyncExceptions(), false);
    }
    public static Value loadProgram(Context context, List<String> modules, boolean instrument, String backend,
            Boolean asyncExceptions, boolean verifyArtifacts) {
        return context.eval("thc", CoreModules.programRequest(modules, instrument,
            Boolean.getBoolean("thc.diagnosticUnsupported"), backend,
            strictBoolean(System.getProperty("thc.sourceNotesEnabled", "true")), asyncExceptions, verifyArtifacts));
    }
    /** Select a lazy entry view from an explicitly loaded program. Lookup does not evaluate its CAFs. */
    public static Value loadEntry(Value program, String entry) { return program.invokeMember("entry", entry); }
    /**
     * Select an IO view, optionally with executable shutdown. Only a view with
     * shutdown is one-shot; repeated lookup of that exact configuration retains
     * its lifecycle state. Other entry configurations have independent views.
     */
    public static Value loadEntry(Value program, String entry, boolean ioMain, String shutdownEntry) {
        return shutdownEntry == null ? program.invokeMember("entry", entry, ioMain)
            : program.invokeMember("entry", entry, ioMain, shutdownEntry);
    }
    public static Boolean configuredAsyncExceptions() {
        String configured = System.getProperty("thc.asyncExceptions");
        return configured == null ? null : strictBoolean(configured);
    }
    private static boolean strictBoolean(String value) {
        if (value.equals("true")) return true;
        if (value.equals("false")) return false;
        throw new IllegalArgumentException("The string doesn't represent a boolean value: " + value);
    }

    /** Load one checked bundle; aliases retain shared program and CAF identity. */
    public static Value loadManagedExports(Context context, List<String> modules) { return loadManagedExports(context, modules, defaultBackend()); }
    public static Value loadManagedExports(Context context, List<String> modules, String backend) { return loadManagedExports(context, modules, backend, true); }
    public static Value loadManagedExports(Context context, List<String> modules, String backend, boolean instrument) { return loadManagedExports(context, modules, backend, instrument, false); }
    public static Value loadManagedExports(Context context, List<String> modules, String backend, boolean instrument, boolean verifyArtifacts) {
        return context.eval("thc", CoreModules.managedExportRequest(modules, backend, instrument, verifyArtifacts));
    }

    /** Ordinary package users invoke the Haskell thc run driver. */
    public static void main(String[] args) {
        processExit(() -> launch(args));
    }

    /** Owning contexts close before either launcher terminates the process. */
    static void processExit(Runnable launch) {
        try { launch.run(); }
        catch (PolyglotException exit) {
            if (!exit.isExit()) throw exit;
            // All owning contexts have closed before process termination.
            if (exit.getExitStatus() < 0) NativeSignalTransport.exitBySignal(-exit.getExitStatus());
            System.exit(exit.getExitStatus());
        }
    }

    public record ProgramArguments(String programName, String[] arguments) {
        public String getProgramName() { return programName; }
        public String[] getArguments() { return arguments; }
    }
    public record VerifiedArguments(String[] arguments, boolean verifyArtifacts, boolean interfaceHelper) {
        public String[] getArguments() { return arguments; }
        public boolean getVerifyArtifacts() { return verifyArtifacts; }
    }

    /** The fixed driver prefix is followed by opaque guest arguments. */
    public static ProgramArguments launcherArguments(String[] args, int prefix) {
        if (args.length == prefix) return new ProgramArguments("thc", new String[0]);
        require(args.length >= prefix + 2 && args[prefix].equals("--"), "Expected -- PROGRAM_NAME [ARG...] after executable arguments");
        return new ProgramArguments(args[prefix + 1], Arrays.copyOfRange(args, prefix + 2, args.length));
    }
    private static void initializeArguments(Context context, ProgramArguments arguments) {
        context.initialize("thc"); context.enter();
        try { Language.currentState(null).getArguments().initialize(arguments.programName(), arguments.arguments()); }
        finally { context.leave(); }
    }

    public static VerifiedArguments launcherArtifactVerification(String[] arguments) {
        var selected = new ArrayList<String>();
        boolean verify = false, guest = false, interfaceHelper = false;
        for (String argument : arguments) {
            if (argument.equals("--")) guest = true;
            require(guest || !argument.equals("--json-sidecar"), "JSON .idx sidecars are no longer supported");
            if (!guest && argument.equals("--verify-artifacts")) { require(!verify, "Duplicate --verify-artifacts"); verify = true; }
            else if (!guest && argument.equals("--allow-interface-helper")) { require(!interfaceHelper, "Duplicate --allow-interface-helper"); interfaceHelper = true; }
            else selected.add(argument);
        }
        return new VerifiedArguments(selected.toArray(String[]::new), verify, interfaceHelper);
    }

    /** Shared command-line authority, argv and one-shot entry/shutdown lifecycle. */
    static void runExecutable(ProgramArguments guest, boolean interfaceHelper, java.util.function.Function<Context,Value> load) {
        try (Context context = executionContext(true, interfaceHelper)) {
            initializeArguments(context, guest);
            var action = load.apply(context);
            check(action.invokeMember("runIO").asBoolean(), "Executable IO did not complete");
            if (Boolean.getBoolean("thc.diagnostics")) System.err.println(action.getMember("diagnostics").asString());
        }
    }

    // Report the actual selector code source, including every compiled class it
    // can consult. This cold audit path never initializes a guest context.
    private static Map<String, String> foreignOwnershipRuntime() throws java.io.IOException {
        try {
            var source = java.nio.file.Path.of(thc.runtime.CoreForeignOverride.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            boolean directory = java.nio.file.Files.isDirectory(source);
            if (directory) {
                try (var paths = java.nio.file.Files.walk(source)) {
                    for (var path : paths.filter(java.nio.file.Files::isRegularFile).sorted().toList()) {
                        var fileDigest = java.security.MessageDigest.getInstance("SHA-256");
                        try (var input = new java.security.DigestInputStream(java.nio.file.Files.newInputStream(path), fileDigest)) {
                            input.transferTo(java.io.OutputStream.nullOutputStream());
                        }
                        digest.update((HexFormat.of().formatHex(fileDigest.digest()) + "  " +
                            source.relativize(path).toString().replace('\\', '/') + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
            } else {
                try (var input = new java.security.DigestInputStream(java.nio.file.Files.newInputStream(source), digest)) {
                    input.transferTo(java.io.OutputStream.nullOutputStream());
                }
            }
            return new TreeMap<>(Map.of("path", source.toString(), "algorithm", directory ? "sha256-path-manifest-v1" : "sha256",
                "sha256", HexFormat.of().formatHex(digest.digest())));
        } catch (java.net.URISyntaxException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot identify the foreign ownership runtime", failure);
        }
    }

    public static void launch(String[] arguments) {
        if (arguments.length == 1 && arguments[0].equals("--classify-foreign-calls")) {
            // Cold audit routing only. Selected operations still validate the full
            // ABI, foreign head and operands before any guest execution.
            try {
                Object input = Json.parse(new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                require(input instanceof List<?>, "Foreign ownership requires a JSON array of call descriptors");
                var owners = new ArrayList<String>();
                for (Object call : (List<?>) input) {
                    owners.add(thc.runtime.CoreForeignOverride.owner(Collections.singletonMap("foreignCall", call)));
                }
                System.out.println(Json.stringify(new TreeMap<>(Map.of("schema", 1, "runtime", foreignOwnershipRuntime(), "owners", owners))));
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            return;
        }
        var withVerification = launcherArtifactVerification(arguments);
        String[] args = withVerification.arguments();
        boolean verifyArtifacts = withVerification.verifyArtifacts();
        boolean interfaceHelper = withVerification.interfaceHelper();
        if (args.length > 0 && args[0].equals("--run-executable")) {
            require(args.length >= 4, "Usage: thc --run-executable MODULE.cbd[,MODULE.cbd...] ENTRY SHUTDOWN_ENTRY [-- PROGRAM_NAME ARG...]");
            var guest = launcherArguments(args, 4);
            runExecutable(guest, interfaceHelper, context -> loadEntry(context, modules(args[1]), args[2], true,
                defaultBackend(), true, args[3], configuredAsyncExceptions(), verifyArtifacts));
            return;
        }
        if (args.length > 0 && args[0].equals("--run-io")) {
            require(args.length >= 3, "Usage: thc --run-io MODULE.cbd[,MODULE.cbd...] ENTRY [-- PROGRAM_NAME ARG...]");
            var guest = launcherArguments(args, 3);
            try (Context context = executionContext(true, interfaceHelper)) {
                initializeArguments(context, guest);
                var action = loadEntry(context, modules(args[1]), args[2], true, defaultBackend(), true, null, configuredAsyncExceptions(), verifyArtifacts);
                check(action.invokeMember("runIO").asBoolean(), "IO main did not complete");
                if (Boolean.getBoolean("thc.diagnostics")) System.err.println(action.getMember("diagnostics").asString());
            }
            return;
        }
        require(args.length >= 3, "Usage: thc MODULE.cbd[,MODULE.cbd...] ENTRY INTEGER [--compile]");
        long input = Long.parseLong(args[2]);
        try (Context context = executionContext(false, interfaceHelper)) {
            var function = loadEntry(context, modules(args[0]), args[1], true, defaultBackend(), false, null, configuredAsyncExceptions(), verifyArtifacts);
            Long before = null;
            if (Arrays.asList(args).subList(3, args.length).contains("--compile")) {
                // Training precedes installation; never settle or retry the first installed call.
                for (int i = 0; i < 40; i++) function.execute(input + (i & 3)).asLong();
                function.invokeMember("compile");
                var diagnostics = (Map<?, ?>) Json.parse(function.getMember("diagnostics").asString());
                installed(diagnostics); before = ((Number) diagnostics.get("compiledEntries")).longValue();
            }
            long result = function.execute(input).asLong();
            String diagnostics = function.getMember("diagnostics").asString();
            if (before != null) {
                var after = (Map<?, ?>) Json.parse(diagnostics);
                long entries = ((Number) after.get("compiledEntries")).longValue();
                check(entries > before, "First post-install call did not enter compiled guest code");
                installed(after);
                var observed = new LinkedHashMap<Object, Object>(after);
                var firstInstalledCall = new LinkedHashMap<String, Object>();
                firstInstalledCall.put("compiledEntriesBefore", before);
                firstInstalledCall.put("compiledEntriesAfter", entries);
                observed.put("firstInstalledCall", firstInstalledCall);
                diagnostics = Json.stringify(observed);
            }
            System.out.println(result); System.err.println(diagnostics);
        }
    }
    private static List<String> modules(String text) { return Arrays.asList(text.split(",", -1)); }
    static void installed(Map<?, ?> diagnostics) {
        var observation = (Map<?, ?>) diagnostics.get("explicitCompilation");
        check(((Number) observation.get("targetCount")).intValue() > 0 && Boolean.TRUE.equals(observation.get("sameTargets")) && Boolean.TRUE.equals(observation.get("validLastTier")),
            "Explicitly installed guest targets changed or became invalid");
    }
    private static boolean blank(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) return false;
        }
        return true;
    }
    private static void require(boolean accepted, String message) { if (!accepted) throw new IllegalArgumentException(message); }
    private static void check(boolean accepted, String message) { if (!accepted) throw new IllegalStateException(message); }
}
