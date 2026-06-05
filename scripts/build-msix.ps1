param(
  [string]$Version,
  [string]$PackageName      = "TBTechs.UIScopeDesktopAndroidUIInspector",
  [string]$Publisher        = "CN=E08824C8-6F22-4DC2-8025-DD8C707E2BE9",
  [string]$PublisherDisplay = "TBTechs"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Write-Host "==> Building MSIX for UIScope $Version"
Write-Host "    Package : $PackageName"
Write-Host "    Publisher: $Publisher"

# ── 1. Locate makeappx.exe from Windows SDK ───────────────────────────────────
$makeappx = Get-ChildItem "C:\Program Files (x86)\Windows Kits\10\bin" `
              -Filter "makeappx.exe" -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match "x64" } |
            Select-Object -First 1 -ExpandProperty FullName

if (-not $makeappx) { throw "makeappx.exe not found — Windows SDK not installed?" }
Write-Host "==> makeappx: $makeappx"

# ── 2. Locate app-image (created as a side-effect of packageMsi / packageExe) ─
$candidates = @(
  "uiscope\app\build\compose\binaries\main-release\app\UIScope",
  "uiscope\app\build\compose\binaries\main\app\UIScope"
)
$appImage = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1

if (-not $appImage) {
  Write-Host "==> app-image not found in standard paths, running createDistributable..."
  Push-Location uiscope
  .\gradlew.bat :app:createDistributable --no-daemon
  Pop-Location
  $appImage = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $appImage) { throw "app-image still missing after createDistributable" }
Write-Host "==> app-image: $appImage"

# ── 3. Stage contents ─────────────────────────────────────────────────────────
$stage = "msix-stage"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null
Copy-Item -Path "$appImage\*" -Destination $stage -Recurse -Force

# ── 4. Generate scaled icon assets via System.Drawing ────────────────────────
$assetsDir = "$stage\Assets"
New-Item -ItemType Directory -Force -Path $assetsDir | Out-Null
$srcIcon   = "uiscope\app\src\main\resources\icon.png"

Add-Type -AssemblyName System.Drawing
foreach ($spec in @(
  [PSCustomObject]@{ Size = 44;  Name = "Square44x44Logo.png" },
  [PSCustomObject]@{ Size = 150; Name = "Square150x150Logo.png" },
  [PSCustomObject]@{ Size = 50;  Name = "StoreLogo.png" }
)) {
  $src = [System.Drawing.Image]::FromFile((Resolve-Path $srcIcon).Path)
  $bmp = New-Object System.Drawing.Bitmap($spec.Size, $spec.Size)
  $g   = [System.Drawing.Graphics]::FromImage($bmp)
  $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $g.DrawImage($src, 0, 0, $spec.Size, $spec.Size)
  $bmp.Save("$assetsDir\$($spec.Name)", [System.Drawing.Imaging.ImageFormat]::Png)
  $g.Dispose(); $bmp.Dispose(); $src.Dispose()
  Write-Host "    icon resized: $($spec.Name) ($($spec.Size)px)"
}

# ── 5. Write AppxManifest.xml ────────────────────────────────────────────────
$msixVersion = "$Version.0"

$xml  = '<?xml version="1.0" encoding="utf-8"?>'
$xml += '<Package'
$xml += '  xmlns="http://schemas.microsoft.com/appx/manifest/foundation/windows10"'
$xml += '  xmlns:uap="http://schemas.microsoft.com/appx/manifest/uap/windows10"'
$xml += '  xmlns:rescap="http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities">'
$xml += '  <Identity'
$xml += "    Name=`"$PackageName`""
$xml += "    Publisher=`"$Publisher`""
$xml += "    Version=`"$msixVersion`""
$xml += '    ProcessorArchitecture="x64" />'
$xml += '  <Properties>'
$xml += '    <DisplayName>UIScope: Desktop &amp; Android UI Inspector</DisplayName>'
$xml += "    <PublisherDisplayName>$PublisherDisplay</PublisherDisplayName>"
$xml += '    <Logo>Assets\StoreLogo.png</Logo>'
$xml += '  </Properties>'
$xml += '  <Dependencies>'
$xml += '    <TargetDeviceFamily Name="Windows.Desktop" MinVersion="10.0.17763.0" MaxVersionTested="10.0.22621.0" />'
$xml += '  </Dependencies>'
$xml += '  <Resources><Resource Language="en-us" /></Resources>'
$xml += '  <Applications>'
$xml += '    <Application Id="UIScope" Executable="UIScope.exe" EntryPoint="Windows.FullTrustApplication">'
$xml += '      <uap:VisualElements'
$xml += '        DisplayName="UIScope: Desktop &amp; Android UI Inspector"'
$xml += '        Description="See what your UI is made of. Live inspection of Android and Windows UI trees."'
$xml += '        BackgroundColor="transparent"'
$xml += '        Square150x150Logo="Assets\Square150x150Logo.png"'
$xml += '        Square44x44Logo="Assets\Square44x44Logo.png" />'
$xml += '    </Application>'
$xml += '  </Applications>'
$xml += '  <Capabilities>'
$xml += '    <rescap:Capability Name="runFullTrust" />'
$xml += '  </Capabilities>'
$xml += '</Package>'

$xml | Set-Content "$stage\AppxManifest.xml" -Encoding UTF8NoBOM
Write-Host "==> AppxManifest.xml written (v$msixVersion)"

# ── 6. Pack MSIX ─────────────────────────────────────────────────────────────
$outDir  = "msix-out"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$outFile = "$outDir\UIScope-$Version-x64.msix"

Write-Host "==> Packing MSIX..."
& $makeappx pack /d $stage /p $outFile /nv
if ($LASTEXITCODE -ne 0) { throw "makeappx failed (exit $LASTEXITCODE)" }

$sizeMB = [Math]::Round((Get-Item $outFile).Length / 1MB, 1)
Write-Host "==> Done: $outFile ($sizeMB MB)"
