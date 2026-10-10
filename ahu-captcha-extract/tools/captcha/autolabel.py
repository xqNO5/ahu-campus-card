"""Create two independent local ddddocr predictions for each captcha image."""

import argparse
import csv
import random
from pathlib import Path

import ddddocr


def main() -> int:
    parser = argparse.ArgumentParser(description="智慧安大验证码双模型本地初标注")
    parser.add_argument("--raw", type=Path, default=Path("raw"))
    parser.add_argument("--out", type=Path, default=Path("labels.csv"))
    parser.add_argument("--validation-out", type=Path)
    parser.add_argument("--validation-size", type=int, default=180)
    args = parser.parse_args()

    files = sorted(args.raw.glob("*.jpg"))
    if not files:
        parser.error(f"{args.raw} 中没有 jpg 图片")

    primary = ddddocr.DdddOcr(show_ad=False)
    secondary = ddddocr.DdddOcr(show_ad=False, beta=True)
    rows = []
    exact_agreements = 0
    agreed_chars = 0
    comparable_chars = 0
    for index, path in enumerate(files, 1):
        image = path.read_bytes()
        first = primary.classification(image).strip().upper()
        second = secondary.classification(image).strip().upper()
        rows.append((path.name, first, second))
        if len(first) == len(second) == 4:
            exact_agreements += first == second
            agreed_chars += sum(a == b for a, b in zip(first, second))
            comparable_chars += 4
        if index % 100 == 0:
            print(f"已标注 {index}/{len(files)}")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with args.out.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.writer(stream)
        writer.writerow(["filename", "label", "secondary_label"])
        writer.writerows(rows)

    print(f"双模型标签 -> {args.out} ({len(rows)} 张)")
    print(f"四位整图一致: {exact_agreements}/{len(rows)}")
    if comparable_chars:
        print(f"可比较字符一致率: {agreed_chars / comparable_chars:.2%}")
    print("注意: 模型一致不等于人工真值，不能据此声明真实准确率。")
    if args.validation_out:
        if args.validation_out.exists():
            print(f"保留已有冻结代理集: {args.validation_out}")
        else:
            candidates = [
                (name, first)
                for name, first, second in rows
                if first == second and len(first) == 4 and first.isascii() and first.isalnum()
            ]
            random.Random(42).shuffle(candidates)
            frozen = candidates[:min(args.validation_size, len(candidates))]
            args.validation_out.parent.mkdir(parents=True, exist_ok=True)
            with args.validation_out.open("w", newline="", encoding="utf-8") as stream:
                writer = csv.writer(stream)
                writer.writerow(["filename", "label"])
                writer.writerows(frozen)
            print(f"冻结代理集 -> {args.validation_out} ({len(frozen)} 张)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
