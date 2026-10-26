param([string]$Configuration = "Release")

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$appProj = Join-Path $root "app\PocketForgeDesktop.csproj"
$mcpProj = Join-Path $root "McpTools\McpTools.csproj"
$pubApp = Join-Path $root "publish\app"
$pubMcp = Join-Path $root "publish\mcp"

Write-Host "== Publishing PocketForgeDesktop ($Configuration, win-x64, self-contained) =="
dotnet publish $appProj -c $Configuration -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o $pubApp
if ($LASTEXITCODE -ne 0) { throw "app publish failed" }

dotnet publish $mcpProj -c $Configuration -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o $pubMcp
if ($LASTEXITCODE -ne 0) { throw "McpTools publish failed" }

# ToolLoop spawns McpTools.exe as a child process - it must sit next to the app exe
Copy-Item (Join-Path $pubMcp "McpTools.exe") $pubApp -Force
Copy-Item (Join-Path $root "McpTools\tools.config.json") $pubApp -Force

$zip = Join-Path $root "PocketForgeDesktop-win-x64.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path (Join-Path $pubApp "*") -DestinationPath $zip
$hash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
$size = (Get-Item $zip).Length
Write-Host ""
Write-Host ("ZIP : {0}" -f $zip)
Write-Host ("SIZE: {0} bytes" -f $size)
Write-Host ("SHA256: {0}" -f $hash)
Write-Host "DONE"
