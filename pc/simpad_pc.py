#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SIM PAD PC 프로그램 (Windows)

폰의 SIM PAD 앱(터치패드·키보드)이 보내는 신호를 받아 이 PC의 마우스·키보드 입력으로 실행합니다.
  폰(SIM PAD)  →  Supabase Realtime(Broadcast, 채널 = 연결 코드)  →  이 프로그램  →  Windows SendInput

- 연결 코드: 처음 실행할 때 한 번 만들어 저장하며(%APPDATA%\\SIM-PAD\\config.json) 이후 같은 코드를 씁니다.
  코드를 아는 사람은 이 PC를 조작할 수 있으니 남에게 알려주지 마세요. 바꾸려면:  simpad_pc.py --new-code
- 이 프로그램은 '마우스 이동·클릭·스크롤, 키 입력, 글자 입력'만 실행합니다. 파일·명령 실행 기능은 없습니다.
- 관리자 권한 창(작업관리자 등)을 조작하려면 이 프로그램도 관리자 권한으로 실행해야 합니다.
"""
import argparse, base64, ctypes, hashlib, json, os, platform, secrets, socket, ssl, struct, sys, threading, time
import urllib.request
from urllib.parse import urlparse

SB_REF = 'gpqlximhoqdybcewdfde'
SB_KEY = 'sb_publishable_xUnGxyLCc5TB5nv4keVQVw__grqGJkP'
WS_URL = 'wss://%s.supabase.co/realtime/v1/websocket?apikey=%s&vsn=1.0.0' % (SB_REF, SB_KEY)
APP_URL = 'https://jhsim2545.github.io/Webtools/pad.html'      # QR이 여는 SIM PAD 주소
ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'                  # 헷갈리는 글자(0,O,1,I) 제외

try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass


def log(*a):
    print(time.strftime('[%H:%M:%S]'), *a, flush=True)


# ───────────────────────── 연결 코드 ─────────────────────────
def cfg_path():
    base = os.environ.get('APPDATA') or os.path.join(os.path.expanduser('~'), '.config')
    d = os.path.join(base, 'SIM-PAD')
    os.makedirs(d, exist_ok=True)
    return os.path.join(d, 'config.json')


def load_code(renew=False):
    p = cfg_path()
    if not renew:
        try:
            c = json.load(open(p, encoding='utf-8')).get('code', '')
            if len(c) == 12 and all(ch in ALPHABET for ch in c):
                return c
        except Exception:
            pass
    c = ''.join(secrets.choice(ALPHABET) for _ in range(12))
    try:
        json.dump({'code': c}, open(p, 'w', encoding='utf-8'))
    except Exception as e:
        log('코드 저장 실패(이번 실행에만 사용):', e)
    return c


def fmt(c):
    return '-'.join([c[0:4], c[4:8], c[8:12]])


# ───────────────────────── 키 이름 → 가상키 ─────────────────────────
VK = {
    'backspace': 0x08, 'tab': 0x09, 'enter': 0x0D, 'shift': 0x10, 'ctrl': 0x11, 'alt': 0x12, 'esc': 0x1B, 'space': 0x20,
    'pgup': 0x21, 'pgdn': 0x22, 'end': 0x23, 'home': 0x24, 'left': 0x25, 'up': 0x26, 'right': 0x27, 'down': 0x28,
    'hangul': 0x15, 'prtsc': 0x2C, 'ins': 0x2D, 'del': 0x2E, 'win': 0x5B,
    'volDown': 0xAE, 'volUp': 0xAF, 'mute': 0xAD, 'next': 0xB0, 'prev': 0xB1, 'stop': 0xB2, 'play': 0xB3,
    'plus': 0xBB, 'comma': 0xBC, 'minus': 0xBD, 'period': 0xBE, 'slash': 0xBF, 'backtick': 0xC0,
}
for i in range(1, 13):
    VK['f%d' % i] = 0x6F + i            # F1=0x70
for i in range(10):
    VK[str(i)] = 0x30 + i
for i in range(26):
    VK[chr(97 + i)] = 0x41 + i
EXTENDED = {0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28, 0x2C, 0x2D, 0x2E, 0xAD, 0xAE, 0xAF, 0xB0, 0xB1, 0xB2, 0xB3}


# ───────────────────────── 입력 실행기 ─────────────────────────
class DryRun:
    """실제 입력 없이 받은 동작을 화면에 찍기만 함 (시험용 / Windows가 아닐 때)"""
    def move(self, dx, dy): log('move', dx, dy)
    def wheel(self, dy, horizontal=False): log('hwheel' if horizontal else 'wheel', dy)
    def button(self, b, down): log('mouse', b, 'down' if down else 'up')
    def key(self, name, down): log('key', name, 'down' if down else 'up')
    def text(self, s): log('text', repr(s))


class Win32:
    ULONG_PTR = ctypes.c_size_t

    class MOUSEINPUT(ctypes.Structure):
        _fields_ = [('dx', ctypes.c_long), ('dy', ctypes.c_long), ('mouseData', ctypes.c_ulong),
                    ('dwFlags', ctypes.c_ulong), ('time', ctypes.c_ulong), ('dwExtraInfo', ctypes.c_size_t)]

    class KEYBDINPUT(ctypes.Structure):
        _fields_ = [('wVk', ctypes.c_ushort), ('wScan', ctypes.c_ushort), ('dwFlags', ctypes.c_ulong),
                    ('time', ctypes.c_ulong), ('dwExtraInfo', ctypes.c_size_t)]

    class HARDWAREINPUT(ctypes.Structure):
        _fields_ = [('uMsg', ctypes.c_ulong), ('wParamL', ctypes.c_ushort), ('wParamH', ctypes.c_ushort)]

    class _U(ctypes.Union):
        pass

    def __init__(self):
        M, K, H = Win32.MOUSEINPUT, Win32.KEYBDINPUT, Win32.HARDWAREINPUT
        Win32._U._fields_ = [('mi', M), ('ki', K), ('hi', H)]

        class INPUT(ctypes.Structure):
            _fields_ = [('type', ctypes.c_ulong), ('u', Win32._U)]
        self.INPUT = INPUT
        self.u32 = ctypes.windll.user32
        self.u32.SendInput.argtypes = (ctypes.c_uint, ctypes.POINTER(INPUT), ctypes.c_int)
        self.u32.MapVirtualKeyW.argtypes = (ctypes.c_uint, ctypes.c_uint)

    def _send(self, *items):
        arr = (self.INPUT * len(items))(*items)
        self.u32.SendInput(len(items), arr, ctypes.sizeof(self.INPUT))

    def _mouse(self, flags, dx=0, dy=0, data=0):
        i = self.INPUT(type=0)
        i.u.mi = Win32.MOUSEINPUT(dx, dy, data & 0xFFFFFFFF, flags, 0, 0)
        return i

    def _kbd(self, vk, up, unicode_=False, scan=0):
        i = self.INPUT(type=1)
        fl = 0
        if unicode_:
            fl |= 0x0004
            wvk, wscan = 0, scan
        else:
            wvk = vk
            wscan = self.u32.MapVirtualKeyW(vk, 0)
            if vk in EXTENDED:
                fl |= 0x0001
        if up:
            fl |= 0x0002
        i.u.ki = Win32.KEYBDINPUT(wvk, wscan, fl, 0, 0)
        return i

    def move(self, dx, dy):
        self._send(self._mouse(0x0001, dx, dy))

    def wheel(self, d, horizontal=False):
        self._send(self._mouse(0x1000 if horizontal else 0x0800, data=d))

    def button(self, b, down):
        fl = {'l': (0x0002, 0x0004), 'r': (0x0008, 0x0010), 'm': (0x0020, 0x0040)}[b][0 if down else 1]
        self._send(self._mouse(fl))

    def key(self, name, down):
        vk = VK.get(name)
        if vk is not None:
            self._send(self._kbd(vk, not down))

    def text(self, s):
        items = []
        for ch in s:
            if ch == '\n':
                items += [self._kbd(0x0D, False), self._kbd(0x0D, True)]
                continue
            b = ch.encode('utf-16-le')
            for j in range(0, len(b), 2):
                u = b[j] | (b[j + 1] << 8)
                items += [self._kbd(0, False, True, u), self._kbd(0, True, True, u)]
        if items:
            self._send(*items)


# ───────────────────────── 신호 처리 ─────────────────────────
class Handler:
    def __init__(self, inj):
        self.inj = inj
        self.down_btn = set()

    def run(self, ev, p):
        i = self.inj
        if ev == 'f':                                  # 이동·스크롤·확대 묶음
            x, y = int(p.get('x', 0)), int(p.get('y', 0))
            if x or y:
                i.move(max(-3000, min(3000, x)), max(-3000, min(3000, y)))
            sy, sx, z = int(p.get('sy', 0)), int(p.get('sx', 0)), int(p.get('z', 0))
            if sy: i.wheel(max(-2400, min(2400, sy)))
            if sx: i.wheel(max(-2400, min(2400, sx)), True)
            if z:
                i.key('ctrl', True); i.wheel(max(-2400, min(2400, z))); i.key('ctrl', False)
        elif ev == 'b':                                # 마우스 버튼
            b, a = p.get('b'), p.get('a')
            if b not in ('l', 'r', 'm'):
                return
            if a == 'd':
                i.button(b, True); self.down_btn.add(b)
            elif a == 'u':
                i.button(b, False); self.down_btn.discard(b)
            elif a == 'c':
                i.button(b, True); i.button(b, False)
        elif ev == 'k':                                # 키 하나
            k, a = p.get('k'), p.get('a', 'p')
            if k in VK:
                if a == 'd': i.key(k, True)
                elif a == 'u': i.key(k, False)
                else: i.key(k, True); i.key(k, False)
        elif ev == 'c':                                # 조합키 (Ctrl+C 등)
            ks = [k for k in (p.get('k') or []) if k in VK][:5]
            for k in ks: i.key(k, True)
            for k in reversed(ks): i.key(k, False)
        elif ev == 'x':                                # 글자 입력 (앞에서 백스페이스 n번)
            bs = max(0, min(500, int(p.get('bs', 0))))
            for _ in range(bs):
                i.key('backspace', True); i.key('backspace', False)
            s = str(p.get('s', ''))[:800]
            if s:
                i.text(s)

    def release_all(self):
        for b in list(self.down_btn):
            self.inj.button(b, False)
        self.down_btn.clear()


# ───────────────────────── 웹소켓 (표준 라이브러리만 사용 — 별도 설치 불필요) ─────────────────────────
class MiniWS:
    """아주 작은 WebSocket 클라이언트. wss/ws, 회사 프록시(HTTP CONNECT) 지원."""

    def __init__(self, url, timeout=15):
        u = urlparse(url)
        self.tls = u.scheme == 'wss'
        host, port = u.hostname, u.port or (443 if self.tls else 80)
        path = (u.path or '/') + ('?' + u.query if u.query else '')
        sock = self._open(host, port, timeout)
        if self.tls:
            ctx = ssl.create_default_context()
            if os.environ.get('SIMPAD_INSECURE') == '1':      # 시험용
                ctx.check_hostname = False
                ctx.verify_mode = ssl.CERT_NONE
            sock = ctx.wrap_socket(sock, server_hostname=host)
        key = base64.b64encode(os.urandom(16)).decode()
        req = ('GET %s HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n'
               'Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\nUser-Agent: SIM-PAD-PC\r\n\r\n') % (path, u.netloc, key)
        sock.sendall(req.encode())
        buf = b''
        while b'\r\n\r\n' not in buf:
            d = sock.recv(4096)
            if not d:
                raise ConnectionError('서버가 연결을 닫았습니다')
            buf += d
            if len(buf) > 65536:
                raise ConnectionError('응답이 너무 큽니다')
        head, _, rest = buf.partition(b'\r\n\r\n')
        line = head.split(b'\r\n')[0].decode('latin1')
        if ' 101 ' not in line + ' ':
            raise ConnectionError('웹소켓 연결 거부: ' + line)
        self.sock = sock
        self.buf = rest
        self.lock = threading.Lock()
        sock.settimeout(35)

    @staticmethod
    def _proxy():
        try:
            pr = urllib.request.getproxies()          # 환경변수 + Windows 인터넷 옵션의 프록시
        except Exception:
            return None
        p = pr.get('https') or pr.get('http')
        if not p:
            return None
        if '://' not in p:
            p = 'http://' + p
        q = urlparse(p)
        return q.hostname, q.port or 8080

    def _open(self, host, port, timeout):
        px = self._proxy()
        if px and host not in ('127.0.0.1', 'localhost'):
            s = socket.create_connection(px, timeout)
            s.sendall(('CONNECT %s:%d HTTP/1.1\r\nHost: %s:%d\r\n\r\n' % (host, port, host, port)).encode())
            buf = b''
            while b'\r\n\r\n' not in buf:
                d = s.recv(4096)
                if not d:
                    raise ConnectionError('프록시가 연결을 닫았습니다')
                buf += d
            if b' 200' not in buf.split(b'\r\n')[0]:
                raise ConnectionError('프록시 거부: ' + buf.split(b'\r\n')[0].decode('latin1'))
            return s
        return socket.create_connection((host, port), timeout)

    def _frame(self, op, payload=b''):
        n = len(payload)
        h = bytes([0x80 | op])
        if n < 126:
            h += bytes([0x80 | n])
        elif n < 65536:
            h += bytes([0x80 | 126]) + struct.pack('>H', n)
        else:
            h += bytes([0x80 | 127]) + struct.pack('>Q', n)
        mask = os.urandom(4)
        return h + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload))

    def send_text(self, text):
        with self.lock:
            self.sock.sendall(self._frame(1, text.encode('utf-8')))

    def ping(self):
        with self.lock:
            self.sock.sendall(self._frame(9))

    def _read(self, n):
        while len(self.buf) < n:
            d = self.sock.recv(65536)
            if not d:
                raise ConnectionError('연결이 끊겼습니다')
            self.buf += d
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def recv(self):
        """텍스트 메시지 하나를 돌려준다. 35초 동안 아무 것도 없으면 ping 을 보내고, 3번 연속이면 연결 끊김으로 본다."""
        text, idle = b'', 0
        while True:
            try:
                b1, b2 = self._read(2)
            except socket.timeout:
                idle += 1
                if idle >= 3:
                    raise ConnectionError('응답 없음')
                self.ping()
                continue
            idle = 0
            fin, op, ln = b1 & 0x80, b1 & 0x0F, b2 & 0x7F
            if ln == 126:
                ln = struct.unpack('>H', self._read(2))[0]
            elif ln == 127:
                ln = struct.unpack('>Q', self._read(8))[0]
            if b2 & 0x80:
                mask = self._read(4)
                data = bytes(b ^ mask[i % 4] for i, b in enumerate(self._read(ln)))
            else:
                data = self._read(ln)
            if op == 8:
                raise ConnectionError('서버가 연결을 닫았습니다')
            if op == 9:
                with self.lock:
                    self.sock.sendall(self._frame(10, data))
                continue
            if op == 10:
                continue
            if op in (1, 0):
                text += data
                if fin:
                    return text.decode('utf-8', 'replace')

    def close(self):
        try:
            self.sock.close()
        except Exception:
            pass


# ───────────────────────── Realtime 연결 ─────────────────────────
def main():
    ap = argparse.ArgumentParser(description='SIM PAD PC 프로그램')
    ap.add_argument('--new-code', action='store_true', help='연결 코드를 새로 만든다')
    ap.add_argument('--dry-run', action='store_true', help='실제 입력 없이 받은 동작만 표시')
    ap.add_argument('--url', default=WS_URL, help=argparse.SUPPRESS)
    ap.add_argument('--code', default='', help=argparse.SUPPRESS)
    a = ap.parse_args()

    log('SIM PAD PC 프로그램을 시작합니다…')
    if a.dry_run or platform.system() != 'Windows':
        if not a.dry_run:
            log('Windows가 아니어서 시험 모드로 실행합니다 (입력은 실행되지 않음).')
        inj = DryRun()
    else:
        inj = Win32()

    code = a.code or load_code(a.new_code)
    topic = 'realtime:pad-' + code.lower()
    host = socket.gethostname()
    handler = Handler(inj)
    url = APP_URL + '#c=' + code

    print()
    print('=' * 52)
    print('  SIM PAD  PC 프로그램')
    print('  연결 코드 :  ' + fmt(code))
    print('=' * 52)
    print('  폰에서 SIM PAD 앱에 위 코드를 입력하세요.')
    print('  (창을 닫으면 조작이 멈춥니다)')
    print()
    try:
        import qrcode                         # 설치되어 있으면 QR 도 표시 (없어도 무방)
        q = qrcode.QRCode(border=1)
        q.add_data(url); q.make(fit=True)
        q.print_ascii(invert=True)
    except Exception:
        pass

    state = {'ref': 0, 'ws': None, 'joined': False, 'last_hello': 0.0, 'phone': 0.0}

    def raw(o):
        ws = state['ws']
        if ws is None:
            return
        try:
            ws.send_text(json.dumps(o))
        except Exception:
            pass

    def nref():
        state['ref'] += 1
        return str(state['ref'])

    def hello():
        if state['joined']:
            raw({'topic': topic, 'event': 'broadcast', 'ref': nref(),
                 'payload': {'type': 'broadcast', 'event': 'hello', 'payload': {'n': host, 'os': platform.system()}}})
            state['last_hello'] = time.time()

    def on_message(msg):
        try:
            m = json.loads(msg)
        except Exception:
            return
        ev = m.get('event')
        if ev == 'phx_reply' and m.get('topic') == topic:
            if (m.get('payload') or {}).get('status') == 'ok':
                state['joined'] = True
                log('준비 완료 — 폰에서 연결하세요.')
                hello()
            else:
                log('채널 참가 실패:', m.get('payload'))
        elif ev == 'broadcast' and m.get('topic') == topic:
            pl = m.get('payload') or {}
            name, data = pl.get('event'), pl.get('payload') or {}
            if name == 'ping':
                if time.time() - state['last_hello'] > 0.3:
                    hello()
                if time.time() - state['phone'] > 20:
                    log('폰 연결됨')
                state['phone'] = time.time()
                return
            try:
                handler.run(name, data)
            except Exception as e:
                log('입력 처리 오류:', name, e)

    def beat():
        n = 0
        while True:
            time.sleep(1)
            n += 1
            if state['ws'] is None:
                continue
            if n % 25 == 0:
                raw({'topic': 'phoenix', 'event': 'heartbeat', 'payload': {}, 'ref': nref()})
            if n % 3 == 0:
                hello()
            if state['phone'] and time.time() - state['phone'] > 15:   # 폰이 사라지면 눌린 버튼 해제
                handler.release_all()

    threading.Thread(target=beat, daemon=True).start()

    wait = 1
    while True:
        ws = None
        try:
            log('서버에 연결하는 중…')
            ws = MiniWS(a.url)
            state['ws'] = ws
            log('서버 연결됨, 채널 참가 중…')
            wait = 1
            raw({'topic': topic, 'event': 'phx_join', 'ref': nref(), 'join_ref': '1',
                 'payload': {'config': {'broadcast': {'self': False, 'ack': False}, 'presence': {'key': ''},
                                        'postgres_changes': [], 'private': False}}})
            while True:
                on_message(ws.recv())
        except KeyboardInterrupt:
            break
        except Exception as e:
            log('연결 실패/끊김:', e)
            if wait == 1:
                log('회사 네트워크가 웹소켓(wss)을 막고 있으면 이 오류가 반복됩니다. 폰 핫스팟으로 시험해 보세요.')
        finally:
            state['joined'] = False
            state['ws'] = None
            handler.release_all()
            if ws:
                ws.close()
        try:
            time.sleep(wait)
        except KeyboardInterrupt:
            break
        wait = min(wait * 2, 15)
    log('종료')


if __name__ == '__main__':
    main()
