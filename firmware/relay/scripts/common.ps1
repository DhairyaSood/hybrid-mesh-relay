# Shared helpers for flash.ps1 and monitor.ps1. Not meant to be run directly.

$ProjectDir = Split-Path -Parent $PSScriptRoot
$ValidEnvs = @('bridge', 'lora-ping', 'radiodiag')
# USB-serial chips found on ESP32 DevKit boards (CP210x, CH340/CH9102, FTDI).
$UsbSerialPattern = 'cp210|ch340|ch910|ch34|usb-serial|usb serial|uart|10c4:ea60|1a86:|0403:6001'

function Fail([string]$Message) {
    Write-Host "ERROR: $Message" -ForegroundColor Red
    exit 1
}

# Returns the PlatformIO CLI: $env:PIO, then PATH, then the standard install location.
function Find-Pio {
    if ($env:PIO -and (Test-Path $env:PIO)) { return $env:PIO }
    foreach ($name in @('pio', 'platformio')) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue
        if ($cmd) { return $cmd.Source }
    }
    $core = if ($env:PLATFORMIO_CORE_DIR) { $env:PLATFORMIO_CORE_DIR } else { Join-Path $env:USERPROFILE '.platformio' }
    $candidate = Join-Path $core 'penv\Scripts\pio.exe'
    if (Test-Path $candidate) { return $candidate }
    Fail "PlatformIO not found. Install the PlatformIO extension in VS Code (it creates %USERPROFILE%\.platformio\penv\Scripts\pio.exe), or: pip install platformio"
}

# Returns the given port, or the only ESP32 USB-serial port that is plugged in.
function Find-Port([string]$Pio, [string]$Port, [string]$EnvName) {
    if ($Port) { return $Port }

    $json = & $Pio device list --json-output 2>$null
    $devices = @()
    if ($json) { $devices = @($json | Out-String | ConvertFrom-Json) }
    $esp = @($devices | Where-Object {
        ("$($_.description) $($_.hwid)").ToLower() -match $UsbSerialPattern
    } | ForEach-Object { $_.port } | Select-Object -Unique)

    if ($esp.Count -eq 0) {
        Write-Host "ERROR: no ESP32 board found." -ForegroundColor Red
        Write-Host "  - Plug the board in with a DATA USB cable (some cables are charge-only)."
        Write-Host "  - Install the USB driver: CP210x (Silicon Labs) or CH340 (WCH), then replug."
        Write-Host "  - Check Device Manager > Ports (COM & LPT)."
        Write-Host "  Ports PlatformIO can see:"
        & $Pio device list
        exit 1
    }
    if ($esp.Count -gt 1) {
        Write-Host "More than one ESP32 board is plugged in. Pick one and pass it as the port:" -ForegroundColor Yellow
        $devices | Where-Object { $esp -contains $_.port } | ForEach-Object { Write-Host "  $($_.port)  $($_.description)" }
        $example = if ($EnvName) { $EnvName } else { 'bridge' }
        Write-Host "Example: .\scripts\flash.ps1 $example $($esp[0])"
        exit 1
    }
    return $esp[0]
}
