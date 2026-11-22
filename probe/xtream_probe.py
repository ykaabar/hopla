"""Xtream Codes link checker: login, catalogue counts, and one live stream probe.

Usage:  python xtream_probe.py            (asks for the link, hidden like a password)
        python xtream_probe.py --play     (also opens the first channel in VLC)
        python xtream_probe.py --ua "VLC/3.0.20"   (custom user-agent if the provider locks apps)
        python xtream_probe.py --server http://host:port   (keep the link's account, change the server)
        python xtream_probe.py --selftest
The password is never printed.
"""
import argparse, getpass, json, os, shutil, subprocess, sys, time
import urllib.error, urllib.parse, urllib.request

def parse_link(link):
    """Return (base_url, username, password) from a get.php / player_api.php link."""
    u = urllib.parse.urlsplit(link.strip())
    q = urllib.parse.parse_qs(u.query)
    if u.scheme not in ("http", "https") or not u.netloc:
        raise ValueError("not an http(s) link")
    if "username" not in q or "password" not in q:
        raise ValueError("link has no username/password parameters (not an Xtream link)")
    prefix = u.path.rsplit("/", 1)[0]  # keep folders before get.php, e.g. http://host/iptv/get.php
    return f"{u.scheme}://{u.netloc}{prefix}", q["username"][0], q["password"][0]

class Client:
    def __init__(self, base, user, pw, ua):
        self.base, self.user, self.pw, self.ua = base, user, pw, ua

    def redact(self, s):
        for q in dict.fromkeys((urllib.parse.quote(self.pw, safe=""), urllib.parse.quote_plus(self.pw), self.pw)):
            s = s.replace(f"/{q}/", "/***/").replace(f"password={q}", "password=***")
        return s

    def open(self, url, timeout=20):
        return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": self.ua}), timeout=timeout)

    def api(self, action=None):
        qs = {"username": self.user, "password": self.pw}
        if action: qs["action"] = action
        t = time.perf_counter()
        with self.open(f"{self.base}/player_api.php?{urllib.parse.urlencode(qs)}") as r:
            data = json.loads(r.read() or b"null")
        return data, time.perf_counter() - t

def probe_stream(c, url):
    """Read the first bytes of a stream; MPEG-TS starts with 0x47 every 188 bytes, HLS with #EXTM3U."""
    t = time.perf_counter()
    with c.open(url, timeout=15) as r:
        head = r.read(188 * 8)
        ctype = r.headers.get("Content-Type", "?")
    ms = (time.perf_counter() - t) * 1000
    if head.startswith(b"#EXTM3U"): kind = "HLS playlist"
    elif len(head) >= 377 and head[0] == head[188] == 0x47: kind = "MPEG-TS video"
    else: kind = f"unknown ({head[:16]!r})"
    return kind, ctype, len(head), ms

def run(link, ua, play, server=None):
    base, user, pw = parse_link(link)
    if server: base = server.rstrip("/")  # same account, other server address (e.g. the one ATLAS uses)
    c = Client(base, user, pw, ua)
    print(f"Server : {base}\nUser   : {user}\nUA     : {ua}\n")

    try:
        info, dt = c.api()
    except urllib.error.HTTPError as e:
        print(f"Login  : player_api.php answered HTTP {e.code}")
        if e.code == 404:
            print("-> this server has no Xtream API at that address; trying the link as a plain M3U playlist\n")
            return m3u_fallback(c, link, play)
        print("-> 401/403 usually means wrong credentials or a user-agent lock: retry with --ua \"IPTVSmartersPro\"")
        return 1
    except Exception as e:
        print("LOGIN FAILED:", c.redact(str(e)))
        print("-> 401/403 usually means wrong credentials or a user-agent lock: retry with --ua \"VLC/3.0.20\"")
        return 1
    ui = (info or {}).get("user_info", {})
    if str(ui.get("auth")) != "1":
        print("LOGIN REFUSED by server:", c.redact(json.dumps(info)[:300])); return 1
    exp = ui.get("exp_date")
    exp = time.strftime("%Y-%m-%d", time.localtime(int(exp))) if str(exp).isdigit() else exp
    print(f"Login  : OK ({dt*1000:.0f} ms)  status={ui.get('status')}  expires={exp}  "
          f"connections={ui.get('active_cons')}/{ui.get('max_connections')}  formats={ui.get('allowed_output_formats')}")

    streams = []
    for label, action in [("Live categories", "get_live_categories"), ("Live channels", "get_live_streams"),
                          ("VOD categories", "get_vod_categories"), ("Series categories", "get_series_categories")]:
        try:
            data, dt = c.api(action)
            n = len(data or [])
            print(f"{label:18}: {n:6}  ({dt:.1f} s)")
            if action == "get_live_streams":
                streams = list(data.values()) if isinstance(data, dict) else (data or [])
        except Exception as e:
            print(f"{label:18}: ERROR {c.redact(str(e))}")

    if not streams:
        print("\nNo live channels to probe."); return 1
    fmts = ui.get("allowed_output_formats") or ["ts"]
    exts = sorted(set(fmts) & {"ts", "m3u8"} or {"ts"}, key=lambda x: x != "ts") + ["m3u8", "ts"]
    up = f"{urllib.parse.quote(user)}/{urllib.parse.quote(pw)}"
    # A single dead channel gives 404 too, so try 3 channels x formats x both path styles.
    cands = [(s, f"{base}{p}/{up}/{s.get('stream_id')}.{e}")
             for s in streams[:3] for e in dict.fromkeys(exts) for p in ("/live", "")]
    url = probe_first(c, [(s.get("name"), u) for s, u in cands])
    if not url: return 1
    return finish(c, url, play)

def probe_first(c, cands):
    """Try candidate (name, url) pairs until one returns video; print each attempt."""
    print()
    for name, url in cands:
        try:
            kind, ctype, n, ms = probe_stream(c, url)
            print(f"Stream : OK  '{name}' -> {kind}, {n} bytes in {ms:.0f} ms ({c.redact(url)})")
            return url
        except Exception as e:
            print(f"Stream : FAIL '{name}' {c.redact(url)} -> {c.redact(str(e))}")
    print("\nNo stream could be read.")
    return None

def m3u_fallback(c, link, play):
    try:
        t = time.perf_counter()
        with c.open(link.strip(), timeout=60) as r:
            text = r.read(5_000_000).decode("utf8", "replace")
        print(f"M3U    : downloaded {len(text)//1024} KB in {time.perf_counter()-t:.1f} s")
    except urllib.error.HTTPError as e:
        print(f"M3U    : the link itself answers HTTP {e.code}")
        print("-> the server refuses this account or this address. Check the link in a browser; "
              "if it also fails there, the subscription/URL is the problem, not the app.")
        return 1
    except Exception as e:
        print("M3U    : ERROR", c.redact(str(e))); return 1
    if not text.lstrip().startswith("#EXTM3U"):
        print("M3U    : not a playlist, server sent:", c.redact(text[:200])); return 1
    lines = text.splitlines()
    print(f"M3U    : {sum(l.startswith('#EXTINF') for l in lines)} entries (first 5 MB)")
    cands, name = [], "?"
    for l in lines:
        if l.startswith("#EXTINF"): name = l.rsplit(",", 1)[-1].strip()
        elif l.startswith("http") and len(cands) < 3: cands.append((name, l.strip()))
    url = probe_first(c, cands)
    return finish(c, url, play) if url else 1

def finish(c, url, play):
    if play:
        vlc = shutil.which("vlc") or r"C:\Program Files\VideoLAN\VLC\vlc.exe"
        if os.path.exists(vlc):
            subprocess.Popen([vlc, f":http-user-agent={c.ua}", url])
            print("VLC    : opened (close it before testing elsewhere: it uses one connection)")
        else:
            print("VLC    : not found")
    print("\nRESULT : link is readable by a standard Xtream client.")
    return 0

def selftest():
    b, u, p = parse_link("http://ex.com:8080/get.php?username=ab&password=c%26d&type=m3u_plus&output=ts")
    assert (b, u, p) == ("http://ex.com:8080", "ab", "c&d"), (b, u, p)
    assert parse_link("http://h/iptv/get.php?username=a&password=b")[0] == "http://h/iptv"
    for bad in ("ftp://x/get.php?username=a&password=b", "http://x/list.m3u"):
        try: parse_link(bad); raise AssertionError(bad)
        except ValueError: pass
    assert Client(b, u, p, "x").redact("http://ex.com/live/ab/c%26d/1.ts") == "http://ex.com/live/ab/***/1.ts"
    print("selftest OK")

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--ua", default="VLC/3.0.20 LibVLC/3.0.20")
    ap.add_argument("--play", action="store_true")
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--server", help="server address to use instead of the link's, e.g. http://host:8080")
    a = ap.parse_args()
    if a.selftest: selftest(); sys.exit(0)
    sys.exit(run(getpass.getpass("Paste your Xtream link (hidden): "), a.ua, a.play, a.server))
