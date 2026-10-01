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
    if (!$env:JAVA_HOME -or !(Test-Path "$env:JAVA_HOME/bin/java.exe")) {
        throw 'Set JAVA_HOME to GraalVM 25.3.4.1 / JDK 25 for Windows'
    }
    $release = @{}
    Get-Content "$env:JAVA_HOME/release" | ForEach-Object {
        if ($_ -match '^([^=]+)="(.*)"$') { $release[$Matches[1]] = $Matches[2] }
    }
    if ($release['GRAALVM_VERSION'] -ne '25.3.4.1' -or $release['JAVA_VERSION'].Split('.')[0] -ne '25') {
        throw 'THC requires exactly GraalVM 25.3.4.1 on JDK 25'
    }
}
