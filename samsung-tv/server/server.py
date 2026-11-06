"""Local companion service for Samsung Tizen RobinPersianFilm.
Runs on your own LAN. It reads public provider catalog pages and follows
the provider's published playback forms; no fixed signed MP4 URLs.
"""
import re
import os
from urllib.parse import urljoin, urlparse
from flask import Flask, jsonify, request, make_response
from bs4 import BeautifulSoup
import requests

app = Flask(__name__)
ALLOWED = {"farsiplex.com", "www.farsiplex.com", "farsiland.com",
           "www.farsiland.com", "farsiland.buzz", "www.farsiland.buzz"}
MEDIA = {"pl.farsiplex.buzz", "pl.farsiland.buzz"}
AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36"
SESSION = requests.Session()
SESSION.headers.update({"User-Agent": AGENT})
TIMEOUT = 15
MEDIA_RE = re.compile(r'https://pl\.(?:farsiplex|farsiland)\.buzz/[^\s"\'<>\\]+?\.mp4\?md5=[\w-]+', re.I)
Q_RE = re.compile(r'[.-](360|480|720|1080|1440|2160)(?=(?:\.[^./]+)*\.mp4$)', re.I)

def allowed(url):
    p = urlparse(url)
    return p.scheme == "https" and p.hostname in ALLOWED

def fetch(url, data=None, referer=None):
    if not allowed(url):
        raise ValueError("Untrusted provider URL")
    headers = {"Referer": referer} if referer else {}
    res = SESSION.request("POST" if data is not None else "GET", url,
                          data=data, headers=headers, timeout=TIMEOUT)
    res.raise_for_status()
    if not allowed(res.url):
        raise ValueError("Unexpected provider redirect")
    return BeautifulSoup(res.text, "html.parser"), res.url

def clean_title(text):
    return re.sub(r"[^\w\u0600-\u06ff]", "", text.lower().replace("ي","ی").replace("ك","ک"))

def image_for(node, base):
    img = node.find("img") or (node.parent and node.parent.find("img"))
    if not img:
        return None
    for attr in ("data-src", "data-lazy-src", "src"):
        if img.get(attr):
            return urljoin(base, img[attr])
    return None

def extract(doc, base, series):
    found = {}
    prefix = "/tvshow/" if series else "/movie/"
    if urlparse(base).hostname.endswith("farsiland.com"):
        prefix = "/tvshows/" if series else "/movies/"
    for link in doc.select("a[href]"):
        target = urljoin(base, link["href"])
        if not allowed(target):
            continue
        path = urlparse(target).path
        if not path.startswith(prefix) or path.rstrip("/") == prefix.rstrip("/"):
            continue
        h = link.find(["h2", "h3", "h4"])
        title = ((h.get_text(" ", strip=True) if h else link.get("title"))
                 or link.get_text(" ", strip=True)
                 or (link.find("img") or {}).get("alt", "")).strip()
        if not title or len(title) > 110:
            continue
        image = image_for(link, base)
        if not image and not h and not link.find("img"):
            continue
        found[target.split("?")[0]] = {
            "title": title, "page": target.split("?")[0], "series":series,
            "poster":image, "publishedAt":None}
    return list(found.values())

def catalog(kind):
    series = kind == "series"
    sources = [
        ("https://farsiplex.com/" + ("tvshow/" if series else "movie/"), 6),
        ("https://farsiland.com/home", 1),
        ("https://farsiland.com/" + ("series-26/" if series else "iran-movie-2025/"), 4)
    ]
    titles = []
    for source, limit in sources:
        url = source
        for page in range(limit):
            try:
                doc, current = fetch(url)
                titles.extend(extract(doc, current, series))
                if limit == 1: break
                next_link = doc.select_one("a.next[href], a.nextpostslink[href], .pagination a.next[href]")
                next_url = urljoin(current, next_link["href"]) if next_link else None
                if not next_url or not allowed(next_url) or next_url == url:break
                url = next_url
            except Exception:
                break
    # FarsiPlex is loaded first and wins over duplicate FarsiLand names.
    seen = set()
    result = []
    for entry in titles:
        key = (entry["series"],clean_title(entry["title"]))
        if key not in seen:
            seen.add(key)
            entry["rank"] = len(result)
            result.append(entry)
    return result

def inputs(form):
    return {field.get("name"):field.get("value","") for field in form.select("input[name]")}

def post_form(form, base):
    dest = urljoin(base,form.get("action") or base)
    # Only follow a provider's own published form.
    if not allowed(dest):
        raise ValueError("Invalid form destination")
    return fetch(dest, inputs(form), base)

def quality_of(url, default="Auto"):
    return (Q_RE.search(urlparse(url).path) or [None, default])[1]

def verify(url):
    try:
        parsed=urlparse(url)
        if parsed.hostname not in MEDIA or parsed.scheme!="https":return False
        res=SESSION.get(url, headers={"Range":"bytes=0-0"},
                        timeout=7, stream=True, allow_redirects=False)
        try:
            return res.status_code in (200,206) and (
                res.headers.get("Content-Type","").lower().startswith("video/")
                or "octet-stream" in res.headers.get("Content-Type","").lower())
        finally:res.close()
    except requests.RequestException:return False

def player_sources(html):
    candidates = []
    for match in MEDIA_RE.finditer(html.replace("&amp;","&").replace("\\/","/")):
        link = match.group()
        if link not in candidates:candidates.append(link)
    return [{"url":u,"quality":quality_of(u)} for u in candidates if verify(u)]

def resolve_land(page):
    doc,base=fetch(page)
    first=next((f for f in doc.select("form[action]")
                if "farsiland.buzz/redirect.php" in urljoin(base,f.get("action",""))),None)
    if not first:return []
    doc,base=post_form(first,base)
    second=next((f for f in doc.select("form")
                 if urlparse(urljoin(base,f.get("action") or base)).hostname in
                 ("farsiland.buzz","www.farsiland.buzz")),None)
    if not second:return []
    doc,base=post_form(second,base)
    discovered=[]
    quality_forms=[]
    for form in doc.select("form"):
        data=inputs(form)
        if data.get("q") in ("480","720","1080") and data.get("postid"):
            quality_forms.append(form)
    for form in quality_forms:
        try:
            destination, url=post_form(form,base)
            discovered.extend(player_sources(str(destination)))
        except Exception:continue
    unique={}
    for item in discovered:unique.setdefault(item["quality"],item)
    return sorted(unique.values(),key=lambda item:int(item["quality"]) if item["quality"].isdigit() else 0)

def resolve_plex(page):
    # FarsiPlex may require a browser to establish its playback session.
    # Only return URLs published in retrievable page markup; never invent tokens.
    doc,base=fetch(page)
    sources=player_sources(str(doc))
    for frame in doc.select("iframe[src],a[href]"):
        url=urljoin(base,frame.get("src") or frame.get("href") or "")
        if allowed(url) and ("/play-hq-player/" in url or "/play-quick-view/" in url):
            try:
                child,_=fetch(url,referer=base)
                sources.extend(player_sources(str(child)))
            except Exception:pass
    return list({x["url"]:x for x in sources}.values())

def cors(response):
    response.headers["Access-Control-Allow-Origin"]="*"
    response.headers["Access-Control-Allow-Methods"]="GET, OPTIONS"
    response.headers["Access-Control-Allow-Headers"]="Content-Type"
    return response

@app.after_request
def add_headers(response):
    return cors(response)

@app.get("/api/catalog")
def api_catalog():
    return jsonify({"movies":catalog("movies"),"series":catalog("series")})

@app.get("/api/episodes")
def api_episodes():
    url=request.args.get("url","")
    if not allowed(url):return jsonify(error="Invalid URL"),400
    try:
        doc,base=fetch(url)
        episodes={}
        for a in doc.select("a[href]"):
            link=urljoin(base,a.get("href",""))
            path=urlparse(link).path
            if not allowed(link) or not ("/episodes/" in path or "/episode/" in path):
                continue
            label=(a.get_text(" ",strip=True)+" "+path.lower())
            match=re.search(r"(?:ep|episode)[- _]*0*(\d+)",label,re.I)
            if not match:continue
            number=int(match.group(1))
            episodes[link]={"page":link,"name":"Episode "+str(number),"number":number}
        return jsonify(episodes=sorted(episodes.values(),key=lambda x:x["number"]))
    except Exception as e:return jsonify(error=str(e)),502

@app.get("/api/resolve")
def api_resolve():
    url=request.args.get("url","")
    if not allowed(url):return jsonify(error="Invalid URL"),400
    try:
        if urlparse(url).hostname.endswith("farsiland.com"):
            sources=resolve_land(url)
        else:sources=resolve_plex(url)
        return jsonify(sources=sources)
    except Exception as e:return jsonify(error=str(e)),502

@app.get("/api/health")
def health():return jsonify(status="ok")

if __name__=="__main__":
    app.run(host=os.environ.get("ROBIN_HOST","0.0.0.0"),
            port=int(os.environ.get("ROBIN_PORT","8787")))
