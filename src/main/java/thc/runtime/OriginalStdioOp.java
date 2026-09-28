// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Exact pinned GHC/library declarations, not aliases for arbitrary POSIX imports. */
public enum OriginalStdioOp {
    // GHC marshals source BOOL through Int#, while the native Windows ABI uses
    // a 32-bit BOOL. GetLastError's DWORD retains its Word32# declaration.
    FIND_FIRST("FindFirstFileW", "ccall", "unsafe", Arrays.asList("AddrRep", "AddrRep", null), "AddrRep", "Win32-2.14.2.1-inplace"),
    FIND_NEXT("FindNextFileW", "ccall", "unsafe", Arrays.asList("AddrRep", "AddrRep", null), "IntRep", "Win32-2.14.2.1-inplace"),
    FIND_CLOSE("FindClose", "ccall", "unsafe", Arrays.asList("AddrRep", null), "IntRep", "Win32-2.14.2.1-inplace"),
    LAST_ERROR("GetLastError", "ccall", "unsafe", Arrays.asList((String) null), "Word32Rep", "Win32-2.14.2.1-inplace"),
    ANSI_CODE_PAGE("GetACP", "ccall", "unsafe", Arrays.asList((String) null), "Word32Rep"),
    CONSOLE_CODE_PAGE("GetConsoleCP", "ccall", "unsafe", Arrays.asList((String) null), "Word32Rep"),
    CODE_PAGE_INFO("GetCPInfo", "ccall", "unsafe", Arrays.asList("Word32Rep", "AddrRep", null), "IntRep"),
    DBCS_LEAD_BYTE("IsDBCSLeadByteEx", "ccall", "unsafe", Arrays.asList("Word32Rep", "Word8Rep", null), "IntRep"),
    MULTI_BYTE_TO_WIDE("MultiByteToWideChar", "ccall", "unsafe", Arrays.asList("Word32Rep", "Word32Rep", "AddrRep", "Int32Rep", "AddrRep", "Int32Rep", null), "Int32Rep"),
    WIDE_TO_MULTI_BYTE("WideCharToMultiByte", "ccall", "unsafe", Arrays.asList("Word32Rep", "Word32Rep", "AddrRep", "Int32Rep", "AddrRep", "Int32Rep", "AddrRep", "AddrRep", null), "Int32Rep"),
    MAP_ERRNO("maperrno", "ccall", "unsafe", Arrays.asList((String) null), null),
    MAP_ERRNO_VALUE("maperrno_func", "ccall", "unsafe", Arrays.asList("Word32Rep", null), "Int32Rep"),
    WINDOWS_ERROR_MESSAGE("base_getErrorMessage", "ccall", "unsafe", Arrays.asList("Word32Rep", null), "AddrRep"),
    LOCAL_FREE("LocalFree", "ccall", "unsafe", Arrays.asList("AddrRep", null), "AddrRep"),
    WCOREDUMP("ghczuwrapperZC0ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziProcessziInternalsZCWCOREDUMP", "capi", "unsafe", Arrays.asList("Int32Rep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    WSTOPSIG("ghczuwrapperZC1ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziProcessziInternalsZCWSTOPSIG", "capi", "unsafe", Arrays.asList("Int32Rep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    WIFSTOPPED("ghczuwrapperZC2ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziProcessziInternalsZCWIFSTOPPED", "capi", "unsafe", Arrays.asList("Int32Rep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    WTERMSIG("ghczuwrapperZC3ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziProcessziInternalsZCWTERMSIG", "capi", "unsafe", Arrays.asList("Int32Rep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    WIFSIGNALED("ghczuwrapperZC4ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziProcessziInternalsZCWIFSIGNALED", "capi", "unsafe", Arrays.asList("Int32Rep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    WEXITSTATUS("ghczuwrapperZC5ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziProcessziInternalsZCWEXITSTATUS", "capi", "unsafe", Arrays.asList("Int32Rep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    WIFEXITED("ghczuwrapperZC6ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziProcessziInternalsZCWIFEXITED", "capi", "unsafe", Arrays.asList("Int32Rep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    GET_SAVED_TERMIOS("__hscore_get_saved_termios", "ccall", "unsafe", Arrays.asList("Int32Rep", null), "AddrRep"),
    SET_SAVED_TERMIOS("__hscore_set_saved_termios", "ccall", "unsafe", Arrays.asList("Int32Rep", "AddrRep", null), null),
    SIGPROCMASK("ghczuwrapperZC11ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCsigprocmask", "capi", "unsafe",
        Arrays.asList("Int32Rep", "AddrRep", "AddrRep", null), "Int32Rep"),
    TCGETATTR("ghczuwrapperZC10ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCtcgetattr", "capi", "unsafe",
        Arrays.asList("Int32Rep", "AddrRep", null), "Int32Rep"),
    TCSETATTR("ghczuwrapperZC9ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCtcsetattr", "capi", "unsafe",
        Arrays.asList("Int32Rep", "Int32Rep", "AddrRep", null), "Int32Rep"),
    LFLAG("__hscore_lflag", "ccall", "unsafe", Arrays.asList("AddrRep", null), "Word32Rep"),
    POKE_LFLAG("__hscore_poke_lflag", "ccall", "unsafe", Arrays.asList("AddrRep", "Word32Rep", null), null),
    PTR_C_CC("__hscore_ptr_c_cc", "ccall", "unsafe", Arrays.asList("AddrRep", null), "AddrRep"),
    SIZEOF_TERMIOS("__hscore_sizeof_termios", "ccall", "unsafe", Arrays.asList((String) null), "IntRep"),
    ECHO("__hscore_echo", "ccall", "unsafe", Arrays.asList((String) null), "Int32Rep"),
    ICANON("__hscore_icanon", "ccall", "unsafe", Arrays.asList((String) null), "Int32Rep"),
    VMIN("__hscore_vmin", "ccall", "unsafe", Arrays.asList((String) null), "Int32Rep"),
    VTIME("__hscore_vtime", "ccall", "unsafe", Arrays.asList((String) null), "Int32Rep"),
    TCSANOW("__hscore_tcsanow", "ccall", "unsafe", Arrays.asList((String) null), "Int32Rep"),
    SIZEOF_SIGSET("__hscore_sizeof_sigset_t", "ccall", "unsafe", Arrays.asList((String) null), "IntRep"),
    SIGTTOU("__hscore_sigttou", "ccall", "unsafe", Arrays.asList((String) null), "Int32Rep"),
    SIG_BLOCK("__hscore_sig_block", "ccall", "unsafe", Arrays.asList((String) null), "Int32Rep"),
    SIG_SETMASK("__hscore_sig_setmask", "ccall", "unsafe", Arrays.asList((String) null), "Int32Rep"),
    SIGEMPTYSET("ghczuwrapperZC13ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCsigemptyset", "capi", "unsafe",
        Arrays.asList("AddrRep", null), "Int32Rep"),
    SIGADDSET("ghczuwrapperZC12ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCsigaddset", "capi", "unsafe",
        Arrays.asList("AddrRep", "Int32Rep", null), "Int32Rep"),
    READ_SAFE("ghczuwrapperZC22ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCread", "capi", "safe",
        Arrays.asList("Int32Rep", "AddrRep", "Word64Rep", null), "Int64Rep"),
    READ_UNSAFE("ghczuwrapperZC23ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCread", "capi", "unsafe",
        Arrays.asList("Int32Rep", "AddrRep", "Word64Rep", null), "Int64Rep"),
    WRITE_SAFE("ghczuwrapperZC20ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCwrite", "capi", "safe",
        Arrays.asList("Int32Rep", "AddrRep", "Word64Rep", null), "Int64Rep"),
    WRITE_UNSAFE("ghczuwrapperZC21ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCwrite", "capi", "unsafe",
        Arrays.asList("Int32Rep", "AddrRep", "Word64Rep", null), "Int64Rep"),
    GET_PID("getpid", "ccall", "unsafe", Arrays.asList((String) null), "Int32Rep"),
    GET_EUID("geteuid", "ccall", "unsafe", Arrays.asList((String) null), "Word32Rep", "unix-2.8.8.0-inplace"),
    ERRNO("__hscore_get_errno", "ccall", "unsafe", Arrays.asList((String) null), "Int32Rep"),
    SET_ERRNO("__hscore_set_errno", "ccall", "unsafe", Arrays.asList("Int32Rep", null), null),
    O_APPEND("__hscore_o_append", "ccall", "unsafe", Arrays.asList((String) null), "Int32Rep"),
    O_EXCL("__hscore_o_excl", "ccall", "unsafe", Arrays.asList((String) null), "Int32Rep"),
    O_BINARY("__hscore_o_binary", "ccall", "unsafe", Arrays.asList((String) null), "Int32Rep"),
    O_TRUNC("__hscore_o_trunc", "ccall", "unsafe", Arrays.asList((String) null), "Int32Rep"),
    O_CREAT("__hscore_o_creat", "ccall", "unsafe", Arrays.asList((String) null), "Int32Rep"),
    O_NOCTTY("__hscore_o_noctty", "ccall", "unsafe", Arrays.asList((String) null), "Int32Rep"),
    O_NONBLOCK("__hscore_o_nonblock", "ccall", "unsafe", Arrays.asList((String) null), "Int32Rep"),
    O_RDONLY("__hscore_o_rdonly", "ccall", "unsafe", Arrays.asList((String) null), "Int32Rep"),
    O_RDWR("__hscore_o_rdwr", "ccall", "unsafe", Arrays.asList((String) null), "Int32Rep"),
    O_WRONLY("__hscore_o_wronly", "ccall", "unsafe", Arrays.asList((String) null), "Int32Rep"),
    F_GETFL("__hscore_f_getfl", "ccall", "unsafe", Arrays.asList((String) null), "Int32Rep"),
    F_SETFL("__hscore_f_setfl", "ccall", "unsafe", Arrays.asList((String) null), "Int32Rep"),
    F_SETFD("__hscore_f_setfd", "ccall", "unsafe", Arrays.asList((String) null), "Int32Rep"),
    FD_CLOEXEC("__hscore_fd_cloexec", "ccall", "unsafe", Arrays.asList((String) null), "Int64Rep"),
    FCNTL_READ("ghczuwrapperZC17ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCfcntl", "capi", "unsafe",
        Arrays.asList("Int32Rep", "Int32Rep", null), "Int32Rep"),
    FCNTL_WRITE("ghczuwrapperZC16ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCfcntl", "capi", "unsafe",
        Arrays.asList("Int32Rep", "Int32Rep", "Int64Rep", null), "Int32Rep"),
    SEEK_SET("ghczuwrapperZC1ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSEEKzuSET", "capi", "unsafe", Arrays.asList((String) null), "Int32Rep"),
    SEEK_CUR("ghczuwrapperZC2ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSEEKzuCUR", "capi", "unsafe", Arrays.asList((String) null), "Int32Rep"),
    SEEK_END("ghczuwrapperZC0ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSEEKzuEND", "capi", "unsafe", Arrays.asList((String) null), "Int32Rep"),
    CLOSE("close", "ccall", "unsafe", Arrays.asList("Int32Rep", null), "Int32Rep"),
    EVENTFD("eventfd", "ccall", "unsafe", Arrays.asList("Int32Rep", "Int32Rep", null), "Int32Rep"),
    EVENTFD_WRITE("eventfd_write", "ccall", "unsafe", Arrays.asList("Int32Rep", "Word64Rep", null), "Int32Rep"),
    PIPE("pipe", "ccall", "unsafe", Arrays.asList("AddrRep", null), "Int32Rep"),
    EPOLL_CREATE("epoll_create", "ccall", "unsafe", Arrays.asList("Int32Rep", null), "Int32Rep"),
    EPOLL_CTL("epoll_ctl", "ccall", "unsafe", Arrays.asList("Int32Rep", "Int32Rep", "Int32Rep", "AddrRep", null), "Int32Rep"),
    EPOLL_WAIT_SAFE("epoll_wait", "ccall", "safe", Arrays.asList("Int32Rep", "AddrRep", "Int32Rep", "Int32Rep", null), "Int32Rep"),
    EPOLL_WAIT_UNSAFE("epoll_wait", "ccall", "unsafe", Arrays.asList("Int32Rep", "AddrRep", "Int32Rep", "Int32Rep", null), "Int32Rep"),
    POLL_SAFE("poll", "ccall", "safe", Arrays.asList("AddrRep", "Word64Rep", "Int32Rep", null), "Int32Rep"),
    POLL_UNSAFE("poll", "ccall", "unsafe", Arrays.asList("AddrRep", "Word64Rep", "Int32Rep", null), "Int32Rep"),
    IO_WAKEUP_FD("setIOManagerWakeupFd", "ccall", "unsafe", Arrays.asList("Int32Rep", null), null),
    IO_CONTROL_FD("setIOManagerControlFd", "ccall", "unsafe", Arrays.asList("Word32Rep", "Int32Rep", null), null),
    TIMER_CONTROL_FD("setTimerManagerControlFd", "ccall", "unsafe", Arrays.asList("Int32Rep", null), null),
    CHDIR("chdir", "ccall", "unsafe", Arrays.asList("AddrRep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    OPENDIR("ghczuwrapperZC0ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziDirectoryziPosixPathZCopendir", "capi", "unsafe",
        Arrays.asList("AddrRep", null), "AddrRep", "unix-2.8.8.0-inplace"),
    FDOPENDIR("ghczuwrapperZC0ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziDirectoryziCommonZCfdopendir", "capi", "unsafe",
        Arrays.asList("Int32Rep", null), "AddrRep", "unix-2.8.8.0-inplace"),
    CLOSEDIR("closedir", "ccall", "unsafe", Arrays.asList("AddrRep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    READDIR("__hscore_readdir", "ccall", "unsafe", Arrays.asList("AddrRep", "AddrRep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    DIRENT_NAME("__hscore_d_name", "ccall", "unsafe", Arrays.asList("AddrRep", null), "AddrRep", "unix-2.8.8.0-inplace"),
    FREE_DIRENT("__hscore_free_dirent", "ccall", "unsafe", Arrays.asList("AddrRep", null), null, "unix-2.8.8.0-inplace"),
    GETCWD("getcwd", "ccall", "unsafe", Arrays.asList("AddrRep", "Word64Rep", null), "AddrRep", "unix-2.8.8.0-inplace"),
    SYMLINK("symlink", "ccall", "unsafe", Arrays.asList("AddrRep", "AddrRep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    RMDIR("rmdir", "ccall", "unsafe", Arrays.asList("AddrRep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    READLINK("readlink", "ccall", "unsafe", Arrays.asList("AddrRep", "AddrRep", "Word64Rep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    MKDIR("mkdir", "ccall", "unsafe", Arrays.asList("AddrRep", "Word32Rep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    FSTATAT("ghczuwrapperZC1ZCdirectoryzm1zi3zi10zi0zminplaceZCSystemziDirectoryziInternalziPosixZCfstatat", "capi", "safe", Arrays.asList("Int32Rep", "AddrRep", "AddrRep", "Int32Rep", null), "Int32Rep", "directory-1.3.10.0-inplace"),
    UNLINKAT("unlinkat", "ccall", "safe", Arrays.asList("Int32Rep", "AddrRep", "Int32Rep", null), "Int32Rep", "directory-1.3.10.0-inplace"),
    ACCESS("access", "ccall", "unsafe", Arrays.asList("AddrRep", "Int32Rep", null), "Int32Rep"),
    CHMOD("chmod", "ccall", "unsafe", Arrays.asList("AddrRep", "Word32Rep", null), "Int32Rep"),
    UNLINK("unlink", "ccall", "unsafe", Arrays.asList("AddrRep", null), "Int32Rep"),
    OPEN("__hscore_open", "ccall", "unsafe", Arrays.asList("AddrRep", "Int32Rep", "Word32Rep", null), "Int32Rep"),
    OPEN_SAFE("__hscore_open", "ccall", "safe", Arrays.asList("AddrRep", "Int32Rep", "Word32Rep", null), "Int32Rep"),
    OPEN_INTERRUPTIBLE("__hscore_open", "ccall", "interruptible", Arrays.asList("AddrRep", "Int32Rep", "Word32Rep", null), "Int32Rep"),
    DUP("dup", "ccall", "unsafe", Arrays.asList("Int32Rep", null), "Int32Rep"),
    DUP2("dup2", "ccall", "unsafe", Arrays.asList("Int32Rep", "Int32Rep", null), "Int32Rep"),
    SEEK("ghczuwrapperZC19ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZClseek", "capi", "unsafe",
        Arrays.asList("Int32Rep", "Int64Rep", "Int32Rep", null), "Int64Rep"),
    TRUNCATE("__hscore_ftruncate", "ccall", "unsafe", Arrays.asList("Int32Rep", "Int64Rep", null), "Int32Rep"),
    STAT("__hscore_stat", "ccall", "unsafe", Arrays.asList("AddrRep", "AddrRep", null), "Int32Rep"),
    LSTAT("__hscore_lstat", "ccall", "unsafe", Arrays.asList("AddrRep", "AddrRep", null), "Int32Rep"),
    UNIX_LSTAT("ghczuwrapperZC2ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziFilesziPosixStringZClstat", "capi", "unsafe",
        Arrays.asList("AddrRep", "AddrRep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    FSTAT("__hscore_fstat", "ccall", "unsafe", Arrays.asList("Int32Rep", "AddrRep", null), "Int32Rep"),
    LOCK("lockFile", "ccall", "unsafe", Arrays.asList("Word64Rep", "Word64Rep", "Word64Rep", "Int32Rep", null), "Int32Rep"),
    UNLOCK("unlockFile", "ccall", "unsafe", Arrays.asList("Word64Rep", null), "Int32Rep"),
    SIZEOF_STAT("__hscore_sizeof_stat", "ccall", "unsafe", Arrays.asList((String) null), "IntRep"),
    ST_DEV("__hscore_st_dev", "ccall", "unsafe", Arrays.asList("AddrRep", null), "Word64Rep"),
    ST_INO("__hscore_st_ino", "ccall", "unsafe", Arrays.asList("AddrRep", null), "Word64Rep"),
    ST_MODE("__hscore_st_mode", "ccall", "unsafe", Arrays.asList("AddrRep", null), "Word32Rep"),
    ST_SIZE("__hscore_st_size", "ccall", "unsafe", Arrays.asList("AddrRep", null), "Int64Rep"),
    IS_REG("ghczuwrapperZC8ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSzuISREG", "capi", "unsafe", Arrays.asList("Word32Rep", null), "Int32Rep"),
    IS_CHR("ghczuwrapperZC7ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSzuISCHR", "capi", "unsafe", Arrays.asList("Word32Rep", null), "Int32Rep"),
    IS_BLK("ghczuwrapperZC6ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSzuISBLK", "capi", "unsafe", Arrays.asList("Word32Rep", null), "Int32Rep"),
    IS_DIR("ghczuwrapperZC5ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSzuISDIR", "capi", "unsafe", Arrays.asList("Word32Rep", null), "Int32Rep"),
    IS_FIFO("ghczuwrapperZC4ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSzuISFIFO", "capi", "unsafe", Arrays.asList("Word32Rep", null), "Int32Rep"),
    IS_SOCK("ghczuwrapperZC3ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSzuISSOCK", "capi", "unsafe", Arrays.asList("Word32Rep", null), "Int32Rep"),
    ISATTY("isatty", "ccall", "unsafe", Arrays.asList("Int32Rep", null), "Int32Rep"),
    READY_SAFE("fdReady", "ccall", "safe", Arrays.asList("Int32Rep", "Word8Rep", "Int64Rep", "Word8Rep", null), "Int32Rep"),
    READY_UNSAFE("fdReady", "ccall", "unsafe", Arrays.asList("Int32Rep", "Word8Rep", "Int64Rep", "Word8Rep", null), "Int32Rep"),
    LOCALE("localeEncoding", "ccall", "unsafe", Arrays.asList((String) null), "AddrRep"),
    ICONV_OPEN("hs_iconv_open", "ccall", "unsafe", Arrays.asList("AddrRep", "AddrRep", null), "Int64Rep"),
    ICONV_CLOSE("hs_iconv_close", "ccall", "unsafe", Arrays.asList("Int64Rep", null), "Int32Rep"),
    ICONV("hs_iconv", "ccall", "unsafe", Arrays.asList("Int64Rep", "AddrRep", "AddrRep", "AddrRep", "AddrRep", null), "Word64Rep"),
    STRERROR("base_strerror_r", "ccall", "safe", Arrays.asList("Int32Rep", "AddrRep", "Word64Rep", null), "Int32Rep");


    private final String symbol, convention, safety, result, unit;
    private final List<String> arguments;
    private final NarrowInteger narrowResult;
    private static final Pattern DIRECTORY_UNIT = Pattern.compile("directory-1\\.3\\.10\\.0-(?:inplace|[0-9a-f]+)");
    private static final Pattern WIN32_UNIT = Pattern.compile("Win32-2\\.14\\.2\\.1-(?:inplace|[0-9a-f]+)");
    private static final Pattern DIRECTORY_WRAPPER_UNIT = Pattern.compile("directoryzm1zi3zi10zi0zm(?:inplace|[0-9a-f]+)ZC");
    private static final Pattern UNIX_WRAPPER_UNIT = Pattern.compile("unixzm2zi8zi8zi0zm(?:inplace|[0-9a-f]+)ZC");

    OriginalStdioOp(String symbol, String convention, String safety, List<String> arguments, String result) {
        this(symbol, convention, safety, arguments, result, "ghc-internal");
    }
    OriginalStdioOp(String symbol, String convention, String safety, List<String> arguments, String result, String unit) {
        this.symbol = symbol;
        this.convention = convention;
        this.safety = safety;
        this.arguments = Collections.unmodifiableList(arguments);
        this.result = result;
        this.unit = unit;
        this.narrowResult = NarrowInteger.fromRep(result);
    }
    public String getSymbol() { return symbol; }
    public String getConvention() { return convention; }
    public String getSafety() { return safety; }
    public List<String> getArguments() { return arguments; }
    public String getResult() { return result; }
    public String getUnit() { return unit; }
    public NarrowInteger getNarrowResult() { return narrowResult; }

    // Only these reviewed declarations accept an installed identity of this release.
    public boolean acceptsUnit(Object value) {
        return unit.equals(value) ||
            this == LAST_ERROR && ("ghc-internal".equals(value) || value instanceof String text && WIN32_UNIT.matcher(text).matches()) ||
            getWindowsDirectory() && value instanceof String text && WIN32_UNIT.matcher(text).matches() ||
            this == READLINK && "ghc-internal".equals(value) ||
            (this == UNLINKAT || this == FSTATAT) && value instanceof String text && DIRECTORY_UNIT.matcher(text).matches() ||
            CoreOriginalStdio.isOriginalUnixUnit(value) && !getWindowsDirectory() && (this == CLOSE || this == DUP || this == DUP2 || this == PIPE || this == ISATTY ||
                this == UNIX_LSTAT || getCurrentDirectory() || getDirectoryStream() || getWaitStatus() || this == MKDIR || this == RMDIR || this == SYMLINK || this == READLINK || this == GET_EUID);
    }
    public boolean matchesSymbol(Object value) {
        return symbol.equals(value) ||
            this == FSTATAT && value instanceof String text && DIRECTORY_WRAPPER_UNIT.matcher(text).replaceAll("directoryzm1zi3zi10zi0zminplaceZC").equals(symbol) ||
            (this == UNIX_LSTAT || getWaitStatus() || this == OPENDIR || this == FDOPENDIR) && value instanceof String text &&
                UNIX_WRAPPER_UNIT.matcher(text).replaceAll("unixzm2zi8zi8zi0zminplaceZC").equals(symbol);
    }

    // Direct comparisons keep the operation constant during partial evaluation.
    public boolean getProcessIdentity() { return this == GET_PID || this == GET_EUID; }
    public boolean getReadiness() { return this == READY_SAFE || this == READY_UNSAFE; }
    public boolean getWaitStatus() { return this == WCOREDUMP || this == WSTOPSIG || this == WIFSTOPPED ||
        this == WTERMSIG || this == WIFSIGNALED || this == WEXITSTATUS || this == WIFEXITED; }
    public boolean getDuplication() { return this == DUP || this == DUP2; }
    public boolean getLocking() { return this == LOCK || this == UNLOCK; }
    public boolean getFlagConstant() { return this == O_APPEND || this == O_CREAT || this == O_EXCL || this == O_BINARY || this == O_TRUNC || this == O_NOCTTY ||
        this == O_NONBLOCK || this == O_RDONLY || this == O_RDWR || this == O_WRONLY ||
        this == F_GETFL || this == F_SETFL || this == F_SETFD || this == FD_CLOEXEC; }
    public boolean getFcntl() { return this == FCNTL_READ || this == FCNTL_WRITE; }
    public boolean getEventPair() { return this == EVENTFD || this == EVENTFD_WRITE; }
    public boolean getPoll() { return this == POLL_SAFE || this == POLL_UNSAFE; }
    public boolean getEpollWait() { return this == EPOLL_WAIT_SAFE || this == EPOLL_WAIT_UNSAFE; }
    public boolean getControlFd() { return this == IO_WAKEUP_FD || this == IO_CONTROL_FD || this == TIMER_CONTROL_FD; }
    public boolean getEventManager() { return getPoll() || getEpollWait() || getControlFd() || this == EPOLL_CREATE || this == EPOLL_CTL; }
    public boolean getEventDescriptor() { return getEventPair() || this == PIPE || getEventManager(); }
    public boolean getSeekConstant() { return this == SEEK_SET || this == SEEK_CUR || this == SEEK_END; }
    public boolean getStat() { return this == SIZEOF_STAT || getStatField() ||
        this == IS_REG || this == IS_CHR || this == IS_BLK || this == IS_DIR || this == IS_FIFO || this == IS_SOCK; }
    public boolean getStatField() { return this == ST_DEV || this == ST_INO || this == ST_MODE || this == ST_SIZE; }
    public boolean getCurrentDirectory() { return this == CHDIR || this == GETCWD; }
    public boolean getWindowsDirectory() { return this == FIND_FIRST || this == FIND_NEXT || this == FIND_CLOSE; }
    public boolean getWindowsEncoding() { return this == LAST_ERROR || this == ANSI_CODE_PAGE || this == CONSOLE_CODE_PAGE ||
        this == CODE_PAGE_INFO || this == DBCS_LEAD_BYTE || this == MULTI_BYTE_TO_WIDE || this == WIDE_TO_MULTI_BYTE ||
        this == MAP_ERRNO || this == MAP_ERRNO_VALUE || this == WINDOWS_ERROR_MESSAGE || this == LOCAL_FREE; }
    public boolean getDirectoryStream() { return getWindowsDirectory() || this == OPENDIR || this == FDOPENDIR || this == CLOSEDIR ||
        this == READDIR || this == DIRENT_NAME || this == FREE_DIRENT; }
    public boolean getDirectoryPointer() { return this == OPENDIR || this == DIRENT_NAME; }
    public boolean getPathRemoval() { return this == UNLINK || this == RMDIR; }
    public boolean getPathLink() { return this == SYMLINK || this == READLINK; }
    public boolean getPathMode() { return this == MKDIR || this == CHMOD; }
    public boolean getPathStat() { return this == STAT || this == LSTAT || this == UNIX_LSTAT; }
    public boolean getReadImage() { return this == FSTAT || this == TCGETATTR; }
    public boolean getIconv() { return this == LOCALE || this == ICONV_OPEN || this == ICONV_CLOSE || this == ICONV; }
    public boolean getStrerror() { return this == STRERROR; }
    public boolean getTermios() { return this == LFLAG || this == POKE_LFLAG || this == PTR_C_CC ||
        this == SIZEOF_TERMIOS || this == ECHO || this == ICANON || this == VMIN || this == VTIME || this == TCSANOW ||
        this == SIZEOF_SIGSET || this == SIGTTOU || this == SIG_BLOCK || this == SIG_SETMASK; }
    public boolean getTermiosAddress() { return this == LFLAG || this == POKE_LFLAG || this == PTR_C_CC; }
    public boolean getSigset() { return this == SIGEMPTYSET || this == SIGADDSET; }
    public boolean getSavedTermios() { return this == GET_SAVED_TERMIOS || this == SET_SAVED_TERMIOS; }
    public boolean getOpening() { return this == OPEN || this == OPEN_SAFE || this == OPEN_INTERRUPTIBLE; }
}
