param(
    [ValidateSet('Demo','Live','Prod')][string]$Mode = 'Demo',
    [string]$EnvFile = '',
    [int]$Port = 8080,
    [switch]$Build
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $taskRoot
if ($EnvFile) {
    $taskConfigPath = (Resolve-Path -LiteralPath $EnvFile).Path
    foreach ($taskLine in [System.IO.File]::ReadAllLines($taskConfigPath)) {
        if ($taskLine -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') {
            $taskName = $Matches[1]
            $taskValue = $Matches[2]
            if (($taskValue.StartsWith('"') -and $taskValue.EndsWith('"')) -or ($taskValue.StartsWith("'") -and $taskValue.EndsWith("'"))) { $taskValue = $taskValue.Substring(1,$taskValue.Length-2) }
            [Environment]::SetEnvironmentVariable($taskName,$taskValue,'Process')
        }
    }
}
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath "$env:JAVA_HOME\bin\javac.exe")) {
    $taskJdk = 'D:\idea\IntelliJ IDEA 2025.1.4.1\jbr'
    if (Test-Path -LiteralPath "$taskJdk\bin\javac.exe") { $env:JAVA_HOME = $taskJdk }
}
if ($env:JAVA_HOME) { $env:Path = "$env:JAVA_HOME\bin;$env:Path" }
if (-not $env:FFMPEG_PATH -and (Test-Path -LiteralPath 'D:\project\aimanju\node_modules\ffmpeg-static\ffmpeg.exe')) { $env:FFMPEG_PATH='D:\project\aimanju\node_modules\ffmpeg-static\ffmpeg.exe' }
$env:PORT = "$Port"
$env:SPRING_PROFILES_ACTIVE = switch ($Mode) { 'Demo' {'demo'} 'Live' {'demo,live'} 'Prod' {'prod'} }
if ($Build -or -not (Test-Path -LiteralPath 'target\drama-0.1.0-SNAPSHOT.jar')) {
    if ($Build -or -not (Test-Path -LiteralPath 'frontend\dist\index.html')) {
        Push-Location -LiteralPath 'frontend'
        try {
            if (-not (Test-Path -LiteralPath 'node_modules')) { & npm.cmd ci }
            if ($LASTEXITCODE -ne 0) { throw '界面依赖安装失败' }
            & npm.cmd run build
            if ($LASTEXITCODE -ne 0) { throw '界面构建失败' }
        } finally { Pop-Location }
    }
    & mvn -B -ntp package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw '构建失败' }
}
Write-Host "短剧工作室：http://127.0.0.1:$Port  模式：$Mode"
& java '-Dfile.encoding=UTF-8' -jar target\drama-0.1.0-SNAPSHOT.jar
exit $LASTEXITCODE
