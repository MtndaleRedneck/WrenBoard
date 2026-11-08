#!/usr/bin/env python3
"""Locate actual rendered IME text when Android omits IME nodes from UI hierarchy."""
import csv, io, re, subprocess, sys
from pathlib import Path
from PIL import Image
p=Path(sys.argv[1]); wanted=sys.argv[2].casefold(); im=Image.open(p).convert('RGB')
# Tile-column crops prevent labels such as Debug Info/Copy to Clipboard from
# being mistaken for the separate Info/Clipboard tiles.
if len(sys.argv)>3 and sys.argv[3]=='hidden':
 matches=[]
 for col in range(4):
  x0=im.width*col//4;x1=im.width*(col+1)//4;y0=int(im.height*0.63)
  crop=im.crop((x0,y0,x1,im.height-60)).resize(((x1-x0)*3,(im.height-60-y0)*3))
  temp=p.with_suffix('.hidden.png');crop.save(temp)
  data=subprocess.check_output(['tesseract',str(temp),'stdout','--psm','6','tsv'],stderr=subprocess.DEVNULL).decode()
  groups={}
  for row in csv.DictReader(io.StringIO(data),delimiter='\t'):
   if float(row['conf'])<15:continue
   key=(row['block_num'],row['par_num'],row['line_num']);groups.setdefault(key,[]).append(row)
  for rows in groups.values():
   words=''.join(re.sub('[^a-z]','',r['text'].casefold()) for r in rows)
   if words==re.sub('[^a-z]','',wanted):
    left=min(int(r['left']) for r in rows);right=max(int(r['left'])+int(r['width'])for r in rows)
    top=min(int(r['top'])for r in rows);bottom=max(int(r['top'])+int(r['height'])for r in rows)
    matches.append((round(x0+(left+right)/6),round(y0+(top+bottom)/6)))
  temp.unlink(missing_ok=True)
 if len(matches)==1:print(*matches[0]);sys.exit(0)
 raise SystemExit('Hidden tile label missing or ambiguous: '+wanted)
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
