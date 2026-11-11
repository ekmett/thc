# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

# Run in a Visual Studio developer shell with Clang 23 and Ninja on PATH.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$nativeBuild = if ($env:THC_VM_NATIVE_BUILD) { $env:THC_VM_NATIVE_BUILD } else { "$root/build-jam" }
$cmake = if ($env:JAM_CMAKE) { $env:JAM_CMAKE } else { 'cmake' }
$compiler = if ($env:JAM_CXX) { $env:JAM_CXX } else { 'clang-cl' }
$ninja = if ($env:JAM_NINJA) { $env:JAM_NINJA } else { (Get-Command ninja).Source }
$jobs = if ($env:JAM_JOBS) { $env:JAM_JOBS } else { '8' }
$arguments = @('-S', $root, '-DTHC_VM_BUILD_TESTS=ON', '-B', $nativeBuild, '-G', 'Ninja',
    "-DCMAKE_MAKE_PROGRAM=$ninja", "-DCMAKE_CXX_COMPILER=$compiler",
    "-DCMAKE_C_COMPILER=$compiler", '-DCMAKE_LINKER_TYPE=LLD', '-DCMAKE_BUILD_TYPE=Release')
& $cmake @arguments
if ($LASTEXITCODE) { throw 'Native collector configuration failed' }
& $cmake --build $nativeBuild --parallel $jobs
if ($LASTEXITCODE) { throw 'Native collector build failed' }
$ctest = Join-Path (Split-Path (Get-Command $cmake).Source -Parent) 'ctest.exe'
& $ctest --test-dir $nativeBuild --output-on-failure
if ($LASTEXITCODE) { throw 'Native collector tests failed' }
