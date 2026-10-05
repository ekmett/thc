// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc;

import java.util.*;

public final class PackageScalarLink {
    /** Verified captured adapters are bound lazily to their context's provider. */
    public record CallSeed(String entry, String bitcodeSha256, String bitcodeHex,
            String providerUnit, String providerComponentSha256, String providerSymbol) {}
    private final String unit, target, componentSha256, bitcodeSha256, format;
    private final byte[] bytes, nativeLibrary;
    private final List<PackageScalarSignature> abi;
    private final Set<String> finalizers, dataSymbols;
    private final PackageNativeComponent component;
    private final Map<String, CallSeed> callSeeds;
    public PackageScalarLink(String unit, String target, String componentSha256, String bitcodeSha256, byte[] bytes, List<PackageScalarSignature> abi) {
        this(unit, target, componentSha256, bitcodeSha256, bytes, abi, "llvm-bitcode");
    }
    public PackageScalarLink(String unit, String target, String componentSha256, String bitcodeSha256, byte[] bytes, List<PackageScalarSignature> abi, String format) {
        this(unit, target, componentSha256, bitcodeSha256, bytes, abi, format, Set.of());
    }
    public PackageScalarLink(String unit, String target, String componentSha256, String bitcodeSha256, byte[] bytes, List<PackageScalarSignature> abi, String format, Set<String> finalizers) {
        this(unit, target, componentSha256, bitcodeSha256, bytes, abi, format, finalizers, new byte[0]);
    }
    public PackageScalarLink(String unit, String target, String componentSha256, String bitcodeSha256, byte[] bytes, List<PackageScalarSignature> abi, String format, Set<String> finalizers, byte[] nativeLibrary) {
        this(unit, target, componentSha256, bitcodeSha256, bytes, abi, format, finalizers, nativeLibrary, Set.of());
    }
    public PackageScalarLink(String unit, String target, String componentSha256, String bitcodeSha256, byte[] bytes, List<PackageScalarSignature> abi, String format, Set<String> finalizers, byte[] nativeLibrary, Set<String> dataSymbols) {
        this(unit, target, componentSha256, bitcodeSha256, bytes, abi, format, finalizers, nativeLibrary, dataSymbols, Set.of(), List.of());
    }
    public PackageScalarLink(String unit, String target, String componentSha256, String bitcodeSha256, byte[] bytes, List<PackageScalarSignature> abi, String format, Set<String> finalizers, byte[] nativeLibrary, Set<String> dataSymbols, Set<String> exports, List<PackageNativeComponent> dependencies) {
        this(unit, target, componentSha256, bitcodeSha256, bytes, abi, format, finalizers, nativeLibrary, dataSymbols, exports, dependencies, Map.of());
    }
    public PackageScalarLink(String unit, String target, String componentSha256, String bitcodeSha256, byte[] bytes, List<PackageScalarSignature> abi, String format, Set<String> finalizers, byte[] nativeLibrary, Set<String> dataSymbols, Set<String> exports, List<PackageNativeComponent> dependencies, Map<String, CallSeed> callSeeds) {
        this(unit, target, componentSha256, bitcodeSha256, bytes, abi, format, finalizers, nativeLibrary, dataSymbols, exports, dependencies, callSeeds, List.of());
    }
    public PackageScalarLink(String unit, String target, String componentSha256, String bitcodeSha256, byte[] bytes, List<PackageScalarSignature> abi, String format, Set<String> finalizers, byte[] nativeLibrary, Set<String> dataSymbols, Set<String> exports, List<PackageNativeComponent> dependencies, Map<String, CallSeed> callSeeds, List<PackageNativeComponent.BundledLibrary> bundledLibraries) {
        this.unit = unit; this.target = target; this.componentSha256 = componentSha256; this.bitcodeSha256 = bitcodeSha256;
        this.bytes = bytes; this.abi = abi; this.format = format;
        this.finalizers = Set.copyOf(finalizers);
        this.nativeLibrary = nativeLibrary;
        this.dataSymbols = Set.copyOf(dataSymbols);
        this.callSeeds = Map.copyOf(callSeeds);
        component = bytes.length == 0 && !callSeeds.isEmpty() ? null :
            new PackageNativeComponent(unit, target, componentSha256, bitcodeSha256, format, bytes, nativeLibrary, exports, dependencies, bundledLibraries);
    }
    public Map<String, CallSeed> getCallSeeds() { return callSeeds; }
    public PackageNativeComponent getComponent() { return component; }
    public String getUnit() { return unit; } public String getTarget() { return target; }
    public String getComponentSha256() { return componentSha256; } public String getBitcodeSha256() { return bitcodeSha256; }
    public String getFormat() { return format; } public byte[] getBytes() { return bytes; }
    public List<PackageScalarSignature> getAbi() { return abi; }
    public Set<String> getFinalizers() { return finalizers; }
    public byte[] getNativeLibrary() { return nativeLibrary; }
    /** Address-thunk entries for either data or function labels, never callable target ABIs.
     * The wire spelling remains dataSymbols for compatibility with the initial codec. */
    public Set<String> getDataSymbols() { return dataSymbols; }
    public boolean same(PackageScalarLink other) {
        return this == other || unit.equals(other.unit) && target.equals(other.target) && componentSha256.equals(other.componentSha256) &&
                bitcodeSha256.equals(other.bitcodeSha256) && abi.equals(other.abi) && format.equals(other.format) && finalizers.equals(other.finalizers) && Arrays.equals(bytes, other.bytes) &&
                Arrays.equals(nativeLibrary, other.nativeLibrary) && dataSymbols.equals(other.dataSymbols) && callSeeds.equals(other.callSeeds) &&
                (component == null ? other.component == null : component.same(other.component));
    }
}
