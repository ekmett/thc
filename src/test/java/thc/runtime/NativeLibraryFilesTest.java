// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thc.Json;
import static org.junit.jupiter.api.Assertions.*;

class NativeLibraryFilesTest {
    @TempDir Path directory;

    private Path backing() throws Exception {
        Path file = NativeLibraryFiles.createTempFile("thc-backing-test-", ".so");
        Files.write(file, new byte[]{1, 2, 3});
        return file;
    }

    @Test void runtimeCleanupPreservesReusableImageBackingAndItsIdentity() throws Exception {
        Path file = backing();
        var identity = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        var image = directory.resolve("image");
        var ownership = NativeLibraryFiles.retainForCheckpoint(image);
        assertThrows(java.io.IOException.class, () -> NativeLibraryFiles.createTempFile("thc-late-", ".so"));
        ownership.finish();
        var manifest = (Map<?,?>) Json.parse(Files.readString(directory.resolve("image.native-libraries.json")));
        var files = (java.util.List<?>) manifest.get("files");
        var entry = files.stream().map(value -> (Map<?,?>) value)
            .filter(value -> file.toString().equals(value.get("path"))).findFirst().orElseThrow();
        assertEquals(3L, entry.get("size"));
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", entry.get("sha256"));
        for (int restore = 0; restore < 2; restore++) {
            NativeLibraryFiles.discard(file); // The same cleanup used by JVM shutdown.
            assertTrue(Files.exists(file));
            assertEquals(identity, Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class).fileKey());
            Path fresh = backing();
            NativeLibraryFiles.discard(fresh);
            assertFalse(Files.exists(fresh), "postrestore extraction remains runtime-owned");
        }
        Files.delete(file); // Only the reusable image's owner releases its backing.
    }

    @Test void failedCheckpointBeforeImageReturnsBackingToRuntimeCleanup() throws Exception {
        Path file = backing();
        var ownership = NativeLibraryFiles.retainForCheckpoint(directory.resolve("failed"));
        ownership.checkpointFailed(false);
        ownership.checkpointFailed(false);
        ownership.finish();
        assertFalse(Files.exists(directory.resolve("failed.native-libraries.json")));
        NativeLibraryFiles.discard(file);
        assertFalse(Files.exists(file));
    }

    @Test void failureAfterRestoreOrPartialImageNeverRevokesImageOwnership() throws Exception {
        for (boolean restoreFailed : new boolean[]{false, true}) {
            Path file = backing();
            Path image = directory.resolve("retained-" + restoreFailed);
            var ownership = NativeLibraryFiles.retainForCheckpoint(image);
            if (!restoreFailed) {
                Files.createDirectory(image);
                Files.createFile(image.resolve("inventory.img"));
            }
            ownership.checkpointFailed(restoreFailed);
            ownership.finish();
            NativeLibraryFiles.discard(file);
            assertTrue(Files.exists(file));
            assertTrue(Files.exists(directory.resolve(image.getFileName() + ".native-libraries.json")));
            Files.delete(file);
        }
    }

    @Test void failedManifestCreationDoesNotTransferOrReplaceOwnership() throws Exception {
        Path file = backing();
        Path sidecar = directory.resolve("existing.native-libraries.json");
        Files.writeString(sidecar, "original owner");
        assertThrows(java.io.IOException.class, () -> NativeLibraryFiles.retainForCheckpoint(directory.resolve("existing")));
        assertEquals("original owner", Files.readString(sidecar));
        NativeLibraryFiles.discard(file);
        assertFalse(Files.exists(file));
    }
}
