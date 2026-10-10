# 验证码采集 / 标注 / 训练工具

为 `adwmh.ahu.edu.cn` 的支付码登录训练四位验证码模型。图片采集、标注、训练和 App 推理均在本机完成，验证码不会发送到外部 OCR 或 AI 服务。

> **注意**：这套脚本在原仓库里于 2026-07-31 被删除（理由是「不再维护」），
> 本目录是从 git 历史 commit `5973898` 恢复出来的原始版本，未做任何修改。
> 恢复的目的只是让训练链路可复现，不代表它还在被维护。

## 为什么不用通用 OCR / CRNN

首版 6.20 MB CRNN 在 973 张四位伪标签上的整图准确率只有 7.61%，且 TFLite Android AAR 本身约 6.23 MB。该方案同时无法满足准确率和 App 增幅小于 1 MB 的目标。

当前方案利用「验证码固定为四位」这一协议事实：

- 从 100x40 RGB 图片的四个固定中心提取 48x40 字符窗。
- 三个小型 CNN 对字符窗分类并平均 logits。
- BatchNorm 折叠后按输出通道将权重量化为 int8。
- App 用纯 Kotlin 执行卷积，不引入 TFLite/ONNX/Paddle 等运行库。

导出的 `captcha_compact.bin` 约 357 KiB。2005 张样本、冻结的 180 张一致伪标签留出集上，量化后三模型整图准确率为 93.33%，字符准确率为 98.33%；单模型和双模型分别为 84.44% 和 88.33%。

**这些都只是代理指标，不是人工真值准确率**，不能对外当成真实准确率宣传。

## 文件

| 脚本 | 用途 |
|---|---|
| `collect.py` | 按 TLS 1.2、2 秒节流从公开验证码端点采集图片 |
| `autolabel.py` | 用 ddddocr 默认版和 beta 版生成两列本地伪标签 |
| `label.py` | 随机抽样并人工创建独立 `ground_truth.csv` |
| `train.py` | 训练三模型集成、量化导出并执行准确率/体积质量门 |

`raw/`、`models/` 和所有 CSV 都必须被 Git 忽略。验证码图片和标签不得提交。

## 流程

在本目录运行：

```powershell
python collect.py --count 1000 --interval 2.0 --out raw
python autolabel.py --raw raw --out labels.csv --validation-out validation_proxy.csv
python label.py --raw raw --out ground_truth.csv --count 200
python train.py --raw raw --labels labels.csv --validation-set validation_proxy.csv --ground-truth ground_truth.csv --out models
```

人工真值至少建议 200 张，并且不能预先查看伪标签。`train.py` 会把真值图片完全排除在训练和 EarlyStopping 验证数据外。`autolabel.py` 只在 `validation_proxy.csv` 不存在时创建冻结调参集，后续扩大数据再标注不会覆盖它。默认质量门为：

- 整图准确率不低于 80%。
- `captcha_compact.bin` 不大于 1024 KiB。

未传 `--ground-truth` 时，脚本会使用 `--validation-set` 指定的冻结代理集，或从两套 OCR 完全一致的数据中固定留出最多 180 张，只能用于快速回归和调参，不能用于对外声明真实准确率。导出器会选择达到 `--min-accuracy` 所需的最小集成数（实测选了 3 个，即全部）。

通过质量门后部署：

```powershell
Copy-Item models/captcha_compact.bin ../../app/src/main/assets/captcha_compact.bin
```

App 端 `AdwmhCaptchaRecognizer` 只负责本机图片解码，`CompactCaptchaModel` 解析量化权重并推理。模型缺失、损坏或识别失败时，登录流程回退到手动输入。

## 安全边界

- `collect.py` 只请求 `https://adwmh.ahu.edu.cn/remind/authcode`，不携带账号、Cookie 或 Authorization。
- ddddocr 仅用于开发机本地伪标注，其 54 MB ONNX 模型不会打进 App。
- 识别结果、图片和验证码原文不得写入日志、诊断包或版本库。
- 服务端样式或字符长度变化后必须重新采样、人工测试和训练，不能沿用旧准确率。

## 复现时注意

- **没有附带训练数据**。`raw/`（2005 张图）、`labels.csv`、`validation_proxy.csv` 等都在原仓库本地，没有进版本库，这里也不提供。
  要复现就从 `collect.py` 重新采集 —— 采集端点是公开的，不需要登录。
- 依赖：`tensorflow`、`numpy`、`Pillow`、`requests`（采集）、`ddddocr`（伪标注）、`tkinter`（人工标注，Python 自带）。
- `train.py` 会在 20 万+ 字符样本上跑三个模型各 100 epoch，**单机 CPU 训练不现实**，原项目是在有 GPU 的开发机上跑的。
- 恢复出来的脚本里没有任何硬编码账号、Cookie 或 token，可以直接跑。
