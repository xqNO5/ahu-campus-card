"""How Sangfor WebVPN addresses upstream applications.

Every upstream app is reachable at ``https://<vpn-host>/https/<HASH>/<path>``, where
``HASH`` encodes the upstream hostname.  The scheme (verified against live responses) is::

    HASH = hex(b"wrdvpnisthebest!") + AES-CFB128(hostname)
           with Key = IV = b"wrdvpnisthebest!" and no padding

Verified by decryption: ``...ffe7449269276d59660187e289446d36a8d6`` -> open.weixin.qq.com
(the WeChat OAuth stub), ``...e2f252d2302826416f468aa395`` -> res.wx.qq.com, and by
round-tripping the three entry points in the original bug report.

So unlike my first assumption, the hash *is* derivable -- but only with the vendor key.
"""

from __future__ import annotations

import re
from urllib.parse import urlsplit

from Crypto.Cipher import AES

#: WebVPN's fixed host-crypt material, published in the page as
#: ``__vpn_host_crypt_key`` / ``__vpn_host_crypt_iv``.
_CRYPT_KEY = b"wrdvpnisthebest!"
_HASH_PREFIX = _CRYPT_KEY.hex()  # 77726476706e69737468656265737421

#: Upstream applications we care about, keyed by hostname.
APP_HOSTS = {
    "portal": "ycard.ahu.edu.cn",       # 智慧安大 / 校园卡 SPA
    "one": "one.ahu.edu.cn",            # WebVPN 自身的门户
    "adwmh": "adwmh.ahu.edu.cn",        # 一码通入口
    "wechat_oauth": "open.weixin.qq.com",
}

_HASH_IN_URL = re.compile(r"/https/([0-9a-f]{32,})/")


def host_hash(host: str) -> str:
    """Compute the ``/https/<HASH>/`` segment for an upstream hostname."""
    cipher = AES.new(_CRYPT_KEY, AES.MODE_CFB, iv=_CRYPT_KEY, segment_size=128)
    return _HASH_PREFIX + cipher.encrypt(host.encode()).hex()


def unhost_hash(app_hash: str) -> str | None:
    """Recover the upstream hostname from a hash, or ``None`` if it is not one of ours."""
    if not app_hash.startswith(_HASH_PREFIX):
        return None
    cipher = AES.new(_CRYPT_KEY, AES.MODE_CFB, iv=_CRYPT_KEY, segment_size=128)
    try:
        return cipher.decrypt(bytes.fromhex(app_hash[len(_HASH_PREFIX) :])).decode("utf-8")
    except Exception:  # noqa: BLE001
        return None


def hash_from_url(url: str) -> str | None:
    """Extract the ``/https/<HASH>/`` segment of a tunnelled URL."""
    m = _HASH_IN_URL.search(urlsplit(url).path)
    return m.group(1) if m else None


def discover_hashes(html: str) -> dict[str, str]:
    """Map every hostname referenced by a tunnelled page to its hash."""
    out: dict[str, str] = {}
    for h in set(_HASH_IN_URL.findall(html)):
        host = unhost_hash(h)
        if host:
            out[host] = h
    return out


def tunnel(vpn_host: str, host: str, path: str) -> str:
    """Build ``https://<vpn>/https/<HASH>/<path>`` for an upstream hostname."""
    return f"https://{vpn_host}/https/{host_hash(host)}/{path.lstrip('/')}"


def tunnel_url(vpn_host: str, upstream: str) -> str:
    """Rewrite an absolute upstream URL into its WebVPN-tunnelled equivalent."""
    parts = urlsplit(upstream)
    if not parts.scheme or not parts.hostname:
        raise ValueError(f"need an absolute upstream URL, got {upstream!r}")
    path = (parts.path or "/") + (f"?{parts.query}" if parts.query else "")
    return tunnel(vpn_host, parts.hostname, path)
