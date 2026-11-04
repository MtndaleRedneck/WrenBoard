#!/usr/bin/env python3
# Tab 2 additions to Tab 1's teardown harness: mandatory Pumpkin theme for SwiftKey (retries, no default fallback),
# a deep settings walk (every row on every page, one level of sub-pages), every-key long-press, every toolbar item,
# next-word strip probes, and a long-press timing video with show_touches on. Reference/comparison only.
import os, re, time, json, traceback
import skhelpers as H
from skhelpers import note, run, sh, shot, tap, key, dump, B, ctr, texts, find, scroll, clear_dialogs, save, record
from PIL import Image
import numpy as np

PKG = "com.touchtype.swiftkey"
THEME_BUDGET = int(os.environ.get("THEME_BUDGET", "900"))

def _sig(r):
    return "|".join(texts(r)) if r is not None else ""

def unblock(tag="ub"):
    """System/app 'isn't responding' dialogs on a slow emulator: always choose Wait."""
    for i in range(4):
        r = dump("%s_%d" % (tag, i))
        if r is None or "isn't responding" not in _sig(r): return r
        n = find(r, "Wait", exact=True)
        if n is None: key("KEYCODE_BACK", 1.0)
        else: tap(*ctr(n), 3.0)
        note("unblock: tapped Wait on ANR dialog"); time.sleep(3)
    return dump(tag + "_x")

def close_ime_picker(tag):
    r = dump(tag)
    if r is not None and "Choose input method" in _sig(r):
        key("KEYCODE_BACK", 1.0); note("closed IME picker"); r = dump(tag + "b")
    return r

def sk_settings_home():
    sh("am start -S -n %s/com.touchtype.materialsettingsx.NavigationActivity" % PKG); time.sleep(5)
    unblock("skh_ub"); clear_dialogs("skh", 2); return close_ime_picker("skh_ip")

def tap_text_scroll(pat, tag, tries=8, exact=True, down=True):
    for i in range(tries):
        r = dump("%s_%d" % (tag, i))
        n = find(r, pat, exact=exact)
        if n is not None:
            tap(*ctr(n), 2.5); return True
        scroll(down)
    return False

def scan(tag, max_scrolls=25, pat="pumpkin"):
    prev = None; same = 0
    for i in range(max_scrolls):
        r = dump("%s_%02d" % (tag, i))
        n = find(r, pat)
        if n is not None: return r, n
        s = _sig(r)
        if s == prev:
            same += 1
            if same >= 2: break
        else: same = 0
        prev = s; scroll(True)
    return None, None

def open_themes():
    sk_settings_home()
    ok = tap_text_scroll("Themes", "thnav")
    time.sleep(5); clear_dialogs("thd", 2)
    return ok

def headings_with_see_all(r):
    out = []
    if r is None: return out
    sees = [n for n in r.iter("node") if (n.get("content-desc") or n.get("text") or "").strip() == "See all"]
    for s in sees:
        sb = B(s); cy = (sb[1] + sb[3]) // 2
        best = None
        for n in r.iter("node"):
            t = (n.get("text") or "").strip()
            if not t or t == "See all": continue
            b = B(n)
            if b[1] <= cy <= b[3] and b[2] < sb[0] and b[0] < 300:
                best = t; break
        if best: out.append(best)
    return out

def keyboard_orange_score(im):
    a = np.asarray(im.convert("RGB")).astype(int)[1500:2400]
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    orange = (r > 150) & (r - b > 70) & (g < r - 20)
    return float(orange.mean()), [int(v) for v in a.reshape(-1, 3).mean(axis=0)]

def pumpkin_attempt(k):
    note("THEME attempt", k)
    if not open_themes():
        note("THEME: Themes row not found"); shot("th_%d_00_nothemes" % k); return False
    shot("th_%d_01_themes" % k)
    r = dump("th_%d_chips" % k)
    found = None
    # 1) Festive chip, 2) All chip, 3) every 'See all' list (Festive first)
    # Tab 1 (Nov 1 runs that worked): Settings > Themes > "Originals" > See all; Pumpkin is in that list.
    for cat in ("Originals",):
        opened = False
        for i in range(20):
            r = dump("th_%d_orig_%d" % (k, i))
            if r is not None and cat in headings_with_see_all(r):
                hn = find(r, cat, exact=True); hy = ctr(hn)[1]
                for s in r.iter("node"):
                    if (s.get("content-desc") or s.get("text") or "").strip() == "See all" and B(s)[1] - 40 <= hy <= B(s)[3] + 40:
                        tap(*ctr(s), 4); opened = True; break
            if opened: break
            scroll(True)
        if opened:
            shot("th_%d_02_originals" % k)
            rr, nn = scan("th_%d_orig_scan" % k, 40)
            if nn is not None: found = ("Originals See all", rr, nn)
        else:
            note("THEME: Originals See all not reached")
    if found is None:
        open_themes()
        for chip in ("Festive",):
            H.to_top(); r = dump("th_%d_chip_%s" % (k, chip)); n = find(r, chip, exact=True)
            if n is not None:
                tap(*ctr(n), 4); shot("th_%d_02_chip_%s" % (k, chip.lower()))
                rr, nn = scan("th_%d_chip_%s_scan" % (k, chip))
                if nn is not None: found = ("chip " + chip, rr, nn); break
    if found is None:
        open_themes(); heads = []
        prev = None
        for i in range(20):
            r = dump("th_%d_heads_%d" % (k, i))
            for h in headings_with_see_all(r):
                if h not in heads: heads.append(h)
            s = _sig(r)
            if s == prev: break
            prev = s; scroll(True)
        heads.sort(key=lambda h: (0 if re.search("original|festive|halloween|autumn|fall|season", h, re.I) else 1))
        note("THEME: categories with See all:", heads)
        for h in heads:
            open_themes(); hit = False
            for i in range(20):
                r = dump("th_%d_go_%s_%d" % (k, re.sub(r"\W", "", h)[:12], i))
                for s in [n for n in r.iter("node") if (n.get("content-desc") or n.get("text") or "").strip() == "See all"] if r is not None else []:
                    if h in headings_with_see_all(r):
                        sb = B(s); cy = (sb[1] + sb[3]) // 2
                        hn = find(r, h, exact=True)
                        if hn is not None and B(hn)[1] <= cy <= B(hn)[3]:
                            tap(*ctr(s), 4); hit = True; break
                if hit: break
                scroll(True)
            if not hit: continue
            rr, nn = scan("th_%d_list_%s" % (k, re.sub(r"\W", "", h)[:12]), 40)
            if nn is not None: found = ("See all " + h, rr, nn); break
    if found is None:
        note("THEME: Pumpkin not visible in this attempt"); shot("th_%d_03_notfound" % k); return False
    where, r, n = found
    note("THEME: Pumpkin found via", where, "node", n.get("text"), n.get("content-desc"), n.get("bounds"))
    shot("th_%d_04_pumpkin_visible" % k)
    tb = B(n); tcy = (tb[1] + tb[3]) // 2
    dl = None; best = 1e9
    for nd in r.iter("node"):
        d = (nd.get("content-desc") or nd.get("text") or "")
        if re.search(r"download", d, re.I):
            bb = B(nd); cy = (bb[1] + bb[3]) // 2; cx = (bb[0] + bb[2]) // 2
            dist = abs(cy - tcy) * 2 + abs(cx - (tb[0] + tb[2]) // 2) * 0.5
            if abs(cy - tcy) < 260 and dist < best: best = dist; dl = nd
    if dl is not None:
        tap(*ctr(dl), 3); note("THEME: tapped download")
        for w in range(24):
            time.sleep(5)
            r2 = dump("th_%d_dlwait_%d" % (k, w)); s2 = _sig(r2)
            if re.search(r"sign in|microsoft account|log in", s2, re.I):
                shot("th_%d_05_signin" % k); note("THEME: sign-in prompt:", s2[:300]); key("KEYCODE_BACK", 1.5); return False
            n2 = find(r2, "pumpkin")
            if n2 is None: continue
            still = False
            b2 = B(n2); c2 = (b2[1] + b2[3]) // 2
            for nd in r2.iter("node"):
                d = (nd.get("content-desc") or nd.get("text") or "")
                if re.search(r"download", d, re.I) and abs(((B(nd)[1] + B(nd)[3]) // 2) - c2) < 260: still = True
            if not still: note("THEME: download finished after ~%ds" % (5 * (w + 1))); break
        shot("th_%d_05_after_download" % k)
    else:
        note("THEME: no download control near Pumpkin (already downloaded?)")
    r3 = dump("th_%d_sel" % k); n3 = find(r3, "pumpkin")
    if n3 is None:
        rr, n3 = scan("th_%d_rescan" % k, 15)
        r3 = rr
    if n3 is None: note("THEME: lost Pumpkin after download"); return False
    # tap the card image (above the label when the label is a TextView)
    b3 = B(n3); x, y = (b3[0] + b3[2]) // 2, (b3[1] + b3[3]) // 2
    if (n3.get("class") or "").endswith("TextView"): y = max(b3[1] - 120, 200)
    tap(x, y, 5); clear_dialogs("th_%d_afsel" % k, 2)
    shot("th_%d_06_after_select" % k); note("THEME after select:", _sig(dump("th_%d_06" % k))[:400])
    th = sh("grep -i -E 'theme' %s | head -30" % H.PF)
    if "c59041a7-85d7-4ba3-8f9c-bbcb0681a158" in th: note("THEME: pref_keyboard_theme_key is the Nov 1 Pumpkin id"); save("sys/theme_prefs_attempt%d.txt" % k, th)
    note("THEME prefs:", th.strip().replace("\n", " ")[:500])
    return True

def verify_pumpkin(tag, open_target):
    key("KEYCODE_HOME", 1); open_target(); time.sleep(3)
    im = shot(tag)
    if im is None: return False, 0
    score, mean = keyboard_orange_score(im)
    note("THEME verify %s: orange fraction %.3f mean rgb %s" % (tag, score, mean))
    return score > 0.008, score

def ensure_pumpkin(open_target):
    base_im = None
    key("KEYCODE_HOME", 1); open_target(); time.sleep(3)
    im0 = shot("th_00_keyboard_before_theme")
    s0 = keyboard_orange_score(im0)[0] if im0 is not None else 0
    note("THEME: default-theme orange fraction %.3f" % s0)
    pre = sh("grep -i -E 'theme' %s | head -30" % H.PF); save("sys/theme_prefs_before.txt", pre)
    t0 = time.time(); k = 0
    while time.time() - t0 < THEME_BUDGET:
        k += 1
        try:
            sel = pumpkin_attempt(k)
        except Exception:
            note("THEME attempt error", traceback.format_exc()[-600:]); sel = False
        if sel:
            ok, sc = verify_pumpkin("th_%d_07_keyboard" % k, open_target)
            post = sh("grep -i -E 'theme' %s | head -30" % H.PF)
            changed = post.strip() != pre.strip()
            note("THEME: prefs changed vs before: %s" % changed)
            if ok and sc > s0 + 0.006:
                note("THEME OK: Pumpkin applied after %ds, attempt %d" % (time.time() - t0, k))
                H.apply_prefs(); sh("am force-stop %s" % PKG); H.ensure_ime("after theme")
                th2 = sh("grep -E 'pref_keyboard_theme_key' %s" % H.PF); note("THEME key after prefs:", th2.strip())
                save("THEME_OK.txt", "Pumpkin applied attempt %d, orange %.3f (default %.3f)\n" % (k, sc, s0))
                return True
        sh("am force-stop %s" % PKG); time.sleep(3)
        note("THEME: net check", sh("ping -c1 -W3 8.8.8.8 | tail -2").strip()[:120])
        time.sleep(10)
    note("THEME FAILED: Pumpkin not applied within %ds; stopping (no default-theme fallback)" % THEME_BUDGET)
    save("THEME_FAILED.txt", "Pumpkin not applied within %ds\n" % THEME_BUDGET)
    return False

# ---------------- deep settings walk ----------------
SKIP_ROW = re.compile(r"^(back|navigate up|sign in.*|sign out|log ?out|delete.*|clear.*|reset.*|erase.*|remove.*|uninstall|"
                      r"import.*|restore.*|export.*|back ?up.*|backup now|send feedback|rate .*|share .*|check now|switch|search|"
                      r"more options|filter themes|download theme|close app|wait)$", re.I)

def clickable_rows(r, pkg_hint=None, top=230, bottom=2330):
    rows = []
    if r is None: return rows
    for n in r.iter("node"):
        if n.get("clickable") != "true": continue
        if pkg_hint and n.get("package") and pkg_hint not in n.get("package"): continue
        b = B(n)
        if b[3] - b[1] < 60 or b[1] < top or b[3] > bottom + 60: continue
        if b[2] - b[0] < 300: continue  # rows span the width; small icons are not rows
        tx = [(m.get("text") or m.get("content-desc") or "").strip() for m in n.iter("node")]
        tx = [t for t in tx if t]
        if not tx: continue
        chk = [m for m in n.iter("node") if m.get("checkable") == "true"]
        state = None
        if chk: state = chk[0].get("checked") == "true"
        rows.append({"title": tx[0][:60], "texts": tx[:4], "b": b, "switch": bool(chk), "checked": state, "node": n})
    rows.sort(key=lambda x: x["b"][1])
    return rows

def is_dialog(r):
    if r is None: return False
    for n in r.iter("node"):
        rid = n.get("resource-id") or ""
        if "alertTitle" in rid or rid.startswith("android:id/button"): return True
    tx = [t.strip().lower() for t in texts(r)]
    return ("cancel" in tx and ("ok" in tx or "done" in tx or "save" in tx or "apply" in tx)) or len(tx) <= 6 and "cancel" in tx

def on_page(r, title, pkg_hint):
    if r is None: return False
    first = r.find(".//node")
    pk = first.get("package") if first is not None else ""
    if pkg_hint and pkg_hint not in (pk or ""): return False
    return title is None or find(r, re.escape(title)) is not None

def page_shots(tag, max_s=6):
    unblock(tag + "_pub"); H.to_top(); prev = None; names = []
    for i in range(max_s):
        nm = "%s_%d" % (tag, i); shot(nm); names.append(nm)
        r = dump(nm); s = _sig(r)
        if s == prev: break
        prev = s; scroll(True)
    H.to_top()
    return names

def walk(navigate, path, tag, depth, tree, deadline, pkg_hint):
    """we are ON the page reached by path; record it, then (depth>0) open every non-switch row once"""
    node = {"path": path, "shots": page_shots(tag), "rows": []}
    r0 = dump(tag + "_t"); node["texts"] = texts(r0)[:120]
    tree.append(node)
    if depth <= 0: return
    done = set(); stalls = 0
    page_title = path[-1] if path else None
    while time.time() < deadline:
        unblock(tag + "_ub")
        r = dump(tag + "_rows")
        rows = [x for x in clickable_rows(r, pkg_hint) if x["title"] not in done]
        if not rows:
            before = _sig(r); scroll(True); after = _sig(dump(tag + "_rs"))
            if after == before:
                stalls += 1
                if stalls >= 1: break
            continue
        row = rows[0]; done.add(row["title"])
        ent = {"title": row["title"], "texts": row["texts"], "switch": row["switch"], "checked": row["checked"], "bounds": row["b"]}
        node["rows"].append(ent)
        if row["switch"] or SKIP_ROW.match(row["title"]):
            ent["action"] = "recorded (switch state)" if row["switch"] else "skipped (risky or external)"; continue
        before = _sig(r)
        tap(*ctr(row["node"]), 2.5)
        r2 = dump(tag + "_after"); after = _sig(r2)
        pk = r2.find(".//node").get("package") if r2 is not None and r2.find(".//node") is not None else ""
        if after == before:
            ent["action"] = "no visible change"; continue
        sub = "%s_%02d" % (tag, len(node["rows"]))
        ent["opened"] = sub; ent["opened_texts"] = texts(r2)[:60]; ent["package"] = pk
        if is_dialog(r2):
            ent["kind"] = "dialog"; shot(sub)
        elif depth >= 2:
            walk(navigate, path + [row["title"]], sub, depth - 1, tree, deadline, pkg_hint)
        else:
            shot(sub); scroll(True); shot(sub + "_b"); H.to_top()
        key("KEYCODE_BACK", 1.6)
        r3 = dump(tag + "_back")
        if not on_page(r3, page_title, pkg_hint):
            note("walk: not back on", path, "- renavigating"); navigate(path)
        # restore scroll so the next undone row is visible: rows are taken from the fresh dump each loop

def deep_settings(home, pkg_hint, prefix, budget_s):
    deadline = time.time() + budget_s
    tree = []
    def navigate(path):
        home()
        for t in path:
            if not tap_text_scroll(re.escape(t), "nav", 8, exact=False):
                note("navigate: could not reach", t); return False
        return True
    home()
    walk(navigate, [], prefix + "home", 2, tree, deadline, pkg_hint)
    # second level: pages reached from home are walked with depth 1 inside walk() already (depth 2 from home)
    save("settings_tree.json", json.dumps(tree, indent=1, default=str))
    note("settings walk: %d pages recorded, %s left" % (len(tree), int(deadline - time.time())))
    return tree

# ---------------- keyboard extras ----------------
def every_longpress(KM, open_target, clear_field):
    open_target(); clear_field()
    for k in list("qwertyuiopasdfghjklzxcvbnm") + ["N%d" % i for i in range(10)] + [".", ","]:
        if k in KM:
            nm = {".": "period", ",": "comma"}.get(k, k)
            H.press_hold_shot(KM[k][0], KM[k][1], "40_lp_%s" % nm, 1.0); clear_field()

def nextword_probes(KM, open_target, clear_field, type_keys, field):
    open_target(); clear_field(); time.sleep(1); shot("41_strip_empty_field")
    for i, s_ in enumerate(["how are ", "i will ", "thank you ", "see you ", "hello, "]):
        clear_field(); time.sleep(0.6); type_keys(s_, KM); time.sleep(1.5)
        shot("41_nextword_%d" % i); note("NEXTWORD after %r FIELD %r" % (s_, field()))
        r = H.try_dump_ime("41nw%d" % i)
        if r is not None:
            labs = [(n.get("content-desc") or n.get("text") or "") for n in r.iter("node")]
            note("NEXTWORD labels:", [l for l in labs if l][:30])
    clear_field()

def longpress_timing(KM, open_target, clear_field):
    sh("settings put system show_touches 1"); open_target(); clear_field()
    def act():
        for k in ("e", "a", "N1"):
            if k in KM:
                x, y = KM[k]; sh("input motionevent DOWN %d %d" % (x, y)); time.sleep(1.5); sh("input motionevent UP %d %d" % (x, y)); time.sleep(1.2)
                clear_field()
    record("lp_timing", 14, act)
    sh("settings put system show_touches 0")
    save("geom/lp_timing_keys.json", json.dumps({k: KM[k] for k in ("e", "a", "N1") if k in KM}))
