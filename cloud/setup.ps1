# One-time setup for Stock Tracker live alerts. Run from this folder in PowerShell:
#   powershell -ExecutionPolicy Bypass -File setup.ps1
# Needs Node.js (https://nodejs.org, the LTS version) and a free Cloudflare account.
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

Write-Host "`n1/5  Installing Cloudflare's tool (wrangler)..." -ForegroundColor Cyan
npm install --no-fund --no-audit

Write-Host "`n2/5  Signing in to Cloudflare (a browser window opens; click Allow)..." -ForegroundColor Cyan
npx wrangler login

Write-Host "`n3/5  Creating the small database that remembers what was already sent..." -ForegroundColor Cyan
$null = npx wrangler d1 create stock-tracker-alerts 2>&1   # fails harmlessly if it already exists
$dbs = (npx wrangler d1 list --json) | Out-String | ConvertFrom-Json
$db = $dbs | Where-Object { $_.name -eq "stock-tracker-alerts" } | Select-Object -First 1
if (-not $db) { throw "Couldn't create the database. Run 'npx wrangler d1 create stock-tracker-alerts' to see why." }
(Get-Content wrangler.toml) -replace 'database_id = ".*"', "database_id = `"$($db.uuid)`"" | Set-Content wrangler.toml

Write-Host "`n4/5  Starting it (if asked to pick a workers.dev name, any name is fine)..." -ForegroundColor Cyan
npx wrangler deploy

Write-Host "`n5/5  Your settings" -ForegroundColor Cyan
$ua = Read-Host "Your name and email (the SEC requires this to identify who's checking; it's only sent to sec.gov)"
$topic = "stocktracker-" + -join ((48..57) + (97..122) | Get-Random -Count 20 | ForEach-Object { [char]$_ })
$ua | npx wrangler secret put SEC_USER_AGENT
$topic | npx wrangler secret put NTFY_TOPIC
$bargo = Read-Host "Bargo Congress key (optional, press Enter to skip; allows more Congress checks)"
if ($bargo) { $bargo | npx wrangler secret put BARGO_KEY }

Write-Host "`nDone! Now on your phone:" -ForegroundColor Green
Write-Host "  1. Install the free 'ntfy' app (App Store or Google Play)."
Write-Host "  2. Tap + (Subscribe to topic) and enter exactly:"
Write-Host "        $topic" -ForegroundColor Yellow
Write-Host "  3. Within a minute you'll get 'Stock Tracker live alerts are on'."
Write-Host "`nKeep that topic name private: anyone who knows it can read your alerts."
Set-Content -Path "my-ntfy-topic.txt" -Value $topic
Write-Host "(It's also saved in my-ntfy-topic.txt in this folder.)"
