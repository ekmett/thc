// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.runtime.OptimizedCallTarget;
import com.oracle.truffle.runtime.OptimizedTruffleRuntime;
import com.oracle.truffle.runtime.OptimizedTruffleRuntimeListener;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;

/** Experimental selected-Core auxiliary cache launcher; not the ordinary THC launcher. */
public final class NativeCache {
    private static final String SOURCE_PREFIX = "thc-native-cache-";
    private NativeCache() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("store CACHE MODULE.json[,MODULE.json...]|@PACKAGES.json ENTRY [--io-main] [--shutdown-entry=ID] [--verify-artifacts]\nrun CACHE [INTEGER|f:FLOAT|d:DOUBLE...]");
            return;
        }
        if (args.length < 2 || !(args[0].equals("store") && args.length >= 4 || args[0].equals("run")))
            throw new IllegalArgumentException("Expected store CACHE MODULES ENTRY or run CACHE [INTEGER|f:FLOAT|d:DOUBLE...]");
        boolean ioMain = false, verifyArtifacts = false;
        String shutdown = null;
        if (args[0].equals("store")) {
            for (int i = 4; i < args.length; i++) {
                if (args[i].equals("--io-main") && !ioMain) ioMain = true;
                else if (args[i].equals("--verify-artifacts") && !verifyArtifacts) verifyArtifacts = true;
                else if (args[i].startsWith("--shutdown-entry=") && shutdown == null)
                    shutdown = args[i].substring("--shutdown-entry=".length());
                else throw new IllegalArgumentException("Unknown or repeated store option: " + args[i]);
            }
            if (shutdown != null && (!ioMain || shutdown.isBlank() || shutdown.equals(args[3])))
                throw new IllegalArgumentException("Shutdown requires --io-main and a distinct nonempty entry");
        }
        if (!ImageInfo.inImageRuntimeCode()) throw new IllegalStateException("Use the experimental native-cache image, not the JVM launcher");
        Path cache = Path.of(args[1]).toAbsolutePath();
        if (args[0].equals("store")) store(cache, request(Arrays.asList(args[2].split(",", -1)), args[3], verifyArtifacts, ioMain, shutdown));
        else {
            Object[] arguments = new Object[args.length - 2];
            for (int i = 2; i < args.length; i++) arguments[i - 2] = argument(args[i]);
            run(cache, arguments);
        }
    }

    static Object argument(String value) {
        if (value.startsWith("f:")) return Float.valueOf(value.substring(2));
        if (value.startsWith("d:")) return Double.valueOf(value.substring(2));
        return Long.valueOf(value);
    }

    static String request(List<String> modules, String entry) {
        return request(modules, entry, false);
    }

    @SuppressWarnings("unchecked")
    static String request(List<String> modules, String entry, boolean verifyArtifacts) {
        return request(modules, entry, verifyArtifacts, false, null);
    }

    @SuppressWarnings("unchecked")
    static String request(List<String> modules, String entry, boolean verifyArtifacts, boolean ioMain, String shutdownEntry) {
        if (modules.isEmpty() || modules.stream().anyMatch(String::isBlank) || entry.isBlank())
            throw new IllegalArgumentException("Core modules and selected entry are required");
        var request = new LinkedHashMap<>((Map<String, Object>) Json.parse(CoreModules.request(
            modules, entry, true, false, "ast", true, ioMain, shutdownEntry, false, verifyArtifacts)));
        request.put("prepareCode", true);
        return CoreModules.detachedRequest(request, entry);
    }

    static String sourceName(String request) {
        try {
            return SOURCE_PREFIX + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(request.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static Source selectedSource(Engine engine) {
        List<Source> sources = engine.getCachedSources().stream().filter(source -> source.getLanguage().equals("thc") &&
            source.hasCharacters() && source.getName().equals(sourceName(source.getCharacters().toString()))).toList();
        if (sources.size() != 1) throw new IllegalStateException("Expected exactly one persisted THC program, found " + sources.size());
        return sources.getFirst();
    }

    private static Engine.Builder engine() {
        return Engine.newBuilder().allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw");
    }

    private static void store(Path cache, String request) throws Exception {
        if (Files.exists(cache)) throw new IllegalArgumentException("Cache already exists: " + cache);
        Source source = Source.newBuilder("thc", request, sourceName(request)).cached(true).build();
        try (Engine engine = engine().option("engine.CacheStoreEnabled", "true")
                .option("engine.CacheCompile", "aot").option("engine.CachePreinitializeContext", "false").build()) {
            try (Context preparation = Context.newBuilder("thc").engine(engine).build()) {
                preparation.parse(source); // No load-factory or guest execution/training.
            }
            if (!engine.storeCache(cache)) throw new IllegalStateException("Provider did not store compiled THC code");
        }
        System.err.println("Stored " + source.getName());
    }

    private static void run(Path cache, Object[] arguments) throws Exception {
        if (!Files.isRegularFile(cache)) throw new IllegalArgumentException("Required cache is absent: " + cache);
        System.setProperty("thc.requireCachedCode", "true");
        System.setProperty("thc.requireCompiledCode", "true");
        var submissions = new AtomicInteger();
        var listener = new OptimizedTruffleRuntimeListener() {
            @Override public void onCompilationQueued(OptimizedCallTarget target, int tier) { submissions.incrementAndGet(); }
        };
        var runtime = OptimizedTruffleRuntime.getRuntime();
        runtime.addListener(listener);
        try (Engine engine = engine().option("engine.CacheLoad", cache.toString()).option("engine.Compilation", "false").build()) {
            Source source = selectedSource(engine);
            try (Context context = Context.newBuilder("thc").engine(engine).allowNativeAccess(true)
                    .allowIO(IOAccess.ALL).allowCreateThread(true).build()) {
                Value entry = context.parse(source).execute(); // Actual cached factory validates its own targets.
                boolean io = entry.canInvokeMember("runIO");
                if (io && arguments.length != 0) throw new IllegalArgumentException("Cached IO main takes no arguments");
                Value result = io ? entry.invokeMember("runIO") : entry.execute(arguments);
                if (io ? !result.isBoolean() || !result.asBoolean() : !result.isNumber())
                    throw new IllegalStateException("Cached entry must complete IO main or return a numeric scalar");
                Map<?, ?> diagnostics = (Map<?, ?>) Json.parse(entry.getMember("diagnostics").asString());
                if (((Number) diagnostics.get("loweredRootCount")).longValue() != 0 ||
                        ((Number) diagnostics.get("compiledEntries")).longValue() == 0 || submissions.get() != 0)
                    throw new IllegalStateException("Cached guest execution contract failed");
                if (!io) System.out.println(result);
                System.err.println("Executed " + source.getName() + " with cached code and fresh program state");
            }
        } finally { runtime.removeListener(listener); }
    }
}
