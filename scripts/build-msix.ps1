# build-msix.ps1 (plan P8-2): lays the jpackage app image into an MSIX package and packs it with MakeAppx.
#
# Usage:
#   pwsh scripts/build-msix.ps1 -AppImage <dir> -Version 1.0.0 -Out dist\UIScope-1.0.0-x64.msix
#   ... -TestSign            creates a throwaway self-signed certificate and signs the package for
#                            local sideload/WACK runs ONLY. Never published, never committed.
#   ... -RunWack             runs the Windows App Certification Kit when appcert.exe is found.
#
# The package that reaches Partner Center is built WITHOUT -TestSign: the Store signs it. Do not change the
# Identity Name/Publisher once Partner Center has reserved them (packaging/msix/AppxManifest.xml).
param(
    [Parameter(Mandatory = $true)][string] $AppImage,
    [Parameter(Mandatory = $true)][string] $Version,
    [Parameter(Mandatory = $true)][string] $Out,
    [switch] $TestSign,
    [switch] $RunWack
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot

if (-not (Test-Path (Join-Path $AppImage "UIScope.exe"))) {
    throw "AppImage '$AppImage' does not look like a jpackage app image (no UIScope.exe). Run :app:createDistributable first."
}

# The Store requires the fourth version segment to be 0.
if ($Version -notmatch "^\d+\.\d+\.\d+$") { throw "Version must be MAJOR.MINOR.PATCH, got '$Version'" }
$packageVersion = "$Version.0"

# Locate the Windows SDK's MakeAppx (and signtool for -TestSign).
$kitsRoot = "C:\Program Files (x86)\Windows Kits\10\bin"
$makeappx = Get-ChildItem $kitsRoot -Recurse -Filter "makeappx.exe" -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match "x64" } |
    Sort-Object FullName -Descending |
    Select-Object -First 1
if ($null -eq $makeappx) { throw "MakeAppx.exe not found under $kitsRoot - install the Windows SDK" }
Write-Host "Using $($makeappx.FullName)"

$work = Join-Path $env:TEMP ("uiscope-msix-" + [guid]::NewGuid().ToString("N"))
$layout = Join-Path $work "layout"
New-Item -ItemType Directory -Path $layout -Force | Out-Null

# 1. The app image becomes the package payload, plus the manifest and the tile assets.
Copy-Item -Path (Join-Path $AppImage "*") -Destination $layout -Recurse -Force
New-Item -ItemType Directory -Path (Join-Path $layout "assets") -Force | Out-Null
Copy-Item -Path (Join-Path $repoRoot "branding\tile-71x71.png") -Destination (Join-Path $layout "assets\Tile71x71.png") -Force
Copy-Item -Path (Join-Path $repoRoot "branding\tile-150x150.png") -Destination (Join-Path $layout "assets\Tile150x150.png") -Force
Copy-Item -Path (Join-Path $repoRoot "branding\app-tile-300x300.png") -Destination (Join-Path $layout "assets\Tile300x300.png") -Force

$manifest = Get-Content (Join-Path $repoRoot "packaging\msix\AppxManifest.xml") -Raw
$manifest = $manifest.Replace("@VERSION@", $packageVersion)
Set-Content -Path (Join-Path $layout "AppxManifest.xml") -Value $manifest -Encoding UTF8

# 2. Pack.
New-Item -ItemType Directory -Path (Split-Path -Parent $Out) -Force | Out-Null
& $makeappx.FullName pack /o /d $layout /p $Out
if ($LASTEXITCODE -ne 0) { throw "makeappx pack failed with $LASTEXITCODE" }
Write-Host "Packed $Out"

# 3. Optional throwaway test signature (sideload/WACK only - the Store package stays unsigned).
if ($TestSign) {
    $signtool = Get-ChildItem $kitsRoot -Recurse -Filter "signtool.exe" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match "x64" } |
        Sort-Object FullName -Descending |
        Select-Object -First 1
    if ($null -eq $signtool) { throw "signtool.exe not found - install the Windows SDK" }
    $cert = New-SelfSignedCertificate -Type Custom -Subject "CN=UIScope test signing, O=TBTechs, C=US" `
        -KeyUsage DigitalSignature -FriendlyName "UIScope throwaway test certificate" `
        -CertStoreLocation "Cert:\CurrentUser\My" `
        -TextExtension @("2.5.29.37={text}1.3.6.1.5.5.7.3.3", "2.5.29.19={text}")
    $password = ConvertTo-SecureString -String ("uiscope-" + [guid]::NewGuid().ToString("N")) -Force -AsPlainText
    $pfx = Join-Path $work "uiscope-test.pfx"
    Export-PfxCertificate -Cert $cert -FilePath $pfx -Password $password | Out-Null
    & $signtool.FullName sign /fd SHA256 /a /f $pfx /p $password.Password $Out
    if ($LASTEXITCODE -ne 0) { throw "signtool sign failed with $LASTEXITCODE" }
    Write-Host "Test-signed with throwaway certificate $($cert.Thumbprint) (local machine only - it is not exported or published)"
}

# 4. Optional Windows App Certification Kit run (needs a desktop session; CI runners may not have one).
if ($RunWack) {
    $appcert = Get-ChildItem $kitsRoot -Recurse -Filter "appcert.exe" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match "x64" } |
        Sort-Object FullName -Descending |
        Select-Object -First 1
    if ($null -eq $appcert) {
        Write-Warning "appcert.exe not found; skipping WACK"
    } else {
        $wackReport = Join-Path $work "wack"
        & $appcert.FullName reset
        & $appcert.FullName test -appxpackagePath $Out -reportoutputpath $wackReport
        Write-Host "WACK report directory: $wackReport"
    }
}

Write-Host "Done: $Out"
