# build_controller.ps1 - one-liner host build for the PF plan controller + tests.
# No Android NDK / llama.cpp dependency: pf_plan.cpp, pf_controller.cpp and
# pf_controller_test.cpp are plain C++17.
#
# Usage:
#   .\build_controller.ps1            # auto-detect cl (MSVC) / clang++ / g++, compile, run tests
#   .\build_controller.ps1 -ShowCmd   # print the exact one-liner command used
# Exit 3 = no toolchain found (instructions printed).
param([switch]$ShowCmd)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host "== PF controller build stub =="
$src = @("pf_plan.cpp", "pf_plan_apply.cpp", "pf_controller.cpp", "pf_controller_test.cpp")
foreach ($f in $src) {
    if (-not (Test-Path (Join-Path $here $f))) { Write-Host "missing source: $f" -ForegroundColor Red; exit 2 }
}

$exe = Join-Path $here "pf_controller_test.exe"
if (Test-Path $exe) { Remove-Item $exe -Force | Out-Null }

$vcvars = Get-ChildItem "C:\Program Files*\Microsoft Visual Studio\*\*\VC\Auxiliary\Build\vcvars64.bat" -ErrorAction SilentlyContinue | Select-Object -First 1
$clOnPath = Get-Command cl -ErrorAction SilentlyContinue
$clangpp = Get-Command clang++ -ErrorAction SilentlyContinue
$gpp = Get-Command g++ -ErrorAction SilentlyContinue

if ($vcvars) {
    $vcvarsPath = $vcvars.FullName
    $compile = "cl /nologo /EHsc /std:c++17 /O2 /W3 /Fe:pf_controller_test.exe pf_plan.cpp pf_plan_apply.cpp pf_controller.cpp pf_controller_test.cpp"
    $bat = @("@echo off",
             "call `"$vcvarsPath`" >NUL 2>&1",
             "pushd `"$here`"",
             $compile,
             "if ERRORLEVEL 1 exit /b 1",
             "pf_controller_test.exe",
             "set RC=%ERRORLEVEL%",
             "popd",
             "exit /b %RC%") -join "`r`n"
    $batFile = Join-Path $env:TEMP "pf_controller_build.bat"
    Set-Content -Path $batFile -Value $bat -Encoding ASCII
    if ($ShowCmd) {
        Write-Host "cmd: `"$vcvarsPath`" && cl /nologo /EHsc /std:c++17 /O2 /Fe:pf_controller_test.exe pf_plan.cpp pf_controller.cpp pf_controller_test.cpp"
    }
    $out = & cmd.exe /c $batFile 2>&1
    $rc = $LASTEXITCODE
    $out | ForEach-Object { Write-Host "  $_" }
    if ($rc -eq 0) { Write-Host "controller tests PASS" -ForegroundColor Green }
    exit $rc
}

$tool = $null; $args = $null
if ($clOnPath) {
    $tool = $clOnPath.Source
    $args = @("/nologo", "/EHsc", "/std:c++17", "/O2", "/W3", "/Fe:pf_controller_test.exe", "pf_plan.cpp", "pf_plan_apply.cpp", "pf_controller.cpp", "pf_controller_test.cpp")
} elseif ($clangpp) {
    $tool = $clangpp.Source
    $args = @("-std=c++17", "-O2", "-Wall", "pf_plan.cpp", "pf_plan_apply.cpp", "pf_controller.cpp", "pf_controller_test.cpp", "-o", "pf_controller_test.exe")
} elseif ($gpp) {
    $tool = $gpp.Source
    $args = @("-std=c++17", "-O2", "-Wall", "pf_plan.cpp", "pf_plan_apply.cpp", "pf_controller.cpp", "pf_controller_test.cpp", "-o", "pf_controller_test.exe")
} else {
    Write-Host @"
No C++ toolchain found. Any of these works, then re-run .\build_controller.ps1:
  1. cl.exe via vcvars64.bat (Visual Studio), e.g.:
     & "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" ; then run this script (cl.exe auto-detected)
  2. clang++ -std=c++17 -O2 pf_plan.cpp pf_controller.cpp pf_controller_test.cpp -o pf_controller_test.exe
  3. g++    -std=c++17 -O2 pf_plan.cpp pf_controller.cpp pf_controller_test.cpp -o pf_controller_test.exe
"@ -ForegroundColor Yellow
    exit 3
}

if ($ShowCmd) { Write-Host ("cmd: " + (Get-Item $tool).Source + " " + ($args -join " ")) }
Push-Location $here
try {
    & $tool @args 2>&1 | ForEach-Object { Write-Host "  $_" }
    if ($LASTEXITCODE -ne 0) { Write-Host "compile FAILED" -ForegroundColor Red; exit 1 }
} finally { Pop-Location }

Push-Location $here
try {
    & $exe
    $rc = $LASTEXITCODE
} finally { Pop-Location }
if ($rc -eq 0) { Write-Host "controller tests PASS" -ForegroundColor Green }
exit $rc
