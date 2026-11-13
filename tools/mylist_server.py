#!/usr/bin/env python3
"""MyList server v5 — runs on Karthi's Raspberry Pi.

Serves a phone-friendly web app (home-screen icon, manifest) for
adding links, VERIFIES each pasted link before it joins the list
(HubCloud drive pages must load with a download button, Pixeldrain
files must exist, direct files must answer, other pages must load),
fetches the movie's POSTER when a link is added (title + year
lookup against IMDb's keyless suggestion data, strict exact match
only), backfills posters for older entries at startup, and serves
the plain-text list the MyList CloudStream plugin reads.
Each entry also carries a CATEGORY (movie / dubbed / series /
english) picked when the link is added and changeable on the web
page; the plugin shows one home row per category. The page
itself was redesigned in v5: card layout, posters, grouped
by category, quality chips.
Data file: links.txt next to this script, one entry per line —
address|title|poster|category (trailing fields may be omitted).
"""
import html
import json
import os
import re
import struct
import threading
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


CATEGORIES = ("movie", "dubbed", "series", "english")


def clean_category(value):
    v = (value or "").strip().lower()
    return v if v in CATEGORIES else "movie"


def split_entry(entry):
    parts = entry.split("|")
    url = parts[0].strip()
    title = parts[1].strip() if len(parts) > 1 else ""
    poster = parts[2].strip() if len(parts) > 2 else ""
    category = clean_category(parts[3]) if len(parts) > 3 else "movie"
    return url, title, poster, category


def join_entry(url, title, poster, category="movie"):
    parts = [url, title or "", poster or "", clean_category(category)]
    if parts[3] == "movie":
        parts.pop()
    while len(parts) > 1 and not parts[-1]:
        parts.pop()
    return "|".join(parts)


def display_title(entry):
    url, title, _, _ = split_entry(entry)
    if title:
        return title
    tail = unquote(url.split("?")[0].rstrip("/").rsplit("/", 1)[-1])
    return tail or url


# ---------------------------------------------------------------
# Poster lookup (IMDb suggestion data, strict exact match)
# ---------------------------------------------------------------

JUNK_TOKENS = {
    "2160p", "1080p", "720p", "480p", "360p", "4k", "uhd",
    "bluray", "webdl", "webrip", "hdrip", "x264", "x265",
    "h264", "h265", "hevc", "aac", "ddp5", "atmos", "esub",
    "esubs", "nf", "amzn", "multi", "audio", "dubbed",
    "tamil", "telugu", "hindi", "malayalam", "kannada",
    "english", "com", "www", "xdmovies", "kmmovies",
}


def norm_title(s):
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9]+", " ",
                                      s.lower())).strip()


def lookup_title(raw):
    t = re.sub(r"\.(mkv|mp4|avi|mov|webm)$", "", raw, flags=re.I)
    t = re.sub(r"[\[\({][^\]\)\}]*[\]\)\}]", " ", t)
    t = re.sub(r"[._\-]+", " ", t)
    kept = []
    for w in t.split():
        lw = w.lower()
        if re.fullmatch(r"(?:19|20)\d{2}", lw):
            break
        if lw in JUNK_TOKENS:
            break
        kept.append(w)
    return norm_title(" ".join(kept))


def year_of(raw):
    m = re.search(r"(?:19|20)\d{2}", raw)
    return int(m.group(0)) if m else None


def fetch_poster(title):
    """Poster URL for a display title, or "" — strict exact
    title + year match against IMDb's suggestion data; any
    failure simply means no poster."""
    try:
        lookup = lookup_title(title or "")
        if len(lookup) < 2:
            return ""
        year = year_of(title or "")
        url = ("https://v3.sg.media-imdb.com/suggestion/x/" +
               lookup.replace(" ", "%20") + ".json")
        req = urllib.request.Request(url, headers=dict(UA))
        with urllib.request.urlopen(req, timeout=10) as r:
            data = json.loads(r.read().decode("utf-8", "replace"))
        for e in data.get("d", []):
            img = (e.get("i") or {}).get("imageUrl")
            if not img:
                continue
            if e.get("qid") not in (None, "movie"):
                continue
            if norm_title(e.get("l", "")) != lookup:
                continue
            if year is not None and e.get("y") not in (None, year):
                continue
            return img
    except Exception:
        pass
    return ""


def backfill_posters():
    """Fill in posters for entries that lack one (runs in a
    background thread at startup; merges by address so it
    cannot clobber adds/deletes that happen meanwhile)."""
    try:
        wanted = {}
        for e in load_links():
            url, title, poster, _ = split_entry(e)
            if not poster:
                p = fetch_poster(display_title(e))
                if p:
                    wanted[url] = p
        if not wanted:
            return
        links = load_links()
        changed = False
        out = []
        for e in links:
            url, title, poster, category = split_entry(e)
            if not poster and url in wanted:
                e = join_entry(url, title, wanted[url], category)
                changed = True
            out.append(e)
        if changed:
            save_links(out)
    except Exception:
        pass


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
    "background_color": "#0e1013",
    "theme_color": "#0e1013",
    "icons": [
        {"src": "/icon-192.png", "sizes": "192x192",
         "type": "image/png"},
        {"src": "/icon-512.png", "sizes": "512x512",
         "type": "image/png"},
    ],
})

SW = (
    "const C='mylist-v5';"
    "self.addEventListener('install',e=>{e.waitUntil("
    "caches.open(C).then(c=>c.addAll(['/',"
    "'/manifest.webmanifest','/icon-192.png','/icon-512.png']))"
    ".then(()=>self.skipWaiting()))});"
    "self.addEventListener('fetch',e=>{"
    "if(e.request.method!=='GET')return;"
    "e.respondWith(fetch(e.request).catch(()=>"
    "caches.match(e.request)))});"
)

PAGE = r"""<!doctype html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="theme-color" content="#0e1013">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black">
<link rel="manifest" href="/manifest.webmanifest">
<link rel="icon" href="/icon-192.png">
<title>MyList</title>
<style>
:root{--bg:#0e1013;--card:#1a1d23;--card2:#22252d;--line:#2b2f38;
--txt:#f2f3f5;--mut:#9aa3af;--dim:#6f7683;--acc:#ffd100;--ink:#161616}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--txt);
font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;
-webkit-font-smoothing:antialiased}
.wrap{max-width:680px;margin:0 auto;padding:0 14px 30px}
.top{display:flex;align-items:center;gap:10px;padding:18px 2px 12px}
.top img{width:34px;height:34px;border-radius:9px}
.top h1{font-size:1.35rem;margin:0;font-weight:700}
.pill{margin-left:auto;background:var(--card2);color:var(--mut);
border:1px solid var(--line);padding:.28em .75em;border-radius:999px;
font-size:.85rem}
.addcard{background:var(--card);border:1px solid var(--line);
border-radius:16px;padding:14px;box-shadow:0 8px 22px rgba(0,0,0,.35)}
input[type=url]{width:100%;padding:.8em .9em;font-size:1rem;
border-radius:12px;border:1px solid var(--line);
background:#101216;color:var(--txt)}
input[type=url]::placeholder{color:#5f6672}
input[type=url]:focus{outline:2px solid var(--acc);
border-color:transparent}
.row{display:flex;gap:10px;margin-top:10px}
select{border:1px solid var(--line);background:var(--card2);
color:var(--txt);border-radius:12px;padding:.68em .7em;font-size:.95rem}
.row select{flex:1;min-width:0}
#addbtn{flex:0 0 auto;background:var(--acc);color:var(--ink);
font-weight:700;border:0;border-radius:12px;padding:.68em 1.25em;
font-size:1rem}
#addbtn:disabled{opacity:.55}
#status{margin:12px 2px 0;font-size:.95rem;min-height:1.15em}
.ok{color:#7dd87d}.bad{color:#ff8080}
.sec{display:flex;align-items:baseline;gap:8px;margin:24px 2px 10px}
.sec h2{font-size:1.02rem;margin:0;font-weight:700}
.sec span{color:var(--mut);font-size:.85rem}
.list{display:flex;flex-direction:column;gap:10px}
.item{display:flex;gap:12px;background:var(--card);
border:1px solid var(--line);border-radius:16px;padding:10px}
.poster{width:52px;height:78px;border-radius:10px;object-fit:cover;
flex:0 0 auto;background:#0b0d10}
.noposter{display:flex;align-items:center;justify-content:center;
color:var(--acc);font-size:1.25rem;
background:linear-gradient(160deg,#262a33,#131519)}
.main{flex:1;min-width:0}
.ttl{font-weight:600;font-size:.97rem;line-height:1.35;
display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;
overflow:hidden;word-break:break-word}
.meta{display:flex;flex-wrap:wrap;gap:6px;margin-top:6px}
.chip{background:var(--card2);border:1px solid var(--line);
color:var(--mut);font-size:.72rem;padding:.16em .55em;
border-radius:999px}
.url{color:var(--dim);font-size:.75rem;margin-top:6px;
white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.ctrl{display:flex;gap:8px;margin-top:10px}
.ctrl select{flex:1;min-width:0;border-radius:10px;
padding:.5em .6em;font-size:.9rem}
.rm{background:transparent;border:1px solid #54302e;color:#ff8f8f;
border-radius:10px;padding:.5em .95em;font-size:.9rem;font-weight:600}
.rm:active{background:#38211f}
.empty{color:var(--mut);text-align:center;padding:28px 12px;
border:1px dashed var(--line);border-radius:16px;margin-top:14px}
</style></head>
<body>
<div class="wrap">
<div class="top"><img src="/icon-192.png" alt="">
<h1>MyList</h1><div class="pill"><span id="count">0</span> links</div></div>
<form id="addform" class="addcard">
<input type="url" id="url" placeholder="Paste a HubCloud or video link" required>
<div class="row">
<select id="cat">
<option value="movie">Movies</option>
<option value="dubbed">Dubbed</option>
<option value="series">Web Series</option>
<option value="english">English</option>
</select>
<button type="submit" id="addbtn">Verify &amp; Add</button>
</div>
</form>
<div id="status"></div>
<div id="list"></div>
</div>
<script>
function esc(s){return (s||"").replace(/&/g,"&amp;").replace(/</g,"&lt;")}
function setStatus(msg, ok){
  var el=document.getElementById("status");
  el.innerHTML=msg; el.className=ok?"ok":"bad";
}
function pretty(t){
  return (t||"").replace(/\.(mkv|mp4|avi|mov|webm)$/i,"")
    .replace(/[._]+/g," ").replace(/\s+/g," ").trim();
}
function chips(t){
  var out=[]; t=t||"";
  var y=t.match(/(?:19|20)\d{2}/); if(y) out.push(y[0]);
  var q=t.match(/2160p|1080p|720p|480p|360p/i);
  if(q) out.push(q[0].toUpperCase());
  if(/h\.?265|hevc/i.test(t)) out.push("H.265");
  else if(/h\.?264/i.test(t)) out.push("H.264");
  return out;
}
function card(it, i){
  var d=document.createElement("div"); d.className="item";
  if(it.poster){
    var im=document.createElement("img");
    im.className="poster"; im.src=it.poster; im.alt="";
    im.loading="lazy"; d.appendChild(im);
  } else {
    var ph=document.createElement("div");
    ph.className="poster noposter"; ph.textContent="\u25B6";
    d.appendChild(ph);
  }
  var main=document.createElement("div"); main.className="main";
  var ttl=document.createElement("div"); ttl.className="ttl";
  ttl.textContent=pretty(it.title); ttl.title=it.title;
  main.appendChild(ttl);
  var cs=chips(it.title);
  if(cs.length){
    var meta=document.createElement("div"); meta.className="meta";
    cs.forEach(function(c){
      var s=document.createElement("span"); s.className="chip";
      s.textContent=c; meta.appendChild(s);
    });
    main.appendChild(meta);
  }
  var u=document.createElement("div"); u.className="url";
  u.textContent=it.url; main.appendChild(u);
  var ctrl=document.createElement("div"); ctrl.className="ctrl";
  var sel=document.createElement("select");
  [["movie","Movies"],["dubbed","Dubbed"],
   ["series","Web Series"],["english","English"]].forEach(function(c){
    var o=document.createElement("option");
    o.value=c[0]; o.textContent=c[1];
    if(it.category===c[0]) o.selected=true;
    sel.appendChild(o);
  });
  sel.onchange=function(){ categorize(i, sel.value); };
  ctrl.appendChild(sel);
  var b=document.createElement("button");
  b.className="rm"; b.textContent="Remove";
  b.onclick=function(){ del(i); };
  ctrl.appendChild(b);
  main.appendChild(ctrl);
  d.appendChild(main);
  return d;
}
function render(items){
  document.getElementById("count").textContent=items.length;
  var host=document.getElementById("list"); host.innerHTML="";
  if(!items.length){
    var e=document.createElement("div"); e.className="empty";
    e.textContent="Your list is empty - paste a link above " +
      "to add the first one.";
    host.appendChild(e); return;
  }
  [["movie","Movies"],["dubbed","Dubbed"],
   ["series","Web Series"],["english","English"]].forEach(function(g){
    var mine=[];
    items.forEach(function(it, i){
      if((it.category||"movie")===g[0]) mine.push([it, i]);
    });
    if(!mine.length) return;
    var sec=document.createElement("div"); sec.className="sec";
    var h=document.createElement("h2"); h.textContent=g[1];
    sec.appendChild(h);
    var n=document.createElement("span");
    n.textContent=mine.length; sec.appendChild(n);
    host.appendChild(sec);
    var list=document.createElement("div"); list.className="list";
    mine.forEach(function(pair){ list.appendChild(card(pair[0], pair[1])); });
    host.appendChild(list);
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
function categorize(i, cat){
  fetch("/categorize",{method:"POST",
    headers:{"Content-Type":"application/x-www-form-urlencoded"},
    body:"index="+i+"&category="+encodeURIComponent(cat)})
  .then(refresh);
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
    body:"url="+encodeURIComponent(u)+
      "&category="+encodeURIComponent(
        document.getElementById("cat").value)})
  .then(function(r){return r.json()})
  .then(function(res){
    btn.disabled=false;
    if(res.ok){
      setStatus("Added: "+esc(pretty(res.title)), true);
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
                url, _, poster, category = split_entry(e)
                items.append({"url": url, "title": display_title(e),
                              "poster": poster, "category": category})
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
            poster = fetch_poster(title or display_title(url))
            category = clean_category((form.get("category") or [""])[0])
            links.append(join_entry(url, title, poster, category))
            save_links(links)
            self._json({"ok": True,
                        "title": title or display_title(url),
                        "poster": poster, "category": category})
            return
        if path == "/categorize":
            try:
                idx = int((form.get("index") or ["-1"])[0])
                category = clean_category(
                    (form.get("category") or [""])[0])
                if 0 <= idx < len(links):
                    url, title, poster, _ = split_entry(links[idx])
                    links[idx] = join_entry(url, title, poster, category)
                    save_links(links)
            except ValueError:
                pass
            self._json({"ok": True})
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
    threading.Thread(target=backfill_posters, daemon=True).start()
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
