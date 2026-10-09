# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
#requires -Version 7.0
param([Parameter(Mandatory)][string]$Output)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { throw 'Requires native Windows' }
$root = Split-Path $PSScriptRoot -Parent
$Output = [IO.Path]::GetFullPath($Output)
New-Item -ItemType Directory -Force -Path $Output | Out-Null
$compiler = if ($env:GHC) { $env:GHC } else { 'ghc' }
$packageTool = if ($env:GHC_PKG) { $env:GHC_PKG } else { 'ghc-pkg' }
$clang = if ($env:THC_CLANG) { $env:THC_CLANG } else { 'clang' }
$receipt = [ordered]@{ ghc='9.14.1'; started=[DateTime]::UtcNow.ToString('o'); commands=@() }
function Invoke-Producer([string]$Stage, [string]$Tool, [string[]]$Arguments) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    & $Tool @Arguments 1> "$Output/$Stage.stdout.log" 2> "$Output/$Stage.stderr.log"
    $code = $LASTEXITCODE
    $watch.Stop()
    $receipt.commands += [ordered]@{ stage=$Stage; command=@($Tool)+$Arguments; exit=$code; seconds=$watch.Elapsed.TotalSeconds }
    if ($code -ne 0) { throw "Windows native IO producer $Stage failed with exit $code; see $Output/$Stage.stderr.log" }
}
try {
    Invoke-Producer 'version' $compiler @('--numeric-version')
    if ((Get-Content "$Output/version.stdout.log" -Raw).Trim() -ne '9.14.1') { throw 'Requires pinned GHC9.14.1' }
    Invoke-Producer 'compiler-info' $compiler @('--info')
    if ((Get-Content "$Output/compiler-info.stdout.log" -Raw) -notmatch 'x86_64.*mingw32') { throw 'Requires pinned native Win64 GHC' }
    Invoke-Producer 'native-package' $packageTool @('--global','describe','ghc-internal')
    Invoke-Producer 'library-dir' $packageTool @('--global','field','ghc-internal','library-dirs','--simple-output')
    Invoke-Producer 'library-name' $packageTool @('--global','field','ghc-internal','hs-libraries','--simple-output')
    $libraryDir = (Get-Content "$Output/library-dir.stdout.log" -Raw).Trim().Trim('"')
    $libraryName = (Get-Content "$Output/library-name.stdout.log" -Raw).Trim()
    if ($libraryName -notmatch '^HSghc-internal-9\.1401\.0-[A-Za-z0-9]+$') { throw 'Requires one actual pinned ghc-internal archive' }
    $archive = Join-Path $libraryDir "lib$libraryName.a"
    Invoke-Producer 'archive-libraries' $packageTool @('--global','field','ghc-internal','extra-libraries','--simple-output')
    $nativeLibraries = ((Get-Content "$Output/archive-libraries.stdout.log" -Raw).Trim() -split '\s+') | ForEach-Object { "-l$_" }
    $receipt.nativeArchive = @{ path=$archive; sha256=(Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() }
    $receipt.compiler = @{ path=(Get-Command $compiler).Source; sha256=(Get-FileHash -LiteralPath (Get-Command $compiler).Source).Hash.ToLowerInvariant() }
    # GHC supplies its registered configured headers, native archives and RTS.
    # This links existing package code; it never rebuilds the package/bootstrap.
    Invoke-Producer 'original-wrapper' $compiler @('-c','-O2','-Wall','-Werror','-package','ghc-internal',
        "$root/t/fixtures/compiler/WindowsNativeOpen.c",'-o',"$Output/original-open.o")
    Invoke-Producer 'sdk-fixture' $clang @('-std=c11','-Wall','-Wextra','-Werror','-O2','-fno-builtin','-c',
        "$root/t/fixtures/compiler/WindowsNativeIo.c",'-o',"$Output/sdk-fixture.o")
    Invoke-Producer 'link' $compiler @('-shared','-no-hs-main','-hide-all-packages','-no-user-package-db',
        '-package','rts',"$Output/original-open.o","$Output/sdk-fixture.o","-optl$archive",'-lws2_32',
        '-o',"$Output/windows-io-fixture.dll")
    # An ordinary opening component has no SDK transfer roots. Do not inject
    # imports just to satisfy the THC boundary's unrelated operations.
    Invoke-Producer 'opening-only-link' $clang (@('-shared',"$Output/original-open.o",$archive) + $nativeLibraries +
        @('-o',"$Output/windows-opening-fixture.dll"))
    $readobj = Join-Path (Split-Path (Get-Command $clang).Source) 'llvm-readobj.exe'
    Invoke-Producer 'opening-only-imports' $readobj @('--coff-imports',"$Output/windows-opening-fixture.dll")
    $imports = Get-Content "$Output/opening-only-imports.stdout.log" -Raw
    if ($imports -notmatch 'Symbol: _errno ' -or $imports -match 'Symbol: (_read|_write|recv|send|WSAGetLastError|closesocket) ') {
        throw 'Opening-only component must retain its actual errno origin without transfer imports'
    }
    Invoke-Producer 'sulong-transport' $clang @('--target=x86_64-pc-windows-msvc19.33.0','-std=c11','-Wall','-Wextra','-Werror','-O2','-emit-llvm','-c',
        "$root/t/fixtures/compiler/WindowsNativeOpenTransport.c",'-o',"$Output/windows-open.bc")
    Invoke-Producer 'ghc-oracle-build' $compiler @('--make','-O2','-Wall','-Werror','-dcore-lint','-dstg-lint',
        "$root/t/fixtures/compiler/WindowsNativeIoOracle.hs",'-odir',$Output,'-hidir',$Output,'-o',"$Output/windows-io-oracle.exe")
    Invoke-Producer 'ghc-oracle-run' "$Output/windows-io-oracle.exe" @(
        "$root/build/generated/cbits/thc/cbits/windows-io.dll","$Output/windows-opening-fixture.dll","$Output/oracle-input.bin")
    $receipt.artifacts = @('windows-io-fixture.dll','windows-opening-fixture.dll','windows-open.bc') | ForEach-Object {
        @{ path=(Join-Path $Output $_); sha256=(Get-FileHash -LiteralPath (Join-Path $Output $_)).Hash.ToLowerInvariant() }
    }
} catch { $receipt.failure = $_.ToString(); throw }
finally {
    $receipt.finished = [DateTime]::UtcNow.ToString('o')
    $receipt | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath "$Output/windows-io-receipt.json"
}
