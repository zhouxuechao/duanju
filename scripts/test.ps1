$ErrorActionPreference='Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
$taskIsolationNames=@('DRAMA_PROVIDER_MODE','VISUAL_REVIEWER','RUN_LIVE_PROVIDER_CANARY','RUN_LIVE_PIPELINE_CANARY','CANARY_RUNNER_PREFLIGHT_OK','CANARY_PIPELINE_RUNNER_PREFLIGHT_OK')
$taskOriginalEnvironment=@{}
foreach($taskName in $taskIsolationNames){$taskOriginalEnvironment[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
$taskExitCode=1
try {
    $env:DRAMA_PROVIDER_MODE='mock'
    $env:VISUAL_REVIEWER='fake'
    $env:RUN_LIVE_PROVIDER_CANARY='false'
    $env:RUN_LIVE_PIPELINE_CANARY='false'
    Remove-Item Env:CANARY_RUNNER_PREFLIGHT_OK -ErrorAction SilentlyContinue
    Remove-Item Env:CANARY_PIPELINE_RUNNER_PREFLIGHT_OK -ErrorAction SilentlyContinue
    if (-not $env:JAVA_HOME -and (Test-Path -LiteralPath 'D:\idea\IntelliJ IDEA 2025.1.4.1\jbr\bin\javac.exe')) { $env:JAVA_HOME='D:\idea\IntelliJ IDEA 2025.1.4.1\jbr' }
    if ($env:JAVA_HOME) { $env:Path="$env:JAVA_HOME\bin;$env:Path" }
    Push-Location -LiteralPath 'frontend'
    try {
        if (-not (Test-Path -LiteralPath 'node_modules')) {
            & npm.cmd ci
            if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        }
        & npm.cmd run test
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } finally { Pop-Location }
    & mvn -B -ntp verify
    $taskExitCode=$LASTEXITCODE
} finally {
    foreach($taskName in $taskIsolationNames){[Environment]::SetEnvironmentVariable($taskName,$taskOriginalEnvironment[$taskName],'Process')}
}
exit $taskExitCode
