// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Native definitions and their declared component dependencies, not a Haskell call ABI. */
public record PackageNativeComponent(String unit, String target, String componentSha256,
        String bitcodeSha256, String format, byte[] bytes, byte[] nativeLibrary,
        Set<String> exports, List<PackageNativeComponent> dependencies) {
    public PackageNativeComponent {
        exports = Set.copyOf(exports);
        dependencies = List.copyOf(dependencies);
    }
    public boolean same(PackageNativeComponent other) {
        if (this == other) return true;
        if (!unit.equals(other.unit) || !target.equals(other.target) || !componentSha256.equals(other.componentSha256)
                || !bitcodeSha256.equals(other.bitcodeSha256) || !format.equals(other.format)
                || !Arrays.equals(bytes, other.bytes) || !Arrays.equals(nativeLibrary, other.nativeLibrary)
                || !exports.equals(other.exports) || dependencies.size() != other.dependencies.size()) return false;
        for (int i = 0; i < dependencies.size(); i++)
            if (!dependencies.get(i).same(other.dependencies.get(i))) return false;
        return true;
    }
}
