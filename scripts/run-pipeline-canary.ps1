param(
  [switch]$ConfirmLive,
  [string]$EnvFile='',
  [string]$BaseUrl='http://127.0.0.1:8080'
)
$ErrorActionPreference='Stop'
Remove-Item Env:CANARY_PIPELINE_RUNNER_PREFLIGHT_OK -ErrorAction SilentlyContinue
function Import-CanaryEnvironment {
  param([Parameter(Mandatory=$true)][string]$Path)
  foreach($taskLine in Get-Content -LiteralPath $Path){
    if($taskLine -match '^\s*([^#][^=]+)=(.*)$'){
      $taskName=$matches[1].Trim();$taskValue=$matches[2].Trim().Trim('"')
      [Environment]::SetEnvironmentVariable($taskName,$taskValue,'Process')
    }
  }
}
$root=Split-Path -Parent $PSScriptRoot
$target=Join-Path $root 'target\canary'
New-Item -ItemType Directory -Force -Path $target | Out-Null
$plan=[ordered]@{
  phase='PIPELINE';generationProfile='TEST';shots=4;targetDurationSeconds=20
  characters=2;locations=1;props=1;dialogueLines=2
  imageModel='doubao-seedream-5-0-260128';imageSize='2K';aspectRatioIntent='9:16'
  videoModel='doubao-seedance-2-0-fast-260128';videoResolution='480p';videoDurationSeconds=5
  requests=[ordered]@{storyDirectorLlm=2;image=4;video=4;audio=2;vlmQc=8}
  automaticRetry=$false;automaticSecondTake=$false;resume=$true;productionIntegration='PRODUCTION_PIPELINE_READY'
}
$plan | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $target 'pipeline-canary-dry-run.json') -Encoding utf8
@"
# Pipeline Canary Dry Run

- GenerationProfile: TEST
- Shots: 4
- Target video duration: 20s
- Characters: 2
- Locations: 1
- Props: 1
- Dialogue lines / TTS: 2
- Image requests: 4 (doubao-seedream-5-0-260128 / 2K / 9:16)
- Video submissions: 4 (doubao-seedance-2-0-fast-260128 / 480p / 5s)
- Story/Director LLM: <= 2
- VLM QC: <= 8
- Automatic retry: false
- Automatic second take: false
- Resume: enabled
- Production integration: PRODUCTION_PIPELINE_READY
- Real Provider requests: 0
"@ | Set-Content -LiteralPath (Join-Path $target 'pipeline-canary-dry-run.md') -Encoding utf8

if(-not $ConfirmLive){$plan | ConvertTo-Json -Depth 5;Write-Host 'DRY RUN ONLY. No provider request was sent.' -ForegroundColor Yellow;exit 0}

$snapshot=Join-Path $root 'target\provider-capability-snapshot.json'
if(-not (Test-Path -LiteralPath $snapshot)){throw 'PIPELINE CANARY BLOCKED: Phase A capability snapshot is missing.'}
$capability=Get-Content -LiteralPath $snapshot -Raw | ConvertFrom-Json
if($capability.imageOutputs.'2K'.'9:16'.status -ne 'LIVE_VERIFIED' -or $capability.videoOutputs.'480p'.'5s'.status -ne 'LIVE_VERIFIED'){throw 'PIPELINE CANARY BLOCKED: exact Image 2K/9:16 and Video 480p/5s capabilities must be LIVE_VERIFIED.'}
$statePath=Join-Path $root 'target\live-canary\pipeline-canary-state.json'
if(Test-Path -LiteralPath $statePath){
  $pipelineState=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
  if($pipelineState.status -eq 'RECONCILIATION_REQUIRED'){throw 'Pipeline Canary requires reconciliation. Do not resume or submit another Provider request.'}
  if($pipelineState.status -eq 'SUCCEEDED'){throw 'Pipeline Canary already succeeded. Do not run it again.'}
  Write-Host "RESUMING PIPELINE CANARY from $($pipelineState.currentStep)" -ForegroundColor Yellow
}
if($EnvFile -and -not (Test-Path -LiteralPath $EnvFile)){throw "Env file not found: $EnvFile"}
try{$jobs=Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/resources/jobs" -TimeoutSec 10}catch{throw 'The local app must be running so unresolved provider submissions can be checked before Live Pipeline Canary.'}
$unresolved=@($jobs | Where-Object {$_.status -eq 'UNKNOWN' -or $_.submissionUncertain -eq $true -or $_.reconciliationRequired -eq $true})
if($unresolved.Count -gt 0){throw "Pipeline Canary blocked: $($unresolved.Count) provider submission(s) require reconciliation."}
$secretScan=& git -C $root grep -n -E 'ark-[A-Za-z0-9-]{20,}' -- . ':(exclude).env' ':(exclude).env.*' 2>$null
if($LASTEXITCODE -eq 0 -and $secretScan){throw 'Pipeline Canary blocked: an Ark credential pattern exists in tracked workspace files.'}
$env:JAVA_HOME='D:\idea\IntelliJ IDEA 2025.1.4.1\jbr'
$maven='D:\maven\apache-maven-3.6.3\bin\mvn.cmd'
$ffmpeg=Join-Path $root 'frontend\node_modules\ffmpeg-static\ffmpeg.exe'
$ffprobe=Join-Path $root 'frontend\node_modules\ffprobe-static\bin\win32\x64\ffprobe.exe'
if(-not (Test-Path -LiteralPath $ffmpeg) -or -not (Test-Path -LiteralPath $ffprobe)){throw 'FFmpeg or ffprobe is missing.'}
Push-Location $root
try{
  & (Join-Path $PSScriptRoot 'test.ps1') -SkipPackage;if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
  if($EnvFile){Import-CanaryEnvironment -Path $EnvFile}
  if($env:RUN_LIVE_PIPELINE_CANARY -ne 'true'){throw 'RUN_LIVE_PIPELINE_CANARY=true is the second required confirmation.'}
  $env:CANARY_GENERATION_PROFILE='TEST'
  if(-not $env:ARK_IMAGE_MODEL){$env:ARK_IMAGE_MODEL=$plan.imageModel}
  if(-not $env:ARK_IMAGE_SIZE){$env:ARK_IMAGE_SIZE='2K'}
  if(-not $env:ARK_VIDEO_MODEL){$env:ARK_VIDEO_MODEL=$plan.videoModel}
  if(-not $env:ARK_VIDEO_RESOLUTION){$env:ARK_VIDEO_RESOLUTION='480p'}
  if($env:ARK_IMAGE_MODEL -ne $plan.imageModel -or $env:ARK_IMAGE_SIZE -ne '2K' -or $env:ARK_VIDEO_MODEL -ne $plan.videoModel -or $env:ARK_VIDEO_RESOLUTION -ne '480p'){throw 'Only the fixed TEST generation profile is allowed.'}
  if(-not $env:ARK_API_KEY){throw 'ARK_API_KEY must be present in the local process environment.'}
  if(-not $env:SEED_AUDIO_API_KEY){throw 'SEED_AUDIO_API_KEY must be present in the local process environment.'}
  if(-not $env:CANARY_TTS_VOICE_ID){throw 'CANARY_TTS_VOICE_ID must be configured for the two Mandarin lines.'}
  $env:PIPELINE_TEST_MAX_REAL_IMAGE_REQUESTS='4'
  $env:PIPELINE_TEST_MAX_REAL_VIDEO_REQUESTS='4'
  $env:PIPELINE_TEST_MAX_REAL_AUDIO_REQUESTS='2'
  $env:PIPELINE_TEST_MAX_REAL_LLM_REQUESTS='2'
  $env:PIPELINE_TEST_MAX_REAL_VLM_REQUESTS='8'
  if(-not $env:PIPELINE_TEST_MAX_COST_CNY){$env:PIPELINE_TEST_MAX_COST_CNY='20'}
  if(-not $env:PIPELINE_TEST_IMAGE_ESTIMATED_COST_CNY){$env:PIPELINE_TEST_IMAGE_ESTIMATED_COST_CNY='8'}
  if(-not $env:PIPELINE_TEST_VIDEO_ESTIMATED_COST_CNY){$env:PIPELINE_TEST_VIDEO_ESTIMATED_COST_CNY='12'}
  $env:DRAMA_TEST_RUN='true'
  $env:DRAMA_TEST_RUN_ID="pipeline-canary-$([guid]::NewGuid())"
  $env:SPRING_PROFILES_ACTIVE='demo,live'
  $env:SPRING_DATASOURCE_URL='jdbc:h2:file:./target/live-canary/production-db;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;AUTO_SERVER=TRUE'
  $env:FFMPEG_PATH=$ffmpeg;$env:FFPROBE_PATH=$ffprobe
  $env:CANARY_PIPELINE_RUNNER_PREFLIGHT_OK='true'
  & $maven -q '-Dtest=LivePipelineCanaryIT' test;if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
}finally{
  Remove-Item Env:CANARY_PIPELINE_RUNNER_PREFLIGHT_OK -ErrorAction SilentlyContinue
  Pop-Location
}
Write-Host 'PIPELINE CANARY FINISHED.' -ForegroundColor Green
