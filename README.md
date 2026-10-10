# 安徽大学校园卡二维码 — 协议逆向与 Android 实现

获取安徽大学校园卡的**付款二维码**。客户端为纯 Kotlin、**零第三方运行时依赖** ——
连验证码识别也是自带的量化 CNN（不引入 TFLite / ONNX / 协程运行时），通过
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
- 图形验证码由 App **自带的端侧模型识别**（见 §4）：识别不出来才回退人工输入，识别错误则
  换一张重试（有界）。会话过期后，用 Keystore 加密保存的密码 + 自动识别的验证码**静默重登**，
  用户全程看不到登录页。

### 二维码内容的真实形态

一开始只拿到 20 位数字，与实际不符。解码官方页面截图后确认内容形如：

```
41194784288188343519 SPAEMBAGdcymoBAQE1ATEyAwC+x2oAAAAziQO6OPpKoqEDAGYDAAAADGfTGqgtAaf9PCtTuAifP77Ia7aBR5WSSivsCnXC
└── 20 位数字（每 60 秒轮换）  └── 94 字符 base64url 服务端签名 token（解出 70 字节）
```

对应 QR **version 6 / 纠错等级 L / 41×41 模块**。注意官方页面把前 20 位另画成一个
CODE128 条形码（`val.slice(0,20)`），二维码用的才是完整值 —— 这也是页面上"小二维码一闪而过"
的原因（先渲染 20 位版本，再换成完整版）。

这正是"token 需要鉴权且会过期"的部分：二维码值 = **20 位数字 + 服务端签名 token**，
token 由登录态签发，每 60 秒轮换，所以 App 每分钟重新取一次。

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
| 端侧模型识别**真实**验证码 | `reports/captcha_live.jpg` → `NRXG`，人工放大核对一致 |

---

## 3. 仓库结构

```
android/                          Android 应用（纯 Kotlin，零第三方运行时依赖）
  app/src/main/java/cn/edu/ahu/cardqr/
    net/HttpClient.kt             HttpURLConnection 封装：手动 cookie、手动跳转
    data/CampusCardSession.kt     会话（session/captcha/login/qrcode/yue）
    data/AutoLogin.kt             无感登录策略：识别 → 有界重试 → 人工降级
    captcha/CompactCaptchaModel.kt  量化 CNN 推理运行时（纯 JVM，手写卷积）
    captcha/CaptchaRecognizer.kt    图片字节 → Bitmap → 像素数组 → 四个字符
    qr/QrEncoder.kt               自写二维码编码器（纯计算，可单测）
    qr/QrRenderer.kt              矩阵 → Bitmap
    store/SecureStore.kt          Android Keystore + AES-256-GCM 加密存储
    MainActivity.kt               登录页 + 二维码页（每分钟自动刷新）
  app/src/main/assets/
    captcha_compact.bin           训练好的验证码模型，365776 字节（随 APK 打包）
  app/src/test/…                  JVM 单元测试 + 参考夹具
  verify-stubs/                   本地验证用的 Android / org.json 桩
  verify-harness/VerifyMain.kt    本地验证程序（含真实服务直连验证）

tools/                            逆向与验证脚本
  verify-android.ps1              一键本地验证（不需要 Android SDK）
  check_android_resources.py      资源引用静态检查：@string / R.id 是否都能解析
  validate_payload.py             校验任意二维码内容：报版本/纠错等级/掩码并回读
  qr_port_check.py                二维码编码器的 Python 镜像（用于比对）
  qr_decode_check.py              用解码器回读验证生成的二维码
  qr_kotlin_compare.py            Kotlin 与 Python 二维码矩阵逐模块比对
  gen_qr_fixtures.js              生成 Kotlin 二维码测试夹具
  inspect_barcodes.py             对比 batchGetBarCodeGet 两种 codeType 的返回形态
  ci_status.py / ci_log.py        查看 CI 状态与失败日志
  session.py                      adwmh 会话缓存（供脚本复用，避免重复登录）

.github/workflows/android.yml     CI：单元测试 + 构建 APK + 校验模型确实打进 APK
```

---

## 4. 无感登录：端侧验证码模型

### 4.1 模型从哪来

`captcha/CompactCaptchaModel.kt` 与 `assets/captcha_compact.bin` 来自
**Ahu_Plus**（安徽大学校园助手）的端侧验证码识别模块，经 `ahu-captcha-extract/` 抽取版并入
本工程（只改了包名、去掉了协程依赖）。整个目录保留在仓库里，含训练链路说明与
训练工具，便于日后服务端改版时重训。

| 项 | 值 |
|---|---|
| 结构 | 3 个迷你 CNN 集成（logits 相加取 argmax） |
| 输入 | 固定 100×40 彩色，四个固定中心裁窗（12/37/62/87） |
| 输出 | 固定 4 字符，36 类字符集（A–Z + 0–9） |
| 权重量化 | int8 权重 + 每输出通道 float scale/bias（BatchNorm 训练时折叠进卷积） |
| 体积 | 365776 字节（357 KiB），**无推理框架**，纯 Kotlin for 循环 |
| 精度 | 93.33% 整图（**代理指标**，见 4.4） |

它成立的关键不是模型强，而是**协议事实**：验证码永远四位、图片永远 100×40、字符位置固定。
抓住这三条，不定长序列识别（CRNN + CTC）就降级成四次定长分类，CTC、对齐、序列解码全部消失。

### 4.2 登录流程怎么用它

`data/AutoLogin.kt` 是唯一持有重试策略的地方，`MainActivity` 只负责把结果画出来：

```kotlin
repeat(maxAttempts) { attempt ->          // 默认 3 次，有界
    if (attempt > 0) sleep(1_500)         // 限速，避免触发风控
    val image = api.fetchCaptcha()        // 每张验证码只能猜一次
    val guess = solver(image) ?: return@repeat
    try { api.login(user, pass, guess); return Success }
    catch (e: CampusCardException) {
        if (e.kind != CAPTCHA) throw e    // 密码错/网络错不在这里重试
    }
}
return ManualCaptchaRequired(freshCaptcha())   // 统一收敛到人工输入
```

三个刻意的决定：

1. **重试有界（3 次）且限速（1.5 s）**。目标站点对突发请求敏感，暴力重试会把一个可选优化
   变成风控事故。约 93% 的单次准确率下，3 次仍失败的概率在个位数百分比。
2. **所有失败路径收敛成「人工输入」一种结果**（`ManualCaptchaRequired`），顺带把一张
   **没被消费过的新验证码**交给 UI —— 用户只需读图输 4 位，不用重新输入账号密码。
3. **日志绝不含验证码或识别结果**。识别只是登录凭据的一部分，日志里只记成功/失败与
   异常类名（`AutoLogin.redactForLog`）。

失败降级的三档原因也会如实告诉用户：模型不可用 / 识别不出 / 识别结果被服务端拒绝
（最后一档通常意味着服务端改了验证码样式，模型该重训了）。

### 4.3 密码怎么存

要做到"会话过期也无需任何操作"，就得能自动重登，因此密码需要落盘 —— 这是本项目里
**唯一一处安全取舍**，实现上尽量收紧：

- 只存在 `SecureStore` 里：AES-256-GCM，密钥由 Android Keystore 生成且**不出安全硬件**
  （`setKeySize(256)`、GCM、无 padding），拷走 `SharedPreferences` 文件在其他设备上解不开。
- 登录页有「记住密码」开关，**取消勾选即删除**已存密文；「退出登录」也会一并抹掉。
- 密码永远不进日志、不进诊断包；主界面在登录成功后立刻清空密码输入框。

### 4.4 已知边界

- **准确率是代理指标，不是人工真值**。93.33% 是在「180 张双模型一致伪标签」上测的，
  伪标签本身有偏。要对外声明准确率，得先补人工真值集。
- **服务端样式一变就全废**。固定四位、固定裁窗中心、固定 36 类字符集全部依赖目标站点当前
  实现；改版后必须重新采集 + 人工验证 + 重训，不能沿用旧准确率。
- **模型没有格式迁移机制**。`VERSION` 字段只是拒绝位，`fromBytes` 里全是 `require`：
  宁可加载失败回退人工输入，也不拿一个尺寸错位的模型算出"看起来正常"的错字符。

---

## 5. 构建 APK（无需 Android Studio）

推送到 GitHub 后，**Build APK** 工作流会跑单元测试、构建 debug APK、**校验模型确实被打进
APK**（`unzip -l` 找 `assets/captcha_compact.bin`，并打印模型 SHA-256）并作为 artifact 上传。

**取 APK**：打开 [Actions 页面](https://github.com/xqNO5/ahu-campus-card/actions) → 最新成功的
**Build APK** → 底部 **Artifacts** → 下载 `ahu-campus-card-debug-apk`（实测 1,196,719 字节，
其中 357 KiB 是验证码模型本体）。

### 使用方式

1. **首次**：输入学号、密码 → 验证码由本机模型识别，**不需要看验证码**。
   万一识别不出来，界面会显示一张新验证码让你手输 4 位。
2. **之后**：打开 App **直接显示可用二维码**，每分钟自动刷新，无需任何输入。
3. **会话过期**（服务端重启、改密码、风控）：有保存的密码就静默重登并继续显示二维码；
   只有当密码也不对/没保存时，才回到登录页。

---

## 6. 本地验证（不需要 Android SDK）

```powershell
pwsh -File tools/verify-android.ps1
```

它先跑一遍**资源引用静态检查**（不需要 JDK/Kotlin，秒级返回）：

```powershell
python tools/check_android_resources.py android
```

这一步专门防住"CI 跑到资源链接阶段才炸"的那类错误 —— 布局或代码里引用了不存在的
`@string/xxx` / `R.id.xxx`（接入模型时改布局最容易踩）。

然后用桩类编译纯逻辑 Kotlin 并运行断言，需要 JDK 17 与 `.tools/` 下的 Kotlin 编译器 jar：

```
kotlin-compiler-embeddable-1.9.24.jar   kotlin-stdlib-1.9.24.jar
trove4j-1.0.20200330.jar                kotlin-daemon-embeddable-1.9.24.jar
kotlin-reflect-1.9.24.jar               annotations-24.0.1.jar
```

这套桩里有两点是**故意做成真货**的，否则验证就只是"能编译"：

- `BitmapFactory` 用 ImageIO **真的解码** JPEG，所以 图片字节 → Bitmap → 缩放 → 像素 →
  模型 这条路径是真跑的；
- `Log` 把日志打到 stdout，于是"日志里没有验证码"这件事可以被看见。

单独用真实验证码图试模型（不联网）：

```powershell
cd android
$java = "C:\Program Files\Java\jdk-17.0.5\bin\java.exe"
& $java -cp ".verify-build;..\.tools\kotlin-stdlib.jar" cn.edu.ahu.cardqr.verify.VerifyMain captcha <图片路径>
```

真实服务直连验证（会真的登录，凭据从环境变量读取）：

```powershell
cd android
$env:AHU_USER='<学号>'; $env:AHU_PASS='<密码>'
# 1) 取验证码并保存会话（同时打印本机模型读出的结果）
& $java -cp ".verify-build;..\.tools\kotlin-stdlib.jar" cn.edu.ahu.cardqr.verify.VerifyMain direct
# 2) 用模型读出的四位字符登录
& $java -cp ".verify-build;..\.tools\kotlin-stdlib.jar" "-Dahu.user=$env:AHU_USER" "-Dahu.pass=$env:AHU_PASS" "-Dahu.captcha=XXXX" cn.edu.ahu.cardqr.verify.VerifyMain direct
# 3) 再次运行（不填验证码）即验证"打开即用"
```

---

## 7. 验证情况

Kotlin/Android 侧（`tools/verify-android.ps1`，33 项检查）：

| 验证项 | 结果 |
|---|---|
| 二维码矩阵 4 个纠错级别 vs Python 参考 | 逐模块完全一致 |
| 真实 114 字符载荷 | 编成 **version 6 / L**，与官方一致；可渲染 |
| 定位图案 / 版本递增 / 超长拒绝 | 通过 |
| `CardQr` 载荷解析（20 位 + token） | 通过 |
| **模型数值对拍**（确定性伪随机图 → `BQWW`） | 通过 —— 证明 `.bin` 与推理运行时匹配 |
| **真实验证码**经 `CaptchaRecognizer` 识别 | `captcha_live.jpg` → `NRXG`，与人工核对一致 |
| 模型缺失 / 图片非法 → 返回 null 而非抛异常 | 通过 |
| 自动登录策略：首次成功 / 有界重试 / 限速 / 人工降级 | 通过 |
| **真实服务**：验证码登录 → 取 114 字符码 → 余额 | 通过 |
| **重启免登录**：新进程仅凭缓存 cookie 取码 | 通过 |

JVM 单元测试（CI 的 `testDebugUnitTest`）覆盖同样的模型对拍，外加 `AutoLogin` 策略的
完整矩阵（首次命中、被拒绝后换图重试、预算耗尽降级、识别失败不浪费登录请求、
密码错误不被当作验证码问题重试、日志不含验证码）。

二维码编码器验证：

| 验证项 | 方法 | 结果 |
|---|---|---|
| 与 `qrcode` 库逐模块比对 | 44 组用例 | 41 组完全一致，其余仅掩码选择不同 |
| 掩码一致性（32 组字节模式载荷） | 与 `qrcode` 参考 | **31/32** |
| 可扫性 | 解码器回读 | 全部解出原文 |
| 真实官方载荷 | 编码 + 回读 | version 6 / L，字节级一致 |

### 7.1 关于掩码选择

官方页面两张截图的掩码是 4 和 6，本项目的编码器对同一载荷选 3。排查结论：本项目的掩码与
`qrcode` 参考实现一致（31/32），属于标准实现；官方用的是它自己的评分实现。ISO/IEC 18004 中
8 种掩码等效，**不影响可扫性** —— 本项目生成的二维码经解码器回读全部成功。

（`segno` 一致率较低只是因为它在字节模式下会把纯数字串改压成 numeric 模式、版本都不同，
掩码不可直接比较。）

---

## 8. 逆向过程中修掉的真实缺陷

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
6. **照抄模型包装层会带进两个隐患**：(a) 抽取版是 `suspend fun` +
   `withContext(Dispatchers.Default)`，本工程是零第三方运行时依赖，直接抄会为了识别多带一个
   协程运行时 —— 改成同步调用，由登录流程自己的 IO 线程驱动即可；
   (b) 抽取版在**构造函数**里解析 357 KiB 模型，若在 `onCreate` 里构造就卡住主线程 ——
   改成首次用到时在 IO 线程懒加载。

---

## 9. 注意事项

- **凭据不要提交进仓库。** 脚本从 `AHU_USER` / `AHU_PASS` 读取；App 由用户运行时输入，
  密码只以 Keystore 加密形态存在本机，且可以随时关掉「记住密码」清除。
- 会话文件（`reports/.adwmh_session.json`、`android/reports/`）已在 `.gitignore` 中，不要提交。
- 二维码内容每 60 秒轮换，App 每分钟自动刷新，回到前台也会刷新。
- 若服务端让 `JSESSIONID` 失效（改密码、风控、服务端重启），App 会先静默重登；重登也失败
  才回到登录页。
- 验证码识别的定位是**锦上添花**：识别成功用户无感，识别失败用户照样能登录 ——
  所以它永不抛异常、永不阻断登录流程。
- 训练数据（验证码原始图片与标签）**不在** `ahu-captcha-extract/` 里，只有代码、模型与
  训练脚本：原始验证码涉及他人登录凭据，不入版本库。
- 本项目仅供本人查询自己的校园卡使用；请勿用于他人账号。
