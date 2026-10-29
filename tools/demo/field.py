#!/usr/bin/env python3
"""Reads the focused text box on the emulator (via uiautomator), or waits for its text to change and settle."""
import sys, time
sys.path.insert(0, "tools/ui")
import ui

def text():
    ns = [n for n in ui.nodes() if n.get("class", "").endswith("EditText")]
    focused = [n for n in ns if n.get("focused") == "true"] or ns
    if not focused: return ""
    n = focused[0]
    t = n.get("text", "")
    return "" if t in (n.get("hint", ""), "Message", "Compose email") else t

if __name__ == "__main__":
    if sys.argv[1] == "text":
        print(text().replace("\n", "⏎"))
    else:  # wait <before> <timeout>
        before, timeout = sys.argv[2].replace("⏎", "\n"), float(sys.argv[3])
        end = time.time() + timeout; last = None
        while time.time() < end:
            t = text()
            if t != before and t == last: break
            last = t; time.sleep(1.0)
