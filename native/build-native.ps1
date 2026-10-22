# Build the PocketForge native layer with the NDK toolchain directly (no Gradle),
# then copy the produced shared libraries into app/src/main/jniLibs/arm64-v8a so
# AGP packages them as-is.
#
# Usage:
#   .\native\build-native.ps1                  CPU-only build (default)
#   .\native\build-native.ps1 -EnableVulkan    experimental ggml Vulkan experiment
#   .\native\build-native.ps1 -Clean           wipe build dir first

param(
    [switch]$EnableVulkan,
    [switch]$Clean
)

$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $PSScriptRoot
$CppDir   = Join-Path $RepoRoot "app\src\main\cpp"
$BuildDir = Join-Path $RepoRoot "native\build-arm64"
$OutDir   = Join-Path $RepoRoot "app\src\main\jniLibs\arm64-v8a"

if (-not (Test-Path (Join-Path $RepoRoot "local.properties"))) {
    throw "local.properties with sdk.dir not found at $RepoRoot"
}
$SdkDir = ($match = Select-String -Path (Join-Path $RepoRoot "local.properties") -Pattern '^sdk\.dir=(.*)$').Matches[0].Groups[1].Value
$SdkDir = [System.Text.RegularExpressions.Regex]::Replace($SdkDir, '\\(.)', '$1')  # unescape \: and \\
if (-not (Test-Path $SdkDir)) { throw "SDK dir from local.properties not found: $SdkDir" }

$NdkDir = Join-Path $SdkDir "ndk\28.2.13676358"
if (-not (Test-Path $NdkDir)) {
    throw "NDK 28.2.13676358 not found at $NdkDir"
}

$Toolchain = Join-Path $NdkDir "build\cmake\android.toolchain.cmake"

# Prefer SDK cmake 3.31.6 (matches upstream llama.cpp example requirement);
# fall back to whatever cmake is on PATH.
$CmakeExe = Get-ChildItem (Join-Path $SdkDir "cmake") -Directory -ErrorAction SilentlyContinue |
    Sort-Object { [version]$_.Name } -Descending |
    ForEach-Object { Join-Path $_.FullName "bin\cmake.exe" } |
    Where-Object { Test-Path $_ } |
    Select-Object -First 1
if (-not $CmakeExe) { $CmakeExe = "cmake.exe" }
Write-Host "Using cmake: $CmakeExe"

$NinjaDir = Split-Path $CmakeExe -Parent
$env:PATH = "$NinjaDir;$env:PATH"
$Cpus = [Environment]::ProcessorCount

if ($Clean -and (Test-Path $BuildDir)) {
    Remove-Item -Recurse -Force $BuildDir
}

$Opts = @(
    "-G", "Ninja",
    "-DCMAKE_TOOLCHAIN_FILE=$Toolchain",
    "-DANDROID_ABI=arm64-v8a",
    "-DANDROID_PLATFORM=android-26",
    "-DCMAKE_BUILD_TYPE=Release",
    # options mirroring the upstream examples/llama.android build
    "-DGGML_NATIVE=OFF",
    "-DGGML_BACKEND_DL=ON",
    "-DGGML_CPU_ALL_VARIANTS=ON",
    "-DGGML_LLAMAFILE=OFF",
    "-DBUILD_SHARED_LIBS=ON",
    "-DLLAMA_BUILD_APP=OFF",
    "-DLLAMA_BUILD_COMMON=ON",
    "-DLLAMA_OPENSSL=OFF"
)

if ($EnableVulkan) {
    $Opts += @("-DPOCKETFORGE_ENABLE_VULKAN=ON", "-DGGML_VULKAN=ON")
    # glslc ships with the NDK in shader-tools; the Vulkan SDK, if present, overrides.
    $NdkGlslc = Join-Path $NdkDir "shader-tools\windows-x86_64\glslc.exe"
    if (Test-Path $NdkGlslc) {
        $Opts += @("-DVulkan_GLSLC_EXECUTABLE=$NdkGlslc")
        Write-Host "Vulkan shader compiler: $NdkGlslc"
    }
}

# Locate the NDK's static OpenMP runtime so packaged libs get no libomp.so NEEDED entry
$LibompStatic = Get-ChildItem (Join-Path $NdkDir "toolchains\llvm\prebuilt\windows-x86_64\lib\clang") -Recurse -Filter libomp.a |
    Where-Object { $_.FullName -match "aarch64" } |
    Select-Object -First 1
if ($LibompStatic) {
    $Opts += @("-DPOCKETFORGE_LIBOMP_STATIC=$($LibompStatic.FullName)")
    Write-Host "Static libomp: $($LibompStatic.FullName)"
} else {
    Write-Warning "NDK libomp.a not found; packaged libs would require libomp.so which Android lacks - packaging NDK dynamic libomp.so instead"
}

Write-Host "Configuring (Vulkan: $EnableVulkan)..."

if ($EnableVulkan) {
    # ggml's ggml-vulkan cross-support needs a HOST compiler to build
    # vulkan-shaders-gen; cl.exe is only on PATH inside a vcvars environment.
    # Locate Visual Studio and run configure+build inside one vcvars cmd chain.
    $vswhere = "C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe"
    if (-not (Test-Path $vswhere)) { throw "vswhere not found; install Visual Studio or use CPU-only build" }
    $vsPath = & $vswhere -latest -property installationPath
    if (-not $vsPath) { throw "Visual Studio installation not found" }
    $vcvars = Join-Path $vsPath "VC\Auxiliary\Build\vcvars64.bat"
    if (-not (Test-Path $vcvars)) { throw "vcvars64.bat not found at $vcvars" }

    $optStr = ($Opts | ForEach-Object { if ($_ -match ' ') { '"' + $_ + '"' } else { $_ } }) -join ' '
    $cmakeExePath = (Resolve-Path $CmakeExe).Path
    $configureCmd = '"' + $cmakeExePath + '" -S ' + $CppDir + ' -B ' + $BuildDir + ' ' + $optStr
    $buildCmd = '"' + $cmakeExePath + '" --build ' + $BuildDir + ' --parallel ' + $Cpus
    Write-Host ("> call " + '"' + $vcvars + '" >nul 2>&1 && ' + $configureCmd + ' && ' + $buildCmd) | Out-File (Join-Path $PSScriptRoot "last-vulkan-cmd.txt") -Encoding utf8
    cmd /c ('call "' + $vcvars + '" >nul 2>&1 && ' + $configureCmd + ' && ' + $buildCmd)
    if ($LASTEXITCODE -ne 0) { throw "Vulkan-enabled configure/build failed (see output above)" }
} else {
    & $CmakeExe -S $CppDir -B $BuildDir @Opts
    if ($LASTEXITCODE -ne 0) { throw "CMake configure failed" }

    Write-Host "Building..."
    & $CmakeExe --build $BuildDir --parallel $Cpus
    if ($LASTEXITCODE -ne 0) { throw "Build failed" }
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$libs = Get-ChildItem $BuildDir -Recurse -Filter *.so |
    Where-Object { $_.FullName -notmatch "glslc|vulkan-shaders-gen|shaderc" }
foreach ($lib in $libs) {
    Copy-Item $lib.FullName $OutDir -Force
    Write-Host "Packaged: $($lib.Name)"
}
if (-not $libs) { throw "No shared libraries found in build output" }

Write-Host ""
Write-Host "Done. $($libs.Count) shared libraries in $OutDir"
Get-ChildItem $OutDir | ForEach-Object { "{0,10:N0}  {1}" -f $_.Length, $_.Name }
