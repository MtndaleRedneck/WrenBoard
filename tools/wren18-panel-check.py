from PIL import Image, ImageChops
import sys

# --probe BASE CANDIDATE: exit 0 when CANDIDATE visibly differs from BASE in the keyboard area
# (used by the runtime script to wait until a panel has actually rendered before screenshotting).
if sys.argv[1] == "--probe":
    b, c = [Image.open(p).convert("RGB") for p in sys.argv[2:4]]
    w,h = b.size
    box = (0,int(h*0.59),w,int(h*0.94))
    d = ImageChops.difference(b.crop(box), c.crop(box))
    frac = sum(max(v)>24 for v in d.getdata())/(d.width*d.height)
    sys.exit(0 if frac > 0.03 else 1)

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
# WrenBoard 1.9: while a panel is open the system nav bar must show the back arrow instead of
# the keyboard down-chevron, and it must return to the down-chevron when the panel closes.
# The system nav bar is the bottom ~126px at 420dpi (3-button mode). The icon there must
# switch from the down-chevron to the back arrow while the panel is open and switch back.
nav=(0,h-130,w,h)
def nav_changed(x,y):
 d=ImageChops.difference(x.crop(nav),y.crop(nav))
 return sum(max(v)>24 for v in d.getdata())
opened_nav=nav_changed(base,opened); returned_nav=nav_changed(base,returned)
print(sys.argv[4],"nav_opened_changed_px",opened_nav,"nav_returned_changed_px",returned_nav)
assert opened_nav>60, "Nav bar did not switch to the back arrow while the panel was open"
assert returned_nav<30, "Nav bar did not restore the down-chevron after the panel closed"
