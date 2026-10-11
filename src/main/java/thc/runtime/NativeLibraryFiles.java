// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import thc.Json;

/** Extracted libraries retain their original backing inode while a loader may
 * cache their handle. Checkpoint-owned files outlive every restored JVM. */
public final class NativeLibraryFiles {
    private NativeLibraryFiles() {}
    private static final Set<Path> runtimeOwned = new LinkedHashSet<>();
    private static Checkpoint pendingCheckpoint;
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (NativeLibraryFiles.class) {
                for (Path path : List.copyOf(runtimeOwned)) {
                    try { discard(path); }
                    catch (IOException ignored) { /* Process exit cannot retry cleanup. */ }
                }
            }
        }, "THC native library backing cleanup"));
    }

    public static synchronized Path createTempFile(String prefix, String suffix) throws IOException {
        if (pendingCheckpoint != null)
            throw new IOException("Cannot extract a THC native library during checkpoint/restore callbacks");
        Path path = Files.createTempFile(prefix, suffix).toAbsolutePath();
        runtimeOwned.add(path);
        return path;
    }

    /** Runtime cleanup must never release image-owned backing. */
    static synchronized void discard(Path path) throws IOException {
        if (runtimeOwned.contains(path)) {
            Files.deleteIfExists(path);
            runtimeOwned.remove(path);
        }
    }

    /** Transfer current files without copying, renaming or changing their inode.
     * The sidecar owns cleanup after all users of this image have finished. */
    public static synchronized Checkpoint retainForCheckpoint(Path image) throws IOException {
        if (pendingCheckpoint != null) throw new IOException("Native library checkpoint ownership is already pending");
        image = image.toAbsolutePath().normalize();
        Path sidecar = image.resolveSibling(image.getFileName() + ".native-libraries.json");
        var paths = List.copyOf(runtimeOwned);
        var files = new ArrayList<Object>();
        for (Path path : paths) {
            var entry = new LinkedHashMap<String,Object>();
            entry.put("path", path.toString());
            entry.put("size", Files.size(path));
            try {
                var identity = Files.readAttributes(path, "unix:dev,ino");
                entry.put("dev", identity.get("dev")); entry.put("ino", identity.get("ino"));
            } catch (UnsupportedOperationException ignored) {
                entry.put("fileKey", String.valueOf(Files.readAttributes(path,
                    java.nio.file.attribute.BasicFileAttributes.class).fileKey()));
            }
            try {
                var hash = MessageDigest.getInstance("SHA-256");
                try (var input = new java.security.DigestInputStream(Files.newInputStream(path), hash)) {
                    input.transferTo(java.io.OutputStream.nullOutputStream());
                }
                entry.put("sha256", HexFormat.of().formatHex(hash.digest()));
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
            files.add(entry);
        }
        var manifest = new LinkedHashMap<String,Object>();
        manifest.put("schema", 1); manifest.put("image", image.toString()); manifest.put("files", files);
        Files.createDirectories(sidecar.getParent());
        Files.createFile(sidecar); // Never replace another image's ownership receipt.
        try { Files.writeString(sidecar, Json.stringify(manifest), StandardOpenOption.WRITE); }
        catch (IOException failure) {
            try { Files.delete(sidecar); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        runtimeOwned.removeAll(paths);
        pendingCheckpoint = new Checkpoint(image, sidecar, paths);
        return pendingCheckpoint;
    }

    public static final class Checkpoint {
        private final Path image;
        private final Path sidecar;
        private final List<Path> paths;
        private boolean rolledBack;
        private Checkpoint(Path image, Path sidecar, List<Path> paths) {
            this.image = image; this.sidecar = sidecar; this.paths = paths;
        }

        /** Resume ordinary extraction after the checkpoint operation has returned. */
        public void finish() {
            synchronized (NativeLibraryFiles.class) {
                if (pendingCheckpoint == this) pendingCheckpoint = null;
            }
        }

        /** Only a failed checkpoint can return image files to runtime ownership.
         * Failure after restore must retain the reusable image's ownership. */
        public void checkpointFailed(boolean restoreFailed) throws IOException {
            synchronized (NativeLibraryFiles.class) {
                if (rolledBack || restoreFailed || Files.exists(image.resolve("inventory.img"))) return;
                runtimeOwned.addAll(paths);
                Files.deleteIfExists(sidecar);
                rolledBack = true;
            }
        }
    }
}
