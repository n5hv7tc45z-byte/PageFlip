#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
翻页器 · PageFlip
把手机变成电脑的翻页器（局域网，零第三方依赖）

用法:
    python server.py                # 默认 8000 端口，PageUp / PageDown
    python server.py --mode arrow   # 方向键 ↑ / ↓
    python server.py --mode space   # PageUp / 空格（PPT 常用）
    python server.py --port 9000    # 指定端口
    python server.py --dry-run      # 只打印不真的按键（调试用）
"""

from __future__ import annotations

import argparse
import ctypes
import hmac
import json
import secrets
import socket
import socketserver
import sys
import threading
import time
from ctypes import wintypes
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

BASE_DIR = Path(__file__).resolve().parent
WEB_DIR = BASE_DIR / "web"
VERSION = "1.0.0"

if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

import qr  # noqa: E402  （同目录模块，本地二维码生成）

# ---------------------------------------------------------------- 按键模拟

if sys.platform == "win32":
    _user32 = ctypes.WinDLL("user32", use_last_error=True)
    _ULONG_PTR = ctypes.POINTER(ctypes.c_ulong)

    class _MOUSEINPUT(ctypes.Structure):
        _fields_ = (
            ("dx", wintypes.LONG), ("dy", wintypes.LONG),
            ("mouseData", wintypes.DWORD), ("dwFlags", wintypes.DWORD),
            ("time", wintypes.DWORD), ("dwExtraInfo", _ULONG_PTR),
        )

    class _KEYBDINPUT(ctypes.Structure):
        _fields_ = (
            ("wVk", wintypes.WORD), ("wScan", wintypes.WORD),
            ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD),
            ("dwExtraInfo", _ULONG_PTR),
        )

    class _HARDWAREINPUT(ctypes.Structure):
        _fields_ = (
            ("uMsg", wintypes.DWORD), ("wParamL", wintypes.WORD),
            ("wParamH", wintypes.WORD),
        )

    class _INPUT(ctypes.Structure):
        class _U(ctypes.Union):
            _fields_ = (("mi", _MOUSEINPUT), ("ki", _KEYBDINPUT), ("hi", _HARDWAREINPUT))

        _anonymous_ = ("u",)
        _fields_ = (("type", wintypes.DWORD), ("u", _U))

    _user32.SendInput.argtypes = (wintypes.UINT, ctypes.POINTER(_INPUT), ctypes.c_int)
    _user32.SendInput.restype = wintypes.UINT

    _KEYEVENTF_KEYUP = 0x0002
    _INPUT_KEYBOARD = 1

    def send_vk(vk: int) -> bool:
        """向当前前台窗口发送一次按键（按下 + 抬起）。"""
        seq = (_INPUT * 2)()
        for i, flags in enumerate((0, _KEYEVENTF_KEYUP)):
            seq[i].type = _INPUT_KEYBOARD
            seq[i].ki.wVk = vk
            seq[i].ki.wScan = 0
            seq[i].ki.dwFlags = flags
            seq[i].ki.time = 0
            seq[i].ki.dwExtraInfo = None
        n = _user32.SendInput(2, seq, ctypes.sizeof(_INPUT))
        return n == 2

else:  # 非 Windows：仅提示，不阻断服务启动
    def send_vk(vk: int) -> bool:
        print(f"[warn] 当前系统不支持按键模拟 (VK=0x{vk:02X})", file=sys.stderr)
        return False


# 虚拟键码
VK = {
    "pageup": 0x21, "pagedown": 0x22, "end": 0x23, "home": 0x24,
    "left": 0x25, "up": 0x26, "right": 0x27, "down": 0x28,
    "space": 0x20, "esc": 0x1B, "enter": 0x0D, "b": 0x42, "f5": 0x74,
}


def build_keymap(mode: str) -> dict[str, list[int]]:
    """把「语义动作」映射成一组虚拟键码。"""
    km: dict[str, list[int]] = {
        "first": [VK["home"]],     # 回到第一页
        "last": [VK["end"]],       # 跳到最后一页
        "start": [VK["f5"]],       # PPT 开始放映
        "stop": [VK["esc"]],       # 退出放映
        "black": [VK["b"]],        # 放映中黑屏
        "enter": [VK["enter"]],
    }
    if mode == "arrow":
        km["prev"] = [VK["up"]]
        km["next"] = [VK["down"]]
    elif mode == "space":
        km["prev"] = [VK["pageup"]]
        km["next"] = [VK["space"]]
    else:  # page
        km["prev"] = [VK["pageup"]]
        km["next"] = [VK["pagedown"]]
    return km


MODE_DESC = {
    "page": "PageUp / PageDown",
    "arrow": "↑ / ↓ 方向键",
    "space": "PageUp / 空格",
}


# ---------------------------------------------------------------- 网络工具

def lan_ips() -> list[str]:
    """列出本机可用的局域网 IPv4，最可能的排在最前。"""
    found: list[str] = []

    # UDP connect 不会真的发包，只用来让系统选出默认出口网卡
    for probe in ("8.8.8.8", "114.114.114.114", "223.5.5.5"):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.settimeout(0.3)
            s.connect((probe, 80))
            ip = s.getsockname()[0]
            s.close()
            if ip and not ip.startswith("127.") and ip not in found:
                found.insert(0, ip)
                break
        except OSError:
            continue

    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip and not ip.startswith("127.") and ip not in found:
                found.append(ip)
    except OSError:
        pass

    # 过滤掉虚拟机 / Docker 之类的虚拟网卡
    def score(ip: str) -> int:
        if ip.startswith(("192.168.", "10.", "172.")):
            return 0
        return 1

    found.sort(key=score)
    return found or ["127.0.0.1"]


# ---------------------------------------------------------------- 配对码

def load_or_create_token(no_auth: bool = False) -> str:
    """读取或生成配对码，持久化在 .pairing 文件，重启后保持不变。"""
    if no_auth:
        return ""
    path = BASE_DIR / ".pairing"
    try:
        if path.is_file():
            saved = path.read_text(encoding="utf-8").strip()
            if saved:
                return saved
    except OSError:
        pass
    token = "".join(secrets.choice("0123456789") for _ in range(6))
    try:
        path.write_text(token, encoding="utf-8")
    except OSError:
        pass
    return token


# ---------------------------------------------------------------- 应用状态

class PageFlipApp:
    def __init__(self, mode: str = "page", dry_run: bool = False, token: str = ""):
        self.mode = mode
        self.dry_run = dry_run
        self.token = token
        self.keymap = build_keymap(mode)
        self.lock = threading.Lock()
        self.total = 0
        self.injected = 0
        self.failed = 0
        self.last_action: str | None = None
        self.last_time: float = 0.0
        self.started_at = time.time()
        self.udp_ok = False
        # 幂等去重表：(clientId, seq) -> (首次时间, 最终结果 None/True/False)
        self.seen: dict[tuple[str, int], tuple[float, bool | None]] = {}
        self.dedup_lock = threading.Lock()
        self.dedup_ttl = 30.0
        # 鉴权失败计数：ip -> [时间戳]
        self.auth_fail: dict[str, list[float]] = {}
        self.fail_lock = threading.Lock()

    def fire(self, action: str, repeat: int = 1) -> bool:
        """执行按键。返回 True 仅代表系统接受了注入，不代表目标软件真的翻页。"""
        vks = self.keymap.get(action)
        if not vks:
            return False
        repeat = max(1, min(int(repeat), 30))
        ok_all = True
        with self.lock:
            for i in range(repeat):
                for vk in vks:
                    if self.dry_run:
                        print(f"[dry-run] {action:>6} -> VK 0x{vk:02X}")
                    elif not send_vk(vk):
                        ok_all = False
                # 仅连发时需要间隔，单次按键不额外等待（省掉 20ms 固定延迟）
                if i < repeat - 1:
                    time.sleep(0.008)
            self.last_action = action
            self.last_time = time.time()
            if ok_all:
                self.total += repeat
                self.injected += repeat
            else:
                self.failed += 1
        return ok_all

    # ---- 配对与幂等 ------------------------------------------------------

    def check_token(self, token: str) -> bool:
        if not self.token:
            return True  # --no-auth 模式
        return hmac.compare_digest((token or "").strip().encode("utf-8"),
                                   self.token.encode("utf-8"))

    def claim(self, client: str, seq: int) -> tuple[bool, bool | None]:
        """
        幂等占位。返回 (is_new, prev_ok)：
        - is_new=True  → 本次需要执行
        - is_new=False → 重复请求，prev_ok 是上次的最终结果（None 表示仍在执行中）
        这样 UDP 注入失败后的 HTTP 重试会拿到 err，而不是被当成成功。
        """
        if not client or seq <= 0:
            return True, None
        key = (client, seq)
        now = time.time()
        with self.dedup_lock:
            if len(self.seen) > 1024:
                cutoff = now - self.dedup_ttl
                for k in [k for k, v in self.seen.items() if v[0] < cutoff]:
                    del self.seen[k]
                if len(self.seen) > 4096:
                    self.seen.clear()
            prev = self.seen.get(key)
            if prev is not None and now - prev[0] < self.dedup_ttl:
                return False, prev[1]
            self.seen[key] = (now, None)   # 占位：正在执行
            return True, None

    def settle(self, client: str, seq: int, ok: bool) -> None:
        """记录最终结果，供同一命令的后续重试回放。"""
        if not client or seq <= 0:
            return
        with self.dedup_lock:
            self.seen[(client, seq)] = (time.time(), ok)

    def wait_settled(self, client: str, seq: int, timeout: float = 0.3) -> bool | None:
        """
        同一条命令的重复请求到来时，首发可能还在执行。
        有界等待它落定，避免把"处理中"当成失败回给客户端。
        """
        key = (client, seq)
        deadline = time.time() + timeout
        while time.time() < deadline:
            with self.dedup_lock:
                v = self.seen.get(key)
                if v is not None and v[1] is not None:
                    return v[1]
            time.sleep(0.01)
        return None

    # ---- 鉴权失败限速（防止 6 位码被持续猜测） ---------------------------

    def auth_blocked(self, ip: str) -> bool:
        now = time.time()
        with self.fail_lock:
            hits = [t for t in self.auth_fail.get(ip, []) if now - t < 60]
            if hits:
                self.auth_fail[ip] = hits
            else:
                self.auth_fail.pop(ip, None)
            return len(hits) >= 10

    def note_auth_fail(self, ip: str) -> None:
        with self.fail_lock:
            self.auth_fail.setdefault(ip, []).append(time.time())
            if len(self.auth_fail) > 512:
                self.auth_fail.clear()

    def info(self) -> dict:
        return {
            "version": VERSION,
            "mode": self.mode,
            "mode_desc": MODE_DESC.get(self.mode, self.mode),
            "total": self.total,
            "injected": self.injected,
            "failed": self.failed,
            "last_action": self.last_action,
            "last_time": self.last_time,
            "uptime": round(time.time() - self.started_at, 1),
            "dry_run": self.dry_run,
            "udp": self.udp_ok,
            "auth": bool(self.token),
        }


# ---------------------------------------------------------------- UDP 极速通道

class UdpServer(threading.Thread):
    """
    低延迟指令通道（App 主用）。

    请求：PFD1 <token> <clientId> <seq> <action>
    应答：PFD1 ack <seq> ok | dup | auth | err

    dup 表示该 (clientId, seq) 已执行过 —— 客户端据此避免重复翻页。
    TCP 与 UDP 端口空间相互独立，因此可以和 HTTP 复用同一个端口号。
    """

    def __init__(self, app: "PageFlipApp", port: int):
        super().__init__(name="pageflip-udp", daemon=True)
        self.app = app
        self.port = port
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("0.0.0.0", port))
        self.sock.settimeout(0.5)
        self._stop_evt = threading.Event()
        self.packets = 0

    def run(self) -> None:
        while not self._stop_evt.is_set():
            try:
                data, addr = self.sock.recvfrom(512)
            except socket.timeout:
                continue
            except OSError:
                break
            if not data or data[:4] != b"PFD1":
                continue

            parts = data.decode("ascii", "ignore").split()
            if len(parts) >= 5:
                token, client, seq_s, action = parts[1], parts[2], parts[3], parts[4]
            elif len(parts) >= 3:
                # 旧格式兼容：PFD1 <action> <seq>
                token, client, seq_s, action = "", "", parts[2], parts[1]
            else:
                continue

            try:
                seq = int(seq_s)
            except ValueError:
                seq = 0

            if self.app.auth_blocked(addr[0]):
                self._reply(addr, seq, "auth")
                continue
            if not self.app.check_token(token):
                self.app.note_auth_fail(addr[0])
                self._reply(addr, seq, "auth")
                continue

            is_new, prev_ok = self.app.claim(client, seq)
            if not is_new:
                # 重复请求：首发可能还在执行，有界等待后回放真实结果
                if prev_ok is None:
                    prev_ok = self.app.wait_settled(client, seq, 0.3)
                if prev_ok is None:
                    self._reply(addr, seq, "pend")
                else:
                    self._reply(addr, seq, "dup" if prev_ok else "err")
                continue

            ok = self.app.fire(action, 1)
            self.app.settle(client, seq, ok)
            if ok:
                self.packets += 1
            self._reply(addr, seq, "ok" if ok else "err")

    def _reply(self, addr, seq: int, status: str) -> None:
        try:
            self.sock.sendto(f"PFD1 ack {seq} {status}".encode("ascii"), addr)
        except OSError:
            pass

    def stop(self) -> None:
        self._stop_evt.set()
        try:
            self.sock.close()
        except OSError:
            pass


# ---------------------------------------------------------------- HTTP 服务

class FastHTTPServer(ThreadingHTTPServer):
    """关掉 Nagle 合并与启动时的反向 DNS 解析，降低首包延迟。"""

    daemon_threads = True
    allow_reuse_address = True
    disable_nagle_algorithm = True

    def server_bind(self):
        socketserver.TCPServer.server_bind(self)
        self.server_name = "localhost"
        self.server_port = self.server_address[1]


class Handler(BaseHTTPRequestHandler):
    server_version = f"PageFlip/{VERSION}"
    protocol_version = "HTTP/1.1"
    # StreamRequestHandler.setup() 读的是 Handler 上的属性，放 Server 上不生效
    disable_nagle_algorithm = True
    timeout = 15  # 慢连接/半开连接不长期占线程

    app: PageFlipApp = None  # type: ignore[assignment]

    # 关掉默认的 stderr 访问日志，避免刷屏
    def log_message(self, fmt, *args):  # noqa: D102
        pass

    # -- 响应辅助 --------------------------------------------------
    def _send(self, code: int, body: bytes | str, ctype: str, cors: bool = True) -> None:
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store, max-age=0")
        if self.close_connection:
            self.send_header("Connection", "close")
        if cors:
            self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        if self.command != "HEAD" and body:
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass

    def _json(self, obj: dict, code: int = 200, cors: bool = True) -> None:
        self._send(code, json.dumps(obj, ensure_ascii=False),
                   "application/json; charset=utf-8", cors)

    def _same_origin(self, origin: str) -> bool:
        """Origin 必须与请求 Host 同源，防止任意网页读取本机配对码。"""
        host = (self.headers.get("Host") or "").strip()
        if not host:
            return False
        try:
            u = urlparse(origin)
            return u.scheme == "http" and u.netloc == host
        except Exception:
            return False

    def _local_pair_host(self) -> bool:
        """配对码仅允许通过本机字面地址访问，阻止 DNS rebinding。"""
        host = (self.headers.get("Host") or "").strip()
        try:
            u = urlparse("http://" + host)
            return (u.hostname in ("127.0.0.1", "localhost", "::1")
                    and (u.port if u.port is not None else 80) == self.server.server_port
                    and u.username is None and u.password is None
                    and not (u.path or u.params or u.query or u.fragment))
        except ValueError:
            return False

    def _static(self, name: str) -> None:
        path = (WEB_DIR / name).resolve()
        if not str(path).startswith(str(WEB_DIR.resolve())) or not path.is_file():
            return self._send(404, "Not Found", "text/plain; charset=utf-8")
        ctype = {
            ".html": "text/html; charset=utf-8",
            ".js": "application/javascript; charset=utf-8",
            ".css": "text/css; charset=utf-8",
            ".svg": "image/svg+xml",
            ".png": "image/png",
            ".ico": "image/x-icon",
        }.get(path.suffix.lower(), "application/octet-stream")
        self._send(200, path.read_bytes(), ctype)

    # -- 路由 ------------------------------------------------------
    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-PF-Token")
        self.send_header("Access-Control-Max-Age", "600")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _token(self, parsed) -> str:
        t = self.headers.get("X-PF-Token") or ""
        if not t:
            t = (parse_qs(parsed.query).get("t", [""])[0])
        return t.strip()

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path in ("/", "/index.html"):
            return self._static("index.html")
        if path in ("/desk", "/desk.html"):
            return self._static("desk.html")
        if path == "/favicon.ico":
            return self._send(204, b"", "image/x-icon")
        if path == "/api/info":
            return self._json({"ok": True, **self.app.info()})
        if path == "/api/lanip":
            ips = lan_ips()
            return self._json({"ok": True, "ip": ips[0], "ips": ips})
        if path == "/api/verify":
            # 只校验配对码，不执行任何动作；供手机网页在保存前确认
            if self.app.auth_blocked(self.client_address[0]):
                return self._json({"ok": False, "error": "too many attempts"}, 429)
            if not self.app.check_token(self._token(parsed)):
                self.app.note_auth_fail(self.client_address[0])
                return self._json({"ok": False}, 403)
            return self._json({"ok": True})
        if path == "/api/pair":
            # 本机来源 + 本机 Host/端口 + 同源 Origin + 不回全域 CORS
            if self.client_address[0] not in ("127.0.0.1", "::1"):
                return self._json({"ok": False, "error": "local only"}, 403, cors=False)
            if not self._local_pair_host():
                return self._json({"ok": False, "error": "local host required"}, 403, cors=False)
            origin = self.headers.get("Origin")
            if origin and not self._same_origin(origin):
                return self._json({"ok": False, "error": "cross-origin denied"}, 403, cors=False)
            return self._json({"ok": True, "token": self.app.token,
                               "auth": bool(self.app.token)}, 200, cors=False)
        if path == "/apk":
            apk = BASE_DIR / "android" / "PageFlip.apk"
            if apk.is_file():
                return self._send(200, apk.read_bytes(),
                                  "application/vnd.android.package-archive")
            return self._send(404, "尚未编译 APK", "text/plain; charset=utf-8")
        if path == "/qr.svg":
            qs = parse_qs(parsed.query)
            data = (qs.get("u", [""])[0] or "").strip()
            if not data:
                data = f"http://{lan_ips()[0]}:{self.server.server_address[1]}"
            try:
                svg = qr.make_svg(data, scale=8, border=4, dark="#0a0d13", light="#ffffff")
            except ValueError as exc:
                return self._send(400, str(exc), "text/plain; charset=utf-8")
            return self._send(200, svg, "image/svg+xml; charset=utf-8")

        # 极简动作接口：/next  /prev  /start  /stop  /black  ?n=2
        action = path.strip("/").lower()
        if action in self.app.keymap:
            # HEAD 只取元信息，绝不能触发真实按键（爬虫/预取会误触）
            if self.command == "HEAD":
                return self._send(405, b"", "text/plain; charset=utf-8")
            if self.app.auth_blocked(self.client_address[0]):
                return self._json({"ok": False, "error": "too many attempts"}, 429)
            if not self.app.check_token(self._token(parsed)):
                self.app.note_auth_fail(self.client_address[0])
                return self._json({"ok": False, "error": "pairing required"}, 403)
            try:
                n = int(parse_qs(parsed.query).get("n", ["1"])[0])
            except (ValueError, IndexError):
                n = 1
            ok = self.app.fire(action, n)
            return self._json({"ok": ok, "action": action, "count": n})

        # 静态资源
        name = path.lstrip("/")
        if "/" not in name and ".." not in name:
            return self._static(name)
        return self._send(404, "Not Found", "text/plain; charset=utf-8")

    do_HEAD = do_GET

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path.rstrip("/") != "/api/cmd":
            self.close_connection = True  # 未消费请求体，不能复用此连接
            return self._json({"ok": False, "error": "unknown endpoint"}, 404)

        if self.app.auth_blocked(self.client_address[0]):
            self.close_connection = True
            return self._json({"ok": False, "error": "too many attempts"}, 429)

        if not self.app.check_token(self._token(parsed)):
            self.app.note_auth_fail(self.client_address[0])
            self.close_connection = True
            return self._json({"ok": False, "error": "pairing required"}, 403)

        try:
            length = int(self.headers.get("Content-Length") or 0)
        except (TypeError, ValueError):
            length = -1
        if length < 0 or length > 4096 or self.headers.get("Transfer-Encoding"):
            self.close_connection = True
            return self._json({"ok": False, "error": "bad length"}, 400)

        try:
            raw = self.rfile.read(length) if length else b"{}"
            data = json.loads(raw or b"{}")
        except (ValueError, json.JSONDecodeError):
            return self._json({"ok": False, "error": "bad request"}, 400)
        if not isinstance(data, dict):
            return self._json({"ok": False, "error": "bad request"}, 400)

        action = str(data.get("action", "")).lower()
        try:
            repeat = int(data.get("repeat", 1))
        except (TypeError, ValueError, OverflowError):
            repeat = 1

        if action not in self.app.keymap:
            return self._json({"ok": False, "error": f"unknown action: {action}"}, 400)

        client = str(data.get("c", ""))[:32]
        try:
            seq = int(data.get("s", 0))
        except (TypeError, ValueError, OverflowError):
            seq = 0

        # UDP 已执行但回执丢了，客户端会用同一个 (client, seq) 走 HTTP 重试
        is_new, prev_ok = self.app.claim(client, seq)
        if not is_new:
            if prev_ok is None:
                prev_ok = self.app.wait_settled(client, seq, 0.3)
            # 回放真实结果：上次注入失败时，这次重试也必须报失败
            return self._json({"ok": bool(prev_ok), "dup": True,
                               "pending": prev_ok is None,
                               "action": action, "count": 0})

        ok = self.app.fire(action, repeat)
        self.app.settle(client, seq, ok)
        return self._json({"ok": ok, "dup": False, "action": action, "count": repeat})


# ---------------------------------------------------------------- 启动

BANNER = """
──────────────────────────────────────────────
   翻页器 · PageFlip  v{ver}
──────────────────────────────────────────────
   手机打开   {url}
   电脑视图   {desk}
   配对码     {pair}
   按键模式   {mode}
   运行状态   {state}
   指令通道   {udp}
──────────────────────────────────────────────
   手机与电脑需在同一个 WiFi / 局域网内
   按 Ctrl+C 停止服务
──────────────────────────────────────────────
"""


def main() -> int:
    try:
        # 跟随控制台当前编码输出（GBK 控制台就输出 GBK），只额外开启行缓冲。
        # 强行改成 utf-8 在 936 代码页下会显示成乱码。
        for stream in (sys.stdout, sys.stderr):
            enc = getattr(stream, "encoding", None) or "utf-8"
            stream.reconfigure(encoding=enc, errors="replace", line_buffering=True)
    except (AttributeError, OSError, ValueError):
        pass

    ap = argparse.ArgumentParser(description="手机遥控电脑翻页")
    ap.add_argument("--port", type=int, default=8000, help="监听端口，默认 8000")
    ap.add_argument("--mode", choices=["page", "arrow", "space"], default="page",
                    help="按键模式：page=PageUp/PageDown, arrow=↑/↓, space=PageUp/空格")
    ap.add_argument("--dry-run", action="store_true", help="只打印按键不真正发送")
    ap.add_argument("--no-auth", action="store_true", help="关闭配对码校验（不推荐）")
    args = ap.parse_args()

    if not WEB_DIR.is_dir():
        print(f"[错误] 找不到 web 目录：{WEB_DIR}", file=sys.stderr)
        return 1

    token = load_or_create_token(args.no_auth)
    app = PageFlipApp(args.mode, args.dry_run, token)
    Handler.app = app

    httpd = None
    port = args.port
    for candidate in range(args.port, args.port + 20):
        try:
            httpd = FastHTTPServer(("0.0.0.0", candidate), Handler)
            port = candidate
            break
        except OSError:
            continue
    if httpd is None:
        print(f"[错误] 端口 {args.port}-{args.port + 19} 全部被占用", file=sys.stderr)
        return 1

    # UDP 极速通道：与 HTTP 共用端口号（TCP/UDP 端口空间独立）
    udp = None
    try:
        udp = UdpServer(app, port)
        udp.start()
        app.udp_ok = True
    except OSError as exc:
        app.udp_ok = False
        print(f"[提示] UDP 通道不可用（{exc}），App 将自动回退到 HTTP")

    ips = lan_ips()
    url = f"http://{ips[0]}:{port}"
    print(BANNER.format(
        ver=VERSION,
        url=url,
        desk=f"http://127.0.0.1:{port}/desk",
        pair=token if token else "未启用（--no-auth）",
        mode=MODE_DESC.get(args.mode, args.mode),
        state="DRY-RUN（不会真的按键）" if args.dry_run else "运行中",
        udp=f"UDP {port} 已开启（低延迟）" if app.udp_ok else "仅 HTTP",
    ))
    if len(ips) > 1:
        print("  其他可用地址：" + "  ".join(f"http://{ip}:{port}" for ip in ips[1:]))
        print()

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止。")
    finally:
        if udp is not None:
            udp.stop()
        httpd.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
