# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Configure)
$ErrorActionPreference = 'Stop'
if (!$env:JAM_BASH) { throw 'Set JAM_BASH to the Cygwin bash.exe' }
if (!$env:JAM_PYTHON) { $env:JAM_PYTHON = (Get-Command python).Source }
$flags = @('--with-toolchain-version=2022', '--with-native-debug-symbols=none')
$cc, $cxx, $path = $env:CC, $env:CXX, $env:Path
try {
    # HotSpot uses MSVC; the C++26 collector was compiled separately with LLVM.
    $env:CC = $null
    $env:CXX = $null
    $env:Path = "$(Split-Path $env:JAM_BASH -Parent);$path"
    $script = & $env:JAM_BASH --noprofile --norc -c 'cygpath -u "$1"' -- "$PSScriptRoot/build_hotspot.sh"
    if ($LASTEXITCODE) { throw 'Could not resolve the Cygwin build path' }
    & $env:JAM_BASH --noprofile --norc $script @flags @Configure
    if ($LASTEXITCODE) { throw "HotSpot build failed: $LASTEXITCODE" }
} finally {
    $env:CC, $env:CXX, $env:Path = $cc, $cxx, $path
}
