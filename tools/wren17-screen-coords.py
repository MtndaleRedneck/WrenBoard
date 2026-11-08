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
 found=[]
 for r in csv.DictReader(io.StringIO(data),delimiter='\t'):
  text=re.sub('[^a-z]','',r['text'].casefold())
  if text!=re.sub('[^a-z]','',wanted):continue
  if float(r['conf'])<15:continue
  found.append(((int(r['left'])+int(r['width'])/2)/2,(int(r['top'])+int(r['height'])/2)/2))
 if len(found)==1:
  temp.unlink(missing_ok=True)
  x,y=found[0];print(round(x),round(y));print(f'SCREEN_TEXT {wanted} via {mode} x={x} y={y}',file=sys.stderr);sys.exit(0)
temp.unlink(missing_ok=True)
raise SystemExit('Rendered text missing or ambiguous: '+wanted)
