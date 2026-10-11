# SPDX-FileCopyrightText: 2026 Edward Kmett
# SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

param([ValidateSet('native', 'hotspot', 'graal')][string]$Mode = 'native')
$ErrorActionPreference = 'Stop'
if ((Get-ItemPropertyValue 'HKLM:/SYSTEM/CurrentControlSet/Control/FileSystem' -Name LongPathsEnabled -ErrorAction SilentlyContinue) -ne 1) {
    throw 'Enable Win32 long paths before building; see docs/build.md#windows.'
}
if (!$env:JAM_CI_TOOLS) { throw 'Set JAM_CI_TOOLS to the dependency installation directory' }
if (!$env:JAM_PYTHON) { $env:JAM_PYTHON = (Get-Command python).Source }
$prefix = [IO.Path]::GetFullPath($env:JAM_CI_TOOLS)
New-Item -ItemType Directory -Force $prefix | Out-Null
$tar = "$env:SystemRoot/System32/tar.exe"

function Get-Archive($Name, $Digest, $Url, $Algorithm = 'SHA256') {
    $archive = Join-Path $prefix $Name
    if (!(Test-Path $archive)) {
        Write-Host "Downloading $Name"
        & curl.exe --fail --location --retry 3 --connect-timeout 20 --max-time 900 --silent --show-error --output $archive $Url
        if ($LASTEXITCODE) { throw "Download failed: $Url" }
    }
    Write-Host "Verifying $Name"
    if ((Get-FileHash $archive -Algorithm $Algorithm).Hash.ToLowerInvariant() -ne $Digest) {
        throw "Digest mismatch: $archive"
    }
    return $archive
}

$llvm = "$prefix/clang+llvm-23.1.2-x86_64-pc-windows-msvc"
if (!(Test-Path "$prefix/llvm-ready")) {
    # Use Python's XZ reader consistently across Windows releases.
    $archive = Get-Archive llvm23.tar.xz '8fb91cdc44fcbbdcf6b3ffd0a1f9859abd14a3c3aae4423c2b6d4a4f90bf0095' 'https://github.com/llvm/llvm-project/releases/download/llvmorg-23.1.2/clang%2Bllvm-23.1.2-x86_64-pc-windows-msvc.tar.xz'
    Write-Host 'Extracting LLVM'
    & $env:JAM_PYTHON -m tarfile -e $archive $prefix
    if ($LASTEXITCODE) { throw 'LLVM extraction failed' }
    New-Item -ItemType File "$prefix/llvm-ready" | Out-Null
}
$cmake = "$prefix/cmake-4.4.3-windows-x86_64"
if (!(Test-Path "$cmake/bin/cmake.exe")) {
    $archive = Get-Archive cmake.zip '4d52ebab7193a698651639ed80d8d04fd903358843572cf44c7fd234cb7c26ab' 'https://github.com/Kitware/CMake/releases/download/v4.4.3/cmake-4.4.3-windows-x86_64.zip'
    Write-Host 'Extracting CMake'
    & $tar -xf $archive -C $prefix
    if ($LASTEXITCODE) { throw 'CMake extraction failed' }
}
if (!(Test-Path "$prefix/ninja/ninja.exe")) {
    $archive = Get-Archive ninja.zip '07fc8261b42b20e71d1720b39068c2e14ffcee6396b76fb7a795fb460b78dc65' 'https://github.com/ninja-build/ninja/releases/download/v1.13.2/ninja-win.zip'
    Write-Host 'Extracting Ninja'
    New-Item -ItemType Directory -Force "$prefix/ninja" | Out-Null
    & $tar -xf $archive -C "$prefix/ninja"
    if ($LASTEXITCODE) { throw 'Ninja extraction failed' }
}
$jdk = "$prefix/jdk-25.0.2+10"
if (!(Test-Path "$jdk/bin/java.exe")) {
    $archive = Get-Archive boot-jdk.zip '06ac5f5444a1269dd11d11cbb7ab6ebaecedc60dc1caca82cdb56f29100b7b8c' 'https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.2%2B10/OpenJDK25U-jdk_x64_windows_hotspot_25.0.2_10.zip'
    Write-Host 'Extracting boot JDK'
    & $tar -xf $archive -C $prefix
    if ($LASTEXITCODE) { throw 'Boot JDK extraction failed' }
}
if ($Mode -ne 'native' -and !(Test-Path "$prefix/cygwin-ready")) {
    $setup = Get-Archive setup-x86_64.exe '6acea47c59781c9e7f544a18d53935d59df6e44d5d52ac95ee165671b8e388820455eebf30ccc7d254b00c3d0eb694269a0f8dc84b17944c1f355dac9c5aafcc' 'https://cygwin.com/setup-x86_64.exe' 'SHA512'
    Write-Host 'Installing Cygwin'
    $process = Start-Process -FilePath $setup -ArgumentList @('--quiet-mode', '--no-admin', '--no-desktop', '--no-startmenu', '--no-write-registry', '--root', "`"$prefix/cygwin`"", '--local-package-dir', "`"$prefix/cygwin-downloads`"", '--site', 'https://mirrors.kernel.org/sourceware/cygwin/', '--packages', 'autoconf,make,unzip,zip,diffutils,patch,procps-ng') -Wait -PassThru
    if ($process.ExitCode) { throw "Cygwin setup failed: $($process.ExitCode)" }
    # Files are shared with native Git, Python and the Windows archive extractor.
    Add-Content -Path "$prefix/cygwin/etc/fstab" -Value "`nnone /cygdrive cygdrive binary,posix=0,user,noacl 0 0" -Encoding ascii
    New-Item -ItemType File "$prefix/cygwin-ready" | Out-Null
}
$graal = "$prefix/graalvm-community-25.3.4.1+1.1"
if ($Mode -eq 'graal' -and !(Test-Path "$graal/bin/java.exe")) {
    $archive = Get-Archive graalvm.zip '770a0d78aba4c19bd40ee410ec82afbbee8e33fdd762e509006ac64e1007b4ce' 'https://github.com/graalvm/graalvm-ce-builds/releases/download/graal-25.3.4.1/graalvm-community-jdk-25i3-25.0.4.1_windows-x64_bin.zip'
    Write-Host 'Extracting stock GraalVM'
    & $tar -xf $archive -C $prefix
    if ($LASTEXITCODE) { throw 'Stock GraalVM extraction failed' }
}
if ($Mode -ne 'native') {
    # Keep the original redistribution documents with the DLLs, including the
    # compiler-builtins notice omitted from the binary LLVM archive.
    $null = Get-Archive compiler-rt-LICENSE.TXT '1a8f1058753f1ba890de984e48f0242a3a5c29a6a8f2ed9fd813f36985387e8d' 'https://raw.githubusercontent.com/llvm/llvm-project/85ac560262434c9ccfc0c183ec22d4138ed647fb/compiler-rt/LICENSE.TXT'
    $null = Get-Archive Microsoft-Build-Tools-License.docx '2f66b86a00e8d9833789897ce23d05a4a2dbea370cf39c8c1098dbc17d0e7bdc' 'https://visualstudio.microsoft.com/wp-content/uploads/2024/03/Visual-Studio-2022-Diagnostic-Build-Tools-Agent-License_Update-March-2024_EN.docx'
    $null = Get-Archive Microsoft-Redistribution.md '0240b9a75f8fd997d998af27ea84b901f576782177f8ff535b7b07924c9570f7' 'https://learn.microsoft.com/en-us/visualstudio/releases/2022/redistribution?accept=text/markdown'
}

# Resolve Visual Studio on each runner; its installation is not part of the cache.
$environment = @'
$ErrorActionPreference = 'Stop'
if ((Get-ItemPropertyValue 'HKLM:/SYSTEM/CurrentControlSet/Control/FileSystem' -Name LongPathsEnabled -ErrorAction SilentlyContinue) -ne 1) {
    throw 'Enable Win32 long paths before building; see docs/build.md#windows.'
}
$prefix = '__PREFIX__'
Write-Host 'Locating Visual Studio 2022'
$vswhere = "${env:ProgramFiles(x86)}/Microsoft Visual Studio/Installer/vswhere.exe"
$vs = & $vswhere -latest -products '*' -version '[17.0,18.0)' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (!$vs) { throw 'Visual Studio 2022 C++ build tools are required' }
Write-Host "Initializing Visual Studio developer shell: $vs"
Import-Module "$vs/Common7/Tools/Microsoft.VisualStudio.DevShell.dll"
Enter-VsDevShell -VsInstallPath $vs -SkipAutomaticLocation -DevCmdArguments '-arch=x64 -host_arch=x64'
$env:JAM_CXX = "$prefix/clang+llvm-23.1.2-x86_64-pc-windows-msvc/bin/clang-cl.exe"
$env:JAM_CMAKE = "$prefix/cmake-4.4.3-windows-x86_64/bin/cmake.exe"
$env:JAM_NINJA = "$prefix/ninja/ninja.exe"
$env:JAM_BOOT_JDK = "$prefix/jdk-25.0.2+10"
$env:JAM_STOCK_GRAAL_HOME = "$prefix/graalvm-community-25.3.4.1+1.1"
$env:JAM_MSVC_REDIST = Join-Path $env:VCToolsRedistDir 'x64/Microsoft.VC143.CRT'
$env:JAM_COMPILER_RUNTIME_LICENSE = "$prefix/compiler-rt-LICENSE.TXT"
if (!$env:JAM_PYTHON) { $env:JAM_PYTHON = (Get-Command python).Source }
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$env:JAM_BASH = "$prefix/cygwin/bin/bash.exe"
$env:JAM_PATCH = "$prefix/cygwin/bin/patch.exe"
$git = Split-Path (Get-Command git).Source -Parent
# Native tools must find MSVC's link.exe before Cygwin's hard-link utility.
# The HotSpot wrapper supplies the opposite ordering inside Cygwin.
$env:Path = "$prefix/clang+llvm-23.1.2-x86_64-pc-windows-msvc/bin;$prefix/ninja;$git;$env:Path;$prefix/cygwin/bin"
$env:CC = 'clang-cl'
$env:GIT_CONFIG_COUNT = '2'
$env:GIT_CONFIG_KEY_0 = 'core.longpaths'; $env:GIT_CONFIG_VALUE_0 = 'true'
$env:GIT_CONFIG_KEY_1 = 'core.autocrlf'; $env:GIT_CONFIG_VALUE_1 = 'false'
'@
$environment.Replace('__PREFIX__', $prefix.Replace("'", "''")) | Set-Content "$prefix/env.ps1" -Encoding utf8
. "$prefix/env.ps1"
Write-Host 'Checking build tools'
& $env:JAM_CXX --version
& $env:JAM_CMAKE --version
& $env:JAM_NINJA --version
& "$env:JAM_BOOT_JDK/bin/java.exe" -version
if ($LASTEXITCODE) { throw 'Toolchain verification failed' }
& $env:JAM_PYTHON --version
if ($LASTEXITCODE) { throw 'Set JAM_PYTHON to a working native Python 3.10 or newer' }
