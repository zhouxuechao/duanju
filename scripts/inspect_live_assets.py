"""Create labelled inspection sheets from archived originals; never used as model input."""
import json
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'target/live-pipeline-check'
workspace=json.loads((OUT/'workspace.json').read_text(encoding='utf-8'))
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',23)
views=[v for v in workspace.get('asset-views',[]) if not v.get('stale') and v.get('archiveKey')]
if len(sys.argv)>1 and sys.argv[1]!='masters':
    views=[v for v in views if v['assetId']==sys.argv[1]]
else:
    views=[v for v in views if v['master']]
if not views:
    raise SystemExit('No archived views ready for inspection')
columns=2 if len(views)<=4 else 3
cellw,cellh=700,790
sheet=Image.new('RGB',(columns*cellw,((len(views)+columns-1)//columns)*cellh),'#eeeeee')
draw=ImageDraw.Draw(sheet)
for i,v in enumerate(views):
    path=ROOT/'data/media'/v['archiveKey']
    with Image.open(path) as source:
        source.thumbnail((cellw-16,cellh-76))
        x=(i%columns)*cellw+(cellw-source.width)//2
        y=(i//columns)*cellh+58
        sheet.paste(source.convert('RGB'),(x,y))
    draw.text(((i%columns)*cellw+8,(i//columns)*cellh+5),v['assetName']+' / '+v['view'],font=font,fill='black')
    draw.text(((i%columns)*cellw+8,(i//columns)*cellh+31),'v'+str(v['setVersion'])+' / '+v['status'],font=font,fill='black')
name='inspection-'+(sys.argv[1] if len(sys.argv)>1 else 'masters')+'.jpg'
sheet.save(OUT/name,quality=95)
sys.stdout.reconfigure(encoding='utf-8')
print(json.dumps({'path':str(OUT/name),'views':[{k:v[k] for k in ('id','assetId','assetName','view','revision')} for v in views]},ensure_ascii=False))
