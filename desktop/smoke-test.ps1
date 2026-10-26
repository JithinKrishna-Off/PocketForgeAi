param([string]$Configuration = "Debug")

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host "== Building test projects =="
dotnet build (Join-Path $root "tools\MockPhone\MockPhone.csproj") -c $Configuration -v q --nologo
if ($LASTEXITCODE -ne 0) { throw "MockPhone build failed" }
dotnet build (Join-Path $root "tools\SelfTest\SelfTest.csproj") -c $Configuration -v q --nologo
if ($LASTEXITCODE -ne 0) { throw "SelfTest build failed" }

$mock = Get-ChildItem (Join-Path $root "tools\MockPhone\bin") -Recurse -Filter "MockPhone.exe" |
    Where-Object { $_.FullName -like "*$Configuration*" } | Select-Object -First 1
if (-not $mock) { throw "MockPhone.exe not found" }

$port = 17851 + (Get-Random -Maximum 600)
Write-Host ("== Starting MockPhone on port {0} ==" -f $port)
$proc = Start-Process -FilePath $mock.FullName -ArgumentList "--port", "$port" -PassThru -WindowStyle Hidden
try {
    $ready = $false
    foreach ($i in 1..50) {
        try {
            $h = Invoke-WebRequest -Uri ("http://127.0.0.1:{0}/health" -f $port) -UseBasicParsing -TimeoutSec 2
            if ($h.StatusCode -eq 200) { $ready = $true; break }
        } catch { Start-Sleep -Milliseconds 200 }
    }
    if (-not $ready) { throw "MockPhone did not become ready" }
    Write-Host "MockPhone ready"

    $self = Get-ChildItem (Join-Path $root "tools\SelfTest\bin") -Recurse -Filter "SelfTest.dll" |
        Where-Object { $_.FullName -like "*$Configuration*" } | Select-Object -First 1
    if (-not $self) { throw "SelfTest.dll not found" }
    Write-Host "== Running SelfTest =="
    & dotnet $self.FullName
    $code = $LASTEXITCODE
    Write-Host ("== SelfTest exit code: {0} ==" -f $code)
    exit $code
} finally {
    if ($proc -and -not $proc.HasExited) {
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    }
}
