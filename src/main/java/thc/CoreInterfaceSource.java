// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import com.oracle.truffle.api.TruffleSafepoint;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** A checked selected-GHC source. Each context owns converted files and readers;
 * no helper process, mutable GHC session or conversion cache survives demand. */
final class CoreInterfaceSource {
    private final Path helper, libdir;
    private final String registeredUnit, way;
    private final List<String> databases;
    private final List<CoreUnitDirectory.Artifact> inputs;
    private CoreInterfaceSource(Path helper, Path libdir, String registeredUnit, String way,
            List<String> databases, List<CoreUnitDirectory.Artifact> inputs) {
        this.helper = helper; this.libdir = libdir; this.registeredUnit = registeredUnit;
        this.way = way; this.databases = databases; this.inputs = inputs;
    }
    static CoreInterfaceSource read(Object raw, List<CoreUnitDirectory.Artifact> inputs) {
        require(raw instanceof Map<?,?>, "Missing interface source descriptor");
        var source = (Map<?,?>) raw;
        require(Objects.equals(source.get("format"), "thc-ghc-interfaces-v1"), "Invalid interface source format");
        String way = text(source.get("way"));
        require(Set.of("vanilla", "dynamic").contains(way), "Unsupported interface way");
        String unit = text(source.get("registeredUnit")); require(!unit.isBlank(), "Missing registered interface unit");
        var databases = new ArrayList<String>();
        require(source.get("packageDatabases") instanceof List<?>, "Missing interface package databases");
        for (Object item : (List<?>) source.get("packageDatabases")) databases.add(absolute(item).toString());
        require(source.get("compiler") instanceof Map<?,?> compiler && Objects.equals(compiler.get("id"), "ghc-9.14.1"),
                "Interface source requires the selected GHC 9.14.1 compiler");
        var seen = new HashSet<Path>();
        for (var input : inputs) seen.add(input.path());
        Path helper = absolute(source.get("helper")), libdir = absolute(source.get("libdir"));
        require(seen.contains(helper) && seen.contains(libdir.resolve("settings")), "Interface toolchain is outside checked snapshot");
        require(seen.contains(absolute(source.get("implicitGlobalDatabase")).resolve("package.cache")), "Implicit interface package state is outside checked snapshot");
        for (String db : databases) require(seen.contains(Path.of(db).resolve("package.cache")), "Interface package state is outside checked snapshot");
        return new CoreInterfaceSource(helper, libdir, unit, way, List.copyOf(databases), inputs);
    }
    static List<CoreUnitDirectory.Artifact> snapshot(Object raw) {
        if (raw == null) return List.of();
        require(raw instanceof List<?>, "Missing interface source snapshot");
        var inputs = new ArrayList<CoreUnitDirectory.Artifact>(); var paths = new HashSet<Path>();
        for (Object item : (List<?>) raw) inputs.add(input(item, paths));
        return List.copyOf(inputs);
    }
    CoreUnitDirectory.Artifact artifact(Object raw, Set<Path> paths) {
        var artifact = input(raw, paths);
        require(inputs.contains(artifact), "Interface module is outside checked snapshot"); return artifact;
    }
    private static CoreUnitDirectory.Artifact input(Object raw, Set<Path> paths) {
        require(raw instanceof Map<?,?>, "Missing interface input"); var item = (Map<?,?>) raw;
        Path path = absolute(item.get("path")); String hash = text(item.get("sha256"));
        require(item.keySet().equals(Set.of("path", "sha256")) && hash.matches("[0-9a-f]{64}") && paths.add(path),
                "Invalid or duplicate interface snapshot input");
        return new CoreUnitDirectory.Artifact(path, hash);
    }
    private static Path absolute(Object raw) { Path path = Path.of(text(raw)); require(path.isAbsolute(), "Interface paths must be absolute"); return path.normalize(); }
    private static String text(Object raw) { require(raw instanceof String, "Invalid interface source field"); return (String) raw; }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
    private void verifyInputs() throws Exception {
        byte[] buffer = new byte[64 * 1024];
        for (var input : inputs) {
            String digest;
            try { digest = hash(input.path(), buffer); }
            catch (java.io.IOException failure) { throw new IllegalArgumentException("Retained interface input unavailable after publication: " + input.path(), failure); }
            require(digest.equals(input.sha256()), "Retained interface input changed after publication: " + input.path());
        }
    }
    private static String hash(Path path) throws Exception { return hash(path, new byte[64 * 1024]); }
    private static String hash(Path path, byte[] buffer) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = Files.newInputStream(path)) {
            int count;
            while ((count = stream.read(buffer)) != -1) { digest.update(buffer, 0, count); TruffleSafepoint.poll(null); }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    static com.oracle.truffle.api.TruffleLanguage.Env processEnvironment() {
        com.oracle.truffle.api.TruffleLanguage.Env env;
        try { env = Language.currentState(null).getEnv(); }
        catch (IllegalStateException | AssertionError failure) { throw new IllegalArgumentException("Retained interface demand requires an entered THC context with explicit process permission; use required or pinned for offline CBD readers", failure); }
        require(env.isCreateProcessAllowed(), "Retained interface demand requires explicit context process permission (allowCreateProcess); the launcher uses --allow-interface-helper");
        return env;
    }
    CoreUnitDirectory.Artifact convert(CoreUnitDirectory.ModuleRecord module, Path directory,
            com.oracle.truffle.api.TruffleLanguage.Env env) throws Exception {
        verifyInputs();
        Path output = Files.createTempFile(directory, "module-", ".cbd"), diagnostic = Files.createTempFile(directory, "helper-", ".stderr");
        var arguments = new ArrayList<>(List.of(helper.toString(), "--libdir", libdir.toString(), "--unit", registeredUnit,
                "--module", module.name(), "--interface", module.artifact().path().toString(), "--way", way, "--source-spans"));
        for (String db : databases) { arguments.add("--package-db"); arguments.add(db); }
        var environment = new HashMap<>(env.getEnvironment());
        environment.remove("GHC_PACKAGE_PATH"); environment.remove("GHC_ENVIRONMENT");
        try (var out = Files.newOutputStream(output); var err = Files.newOutputStream(diagnostic)) {
        var builder = env.newProcessBuilder(arguments.toArray(String[]::new)).clearEnvironment(true).environment(environment);
        // Every input is absolute; use our private output directory instead of
        // consulting the context's ambient working directory or IO permissions.
        builder.directory(env.getPublicTruffleFile(directory.toAbsolutePath().toString()));
        builder.redirectOutput(builder.createRedirectToStream(out)).redirectError(builder.createRedirectToStream(err));
        Process child = builder.start();
        try {
            child.getOutputStream().close();
            boolean finished = TruffleSafepoint.setBlockedThreadInterruptibleFunction(null,
                (TruffleSafepoint.InterruptibleFunction<Process,Boolean>) process -> process.waitFor(180, TimeUnit.SECONDS), child);
            require(finished, "Retained interface helper timed out for " + module.getPrefix());
            int status = child.exitValue();
            if (status != 0) {
                String response = clipped(output), stderr = clipped(diagnostic);
                if (status == 3) throw new IllegalArgumentException("complete-interface-core unavailable for " + module.unit() + ":" + module.name() +
                    " (" + module.artifact().path() + "). Build the libraries with -fwrite-if-simplified-core or select --installed-core pinned. " + response);
                throw new IllegalArgumentException("Retained interface helper failed for " + module.getPrefix() + " (exit " + status + "): " + response + stderr);
            }
            verifyInputs();
            return new CoreUnitDirectory.Artifact(output, hash(output));
        } finally {
            if (child.isAlive()) child.destroyForcibly();
            boolean interrupted = false;
            for (;;) {
                try { child.waitFor(); break; } catch (InterruptedException failure) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        } finally { Files.deleteIfExists(diagnostic); }
    }
    private static String clipped(Path path) throws Exception {
        try (var stream = Files.newInputStream(path)) { return new String(stream.readNBytes(2000), StandardCharsets.UTF_8); }
    }
    static void removeTemporary(Path directory) {
        CoreFileMappings.shared.evictIdleBelow(directory);
        try {
            List<Path> contents;
            try (var files = Files.list(directory)) { contents = files.toList(); }
            for (Path file : contents) Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        } catch (Exception failure) { throw new IllegalStateException("Cannot release retained interface files: " + directory, failure); }
    }
}
