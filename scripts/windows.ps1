# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
param(
    [ValidateSet('Build', 'Runtime', 'Haskell', 'Fixtures', 'Test', 'CheckCore')]
    [string]$Action = 'Build',
    [ValidateRange(1, 32)][int]$Jobs = 4
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
. "$PSScriptRoot/windows-common.ps1"
if ($env:OS -ne 'Windows_NT') { throw 'Use the native Windows host for this script' }
if (!$env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $root '.gradle-user-home' }
Push-Location $root
try {
    if ($Action -in @('Build', 'Runtime', 'Test')) {
        Assert-ThcJava
        Invoke-ThcTool "$root/gradlew.bat" @('--no-daemon', "--max-workers=$Jobs", 'installDist', 'toolsJar')
    }
    if ($Action -in @('Build', 'Haskell', 'Fixtures', 'Test', 'CheckCore')) {
        $tools = Get-ThcGhc
        $env:GHC = $tools.Compiler
        $env:GHC_PKG = $tools.PackageTool
        $cabal = if ($env:CABAL) { $env:CABAL } else { 'cabal' }
        $flags = @('--disable-shared', "-j$Jobs", '-fdevelopment',
            "--with-compiler=$($tools.Compiler)", "--with-hc-pkg=$($tools.PackageTool)")
        if ($Action -eq 'CheckCore') {
            Invoke-ThcTool (Join-Path (Split-Path $tools.Compiler) 'runghc.exe') @('-f', $tools.Compiler,
                '--ghc-arg=-package', '--ghc-arg=ghc', '--ghc-arg=-package', '--ghc-arg=Cabal',
                'scripts/check-ghc-core.hs', 'check', 'ghc-internal', 'base')
        } else {
            Invoke-ThcTool $cabal (@('build', 'all') + $flags)
        }
    }
    if ($Action -in @('Fixtures', 'Test')) {
        $fixture = Invoke-ThcTool $cabal (@('list-bin', 'exe:thc-fixtures') + $flags)
        Invoke-ThcTool $fixture @('windows-smoke')
        $python = if ($env:THC_PYTHON) { $env:THC_PYTHON } else { 'python' }
        $clang = if ($env:THC_CLANG) { $env:THC_CLANG } else { 'clang' }
        Invoke-ThcTool $python @('scripts/prepare-managed-md5.py', '--cc', $clang)
    }
    if ($Action -eq 'Test') {
        Invoke-ThcTool $cabal (@('test', 'driver-lock-tests', '--test-show-details=direct') + $flags)
        Invoke-ThcTool $fixture @('windows-driver')
        Invoke-ThcTool "$root/gradlew.bat" @('--no-daemon', "--max-workers=$Jobs",
            'windowsSmokeTest', 'windowsDenseSmokeTest', '--rerun')
    }
} finally { Pop-Location }
