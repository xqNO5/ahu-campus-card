# 安徽大学校园卡二维码 — 协议逆向与 Android 实现

获取安徽大学校园卡的**付款二维码**。客户端为纯 Kotlin、**零第三方运行时依赖**，通过
GitHub Actions 构建 APK（无需本地 Android Studio）。

**仓库**：https://github.com/xqNO5/ahu-campus-card

---

## 1. 结论先行

**不需要微信，也不需要 WebVPN。**

一码通页面本身有一条微信专属网关（`adwmh.ahu.edu.cn/index/wxindex/login`，服务端强制要求
微信 OAuth 的 `code`），那条路确实绕不过。但**同一个站点还有一套普通的账号密码 + 图形验证码
登录**，走的是完全不同的接口：

```
POST https://adwmh.ahu.edu.cn/user/session      → JSESSIONID（匿名会话）
GET  https://adwmh.ahu.edu.cn/remind/authcode    → 4 位图形验证码
POST https://adwmh.ahu.edu.cn/user/login         → 绑定账号（仅需一次）
GET  https://adwmh.ahu.edu.cn/xzxcard/qrcode     → 完整二维码内容 + 签发时间
GET  https://adwmh.ahu.edu.cn/xzxcard/yue        → 余额
```

关键点：

- `adwmh.ahu.edu.cn`（`101.76.160.195`）**公网直连**，不需要 WebVPN 隧道。
- `JSESSIONID` 一旦绑定账号就长期可用 —— **登录一次，之后打开即用**，无需再输验证码。
- 图形验证码**由用户本人识别**，客户端不做验证码自动识别。

### 二维码内容的真实形态

一开始只拿到 20 位数字，与实际不符。解码官方页面截图后确认内容形如：

```
41194784288188343519 SPAEMBAGdcymoBAQE1ATEyAwC+x2oAAAAziQO6OPpKoqEDAGYDAAAADGfTGqgtAaf9PCtTuAifP77Ia7aBR5WSSivsCnXC
└── 20 位数字（每 60 秒轮换）  └── 94 字符 base64url 服务端签名 token（解出 70 字节）
```

对应 QR **version 6 / 纠错等级 L / 41×41 模块**。注意官方页面把前 20 位另画成一个
CODE128 条形码（`val.slice(0,20)`），二维码用的才是完整值 —— 这也是页面上"小二维码一闪而过"
的原因（先渲染 20 位版本，再换成完整版）。

---

## 2. 实测记录

| 步骤 | 结果 |
|---|---|
| `POST /user/session` | `{"code":10000,...,"object":{"user":null}}`，下发 `JSESSIONID=…; Path=/; HttpOnly` |
| `GET /remind/authcode` | 返回 100×40 JPEG 验证码（约 2 KB） |
| `POST /user/login` | 假验证码 → `{"code":10012,"msg":"图形验证码错误"}`；正确验证码 → `{"code":10000,"msg":"操作成功！",...}` |
| `GET /xzxcard/qrcode` | `{"code":10000,"msg":"2026-10-10 23:52:17","object":"41812…114 字符…"}` |
| `GET /xzxcard/yue` | `{"code":10000,"object":8.7}` |
| 缓存 cookie 后**新进程**直接取码 | 成功，114 字符，无需登录 |

---

## 3. 仓库结构

```
android/                          Android 应用（纯 Kotlin，零第三方运行时依赖）
  app/src/main/java/cn/edu/ahu/cardqr/
    net/HttpClient.kt             HttpURLConnection 封装：手动 cookie、手动跳转
    data/CampusCardSession.kt     会话（session/captcha/login/qrcode/yue）
    qr/QrEncoder.kt               自写二维码编码器（纯计算，可单测）
    qr/QrRenderer.kt              矩阵 → Bitmap
    store/SecureStore.kt          Android Keystore + AES-256-GCM 加密存储
    MainActivity.kt               首次登录页 + 二维码页（每分钟自动刷新）
  app/src/test/…                  JVM 单元测试 + 参考夹具
  verify-stubs/                   本地验证用的 Android / org.json 桩
  verify-harness/VerifyMain.kt    本地验证程序（含真实服务直连验证）

tools/                            逆向与验证脚本
  verify-android.ps1              一键本地验证（不需要 Android SDK）
  validate_payload.py             校验任意二维码内容：报版本/纠错等级/掩码并回读
  qr_port_check.py                二维码编码器的 Python 镜像（用于比对）
  qr_decode_check.py              用解码器回读验证生成的二维码
  qr_kotlin_compare.py            Kotlin 与 Python 二维码矩阵逐模块比对
  gen_qr_fixtures.js              生成 Kotlin 二维码测试夹具
  inspect_barcodes.py             对比 batchGetBarCodeGet 两种 codeType 的返回形态
  ci_status.py / ci_log.py        查看 CI 状态与失败日志
  session.py                      adwmh 会话缓存（供脚本复用，避免重复登录）

.github/workflows/android.yml     CI：单元测试 + 构建 APK
```

---

## 4. 构建 APK（无需 Android Studio）

推送到 GitHub 后，**Build APK** 工作流会跑单元测试、构建 debug APK 并作为 artifact 上传。

**取 APK**：打开 [Actions 页面](https://github.com/xqNO5/ahu-campus-card/actions) → 最新成功的
**Build APK** → 底部 **Artifacts** → 下载 `ahu-campus-card-debug-apk`（约 0.9 MB）。

### 使用方式

1. **首次**：输入学号、密码、以及图上显示的 4 位验证码 → 登录。
2. **之后**：打开 App **直接显示可用二维码**，每分钟自动刷新，无需任何输入。

会话（`JSESSIONID`）用 Android Keystore 里的 AES-256-GCM 密钥加密后存 SharedPreferences，
密钥不出安全硬件，拷走文件无法解密。密码不保存。若服务端使会话失效，App 会自动回到登录页。

---

## 5. 本地验证（不需要 Android SDK）

```powershell
pwsh -File tools/verify-android.ps1
```

用桩类编译纯逻辑 Kotlin 并运行断言，需要 JDK 17 与 `.tools/` 下的 Kotlin 编译器 jar：

```
kotlin-compiler-embeddable-1.9.24.jar   kotlin-stdlib-1.9.24.jar
trove4j-1.0.20200330.jar                kotlin-daemon-embeddable-1.9.24.jar
kotlin-reflect-1.9.24.jar               annotations-24.0.1.jar
```

真实服务直连验证（会真的登录，凭据从环境变量读取）：

```powershell
cd android
$env:AHU_USER='<学号>'; $env:AHU_PASS='<密码>'
$java = "C:\Program Files\Java\jdk-17.0.5\bin\java.exe"
# 1) 取验证码并保存会话
& $java -cp ".verify-build;..\.tools\kotlin-stdlib.jar" cn.edu.ahu.cardqr.verify.VerifyMain direct
# 2) 看清 android/reports/captcha_live.jpg 后填入
& $java -cp ".verify-build;..\.tools\kotlin-stdlib.jar" "-Dahu.user=$env:AHU_USER" "-Dahu.pass=$env:AHU_PASS" "-Dahu.captcha=XXXX" cn.edu.ahu.cardqr.verify.VerifyMain direct
# 3) 再次运行（不填验证码）即验证"打开即用"
```

---

## 6. 验证情况

Kotlin/Android 侧（`tools/verify-android.ps1`）：

| 验证项 | 结果 |
|---|---|
| 二维码矩阵 4 个纠错级别 vs Python 参考 | 逐模块完全一致 |
| 真实 114 字符载荷 | 编成 **version 6 / L**，与官方一致；可渲染 |
| 定位图案 / 版本递增 / 超长拒绝 | 通过 |
| `CardQr` 载荷解析（20 位 + token） | 通过 |
| **真实服务**：验证码登录 → 取 114 字符码 → 余额 | 通过 |
| **重启免登录**：新进程仅凭缓存 cookie 取码 | 通过 |

二维码编码器验证：

| 验证项 | 方法 | 结果 |
|---|---|---|
| 与 `qrcode` 库逐模块比对 | 44 组用例 | 41 组完全一致，其余仅掩码选择不同 |
| 掩码一致性（32 组字节模式载荷） | 与 `qrcode` 参考 | **31/32** |
| 可扫性 | 解码器回读 | 全部解出原文 |
| 真实官方载荷 | 编码 + 回读 | version 6 / L，字节级一致 |

### 6.1 关于掩码选择

官方页面两张截图的掩码是 4 和 6，本项目的编码器对同一载荷选 3。排查结论：本项目的掩码与
`qrcode` 参考实现一致（31/32），属于标准实现；官方用的是它自己的评分实现。ISO/IEC 18004 中
8 种掩码等效，**不影响可扫性** —— 本项目生成的二维码经解码器回读全部成功。

（`segno` 一致率较低只是因为它在字节模式下会把纯数字串改压成 numeric 模式、版本都不同，
掩码不可直接比较。）

---

## 7. 逆向过程中修掉的真实缺陷

这些都是靠本地验证与 CI 抓出来的，不是推测：

1. **`Bitmap.createBitmap(w,h,null)` 无法通过编译** —— Android 的第三参数是 `@NonNull`。
   起因是我的离线桩比真实 SDK 宽松，CI 一跑就暴露。桩已改成与 SDK 一致（嵌套 `Config`、
   非空参数），这样此类错误在本地就会被拦下。
2. **`HttpClient` 内部装了全局 `CookieManager`，把 `Set-Cookie` 头吃掉了** —— 导致
   `JSESSIONID` 根本保存不下来，"打开即用"因此不可能实现。改为手动管理 cookie 后修复。
3. **单字符密钥未补齐到 4 个 UTF-16 码元**（旧 CAS 3DES 路径）—— 表现为 4 字符输入正确、
   9 字符输入错误。
4. **二维码格式信息放置错误**：横纵条带弄反、位序弄反 —— 与参考实现从 16 处差异收敛到 0。
5. **验证脚本只检查部分关键字**，harness 在别处失败也报成功；夹具找不到时会**静默跳过**
   整个矩阵比对。现在会检查退出码、任何 `CHECK FAIL` 与 `SUMMARY OK`，缺夹具直接算失败。

---

## 8. 注意事项

- **凭据不要提交进仓库。** 脚本从 `AHU_USER` / `AHU_PASS` 读取；App 由用户运行时输入，
  且不保存密码。
- 会话文件（`reports/.adwmh_session.json`、`android/reports/`）已在 `.gitignore` 中，不要提交。
- 二维码内容每 60 秒轮换，App 每分钟自动刷新，回到前台也会刷新。
- 若服务端让 `JSESSIONID` 失效（改密码、风控、服务端重启），App 会检测到 302 并回到登录页。
- 本项目的客户端**不做验证码自动识别**，验证码由用户本人识别 —— 一次登录即可长期免输。
- 本项目仅供本人查询自己的校园卡使用；请勿用于他人账号。
