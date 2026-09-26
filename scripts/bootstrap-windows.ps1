# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
# Dot-source this script to select task-local tools in the current shell.
param([string]$Prefix = (Join-Path (Split-Path $PSScriptRoot) '.toolchains/windows'))
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/windows-common.ps1"
if ($env:OS -ne 'Windows_NT' -or [Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne 'X64') {
    throw 'This bootstrap supplies native Windows x64 tools'
}
$Prefix = [IO.Path]::GetFullPath($Prefix)
New-Item -ItemType Directory -Force $Prefix, "$Prefix/downloads" | Out-Null
function Get-ThcArchive([string]$Name, [string]$Url, [string]$Sha256) {
    $archive = Join-Path $Prefix "downloads/$Name"
    if (!(Test-Path -LiteralPath $archive)) { Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $archive }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Sha256) {
        throw "Archive SHA256 mismatch; retained for inspection: $archive"
    }
    return $archive
}
# SHA256 values from the release's upstream SHA256SUMS / .sha256 files.
$ghcDownload = @('ghc.tar.xz',
    'https://downloads.haskell.org/ghc/9.14.1/ghc-9.14.1-x86_64-unknown-mingw32.tar.xz',
    '4914e262a91a9cb65807ec33b3a2adc884894b1dcc33691d820bb9456f2794ba')
$cabalDownload = @('cabal.zip',
    'https://downloads.haskell.org/~cabal/cabal-install-3.16.0.0/cabal-install-3.16.0.0-x86_64-windows.zip',
    '572c1eba3da3aa7754790e14462d301df208dd0c4f183a6507ffd86f71595349')
$javaDownload = @('graalvm-ce.zip',
    'https://github.com/graalvm/graalvm-ce-builds/releases/download/graal-25.3.4.1/graalvm-community-jdk-25i3-25.0.4.1_windows-x64_bin.zip',
    '770a0d78aba4c19bd40ee410ec82afbbee8e33fdd762e509006ac64e1007b4ce')
$ghcArchive = Get-ThcArchive @ghcDownload
$cabalArchive = Get-ThcArchive @cabalDownload
$javaArchive = Get-ThcArchive @javaDownload
$ghcRoot = Join-Path $Prefix 'ghc-9.14.1-x86_64-unknown-mingw32'
$javaRoot = Join-Path $Prefix 'graalvm-community-25.3.4.1+1.1'
if (!(Test-Path -LiteralPath $ghcRoot)) { Invoke-ThcTool 'tar.exe' @('-xf', $ghcArchive, '-C', $Prefix) }
if (!(Test-Path -LiteralPath "$Prefix/cabal")) { Expand-Archive -LiteralPath $cabalArchive -DestinationPath "$Prefix/cabal" }
if (!(Test-Path -LiteralPath $javaRoot)) { Expand-Archive -LiteralPath $javaArchive -DestinationPath $Prefix }
$env:JAVA_HOME = $javaRoot
$env:GHC = Join-Path $ghcRoot 'bin/ghc.exe'
$env:GHC_PKG = Join-Path $ghcRoot 'bin/ghc-pkg.exe'
$env:CABAL = Join-Path $Prefix 'cabal/cabal.exe'
$env:THC_CLANG = Join-Path $ghcRoot 'mingw/bin/clang.exe'
$env:CABAL_DIR = Join-Path $Prefix 'cabal-home'
$env:GRADLE_USER_HOME = Join-Path $Prefix 'gradle-home'
$env:PATH = "$javaRoot/bin;$ghcRoot/bin;$ghcRoot/mingw/bin;$Prefix/cabal;$env:PATH"
Assert-ThcJava
$null = Get-ThcGhc
$cabalVersion = Invoke-ThcTool $env:CABAL @('--numeric-version')
if ($cabalVersion -ne '3.16.0.0') { throw "Unexpected Cabal: $cabalVersion" }
if (!(Test-Path -LiteralPath $env:THC_CLANG)) { throw 'Missing GHC-bundled Clang' }
if (!$env:THC_PYTHON) { $env:THC_PYTHON = (Get-Command python.exe -CommandType Application -ErrorAction Stop).Source }
$pythonVersion = Invoke-ThcTool $env:THC_PYTHON @('--version')
if ($pythonVersion -notmatch '^Python (\d+)\.(\d+)\.' -or [int]$Matches[1] -ne 3 -or [int]$Matches[2] -lt 12) {
    throw 'Select Python 3.12+ using THC_PYTHON; the Microsoft Store alias is not an interpreter'
}
Write-Host "Selected native Windows tools in $Prefix"
Write-Host 'GraalVM Community 25.3.4.1 / JDK 25; GHC 9.14.1 stock vanilla bindist (not complete installed Core).'