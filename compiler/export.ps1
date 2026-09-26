# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
# Native Windows counterpart of export.sh. GHC still produces all Core.
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GhcArguments)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
. "$root/scripts/windows-common.ps1"
$tools = Get-ThcGhc
Push-Location $root
try {
    $cabal = if ($env:CABAL) { $env:CABAL } else { 'cabal' }
    Invoke-ThcTool $cabal @('build', 'lib:thc', '--offline', '--disable-shared',
        "--with-compiler=$($tools.Compiler)", "--with-hc-pkg=$($tools.PackageTool)") | Out-Host
    $plan = Get-Content 'dist-newstyle/cache/plan.json' -Raw | ConvertFrom-Json
    if ($plan.'compiler-id' -ne 'ghc-9.14.1') { throw 'Wrong compiler in Cabal plan' }
    $libraries = @($plan.'install-plan' | Where-Object {
        $_.'pkg-name' -eq 'thc' -and $_.'component-name' -eq 'lib' -and $_.style -eq 'local'
    })
    if ($libraries.Count -ne 1) { throw 'Expected exactly one Cabal THC library' }
    $library = $libraries[0]
    if ([IO.Path]::GetFullPath($library.'pkg-src'.path) -ne $root) { throw 'Cabal plan belongs to another checkout' }
    $db = Join-Path $root 'dist-newstyle/packagedb/ghc-9.14.1'
    $registered = Invoke-ThcTool $tools.PackageTool @('--unit-id', 'field', $library.id, 'id', '--simple-output', '--package-db', $db)
    if ($registered -ne $library.id) { throw 'Plugin registration does not match the Cabal plan' }
    # The pinned Windows GHC is static (GHC Dynamic=NO). Let GHC load its actual
    # Cabal registration and vanilla archive; do not invent a Unix shared-library
    # manifest or pass -dynamic against an installation without that library way.
    $info = Invoke-ThcTool $tools.Compiler @('--info')
    if (($info -join "`n") -notmatch '\("GHC Dynamic","NO"\)') { throw 'This Windows exporter requires the pinned vanilla GHC profile' }
    $out = if ($env:THC_CORE_OUT) { $env:THC_CORE_OUT } else { Join-Path $root 'build/core' }
    $obj = if ($env:THC_GHC_OUT) { $env:THC_GHC_OUT } else { Join-Path $root 'build/ghc' }
    New-Item -ItemType Directory -Force $out, $obj | Out-Null
    $arguments = @('--make', '-no-link', '-O2', '-fforce-recomp', '-dcore-lint',
        '-package-db', $db, '-plugin-package-id', $library.id, '-fplugin=THC.Plugin',
        "-fplugin-opt=THC.Plugin:$out", "-i$root/examples", '-odir', $obj, '-hidir', $obj)
    if ($env:THC_SOURCE_NOTES -ne 'false') { $arguments += @('-g', '-fplugin-opt=THC.Plugin:source-notes') }
    Invoke-ThcTool $tools.Compiler ($arguments + $GhcArguments)
} finally { Pop-Location }
