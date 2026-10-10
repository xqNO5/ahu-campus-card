"""Sangfor WebVPN + AHU CAS login, and helpers for tunnelled URLs.

The WebVPN exposes each upstream application at

    https://wvpn.ahu.edu.cn/https/<HASH>/<upstream path>

where ``<HASH>`` is an opaque per-application identifier (see ``vpn_apps``).  This module
owns the CAS handshake and hands back a `requests.Session` authenticated for the tunnel.
"""

from __future__ import annotations

import re
import time
from dataclasses import dataclass, field
from urllib.parse import urlsplit

import requests

from .cas_des import str_enc
from .vpn_apps import discover_hashes, hash_from_url, tunnel, tunnel_url

DEFAULT_VPN_HOST = "wvpn.ahu.edu.cn"

DESKTOP_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)

#: What a WeChat in-app browser sends.  The 一码通 gateway gates on this **client-side**.
WECHAT_UA = (
    "Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230805.001; wv) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 "
    "Mobile Safari/537.36 MMWEBID[0] MicroMessenger/8.0.47.2560(0x28002F35) "
    "WeChat/arm64 Weixin NetType/WIFI Language/zh_CN ABI/arm64"
)


class WebVpnError(RuntimeError):
    """Raised when the WebVPN or CAS leg fails."""


@dataclass
class AhuWebVpnClient:
    """Logs into the AHU WebVPN and issues requests through the tunnel."""

    username: str
    password: str
    vpn_host: str = DEFAULT_VPN_HOST
    user_agent: str = DESKTOP_UA
    timeout: int = 30
    session: requests.Session = field(default_factory=requests.Session, repr=False)
    #: application hashes learned at runtime, host/route -> hash
    hashes: dict[str, str] = field(default_factory=dict, repr=False)

    def __post_init__(self) -> None:
        self.session.headers.update(
            {
                "User-Agent": self.user_agent,
                "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            }
        )

    # ---------------------------------------------------------------- properties
    @property
    def root(self) -> str:
        return f"https://{self.vpn_host}"

    @property
    def ticket(self) -> str | None:
        return self.session.cookies.get(f"wengine_vpn_ticket{self.vpn_host.replace('.', '_')}")

    # -------------------------------------------------------------------- login
    def login(self) -> requests.Session:
        """Run the full CAS handshake.  Returns the authenticated session."""
        page = self.session.get(f"{self.root}/login", timeout=self.timeout)
        if page.status_code != 200:
            raise WebVpnError(f"GET /login -> HTTP {page.status_code}")

        action = self._find_login_action(page.text)
        fields = self._hidden_fields(page.text)
        lt = fields.get("lt")
        if not lt:
            raise WebVpnError("login page carried no CAS `lt` token")

        cas_base = urlsplit(action).path.rsplit("/", 1)[0]

        # The page first posts the 3DES blob to /cas/device; CAS refuses the credential
        # form unless that device check has already succeeded in this session.
        rsa = str_enc(self.username + self.password + lt, "1", "2", "3")
        device = self.session.post(
            f"{self.root}{cas_base}/device",
            data={
                "ul": len(self.username),
                "pl": len(self.password),
                "rsa": rsa,
                "method": "login",
            },
            headers={
                "X-Requested-With": "XMLHttpRequest",
                "Referer": page.url,
                "Origin": self.root,
            },
            timeout=self.timeout,
        )
        verdict = device.text.strip()
        if '"ok"' not in verdict:
            if "nf" in verdict or "err" in verdict:
                raise WebVpnError("用户名或密码错误（device 校验未通过）")
            if "unbind" in verdict:
                raise WebVpnError("该账号需要先绑定手机号才能登录")
            raise WebVpnError(f"unexpected /cas/device response: {verdict[:200]!r}")

        payload = dict(fields)
        payload.update(
            {
                "un": self.username,
                "pd": self.password,
                "ul": str(len(self.username)),
                "pl": str(len(self.password)),
                "rsa": rsa,
            }
        )
        submit = self.session.post(
            f"{self.root}{action}",
            data=payload,
            headers={"Referer": page.url, "Origin": self.root},
            timeout=self.timeout,
            allow_redirects=False,
        )
        ticket_url = submit.headers.get("Location")
        if submit.status_code != 302 or not ticket_url:
            raise WebVpnError(
                f"CAS login did not issue a ticket (HTTP {submit.status_code}); "
                "check the account or whether an extra verification step is required"
            )

        # Carry the ticket all the way through /login?cas_login=true ->
        # wengine-vpn-token-login; that hop is what stamps the authenticated ticket.
        # A brand-new ticket can be refused ("非法的Token") for a few hundred ms, so retry.
        landed = None
        for attempt in range(4):
            landed = self.session.get(ticket_url, timeout=self.timeout, allow_redirects=True)
            if landed.status_code == 200 and "非法" not in landed.text:
                return self.session
            time.sleep(0.8 * (attempt + 1))
        raise WebVpnError(
            "ticket exchange failed after retries "
            f"(HTTP {landed.status_code if landed else '?'}"
            f"{'; 非法的Token' if landed is not None and '非法' in landed.text else ''}); "
            "logging in too often in a row can trip this"
        )

    # --------------------------------------------------------------- tunnelling
    def url_for(self, upstream: str) -> str:
        """Tunnelled URL for an absolute upstream URL (hash derived from its hostname)."""
        return tunnel_url(self.vpn_host, upstream)

    def learn_hashes(self, page_url: str) -> dict[str, str]:
        """Open an already-reachable tunnelled page and report the hosts it references."""
        html = self.session.get(page_url, timeout=self.timeout).text
        found = discover_hashes(html)
        self.hashes.update(found)
        return found

    def get(self, upstream: str, **kwargs) -> requests.Response:
        kwargs.setdefault("timeout", self.timeout)
        return self.session.get(self.url_for(upstream), **kwargs)

    # ------------------------------------------------------------ page scraping
    @staticmethod
    def _find_login_action(html: str) -> str:
        m = re.search(r'<form[^>]*id="loginForm"[^>]*action="([^"]+)"', html) or re.search(
            r'<form[^>]*action="([^"]*cas/login[^"]*)"', html
        )
        if not m:
            raise WebVpnError("could not locate the CAS login form")
        return m.group(1).replace("&amp;", "&")

    @staticmethod
    def _hidden_fields(html: str) -> dict[str, str]:
        fields: dict[str, str] = {}
        for tag in re.findall(r"<input[^>]*>", html):
            if "hidden" not in tag:
                continue
            name = re.search(r'name="([^"]+)"', tag)
            if not name:
                continue
            value = re.search(r'value="([^"]*)"', tag)
            fields[name.group(1)] = value.group(1) if value else ""
        return fields


__all__ = [
    "AhuWebVpnClient",
    "WebVpnError",
    "DESKTOP_UA",
    "WECHAT_UA",
    "DEFAULT_VPN_HOST",
    "discover_hashes",
    "hash_from_url",
    "host_hash",
    "unhost_hash",
    "tunnel",
    "tunnel_url",
]

