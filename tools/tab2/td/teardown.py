#!/usr/bin/env python3
# Symmetric keyboard teardown driver: the SAME steps for whichever keyboard KB names (wren | swift).
# Reference/comparison only. Runs inside reactivecircus/android-emulator-runner.
import os, sys, time, re, json, glob, traceback, subprocess
sys.path.insert(0, os.path.dirname(__file__))
import skhelpers as H
import tab2 as T2
from skhelpers import note, run, sh, shot, tap, key, dump, B, ctr, texts, find, scroll, clear_dialogs, keymap, try_dump_ime, field, clear_field, press_hold_shot, record, field_times, save
from PIL import Image

KB = os.environ.get("KB", "swift")
PART = os.environ.get("PART", "kb")
SWPKG = "com.touchtype.swiftkey"
TPKG = "com.wrenboard.imetarget"
T0 = time.time()
STEPS = []
def step(name):
    STEPS.append(name); note("== STEP", name)

def dismiss_blockers(tag="ob"):
    for i in range(3):
        r = try_dump_ime("%s_%d" % (tag, i))
        if r is None: return
        tx = "|".join(texts(r))
        hit = None
        if "Choose input method" in tx:
            key("KEYCODE_BACK", 1.0); note("closed IME picker"); continue
        if "Language(s) enabled" in tx: hit = find(r, "OK", exact=True)
        elif "voice typing" in tx.lower() and ("Continue" in tx or "Cancel" in tx): hit = find(r, "Cancel", exact=True)
        elif "isn't responding" in tx: hit = find(r, "Wait", exact=True)
        elif "Welcome to SwiftKey" in tx and "Get started" in tx:
            cb = find(r, "Don't show me more tips", exact=True)
            if cb is not None: tap(*ctr(cb), 1.0)
            hit = find(r, "Get started", exact=True)
        elif "Physical keyboard is enabled" in tx: hit = find(r, "Not now", exact=True)
        elif "Welcome to SwiftKey" in tx and False: hit = None
        if hit is None: return
        tap(*ctr(hit), 1.5); note("dismissed blocker:", tx[:120])

def open_target(t=None):
    sh("am start -S -W -n %s/.TestInput%s" % (TPKG, (" --es t %s" % t) if t else ""), 30); time.sleep(1.5)
    tap(540, 300, 2.5)
    dismiss_blockers()
    cur = sh("settings get secure default_input_method").strip()
    if IME and cur != IME:
        note("default IME drifted to", cur, "- resetting"); sh("ime set %s" % IME); time.sleep(1.5); tap(540, 300, 2.0)

def ime_nodes(tag, pkg):
    r = try_dump_ime(tag)
    out = []
    if r is None: return r, out
    for n in r.iter("node"):
        if pkg and n.get("package") != pkg: continue
        d = (n.get("content-desc") or n.get("text") or "").strip()
        out.append((d, B(n), n))
    return r, out

def ime_label_find(tag, pkg, pat, skip=None):
    r, nodes = ime_nodes(tag, pkg)
    for d, b, n in nodes:
        if d and re.search(pat, d, re.I) and not (skip and re.search(skip, d, re.I)):
            return n
    return None

def a11y_keys(tag, pkg):
    r, nodes = ime_nodes(tag, pkg)
    km = {}
    labels = []
    for d, b, n in nodes:
        if not d: continue
        labels.append([d, list(b)])
        c = ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
        dl = d.lower()
        if dl.startswith("capital "): dl = dl[8:]
        if len(dl) == 1 and dl.isalpha(): km[dl] = c
        elif len(dl) == 1 and dl.isdigit(): km["N" + dl] = c
        elif dl in (".", "period"): km["."] = c
        elif dl.startswith(",") or dl == "comma": km[","] = c
        elif dl in ("space", "space bar"): km["SPACE"] = c
        elif dl in ("delete", "backspace"): km["BKSP"] = c
        elif "shift" in dl or "caps" in dl: km["SHIFT"] = c
        elif "symbol" in dl or dl in ("?123", "123"): km["SYM"] = c
        elif "emoji" in dl: km.setdefault("EMOJI", c)
        elif dl in ("enter", "return", "done", "go", "search", "next"): km["ENTER"] = c
    save("geom/%s_a11y_labels.json" % tag, json.dumps(labels))
    note("a11y keys %s (%s): %d labelled nodes; keys %s" % (tag, pkg, len(labels), sorted(k for k in km if len(k) > 1)))
    return km, labels

def kbpkg():
    return SWPKG if KB == "swift" else WPKG

def merged_km(tag):
    im = shot(tag)
    pk = keymap(im, tag) if im is not None else {}
    ak, labels = a11y_keys(tag, kbpkg())
    km = dict(pk)
    if all(k in ak for k in "qaz"):
        for k, v in ak.items(): km[k] = v
    else:
        for k, v in ak.items(): km.setdefault(k, v)
    note("keymap %s: pixel %d keys, a11y %d keys, merged has %s" % (tag, len(pk), len(ak), [k for k in ("q", "a", "z", "SHIFT", "BKSP", "SYM", "EMOJI", ".", ",", "SPACE", "ENTER", "N1") if k in km]))
    save("geom/%s_km.json" % tag, json.dumps({k: v for k, v in km.items() if not k.startswith("_")}))
    return km, labels

def type_keys(s, km, gap=0):
    cmds = []
    for ch in s:
        k = {" ": "SPACE", ".": ".", ",": ","}.get(ch, ch.lower())
        if k == "," and k not in km: k = "LEFT_OF_SPACE"
        if k not in km: note("type: no key for %r" % ch); continue
        cmds.append("input tap %d %d" % km[k])
    for i in range(0, len(cmds), 12):
        sh("; ".join(cmds[i:i + 12]), 120)
    time.sleep(1.0)

def glide(word, km, steps=6):
    pts = [km[c] for c in word if c in km]
    if len(pts) < 2: return
    cmds = ["input motionevent DOWN %d %d" % pts[0]]
    for a, b in zip(pts, pts[1:]):
        for i in range(1, steps + 1):
            x = a[0] + (b[0] - a[0]) * i // steps; y = a[1] + (b[1] - a[1]) * i // steps
            cmds.append("input motionevent MOVE %d %d" % (x, y))
    cmds.append("input motionevent UP %d %d" % pts[-1])
    t = time.time(); sh("; ".join(cmds), 120)
    note("glide %s: %d events in %.1fs" % (word, len(cmds), time.time() - t)); time.sleep(1.2)

def back_to_letters():
    key("KEYCODE_BACK", 0.8); open_target(); clear_field()

def safe(fn):
    try:
        fn()
    except Exception:
        note("STEP ERROR", fn.__name__, traceback.format_exc()[-600:])
        try:
            key("KEYCODE_HOME", 1)
        except Exception:
            pass

WPKG = "com.wrenboard.keyboard"
KM = {}
IME = None

def setup():
    global IME, WPKG
    run(["adb", "wait-for-device"], 120)
    note("root:", run(["adb", "root"], 30).strip()); time.sleep(4); run(["adb", "wait-for-device"], 60)
    note("abi:", sh("getprop ro.product.cpu.abilist").strip(), "| sdk", sh("getprop ro.build.version.sdk").strip())
    sh("wm size 1080x2400"); sh("wm density 480"); time.sleep(2)
    sh("cmd uimode night yes"); sh("settings put system screen_off_timeout 1800000"); sh("svc power stayon true")
    sh("settings put system accelerometer_rotation 0"); sh("settings put system user_rotation 0")
    sh("input keyevent KEYCODE_WAKEUP"); sh("wm dismiss-keyguard")
    sh("settings put secure show_ime_with_hard_keyboard 1")
    note("disable google voice ime:", sh("pm disable-user --user 0 com.google.android.tts").strip()[:120])
    note("install target:", run(["adb", "install", "-r", "-g", "tools/ime-target/out/imetarget.apk"], 120).strip()[-40:])
    if KB == "swift":
        skdir = os.path.join(os.environ.get("RUNNER_TEMP", "/tmp"), "sk")
        lst = open(os.path.join(skdir, "install-list.txt")).read().split()
        r = run(["adb", "install-multiple", "-r", "-g"] + lst, 400); note("install SwiftKey:", r.strip()[-100:])
        if "Success" not in r: note("FAIL: SwiftKey did not install"); return False
        IME = SWPKG + "/com.touchtype.KeyboardService"
    else:
        apk = os.path.join(os.environ.get("RUNNER_TEMP", "/tmp"), "wb", "WrenBoard-1.5.apk")
        r = run(["adb", "install", "-r", "-g", apk], 400); note("install WrenBoard:", r.strip()[-100:])
        if "Success" not in r: note("FAIL: WrenBoard did not install"); return False
        for i in range(10):
            time.sleep(2)
            for l in sh("ime list -a -s").split():
                if "touchtype" not in l and "google" not in l and "/" in l: IME = l.strip()
            if IME: break
        WPKG = IME.split("/")[0] if IME else WPKG
    note("IME:", IME)
    for i in range(6):
        sh("ime enable %s" % IME); sh("ime set %s" % IME); time.sleep(1.5)
        if sh("settings get secure default_input_method").strip() == IME: break
    note("disable Gboard:", sh("pm disable-user --user 0 com.google.android.inputmethod.latin").strip())
    note("default IME:", sh("settings get secure default_input_method").strip())
    note("package version:", sh("dumpsys package %s | grep -m2 -E 'versionName|versionCode'" % kbpkg()).strip().replace("\n", " "))
    if KB == "swift":
        sh("monkey -p %s -c android.intent.category.LAUNCHER 1" % SWPKG); time.sleep(6)
        shot("00_first_launch"); clear_dialogs("fl", 3)
        sh("am start -n %s/com.touchtype.materialsettingsx.NavigationActivity" % SWPKG); time.sleep(4)
        key("KEYCODE_HOME", 1)
        H.apply_prefs()
    else:
        sh("monkey -p %s -c android.intent.category.LAUNCHER 1" % WPKG); time.sleep(6)
        shot("00_first_launch"); clear_dialogs("fl", 3); key("KEYCODE_HOME", 1)
    for i in range(4):
        if sh("settings get secure default_input_method").strip() == IME: break
        sh("ime set %s" % IME); time.sleep(1.5)
    return True

def s_rest():
    global KM
    step("01_letters_dark")
    open_target(); time.sleep(1.5)
    r = try_dump_ime("01pre")
    n = None
    if r is not None:
        for nd in r.iter("node"):
            if nd.get("package") == kbpkg() and (nd.get("text") or "").strip() in ("OK", "Got it", "Dismiss", "Close"): n = nd
    if n is not None: tap(*ctr(n), 1.2); note("dismissed IME notice")
    time.sleep(4); dismiss_blockers("01b")
    KM, labels = merged_km("01_letters_dark")
    for attempt in range(3):
        ok = all(k in KM for k in "qaz")
        if ok:
            clear_field(); tap(*KM["q"], 1.0); v = (field() or ""); clear_field()
            note("keymap verify: tapped q -> %r" % v)
            if v.lower() == "q": break
        time.sleep(3); open_target(); time.sleep(3); KM, labels = merged_km("01_letters_dark_retry%d" % attempt)
    step("02_letters_light")
    sh("cmd uimode night no"); time.sleep(2); open_target(); time.sleep(1.5); shot("02_letters_light")
    sh("cmd uimode night yes"); time.sleep(2)

def s_typing():
    step("03_typing")
    open_target(); clear_field()
    def act():
        type_keys("teh quic", KM); shot("03_typing_midword_suggestions")
        type_keys("k brwon fox ", KM); shot("04_typing_after_autocorrect")
    record("typing", 25, act)
    note("FIELD after 'teh quick brwon fox ':", repr(field()))
    tests = ["teh quick brwon fox jumsp over teh lazy dog ", "i cant beleive its alreday tomorow ", "im goign to teh store adn ill be back ", "hello world  this is fine ", "thsi is a tset of autocorect "]
    for i, s_ in enumerate(tests):
        clear_field(); time.sleep(0.8); type_keys(s_, KM); time.sleep(1.2)
        note("TYPED %r -> FIELD %r" % (s_, field()))
        if i in (0, 1): shot("05_sentence_%d" % i)
    clear_field()
    step("06_adb_input_text")
    sh("input text 'teh%squikc%sbrwon'"); time.sleep(1.5); shot("06_adb_input_text")
    note("adb input text -> FIELD %r" % field()); clear_field()

def s_shift():
    step("07_shift")
    open_target(); clear_field()
    tap(*KM["SHIFT"], 1.0); shot("07_shift_once")
    type_keys("h", KM); note("after shift+h FIELD %r" % field())
    sh("input tap %d %d; input tap %d %d" % (KM["SHIFT"] + KM["SHIFT"])); time.sleep(1.0); shot("08_caps_lock_double_tap")
    type_keys("ab", KM); note("after double-shift +ab FIELD %r" % field())
    tap(*KM["SHIFT"], 0.8); clear_field()
    type_keys("hi. ", KM); time.sleep(0.5); shot("08b_autocap_after_period"); note("auto-cap probe FIELD %r" % field()); clear_field()

def s_symbols():
    step("09_symbols")
    open_target(); clear_field()
    tap(*KM["SYM"], 1.2); shot("09_symbols_p1")
    kms, labels = a11y_keys("09sym", kbpkg())
    n2 = None
    for d, b in labels:
        if re.fullmatch(r"(=\\<|1/2|2/2|\{&=|more symbols|\{\[|#\+=|\u00bd|1/3|2/3)", d.strip().lower()): n2 = b
    if n2: tap((n2[0] + n2[2]) // 2, (n2[1] + n2[3]) // 2, 1.2)
    else: tap(*KM["SHIFT"], 1.2)
    shot("10_symbols_p2")
    tap(*KM["SYM"], 1.0); shot("10b_back_to_letters")

def s_popups():
    step("11_longpress")
    open_target(); clear_field()
    for k in ["e", "a", "o", "n", "s", "c", "u", ".", ",", "N1", "q"]:
        if k in KM:
            nm = {".": "period", ",": "comma"}.get(k, k)
            press_hold_shot(KM[k][0], KM[k][1], "11_lp_%s" % nm, 1.1); clear_field()
    def lp():
        for k in ("e", "."):
            if k in KM:
                x, y = KM[k]; sh("input motionevent DOWN %d %d" % (x, y)); time.sleep(1.4)
                sh("input motionevent MOVE %d %d" % (x - 110, y - 160)); time.sleep(0.6); sh("input motionevent UP %d %d" % (x - 110, y - 160)); time.sleep(1)
    record("longpress", 14, lp); note("after long-press-slide FIELD %r" % field()); clear_field()
    step("12_key_preview")
    x, y = KM["g"]; sh("input motionevent DOWN %d %d" % (x, y)); time.sleep(0.05); shot("12_keypress_preview_g"); sh("input motionevent UP %d %d" % (x, y)); time.sleep(0.5); clear_field()

def s_glide():
    step("13_glide")
    open_target(); clear_field()
    def act():
        for w in ("wet", "rip", "ask"):
            a, b = KM[w[0]], KM[w[-1]]
            sh("input swipe %d %d %d %d 450" % (a[0], a[1], b[0], b[1])); time.sleep(1.5)
            shot("13_glide_%s" % w); note("glide (straight swipe) %s FIELD %r" % (w, field()))
    record("glide", 25, act); clear_field()

def s_bksp():
    step("14_backspace_hold")
    open_target(); clear_field()
    type_keys("the quick brown fox jumps over the lazy dog and then some more words to delete here ", KM)
    time.sleep(1.5); run(["adb", "logcat", "-c"], 20)
    def act():
        x, y = KM["BKSP"]; sh("input motionevent DOWN %d %d" % (x, y)); time.sleep(2.0); shot("14_bksp_holding"); time.sleep(2.0); sh("input motionevent UP %d %d" % (x, y)); time.sleep(1)
    record("bksp_hold", 12, act)
    ev = field_times("bksp")
    t = [e[0] for e in ev]; d = [round((t[i + 1] - t[i]) * 1000) for i in range(len(t) - 1)]
    note("BKSP HOLD: %d changes, intervals ms %s, lengths %s" % (len(ev), d[:50], [len(e[1]) for e in ev][:50]))
    shot("14b_after_bksp_hold"); clear_field()

def s_cursor():
    step("15_space_cursor_drag")
    open_target(); clear_field()
    type_keys("hello world again ", KM); time.sleep(0.8)
    x, y = KM["SPACE"]
    def act():
        sh("input swipe %d %d %d %d 700" % (x, y, x - 400, y)); time.sleep(0.8)
        shot("15_space_drag_after_swipe")
        type_keys("x", KM)
    record("space_drag", 20, act)
    note("space-drag then 'x' FIELD %r (cursor moved if x is not at the end)" % field())
    shot("15b_after_space_drag"); clear_field()

def s_emoji_gif():
    step("16_emoji")
    open_target(); clear_field()
    n = ime_label_find("16a", kbpkg(), r"emoji", skip=r"predict")
    if n is not None: tap(*ctr(n), 2.0); how = "a11y emoji node"
    elif "EMOJI" in KM: tap(*KM["EMOJI"], 2.0); how = "EMOJI key"
    elif "LEFT2_OF_SPACE" in KM: tap(*KM["LEFT2_OF_SPACE"], 2.0); how = "left2-of-space"
    else:
        x, y = KM["ENTER"]; press_hold_shot(x, y, "16_enter_longpress", 1.2); how = "enter long-press"
    note("emoji opened via", how)
    shot("16_emoji_panel"); r, nodes = ime_nodes("16b", kbpkg()); note("emoji panel labels:", "|".join(d for d, b, nn in nodes if d)[:600])
    sh("input swipe 540 2000 540 1650 300"); time.sleep(1); shot("16b_emoji_scrolled")
    tab_n = None
    for d, b, nn in nodes:
        if re.search(r"^gif|gifs?$|\bgif\b", d, re.I): tab_n = nn; break
    if tab_n is None:
        back_to_letters()
        rm = ime_label_find("17r", kbpkg(), r"^(rich media|gif|gifs|open action bar)$")
        if rm is not None:
            tap(*ctr(rm), 3.0); shot("17_rich_media_or_actionbar")
            r, nodes = ime_nodes("17s", kbpkg()); note("after rich media/action bar:", "|".join(d for d, b, nn in nodes if d)[:600])
            for d, b, nn in nodes:
                if re.search(r"^gif|gifs?$|\bgif\b", d, re.I): tab_n = nn; break
    step("17_gif")
    if tab_n is not None:
        tap(*ctr(tab_n), 8.0); shot("17_gif_panel")
        r, nodes = ime_nodes("17b", kbpkg()); note("gif panel labels:", "|".join(d for d, b, nn in nodes if d)[:600])
        srch = None
        for d, b, nn in nodes:
            if re.search(r"search", d, re.I): srch = nn; break
        if srch is not None:
            tap(*ctr(srch), 2.0); shot("18_gif_search_open")
            clear_field(); type_keys("cat", KM); time.sleep(1); shot("18a_gif_search_typed")
            note("app field after typing in GIF search (should stay empty): %r" % field())
            tap(*KM["ENTER"], 8); shot("18b_gif_search_cat_8s"); time.sleep(10); shot("18c_gif_search_cat_18s")
            r, nodes = ime_nodes("18c", kbpkg()); note("gif search labels:", "|".join(d for d, b, nn in nodes if d)[:400])
        else:
            note("GIF search field not found")
    else:
        note("GIF tab not found in emoji panel")
    back_to_letters()

def s_clipboard_toolbar():
    step("19_clipboard")
    sh("am broadcast -a com.wrenboard.imetarget.SETCLIP -p %s" % TPKG); time.sleep(1)
    open_target(); clear_field(); time.sleep(1); shot("19a_after_copy_suggestion_strip")
    r, nodes = ime_nodes("19a", kbpkg()); toolbar_labels = [d for d, b, nn in nodes if d and b[1] < KM.get("q", (0, 9999))[1] - 60]
    note("labels above letter rows:", toolbar_labels[:40])
    n = ime_label_find("19b", kbpkg(), r"clipboard")
    if n is None:
        ex = ime_label_find("19c", kbpkg(), r"(expand|more keyboard options|menu|toolbar|action bar|open.*tool|show.*tool)")
        if ex is not None: tap(*ctr(ex), 1.5); shot("20_toolbar_expanded"); n = ime_label_find("19d", kbpkg(), r"clipboard")
    if n is not None:
        tap(*ctr(n), 2.0); shot("19_clipboard_panel")
        key("KEYCODE_BACK", 0.8)
    else:
        note("clipboard entry not found")
    step("20_toolbar")
    open_target(); clear_field()
    ex = ime_label_find("20a", kbpkg(), r"(expand|more keyboard options|menu|toolbar|action bar|open.*tool|show.*tool)")
    if ex is not None:
        tap(*ctr(ex), 1.5); shot("20_toolbar_expanded")
    r, nodes = ime_nodes("20b", kbpkg())
    qy = KM.get("q", (0, 9999))[1]
    items = []
    for d, b, nn in nodes:
        if not d or len(d) < 3: continue
        if re.search(r"voice|mic|camera|bing|copilot|search the web|translat|account|sign", d, re.I): continue
        if re.fullmatch(r"[a-z0-9]", d, re.I): continue
        items.append((d, b))
    note("toolbar/ime labels:", [d for d, b in items][:40])
    done = 0
    for d, b in items:
        if done >= 40: break
        if re.search(r"clipboard|emoji|gif|delete|shift|space|enter|symbol|caps|period|comma", d, re.I): continue
        open_target(); clear_field()
        if ex is not None:
            ex2 = ime_label_find("20x", kbpkg(), r"(expand|more keyboard options|menu|toolbar|action bar|open.*tool|show.*tool)")
            if ex2 is not None: tap(*ctr(ex2), 1.2)
        n2 = ime_label_find("20y", kbpkg(), "^" + re.escape(d) + "$")
        if n2 is None: continue
        tap(*ctr(n2), 2.0); nm = re.sub(r"\W+", "_", d)[:24].strip("_").lower()
        shot("21_tool_%s" % nm); done += 1
        rr = dump("21_%s" % nm); note("tool '%s' opened -> %s" % (d, "|".join(texts(rr))[:200]))
        key("KEYCODE_BACK", 0.8); key("KEYCODE_HOME", 0.8)

def s_fields():
    step("22_field_types")
    for t in ("email", "number", "phone", "password", "url", "search"):
        open_target(t); time.sleep(1); shot("22_field_%s" % t)
    step("22b_contacts_app")
    sh("am start -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact"); time.sleep(4); clear_dialogs("ct", 3)
    r = dump("22ct"); n = None
    if r is not None:
        for nd in r.iter("node"):
            if nd.get("class", "").endswith("EditText"): n = nd; break
    if n is not None: tap(*ctr(n), 2.0); shot("22c_contacts_name_field")
    else: shot("22c_contacts_no_field"); note("contacts insert: no EditText", "|".join(texts(r))[:200])
    key("KEYCODE_HOME", 1)

def s_landscape():
    step("23_landscape")
    sh("cmd window user-rotation lock 1"); sh("settings put system user_rotation 1"); time.sleep(3); open_target(); time.sleep(2); shot("23_landscape")
    note("landscape wm size:", sh("wm size; dumpsys window displays | grep -m1 -i 'cur='").strip()[:200])
    sh("cmd window user-rotation lock 0"); sh("settings put system user_rotation 0"); time.sleep(3)

def settings_home():
    if KB == "swift":
        sh("am start -S -n %s/com.touchtype.materialsettingsx.NavigationActivity" % SWPKG)
    else:
        sh("am force-stop %s" % WPKG); sh("monkey -p %s -c android.intent.category.LAUNCHER 1" % WPKG)
    time.sleep(4); clear_dialogs("sh", 2)
    r = dump("shchk")
    if r is not None and "Choose input method" in "|".join(texts(r)): key("KEYCODE_BACK", 1.0); note("closed IME picker in settings")

def s_settings():
    step("30_settings")
    settings_home(); shot("30_settings_home")
    seen = []
    def collect(tag):
        r = dump(tag)
        if r is None: return
        for nd in r.iter("node"):
            t = (nd.get("text") or "").strip()
            b = B(nd)
            if t and 280 < b[1] < 2250 and len(t) < 40 and t not in seen and nd.get("class", "").endswith("TextView"):
                seen.append(t)
    collect("30a")
    for i in range(4):
        scroll(True); shot("30_settings_home_s%d" % (i + 1)); collect("30s%d" % i)
    note("settings home texts:", seen[:60])
    # top-level entries: short titles; skip obvious subtitle sentences
    titles = [t for t in seen if len(t.split()) <= 4 and not t.endswith(".")][:22]
    note("settings pages to open:", titles)
    for k, t in enumerate(titles):
        if time.time() - T0 > 38 * 60: note("settings walk stopped (time)"); break
        settings_home()
        ok = False
        for i in range(5):
            r = dump("31f_%d" % k)
            n = find(r, t, exact=True)
            if n is not None:
                tap(*ctr(n), 2.5); ok = True; break
            scroll(True)
        if not ok: continue
        r2 = dump("31_%02d" % k)
        if r2 is not None and find(r2, t, exact=True) is not None and len(texts(r2)) == len(seen):
            continue
        nm = re.sub(r"\W+", "_", t)[:22].strip("_").lower()
        shot("31_%02d_%s" % (k, nm)); scroll(True); shot("31_%02d_%s_b" % (k, nm)); scroll(True); shot("31_%02d_%s_c" % (k, nm))
        note("page '%s': %s" % (t, "|".join(texts(r2))[:400]))
        key("KEYCODE_BACK", 1)

def main():
    note("teardown start KB=%s PART=%s" % (KB, PART))
    if not setup(): return
    if PART == "set":
        if KB == "swift":
            home = lambda: T2.sk_settings_home()
            pk = SWPKG
        else:
            def home():
                sh("am force-stop %s" % WPKG); sh("monkey -p %s -c android.intent.category.LAUNCHER 1" % WPKG); time.sleep(7)
                T2.unblock("wh_ub"); clear_dialogs("wh", 2); T2.close_ime_picker("wh_ip")
            pk = WPKG
        T2.deep_settings(home, pk, "s_", int(os.environ.get("SET_BUDGET", "3000")))
        note("DONE in %ds" % (time.time() - T0)); return
    if KB == "swift":
        if not T2.ensure_pumpkin(open_target):
            note("STOP: no Pumpkin, no keyboard legs (Ezzy: never the default theme)"); return
    want = [x.strip() for x in os.environ.get("LEGS", "").split(",") if x.strip()]
    for fn in (s_rest, s_typing, s_shift, s_symbols, s_popups, s_glide, s_bksp, s_cursor, s_emoji_gif, s_clipboard_toolbar, s_fields, s_landscape, s_extras):
        if fn is not s_rest and not all(k in KM for k in "qaz") and fn not in (s_fields, s_landscape, s_emoji_gif, s_clipboard_toolbar):
            note("SKIP", fn.__name__, "(no keymap)"); continue
        if want and fn.__name__ not in want and fn is not s_rest: note("SKIP (legs)", fn.__name__); continue
        safe(fn)
    if KB == "swift":
        try:
            T2.open_themes(); shot("th_99_themes_page_after"); T2.scan("th_99_yours", 3, "pumpkin")
        except Exception: note("themes page shot failed")
    note("logcat crashes:", run(["adb", "logcat", "-d", "-b", "crash"], 30)[-1500:])
    note("DONE in %ds; steps %s" % (time.time() - T0, STEPS))

def s_extras():
    step("40_every_longpress"); T2.every_longpress(KM, open_target, clear_field)
    step("41_nextword"); T2.nextword_probes(KM, open_target, clear_field, type_keys, field)
    step("42_lp_timing"); T2.longpress_timing(KM, open_target, clear_field)
try:
    main()
except Exception:
    note("EXCEPTION", traceback.format_exc())
finally:
    with open(os.path.join("shots", "sys", "logcat_tail.txt"), "w") as f:
        f.write(run(["adb", "logcat", "-d", "-t", "3000"], 60))
