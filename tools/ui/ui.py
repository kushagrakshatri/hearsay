#!/usr/bin/env python3
"""Tiny adb UI driver for the screenshot tour: find a view by text/description, tap it, scroll to it."""
import re, subprocess, sys, time, xml.etree.ElementTree as ET

def adb(*a, out=False):
    r = subprocess.run(["adb", *a], capture_output=True, text=not out)
    return r.stdout

def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    try:
        root = ET.fromstring(xml[xml.index("<"):])
    except Exception:
        return []
    return list(root.iter("node"))

def find(label):
    for n in nodes():
        if label.lower() in (n.get("text", "") + " " + n.get("content-desc", "")).lower():
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None

def size():
    m = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    return int(m.group(1)), int(m.group(2))

def swipe_up():
    w, h = size()
    adb("shell", "input", "swipe", str(w // 2), str(int(h * 0.75)), str(w // 2), str(int(h * 0.3)), "400")
    time.sleep(0.8)

def tap(label, scroll=True):
    for _ in range(12 if scroll else 1):
        p = find(label)
        if p:
            adb("shell", "input", "tap", str(p[0]), str(p[1])); time.sleep(1.2); return True
        if scroll: swipe_up()
    print(f"not found: {label}", file=sys.stderr); return False

def hold(label, ms):
    p = find(label)
    if not p: print(f"not found: {label}", file=sys.stderr); return
    subprocess.Popen(["adb", "shell", "input", "swipe", str(p[0]), str(p[1]), str(p[0]), str(p[1]), str(ms)])

if __name__ == "__main__":
    cmd, *rest = sys.argv[1:]
    if cmd == "tap": sys.exit(0 if tap(rest[0]) else 1)
    if cmd == "tapnoscroll": sys.exit(0 if tap(rest[0], scroll=False) else 1)
    if cmd == "hold": hold(rest[0], int(rest[1]))
    if cmd == "up": swipe_up()
