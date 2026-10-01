// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import thc.Json;

/** Actual C errno constants from the build host, not the private service categories. */
final class StdioHostAbi {
    private final Map<String, Long> errors, seek, open, signals;
    private final String system;
    private final Long atFdcwdValue, atRemoveDirValue, atSymlinkNoFollowValue, atEmptyPathValue, siginfoBytesValue;

    private StdioHostAbi(String system, Map<String, Long> errors, Map<String, Long> seek, Map<String, Long> open, Map<String, Long> signals,
            Long atFdcwd, Long atRemoveDir, Long atSymlinkNoFollow, Long atEmptyPath, Long siginfoBytes) {
        this.system = system;
        this.errors = errors;
        this.seek = seek;
        this.open = open;
        this.signals = signals;
        atFdcwdValue = atFdcwd;
        atRemoveDirValue = atRemoveDir;
        atSymlinkNoFollowValue = atSymlinkNoFollow;
        atEmptyPathValue = atEmptyPath;
        siginfoBytesValue = siginfoBytes;
    }

    private static long posix(Long value) {
        if (value == null) throw RuntimeFault.fault("Original POSIX ABI is unavailable on Windows");
        return value;
    }
    public long getAtFdcwd() { return posix(atFdcwdValue); }
    public long getAtRemoveDir() { return posix(atRemoveDirValue); }
    public long getAtSymlinkNoFollow() { return posix(atSymlinkNoFollowValue); }
    public long getAtEmptyPath() { return posix(atEmptyPathValue); }
    public long getSiginfoBytes() { return posix(siginfoBytesValue); }
    public String librarySuffix() {
        if (system.equals("Windows")) throw RuntimeFault.fault("POSIX native resource unavailable on Windows");
        return system.equals("Darwin") ? ".dylib" : ".so";
    }
    public long signal(String name) {
        Long value = signals.get(name);
        if (value == null) throw RuntimeFault.fault("Missing generated process signal ABI: " + name);
        return value;
    }
    public boolean supportedSignal(long number) {
        return SIGNAL_NAMES.stream().anyMatch(name -> signal(name) == number);
    }
    public void requireOpenAbi() {
        if (posix(open.get("modeBytes")) != 4) throw RuntimeFault.fault("Original open requires the Linux Word32 mode_t ABI");
    }
    public boolean openReadable(long flags) {
        long access = flags & posix(open.get("O_ACCMODE"));
        return access == open.get("O_RDONLY") || access == open.get("O_RDWR");
    }
    public boolean openWritable(long flags) {
        long access = flags & posix(open.get("O_ACCMODE"));
        return access == open.get("O_WRONLY") || access == open.get("O_RDWR");
    }
    public boolean openAppend(long flags) { return (flags & posix(open.get("O_APPEND"))) != 0; }
    public long flagConstant(OriginalStdioOp operation) {
        if (!operation.getFlagConstant()) throw RuntimeFault.fault("Invalid original file flag constant operation");
        return posix(open.get(operation.name()));
    }
    public long notTerminal() { return errors.get("ENOTTY"); }
    public long notSeekable() { return errors.get("ESPIPE"); }
    public long seekConstant(OriginalStdioOp operation) {
        return seek.get(switch (operation) {
            case SEEK_SET -> "SEEK_SET";
            case SEEK_CUR -> "SEEK_CUR";
            case SEEK_END -> "SEEK_END";
            default -> throw new RuntimeFault("Invalid original seek constant operation");
        });
    }
    /** C whence values are independent of the private managed 0/1/2 modes. */
    public Long seekMode(long whence) {
        if (whence == seek.get("SEEK_SET")) return 0L;
        if (whence == seek.get("SEEK_CUR")) return 1L;
        if (whence == seek.get("SEEK_END")) return 2L;
        return null;
    }
    public long error(long kind) {
        return errors.get(switch (kind >= 1 && kind <= 10 ? (int) kind : 0) {
            case 1 -> "ENOENT";
            case 2 -> "EACCES";
            case 3 -> "EEXIST";
            case 4 -> "EBADF";
            case 5 -> "EINVAL";
            case 7 -> "ENOTSUP";
            case 8 -> "EBUSY";
            case 9 -> "EISDIR";
            case 10 -> "EMFILE";
            default -> "EIO";
        });
    }
    /** Retain the private service's categories while separately preserving the
     * full captured errno for original calls (e.g. ESPIPE is not generic EIO). */
    public long privateErrorKind(long errno) {
        if (errno == notSeekable()) return 7;
        for (long kind = 1; kind <= 10; kind++) if (kind != 6 && error(kind) == errno) return kind;
        return 6;
    }

    private static final Map<String, Long> WIDTHS = Map.of("charBits", 8L, "pointer", 8L, "int", 4L, "bool", 1L, "size", 8L, "ssize", 8L);
    private static final Map<String, Long> WINDOWS_WIDTHS = Map.of("charBits", 8L, "pointer", 8L, "int", 4L, "long", 4L,
        "bool", 1L, "size", 8L, "crtReadResult", 4L, "crtReadCount", 4L);
    private static final Set<String> ERROR_NAMES = new LinkedHashSet<>(List.of("ENOENT", "EACCES", "EEXIST", "EBADF", "EINVAL", "EIO", "ENOTSUP", "EBUSY", "EISDIR", "ENOTTY", "ESPIPE", "EMFILE"));
    private static final Set<String> SEEK_NAMES = new LinkedHashSet<>(List.of("SEEK_SET", "SEEK_CUR", "SEEK_END"));
    private static final Set<String> OPEN_NAMES = new LinkedHashSet<>(List.of("modeBytes", "O_ACCMODE", "O_RDONLY", "O_WRONLY", "O_RDWR", "O_APPEND",
        "O_CREAT", "O_EXCL", "O_BINARY", "O_TRUNC", "O_NOCTTY", "O_NONBLOCK", "F_GETFL", "F_SETFL", "F_SETFD", "FD_CLOEXEC"));
    static final List<String> SIGNAL_NAMES = List.of("SIGHUP", "SIGINT", "SIGQUIT", "SIGUSR1", "SIGUSR2", "SIGTERM", "SIGXCPU", "SIGXFSZ");

    private static Long exactInteger(Object value) {
        return value instanceof Integer || value instanceof Long ? ((Number) value).longValue() : null;
    }
    private static String architecture(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "arm64" -> "aarch64";
            case "amd64" -> "x86_64";
            default -> value.toLowerCase(Locale.ROOT);
        };
    }
    private static void requireAbi(boolean condition, String detail) {
        if (!condition) throw new RuntimeFault("Invalid original stdio host ABI: " + detail);
    }

    public static StdioHostAbi parse(Object value, String system, String arch) {
        if (!(value instanceof Map<?, ?> manifest)) throw new RuntimeFault("Missing original stdio host ABI");
        boolean windows = system.equals("Windows");
        requireAbi(Objects.equals(exactInteger(manifest.get("schema")), windows ? 2L : 1L), "schema");
        String hostArch = architecture(arch);
        requireAbi(Set.of("Linux", "Darwin", "Windows").contains(system) &&
            (windows ? Set.of("x86_64") : Set.of("x86_64", "aarch64")).contains(hostArch), "unsupported runtime platform");
        requireAbi(system.equals(manifest.get("system")) && manifest.get("architecture") instanceof String declaredArch &&
            architecture(declaredArch).equals(hostArch), "platform mismatch");
        var target = manifest.get("target") instanceof String text ? List.of(text.split("-", -1)) : List.<String>of();
        requireAbi(target.size() >= 3 && architecture(target.get(0)).equals(hostArch) &&
            (system.equals("Linux") ? target.subList(2, target.size()).equals(List.of("linux", "gnu")) :
             windows ? target.subList(2, target.size()).equals(List.of("windows", "gnu")) : target.get(2).startsWith("darwin")), "compiler target");
        var sizes = manifest.get("widths") instanceof Map<?, ?> fields ? fields : null;
        var expectedWidths = windows ? WINDOWS_WIDTHS : WIDTHS;
        requireAbi(sizes != null && sizes.keySet().equals(expectedWidths.keySet()) &&
            expectedWidths.entrySet().stream().allMatch(entry -> entry.getValue().equals(exactInteger(sizes.get(entry.getKey())))), "native C widths");
        var rawErrors = manifest.get("errno") instanceof Map<?, ?> fields ? fields : null;
        requireAbi(rawErrors != null && rawErrors.keySet().equals(ERROR_NAMES), "errno fields");
        var errors = new HashMap<String, Long>();
        for (var name : ERROR_NAMES) {
            Long number = exactInteger(rawErrors.get(name));
            requireAbi(number != null && number >= 1 && number <= Integer.MAX_VALUE, "CInt errno " + name);
            errors.put(name, number);
        }
        var rawSeek = manifest.get("seek") instanceof Map<?, ?> fields ? fields : null;
        requireAbi(rawSeek != null && rawSeek.keySet().equals(SEEK_NAMES), "seek fields");
        var seek = new HashMap<String, Long>();
        for (var name : SEEK_NAMES) {
            Long number = exactInteger(rawSeek.get(name));
            requireAbi(number != null && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE, "CInt " + name);
            seek.put(name, number);
        }
        requireAbi(new HashSet<>(seek.values()).size() == SEEK_NAMES.size(), "distinct seek constants");
        if (windows) {
            // This receipt supplies constants for context-owned descriptor
            // transfers. It does not authorize POSIX FCalls or equate CRT
            // _read/_write's CInt/CUInt signature with ssize_t/size_t.
            requireAbi("windows-managed-descriptors".equals(manifest.get("profile")) &&
                manifest.keySet().equals(Set.of("schema", "profile", "system", "architecture", "target",
                    "compilerDefaultTarget", "compilerVersion", "sourceSha256", "widths", "errno", "seek")),
                "Windows descriptor profile without POSIX fields");
            return new StdioHostAbi(system, errors, seek, Map.of(), Map.of(), null, null, null, null, null);
        }
        var rawOpen = manifest.get("open") instanceof Map<?, ?> fields ? fields : null;
        requireAbi(rawOpen != null && rawOpen.keySet().equals(OPEN_NAMES), "open fields");
        var open = new HashMap<String, Long>();
        for (var name : OPEN_NAMES) {
            Long number = exactInteger(rawOpen.get(name));
            requireAbi(number != null && number >= 0 && number <= Integer.MAX_VALUE, "open " + name);
            open.put(name, number);
        }
        requireAbi(Set.of(2L, 4L).contains(open.get("modeBytes")), "mode_t width");
        requireAbi(!open.get("F_GETFL").equals(open.get("F_SETFL")), "distinct fcntl commands");
        var access = List.of(open.get("O_RDONLY"), open.get("O_WRONLY"), open.get("O_RDWR"));
        long mask = open.get("O_ACCMODE");
        requireAbi(mask != 0 && new HashSet<>(access).size() == 3 && access.stream().allMatch(it -> (it & mask) == it) &&
            open.get("O_APPEND") != 0 && (open.get("O_APPEND") & mask) == 0, "open access/status bits");
        var at = manifest.get("at") instanceof Map<?, ?> fields ? fields : null;
        requireAbi(at != null && at.keySet().equals(Set.of("AT_FDCWD", "AT_REMOVEDIR", "AT_SYMLINK_NOFOLLOW", "AT_EMPTY_PATH")), "at fields");
        Long atFdcwd = exactInteger(at.get("AT_FDCWD"));
        Long atRemoveDir = exactInteger(at.get("AT_REMOVEDIR"));
        requireAbi(atFdcwd != null && atFdcwd >= Integer.MIN_VALUE && atFdcwd <= -1, "negative CInt AT_FDCWD");
        requireAbi(atRemoveDir != null && atRemoveDir >= 1 && atRemoveDir <= Integer.MAX_VALUE, "positive CInt AT_REMOVEDIR");
        Long atNoFollow = exactInteger(at.get("AT_SYMLINK_NOFOLLOW"));
        Long atEmptyPath = exactInteger(at.get("AT_EMPTY_PATH"));
        requireAbi(atNoFollow != null && atNoFollow >= 1 && atNoFollow <= Integer.MAX_VALUE, "positive CInt AT_SYMLINK_NOFOLLOW");
        requireAbi(atEmptyPath != null && atEmptyPath >= (system.equals("Linux") ? 1 : 0) && atEmptyPath <= Integer.MAX_VALUE,
            "CInt AT_EMPTY_PATH availability");
        Long siginfoBytes = exactInteger(manifest.get("siginfoBytes"));
        requireAbi(siginfoBytes != null && siginfoBytes >= 1 && siginfoBytes <= Integer.MAX_VALUE, "siginfo_t size");
        var signals = new HashMap<String, Long>();
        // Old file-only receipts remain valid; actual signal use requires this
        // selected-header section and never supplies guessed platform numbers.
        if (manifest.containsKey("signals")) {
            var names = new HashSet<>(SIGNAL_NAMES); names.addAll(List.of("NSIG", "SIGBUS", "SIGSEGV"));
            var rawSignals = manifest.get("signals") instanceof Map<?, ?> fields ? fields : null;
            requireAbi(rawSignals != null && rawSignals.keySet().equals(names), "signal fields");
            Long limit = exactInteger(rawSignals.get("NSIG"));
            requireAbi(limit != null && limit >= 2 && limit <= Integer.MAX_VALUE, "signal range");
            var distinctSignals = new HashSet<Long>();
            for (String name : names) {
                Long number = exactInteger(rawSignals.get(name));
                requireAbi(number != null && number >= 1 && number <= limit &&
                    (name.equals("NSIG") || number < limit) && distinctSignals.add(number), "signal " + name);
                signals.put(name, number);
            }
        }
        return new StdioHostAbi(system, errors, seek, open, signals, atFdcwd, atRemoveDir, atNoFollow, atEmptyPath, siginfoBytes);
    }

    public static StdioHostAbi load() throws IOException {
        Object document;
        try (var stream = StdioHostAbi.class.getResourceAsStream("/thc/native/stdio-host-abi.json")) {
            if (stream == null) throw new RuntimeFault("Missing generated original stdio host ABI");
            document = Json.INSTANCE.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        if (document == null) throw new RuntimeFault("Missing generated original stdio host ABI");
        String system = System.getProperty("os.name");
        system = system.startsWith("Mac") ? "Darwin" : system.startsWith("Windows") ? "Windows" : system;
        return parse(document, system, System.getProperty("os.arch"));
    }
}
