# Cut a release from this machine: bump the manifest, build, sign, hash, tag, upload.
# Usage:  powershell -ExecutionPolicy Bypass -File app/release.ps1 -Version 1.2
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [switch]$NoPush
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot          # repo root (this script lives in app/)
$app = Join-Path $root "app"
$apk = Join-Path $app "ratemytv.apk"
$manifest = Join-Path $app "AndroidManifest.xml"

$dirty = git -C $root status --porcelain
if ($dirty) { throw "working tree is dirty - commit first so the tag matches the code" }

# versionCode has to increase or Android refuses the upgrade
$xml = [IO.File]::ReadAllText($manifest)
$code = 1
if ($xml -match 'android:versionCode="(\d+)"') { $code = [int]$Matches[1] + 1 }
$xml = [regex]::Replace($xml, 'android:versionCode="\d+"', "android:versionCode=`"$code`"")
$xml = [regex]::Replace($xml, 'android:versionName="[^"]*"', "android:versionName=`"$Version`"")
[IO.File]::WriteAllText($manifest, $xml)
Write-Host "versionName=$Version versionCode=$code"

if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "C:\tv\android-sdk" }
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $app "build.ps1")
if (-not (Test-Path $apk)) { throw "build produced no APK" }

$sha = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
Set-Content -Path (Join-Path $app "checksum.txt") -Value $sha -NoNewline -Encoding ascii
Write-Host "sha256=$sha"

# the README publishes the hash, so keep it truthful
$readme = Join-Path $root "README.md"
$md = [IO.File]::ReadAllText($readme)
$md = [regex]::Replace($md, '`[0-9a-f]{64}`', '`' + $sha + '`')
$md = [regex]::Replace($md, 'SHA-256 of v[0-9.]+:', "SHA-256 of v${Version}:")
[IO.File]::WriteAllText($readme, $md)

if ($NoPush) { Write-Host "NoPush set: stopped before commit/tag/upload"; exit 0 }

$notes = @'
Android TV (SDK 24+), no root. Built for JioHotstar - Prime Video already ships X-Ray.

Install:

    adb connect <TV_IP>:5555
    adb install -r ratemytv.apk
    adb shell pm grant dev.ratemytv android.permission.READ_TV_LISTINGS
    adb shell appops set dev.ratemytv SYSTEM_ALERT_WINDOW allow
    adb shell cmd notification allow_listener dev.ratemytv/dev.ratemytv.Watcher
    adb shell am start -n dev.ratemytv/.MainActivity

The three grants are required: without READ_TV_LISTINGS the TV Provider returns an
empty list and no error, without the overlay permission there is no card, and
without the listener there are no live titles.

Signed with a throwaway debug key - uninstall this build before installing one you
compiled yourself.
'@

git -C $root add -A
git -C $root -c user.email="nikhiltalreja@gmail.com" -c user.name="Nikhil Talreja" commit -q -m "Release v$Version"
git -C $root tag -a "v$Version" -m "v$Version"
git -C $root push origin main
git -C $root push origin "v$Version"

$probe = Join-Path $root "tools\probe.apk"
$assets = @($apk, (Join-Path $app "checksum.txt"))
if (Test-Path $probe) { $assets += $probe }

gh release create "v$Version" $assets `
    --title "Rate My TV $Version" --notes "$notes"
Write-Host "released v$Version"
