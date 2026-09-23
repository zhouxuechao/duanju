param(
  [string]$ExpectedRunId='provider-canary-49657dbf-1d96-4137-b060-3cf799a61180'
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$taskNames=@('DRAMA_PROVIDER_MODE','VISUAL_REVIEWER','RUN_LIVE_PROVIDER_CANARY','RUN_LIVE_PIPELINE_CANARY','CANARY_RUNNER_PREFLIGHT_OK','CANARY_PIPELINE_RUNNER_PREFLIGHT_OK','RUN_OFFLINE_PROVIDER_CANARY_RECONCILIATION','OFFLINE_CANARY_EXPECTED_RUN_ID')
$taskOriginal=@{}
foreach($taskName in $taskNames){$taskOriginal[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process')}
try{
  $env:DRAMA_PROVIDER_MODE='mock'
  $env:VISUAL_REVIEWER='fake'
  $env:RUN_LIVE_PROVIDER_CANARY='false'
  $env:RUN_LIVE_PIPELINE_CANARY='false'
  Remove-Item Env:CANARY_RUNNER_PREFLIGHT_OK -ErrorAction SilentlyContinue
  Remove-Item Env:CANARY_PIPELINE_RUNNER_PREFLIGHT_OK -ErrorAction SilentlyContinue
  $env:RUN_OFFLINE_PROVIDER_CANARY_RECONCILIATION='true'
  $env:OFFLINE_CANARY_EXPECTED_RUN_ID=$ExpectedRunId
  if(-not $env:JAVA_HOME -and (Test-Path -LiteralPath 'D:\idea\IntelliJ IDEA 2025.1.4.1\jbr\bin\javac.exe')){$env:JAVA_HOME='D:\idea\IntelliJ IDEA 2025.1.4.1\jbr'}
  if($env:JAVA_HOME){$env:Path="$env:JAVA_HOME\bin;$env:Path"}
  Push-Location $root
  try{& mvn -B -ntp '-Dtest=OfflineProviderCanaryReconciliationIT' test;if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}}
  finally{Pop-Location}
}finally{
  foreach($taskName in $taskNames){[Environment]::SetEnvironmentVariable($taskName,$taskOriginal[$taskName],'Process')}
}
