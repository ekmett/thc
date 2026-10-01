// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import thc.Json;

/** The selected native LP64 stat image ABI, not a host pointer or
 * a simulated fstat. Images are supplied by the caller; descriptor metadata is
 * deliberately not synthesized from paths or stale open-time permissions. */
public final class PosixStat {
    private final long size;
    private final Map<String, Field> fields;
    private final Map<String, Long> types;
    private record Field(long offset, int width, boolean signed) {}
    private PosixStat(long size, Map<String, Field> fields, Map<String, Long> types) {
        this.size = size; this.fields = fields; this.types = types;
    }
    public long getSize() { return size; }
    public long field(String name, ManagedAddress address) {
        var nativeOwner = address.nativeAllocation();
        try (var ignored = nativeOwner == null ? null : nativeOwner.borrow()) {
            var field = required(fields, name);
            address.requireRange(field.offset, field.width, false);
            // readWord8 preserves managed pointer-cell protection, unlike rawBacking.
            long value = 0;
            boolean little = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
            for (int index = 0; index < field.width; index++) {
                int shift = (little ? index : field.width - 1 - index) * 8;
                value |= address.readWord8(field.offset + index) << shift;
            }
            return field.signed && field.width < 8 ? value << (64 - field.width * 8) >> (64 - field.width * 8) : value;
        }
    }
    public long isType(String name, long mode) {
        if (mode != (mode & 0xffffffffL)) throw RuntimeServiceStatus.fault("Original stat predicate requires canonical Word32# mode");
        return (mode & required(types, "mask")) == required(types, name) ? 1L : 0L;
    }
    private static <T> T required(Map<String, T> values, String name) {
        T result = values.get(name);
        if (result == null) throw new NoSuchElementException("Key " + name + " is missing in the map.");
        return result;
    }
    private static void requireAbi(boolean valid, String detail) {
        if (!valid) throw new RuntimeFault("Unsupported original stat ABI: " + detail);
    }
    private static long integer(Object value) {
        requireAbi(value instanceof Integer || value instanceof Long, "exact integer required");
        return ((Number) value).longValue();
    }
    public static PosixStat parse(Object raw, String system, String arch) {
        if (!(raw instanceof Map<?, ?> document)) throw new RuntimeFault("Unsupported original stat ABI: manifest");
        String architecture = arch.equals("amd64") ? "x86_64" : arch.equals("arm64") ? "aarch64" : arch;
        boolean linux = system.equals("Linux"), darwin = system.equals("Darwin");
        String target = document.get("target") instanceof String text ? text : "";
        var targetParts = List.of(target.split("-", -1));
        requireAbi(integer(document.get("schema")) == 1 && (linux || darwin) &&
            (architecture.equals("x86_64") || architecture.equals("aarch64")) && system.equals(document.get("system")) &&
            architecture.equals(document.get("architecture")) && (linux ? (architecture + "-unknown-linux-gnu").equals(target) :
                targetParts.size() == 3 && (targetParts.get(0).equals(architecture) ||
                    architecture.equals("aarch64") && targetParts.get(0).equals("arm64")) &&
                targetParts.get(1).equals("apple") && targetParts.get(2).startsWith("darwin")), "native LP64 target required");
        if (!(document.get("stat") instanceof Map<?, ?> probe)) throw new RuntimeFault("Unsupported original stat ABI: stat layout");
        requireAbi(probe.keySet().equals(Set.of("size", "alignment", "fields", "types")), "layout fields");
        long size = integer(probe.get("size")), alignment = integer(probe.get("alignment"));
        requireAbi(size >= 1 && size <= 4096 && alignment == 8 && size % alignment == 0, "size/alignment");
        if (!(probe.get("fields") instanceof Map<?, ?> rawFields)) throw new RuntimeFault("Unsupported original stat ABI: fields");
        var names = List.of("st_dev", "st_ino", "st_mode", "st_size");
        requireAbi(rawFields.keySet().equals(Set.copyOf(names)), "exact member set");
        var fields = new LinkedHashMap<String, Field>();
        for (String name : names) {
            if (!(rawFields.get(name) instanceof Map<?, ?> field)) throw new RuntimeFault("Unsupported original stat ABI: member");
            boolean legacy = linux && field.keySet().equals(Set.of("offset", "width"));
            requireAbi(legacy || field.keySet().equals(Set.of("offset", "width", "signed")) && field.get("signed") instanceof Boolean,
                "member signedness");
            long rawWidth = integer(field.get("width"));
            requireAbi(linux ? rawWidth == (name.equals("st_mode") ? 4 : 8) :
                name.equals("st_mode") ? rawWidth == 2 || rawWidth == 4 :
                name.equals("st_dev") ? rawWidth == 4 || rawWidth == 8 : rawWidth == 8, "member width");
            int width = (int) rawWidth;
            boolean signed = legacy ? name.equals("st_size") : (boolean) field.get("signed");
            requireAbi(!linux && name.equals("st_dev") || signed == name.equals("st_size"), "member scalar kind");
            long offset = integer(field.get("offset"));
            requireAbi(offset >= 0 && offset <= size - width && offset % width == 0, "member offset");
            fields.put(name, new Field(offset, width, signed));
        }
        var bytes = new HashSet<Long>();
        for (var field : fields.values()) for (long index = field.offset; index < field.offset + field.width; index++)
            requireAbi(bytes.add(index), "overlapping members");
        if (!(probe.get("types") instanceof Map<?, ?> rawTypes)) throw new RuntimeFault("Unsupported original stat ABI: types");
        var typeNames = List.of("mask", "regular", "character", "block", "directory", "fifo", "socket");
        requireAbi(rawTypes.keySet().equals(Set.copyOf(typeNames)), "exact file type set");
        var types = new LinkedHashMap<String, Long>();
        for (String name : typeNames) types.put(name, integer(rawTypes.get(name)));
        var distinct = new HashSet<Long>();
        for (long value : types.values()) requireAbi(value >= 1 && value <= 0xffffffffL && distinct.add(value), "file type masks");
        for (String name : typeNames) if (!name.equals("mask")) {
            long value = types.get(name);
            requireAbi((value & types.get("mask")) == value, "file type masks");
        }
        return new PosixStat(size, fields, types);
    }

    private static volatile PosixStat host;
    private static PosixStat host() {
        var result = host;
        if (result == null) synchronized (PosixStat.class) {
            result = host;
            if (result == null) {
                try (var resource = PosixStat.class.getResourceAsStream("/thc/native/posix-stat-abi.json")) {
                    if (resource == null) throw new RuntimeFault("Missing native stat ABI probe");
                    String system = System.getProperty("os.name");
                    result = parse(Json.INSTANCE.parse(new String(resource.readAllBytes(), StandardCharsets.UTF_8)),
                        system.startsWith("Mac") ? "Darwin" : system, System.getProperty("os.arch"));
                } catch (IOException failure) { throw propagate(failure); }
                host = result;
            }
        }
        return result;
    }
    @TruffleBoundary public static long execute(OriginalStdioOp operation, ManagedAddress address, long mode) {
        var abi = host();
        return switch (operation) {
            case SIZEOF_STAT -> abi.size;
            case ST_DEV -> abi.field("st_dev", address); case ST_INO -> abi.field("st_ino", address);
            case ST_MODE -> abi.field("st_mode", address); case ST_SIZE -> abi.field("st_size", address);
            case IS_REG -> abi.isType("regular", mode); case IS_CHR -> abi.isType("character", mode);
            case IS_BLK -> abi.isType("block", mode); case IS_DIR -> abi.isType("directory", mode);
            case IS_FIFO -> abi.isType("fifo", mode); case IS_SOCK -> abi.isType("socket", mode);
            default -> throw RuntimeServiceStatus.fault("Invalid original stat operation");
        };
    }
    @SuppressWarnings("unchecked") private static <E extends Throwable> RuntimeException propagate(Throwable failure) throws E { throw (E) failure; }
}
