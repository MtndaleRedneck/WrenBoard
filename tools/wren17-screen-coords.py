#!/usr/bin/env python3
"""Locate actual rendered IME text when Android omits IME nodes from UI hierarchy."""
import csv, io, re, subprocess, sys
from pathlib import Path
from PIL import Image
p=Path(sys.argv[1]); wanted=sys.argv[2].casefold(); im=Image.open(p).convert('RGB')
for mode in ['original','orange']:
 screen=im
 if mode=='orange':
  screen=Image.new('L', im.size,255);dst=screen.load();src=im.load()
  for y in range(im.height):
   for x in range(im.width):
    r,g,b=src[x,y]
    if r>g*1.2 and g>b*1.2 and r>80:dst[x,y]=0
 temp=p.with_suffix('.ocr.png');screen.resize((im.width*2,im.height*2)).save(temp)
 data=subprocess.check_output(['tesseract',str(temp),'stdout','--psm','11','tsv'],stderr=subprocess.DEVNULL).decode()
 rows=list(csv.DictReader(io.StringIO(data),delimiter='\t'))
 found=[]
 clean=lambda t:re.sub('[^a-z]','',t.casefold())
 wanted_clean=clean(wanted)
 for i,r in enumerate(rows):
  if float(r['conf'])<15:continue
  joined=''; group=[]
  for r2 in rows[i:i+5]:
   if float(r2['conf'])<15:break
   if abs(int(r2['top'])-int(r['top']))>35:break
   joined+=clean(r2['text']);group.append(r2)
   if joined==wanted_clean:
    left=min(int(v['left']) for v in group);right=max(int(v['left'])+int(v['width']) for v in group)
    top=min(int(v['top']) for v in group);bottom=max(int(v['top'])+int(v['height']) for v in group)
    found.append(((left+right)/4,(top+bottom)/4));break
   if not wanted_clean.startswith(joined):break
 if len(found)==1:
  temp.unlink(missing_ok=True)
  x,y=found[0];print(round(x),round(y));print(f'SCREEN_TEXT {wanted} via {mode} x={x} y={y}',file=sys.stderr);sys.exit(0)
temp.unlink(missing_ok=True)
raise SystemExit('Rendered text missing or ambiguous: '+wanted)
