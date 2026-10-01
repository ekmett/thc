# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
param(
    [ValidateSet('Build', 'Runtime', 'Haskell', 'Fixtures', 'Test', 'NativeLinkTest', 'ArrayTest', 'DirectoryTest', 'CodePageTest', 'WindowsServicesTest', 'LibdwTest', 'MallocTest', 'CheckCore')]
    [string]$Action = 'Build',
    [ValidateRange(1, 32)][int]$Jobs = 4
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
. "$PSScriptRoot/windows-common.ps1"
if ($env:OS -ne 'Windows_NT') { throw 'Use the native Windows host for this script' }
if (!$env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $root '.gradle-user-home' }
Push-Location $root
New-Item -ItemType Directory -Force "$root/build" | Out-Null
$lease = [IO.File]::Open("$root/build/.native-windows-build.lock",
    [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
try {
    if ($Action -in @('Build', 'Runtime', 'Test', 'DirectoryTest', 'CodePageTest', 'LibdwTest')) {
        Assert-ThcJava
        Invoke-ThcTool "$root/gradlew.bat" @('--no-daemon', "--max-workers=$Jobs", 'installDist', 'toolsJar')
    }
    if ($Action -in @('Build', 'Haskell', 'Fixtures', 'Test', 'NativeLinkTest', 'ArrayTest', 'DirectoryTest', 'CodePageTest', 'WindowsServicesTest', 'LibdwTest', 'MallocTest', 'CheckCore')) {
        $tools = Get-ThcGhc
        $env:GHC = $tools.Compiler
        $env:GHC_PKG = $tools.PackageTool
        $cabal = if ($env:CABAL) { $env:CABAL } else { 'cabal' }
        $flags = @('--disable-shared', "-j$Jobs", '-fdevelopment',
            "--with-compiler=$($tools.Compiler)", "--with-hc-pkg=$($tools.PackageTool)")
        if ($Action -eq 'CheckCore') {
            Invoke-ThcTool (Join-Path (Split-Path $tools.Compiler) 'runghc.exe') @('-f', $tools.Compiler,
                '--ghc-arg=-package', '--ghc-arg=ghc', '--ghc-arg=-package', '--ghc-arg=Cabal',
                'bin/check-ghc-core.hs', 'check', 'ghc-internal', 'base')
        } else {
            Invoke-ThcTool $cabal (@('build', 'all') + $flags)
        }
    }
    if ($Action -in @('Fixtures', 'Test')) {
        $fixture = Invoke-ThcTool $cabal (@('list-bin', 'exe:thc-fixtures') + $flags)
        Invoke-ThcTool $fixture @('windows-smoke')
        $python = if ($env:THC_PYTHON) { $env:THC_PYTHON } else { 'python' }
        $clang = if ($env:THC_CLANG) { $env:THC_CLANG } else { 'clang' }
        Invoke-ThcTool $python @('bin/prepare-managed-md5.py', '--cc', $clang)
    }
    if ($Action -in @('Test', 'NativeLinkTest')) {
        if (!$env:THC_TEST_ROOT) { $env:THC_TEST_ROOT = $root }
        if (!$env:THC_TEST_SCRATCH) { $env:THC_TEST_SCRATCH = Join-Path $root 'build/windows-native-link-tests' }
        New-Item -ItemType Directory -Force $env:THC_TEST_SCRATCH | Out-Null
        Invoke-ThcTool $cabal (@('test', 'driver-tests', '--test-options=--package-native-only',
            '--test-show-details=direct') + $flags)
    }
    if ($Action -eq 'Test') {
        Invoke-ThcTool $cabal (@('test', 'driver-lock-tests', '--test-show-details=direct') + $flags)
        Invoke-ThcTool $fixture @('windows-driver')
        Invoke-ThcTool "$root/gradlew.bat" @('--no-daemon', "--max-workers=$Jobs",
            'windowsSmokeTest', 'windowsDenseSmokeTest', '--rerun')
    }
    $focusedTests = @()
    if ($Action -in @('Test', 'ArrayTest')) {
        Assert-ThcJava
        $fixture = Invoke-ThcTool $cabal (@('list-bin', 'exe:thc-fixtures') + $flags)
        foreach ($group in @('int-arrays', 'int8-arrays', 'int16-arrays', 'int32-arrays', 'double-arrays', 'float-word-arrays')) {
            Invoke-ThcTool $fixture @($group)
        }
        $focusedTests += @('thc.runtime.IntArrayNativeTest', 'thc.runtime.Int8ArrayNativeTest',
            'thc.runtime.Int16ArrayNativeTest', 'thc.runtime.Int32ArrayNativeTest',
            'thc.runtime.DoubleArrayNativeTest', 'thc.runtime.FloatWordArrayNativeTest')
    }
    if ($Action -in @('Test', 'MallocTest')) {
        Assert-ThcJava
        $fixture = Invoke-ThcTool $cabal (@('list-bin', 'exe:thc-fixtures') + $flags)
        Invoke-ThcTool $fixture @('native-addresses')
        $focusedTests += @('thc.runtime.NativeMallocTest', 'thc.runtime.WindowsStdioHostAbiTest',
            'thc.runtime.PosixStdioHostAbiModelTest', 'thc.runtime.StdioHostAbiFailureTest',
            'thc.runtime.ReturnedForeignPointerTest', 'thc.runtime.ReturnedPointerCompilationTest',
            'thc.runtime.NarrowReturnedPointerTest', 'thc.runtime.ForeignExceptionPolicyTest',
            'thc.runtime.WindowsSulongLibraryLookupTest',
            'thc.runtime.PinnedPointerCellsTest.nonOverlappingAddressCopyPreservesPointerCellsAndRejectsInvalidRegions',
            'thc.runtime.ScalarMemoryUtilitiesTest.addressRangesAndPointerCellsAreCheckedBeforeEffects')
    }
    if ($Action -eq 'WindowsServicesTest') {
        Assert-ThcJava
        $focusedTests += @('thc.runtime.PosixStdioHostAbiModelTest', 'thc.runtime.StdioHostAbiFailureTest',
            'thc.runtime.WindowsStdioHostAbiTest')
    }
    if ($Action -in @('Test', 'DirectoryTest', 'CodePageTest', 'WindowsServicesTest')) {
        $focusedTests += 'thc.runtime.WindowsAbiInitializationTest'
    }
    if ($Action -in @('Test', 'DirectoryTest', 'WindowsServicesTest')) {
        $fixture = Invoke-ThcTool $cabal (@('list-bin', 'exe:thc-fixtures') + $flags)
        Invoke-ThcTool $fixture @('windows-directory')
        $focusedTests += 'thc.runtime.WindowsDirectoryStreamsTest'
    }
    if ($Action -in @('Test', 'CodePageTest', 'WindowsServicesTest')) {
        $fixture = Invoke-ThcTool $cabal (@('list-bin', 'exe:thc-fixtures') + $flags)
        Invoke-ThcTool $fixture @('windows-codepages')
        $focusedTests += 'thc.runtime.WindowsCodePagesTest'
    }
    if ($Action -in @('Test', 'LibdwTest')) {
        $fixture = Invoke-ThcTool $cabal (@('list-bin', 'exe:thc-fixtures') + $flags)
        Invoke-ThcTool $fixture @('libdw-unavailable')
        $focusedTests += 'thc.runtime.LibdwUnavailableTest'
    }
    if ($focusedTests.Count) {
        # One shared compilation and one pair of reports retain every selected
        # suite; separate invocations of a test task overwrite earlier XML.
        $gradleArgs = @('--no-daemon', "--max-workers=$Jobs", '--continue', 'installDist')
        foreach ($mode in @('testDefault', 'testDense')) {
            $gradleArgs += $mode
            foreach ($test in $focusedTests) { $gradleArgs += @('--tests', $test) }
        }
        Invoke-ThcTool "$root/gradlew.bat" $gradleArgs
    }
} finally { $lease.Dispose(); Pop-Location }
