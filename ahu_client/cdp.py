"""Minimal Chrome DevTools Protocol driver (no Playwright/Selenium needed)."""
from __future__ import annotations

import json
import shutil
import socket
import subprocess
import tempfile
import time
from pathlib import Path
from urllib.request import urlopen

import websocket  # websocket-client

CHROME_CANDIDATES = [
    Path(r"C:\Program Files\Google\Chrome\Application\chrome.exe"),
    Path(r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe"),
    Path.home() / r"AppData\Local\Google\Chrome\Application\chrome.exe",
    Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"),
    Path(r"C:\Program Files\Microsoft\Edge\Application\msedge.exe"),
]


def find_chrome() -> Path:
    for p in CHROME_CANDIDATES:
        if p.exists():
            return p
    found = shutil.which("chrome") or shutil.which("msedge")
    if found:
        return Path(found)
    raise RuntimeError("no Chrome/Edge executable found")


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Chrome:
    """Launches a headless browser and exposes a thin CDP client."""

    def __init__(self, headless: bool = True, profile_dir: str | Path | None = None):
        self.exe = find_chrome()
        self.port = _free_port()
        self._tmp = None
        if profile_dir is None:
            self._tmp = tempfile.mkdtemp(prefix="ahu-chrome-")
            profile_dir = self._tmp
        self.profile = str(profile_dir)
        self.proc: subprocess.Popen | None = None
        self.ws: websocket.WebSocket | None = None
        self.session_id: str | None = None
        self._msg_id = 0
        self.target_id: str | None = None
        self.headless = headless

    # ------------------------------------------------------------------ lifecycle
    def start(self, start_url: str = "about:blank") -> None:
        args = [
            str(self.exe),
            f"--remote-debugging-port={self.port}",
            f"--user-data-dir={self.profile}",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-features=Translate,OptimizationHints,MediaRouter",
            "--disable-background-networking",
            "--disable-popup-blocking",
            "--window-size=1200,900",
        ]
        if self.headless:
            args += ["--headless=new", "--disable-gpu"]
        args.append(start_url)
        # Let Chrome write its own profile; do not pipe stdio through Python.
        self.proc = subprocess.Popen(
            args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL
        )
        self._await_devtools()
        target = self._first_page_target()
        self.target_id = target["id"]
        self.ws = websocket.create_connection(
            target["webSocketDebuggerUrl"], timeout=60, max_size=64 * 1024 * 1024
        )

    def _await_devtools(self, timeout: float = 30.0) -> None:
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                with urlopen(f"http://127.0.0.1:{self.port}/json/version", timeout=2) as r:
                    json.load(r)
                return
            except Exception:
                if self.proc and self.proc.poll() is not None:
                    raise RuntimeError("browser exited during startup")
                time.sleep(0.3)
        raise TimeoutError("devtools endpoint never came up")

    def _first_page_target(self) -> dict:
        for _ in range(60):
            with urlopen(f"http://127.0.0.1:{self.port}/json/list", timeout=5) as r:
                targets = json.load(r)
            for t in targets:
                if t.get("type") == "page":
                    return t
            time.sleep(0.3)
        raise RuntimeError("no page target")

    def close(self) -> None:
        try:
            if self.ws:
                self.ws.close()
        except Exception:
            pass
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        if self._tmp:
            shutil.rmtree(self._tmp, ignore_errors=True)

    def __enter__(self) -> "Chrome":
        self.start()
        return self

    def __exit__(self, *exc) -> None:
        self.close()

    # ------------------------------------------------------------------------ CDP
    def send(self, method: str, params: dict | None = None, timeout: float = 60.0) -> dict:
        assert self.ws is not None
        self._msg_id += 1
        mid = self._msg_id
        payload = {"id": mid, "method": method, "params": params or {}}
        if self.session_id:
            payload["sessionId"] = self.session_id
        self.ws.send(json.dumps(payload))
        self.ws.settimeout(timeout)
        while True:
            raw = self.ws.recv()
            msg = json.loads(raw)
            if msg.get("id") == mid:
                if "error" in msg:
                    raise RuntimeError(f"{method} failed: {msg['error']}")
                return msg.get("result", {})

    def drain_events(self, seconds: float = 1.0) -> list[dict]:
        """Collect any pending CDP events for `seconds`."""
        assert self.ws is not None
        events: list[dict] = []
        deadline = time.time() + seconds
        while time.time() < deadline:
            self.ws.settimeout(max(0.05, deadline - time.time()))
            try:
                events.append(json.loads(self.ws.recv()))
            except Exception:
                break
        return events

    # ------------------------------------------------------------------ convenience
    def navigate(self, url: str, wait: float = 3.0) -> None:
        self.send("Page.enable")
        self.send("Page.navigate", {"url": url})
        time.sleep(wait)

    def eval(self, expression: str, await_promise: bool = False, timeout: float = 60.0):
        res = self.send(
            "Runtime.evaluate",
            {
                "expression": expression,
                "returnByValue": True,
                "awaitPromise": await_promise,
                "userGesture": True,
            },
            timeout=timeout,
        )
        if res.get("exceptionDetails"):
            raise RuntimeError(json.dumps(res["exceptionDetails"])[:800])
        return res.get("result", {}).get("value")
