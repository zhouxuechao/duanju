$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
if (-not $env:JAVA_HOME -and (Test-Path -LiteralPath 'D:\idea\IntelliJ IDEA 2025.1.4.1\jbr\bin\javac.exe')) { $env:JAVA_HOME='D:\idea\IntelliJ IDEA 2025.1.4.1\jbr' }
if ($env:JAVA_HOME) { $env:Path="$env:JAVA_HOME\bin;$env:Path" }
Push-Location -LiteralPath 'frontend'
try {
    if (-not (Test-Path -LiteralPath 'node_modules')) { & npm.cmd ci }
    if ($LASTEXITCODE -eq 0) { & npm.cmd run build }
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally { Pop-Location }
& mvn -B -ntp verify
exit $LASTEXITCODE
