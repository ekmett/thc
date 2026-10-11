// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
package com.oracle.svm.core.jam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import org.graalvm.nativeimage.Platform;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.guest.staging.util.UserError;
import com.oracle.svm.hosted.FeatureImpl.BeforeAnalysisAccessImpl;
import com.oracle.svm.shared.feature.AutomaticallyRegisteredFeature;

/** Link the collector into each image; the notices directory is not a runtime dependency. */
@AutomaticallyRegisteredFeature
final class JamRuntimeBundleFeature implements InternalFeature {
    @Override public boolean isInConfiguration(IsInConfigurationAccess access) { return SubstrateOptions.useJamGC(); }

    @Override public void beforeAnalysis(BeforeAnalysisAccess access) {
        Path directory = JamNative.Directives.libraryDirectory();
        Path manifest = directory.resolve("native-image-libraries.txt");
        List<String> libraries;
        try {
            libraries = Files.readAllLines(manifest);
        } catch (IOException error) {
            throw UserError.abort("Unable to read static Jam archive manifest %s: %s", manifest, error.getMessage());
        }
        boolean windows = Platform.includedIn(Platform.WINDOWS.class);
        UserError.guarantee(!libraries.isEmpty() && libraries.getFirst().equals("jam-vm-static") &&
                        libraries.stream().distinct().count() == libraries.size(), "Invalid static Jam archive manifest: %s", manifest);
        var nativeLibraries = ((BeforeAnalysisAccessImpl) access).getNativeLibraries();
        for (int index = 0; index < libraries.size(); ++index) {
            String library = libraries.get(index);
            UserError.guarantee(library.matches("[A-Za-z0-9_+][A-Za-z0-9_+.-]*"), "Invalid static Jam library name: %s", library);
            Path archive = directory.resolve("static").resolve(windows ? library + ".lib" : "lib" + library + ".a");
            UserError.guarantee(Files.isRegularFile(archive), "Missing static Jam archive: %s", archive);
            String[] dependencies = index + 1 < libraries.size() ? new String[]{libraries.get(index + 1)} : new String[0];
            nativeLibraries.addStaticNonJniLibrary(library, dependencies);
        }
        if (windows) nativeLibraries.addDynamicNonJniLibrary("onecore");
    }

    @Override public void afterImageWrite(AfterImageWriteAccess access) {
        Path output = access.getImagePath().resolveSibling(access.getImagePath().getFileName() + ".jam");
        Path legal = Path.of(System.getProperty("java.home"), "legal", "jam-vm");
        try {
            Files.createDirectories(output.resolve("legal"));
            Files.writeString(output.resolve("linkage.txt"), "static\n");
            if (Platform.includedIn(Platform.WINDOWS.class)) {
                Path directory = JamNative.Directives.libraryDirectory();
                for (String library : Files.readAllLines(directory.resolve("runtime-libraries.txt"))) {
                    if (library.equalsIgnoreCase("jam-vm.dll")) continue;
                    UserError.guarantee(library.matches("(?i)(msvcp|msvcr|vcruntime|concrt)[0-9]+(_[a-z0-9]+)*\\.dll"),
                                    "Invalid Microsoft runtime library name: %s", library);
                    Path source = directory.resolve(library);
                    Path destination = access.getImagePath().toAbsolutePath().getParent().resolve(library);
                    UserError.guarantee(Files.isRegularFile(source), "Missing Microsoft runtime: %s", source);
                    if (Files.exists(destination)) {
                        UserError.guarantee(Files.isRegularFile(destination) && Files.mismatch(source, destination) == -1,
                                        "A different runtime already exists at %s; use a separate output directory", destination);
                    } else {
                        Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
            }
            try (var files = Files.list(legal)) {
                for (Path file : files.toList()) {
                    if (Files.isRegularFile(file)) Files.copy(file, output.resolve("legal").resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException error) {
            throw UserError.abort("Unable to write Jam notices beside %s: %s", access.getImagePath(), error.getMessage());
        }
    }
}
