# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
param([string]$Output = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
if ($env:OS -ne 'Windows_NT') { throw 'Launcher check requires native Windows' }
if (!$Output) { $Output = Join-Path $root 'build/windows-launcher-check' }
New-Item -ItemType Directory -Force -Path $Output | Out-Null
$Output = [IO.Path]::GetFullPath($Output)
$inputFile = Join-Path $Output 'input.json'
[IO.File]::WriteAllText($inputFile, '[]', [Text.UTF8Encoding]::new($false))
# The provider identity utilities must not consume the guest's stdin. This
# fixture-free audit exercises the generated batch launcher and its real JVM.
$command = 'build\install\thc\bin\thc.bat --classify-foreign-calls'
$receipt = [ordered]@{ command=@($env:ComSpec,'/d','/c',$command); input=$inputFile; started=[DateTime]::UtcNow.ToString('o'); nativeExit=$null; guardTriggered=$false }
$watch = [Diagnostics.Stopwatch]::StartNew()
try {
    $process = Start-Process -FilePath $env:ComSpec -ArgumentList @('/d','/c',$command) -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardInput $inputFile -RedirectStandardOutput "$Output/stdout.log" -RedirectStandardError "$Output/stderr.log" -PassThru
    if (!$process.WaitForExit(90000)) {
        $receipt.guardTriggered = $true
        $process.Kill($true); $process.WaitForExit()
    }
    $process.WaitForExit()
    $receipt.nativeExit = $process.ExitCode
    if ($receipt.guardTriggered -or $process.ExitCode -ne 0) {
        throw "Windows launcher audit failed with native exit $($process.ExitCode); see $Output/stderr.log"
    }
    $result = Get-Content -LiteralPath "$Output/stdout.log" -Raw | ConvertFrom-Json
    if ($result.schema -ne 1 -or $null -eq $result.owners -or $result.owners.Count -ne 0) {
        throw 'Windows launcher changed the stdin audit result'
    }
    $receipt.runtime = $result.runtime
    $receipt.passed = $true
} catch { $receipt.failure = $_.ToString(); throw }
finally {
    $watch.Stop(); $receipt.seconds = $watch.Elapsed.TotalSeconds
    $receipt.finished = [DateTime]::UtcNow.ToString('o')
    $receipt | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$Output/receipt.json"
}
Write-Output "Windows launcher stdin audit passed; receipt: $Output/receipt.json"
