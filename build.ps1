param(
    [Parameter(Mandatory)][string]$Sdk,
    [Parameter(Mandatory)][string]$Jdk,
    [Parameter(Mandatory)][string]$BuildTools,
    [Parameter(Mandatory)][string]$OutputApk
)
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = $Jdk
$env:Path = "$Jdk\bin;$env:Path"
$tools = Join-Path $Sdk "build-tools\$BuildTools"
$platform = Join-Path $Sdk 'platforms\android-35\android.jar'
$build = Join-Path $PSScriptRoot 'build'
$classes = Join-Path $build 'classes'
$dex = Join-Path $build 'dex'
New-Item -ItemType Directory -Force $classes,$dex | Out-Null

function Invoke-BuildStep {
    param([string]$Program, [string[]]$Arguments)
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Build step failed: $Program ($LASTEXITCODE)" }
}

Invoke-BuildStep "$tools\aapt2.exe" @('compile','--dir',"$PSScriptRoot\res",'-o',"$build\resources.zip")
Invoke-BuildStep "$tools\aapt2.exe" @('link','-I',$platform,'--manifest',"$PSScriptRoot\AndroidManifest.xml",'--java',"$build\generated",'-o',"$build\unsigned.apk","$build\resources.zip")
Invoke-BuildStep "$Jdk\bin\javac.exe" @('-encoding','UTF-8','--release','8','-classpath',$platform,'-d',$classes,"$PSScriptRoot\Home.java","$build\generated\de\startpertap\launcher\R.java")
Invoke-BuildStep "$Jdk\bin\jar.exe" @('cf',"$build\classes.jar",'-C',$classes,'.')
Invoke-BuildStep "$Jdk\bin\java.exe" @('-cp',"$Sdk\cmdline-tools\lib\r8.jar",'com.android.tools.r8.D8','--min-api','29','--lib',$platform,'--output',$dex,"$build\classes.jar")
Push-Location $dex
try { Invoke-BuildStep "$tools\aapt.exe" @('add',"$build\unsigned.apk",'classes.dex') }
finally { Pop-Location }
Invoke-BuildStep "$tools\zipalign.exe" @('-f','4',"$build\unsigned.apk","$build\aligned.apk")
$key = Join-Path $build 'local-test.jks'
if (!(Test-Path -LiteralPath $key)) {
    Invoke-BuildStep "$Jdk\bin\keytool.exe" @('-genkeypair','-keystore',$key,'-storepass','android','-keypass','android','-alias','startpertap-test','-keyalg','RSA','-keysize','2048','-validity','3650','-dname','CN=StartPerTap Test')
}
Invoke-BuildStep "$tools\apksigner.bat" @('sign','--v4-signing-enabled','false','--ks',$key,'--ks-pass','pass:android','--key-pass','pass:android','--out',$OutputApk,"$build\aligned.apk")
Invoke-BuildStep "$tools\apksigner.bat" @('verify','--verbose',$OutputApk)
Invoke-BuildStep "$tools\aapt.exe" @('dump','badging',$OutputApk)
