# Open the serial monitor only (no build, no flash).
#
#   .\scripts\monitor.ps1           auto-detect the board
#   .\scripts\monitor.ps1 COM6      pick the port yourself
param([Parameter(Position = 0)][string]$Port)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

$pio = Find-Pio
$Port = Find-Port $pio $Port ''

Write-Host ">> Serial monitor on $Port at 115200 (Ctrl+C to quit)" -ForegroundColor Cyan
& $pio device monitor -d $ProjectDir -p $Port -b 115200
