param(
  [switch]$ConfirmLive,
  [string]$EnvFile='',
  [string]$BaseUrl='http://127.0.0.1:8080'
)
$ErrorActionPreference='Stop'
Remove-Item Env:CANARY_RUNNER_PREFLIGHT_OK -ErrorAction SilentlyContinue
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
  phase='PROVIDER'; generationProfile='TEST'; imageModel='doubao-seedream-5-0-260128'; imageSize='2K'; aspectRatioIntent='9:16'
  videoModel='doubao-seedance-2-0-fast-260128'; videoResolution='480p'; videoDurationSeconds=5; nativeAudio=$false
  referenceRoute='SEEDREAM_PROVIDER_URL_DIRECT_FIRST_FRAME'; automaticRetry=$false
  requests=[ordered]@{image=1;video=1;audio=0;storyDirectorLlm=0;vlmQc=0}
}
$plan | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $target 'provider-canary-dry-run.json') -Encoding utf8
@"
# Provider Canary Dry Run

- 阶段：PROVIDER
- GenerationProfile：TEST
- Seedream：doubao-seedream-5-0-260128 / 2K / 9:16 intent
- Seedance：doubao-seedance-2-0-fast-260128 / 480p / 5 秒
- 预计真实请求：1 图片 + 1 视频
- 原生音频：false
- 引用路线：Seedream 原始 provider URL 直接作为 Seedance first frame
- 自动重试：false
- 本报告没有调用 Provider
"@ | Set-Content -LiteralPath (Join-Path $target 'provider-canary-dry-run.md') -Encoding utf8

$liveRequested=$ConfirmLive -or $env:RUN_LIVE_PROVIDER_CANARY -eq 'true'
if(-not $liveRequested){
  $plan | ConvertTo-Json -Depth 5
  Write-Host 'DRY RUN ONLY. No provider request was sent.' -ForegroundColor Yellow
  exit 0
}
if($EnvFile){
  if(-not (Test-Path -LiteralPath $EnvFile)){throw "Env file not found: $EnvFile"}
}
Remove-Item Env:CANARY_RUNNER_PREFLIGHT_OK -ErrorAction SilentlyContinue
$env:RUN_LIVE_PROVIDER_CANARY='false'
$env:RUN_LIVE_PIPELINE_CANARY='false'
$env:DRAMA_PROVIDER_MODE='mock'
$env:VISUAL_REVIEWER='fake'

try{$jobs=Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/resources/jobs" -TimeoutSec 10}catch{throw 'The local app must be running so unresolved provider submissions can be checked before Live Canary.'}
$unresolved=@($jobs | Where-Object {$_.status -eq 'UNKNOWN' -or $_.submissionUncertain -eq $true -or $_.reconciliationRequired -eq $true})
if($unresolved.Count -gt 0){throw "Live Canary blocked: $($unresolved.Count) provider submission(s) require reconciliation."}

$secretScan=& git -C $root grep -n -E 'ark-[A-Za-z0-9-]{20,}' -- . ':(exclude).env' ':(exclude).env.*' 2>$null
if($LASTEXITCODE -eq 0 -and $secretScan){throw 'Live Canary blocked: an Ark credential pattern exists in tracked workspace files.'}
$env:JAVA_HOME='D:\idea\IntelliJ IDEA 2025.1.4.1\jbr'
$maven='D:\maven\apache-maven-3.6.3\bin\mvn.cmd'
$ffprobe=Join-Path $root 'frontend\node_modules\ffprobe-static\bin\win32\x64\ffprobe.exe'
if(-not (Test-Path -LiteralPath $ffprobe)){throw 'ffprobe is missing. Run the normal dependency setup before Live Canary.'}
$env:FFPROBE_PATH=$ffprobe
Push-Location $root
try{
  & (Join-Path $PSScriptRoot 'test.ps1') -SkipPackage;if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
  if($EnvFile){Import-CanaryEnvironment -Path $EnvFile}
  $env:CANARY_GENERATION_PROFILE='TEST'
  if(-not $env:ARK_IMAGE_MODEL){$env:ARK_IMAGE_MODEL='doubao-seedream-5-0-260128'}
  if(-not $env:ARK_IMAGE_SIZE){$env:ARK_IMAGE_SIZE='2K'}
  if(-not $env:ARK_VIDEO_MODEL){$env:ARK_VIDEO_MODEL='doubao-seedance-2-0-fast-260128'}
  if(-not $env:ARK_VIDEO_RESOLUTION){$env:ARK_VIDEO_RESOLUTION='480p'}
  $env:TEST_MAX_REAL_IMAGE_REQUESTS='1'
  $env:TEST_MAX_REAL_VIDEO_REQUESTS='1'
  $env:TEST_MAX_REAL_AUDIO_REQUESTS='0'
  $env:TEST_MAX_REAL_LLM_REQUESTS='0'
  if(-not $env:TEST_MAX_COST_CNY){$env:TEST_MAX_COST_CNY='5'}
  $env:DRAMA_TEST_RUN='true'
  $env:DRAMA_TEST_RUN_ID="provider-canary-$([guid]::NewGuid())"
  if(-not $env:ARK_API_KEY){throw 'ARK_API_KEY must be present in the local process environment.'}
  if($env:ARK_IMAGE_MODEL -ne $plan.imageModel -or $env:ARK_IMAGE_SIZE -ne '2K' -or $env:ARK_VIDEO_MODEL -ne $plan.videoModel -or $env:ARK_VIDEO_RESOLUTION -ne '480p'){throw 'Canary model/profile check failed. Only TEST + Seedream 5.0 + Seedance 2.0 Fast + 480p is allowed.'}
  $env:RUN_LIVE_PROVIDER_CANARY='true'
  $env:CANARY_RUNNER_PREFLIGHT_OK='true'
  & $maven -q '-Dtest=LiveProviderCanaryIT' test;if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
}finally{
  Remove-Item Env:CANARY_RUNNER_PREFLIGHT_OK -ErrorAction SilentlyContinue
  Pop-Location
}
Write-Host 'PROVIDER CANARY FINISHED. Phase B was not started.' -ForegroundColor Green
