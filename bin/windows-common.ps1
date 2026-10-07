# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

function Invoke-ThcTool {
    param([string]$Program, [string[]]$Arguments = @())
    # Windows PowerShell represents native stderr as ErrorRecords. The native
    # exit code, not ordinary compiler diagnostics on stderr, determines success.
    $saved = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $Program @Arguments
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $saved }
    if ($code -ne 0) { throw "$Program exited $code" }
}

function Get-ThcPython {
    if ($env:THC_PYTHON) { return $env:THC_PYTHON }
    $python = Get-Command python.exe -CommandType Application -ErrorAction SilentlyContinue |
        Where-Object { $_.Source -notmatch '[\\/]Microsoft[\\/]WindowsApps[\\/]' } |
        Select-Object -First 1
    if (!$python) { throw 'Select Python 3.12+ using THC_PYTHON; the Microsoft Store alias is not an interpreter' }
    return $python.Source
}

function Initialize-ThcCabalConfig([string]$ConfigFile) {
    if (!(Test-Path -LiteralPath $ConfigFile)) {
        $repository = "repository hackage.haskell.org`n  url: https://hackage.haskell.org/`n  secure: True"
        Invoke-ThcTool $env:CABAL @("--config-file=$ConfigFile", 'user-config', 'init', '--augment', $repository)
        return
    }
    # Keep existing repository keys and other settings. Cabal's --augment replaces
    # the complete repository list, including unrelated private repositories.
    $text = [IO.File]::ReadAllText($ConfigFile)
    $repository = [regex]::Match($text, '(?ms)^repository hackage\.haskell\.org[ \t]*\r?\n.*?(?=^[^ \t\r\n-]|\z)')
    if (!$repository.Success -or $repository.Value -notmatch '(?m)^[ \t]+url:[ \t]+https?://hackage\.haskell\.org/[ \t]*\r?$' -or
        $repository.Value -match '(?im)^[ \t]+secure:[ \t]+False\b') {
        throw 'Windows bootstrap requires the signed hackage.haskell.org repository; retained existing Cabal config'
    }
    $updated = $repository.Value.Replace('http://hackage.haskell.org/', 'https://hackage.haskell.org/').Replace('-- secure: True', 'secure: True')
    if ($updated -ne $repository.Value) {
        $text = $text.Substring(0, $repository.Index) + $updated + $text.Substring($repository.Index + $repository.Length)
        [IO.File]::WriteAllText($ConfigFile, $text, [Text.UTF8Encoding]::new($false))
    }
}

function Get-ThcGhc {
    $selected = if ($env:GHC) { $env:GHC } else { 'ghc' }
    $compiler = (Get-Command $selected -CommandType Application -ErrorAction Stop).Source
    $versioned = Join-Path (Split-Path $compiler) 'ghc-9.14.1.exe'
    if ((Split-Path $compiler -Leaf) -eq 'ghc.exe' -and (Test-Path -LiteralPath $versioned)) { $compiler = $versioned }
    $packageTool = Join-Path (Split-Path $compiler) 'ghc-pkg-9.14.1.exe'
    if (!(Test-Path -LiteralPath $packageTool)) { $packageTool = Join-Path (Split-Path $compiler) 'ghc-pkg.exe' }
    if ($env:GHC_PKG) { $packageTool = (Get-Command $env:GHC_PKG -CommandType Application -ErrorAction Stop).Source }
    $versioned = Join-Path (Split-Path $packageTool) 'ghc-pkg-9.14.1.exe'
    if ((Split-Path $packageTool -Leaf) -eq 'ghc-pkg.exe' -and (Test-Path -LiteralPath $versioned)) { $packageTool = $versioned }
    $version = Invoke-ThcTool $compiler @('--numeric-version')
    $packageVersion = Invoke-ThcTool $packageTool @('--version')
    if ($version -ne '9.14.1' -or $packageVersion -ne 'GHC package manager version 9.14.1') {
        throw "THC requires GHC and ghc-pkg 9.14.1: $version / $packageVersion"
    }
    $globalDb = Invoke-ThcTool $compiler @('--print-global-package-db')
    $listing = @(Invoke-ThcTool $packageTool @('--global', '--no-user-package-db', 'list', 'ghc-internal'))
    if ([IO.Path]::GetFullPath($globalDb) -ne [IO.Path]::GetFullPath($listing[0])) {
        throw 'Selected GHC and ghc-pkg use different global package databases'
    }
    return @{ Compiler = $compiler; PackageTool = $packageTool }
}

function Assert-ThcJava {
    if (!$env:JAVA_HOME -or !(Test-Path -LiteralPath "$env:JAVA_HOME/bin/java.exe" -PathType Leaf) -or
        !(Test-Path -LiteralPath "$env:JAVA_HOME/release" -PathType Leaf)) {
        throw 'Set JAVA_HOME to the pinned Windows JAM package from etc/jam-graalvm.json, with bin/java.exe and release'
    }
    $pin = Get-Content -LiteralPath (Join-Path (Split-Path $PSScriptRoot) 'etc/jam-graalvm.json') -Raw | ConvertFrom-Json
    $package = $pin.platforms.'Windows-x86_64'
    if ($pin.schema -ne 1 -or !$package.runtime.sha256.release) {
        throw 'No supported Windows JAM package identity in etc/jam-graalvm.json'
    }
    # Cheap setup preflight; the runtime Gradle build verifies the complete package.
    if ((Get-FileHash -LiteralPath "$env:JAVA_HOME/release" -Algorithm SHA256).Hash.ToLowerInvariant() -ne $package.runtime.sha256.release) {
        throw 'JAVA_HOME release differs from the pinned Windows JAM package; select the matching package from etc/jam-graalvm.json'
    }
}
