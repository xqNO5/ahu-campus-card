"""Cache an authenticated WebVPN session on disk for the exploratory scripts.

Repeated CAS logins trip the server's rate limit ("非法的Token"), so the scripts share one
ticket via a local cookie jar. The file is deleted when it stops working.
"""
from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from ahu_client.webvpn import AhuWebVpnClient  # noqa: E402

JAR = ROOT / "reports" / ".session.json"


def _load_into(client: AhuWebVpnClient) -> bool:
    if not JAR.exists():
        return False
    try:
        data = json.loads(JAR.read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001
        return False
    # Sessions are short-lived; ignore anything older than 30 minutes.
    if time.time() - data.get("saved_at", 0) > 1800:
        return False
    client.session.cookies.update(data.get("cookies", {}))
    return True


def _save(client: AhuWebVpnClient) -> None:
    JAR.parent.mkdir(exist_ok=True)
    JAR.write_text(
        json.dumps(
            {
                "saved_at": time.time(),
                "cookies": client.session.cookies.get_dict(),
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )


def session(force_login: bool = False) -> AhuWebVpnClient:
    user = os.environ.get("AHU_USER", "")
    password = os.environ.get("AHU_PASS", "")
    if not user or not password:
        raise SystemExit("设置 AHU_USER / AHU_PASS 环境变量后重试")

    c = AhuWebVpnClient(user, password)

    if not force_login and _load_into(c):
        probe = c.get("https://ycard.ahu.edu.cn/plat/shouyeUser")
        if probe.status_code == 200 and "wengine-vpn/failed" not in probe.url:
            print("复用了已保存的会话")
            return c
        print("保存的会话已失效，重新登录")

    c.login()
    _save(c)
    print("登录成功，会话已保存")
    return c


if __name__ == "__main__":
    session(force_login="--force" in sys.argv)
