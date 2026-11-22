"""Local fake Xtream Codes server to test Hopla's films, series and live TV without a subscription.

Run:   python -I devtools/fake_xtream.py            (listens on 127.0.0.1:8099)
TV:    adb reverse tcp:8099 tcp:8099
Link:  http://127.0.0.1:8099/get.php?username=demo&password=demo

Content is all public test media: iptv-org free channels (live), Blender's Sintel trailer, Mux and Apple
HLS test streams (Apple's BipBop has 2 audio tracks and subtitles in 4 languages). Images: picsum.photos.
Stream URLs answer with a redirect to the real media, like many real panels do.

Redirect tests (HTTP 301, like providers that moved):
  http://127.0.0.1:8099/old/get.php?username=demo&password=demo   -> every /old/... call is 301'd to /...
  http://127.0.0.1:8099/m3u                                      -> 301 to the https iptv-org playlist
TV guide: /xmltv.php returns a generated XMLTV for the live channels (30-minute programmes around now).

Load test:  python -I devtools/fake_xtream.py --big   adds 20 000 films, 4 000 series (12 seasons x 20 episodes
            each in their info) and 8 000 live channels, in 120 categories, like a large real provider.
"""
import html, json, re, sys, time, urllib.parse, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

USER, PASS = "demo", "demo"
SINTEL = "https://download.blender.org/durian/trailer/sintel_trailer-480p.mp4"
TOS = "https://test-streams.mux.dev/tos_ismc/main.m3u8"
BBB = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
BIPBOP = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8"
pic = lambda n, w, h: f"https://picsum.photos/id/{n}/{w}/{h}"

MOVIES = [  # id, name, category, url, ext, picture id, added, plot, year, rating (0 = none)
    (101, "Sintel (bande-annonce)", "1", SINTEL, "mp4", 1036, 1763000000, "Une jeune femme part à la recherche de son dragon.", 2010, 7.4),
    (102, "Tears of Steel", "2", TOS, "m3u8", 1067, 1762000000, "Amsterdam, dans le futur : des scientifiques tentent de sauver le monde.", 2012, 6.2),
    (103, "Big Buck Bunny", "1", BBB, "m3u8", 1084, 1761000000, "Un lapin géant et paisible se venge de trois rongeurs farceurs.", 2008, 6.5),
    (104, "BipBop (sous-titres en 4 langues)", "3", BIPBOP, "m3u8", 1069, 1760000000, "Flux de test Apple : 2 pistes audio, sous-titres anglais, français, espagnol, japonais.", 2011, 0),
    (105, "Le Phare du bout du monde", "4", SINTEL, "mp4", 1015, 1759000000, "Un gardien de phare et une tempête qui ne finit pas.", 2019, 7.9),
    (106, "Neige éternelle", "4", TOS, "m3u8", 1036, 1758000000, "Une expédition perdue dans l'Himalaya.", 2021, 6.8),
    (107, "Les Châteaux du Rhin", "5", BBB, "m3u8", 1040, 1757000000, "Huit siècles d'histoire au fil du fleuve.", 2016, 7.1),
    (108, "Grand Nord", "5", SINTEL, "mp4", 1084, 1756000000, "La vie des morses au bord de la banquise.", 2020, 8.3),
    (109, "Océans profonds", "5", TOS, "m3u8", 1069, 1755000000, "Plongée dans les abysses, là où la lumière ne va pas.", 2018, 8.6),
    (110, "Skyline", "2", BBB, "m3u8", 1067, 1754000000, "Une ville, une nuit, un dernier train.", 2014, 5.9),
    (111, "La Reine de la savane", "5", SINTEL, "mp4", 1074, 1753000000, "Une lionne et ses petits, une saison sèche.", 2022, 8.0),
    (112, "Highlands", "4", TOS, "m3u8", 1018, 1752000000, "Deux frères traversent l'Écosse à pied.", 2017, 7.3),
    (113, "Ruelles", "2", BBB, "m3u8", 1047, 1751000000, "Polar dans les rues de briques d'une ville portuaire.", 2015, 6.1),
    (114, "Fjord", "4", SINTEL, "mp4", 1016, 1750000000, "Un village isolé attend le retour des bateaux.", 2023, 7.6),
    (115, "Avalanche", "1", TOS, "m3u8", 1036, 1749000000, "Une petite station de ski dans la tourmente.", 2009, 5.4),
    (116, "Aube rouge (2013)", "2", BBB, "m3u8", 1011, 1748000000, "Une navigatrice seule face à l'océan.", 0, 6.9),
    (117, "Zénith", "3", SINTEL, "mp4", 1003, 1747000000, "Un astronome amateur découvre une étoile.", 2024, 0),
    (118, "Jardins secrets", "3", TOS, "m3u8", 1080, 1746000000, "Les jardiniers des grandes demeures.", 2019, 7.0),
]
EPISODES = {  # season -> [(id, num, title, url, ext, image id)]
    "1": [(201, 1, "Le dragon", SINTEL, "mp4", 1018), (202, 2, "Les langues", BIPBOP, "m3u8", 1040)],
    "2": [(203, 1, "Le futur", TOS, "m3u8", 1074)],
}
LIVE = []  # filled from iptv-org at start: (id, name, category, logo, url)
LIVE_CATS = {}


def load_live():
    text = urllib.request.urlopen("https://iptv-org.github.io/iptv/countries/fr.m3u", timeout=30).read().decode("utf8", "replace")
    name = group = logo = ""
    for line in text.splitlines():
        if line.startswith("#EXTINF"):
            name = line.rsplit(",", 1)[-1].strip()
            group = (re.search(r'group-title="([^"]*)"', line) or [None, ""])[1].split(";")[0] or "Divers"
            logo = (re.search(r'tvg-logo="([^"]*)"', line) or [None, ""])[1]
        elif line.startswith("http") and ".m3u8" in line and len(LIVE) < 40:
            cid = LIVE_CATS.setdefault(group, str(len(LIVE_CATS) + 1))
            LIVE.append((len(LIVE) + 1, name, cid, logo, line.strip()))


def api(q):
    if q.get("username") != USER or q.get("password") != PASS:
        return {"user_info": {"auth": 0}}
    a = q.get("action")
    if a is None:
        return {"user_info": {"auth": 1, "status": "Active", "exp_date": "1893456000", "max_connections": "2", "active_cons": "0",
                              "allowed_output_formats": ["m3u8"]}, "server_info": {"url": "127.0.0.1", "port": "8099"}}
    if a == "get_live_categories":
        return [{"category_id": v, "category_name": k} for k, v in LIVE_CATS.items()]
    if a == "get_live_streams":
        return [{"stream_id": i, "name": n, "category_id": c, "stream_icon": l, "epg_channel_id": f"ch{i}.test"} for i, n, c, l, _ in LIVE]
    if a == "get_vod_categories":
        base = [("1", "Animation"), ("2", "Science-fiction"), ("3", "Tests techniques"), ("4", "Aventure"), ("5", "Documentaires")]
        if BIG["on"]: base += [(str(10 + k), f"Catégorie {k}") for k in range(120)]
        return [{"category_id": c, "category_name": n} for c, n in base]
    if a == "get_vod_streams":
        return [{"stream_id": i, "name": n, "category_id": c, "stream_icon": pic(p, 300, 450), "container_extension": e,
                 "added": str(ad), "rating": str(r) if r else "", **({"year": str(y)} if y else {})}
                for i, n, c, _, e, p, ad, _, y, r in MOVIES]
    if a == "get_vod_info":
        m = next(m for m in MOVIES if str(m[0]) == q.get("vod_id"))
        return {"info": {"plot": m[7], "genre": "Court métrage", "releasedate": f"{m[8] or 2010}-09-27", "duration": "01:%02d:00" % (m[0] % 50),
                         "backdrop_path": [pic(m[5], 1280, 720)]}, "movie_data": {"stream_id": m[0], "container_extension": m[4]}}
    if a == "get_series_categories":
        return [{"category_id": "9", "category_name": "Tests"}, {"category_id": "10", "category_name": "Documentaires"}]
    if a == "get_series":
        shows = [(1, "Série de test Hopla", "9", 1015, "Trois épisodes libres de droits pour tester la reprise, l'enchaînement et les langues.", 8.0, 2024),
                 (2, "Terres d'Écosse", "10", 1018, "Des falaises de Skye aux routes des Highlands.", 8.4, 2025),
                 (3, "Bêtes du Grand Nord", "10", 1084, "Une année avec les morses et les ours.", 7.7, 2021),
                 (4, "Châteaux", "10", 1040, "Les forteresses d'Europe racontées par ceux qui les gardent.", 6.9, 2019),
                 (5, "Abysses", "10", 1069, "Ce qui vit sous 1000 mètres.", 8.8, 2023)]
        return [{"series_id": i, "name": n, "category_id": c, "cover": pic(p, 300, 450), "plot": pl, "rating": str(r),
                 "releaseDate": f"{y}-01-01", "genre": "Documentaire" if c == "10" else "Test", "backdrop_path": [pic(p, 1280, 720)],
                 "last_modified": str(1763000000 - i * 1000)} for i, n, c, p, pl, r, y in shows] + (
            [{"series_id": 100 + k, "name": f"FR - Série {k}", "category_id": "10", "cover": pic(1015 + k % 3, 300, 450),
              "plot": "Série de charge.", "rating": str(5 + k % 5), "releaseDate": f"{1990 + k % 35}-01-01",
              "last_modified": str(1700000000 - k)} for k in range(4000)] if BIG["on"] else [])
    if a == "get_series_info" and BIG["on"] and int(q.get("series_id", 0)) >= 100:
        urls = [(SINTEL, "mp4"), (BIPBOP, "m3u8")]
        return {"info": {"name": "Série de charge", "backdrop_path": [pic(1015, 1280, 720)]},
                "episodes": {str(se): [{"id": str(900000 + se * 100 + e), "episode_num": e, "title": f"Épisode {e}", "season": se,
                                        "container_extension": urls[e % 2][1], "info": {"plot": f"S{se} É{e}.", "movie_image": pic(1018, 640, 360)}}
                                       for e in range(1, 21)] for se in range(1, 13)}}
    if a == "get_series_info":
        return {"info": {"name": "Série de test Hopla", "backdrop_path": [pic(1015, 1280, 720)]},
                "episodes": {s: [{"id": str(i), "episode_num": n, "title": t, "season": int(s), "container_extension": e,
                                  "info": {"plot": f"Épisode {n} de la saison {s}.", "movie_image": pic(p, 640, 360)}}
                                 for i, n, t, _, e, p in eps] for s, eps in EPISODES.items()}}
    return []


TITLES = ["Le journal", "Météo", "Magazine découverte", "Série : épisode {n}", "Documentaire nature", "Le grand débat",
          "Film du soir", "Jeu télévisé", "Dessins animés", "Reportage"]


def xmltv():
    """Synthetic guide: each channel gets 30-minute programmes from 2 h ago to 12 h ahead (UTC times)."""
    slot = 1800
    t0 = int(time.time()) // slot * slot - 4 * slot
    fmt = lambda t: time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(t))
    out = ['<?xml version="1.0" encoding="UTF-8"?>', "<tv>"]
    for i, name, *_ in LIVE:
        out.append(f'<channel id="ch{i}.test"><display-name>{html.escape(name)}</display-name></channel>')
        for k in range(28):
            start = t0 + k * slot
            title = TITLES[(i + k) % len(TITLES)].format(n=k + 1)
            out.append(f'<programme start="{fmt(start)}" stop="{fmt(start + slot)}" channel="ch{i}.test">'
                       f'<title lang="fr">{html.escape(title)}</title><desc lang="fr">Programme de test {k + 1} sur {html.escape(name)}.</desc></programme>')
    out.append("</tv>")
    return "\n".join(out).encode()


def stream_target(kind, sid):
    if kind == "live":
        return next((u for i, *_, u in LIVE if str(i) == sid), None)
    if kind == "movie":
        return next((m[3] for m in MOVIES if str(m[0]) == sid), None)
    found = next((e[3] for eps in EPISODES.values() for e in eps if str(e[0]) == sid), None)
    if found is None and sid.isdigit() and int(sid) >= 900000:
        found = (SINTEL, BIPBOP)[int(sid) % 2]
    return found


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        u = urllib.parse.urlsplit(self.path)
        q = dict(urllib.parse.parse_qsl(u.query))
        if u.path.startswith("/old/"):
            return self.redirect(self.path[4:], 301)
        if u.path == "/m3u":
            return self.redirect("https://iptv-org.github.io/iptv/countries/fr.m3u", 301)
        if u.path == "/xmltv.php":
            body = xmltv()
            self.send_response(200); self.send_header("Content-Type", "application/xml")
            self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
            return
        if u.path == "/player_api.php":
            body = json.dumps(api(q)).encode()
            self.send_response(200); self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
            return
        m = re.fullmatch(r"/(live|movie|series)/([^/]+)/([^/]+)/(\d+)\.\w+", u.path)
        target = m and m[2] == USER and m[3] == PASS and stream_target(m[1], m[4])
        if target:
            self.redirect(target, 302)
        else:
            self.send_response(404); self.end_headers()

    def redirect(self, location, code):
        self.send_response(code); self.send_header("Location", location); self.end_headers()

    def log_message(self, fmt, *args):
        sys.stderr.write("fake-xtream: " + (fmt % args) + "\n")


def add_big_catalogue():
    import random
    rnd = random.Random(7)
    words = ["Ombre", "Lumière", "Nuit", "Mer", "Feu", "Route", "Ciel", "Secret", "Retour", "Dernier", "Premier", "Vallée",
             "Empire", "Cité", "Hiver", "Été", "Loup", "Étoile", "Miroir", "Pacte", "Silence", "Tempête", "Jardin", "Île"]
    pics = [1003, 1011, 1015, 1016, 1018, 1036, 1040, 1047, 1067, 1069, 1074, 1080, 1084]
    urls = [(SINTEL, "mp4"), (TOS, "m3u8"), (BBB, "m3u8")]
    for i in range(20000):
        name = f"FR - {rnd.choice(words)} {rnd.choice(words).lower()} {i}"
        url, ext = urls[i % 3]
        MOVIES.append((1000 + i, name, str(10 + i % 120), url, ext, rnd.choice(pics), 1700000000 - i * 60,
                       "Film de test pour la charge.", rnd.choice([0, 1975 + i % 50]), round(rnd.uniform(0, 9.5), 1)))
    for i in range(8000):
        url = LIVE[i % len(LIVE)][4]
        LIVE.append((len(LIVE) + 1, f"Chaîne test {i}", str(1 + i % 60), "", url))
    BIG["on"] = True


BIG = {"on": False}

if __name__ == "__main__":
    load_live()
    if "--big" in sys.argv:
        add_big_catalogue()
    print(f"fake Xtream ready: {len(LIVE)} live, {len(MOVIES)} films, {5 + (4000 if BIG["on"] else 0)} series on http://127.0.0.1:8099", flush=True)
    ThreadingHTTPServer(("127.0.0.1", 8099), Handler).serve_forever()
