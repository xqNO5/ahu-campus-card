"""Derive and verify the WebVPN `/https/<HASH>/` host-crypt algorithm.

Hypothesis:  HASH = hex("wrdvpnisthebest!") + AES-CFB128(hostname)
             with Key == IV == b"wrdvpnisthebest!" and no padding.

We verify against hashes that are *known to work* on the live VPN, then cross-check by
confirming that each decrypted host serves the content we actually fetched through it.
"""
from __future__ import annotations

from Crypto.Cipher import AES

KEY = b"wrdvpnisthebest!"
PREFIX = KEY.hex()  # "77726476706e69737468656265737421"


def host_hash(host: str) -> str:
    cipher = AES.new(KEY, AES.MODE_CFB, iv=KEY, segment_size=128)
    return PREFIX + cipher.encrypt(host.encode()).hex()


def unhost_hash(h: str) -> bytes:
    assert h.startswith(PREFIX)
    cipher = AES.new(KEY, AES.MODE_CFB, iv=KEY, segment_size=128)
    return cipher.decrypt(bytes.fromhex(h[len(PREFIX) :]))


if __name__ == "__main__":
    # hashes whose live behaviour we have already observed
    OBSERVED = {
        # returned the 智慧安大 portal SPA shell referencing /plat/js/*.js
        "77726476706e69737468656265737421e9f4408e237e69586b468ca88d1b203b": "?",
        # served /tp_up/ (the VPN's own portal)
        "77726476706e69737468656265737421fff944d226387d1e7b0c9ce29b5b": "?",
        # WeChat OAuth stub:   "抱歉，请在微信..." + res.wx.qq.com/weui
        "77726476706e69737468656265737421ffe7449269276d59660187e289446d36a8d6": "open.weixin.qq.com",
        # assets: res.wx.qq.com/open/... wx_fed
        "77726476706e69737468656265737421e2f252d2302826416f468aa395": "res.wx.qq.com",
        # assets: obtelemetry/phantom.min.js
        "77726476706e69737468656265737421e2f252c4692770417d0486b99c1b32245dbea19e45b60a": "res8.wxqcloud.qq.com.cn",
    }

    print("== 解密已知 hash 反推主机 ==")
    ok = 0
    for h, expected in OBSERVED.items():
        try:
            host = unhost_hash(h).decode()
        except Exception as exc:  # noqa: BLE001
            host = f"<解密失败 {exc}>"
        mark = "OK " if host == expected else "?  "
        if host == expected:
            ok += 1
        print(f"  {mark} {h[:24]}... -> {host!r:34} 期望 {expected!r}")

    print(f"\n  {ok}/{len(OBSERVED)} 与已观测行为一致")

    print("\n== 重新加密回环校验 ==")
    for host in ("adwmh.ahu.edu.cn", "ycard.ahu.edu.cn", "one.ahu.edu.cn", "open.weixin.qq.com"):
        h = host_hash(host)
        back = unhost_hash(h).decode()
        print(f"  {host:24} -> {h}  回环={back == host}")

    print("\n== 现场校验：用户最初给的 3 个 URL 分别指向哪台主机 ==")
    for label, h in (
        ("plat/shouyeUser", "77726476706e69737468656265737421e9f4408e237e69586b468ca88d1b203b"),
        ("一码通 OAuth", "77726476706e69737468656265737421ffe7449269276d59660187e289446d36a8d6"),
        ("tp_up", "77726476706e69737468656265737421fff944d226387d1e7b0c9ce29b5b"),
    ):
        print(f"  {label:18} -> {unhost_hash(h).decode()}")
