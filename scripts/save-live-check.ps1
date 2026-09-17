param([string]$ProjectId='d346329d-ec0b-4c7e-8e09-29b889c3e5d2', [int]$WaitSeconds=0)
$ErrorActionPreference='Stop'
$base='http://127.0.0.1:8080/api'
$root=Split-Path -Parent $PSScriptRoot
$folder=Join-Path $root "data\verification\$ProjectId"
$null=New-Item -ItemType Directory -Path $folder -Force
function Remove-PrivateLinks($value) {
    if($null -eq $value){return $null}
    if($value -is [string]){
        if($value -match '^data:'){return '[inline media omitted]'}
        return ($value -replace 'https?://[^\s"<>]+','[provider media URL omitted]')
    }
    if($value -is [System.Collections.IDictionary]){
        $safe=[ordered]@{}
        foreach($key in $value.Keys){
            if($key -in @('providerOutputRaw','referenceImageUrls','prompt')){continue}
            $safe[$key]=Remove-PrivateLinks $value[$key]
        }
        return $safe
    }
    if($value -is [array]){return ,@($value|ForEach-Object{Remove-PrivateLinks $_})}
    return $value
}
$deadline=[DateTime]::UtcNow.AddSeconds([Math]::Min(55,$WaitSeconds))
do {
    $w=Invoke-RestMethod "$base/resources/projects/$ProjectId/workspace" | ConvertTo-Json -Depth 60 | ConvertFrom-Json -AsHashtable
    $active=@($w.jobs|Where-Object{$_.status -in @('RUNNING','QUEUED','RETRY_WAIT')})
    if($active.Count -eq 0 -or [DateTime]::UtcNow -ge $deadline){break}
    Start-Sleep -Seconds 5
} while($true)
$safe=Remove-PrivateLinks $w
$safe|ConvertTo-Json -Depth 60|Set-Content -LiteralPath (Join-Path $folder 'workspace.json') -Encoding utf8
$shots=@($w.shots|Where-Object{-not $_.stale})
$summary=[ordered]@{
    savedAt=[DateTime]::UtcNow.ToString('o'); projectId=$ProjectId; name=$w.project.name
    activeJobs=@($active|Select-Object id,type,status,providerRequestId,providerTaskId)
    shots=@($shots|Select-Object id,shotNo,status)
    recentJobs=@($w.jobs|Sort-Object createdAt -Descending|Select-Object -First 8 id,type,status,failureCode,failureReason,providerRequestId,providerTaskId,submissionUncertain)
    frames=@($w.keyframes|Where-Object{$_.shotId -in $shots.id -and -not $_.assetReferencesStale}|Select-Object id,shotId,version,qcStatus,archiveUrl,providerRequestId,locked)
    takes=@($w.'video-takes'|Where-Object{$_.shotId -in $shots.id}|Select-Object id,shotId,providerStatus,qcStatus,archiveUrl,locked,providerRequestId)
    audio=@($w.'audio-clips'|Select-Object id,shotId,providerStatus,duration,archiveUrl,locked)
    timelines=@($w.timelines|Select-Object id,status,locked,durationMs,previewUrl,finalUrl)
}
$summary|ConvertTo-Json -Depth 8|Set-Content -LiteralPath (Join-Path $folder 'progress.json') -Encoding utf8
$summary|ConvertTo-Json -Depth 8 -Compress
