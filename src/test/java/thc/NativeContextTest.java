// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class NativeContextTest {
    @TempDir Path directory;
    @Test void launcherRejectsPartialOrRelativeNativeBuilderConfigurationOnlyInItsOwningProfile() throws Exception {
        var builder = directory.resolve("driver").toAbsolutePath().toString();
        var cache = directory.resolve("cache").toAbsolutePath().toString();
        var invalid = List.of(Map.of("THC_PACKAGE_NATIVE_BUILDER", builder),
            Map.of("THC_PACKAGE_NATIVE_CACHE", cache),
            Map.of("THC_PACKAGE_NATIVE_BUILDER", "", "THC_PACKAGE_NATIVE_CACHE", cache),
            Map.of("THC_PACKAGE_NATIVE_BUILDER", builder, "THC_PACKAGE_NATIVE_CACHE", " "),
            Map.of("THC_PACKAGE_NATIVE_BUILDER", "relative-driver", "THC_PACKAGE_NATIVE_CACHE", cache),
            Map.of("THC_PACKAGE_NATIVE_BUILDER", builder, "THC_PACKAGE_NATIVE_CACHE", "relative-cache"));
        for (var environment : invalid) {
            profileProcess(ContextProfile.LAUNCHER, environment, true);
            for (var profile : List.of(ContextProfile.NATIVE, ContextProfile.SYNCHRONOUS_TEST))
                profileProcess(profile, environment, false);
        }
        profileProcess(ContextProfile.LAUNCHER, Map.of(), false);
        profileProcess(ContextProfile.LAUNCHER,
            Map.of("THC_PACKAGE_NATIVE_BUILDER", builder, "THC_PACKAGE_NATIVE_CACHE", cache), false);
    }
    private void profileProcess(ContextProfile profile, Map<String, String> environment, boolean rejected) throws Exception {
        var distribution = Path.of(System.getProperty("thc.projectRoot"), "build/install/thc/lib");
        var paths = new ArrayList<String>();
        paths.add(Path.of(NativeContextTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        try (var files = Files.list(distribution)) {
            files.filter(path -> path.toString().endsWith(".jar")).sorted().forEach(path -> paths.add(path.toString()));
        }
        var output = directory.resolve("profile-output.txt");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", String.join(File.pathSeparator, paths), ProfileProbe.class.getName(), profile.name());
        process.environment().remove("THC_PACKAGE_NATIVE_BUILDER");
        process.environment().remove("THC_PACKAGE_NATIVE_CACHE");
        process.environment().putAll(environment);
        var child = process.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), "profile subprocess did not terminate");
            var diagnostic = Files.readString(output);
            if (rejected) {
                assertNotEquals(0, child.exitValue(), diagnostic);
                assertTrue(diagnostic.contains("THC_PACKAGE_NATIVE_BUILDER and THC_PACKAGE_NATIVE_CACHE must be absolute nonblank paths supplied together"), diagnostic);
            } else assertEquals(0, child.exitValue(), diagnostic);
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }
    public static final class ProfileProbe {
        public static void main(String[] arguments) {
            Main.withContextProfile(Context.newBuilder("thc"), ContextProfile.valueOf(arguments[0]));
        }
    }
    @Test void nativeProfilesRejectInheritedManagedExecutionBeforeContextConstruction() {
        var previous = System.getProperty("polyglot.llvm.managed");
        try {
            for (var setting : new String[] {"true", "TRUE", "invalid", ""}) {
                System.setProperty("polyglot.llvm.managed", setting);
                for (var profile : ContextProfile.values())
                    assertThrows(IllegalArgumentException.class,
                        () -> Main.withContextProfile(Context.newBuilder("thc"), profile));
            }
            System.setProperty("polyglot.llvm.managed", "false");
            for (var profile : ContextProfile.values())
                assertDoesNotThrow(() -> Main.withContextProfile(Context.newBuilder("thc"), profile));
        } finally {
            if (previous == null) System.clearProperty("polyglot.llvm.managed");
            else System.setProperty("polyglot.llvm.managed", previous);
        }
    }
}
