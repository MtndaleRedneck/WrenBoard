from PIL import Image, ImageChops
import sys
base, opened, returned = [Image.open(p).convert("RGB") for p in sys.argv[1:4]]
w,h=base.size
# Exclude host editor/cursor, status clock and Android nav; compare the keyboard content.
box=(0,int(h*0.59),w,int(h*0.94))
def difference(a,b):
 d=ImageChops.difference(a.crop(box),b.crop(box))
 return sum(max(v)>24 for v in d.getdata())/(d.width*d.height)
a=difference(base,opened);b=difference(base,returned)
print(sys.argv[4],"opened_diff",round(a,5),"returned_diff",round(b,5))
assert a>0.03, "Panel did not visibly open"
assert b<0.01, "Back did not visibly restore keyboard"
