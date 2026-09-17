"""Explicit, resumable live verification. Never auto-retries a generation request."""
import json
import sys
import urllib.request
import urllib.error
import time
from pathlib import Path
from datetime import datetime, timezone

BASE = 'http://127.0.0.1:8080/api'
OUT = Path(__file__).resolve().parents[1] / 'target' / 'live-pipeline-check'
OUT.mkdir(parents=True, exist_ok=True)
STATE = OUT / 'state.json'
sys.stdout.reconfigure(encoding='utf-8')

def api(path, body=None, method=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
    req = urllib.request.Request(BASE + path, data=data, method=method or ('POST' if data is not None else 'GET'), headers={'Content-Type':'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f'HTTP {error.code}: {error.read().decode("utf-8")}') from error

def save(name, value):
    (OUT / (name + '.json')).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')

def snapshot():
    state = json.loads(STATE.read_text(encoding='utf-8'))
    value = api('/resources/projects/' + state['projectId'] + '/workspace')
    save('workspace', value)
    brief = {'projectId':state['projectId'], 'documents':[{k:d.get(k) for k in ('id','documentType','episodeNo','reviewStatus','revision','failureReason','generationJobId')} for d in value['story-documents'] if not d.get('stale')],
             'jobs':[{k:j.get(k) for k in ('id','type','status','providerRequestId','providerTaskId','failureReason','submissionUncertain')} for j in value['jobs']],
             'views':[{k:v.get(k) for k in ('id','assetName','view','master','status','revision','archiveKey','failureReason')} for v in value.get('asset-views',[]) if not v.get('stale')],
             'counts':{key:len(value.get(key,[])) for key in ('episodes','scenes','shots','keyframes','video-takes','audio-clips','timelines','renders')}}
    print(json.dumps(brief,ensure_ascii=False))
    return value

def current_project():
    return json.loads(STATE.read_text(encoding='utf-8'))['projectId']

def wait_jobs(seconds):
    deadline = time.monotonic() + min(seconds, 55)
    while True:
        jobs = api('/resources/jobs?projectId=' + current_project())
        active = [j for j in jobs if j['status'] in ('QUEUED','RUNNING','RETRY_WAIT')]
        if not active or time.monotonic() >= deadline:
            break
        time.sleep(4)
    value=api('/resources/projects/'+current_project()+'/workspace')
    save('workspace', value)
    counts={s:sum(j['status']==s for j in jobs) for s in ('QUEUED','RUNNING','SUCCESS','FAILED','CANCELLED')}
    print(json.dumps({'jobs':counts,'active':[{'id':j['id'],'type':j['type'],'providerTaskId':j.get('providerTaskId')} for j in active],
                      'errors':[{k:j.get(k) for k in ('id','type','failureReason','providerRequestId','providerTaskId','submissionUncertain')} for j in jobs if j['status']=='FAILED'],
                      'reviewDocuments':[{k:d.get(k) for k in ('id','documentType','revision')} for d in value['story-documents'] if d['reviewStatus']=='REVIEW'],
                      'reviewViews':[{k:v.get(k) for k in ('id','assetName','view','master','revision','archiveKey')} for v in value.get('asset-views',[]) if v['status']=='REVIEW']},ensure_ascii=False))

if __name__ == '__main__':
    command = sys.argv[1]
    if command == 'create':
        if STATE.exists():
            raise RuntimeError('Existing test project: use status; do not duplicate paid generation.')
        project = api('/resources/projects', {'name':'槐树村夜归 · 三方流程实测', 'idea':'农村恐怖短剧，僵尸，一群留守老人。只拍一个12秒的惊悚短片：村里三位70岁上下的留守老人夜里守着旧祠堂，听到门外已故同伴熟悉的敲门声，发现门缝外站着清朝官服僵尸。结尾以老人辨认出敲门暗号的细节制造恐怖，不靠旁白解释。创作限制：恰好三位身份鲜明的老人和一位僵尸，每人只一套定妆；恰好一个农村旧祠堂场景和一件关键道具（老人手持铜铃）；只一场戏，切镜让老人分别反应，同一镜头最多两个可辨认人物。仅一句简短对白，其余靠动作、空间、声音叙事。所有角色外貌、服装、铜铃形制、门窗位置、持物归属与左右方向应具体并贯穿不变。测试样片也要有清晰因果与反转。', 'episodeCount':1,'targetDuration':12,'ratio':'9:16','dialect':'MANDARIN','style':'自然肤质与年龄纹理，固定冷月光从祠堂东窗照入，西侧一盏暖色油灯，青灰砖墙和潮湿木门保持位置一致，暗部保留可读细节'})
        save('project', project)
        save('state', {'projectId':project['id'],'createdAt':datetime.now(timezone.utc).isoformat()})
        doc = api('/story-development/projects/'+project['id']+'/core', {})
        save('core-submission', doc)
        print(json.dumps({'projectId':project['id'],'documentId':doc['id'],'jobId':doc['generationJobId']}, ensure_ascii=False))
    elif command == 'status':
        snapshot()
    elif command == 'wait':
        wait_jobs(int(sys.argv[2]) if len(sys.argv)>2 else 50)
    elif command == 'edit-core':
        doc=next(d for d in api('/resources/story-documents?projectId='+current_project()) if d['documentType']=='CORE' and not d.get('stale'))
        body={'revision':doc['revision'],'content':json.loads((OUT/'core-reviewed-content.json').read_text(encoding='utf-8'))}
        result=api('/story-development/documents/'+doc['id'],body,'PUT')
        save('core-edited',result)
        print(json.dumps({'id':result['id'],'revision':result['revision'],'validation':result['validation']},ensure_ascii=False))
    elif command == 'confirm':
        doc = api('/resources/story-documents/' + sys.argv[2])
        save('reviewed-'+doc['id'], doc)
        result=api('/story-development/documents/'+doc['id']+'/confirm', {'revision':doc['revision'],'batchSize':5})
        print(json.dumps({'documentId':result['id'],'reviewStatus':result['reviewStatus']},ensure_ascii=False))
    elif command == 'request':
        body=json.loads(Path(sys.argv[3]).read_text(encoding='utf-8')) if len(sys.argv)>3 else {}
        result=api(sys.argv[2],body)
        save('last-request',result)
        print(json.dumps(result,ensure_ascii=False))
    else:
        raise RuntimeError('Unknown operation')
