# Build, flash and open the serial monitor in one command.
#
#   .\scripts\flash.ps1 bridge              auto-detect the board
#   .\scripts\flash.ps1 lora-ping COM5      pick the port yourself
#   .\scripts\flash.ps1 radiodiag
#
# If PowerShell refuses to run scripts:
#   powershell -ExecutionPolicy Bypass -File .\scripts\flash.ps1 bridge
# Close any other serial monitor first: only one program can use the port.
# Stop the monitor with Ctrl+C.
param(
    [Parameter(Position = 0)][string]$EnvName,
    [Parameter(Position = 1)][string]$Port
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

if (-not $EnvName -or ($ValidEnvs -notcontains $EnvName)) {
    Fail "usage: .\scripts\flash.ps1 <bridge|lora-ping|radiodiag> [COMx]"
}

$pio = Find-Pio
$Port = Find-Port $pio $Port $EnvName

Write-Host ">> Flashing '$EnvName' to $Port (PlatformIO: $pio)" -ForegroundColor Cyan
& $pio run -d $ProjectDir -e $EnvName -t upload --upload-port $Port
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "ERROR: upload failed." -ForegroundColor Red
    Write-Host "  'Failed to connect to ESP32': run the command again and HOLD the BOOT button"
    Write-Host "  until you see 'Writing at 0x...', then let go."
    Write-Host "  'Access denied' / 'port busy': close other serial monitors (Arduino, VS Code, PuTTY)."
    exit 1
}

Write-Host ">> Opening serial monitor on $Port at 115200 (Ctrl+C to quit)" -ForegroundColor Cyan
Write-Host ">> No output? Press EN/RST on the board, or unplug and replug USB if it has no reset button."
& $pio device monitor -d $ProjectDir -p $Port -b 115200
