// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Native definitions and their declared component dependencies, not a Haskell call ABI. */
public record PackageNativeComponent(String unit, String target, String componentSha256,
        String bitcodeSha256, String format, byte[] bytes, byte[] nativeLibrary,
        Set<String> exports, List<PackageNativeComponent> dependencies, List<BundledLibrary> bundledLibraries) {
    /** Exact image-host-selected DSO bytes, in dependency-first order. */
    public record BundledLibrary(String name, String sha256, byte[] bytes) {
        public BundledLibrary { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
        public boolean same(BundledLibrary other) {
            return name.equals(other.name) && sha256.equals(other.sha256) && Arrays.equals(bytes, other.bytes);
        }
    }
    public PackageNativeComponent(String unit, String target, String componentSha256,
            String bitcodeSha256, String format, byte[] bytes, byte[] nativeLibrary,
            Set<String> exports, List<PackageNativeComponent> dependencies) {
        this(unit, target, componentSha256, bitcodeSha256, format, bytes, nativeLibrary, exports, dependencies, List.of());
    }
    public PackageNativeComponent {
        exports = Set.copyOf(exports);
        dependencies = List.copyOf(dependencies);
        bundledLibraries = List.copyOf(bundledLibraries);
    }
    public boolean same(PackageNativeComponent other) {
        if (this == other) return true;
        if (!unit.equals(other.unit) || !target.equals(other.target) || !componentSha256.equals(other.componentSha256)
                || !bitcodeSha256.equals(other.bitcodeSha256) || !format.equals(other.format)
                || !Arrays.equals(bytes, other.bytes) || !Arrays.equals(nativeLibrary, other.nativeLibrary)
                || !exports.equals(other.exports) || dependencies.size() != other.dependencies.size() || bundledLibraries.size() != other.bundledLibraries.size()) return false;
        for (int i = 0; i < bundledLibraries.size(); i++)
            if (!bundledLibraries.get(i).same(other.bundledLibraries.get(i))) return false;
        for (int i = 0; i < dependencies.size(); i++)
            if (!dependencies.get(i).same(other.dependencies.get(i))) return false;
        return true;
    }
}
