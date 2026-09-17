param([string]$EnvFile='D:\project\aimanju\.env')
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$projectId='d346329d-ec0b-4c7e-8e09-29b889c3e5d2'
$folder=Join-Path $root "data\verification\$projectId"
$null=New-Item -ItemType Directory -Path $folder -Force
$record=Join-Path $folder 'voice-design.json'
if(Test-Path -LiteralPath $record){throw 'Voice-design request already recorded; inspect it before any retry.'}
$settings=@{}
foreach($line in [IO.File]::ReadAllLines($EnvFile)){
    if($line -match '^\s*(SEED_AUDIO_[A-Z_]+)\s*=\s*(.*?)\s*$'){$settings[$Matches[1]]=$Matches[2].Trim('"').Trim("'")}
}
if(-not $settings.SEED_AUDIO_API_KEY){throw 'Missing audio provider configuration'}
$requestId=[guid]::NewGuid().ToString()
$model=if($settings.SEED_AUDIO_MODEL){$settings.SEED_AUDIO_MODEL}else{'seed-audio-1.0'}
$prompt='为虚构人物张巧云设计唯一固定音色并录制短样本：69岁农村女性，偏尖细的老年声线，有自然气息和轻微苍老沙哑，音量低，警觉中认出熟人。只一个女声，普通话，轻微豫东口音。单人干声，无混响，无环境音，无音乐。只说“是老周。”，不要读角色名或说明。台词在1.5秒内自然说完，首尾各留0.1秒静音。'
$body=@{model=$model;text_prompt=$prompt;audio_config=@{format='mp3';sample_rate=48000;speech_rate=0;pitch_rate=0;loudness_rate=0};watermark=@{}}
$log=[ordered]@{projectId=$projectId;purpose='VOICE_DESIGN';clientRequestId=$requestId;model=$model;status='SUBMITTING';automaticRetries=0;prompt=$prompt;createdAt=[DateTime]::UtcNow.ToString('o')}
$log|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $record -Encoding utf8
try {
    $r=Invoke-WebRequest -Method Post -Uri 'https://openspeech.bytedance.com/api/v3/tts/create' -Headers @{'X-Api-Key'=$settings.SEED_AUDIO_API_KEY;'X-Api-Request-Id'=$requestId} -ContentType 'application/json; charset=utf-8' -Body ($body|ConvertTo-Json -Depth 5) -TimeoutSec 300
    $result=$r.Content|ConvertFrom-Json
    if(-not $result.audio){throw 'No inline audio returned; check provider record before retrying'}
    $mediaFolder=Join-Path $root "data\media\voices\$projectId"
    $null=New-Item -ItemType Directory -Path $mediaFolder -Force
    [IO.File]::WriteAllBytes((Join-Path $mediaFolder 'zhang-qiaoyun.mp3'),[Convert]::FromBase64String($result.audio))
    $log.status='SUCCESS';$log['duration']=$result.duration;$log['providerRequestId']=$r.Headers['X-Tt-Logid'] -join ''
    $log['archiveUrl']="/api/media/voices/$projectId/zhang-qiaoyun.mp3"
} catch {
    $log.status='FAILED';$log['failureReason']='Voice design did not return usable audio. Inspect client request ID; no automatic retry.'
    throw
} finally {$log|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $record -Encoding utf8}
$log|Select-Object status,duration,archiveUrl,providerRequestId,clientRequestId|ConvertTo-Json -Compress
