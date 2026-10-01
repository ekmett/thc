# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot/windows-common.ps1"
$previous = $env:THC_PYTHON
try {
    Remove-Item Env:THC_PYTHON -ErrorAction SilentlyContinue
    # Test discovery without executing an App Execution Alias or changing PATH.
    function Get-Command { param($Name, $CommandType, $ErrorAction) $script:candidates }
    $script:candidates = @(
        [pscustomobject]@{Source='C:\Users\fixture\AppData\Local\Microsoft\WindowsApps\python.exe'},
        [pscustomobject]@{Source='C:\Python313\python.exe'},
        [pscustomobject]@{Source='C:\Python312\python.exe'})
    if ((Get-ThcPython) -cne 'C:\Python313\python.exe') { throw 'Discovery must select one interpreter and skip the Store alias' }
    $script:candidates = @($script:candidates[0])
    try { $null = Get-ThcPython; throw 'Accepted an alias without an interpreter' }
    catch { if ($_.Exception.Message -notlike 'Select Python 3.12+*') { throw } }
    $script:candidates = @()
    try { $null = Get-ThcPython; throw 'Accepted missing Python' }
    catch { if ($_.Exception.Message -notlike 'Select Python 3.12+*') { throw } }
    $env:THC_PYTHON = 'C:\Explicit interpreter\python.exe'
    if ((Get-ThcPython) -cne $env:THC_PYTHON) { throw 'Explicit interpreter was replaced' }
    Write-Output 'Windows Python discovery checks passed'
} finally { $env:THC_PYTHON = $previous }
