# Builds ratemytv.apk with the SDK tools directly: no Gradle, no Android Studio.
#
#   powershell -ExecutionPolicy Bypass -File app\build.ps1
#
# Needs: a JDK (javac/keytool on PATH) and the Android SDK build-tools + android.jar.
# Override the SDK location with $env:ANDROID_HOME if it is not in the default place.

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
Set-Location $root

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt = Get-ChildItem "$sdk\build-tools" -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending |
        Where-Object { Test-Path "$($_.FullName)\aapt2.exe" } | Select-Object -First 1
if (-not $bt) { throw "no build-tools with aapt2 under $sdk" }
$bt = $bt.FullName

$platform = Get-ChildItem "$sdk\platforms" -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending |
        Where-Object { Test-Path "$($_.FullName)\android.jar" } | Select-Object -First 1
if (-not $platform) { throw "no platform android.jar under $sdk" }
$jar = "$($platform.FullName)\android.jar"

# Throwaway signing key, created on first build. Community builds do not share it.
$ks = "$root\debug.keystore"
if (-not (Test-Path $ks)) {
    Write-Host "== keytool (making debug.keystore)"
    & keytool -genkeypair -keystore $ks -storepass android -keypass android -alias androiddebugkey `
        -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

Remove-Item -Recurse -Force classes, dexout -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force classes, dexout | Out-Null

Write-Host "== aapt2 compile"
& "$bt\aapt2.exe" compile --dir res -o res.zip
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "== aapt2 link"
& "$bt\aapt2.exe" link -o app-unsigned.apk --manifest AndroidManifest.xml -I $jar res.zip `
    -A assets --min-sdk-version 24 --target-sdk-version 34
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

# Expand the globs ourselves: d8 does not take wildcards on Windows.
$sources = Get-ChildItem "src\dev\ratemytv\*.java" | ForEach-Object { $_.FullName }
if (-not $sources) { throw "no sources found" }

Write-Host "== javac"
& javac -encoding UTF-8 -source 8 -target 8 -bootclasspath $jar -d classes $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

$classes = Get-ChildItem "classes\dev\ratemytv\*.class" |
        ForEach-Object { "classes\dev\ratemytv\" + $_.Name }
if (-not $classes) { throw "no class files produced" }

Write-Host "== d8"
& "$bt\d8.bat" --lib $jar --release --output dexout $classes
if (-not (Test-Path dexout\classes.dex)) { throw "d8 failed" }

Write-Host "== package"
& python -c "import zipfile,shutil; shutil.copy(r'$root\app-unsigned.apk', r'$root\app-pkg.apk'); z=zipfile.ZipFile(r'$root\app-pkg.apk','a',zipfile.ZIP_DEFLATED); z.write(r'$root\dexout\classes.dex','classes.dex'); z.close()"
if ($LASTEXITCODE -ne 0) { throw "packaging failed" }

Write-Host "== sign"
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --out ratemytv.apk app-pkg.apk
if ($LASTEXITCODE -ne 0) { throw "sign failed" }

Remove-Item -Force res.zip, app-unsigned.apk, app-pkg.apk -ErrorAction SilentlyContinue
Write-Host "OK: $root\ratemytv.apk ($((Get-Item ratemytv.apk).Length) bytes)"
