$ErrorActionPreference = "Stop"
$sdk = "C:\tv\android-sdk"
$bt = "$sdk\build-tools\34.0.0"
$jar = "$sdk\platforms\android-34\android.jar"
$root = "C:\tv\xray\probe"
Set-Location $root

Remove-Item -Recurse -Force classes, dexout -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force classes, dexout | Out-Null

Write-Host "== aapt2 link"
& "$bt\aapt2.exe" link -o probe-unsigned.apk --manifest AndroidManifest.xml -I $jar --min-sdk-version 24 --target-sdk-version 34
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "== javac"
& javac -source 8 -target 8 -bootclasspath $jar -d classes src\dev\xray\probe\ProbeActivity.java 2>&1 | ForEach-Object { $_ }
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "== d8"
& "$bt\d8.bat" --lib $jar --release --output dexout classes\dev\xray\probe\*.class 2>&1 | ForEach-Object { $_ }
if (-not (Test-Path dexout\classes.dex)) { throw "d8 failed" }

Write-Host "== package"
python -c "
import zipfile, shutil, os
shutil.copy(r'C:\tv\xray\probe\probe-unsigned.apk', r'C:\tv\xray\probe\probe-pkg.apk')
z = zipfile.ZipFile(r'C:\tv\xray\probe\probe-pkg.apk', 'a', zipfile.ZIP_DEFLATED)
z.write(r'C:\tv\xray\probe\dexout\classes.dex', 'classes.dex')
z.close()
print('packaged', os.path.getsize(r'C:\tv\xray\probe\probe-pkg.apk'))
"

Write-Host "== keystore + sign"
if (-not (Test-Path debug.keystore)) {
  & keytool -genkeypair -keystore debug.keystore -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" 2>&1 | Out-Null
}
& "$bt\apksigner.bat" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --out probe.apk probe-pkg.apk
if ($LASTEXITCODE -ne 0) { throw "sign failed" }

& "$bt\apksigner.bat" verify probe.apk
Write-Host "OK: $root\probe.apk ($((Get-Item probe.apk).Length) bytes)"
