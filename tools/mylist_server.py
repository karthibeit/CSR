#!/usr/bin/env python3
"""MyList server v2 — runs on Karthi's Raspberry Pi.

Serves a phone-friendly web app (home-screen icon, manifest) for
adding links, VERIFIES each pasted link before it joins the list
(HubCloud drive pages must load with a download button, Pixeldrain
files must exist, direct files must answer, other pages must load),
and serves the plain-text list the MyList CloudStream plugin reads.
Data file: links.txt next to this script, one entry per line —
a bare address, or address|title.
"""
import html
import json
import os
import re
import struct
import urllib.error
import urllib.parse
import urllib.request
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

PORT = 8765
BASE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(BASE, "links.txt")
UA = {
    "User-Agent": "Mozilla/5.0 (Linux; Android 14; SM-S938B) "
                  "AppleWebKit/537.36 (KHTML, like Gecko) "
                  "Chrome/120.0.0.0 Mobile Safari/537.36"
}

# ---------------------------------------------------------------
# The list
# ---------------------------------------------------------------

def load_links():
    if not os.path.exists(DATA):
        return []
    out = []
    with open(DATA, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                out.append(line)
    return out


def save_links(links):
    with open(DATA, "w", encoding="utf-8") as f:
        for line in links:
            f.write(line + "\n")


def split_entry(entry):
    url, _, title = entry.partition("|")
    return url.strip(), title.strip()


def display_title(entry):
    url, title = split_entry(entry)
    if title:
        return title
    tail = unquote(url.split("?")[0].rstrip("/").rsplit("/", 1)[-1])
    return tail or url


# ---------------------------------------------------------------
# Link verification
# ---------------------------------------------------------------

def fetch(url, method="GET", ranged=False, timeout=15):
    req = urllib.request.Request(url, method=method, headers=dict(UA))
    if ranged:
        req.add_header("Range", "bytes=0-1023")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        body = r.read(65536) if method == "GET" else b""
        return r.status, r.headers, body


def page_title(text):
    m = re.search(r"<title>(.*?)</title>", text, re.S | re.I)
    if not m:
        return ""
    return html.unescape(m.group(1)).strip()


def verify(url):
    """Returns (title, error): title is a display name or None,
    error is a human-readable reason or None when the link is good."""
    low = url.lower()
    host = urlparse(url).netloc.lower()
    try:
        if "hubcloud" in host and ("/drive/" in low or "/video/" in low):
            status, headers, body = fetch(url)
            text = body.decode("utf-8", "replace")
            title = page_title(text)
            if "just a moment" in title.lower():
                return None, ("HubCloud answered with a Cloudflare "
                              "check - could not verify the link")
            if 'id="download"' not in text:
                return None, ("HubCloud page has no download button "
                              "- the link looks dead")
            return (title or None), None
        if "hubdrive" in host:
            status, headers, body = fetch(url)
            text = body.decode("utf-8", "replace")
            if "hubcloud" not in text.lower():
                return None, "HubDrive page did not lead to HubCloud"
            return (page_title(text) or None), None
        if "pixeldrain.com" in host and "/u/" in low:
            fid = (url.split("/u/")[1].split("/")[0]
                   .split("?")[0])
            status, headers, body = fetch(
                "https://pixeldrain.com/api/file/" + fid + "/info")
            info = json.loads(body.decode("utf-8", "replace"))
            if info.get("success"):
                return info.get("name"), None
            return None, "Pixeldrain says this file does not exist"
        path = urlparse(url).path.lower()
        if path.endswith((".mkv", ".mp4", ".m3u8", ".avi", ".mov",
                          ".webm")):
            try:
                status, headers, body = fetch(url, method="HEAD")
            except Exception:
                status, headers, body = fetch(url, ranged=True)
            if status in (200, 206):
                tail = unquote(url.split("?")[0].rstrip("/")
                               .rsplit("/", 1)[-1])
                return (tail or None), None
            return None, "File server answered " + str(status)
        status, headers, body = fetch(url, ranged=True)
        if status in (200, 206):
            text = body.decode("utf-8", "replace")
            return (page_title(text) or None), None
        return None, "Site answered " + str(status)
    except urllib.error.HTTPError as e:
        return None, "Site answered " + str(e.code)
    except Exception as e:
        return None, "Could not reach the link (" + \
            e.__class__.__name__ + ")"


# ---------------------------------------------------------------
# App icon (drawn, no image files needed)
# ---------------------------------------------------------------

def make_png(size):
    rows = []
    for y in range(size):
        row = bytearray(b"\x00")
        fy = y / size
        for x in range(size):
            fx = x / size
            in_tri = False
            if 0.34 <= fx <= 0.70:
                half = (fx - 0.34) / 0.36 * 0.26
                if abs(fy - 0.5) <= 0.26 - half:
                    in_tri = True
            if in_tri:
                row += bytes((255, 209, 0, 255))
            else:
                row += bytes((24, 26, 32, 255))
        rows.append(bytes(row))
    raw = b"".join(rows)

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        c += struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        return c

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) +
            chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))


ICON192 = make_png(192)
ICON512 = make_png(512)

MANIFEST = json.dumps({
    "name": "MyList",
    "short_name": "MyList",
    "start_url": "/",
    "scope": "/",
    "display": "standalone",
    "background_color": "#181a20",
    "theme_color": "#181a20",
    "icons": [
        {"src": "/icon-192.png", "sizes": "192x192",
         "type": "image/png"},
        {"src": "/icon-512.png", "sizes": "512x512",
         "type": "image/png"},
    ],
})

SW = (
    "const C='mylist-v2';"
    "self.addEventListener('install',e=>{e.waitUntil("
    "caches.open(C).then(c=>c.addAll(['/',"
    "'/manifest.webmanifest','/icon-192.png','/icon-512.png']))"
    ".then(()=>self.skipWaiting()))});"
    "self.addEventListener('fetch',e=>{"
    "if(e.request.method!=='GET')return;"
    "e.respondWith(fetch(e.request).catch(()=>"
    "caches.match(e.request)))});"
)

PAGE = """<!doctype html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="theme-color" content="#181a20">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black">
<link rel="manifest" href="/manifest.webmanifest">
<link rel="icon" href="/icon-192.png">
<title>MyList</title>
<style>
body{font-family:sans-serif;background:#181a20;color:#eee;
margin:0 auto;max-width:640px;padding:1em}
h2{margin:.2em 0 .6em}
input[type=url]{width:100%;padding:.7em;font-size:1em;box-sizing:border-box;
border-radius:.5em;border:1px solid #444;background:#22242c;color:#eee}
button{padding:.6em 1.1em;font-size:1em;border:0;border-radius:.5em;
background:#ffd100;color:#111;font-weight:bold}
button.rm{background:#33363f;color:#eee;font-weight:normal;
padding:.35em .8em;font-size:.9em}
#status{margin:.8em 0;min-height:1.2em}
.ok{color:#7dd87d}.bad{color:#ff8080}
li{margin:.7em 0;word-break:break-all}
small{color:#999}
</style></head>
<body>
<h2>MyList</h2>
<form id="addform">
<input type="url" id="url" placeholder="Paste a HubCloud or video link" required>
<p><button type="submit" id="addbtn">Verify &amp; Add</button></p>
</form>
<div id="status"></div>
<h3>Your list (<span id="count">0</span>)</h3>
<ul id="list"></ul>
<script>
function esc(s){return s.replace(/&/g,"&amp;").replace(/</g,"&lt;")}
function setStatus(msg, ok){
  var el=document.getElementById("status");
  el.innerHTML=msg; el.className=ok?"ok":"bad";
}
function render(items){
  document.getElementById("count").textContent=items.length;
  var ul=document.getElementById("list"); ul.innerHTML="";
  items.forEach(function(it, i){
    var li=document.createElement("li");
    li.innerHTML=esc(it.title)+" ";
    var b=document.createElement("button");
    b.textContent="Remove"; b.className="rm";
    b.onclick=function(){ del(i); };
    li.appendChild(b);
    var sm=document.createElement("small");
    sm.innerHTML="<br>"+esc(it.url);
    li.appendChild(sm);
    ul.appendChild(li);
  });
}
function refresh(){
  fetch("/list.json").then(function(r){return r.json()})
    .then(render).catch(function(){});
}
function del(i){
  fetch("/delete",{method:"POST",
    headers:{"Content-Type":"application/x-www-form-urlencoded"},
    body:"index="+i}).then(refresh);
}
document.getElementById("addform").addEventListener("submit",
function(ev){
  ev.preventDefault();
  var u=document.getElementById("url").value.trim();
  if(!u) return;
  var btn=document.getElementById("addbtn");
  btn.disabled=true; setStatus("Checking the link...", true);
  fetch("/add",{method:"POST",
    headers:{"Content-Type":"application/x-www-form-urlencoded"},
    body:"url="+encodeURIComponent(u)})
  .then(function(r){return r.json()})
  .then(function(res){
    btn.disabled=false;
    if(res.ok){
      setStatus("Added: "+esc(res.title), true);
      document.getElementById("url").value="";
    } else {
      setStatus("Not added: "+esc(res.error||"unknown error"), false);
    }
    refresh();
  }).catch(function(){
    btn.disabled=false;
    setStatus("Could not reach the Pi server", false);
  });
});
if("serviceWorker" in navigator &&
   window.location.protocol==="https:"){
  navigator.serviceWorker.register("/sw.js").catch(function(){});
}
refresh();
</script>
</body></html>"""

# ---------------------------------------------------------------
# HTTP handler
# ---------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    def _send(self, body, ctype="text/html; charset=utf-8", code=200):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _json(self, obj, code=200):
        self._send(json.dumps(obj), "application/json", code)

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/list.txt":
            text = "\n".join(load_links())
            self._send(text + ("\n" if text else ""),
                       "text/plain; charset=utf-8")
            return
        if path == "/list.json":
            items = []
            for e in load_links():
                url, _ = split_entry(e)
                items.append({"url": url, "title": display_title(e)})
            self._json(items)
            return
        if path == "/manifest.webmanifest":
            self._send(MANIFEST, "application/manifest+json")
            return
        if path == "/icon-192.png":
            self._send(ICON192, "image/png")
            return
        if path == "/icon-512.png":
            self._send(ICON512, "image/png")
            return
        if path == "/sw.js":
            self._send(SW, "text/javascript")
            return
        self._send(PAGE)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        form = parse_qs(self.rfile.read(length).decode("utf-8"))
        path = self.path.split("?")[0]
        links = load_links()
        if path == "/add":
            url = (form.get("url") or [""])[0].strip()
            if not url.startswith("http"):
                self._json({"ok": False,
                            "error": "That does not look like a link"},
                           400)
                return
            for e in links:
                if split_entry(e)[0] == url:
                    self._json({"ok": False,
                                "error": "Already in your list"}, 409)
                    return
            title, error = verify(url)
            if error:
                self._json({"ok": False, "error": error}, 422)
                return
            links.append(url + ("|" + title if title else ""))
            save_links(links)
            self._json({"ok": True,
                        "title": title or display_title(url)})
            return
        if path == "/delete":
            try:
                idx = int((form.get("index") or ["-1"])[0])
                if 0 <= idx < len(links):
                    del links[idx]
                    save_links(links)
            except ValueError:
                pass
            self._json({"ok": True})
            return
        self._json({"ok": False, "error": "unknown path"}, 404)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
