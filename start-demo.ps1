# =============================================================================
# Starts PolicyGuard for a live demo: ONE process, ONE address -> http://localhost:8081
#
#   .\start-demo.ps1              build the UI into the backend, then start
#   .\start-demo.ps1 -SkipUiBuild start without rebuilding the UI (faster)
#   .\start-demo.ps1 -WithOllama  also start the local offline LLM (CPU mode)
# =============================================================================
param([switch]$SkipUiBuild, [switch]$WithOllama)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (-not (Test-Path .env)) { throw ".env is missing - copy .env.example to .env and fill in DATABASE_URL and GROQ_API_KEY" }

if (-not $SkipUiBuild) {
    Write-Host "Building the React UI..." -ForegroundColor Cyan
    Push-Location frontend
    if (-not (Test-Path node_modules)) { npm install --no-audit --no-fund }
    npm run build
    Pop-Location
    # Spring Boot serves everything under src/main/resources/static (gitignored build output)
    Remove-Item -Recurse -Force src\main\resources\static -ErrorAction SilentlyContinue
    Copy-Item -Recurse frontend\dist src\main\resources\static
}

if ($WithOllama) {
    $ollama = Join-Path $env:LOCALAPPDATA "Programs\Ollama\ollama.exe"
    if ((Test-Path $ollama) -and -not (Get-Process -Name ollama -ErrorAction SilentlyContinue)) {
        # This laptop's NVIDIA driver is too old for Ollama's GPU build, so force CPU mode.
        $env:CUDA_VISIBLE_DEVICES = '-1'
        $env:OLLAMA_LLM_LIBRARY = 'cpu'
        Start-Process $ollama -ArgumentList 'serve' -WindowStyle Hidden
        Write-Host "Started Ollama (CPU mode) as the offline fallback" -ForegroundColor Cyan
    }
}

Write-Host ""
Write-Host "Starting PolicyGuard. When you see 'Started PolicyGuardApplication', open:" -ForegroundColor Green
Write-Host "    http://localhost:8081" -ForegroundColor Green
Write-Host "Need a public link for someone else? In a second terminal run: .\share-link.ps1" -ForegroundColor Green
Write-Host ""
.\mvnw.cmd -q spring-boot:run
