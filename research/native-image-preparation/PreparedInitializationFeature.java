// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

import java.nio.file.Files;
import java.nio.file.Path;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;

/** Apply the finite inventory after Truffle has registered its resource providers. */
public final class PreparedInitializationFeature implements Feature {
    private StaticVectorLibrary vectorLibrary;
    static String[] classNames(String argument) {
        String prefix = "--initialize-at-build-time=";
        String line = argument.strip();
        if (!line.startsWith(prefix)) throw new IllegalArgumentException("Missing initialization inventory prefix");
        String names = line.substring(prefix.length());
        if (!names.matches("[a-zA-Z0-9_.$]+(,[a-zA-Z0-9_.$]+)*"))
            throw new IllegalArgumentException("Expected a finite nonempty class inventory");
        return names.split(",");
    }

    /** Discover the bound application's native inputs without building an image or running guest code. */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("NATIVE_LIBRARY_RECEIPT required");
        var receipt = Path.of(arguments[0]);
        Files.deleteIfExists(receipt);
        thc.NativeExecutable.captureForImage(new NativeLibraryCapture(receipt)::capture);
    }

    @Override public void beforeAnalysis(BeforeAnalysisAccess access) {
        if (vectorLibrary != null) vectorLibrary.select();
    }

    @Override public void beforeImageWrite(BeforeImageWriteAccess access) {
        if (vectorLibrary != null)
            ((com.oracle.svm.hosted.FeatureImpl.BeforeImageWriteAccessImpl) access)
                .registerLinkerInvocationTransformer(vectorLibrary::link);
    }

    @Override public void duringSetup(DuringSetupAccess access) {
        try {
            var inventory = Path.of(System.getProperty("thc.nativeImage.initialization"));
            for (String name : classNames(Files.readString(inventory))) {
                // Resolve without initialization; a package name cannot broaden the policy.
                var type = access.findClassByName(name);
                // Match the old CLI's ignored absent names, but never treat one as a package.
                if (type != null) RuntimeClassInitialization.initializeAtBuildTime(type);
            }
            if (Boolean.getBoolean("thc.nativeImage.executable")) {
                RuntimeClassInitialization.initializeAtBuildTime(thc.NativeExecutable.class);
                var receipt = inventory.getParent().resolve("native-libraries.json");
                thc.NativeExecutable.captureForImage(new NativeLibraryCapture(receipt)::capture);
                vectorLibrary = new StaticVectorLibrary(receipt);
                // Truffle's beforeAnalysis handoff will lower this capture inside initializeContext.
                System.setProperty("polyglot.image-build-time.PreinitializeContexts", "thc");
                System.setProperty("polyglot.image-build-time.PreinitializeContextsWithNative", "false");
            }
        } catch (Exception error) {
            throw new IllegalStateException("Cannot apply prepared initialization inventory", error);
        }
    }
}
