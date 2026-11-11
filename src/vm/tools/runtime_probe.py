#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0
"""Observe the DLLs loaded by a child while its test fixture waits at exit."""

import ctypes
from ctypes import wintypes
import os
import subprocess
import threading
import time


def loaded_modules(pid):
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel.K32EnumProcessModules.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.HMODULE),
                                           wintypes.DWORD, ctypes.POINTER(wintypes.DWORD)]
    kernel.K32GetModuleFileNameExW.argtypes = [wintypes.HANDLE, wintypes.HMODULE,
                                            wintypes.LPWSTR, wintypes.DWORD]
    kernel.K32GetModuleFileNameExW.restype = wintypes.DWORD
    process = kernel.OpenProcess(0x0400 | 0x0010, False, pid)
    if not process:
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        count = 256
        while True:
            modules = (wintypes.HMODULE * count)()
            needed = wintypes.DWORD()
            if not kernel.K32EnumProcessModules(process, modules, ctypes.sizeof(modules), ctypes.byref(needed)):
                raise ctypes.WinError(ctypes.get_last_error())
            count = needed.value // ctypes.sizeof(wintypes.HMODULE)
            if needed.value <= ctypes.sizeof(modules):
                break
        paths = []
        for module in modules[:count]:
            path = ctypes.create_unicode_buffer(32768)
            length = kernel.K32GetModuleFileNameExW(process, module, path, len(path))
            if not length:
                raise ctypes.WinError(ctypes.get_last_error())
            if length >= len(path):
                raise RuntimeError('Loaded module path was truncated')
            paths.append(path.value)
        return paths
    finally:
        kernel.CloseHandle(process)


def run(command, *, cwd, env, timeout):
    if os.name != 'nt':
        return subprocess.run(command, cwd=cwd, env=env, timeout=timeout, text=True, capture_output=True)
    deadline = time.monotonic() + timeout
    with subprocess.Popen(command, cwd=cwd, env=env, text=True, stdin=subprocess.PIPE,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE) as process:
        process.stdin.reconfigure(newline='\n')
        ready = threading.Event()
        stdout, stderr = [], []

        def read(stream, output):
            try:
                for line in stream:
                    output.append(line)
                    if line.strip() == 'thc-runtime-audit-ready':
                        ready.set()
            finally:
                # An early exit should report its output without waiting for timeout.
                ready.set()

        readers = [threading.Thread(target=read, args=(stream, output))
                   for stream, output in ((process.stdout, stdout), (process.stderr, stderr))]
        for reader in readers:
            reader.start()
        try:
            if not ready.wait(max(0, deadline - time.monotonic())):
                raise subprocess.TimeoutExpired(command, timeout)
            if any(line.strip() == 'thc-runtime-audit-ready' for line in stdout):
                paths = loaded_modules(process.pid)
                process.stdin.write('\n')
                process.stdin.flush()
                stdout.extend('thc-loaded-library: ' + path + '\n' for path in paths)
            process.wait(timeout=max(0, deadline - time.monotonic()))
        except BaseException:
            process.kill()
            process.wait()
            raise
        finally:
            for reader in readers:
                reader.join()
        return subprocess.CompletedProcess(command, process.returncode, ''.join(stdout), ''.join(stderr))
