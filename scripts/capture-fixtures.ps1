<#
UIScope fixture capture (plan P0-7, Appendix K "Round 0"). Windows (PowerShell 5.1 or 7).

Saves the RAW output of `adb exec-out uiautomator dump /dev/stdout` and `adb exec-out screencap -p` for a few screens.
It calls adb directly and never any UIScope code, so the fixtures cannot depend on the code they will test.
Output bytes are copied from adb's stdout stream untouched (PowerShell's `>` would re-encode them and corrupt the PNG).

Usage:  powershell -ExecutionPolicy Bypass -File scripts\capture-fixtures.ps1 [-Adb C:\path\to\adb.exe] [-OutDir captures\my-run] [-Serial <serial>]

PRIVACY: dumps and screenshots contain everything visible on the screen. Capture screens with no personal data
(use test accounts / empty chats), and look at the files before you share them.
#>
param(
    [string]$Adb = "adb",
    [string]$OutDir = "",
    [string]$Serial = $env:ANDROID_SERIAL
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrEmpty($OutDir)) { $OutDir = "captures\fixtures-" + (Get-Date -Format "yyyyMMdd-HHmmss") }

function Start-Adb([string[]]$AdbArgs) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Adb
    $psi.Arguments = ($AdbArgs -join " ")
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    return [System.Diagnostics.Process]::Start($psi)
}

# Runs adb and returns its stdout as text.
function Invoke-Adb([string[]]$AdbArgs) {
    $p = Start-Adb $AdbArgs
    $errTask = $p.StandardError.ReadToEndAsync()
    $text = $p.StandardOutput.ReadToEnd()
    $p.WaitForExit()
    return $text
}

# Runs adb and writes its stdout to $Path byte for byte. Returns the exit code.
function Save-AdbOutput([string[]]$AdbArgs, [string]$Path) {
    $p = Start-Adb $AdbArgs
    $errTask = $p.StandardError.ReadToEndAsync()
    $fs = [System.IO.File]::Create($Path)
    try { $p.StandardOutput.BaseStream.CopyTo($fs) } finally { $fs.Dispose() }
    $p.WaitForExit()
    return $p.ExitCode
}

try { $null = Invoke-Adb @("version") } catch {
    Write-Host "ERROR: adb not found ('$Adb'). Install Android platform-tools and add adb to PATH, or pass -Adb C:\path\to\adb.exe" -ForegroundColor Red
    exit 1
}
$null = Invoke-Adb @("start-server")

$attached = @()
foreach ($line in (Invoke-Adb @("devices")) -split "`r?`n") {
    $parts = $line -split "\s+"
    if ($parts.Count -ge 2 -and $parts[1] -eq "device") { $attached += $parts[0] }
}
if ($attached.Count -eq 0) {
    Write-Host "ERROR: no authorised device found. Turn on USB debugging, connect the phone, accept the 'Allow USB debugging?' prompt, then run this script again." -ForegroundColor Red
    exit 1
}
if ($attached.Count -gt 1 -and [string]::IsNullOrEmpty($Serial)) {
    Write-Host "ERROR: $($attached.Count) devices attached. Re-run with -Serial <serial>. Attached: $($attached -join ', ')" -ForegroundColor Red
    exit 1
}
if ([string]::IsNullOrEmpty($Serial)) { $Serial = $attached[0] }

function Adb-Device([string[]]$AdbArgs) { return Invoke-Adb (@("-s", $Serial) + $AdbArgs) }
function Get-Prop([string]$Name) { return (Adb-Device @("shell", "getprop", $Name)).Trim() }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$model = Get-Prop "ro.product.model"
$maker = Get-Prop "ro.product.manufacturer"
$release = Get-Prop "ro.build.version.release"
$sdk = Get-Prop "ro.build.version.sdk"
$size = ((Adb-Device @("shell", "wm", "size")).Trim() -split "`r?`n")[-1]
$density = ((Adb-Device @("shell", "wm", "density")).Trim() -split "`r?`n")[-1]
Write-Host "Device: $maker $model, Android $release (API $sdk), $size, $density"
Write-Host "Output: $OutDir"
Write-Host ""

$names = @("settings", "compose", "webview", "long-list", "dialog")
$prompts = @(
    "Open the Android Settings app on its main screen.",
    "Open a Jetpack Compose app (skip if you have none).",
    "Open a web page inside an app that uses a WebView (a browser app is NOT a WebView).",
    "Open a long scrolling list (for example Contacts with several entries, or a settings list).",
    "Open any app and trigger a dialog over a screen (for example a permission prompt)."
)

$captured = @()
for ($i = 0; $i -lt $names.Count; $i++) {
    $name = $names[$i]
    Write-Host ("[{0}/{1}] {2}" -f ($i + 1), $names.Count, $prompts[$i])
    $answer = Read-Host "    Press Enter when the screen is ready (or type s and Enter to skip)"
    if ($answer -eq "s" -or $answer -eq "S") { Write-Host "    skipped"; Write-Host ""; continue }

    $dumpFile = Join-Path $OutDir "$name.dump.txt"
    $pngFile = Join-Path $OutDir "$name.png"
    $code = Save-AdbOutput (@("-s", $Serial, "exec-out", "uiautomator", "dump", "/dev/stdout")) $dumpFile
    if ($code -ne 0) { Write-Host "    WARNING: dump command failed (exit $code). The file is kept for diagnosis." -ForegroundColor Yellow }
    $code = Save-AdbOutput (@("-s", $Serial, "exec-out", "screencap", "-p")) $pngFile
    if ($code -ne 0) { Write-Host "    WARNING: screencap command failed (exit $code). The file is kept for diagnosis." -ForegroundColor Yellow }

    $dumpText = [System.IO.File]::ReadAllText($dumpFile)
    if ($dumpText.Contains("<hierarchy")) {
        $nodes = ([regex]::Matches($dumpText, "<node ")).Count
        Write-Host ("    dump: {0} bytes, {1} nodes" -f (Get-Item $dumpFile).Length, $nodes)
    } else {
        Write-Host "    WARNING: no <hierarchy> in the dump (screen secure/locked, or the tool printed an error). Raw output kept." -ForegroundColor Yellow
    }
    $head = [System.IO.File]::ReadAllBytes($pngFile) | Select-Object -First 4
    if ($head.Count -lt 4 -or $head[1] -ne 0x50 -or $head[2] -ne 0x4E -or $head[3] -ne 0x47) {
        Write-Host "    WARNING: the screenshot is not a PNG (a secure app blocks screenshots). Raw output kept." -ForegroundColor Yellow
    }
    $captured += $name
    Write-Host ""
}

$adbVersion = ((Invoke-Adb @("version")) -split "`r?`n")[0]
$readme = @(
    "UIScope fixture capture",
    "Date (UTC):      " + (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ"),
    "Device:          $maker $model",
    "Android version: $release (API $sdk)",
    "Screen:          $size, $density",
    "adb:             $adbVersion",
    "Command (dump):  adb exec-out uiautomator dump /dev/stdout",
    "Command (shot):  adb exec-out screencap -p",
    "Screens:         " + ($(if ($captured.Count -gt 0) { $captured -join " " } else { "none" })),
    "",
    "Files are raw adb output, unmodified. They contain on-screen text: review before sharing."
) -join "`n"
[System.IO.File]::WriteAllText((Join-Path $OutDir "README.txt"), $readme + "`n", (New-Object System.Text.UTF8Encoding($false)))

Write-Host "Done. Hand the folder '$OutDir' to the agent (it contains README.txt with the device details)."
