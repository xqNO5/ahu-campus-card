# 验证码识别小模型（抽取版）

从 **Ahu_Plus**（安徽大学校园助手，Kotlin + Jetpack Compose）里抽出来的端侧验证码识别模块，
含**训练链路**和**训练好的模型**，用于给同行参考写法。

抽取原则：核心代码逐行保留，与外部模块耦合的地方统一标 `★ 耦合点 ★` 并说明原项目怎么接的。
**训练数据没有放进来**（原始图片和标签都没有进版本库，也涉及隐私）。

---

## 一句话概括

> 一个 357 KiB 的纯 Kotlin 验证码识别器，不依赖任何推理框架，
> 用三个量化过的迷你 CNN 做集成，在 2005 张样本上拿到 93% 的整图准确率。

之所以成立，是因为利用了「验证码固定四位、图片固定 100×40」这个协议事实：
把不定长的序列识别问题（需要 CRNN + CTC）降级成了四次定长分类。

---

## 目录结构

```
app/src/main/java/com/example/captcha/
├── CompactCaptchaModel.kt        ★ 推理运行时。纯 Kotlin/JVM，零第三方依赖
└── AdwmhCaptchaRecognizer.kt       Android 包装层：图片字节 → Bitmap → 像素数组

app/src/main/assets/
└── captcha_compact.bin           ★ 训练好的模型，357 KiB（可直接使用）

app/src/test/java/com/example/captcha/
└── CompactCaptchaModelTest.kt      含 Python↔Kotlin 数值对拍 fixture

tools/captcha/
├── README.md                       训练工具自己的说明（输入/输出/安全边界）
├── collect.py                      采集验证码图片（公开端点，无需登录）
├── autolabel.py                    用 ddddocr 双模型生成伪标签
├── label.py                        人工标注独立真值集（Tkinter 小工具）
└── train.py                      ★ 训练 + 量化导出，含质量门
```

来源对照：

| 本目录 | Ahu_Plus 原路径 |
|---|---|
| `app/src/main/java/com/example/captcha/CompactCaptchaModel.kt` | `app/src/main/java/com/ahu_plus/data/repository/CompactCaptchaModel.kt` |
| `app/src/main/java/com/example/captcha/AdwmhCaptchaRecognizer.kt` | `app/src/main/java/com/ahu_plus/data/repository/AdwmhCaptchaRecognizer.kt` |
| `app/src/main/assets/captcha_compact.bin` | 同路径 |
| `app/src/test/java/com/example/captcha/CompactCaptchaModelTest.kt` | `app/src/test/java/com/ahu_plus/data/repository/CompactCaptchaModelTest.kt` |
| `tools/captcha/*.py` | `tools/captcha_collector/*.py`（**已于 2026-07-31 从仓库删除**，本目录从 git 历史 commit `5973898` 恢复，未做修改） |

---

## 整体流程

```
  ① 采集              ② 伪标注               ③ 人工真值（可选但推荐）
collect.py   ──►   autolabel.py   ──►   label.py
（2s 节流）        （ddddocr 双模型）      （抽 200 张手工标）
     │                  │                      │
     ▼                  ▼                      ▼
   raw/*.jpg        labels.csv           ground_truth.csv
                        │                      │
                        └──────────┬───────────┘
                                   ▼
                          ④ train.py
                    ┌──────────────────────────┐
                    │ 3 个种子各训一个 CNN       │
                    │ BatchNorm 折叠            │
                    │ 按输出通道量化 int8        │
                    │ 选最小够用的集成数         │
                    │ 质量门：准确率/体积         │
                    └──────────────────────────┘
                                   │
                                   ▼
                        captcha_compact.bin (357 KiB)
                                   │
                                   ▼
                    ⑤ 端侧推理 CompactCaptchaModel.kt
                        （手写卷积，无推理框架）
```

---

## 值得借鉴的几个点

### 1. 用协议事实简化问题，而不是上更大的模型

首版走的是「CRNN + CTC」这条通用路线（能处理任意长度、任意间隔的序列），结果：

- 6.20 MB 的模型，在 973 张四位伪标签上整图准确率只有 **7.61%**
- TFLite Android AAR 本身就要 6.23 MB

改成「四个固定中心裁窗 + 四次定长分类」之后：

| 方案 | 整图准确率 | 模型体积 | 运行时依赖 |
|---|---|---|---|
| CRNN + CTC | 7.61% | 6.20 MB | TFLite (+6.23 MB) |
| 本方案 | 93.33%（代理指标） | 357 KiB | 无 |

**这是这个模块最值得抄的一点**：先问「这个问题真的需要通用解吗」，
再看协议里有没有可以利用的固定约束。这里的约束是「永远四位、位置固定」——
一旦抓住，CTC、对齐、序列解码整套复杂度全部消失。

### 2. 不引入推理框架，手写卷积

`CompactCaptchaModel.kt` 里没有 TFLite / ONNX Runtime / Paddle Lite。
卷积、maxpool、全连接都是朴素 for 循环（连 im2col 都没做）。

成立的前提是三个「小」：模型小、输入小（48×40×3）、层数浅（2 conv + 1 dense）。
这种规模下手写的性价比很高 —— 几百行代码换掉 6 MB 的运行时依赖。

反过来，如果模型再大一点或输入再宽一点，手写就会变成负担。**别把它当成通用做法。**

### 3. int8 权重 + float 激活

只量化权重（按**输出通道**各自一个 scale），激活值始终是 float：

```kotlin
sum += input[inputIndex++] * layer.weights[weightIndex++].toInt()
val value = sum * layer.scales[outputChannel] + layer.biases[outputChannel]
```

累加过程仍是浮点，所以不需要写定点数乘法器，也不用做激活值的动态范围校准。
体积降到 1/4，精度损失在这个任务上可以忽略。

### 4. BatchNorm 在导出时折叠进卷积

训练时有 `BatchNorm`，导出时用 `fold_batch_norm()` 把 `gamma/beta/mean/variance`
折进卷积核和偏置，运行时**完全看不到 BN 层**：

```python
multiplier = gamma / np.sqrt(variance + bn.epsilon)
folded_kernel = kernel * multiplier.reshape(1, 1, 1, -1)
folded_bias = beta - mean * multiplier
```

训练侧多做一点，推理侧就简单一点 —— 这个分工是刻意的。

### 5. 集成 = logits 相加取 argmax

三个不同随机种子训出来的网络共享同一份输入裁剪，各自算 logits，逐元素相加再取最大：

```kotlin
networks.forEach { network ->
    val logits = network.infer(crop)
    logits.indices.forEach { i -> ensembleLogits[i] += logits[i] }
}
```

比投票简单，也不用存概率分布。实测收益很大：单模型 84.44% → 双模型 88.33% → 三模型 93.33%。

而且 `train.py` 会**自动选择达到质量门所需的最小集成数**，
不是为了凑数就把三个都塞进去 —— 准确率够用就行，体积能省则省：

```python
selected_count = next(
    (index + 1 for index, (candidate, _) in enumerate(prefix_metrics)
     if candidate >= args.min_accuracy),
    len(prefix_metrics),
)
```

### 6. 一个自定义二进制格式 + 逐字段校验

模型不是 pickle、不是 SavedModel、不是 TFLite，就是一个手写的扁平字节流：

```
magic "AHUCAP1\0" | version | modelCount | H | W | cropW | charCount
| centers[4] | charsetLen | charset(ASCII)
| modelCount × { conv1, conv2, dense }，每层 = int8 权重 + f32 scales + f32 biases
```

全部小端序。加载时**逐字段 `require` 校验**，任何一项不符就抛异常：

```kotlin
require(centers.contentEquals(EXPECTED_CENTERS)) { "模型字符中心不匹配" }
require(!buffer.hasRemaining()) { "模型文件包含未识别数据" }
```

宁可加载失败回退手动输入，也不要拿一个尺寸错位的模型算出「看起来正常」的错字符。

### 7. 跨语言数值对拍 fixture

手写推理最容易错在**权重布局**上（HWC vs CHW、transpose 方向、量化轴），
而这类错误不抛异常，只会安静地输出乱码。所以测试里钉了一条：

```kotlin
@Test
fun `deployed model matches Python export parity fixture`() {
    val model = File("src/main/assets/captcha_compact.bin").inputStream()
        .use(CompactCaptchaModel::load)
    // 确定性的伪随机图，不是真验证码
    val pixels = IntArray(100 * 40) { index -> /* 固定公式算像素 */ }
    assertEquals("BQWW", model.recognize(pixels))
}
```

输入是用固定公式生成的伪随机图（任何机器上结果都一样），
把「Python 导出」和「Kotlin 推理」钉死在同一个输出上。任一侧改了布局，这条立刻红。

**跨语言实现同一套数值逻辑时，这是最省事也最有效的对拍方式** ——
CI 里不需要跑 Python，只要在训练流程末尾把期望输出记下来。

### 8. 失败必须能优雅降级

`AdwmhCaptchaRecognizer` 永不抛异常，任何一步失败（模型缺失、图片解不出、推理出错）
都返回 `null`，由上层回退到手动输入。

```kotlin
} catch (e: Throwable) {
    Log.w(TAG, "本地验证码识别失败: ${e.javaClass.simpleName}")
    null
}
```

验证码识别的定位是**锦上添花**：识别成功用户无感，识别失败用户照样能登录。
让它有失败路径能打断登录流程，就是把一个可选优化变成了新的故障点。

另外注意 `finally { scaled.recycle() }` —— 位图是 native 内存，不依赖 GC，
连续重试几次不回收就能把 Android 的位图内存吃满。

---

## 耦合点清单（照抄前必看）

代码里搜 `★ 耦合点 ★` 能定位到全部。

| # | 位置 | 原项目依赖 | 抽取版怎么处理 |
|---|---|---|---|
| 1 | `AdwmhCaptchaRecognizer` 的 `Context` | 手工 DI：`AhuPlusApplication` 里构造一次，注入给 `AdwmhCardRepository` | 保持构造注入。用 Hilt/Koin 就声明 `@Singleton` —— 每次构造都要重新解析 357 KiB 模型 |
| 2 | `AdwmhCaptchaRecognizer` 的 `ASSET_MODEL` | 从 `assets/captcha_compact.bin` 加载 | 改成文件系统 / 下载目录也只需改这一行 |
| 3 | `AdwmhCaptchaRecognizer` 的日志 | 项目的脱敏 `SafeLog` | 换成 `android.util.Log`。**注意**：日志里绝不能出现识别结果或验证码原文 |
| 4 | `CompactCaptchaModel.fromBytes` ↔ `train.py export_model` | 两者是**同一份私有契约**的两端 | 改任一侧必须同步改另一侧；`VERSION` 字段只用来拒绝不认识的文件，没有迁移机制 |
| 5 | `CompactCaptchaModel` 的所有尺寸常量 | 来自「验证码固定四位、图片固定 100×40」的协议事实 | 服务端样式一变，**整条链路**（采集 → 训练 → 导出 → 运行时）都要重来，不能只改常量 |
| 6 | `class CompactCaptchaModel` 是 `internal` | 同模块内可见即可 | 你放在不同 module 的话改成 `public` |
| 7 | `collect.py` 的 `TLS 1.2` 强制 | `adwmh` 服务器 TLS 1.3 握手后不返回响应 | 换目标站点通常可以删掉 `Tls12Adapter` |
| 8 | `collect.py` 的微信 UA + Referer | 目标站点的协议约束 | 换站点要跟着改 |
| 9 | 上层的重试/降级循环 | `AdwmhCardRepository.autoLoginInternal`（见下） | 这份抽取版**没有包含**这个循环，需要你自己写 |

---

## 怎么接进去

### 最小可用集

```
app/src/main/java/com/example/captcha/CompactCaptchaModel.kt
app/src/main/assets/captcha_compact.bin
```

只要这两样就能识别 —— 连 `AdwmhCaptchaRecognizer` 都不必需（它只是帮你处理 Bitmap）。
输入是 `IntArray`（Android `Bitmap.getPixels` 的 ARGB_8888 格式），输出是四位字符串。

### 上层的降级循环（原项目 `AdwmhCardRepository.autoLoginInternal` 节选）

这是整个集成里最关键的一段 —— **识别只是第一步，重试策略才是**：

```kotlin
val result = doLogin(username, password, captcha = "", generation, priority)
if (result.isSuccess) return result

// 服务端要求验证码
val recognizer = captchaRecognizer
if (recognizer == null || !recognizer.isAvailable) {
    // 模型不可用 → 直接弹手动输入，不做无谓的请求
    return Result.failure(AdwmhCaptchaRequiredException("需要手动输入验证码"))
}

repeat(LOCAL_RECOGNIZE_MAX_RETRIES) { attempt ->   // 实测 2 次是平衡点
    currentCoroutineContext().ensureActive()
    val captchaBytes = fetchCaptcha(priority).getOrNull() ?: return@repeat
    val recognized = recognizer.recognize(captchaBytes)
    if (recognized.isNullOrBlank()) return@repeat
    val retryResult = doLogin(username, password, recognized, generation, priority)
    if (retryResult.isSuccess) return retryResult
}
// 重试仍失败 → 回退手动输入，不走异常打断
return Result.failure(AdwmhCaptchaRequiredException("需要手动输入验证码"))
```

三个设计决定值得注意：

1. **重试次数是 2 次，不是无限**。目标站点对突发请求敏感，暴力重试会触发限流，
   反而把一个可选优化变成风控事故。原项目还配了 1.5 秒的请求最小间隔。
2. **失败路径统一收敛到「手动输入」**，不是抛出各种不同的异常让 UI 去区分。
3. **`isAvailable` 提前判**，模型没加载成功就不发那次取验证码的请求。

---

## 依赖

| 用途 | 依赖 |
|---|---|
| 推理运行时 | **无**。只用 `java.io.InputStream` / `java.nio.ByteBuffer` |
| Android 包装层 | Android SDK（`Bitmap`/`BitmapFactory`）+ Kotlin 协程 |
| 单测 | JUnit 4 |
| 训练 | TensorFlow / NumPy / Pillow |
| 采集 | requests / urllib3 |
| 伪标注 | ddddocr（54 MB ONNX，**只在开发机跑，不进 App**） |
| 人工标注 | tkinter（Python 自带）+ Pillow |

App 侧**零新增依赖**是这个方案的核心卖点。

---

## 已知边界

- **准确率是代理指标，不是人工真值**。93.33% 整图 / 98.33% 字符是在「180 张双模型
  一致伪标签」上测的，和真实准确率可能差很远（伪标签本身有偏）。
  `label.py` 和 `train.py --ground-truth` 这条人工真值路径是**备好了但没有真正跑出
  一份有代表性的真值集**就定了模型。要对外声明准确率，先补真值。
- **服务端样式一变就全废**。固定中心裁窗、固定四位、固定 36 类字符集，
  全部依赖目标站点的当前实现。改版后必须重新采集 + 人工验证 + 重训，不能沿用旧准确率。
- **模型格式没有版本迁移**。`VERSION` 字段只是拒绝位，`fromBytes` 里全是 `require`。
  换格式就是换文件，没有兼容层。
- **三模型集成是 CPU 串行推理**。每次识别要跑 3 个网络 × 4 个字符窗 = 12 次前向。
  在这个尺寸下可接受（毫秒级），但换大模型就不行。
- **日志红线**。识别结果、验证码原文、验证码图片都不得写进日志/诊断包/版本库 ——
  验证码是登录凭据的一部分。原项目只记录「成功/失败」和异常类名。

---

## 构建验证状态

- ✅ **编译**：把 `app/src/main/java/com/example/captcha/` 临时挂进 Ahu_Plus 的
  Debug 源集，`./gradlew :app:compileDebugKotlin` 通过
  （Gradle 9.4.1 / AGP 9.2.1 / Kotlin 2.2.10）。
- ✅ **单测**：`CompactCaptchaModelTest` **3 条全绿**（`tests=3 failures=0 errors=0`），
  包括那条跨语言对拍断言 —— 也就是说**这份 `.bin` 与这份推理运行时确实匹配**，
  不是「看起来应该能跑」。
- 📦 **模型文件**：`app/src/main/assets/captcha_compact.bin`，365776 字节，
  是从 Ahu_Plus 仓库直接复制的线上文件，二进制逐字节一致。

由于编译验证是临时挂源集做的，**本目录没有自己的 Gradle 工程** ——
把源码按包路径放进你现有工程即可，不需要额外配构建。
