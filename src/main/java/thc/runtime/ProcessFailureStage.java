// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Checked native transport stages, in the native bridge's declared order. */
public enum ProcessFailureStage {
    NONE(""), ARGUMENTS("process arguments"), SIGCHLD("SIGCHLD policy"), PIDFD_OPEN("pidfd_open"),
    PIDFD_WAIT("waitid(P_PIDFD)"), ACTION_INIT("posix_spawn_file_actions_init"),
    ATTR_INIT("posix_spawnattr_init"), FCHDIR("posix_spawn_file_actions_addfchdir_np"),
    CHDIR("posix_spawn_file_actions_addchdir_np"), PIPE("pipe"), DUP_FD("fcntl(F_DUP_FD)"),
    DUP2("posix_spawn_file_actions_adddup2"), DUP2_PIPE("posix_spawn_file_actions_adddup2(child_end)"),
    CLOSE("posix_spawn_file_actions_addclose"), CLOSE_FROM("posix_spawn_file_actions_addclosefrom_np"),
    SIGMASK("posix_spawnattr_setsigmask"), PGROUP("posix_spawnattr_setpgroup"),
    SIGDEFAULT("posix_spawnattr_setsigdefault"), FLAGS("posix_spawnattr_setflags"), SPAWN("posix_spawnp");

    private final String operation;
    ProcessFailureStage(String operation) { this.operation = operation; }
    public String getOperation() { return operation; }
}
