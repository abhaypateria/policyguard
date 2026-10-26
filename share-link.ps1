# =============================================================================
# Creates a TEMPORARY public https link to the PolicyGuard running on this laptop,
# using a Cloudflare "quick tunnel" (free, no account). Start the app first (start-demo.ps1).
#
# Look for a line like:   https://something-random.trycloudflare.com
# The link works only while this window is open; press Ctrl+C to close it.
# =============================================================================
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# Anyone with the link can use the app, so write operations must be protected first.
if (-not (Select-String -Path .env -Pattern '^ADMIN_KEY=.+' -Quiet)) {
    throw "Set ADMIN_KEY=<long random text> in .env (and restart the app) before sharing a public link"
}
try { Invoke-WebRequest http://localhost:8081/health -UseBasicParsing -TimeoutSec 5 | Out-Null }
catch { throw "PolicyGuard is not running on http://localhost:8081 - start it with .\start-demo.ps1 first" }

$cf = (Get-Command cloudflared -ErrorAction SilentlyContinue).Source
if (-not $cf) {
    $candidates = @("C:\Program Files (x86)\cloudflared\cloudflared.exe", "C:\Program Files\cloudflared\cloudflared.exe",
                    (Join-Path $env:LOCALAPPDATA "Microsoft\WinGet\Links\cloudflared.exe"))
    $cf = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $cf) { throw "cloudflared not found - install it with: winget install --id Cloudflare.cloudflared" }

Write-Host "Opening a temporary public link... look for the https://....trycloudflare.com line below." -ForegroundColor Green
& $cf tunnel --no-autoupdate --url http://localhost:8081
