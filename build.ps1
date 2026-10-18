<#
.SYNOPSIS
  Собирает SleepScreen.apk (Android 2.3, API 9) без Android Studio и Gradle и, по желанию, ставит на книгу.

.DESCRIPTION
  aapt (ресурсы, R.java) -> ECJ (Java 1.6 против android-2.3.1.jar) -> dx (classes.dex) -> aapt add -> zipalign ->
  apksigner (подпись v1 самоподписанным ключом) -> проверки. Результат: out\SleepScreen.apk.
  Нужны: Java 8+ (java, keytool), ecj-3.26.0.jar, dx-1.7.jar, android-2.3.1.jar, Android SDK build-tools (aapt, zipalign,
  apksigner). Где их взять: BUILDING.md.

.PARAMETER Tools       Папка с ecj-3.26.0.jar, dx-1.7.jar, android-2.3.1.jar (ищутся рекурсивно). По умолчанию .\tools
.PARAMETER BuildTools  Папка Android SDK build-tools (aapt.exe, zipalign.exe, lib\apksigner.jar; ищутся рекурсивно). По умолчанию .\tools
.PARAMETER Java        Путь к java.exe (по умолчанию из PATH).
.PARAMETER Keystore    Хранилище ключа подписи; если файла нет, создаётся новый самоподписанный. По умолчанию .\keystore\sleepscreen.jks
.PARAMETER Install     После сборки выполнить adb install -r (adb должен быть в PATH или указан в -Adb).
.PARAMETER Adb         Путь к adb.exe.
#>
[CmdletBinding()]
param(
    [string]$Tools = '',
    [string]$BuildTools = '',
    [string]$Java = '',
    [string]$Keystore = '',
    [switch]$Install,
    [string]$Adb = ''
)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $Tools) { $Tools = Join-Path $Root 'tools' }
if (-not $BuildTools) { $BuildTools = Join-Path $Root 'tools' }
if (-not $Keystore) { $Keystore = Join-Path $Root 'keystore\sleepscreen.jks' }

function Step([string]$m) { Write-Host ('[ ] ' + $m) }
function Fail([string]$m) { Write-Host ('[!] ' + $m) -ForegroundColor Red; exit 1 }
function Find-File([string]$dir, [string]$name) {
    if (-not (Test-Path $dir)) { return $null }
    $f = Get-ChildItem -Path $dir -Recurse -File -Filter $name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($f) { return $f.FullName } else { return $null }
}
function Run([string]$what, [string]$exe, [string[]]$arguments, [string]$workDir = '') {
    $o = [IO.Path]::GetTempFileName(); $e = [IO.Path]::GetTempFileName()
    try {
        $q = ($arguments | ForEach-Object { if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ } }) -join ' '
        $p = @{ FilePath = $exe; ArgumentList = $q; NoNewWindow = $true; Wait = $true; PassThru = $true; RedirectStandardOutput = $o; RedirectStandardError = $e }
        if ($workDir) { $p.WorkingDirectory = $workDir }
        $proc = Start-Process @p
        $out = [IO.File]::ReadAllText($o); $err = [IO.File]::ReadAllText($e)
        if ($proc.ExitCode -ne 0) { Write-Host $out; Write-Host $err; Fail ($what + ' не удалось (код ' + $proc.ExitCode + ')') }
        return $out + $err
    } finally { Remove-Item $o, $e -ErrorAction SilentlyContinue }
}

$app = Join-Path $Root 'app'
$ecj = Find-File $Tools 'ecj-3.26.0.jar'
$dx = Find-File $Tools 'dx-1.7.jar'
$androidJar = Find-File $Tools 'android-2.3.1.jar'
$aapt = Find-File $BuildTools 'aapt.exe'
$zipalign = Find-File $BuildTools 'zipalign.exe'
$apksigner = Find-File $BuildTools 'apksigner.jar'
$missing = @()
if (-not $ecj) { $missing += 'ecj-3.26.0.jar' }
if (-not $dx) { $missing += 'dx-1.7.jar' }
if (-not $androidJar) { $missing += 'android-2.3.1.jar' }
if (-not $aapt) { $missing += 'aapt.exe' }
if (-not $zipalign) { $missing += 'zipalign.exe' }
if (-not $apksigner) { $missing += 'apksigner.jar' }
if ($missing.Count -gt 0) { Fail ('не найдено: ' + ($missing -join ', ') + '. См. BUILDING.md (где скачать и куда положить).') }

if (-not $Java) { $c = Get-Command java -ErrorAction SilentlyContinue; if ($c) { $Java = $c.Source } else { Fail 'не найдена Java (нужна 8 или новее, java.exe в PATH или параметр -Java)' } }
$keytool = Join-Path (Split-Path -Parent $Java) 'keytool.exe'
if (-not (Test-Path $keytool)) { $c = Get-Command keytool -ErrorAction SilentlyContinue; if ($c) { $keytool = $c.Source } else { Fail 'не найден keytool (нужен JDK или JRE с keytool)' } }

$out = Join-Path $Root 'out'
if (Test-Path $out) { [IO.Directory]::Delete($out, $true) }
[void][IO.Directory]::CreateDirectory((Join-Path $out 'gen'))
[void][IO.Directory]::CreateDirectory((Join-Path $out 'classes'))

Step 'aapt: ресурсы и R.java'
[void](Run 'aapt package' $aapt @('package', '-f', '-m', '-J', (Join-Path $out 'gen'), '-M', (Join-Path $app 'AndroidManifest.xml'), '-S', (Join-Path $app 'res'), '-I', $androidJar, '-F', (Join-Path $out 'unsigned.apk')))

Step 'ECJ: компиляция Java (source/target 1.6)'
[void](Run 'ECJ' $Java @('-jar', $ecj, '-source', '1.6', '-target', '1.6', '-encoding', 'UTF-8', '-nowarn', '-cp', $androidJar, '-d', (Join-Path $out 'classes'), (Join-Path $app 'src'), (Join-Path $out 'gen')))

Step 'dx: classes.dex'
[void](Run 'dx' $Java @('-cp', $dx, 'com.android.dx.command.Main', '--dex', ('--output=' + (Join-Path $out 'classes.dex')), (Join-Path $out 'classes')))

Step 'добавляю classes.dex в apk'
[void](Run 'aapt add' $aapt @('add', 'unsigned.apk', 'classes.dex') $out)

Step 'zipalign'
[void](Run 'zipalign' $zipalign @('-f', '4', (Join-Path $out 'unsigned.apk'), (Join-Path $out 'aligned.apk')))

$pass = 'sleepscreen'
if (-not (Test-Path $Keystore)) {
    Step 'создаю ключ подписи (самоподписанный, один раз; храните файл, он нужен для обновлений приложения)'
    [void][IO.Directory]::CreateDirectory((Split-Path -Parent $Keystore))
    [void](Run 'keytool' $keytool @('-genkeypair', '-keystore', $Keystore, '-storepass', $pass, '-keypass', $pass, '-alias', 'sleepscreen', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=SleepScreen'))
}
Step 'apksigner: подпись (схема v1, нужна для Android 2.3)'
$final = Join-Path $out 'SleepScreen.apk'
[void](Run 'apksigner' $Java @('-jar', $apksigner, 'sign', '--ks', $Keystore, '--ks-pass', ('pass:' + $pass), '--key-pass', ('pass:' + $pass), '--ks-key-alias', 'sleepscreen', '--min-sdk-version', '9', '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'false', '--v3-signing-enabled', 'false', '--out', $final, (Join-Path $out 'aligned.apk')))

Step 'проверки'
$v = Run 'apksigner verify' $Java @('-jar', $apksigner, 'verify', '--verbose', '--min-sdk-version', '9', $final)
Write-Host ($v.Trim())
$z = Run 'zipalign -c' $zipalign @('-c', '-v', '4', $final)
if ($z -notmatch 'Verification successful') { Fail 'zipalign -c не прошёл' }
$md5 = (Get-FileHash $final -Algorithm MD5).Hash
Write-Host ('[+] готово: ' + $final + ' (' + (Get-Item $final).Length + ' байт, MD5 ' + $md5 + ')') -ForegroundColor Green

if ($Install) {
    if (-not $Adb) { $c = Get-Command adb -ErrorAction SilentlyContinue; if ($c) { $Adb = $c.Source } else { Fail 'adb не найден (PATH или параметр -Adb)' } }
    Step 'adb install -r'
    $r = Run 'adb install' $Adb @('install', '-r', $final)
    Write-Host $r.Trim()
    if ($r -notmatch 'Success') { Fail 'установка не удалась' }
    Write-Host '[+] приложение установлено на книгу' -ForegroundColor Green
}
