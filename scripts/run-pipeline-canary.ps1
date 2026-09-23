param(
  [switch]$ConfirmLive,
  [string]$EnvFile='',
  [string]$BaseUrl='http://127.0.0.1:8080'
)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$target=Join-Path $root 'target\canary'
New-Item -ItemType Directory -Force -Path $target | Out-Null
$plan=[ordered]@{
  phase='PIPELINE'; generationProfile='TEST'; shots=4; targetDurationSeconds=30
  characters=2; locations=1; props=1; continuousTransition=$true; dialogueLines=2
  imageModel='doubao-seedream-5-0-260128'; videoModel='doubao-seedance-2-0-fast-260128'; videoResolution='480p'
  requests=[ordered]@{storyDirectorLlm=2;image=4;video=4;audio=2;vlmQc=8}; automaticRetry=$false; maximumVideoTakesPerShot=2
}
$plan | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $target 'pipeline-canary-dry-run.json') -Encoding utf8
@"
# Pipeline Canary Dry Run

- 阶段：PIPELINE
- GenerationProfile：TEST
- 规模：4 镜头 / 30 秒 / 2 人 / 1 场景 / 1 道具
- Seedream：4 个关键帧
- Seedance：4 个 480p 首次生成；不会自动消费第二次机会
- TTS：2 句普通话对白
- 覆盖：CONTINUOUS、道具持有、知识状态、Timeline、Preview、QA、Render
- Phase A 能力证据：真实启动前强制检查
- 本报告没有调用 Provider
"@ | Set-Content -LiteralPath (Join-Path $target 'pipeline-canary-dry-run.md') -Encoding utf8
$liveRequested=$ConfirmLive -or $env:RUN_LIVE_PIPELINE_CANARY -eq 'true'
if(-not $liveRequested){$plan | ConvertTo-Json -Depth 5;Write-Host 'DRY RUN ONLY. No provider request was sent.' -ForegroundColor Yellow;exit 0}
if($ConfirmLive){$env:RUN_LIVE_PROVIDER_CANARY='true';$env:RUN_LIVE_PIPELINE_CANARY='true'}
if($env:RUN_LIVE_PROVIDER_CANARY -ne 'true' -or $env:RUN_LIVE_PIPELINE_CANARY -ne 'true'){throw 'Both RUN_LIVE_PROVIDER_CANARY=true and RUN_LIVE_PIPELINE_CANARY=true are required.'}
$snapshot=Join-Path $root 'target\provider-capability-snapshot.json'
if(-not (Test-Path -LiteralPath $snapshot)){throw 'Phase B is blocked until Phase A writes target/provider-capability-snapshot.json.'}
$capability=Get-Content -LiteralPath $snapshot -Raw | ConvertFrom-Json
if($capability.imageOutputs.'2K'.'9:16'.status -ne 'LIVE_VERIFIED' -or $capability.videoOutputs.'480p'.'5s'.status -ne 'LIVE_VERIFIED'){throw 'Phase B is blocked because the exact 2K/9:16 and 480p/5s capabilities are not LIVE_VERIFIED.'}
throw 'Phase B remains intentionally stopped after Phase A preparation. Review the Phase A report, then start the resumable pipeline in a separate explicit run.'
