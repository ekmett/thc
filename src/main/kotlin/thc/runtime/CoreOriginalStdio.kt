// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

package thc.runtime

import com.oracle.truffle.api.nodes.Node
import thc.Language

// Preserve the genuine installed owner; only this pinned Unix release is reviewed.
private val unixUnit = Regex("unix-2\\.8\\.8\\.0-(?:inplace|[0-9a-f]+)")
private val directoryUnit = Regex("directory-1\\.3\\.10\\.0-(?:inplace|[0-9a-f]+)")
private val win32Unit = Regex("Win32-2\\.14\\.2\\.1-(?:inplace|[0-9a-f]+)")
private val directoryWrapperUnit = Regex("directoryzm1zi3zi10zi0zm(?:inplace|[0-9a-f]+)ZC")
private val unixWrapperUnit = Regex("unixzm2zi8zi8zi0zm(?:inplace|[0-9a-f]+)ZC")
internal fun isOriginalUnixUnit(unit: Any?): Boolean = unit is String && unixUnit.matches(unit)

/** Exact pinned GHC/library declarations, not aliases for arbitrary POSIX imports. */
internal enum class OriginalStdioOp(val symbol: String, val convention: String, val safety: String,
    val arguments: List<String?>, val result: String?, val unit: String = "ghc-internal") {
    // GHC marshals source BOOL through Int#, while the native Windows ABI uses
    // a 32-bit BOOL. GetLastError's DWORD retains its Word32# declaration.
    FIND_FIRST("FindFirstFileW", "ccall", "unsafe", listOf("AddrRep", "AddrRep", null), "AddrRep", "Win32-2.14.2.1-inplace"),
    FIND_NEXT("FindNextFileW", "ccall", "unsafe", listOf("AddrRep", "AddrRep", null), "IntRep", "Win32-2.14.2.1-inplace"),
    FIND_CLOSE("FindClose", "ccall", "unsafe", listOf("AddrRep", null), "IntRep", "Win32-2.14.2.1-inplace"),
    LAST_ERROR("GetLastError", "ccall", "unsafe", listOf(null), "Word32Rep", "Win32-2.14.2.1-inplace"),
    ANSI_CODE_PAGE("GetACP", "ccall", "unsafe", listOf(null), "Word32Rep"),
    CONSOLE_CODE_PAGE("GetConsoleCP", "ccall", "unsafe", listOf(null), "Word32Rep"),
    CODE_PAGE_INFO("GetCPInfo", "ccall", "unsafe", listOf("Word32Rep", "AddrRep", null), "IntRep"),
    DBCS_LEAD_BYTE("IsDBCSLeadByteEx", "ccall", "unsafe", listOf("Word32Rep", "Word8Rep", null), "IntRep"),
    MULTI_BYTE_TO_WIDE("MultiByteToWideChar", "ccall", "unsafe", listOf("Word32Rep", "Word32Rep", "AddrRep", "Int32Rep", "AddrRep", "Int32Rep", null), "Int32Rep"),
    WIDE_TO_MULTI_BYTE("WideCharToMultiByte", "ccall", "unsafe", listOf("Word32Rep", "Word32Rep", "AddrRep", "Int32Rep", "AddrRep", "Int32Rep", "AddrRep", "AddrRep", null), "Int32Rep"),
    MAP_ERRNO("maperrno", "ccall", "unsafe", listOf(null), null),
    MAP_ERRNO_VALUE("maperrno_func", "ccall", "unsafe", listOf("Word32Rep", null), "Int32Rep"),
    WINDOWS_ERROR_MESSAGE("base_getErrorMessage", "ccall", "unsafe", listOf("Word32Rep", null), "AddrRep"),
    LOCAL_FREE("LocalFree", "ccall", "unsafe", listOf("AddrRep", null), "AddrRep"),
    WCOREDUMP("ghczuwrapperZC0ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziProcessziInternalsZCWCOREDUMP", "capi", "unsafe", listOf("Int32Rep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    WSTOPSIG("ghczuwrapperZC1ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziProcessziInternalsZCWSTOPSIG", "capi", "unsafe", listOf("Int32Rep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    WIFSTOPPED("ghczuwrapperZC2ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziProcessziInternalsZCWIFSTOPPED", "capi", "unsafe", listOf("Int32Rep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    WTERMSIG("ghczuwrapperZC3ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziProcessziInternalsZCWTERMSIG", "capi", "unsafe", listOf("Int32Rep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    WIFSIGNALED("ghczuwrapperZC4ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziProcessziInternalsZCWIFSIGNALED", "capi", "unsafe", listOf("Int32Rep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    WEXITSTATUS("ghczuwrapperZC5ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziProcessziInternalsZCWEXITSTATUS", "capi", "unsafe", listOf("Int32Rep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    WIFEXITED("ghczuwrapperZC6ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziProcessziInternalsZCWIFEXITED", "capi", "unsafe", listOf("Int32Rep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    GET_SAVED_TERMIOS("__hscore_get_saved_termios", "ccall", "unsafe", listOf("Int32Rep", null), "AddrRep"),
    SET_SAVED_TERMIOS("__hscore_set_saved_termios", "ccall", "unsafe", listOf("Int32Rep", "AddrRep", null), null),
    SIGPROCMASK("ghczuwrapperZC11ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCsigprocmask", "capi", "unsafe",
        listOf("Int32Rep", "AddrRep", "AddrRep", null), "Int32Rep"),
    TCGETATTR("ghczuwrapperZC10ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCtcgetattr", "capi", "unsafe",
        listOf("Int32Rep", "AddrRep", null), "Int32Rep"),
    TCSETATTR("ghczuwrapperZC9ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCtcsetattr", "capi", "unsafe",
        listOf("Int32Rep", "Int32Rep", "AddrRep", null), "Int32Rep"),
    LFLAG("__hscore_lflag", "ccall", "unsafe", listOf("AddrRep", null), "Word32Rep"),
    POKE_LFLAG("__hscore_poke_lflag", "ccall", "unsafe", listOf("AddrRep", "Word32Rep", null), null),
    PTR_C_CC("__hscore_ptr_c_cc", "ccall", "unsafe", listOf("AddrRep", null), "AddrRep"),
    SIZEOF_TERMIOS("__hscore_sizeof_termios", "ccall", "unsafe", listOf(null), "IntRep"),
    ECHO("__hscore_echo", "ccall", "unsafe", listOf(null), "Int32Rep"),
    ICANON("__hscore_icanon", "ccall", "unsafe", listOf(null), "Int32Rep"),
    VMIN("__hscore_vmin", "ccall", "unsafe", listOf(null), "Int32Rep"),
    VTIME("__hscore_vtime", "ccall", "unsafe", listOf(null), "Int32Rep"),
    TCSANOW("__hscore_tcsanow", "ccall", "unsafe", listOf(null), "Int32Rep"),
    SIZEOF_SIGSET("__hscore_sizeof_sigset_t", "ccall", "unsafe", listOf(null), "IntRep"),
    SIGTTOU("__hscore_sigttou", "ccall", "unsafe", listOf(null), "Int32Rep"),
    SIG_BLOCK("__hscore_sig_block", "ccall", "unsafe", listOf(null), "Int32Rep"),
    SIG_SETMASK("__hscore_sig_setmask", "ccall", "unsafe", listOf(null), "Int32Rep"),
    SIGEMPTYSET("ghczuwrapperZC13ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCsigemptyset", "capi", "unsafe",
        listOf("AddrRep", null), "Int32Rep"),
    SIGADDSET("ghczuwrapperZC12ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCsigaddset", "capi", "unsafe",
        listOf("AddrRep", "Int32Rep", null), "Int32Rep"),
    READ_SAFE("ghczuwrapperZC22ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCread", "capi", "safe",
        listOf("Int32Rep", "AddrRep", "Word64Rep", null), "Int64Rep"),
    READ_UNSAFE("ghczuwrapperZC23ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCread", "capi", "unsafe",
        listOf("Int32Rep", "AddrRep", "Word64Rep", null), "Int64Rep"),
    WRITE_SAFE("ghczuwrapperZC20ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCwrite", "capi", "safe",
        listOf("Int32Rep", "AddrRep", "Word64Rep", null), "Int64Rep"),
    WRITE_UNSAFE("ghczuwrapperZC21ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCwrite", "capi", "unsafe",
        listOf("Int32Rep", "AddrRep", "Word64Rep", null), "Int64Rep"),
    GET_PID("getpid", "ccall", "unsafe", listOf(null), "Int32Rep"),
    GET_EUID("geteuid", "ccall", "unsafe", listOf(null), "Word32Rep", "unix-2.8.8.0-inplace"),
    ERRNO("__hscore_get_errno", "ccall", "unsafe", listOf(null), "Int32Rep"),
    SET_ERRNO("__hscore_set_errno", "ccall", "unsafe", listOf("Int32Rep", null), null),
    O_APPEND("__hscore_o_append", "ccall", "unsafe", listOf(null), "Int32Rep"),
    O_EXCL("__hscore_o_excl", "ccall", "unsafe", listOf(null), "Int32Rep"),
    O_BINARY("__hscore_o_binary", "ccall", "unsafe", listOf(null), "Int32Rep"),
    O_TRUNC("__hscore_o_trunc", "ccall", "unsafe", listOf(null), "Int32Rep"),
    O_CREAT("__hscore_o_creat", "ccall", "unsafe", listOf(null), "Int32Rep"),
    O_NOCTTY("__hscore_o_noctty", "ccall", "unsafe", listOf(null), "Int32Rep"),
    O_NONBLOCK("__hscore_o_nonblock", "ccall", "unsafe", listOf(null), "Int32Rep"),
    O_RDONLY("__hscore_o_rdonly", "ccall", "unsafe", listOf(null), "Int32Rep"),
    O_RDWR("__hscore_o_rdwr", "ccall", "unsafe", listOf(null), "Int32Rep"),
    O_WRONLY("__hscore_o_wronly", "ccall", "unsafe", listOf(null), "Int32Rep"),
    F_GETFL("__hscore_f_getfl", "ccall", "unsafe", listOf(null), "Int32Rep"),
    F_SETFL("__hscore_f_setfl", "ccall", "unsafe", listOf(null), "Int32Rep"),
    F_SETFD("__hscore_f_setfd", "ccall", "unsafe", listOf(null), "Int32Rep"),
    FD_CLOEXEC("__hscore_fd_cloexec", "ccall", "unsafe", listOf(null), "Int64Rep"),
    FCNTL_READ("ghczuwrapperZC17ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCfcntl", "capi", "unsafe",
        listOf("Int32Rep", "Int32Rep", null), "Int32Rep"),
    FCNTL_WRITE("ghczuwrapperZC16ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCfcntl", "capi", "unsafe",
        listOf("Int32Rep", "Int32Rep", "Int64Rep", null), "Int32Rep"),
    SEEK_SET("ghczuwrapperZC1ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSEEKzuSET", "capi", "unsafe", listOf(null), "Int32Rep"),
    SEEK_CUR("ghczuwrapperZC2ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSEEKzuCUR", "capi", "unsafe", listOf(null), "Int32Rep"),
    SEEK_END("ghczuwrapperZC0ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSEEKzuEND", "capi", "unsafe", listOf(null), "Int32Rep"),
    CLOSE("close", "ccall", "unsafe", listOf("Int32Rep", null), "Int32Rep"),
    EVENTFD("eventfd", "ccall", "unsafe", listOf("Int32Rep", "Int32Rep", null), "Int32Rep"),
    EVENTFD_WRITE("eventfd_write", "ccall", "unsafe", listOf("Int32Rep", "Word64Rep", null), "Int32Rep"),
    PIPE("pipe", "ccall", "unsafe", listOf("AddrRep", null), "Int32Rep"),
    EPOLL_CREATE("epoll_create", "ccall", "unsafe", listOf("Int32Rep", null), "Int32Rep"),
    EPOLL_CTL("epoll_ctl", "ccall", "unsafe", listOf("Int32Rep", "Int32Rep", "Int32Rep", "AddrRep", null), "Int32Rep"),
    EPOLL_WAIT_SAFE("epoll_wait", "ccall", "safe", listOf("Int32Rep", "AddrRep", "Int32Rep", "Int32Rep", null), "Int32Rep"),
    EPOLL_WAIT_UNSAFE("epoll_wait", "ccall", "unsafe", listOf("Int32Rep", "AddrRep", "Int32Rep", "Int32Rep", null), "Int32Rep"),
    POLL_SAFE("poll", "ccall", "safe", listOf("AddrRep", "Word64Rep", "Int32Rep", null), "Int32Rep"),
    POLL_UNSAFE("poll", "ccall", "unsafe", listOf("AddrRep", "Word64Rep", "Int32Rep", null), "Int32Rep"),
    IO_WAKEUP_FD("setIOManagerWakeupFd", "ccall", "unsafe", listOf("Int32Rep", null), null),
    IO_CONTROL_FD("setIOManagerControlFd", "ccall", "unsafe", listOf("Word32Rep", "Int32Rep", null), null),
    TIMER_CONTROL_FD("setTimerManagerControlFd", "ccall", "unsafe", listOf("Int32Rep", null), null),
    CHDIR("chdir", "ccall", "unsafe", listOf("AddrRep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    OPENDIR("ghczuwrapperZC0ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziDirectoryziPosixPathZCopendir", "capi", "unsafe",
        listOf("AddrRep", null), "AddrRep", "unix-2.8.8.0-inplace"),
    FDOPENDIR("ghczuwrapperZC0ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziDirectoryziCommonZCfdopendir", "capi", "unsafe",
        listOf("Int32Rep", null), "AddrRep", "unix-2.8.8.0-inplace"),
    CLOSEDIR("closedir", "ccall", "unsafe", listOf("AddrRep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    READDIR("__hscore_readdir", "ccall", "unsafe", listOf("AddrRep", "AddrRep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    DIRENT_NAME("__hscore_d_name", "ccall", "unsafe", listOf("AddrRep", null), "AddrRep", "unix-2.8.8.0-inplace"),
    FREE_DIRENT("__hscore_free_dirent", "ccall", "unsafe", listOf("AddrRep", null), null, "unix-2.8.8.0-inplace"),
    GETCWD("getcwd", "ccall", "unsafe", listOf("AddrRep", "Word64Rep", null), "AddrRep", "unix-2.8.8.0-inplace"),
    SYMLINK("symlink", "ccall", "unsafe", listOf("AddrRep", "AddrRep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    RMDIR("rmdir", "ccall", "unsafe", listOf("AddrRep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    READLINK("readlink", "ccall", "unsafe", listOf("AddrRep", "AddrRep", "Word64Rep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    MKDIR("mkdir", "ccall", "unsafe", listOf("AddrRep", "Word32Rep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    FSTATAT("ghczuwrapperZC1ZCdirectoryzm1zi3zi10zi0zminplaceZCSystemziDirectoryziInternalziPosixZCfstatat", "capi", "safe", listOf("Int32Rep", "AddrRep", "AddrRep", "Int32Rep", null), "Int32Rep", "directory-1.3.10.0-inplace"),
    UNLINKAT("unlinkat", "ccall", "safe", listOf("Int32Rep", "AddrRep", "Int32Rep", null), "Int32Rep", "directory-1.3.10.0-inplace"),
    ACCESS("access", "ccall", "unsafe", listOf("AddrRep", "Int32Rep", null), "Int32Rep"),
    CHMOD("chmod", "ccall", "unsafe", listOf("AddrRep", "Word32Rep", null), "Int32Rep"),
    UNLINK("unlink", "ccall", "unsafe", listOf("AddrRep", null), "Int32Rep"),
    OPEN("__hscore_open", "ccall", "unsafe", listOf("AddrRep", "Int32Rep", "Word32Rep", null), "Int32Rep"),
    OPEN_SAFE("__hscore_open", "ccall", "safe", listOf("AddrRep", "Int32Rep", "Word32Rep", null), "Int32Rep"),
    OPEN_INTERRUPTIBLE("__hscore_open", "ccall", "interruptible", listOf("AddrRep", "Int32Rep", "Word32Rep", null), "Int32Rep"),
    DUP("dup", "ccall", "unsafe", listOf("Int32Rep", null), "Int32Rep"),
    DUP2("dup2", "ccall", "unsafe", listOf("Int32Rep", "Int32Rep", null), "Int32Rep"),
    SEEK("ghczuwrapperZC19ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZClseek", "capi", "unsafe",
        listOf("Int32Rep", "Int64Rep", "Int32Rep", null), "Int64Rep"),
    TRUNCATE("__hscore_ftruncate", "ccall", "unsafe", listOf("Int32Rep", "Int64Rep", null), "Int32Rep"),
    STAT("__hscore_stat", "ccall", "unsafe", listOf("AddrRep", "AddrRep", null), "Int32Rep"),
    LSTAT("__hscore_lstat", "ccall", "unsafe", listOf("AddrRep", "AddrRep", null), "Int32Rep"),
    UNIX_LSTAT("ghczuwrapperZC2ZCunixzm2zi8zi8zi0zminplaceZCSystemziPosixziFilesziPosixStringZClstat", "capi", "unsafe",
        listOf("AddrRep", "AddrRep", null), "Int32Rep", "unix-2.8.8.0-inplace"),
    FSTAT("__hscore_fstat", "ccall", "unsafe", listOf("Int32Rep", "AddrRep", null), "Int32Rep"),
    LOCK("lockFile", "ccall", "unsafe", listOf("Word64Rep", "Word64Rep", "Word64Rep", "Int32Rep", null), "Int32Rep"),
    UNLOCK("unlockFile", "ccall", "unsafe", listOf("Word64Rep", null), "Int32Rep"),
    SIZEOF_STAT("__hscore_sizeof_stat", "ccall", "unsafe", listOf(null), "IntRep"),
    ST_DEV("__hscore_st_dev", "ccall", "unsafe", listOf("AddrRep", null), "Word64Rep"),
    ST_INO("__hscore_st_ino", "ccall", "unsafe", listOf("AddrRep", null), "Word64Rep"),
    ST_MODE("__hscore_st_mode", "ccall", "unsafe", listOf("AddrRep", null), "Word32Rep"),
    ST_SIZE("__hscore_st_size", "ccall", "unsafe", listOf("AddrRep", null), "Int64Rep"),
    IS_REG("ghczuwrapperZC8ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSzuISREG", "capi", "unsafe", listOf("Word32Rep", null), "Int32Rep"),
    IS_CHR("ghczuwrapperZC7ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSzuISCHR", "capi", "unsafe", listOf("Word32Rep", null), "Int32Rep"),
    IS_BLK("ghczuwrapperZC6ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSzuISBLK", "capi", "unsafe", listOf("Word32Rep", null), "Int32Rep"),
    IS_DIR("ghczuwrapperZC5ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSzuISDIR", "capi", "unsafe", listOf("Word32Rep", null), "Int32Rep"),
    IS_FIFO("ghczuwrapperZC4ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSzuISFIFO", "capi", "unsafe", listOf("Word32Rep", null), "Int32Rep"),
    IS_SOCK("ghczuwrapperZC3ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSzuISSOCK", "capi", "unsafe", listOf("Word32Rep", null), "Int32Rep"),
    ISATTY("isatty", "ccall", "unsafe", listOf("Int32Rep", null), "Int32Rep"),
    READY_SAFE("fdReady", "ccall", "safe", listOf("Int32Rep", "Word8Rep", "Int64Rep", "Word8Rep", null), "Int32Rep"),
    READY_UNSAFE("fdReady", "ccall", "unsafe", listOf("Int32Rep", "Word8Rep", "Int64Rep", "Word8Rep", null), "Int32Rep"),
    LOCALE("localeEncoding", "ccall", "unsafe", listOf(null), "AddrRep"),
    ICONV_OPEN("hs_iconv_open", "ccall", "unsafe", listOf("AddrRep", "AddrRep", null), "Int64Rep"),
    ICONV_CLOSE("hs_iconv_close", "ccall", "unsafe", listOf("Int64Rep", null), "Int32Rep"),
    ICONV("hs_iconv", "ccall", "unsafe", listOf("Int64Rep", "AddrRep", "AddrRep", "AddrRep", "AddrRep", null), "Word64Rep"),
    STRERROR("base_strerror_r", "ccall", "safe", listOf("Int32Rep", "AddrRep", "Word64Rep", null), "Int32Rep");

    // Only these reviewed declarations accept an installed identity of this release.
    fun acceptsUnit(value: Any?): Boolean = value == unit ||
        this == LAST_ERROR && (value == "ghc-internal" || value is String && win32Unit.matches(value)) ||
        windowsDirectory && value is String && win32Unit.matches(value) ||
        this == READLINK && value == "ghc-internal" ||
        (this == UNLINKAT || this == FSTATAT) && value is String && directoryUnit.matches(value) ||
        isOriginalUnixUnit(value) && !windowsDirectory && (this == CLOSE || this == DUP || this == ISATTY ||
            this == UNIX_LSTAT || currentDirectory || directoryStream || waitStatus || this == MKDIR || this == RMDIR || this == SYMLINK || this == READLINK || this == GET_EUID)

    fun matchesSymbol(value: Any?): Boolean = value == symbol ||
        this == FSTATAT && value is String && value.replace(directoryWrapperUnit, "directoryzm1zi3zi10zi0zminplaceZC") == symbol ||
        (this == UNIX_LSTAT || waitStatus || this == OPENDIR || this == FDOPENDIR) && value is String &&
            value.replace(unixWrapperUnit, "unixzm2zi8zi8zi0zminplaceZC") == symbol

    val processIdentity: Boolean get() = this == GET_PID || this == GET_EUID
    val readiness: Boolean get() = this == READY_SAFE || this == READY_UNSAFE
    val waitStatus: Boolean get() = this == WCOREDUMP || this == WSTOPSIG || this == WIFSTOPPED ||
        this == WTERMSIG || this == WIFSIGNALED || this == WEXITSTATUS || this == WIFEXITED
    val duplication: Boolean get() = this == DUP || this == DUP2
    val locking: Boolean get() = this == LOCK || this == UNLOCK
    // Keep the operation a PE constant: enum `when` reads Kotlin's mutable
    // synthetic switch table even when this enum receiver is constant.
    val flagConstant: Boolean get() = this == O_APPEND || this == O_CREAT || this == O_EXCL || this == O_BINARY || this == O_TRUNC || this == O_NOCTTY ||
        this == O_NONBLOCK || this == O_RDONLY || this == O_RDWR || this == O_WRONLY ||
        this == F_GETFL || this == F_SETFL || this == F_SETFD || this == FD_CLOEXEC
    val fcntl: Boolean get() = this == FCNTL_READ || this == FCNTL_WRITE
    val eventPair: Boolean get() = this == EVENTFD || this == EVENTFD_WRITE
    val poll: Boolean get() = this == POLL_SAFE || this == POLL_UNSAFE
    val epollWait: Boolean get() = this == EPOLL_WAIT_SAFE || this == EPOLL_WAIT_UNSAFE
    val controlFd: Boolean get() = this == IO_WAKEUP_FD || this == IO_CONTROL_FD || this == TIMER_CONTROL_FD
    val eventManager: Boolean get() = poll || epollWait || controlFd || this == EPOLL_CREATE || this == EPOLL_CTL
    val eventDescriptor: Boolean get() = eventPair || this == PIPE || eventManager
    val seekConstant: Boolean get() = this == SEEK_SET || this == SEEK_CUR || this == SEEK_END
    val stat: Boolean get() = this == SIZEOF_STAT || statField ||
        this == IS_REG || this == IS_CHR || this == IS_BLK || this == IS_DIR || this == IS_FIFO || this == IS_SOCK
    val statField: Boolean get() = this == ST_DEV || this == ST_INO || this == ST_MODE || this == ST_SIZE
    val currentDirectory: Boolean get() = this == CHDIR || this == GETCWD
    val windowsDirectory: Boolean get() = this == FIND_FIRST || this == FIND_NEXT || this == FIND_CLOSE
    val windowsEncoding: Boolean get() = this == LAST_ERROR || this == ANSI_CODE_PAGE || this == CONSOLE_CODE_PAGE ||
        this == CODE_PAGE_INFO || this == DBCS_LEAD_BYTE || this == MULTI_BYTE_TO_WIDE || this == WIDE_TO_MULTI_BYTE ||
        this == MAP_ERRNO || this == MAP_ERRNO_VALUE || this == WINDOWS_ERROR_MESSAGE || this == LOCAL_FREE
    val directoryStream: Boolean get() = windowsDirectory || this == OPENDIR || this == FDOPENDIR || this == CLOSEDIR ||
        this == READDIR || this == DIRENT_NAME || this == FREE_DIRENT
    val directoryPointer: Boolean get() = this == OPENDIR || this == DIRENT_NAME
    val pathRemoval: Boolean get() = this == UNLINK || this == RMDIR
    val pathLink: Boolean get() = this == SYMLINK || this == READLINK
    val pathMode: Boolean get() = this == MKDIR || this == CHMOD
    val pathStat: Boolean get() = this == STAT || this == LSTAT || this == UNIX_LSTAT
    val readImage: Boolean get() = this == FSTAT || this == TCGETATTR
    val iconv: Boolean get() = this == LOCALE || this == ICONV_OPEN || this == ICONV_CLOSE || this == ICONV
    val strerror: Boolean get() = this == STRERROR
    val termios: Boolean get() = this == LFLAG || this == POKE_LFLAG || this == PTR_C_CC ||
        this == SIZEOF_TERMIOS || this == ECHO || this == ICANON || this == VMIN || this == VTIME || this == TCSANOW ||
        this == SIZEOF_SIGSET || this == SIGTTOU || this == SIG_BLOCK || this == SIG_SETMASK
    val termiosAddress: Boolean get() = this == LFLAG || this == POKE_LFLAG || this == PTR_C_CC
    val sigset: Boolean get() = this == SIGEMPTYSET || this == SIGADDSET
    val savedTermios: Boolean get() = this == GET_SAVED_TERMIOS || this == SET_SAVED_TERMIOS
    val opening: Boolean get() = this == OPEN || this == OPEN_SAFE || this == OPEN_INTERRUPTIBLE
}

internal object CoreOriginalStdio {
    @JvmStatic @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    fun waitStatus(node: Node?, operation: OriginalStdioOp, status: Long): Long {
        requireProof(operation.waitStatus, "wait-status operation")
        val state = Language.currentState(node)
        val previous = state.threads.enterForeign()
        try { return state.cbits().waitStatus(operation, status.toInt()) }
        finally { state.threads.leaveForeign(previous) }
    }
    @JvmStatic fun current(node: Node): ManagedStdio = Language.currentState(node).stdio
    @JvmStatic fun directories(node: Node): NativeDirectoryStreams = NativeFileProvider.current().directoryStreams
    @JvmStatic fun locks(node: Node): RtsFileLocks = Language.currentState(node).rtsFileLocks
    @JvmStatic fun iconv(node: Node): ManagedIconv = Language.currentState(node).iconv
    @JvmStatic fun strerror(node: Node): ManagedStrerror = Language.currentState(node).strerror

    private val scalarKeys = setOf("kind", "primReps", "evaluated")
    private val tupleKeys = scalarKeys + setOf("aggregate", "components")
    private val descriptorKeys = setOf("schema", "target", "convention", "safety", "arity", "suppliedArity", "argumentReps", "resultRep")

    private fun requireProof(condition: Boolean, detail: String) {
        if (!condition) throw RuntimeFault("Invalid original stdio call: $detail")
    }
    private fun exactInteger(value: Any?, expected: Int): Boolean =
        (value is Int || value is Long) && (value as Number).toLong() == expected.toLong()

    /** An occurrence certificate cannot relabel a stored foreign operand. */
    fun validateScalarOperand(operation: OriginalStdioOp, index: Int,
        lowered: CoreRepresentation, stored: CoreRepresentation?) {
        requireProof(operation.processIdentity || operation == OriginalStdioOp.SET_ERRNO || operation.eventDescriptor || operation.waitStatus || operation.pathRemoval || operation.flagConstant || operation.fcntl || operation == OriginalStdioOp.SIGPROCMASK || operation.readiness || operation.seekConstant || operation.stat || operation.termios || operation.sigset || operation.savedTermios || operation.readImage || operation.pathStat || operation.pathMode || operation == OriginalStdioOp.ACCESS || operation == OriginalStdioOp.UNLINKAT || operation == OriginalStdioOp.FSTATAT || operation.pathLink || operation.currentDirectory || operation.directoryStream || operation == OriginalStdioOp.TCSETATTR || operation.opening || operation.iconv || operation.strerror || operation.duplication || operation.locking,
            "strict operand operation")
        val primitive = operation.arguments[index]
        val kind = when (primitive) { null -> CoreKind.VOID; "AddrRep" -> CoreKind.ADDRESS; else -> CoreKind.LONG }
        val reps = listOfNotNull(primitive)
        requireProof(lowered.present && !lowered.isAggregate && !lowered.isVector &&
            lowered.kind == kind && lowered.primReps == reps, "lowered foreign operand $index")
        if (stored != null && stored.present)
            requireProof(!stored.isAggregate && !stored.isVector && stored.kind in setOf(kind, CoreKind.UNKNOWN) &&
                (stored.primReps == null || stored.primReps == reps), "stored foreign operand $index")
    }

    fun validateHead(function: List<Any?>, defined: Boolean) {
        val proof = CoreRepresentations.metadata(function)?.get("rep") as? Map<*, *>
        requireProof(function.size == 3 && function[0] == "var" && function[1] is String &&
            (function[1] as String).isNotEmpty() && !defined && proof?.keys == scalarKeys &&
            proof["kind"] == "closure" && proof["primReps"] == listOf("BoxedRep (Just Lifted)") &&
            proof["evaluated"] == true, "unresolved declared foreign variable required")
    }

    /** Reject malformed heads before generic call/capture analysis casts their IDs. */
    fun validateHeads(value: Any?) {
        when (value) {
            is Map<*, *> -> value.values.forEach(::validateHeads)
            is List<*> -> {
                if (value.firstOrNull() == "app") {
                    val meta = value.getOrNull(6) as? Map<*, *>
                    val descriptor = meta?.get("foreignCall") as? Map<*, *>
                    val target = descriptor?.get("target") as? Map<*, *>
                    if (OriginalStdioOp.entries.any { it.matchesSymbol(target?.get("symbol")) })
                        validateHead(value.getOrNull(1) as? List<Any?>
                            ?: throw RuntimeFault("Invalid original stdio call: missing variable head"), false)
                }
                value.forEach(::validateHeads)
            }
        }
    }

    private fun scalar(raw: Any?, primitive: String?, declared: Boolean = false): Boolean {
        val value = raw as? Map<*, *> ?: return false
        val kind = when (primitive) { null -> "void"; "AddrRep" -> "address"; else -> "long" }
        return value.keys == scalarKeys && value["kind"] == kind &&
            value["primReps"] == (primitive?.let { listOf(it) } ?: emptyList<String>()) &&
            value["evaluated"] is Boolean && (!declared || value["evaluated"] == false)
    }

    private fun result(raw: Any?, primitive: String?, declared: Boolean = false): Boolean {
        val value = raw as? Map<*, *> ?: return false
        val components = value["components"] as? List<*> ?: return false
        val expected = if (primitive == null) listOf(null) else listOf(null, primitive)
        return value.keys == tupleKeys && value["kind"] == "unknown" && value["aggregate"] == "unboxed-tuple" &&
            value["primReps"] == listOfNotNull(primitive) && value["evaluated"] is Boolean &&
            (!declared || value["evaluated"] == false) && components.size == expected.size &&
            expected.indices.all { scalar(components[it], expected[it]) && (components[it] as Map<*, *>)["evaluated"] == true }
    }

    /** Caller binding names are irrelevant; raw FCallId proof must match exactly.
     * Unrecognized symbols retain ordinary unsupported-foreign handling. */
    fun validate(metadata: Any?, argumentReps: List<*>, flags: List<*>, resultRep: Any?): OriginalStdioOp? {
        val meta = metadata as? Map<*, *> ?: return null
        val descriptor = meta["foreignCall"] as? Map<*, *> ?: return null
        val target = descriptor["target"] as? Map<*, *> ?: return null
        val symbol = target["symbol"] as? String ?: return null
        val candidates = OriginalStdioOp.entries.filter { it.matchesSymbol(symbol) }
        if (candidates.isEmpty()) return null
        val operation = candidates.firstOrNull { it.convention == descriptor["convention"] && it.safety == descriptor["safety"] }
            ?: throw RuntimeFault("Invalid original stdio call: calling convention/safety")
        requireProof(descriptor.keys == descriptorKeys && exactInteger(descriptor["schema"], 1), "descriptor schema")
        requireProof(target.keys == setOf("kind", "symbol", "unit", "isFunction") &&
            target["kind"] == "static" && operation.acceptsUnit(target["unit"]) && target["isFunction"] == true,
            "static original installed-library function target")
        // The strict unit grammar needs only these two z-encoding substitutions.
        requireProof(!(operation == OriginalStdioOp.UNIX_LSTAT || operation.waitStatus || operation == OriginalStdioOp.OPENDIR || operation == OriginalStdioOp.FDOPENDIR) || symbol == operation.symbol.replace(
            "unixzm2zi8zi8zi0zminplace", (target["unit"] as String).replace("-", "zm").replace(".", "zi")),
            "Unix wrapper owner")
        requireProof(operation != OriginalStdioOp.FSTATAT || symbol == operation.symbol.replace(
            "directoryzm1zi3zi10zi0zminplace", (target["unit"] as String).replace("-", "zm").replace(".", "zi")),
            "Directory wrapper owner")
        requireProof(descriptor["convention"] == operation.convention && descriptor["safety"] == operation.safety,
            "calling convention/safety")
        val expected = operation.arguments
        requireProof(exactInteger(descriptor["arity"], expected.size) &&
            exactInteger(descriptor["suppliedArity"], expected.size), "saturated arity")
        val declared = descriptor["argumentReps"] as? List<*>
        requireProof(declared != null && declared.size == expected.size &&
            expected.indices.all { scalar(declared[it], expected[it], true) }, "declared argument representations")
        requireProof(argumentReps.size == expected.size && expected.indices.all { scalar(argumentReps[it], expected[it]) },
            "actual argument representations")
        requireProof(flags.size == expected.size && flags.all { it is Boolean && !it }, "unlifted argument flags")
        requireProof(result(descriptor["resultRep"], operation.result, true) && result(meta["rep"], operation.result) &&
            result(resultRep, operation.result), "exact State/result tuple")
        return operation
    }
}
