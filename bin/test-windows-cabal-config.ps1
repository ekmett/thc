# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
param([string]$Directory = (Join-Path (Split-Path $PSScriptRoot) "build/windows-cabal-config/$([Guid]::NewGuid())"))
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/windows-common.ps1"
if (!$env:CABAL) { throw 'Select the pinned Cabal with bootstrap-windows.ps1 first' }
New-Item -ItemType Directory -Path $Directory | Out-Null
$config = Join-Path $Directory 'config'
Initialize-ThcCabalConfig $config
$initial = [IO.File]::ReadAllText($config)
if ($initial -notmatch 'url: https://hackage\.haskell\.org/' -or $initial -notmatch '(?m)^  secure: True\r?$') {
    throw 'New config must use HTTPS and signed metadata'
}
# Migrate the old default while retaining explicit trust keys and private repos.
$legacy = $initial.Replace('https://hackage.haskell.org/', 'http://hackage.haskell.org/').Replace('  secure: True', '  -- secure: True')
$legacy = $legacy.Replace('  -- root-keys:', "  root-keys: $('a' * 64)").Replace('  -- key-threshold: 3', '  key-threshold: 1')
$legacy += "`nrepository private.example`n  url: https://private.example/packages/`n  secure: True`njobs: 3`n"
[IO.File]::WriteAllText($config, $legacy, [Text.UTF8Encoding]::new($false))
Initialize-ThcCabalConfig $config
$expected = $legacy.Replace('http://hackage.haskell.org/', 'https://hackage.haskell.org/').Replace('  -- secure: True', '  secure: True')
if ([IO.File]::ReadAllText($config) -cne $expected) { throw 'Migration changed unrelated settings or trust keys' }
$hash = (Get-FileHash -LiteralPath $config -Algorithm SHA256).Hash
Initialize-ThcCabalConfig $config
if ((Get-FileHash -LiteralPath $config -Algorithm SHA256).Hash -ne $hash) { throw 'Config initialization is not idempotent' }
$insecure = $expected.Replace('  secure: True', '  secure: False')
[IO.File]::WriteAllText($config, $insecure, [Text.UTF8Encoding]::new($false))
$rejected = $false
try { Initialize-ThcCabalConfig $config } catch {
    if ($_.Exception.Message -notmatch '^Windows bootstrap requires the signed') { throw }
    $rejected = $true
}
if (!$rejected -or [IO.File]::ReadAllText($config) -cne $insecure) { throw 'Disabled verification must fail without rewriting config' }
Write-Host 'Windows Cabal config: initialization, migration, trust retention, idempotence and insecure-config rejection passed.'
