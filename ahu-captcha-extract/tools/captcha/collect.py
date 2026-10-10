"""
智慧安大验证码采集脚本（小批量验证用）。

输入：
  --count N      采集张数，默认 5
  --interval S   每次请求最小间隔（秒），默认 2.0
  --out DIR      输出目录，默认 ./raw

输出：
  raw/captcha_<timestamp>_<seq>.jpg   验证码图片

安全边界：
  - 仅请求 adwmh.ahu.edu.cn/remind/authcode（公开验证码端点）
  - 不携带任何 cookie / Authorization / 账号信息
  - 强制 TLS 1.2 + 微信 UA，复用 App 的协议约束
  - 严格间隔，避免触发服务器限流
  - 失败退避，不暴力重试
"""

import argparse
import ssl
import sys
import time
from pathlib import Path

import requests
import urllib3
from requests.adapters import HTTPAdapter
from urllib3.util.ssl_ import create_urllib3_context


HOST = "adwmh.ahu.edu.cn"
URL = f"https://{HOST}/remind/authcode"
WECHAT_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36 "
    "NetType/WIFI MicroMessenger/7.0.20.1781 WindowsWechat Flue"
)

# 抑制 InsecureRequestWarning（我们用系统证书，不验证_disablewarn）
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)


class Tls12Adapter(HTTPAdapter):
    """强制 TLS 1.2（adwmh 服务器 TLS 1.3 握手后不返回响应）。"""

    def init_poolmanager(self, *args, **kwargs):
        ctx = create_urllib3_context()
        ctx.options |= ssl.OP_NO_TLSv1_3
        ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.maximum_version = ssl.TLSVersion.TLSv1_2
        kwargs["ssl_context"] = ctx
        return super().init_poolmanager(*args, **kwargs)


def fetch_one(session: requests.Session, timeout: float = 12.0) -> bytes:
    headers = {
        "User-Agent": WECHAT_UA,
        "Accept": "image/webp,image/*,*/*",
        "X-Requested-With": "XMLHttpRequest",
        "Referer": f"https://{HOST}/www/index.html",
    }
    resp = session.get(URL, headers=headers, timeout=timeout, verify=True)
    resp.raise_for_status()
    body = resp.content
    if not body or len(body) < 100:
        raise RuntimeError(f"验证码内容异常: {len(body)} bytes")
    return body


def main():
    parser = argparse.ArgumentParser(description="智慧安大验证码采集")
    parser.add_argument("--count", type=int, default=5, help="采集张数")
    parser.add_argument("--interval", type=float, default=2.0, help="请求间隔（秒）")
    parser.add_argument("--out", type=str, default="raw", help="输出目录")
    args = parser.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    session = requests.Session()
    session.mount("https://", Tls12Adapter())

    ok = 0
    fail = 0
    for i in range(args.count):
        attempt = 0
        while attempt < 3:
            attempt += 1
            try:
                t0 = time.time()
                body = fetch_one(session)
                ts = int(time.time() * 1000)
                fname = out_dir / f"captcha_{ts}_{i:04d}.jpg"
                fname.write_bytes(body)
                ok += 1
                print(f"[{i+1}/{args.count}] OK {len(body)}B -> {fname.name} ({attempt}次尝试, {time.time()-t0:.2f}s)")
                break
            except Exception as e:
                print(f"[{i+1}/{args.count}] FAIL attempt={attempt}: {type(e).__name__}: {e}")
                if attempt < 3:
                    backoff = 2.0 * attempt
                    print(f"  退避 {backoff}s 后重试...")
                    time.sleep(backoff)
                else:
                    fail += 1

        if i < args.count - 1:
            time.sleep(args.interval)

    print(f"\n完成: 成功 {ok} / 失败 {fail} / 总 {args.count}")
    return 0 if fail == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
