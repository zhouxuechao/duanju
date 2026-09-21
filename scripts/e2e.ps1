param(
  [ValidateSet('Mock','Media','Canary')] [string]$Mode='Mock',
  [string]$Scenario='golden-basic',
  [string]$EnvFile='',
  [string]$ResumeRun='',
  [string]$BaseUrl='http://127.0.0.1:8080'
)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$fixture=Join-Path $projectRoot "test-fixtures/e2e/$Scenario/project.json"
if(-not (Test-Path -LiteralPath $fixture)){throw "Unknown E2E scenario: $Scenario"}
if($ResumeRun){$result=Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/pipeline-runs/$ResumeRun/resume";if($result.status -eq 'WAITING'){Write-Host "Pipeline waits at $($result.resumeFromStage)" -ForegroundColor Yellow}else{Write-Host "Pipeline $($result.status)" -ForegroundColor Green};$result|ConvertTo-Json -Depth 8;exit 0}
if($Mode -eq 'Canary'){
  if(-not $EnvFile -or -not (Test-Path -LiteralPath $EnvFile)){throw 'Canary mode requires -EnvFile with local provider configuration.'}
  Get-Content -LiteralPath $EnvFile | ForEach-Object {if($_ -match '^\s*([^#][^=]+)=(.*)$'){[Environment]::SetEnvironmentVariable($matches[1].Trim(),$matches[2].Trim().Trim('"'),[EnvironmentVariableTarget]::Process)}}
  foreach($required in @('ARK_API_KEY','ARK_TEXT_MODEL','ARK_IMAGE_MODEL','ARK_VIDEO_MODEL','SEED_AUDIO_API_KEY')){if(-not [Environment]::GetEnvironmentVariable($required)){throw "Canary mode requires $required."}}
  $env:DRAMA_TEST_RUN='true';$env:DRAMA_TEST_RUN_ID="canary-$([guid]::NewGuid())"
}
$env:JAVA_HOME='D:\idea\IntelliJ IDEA 2025.1.4.1\jbr'
$maven='D:\maven\apache-maven-3.6.3\bin\mvn.cmd'
$frontend=Join-Path $projectRoot 'frontend'
$projectFfmpeg=Join-Path $frontend 'node_modules\ffmpeg-static\ffmpeg.exe'
$projectFfprobe=Join-Path $frontend 'node_modules\ffprobe-static\bin\win32\x64\ffprobe.exe'
if($Mode -in @('Media','Canary')){
  if(-not (Test-Path -LiteralPath $projectFfmpeg) -or -not (Test-Path -LiteralPath $projectFfprobe)){
    Push-Location $frontend
    try { & npm.cmd ci; if($LASTEXITCODE -ne 0){exit $LASTEXITCODE} } finally { Pop-Location }
  }
  $env:FFMPEG_PATH=$projectFfmpeg
  $env:FFPROBE_PATH=$projectFfprobe
}
$tests=if($Mode -eq 'Mock'){'BenchmarkOneIntegrationTest,ProductionAcceptanceTest,LongStoryHistoryTest,PipelinePreflightIntegrationTest'}elseif($Mode -eq 'Media'){'PostProductionIntegrationTest,MediaProbeServiceTest,ProductionRulesTest,FinalQualityServiceTest'}else{'LiveProviderCanaryIT'}
Write-Host "E2E $Mode / $Scenario" -ForegroundColor Cyan
Push-Location $projectRoot
try { & $maven -q "-Dtest=$tests" test; if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}; Push-Location frontend; try { npm run build; if($LASTEXITCODE -ne 0){exit $LASTEXITCODE} } finally { Pop-Location } }
finally { Pop-Location }
Write-Host 'E2E PASS' -ForegroundColor Green
