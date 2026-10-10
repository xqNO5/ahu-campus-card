"""Small offline Tkinter UI for creating an independent captcha ground-truth set."""

import argparse
import csv
import random
from pathlib import Path
import tkinter as tk
from tkinter import messagebox

from PIL import Image, ImageTk


CHARSET = set("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789")


def read_existing(path: Path):
    if not path.is_file():
        return {}
    with path.open(encoding="utf-8") as stream:
        return {row["filename"]: row["label"] for row in csv.DictReader(stream)}


def save(path: Path, labels):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.writer(stream)
        writer.writerow(["filename", "label"])
        writer.writerows(sorted(labels.items()))


def main() -> int:
    parser = argparse.ArgumentParser(description="本地人工标注验证码真值集")
    parser.add_argument("--raw", type=Path, default=Path("raw"))
    parser.add_argument("--out", type=Path, default=Path("ground_truth.csv"))
    parser.add_argument("--count", type=int, default=200)
    parser.add_argument("--seed", type=int, default=20260722)
    args = parser.parse_args()

    files = sorted(args.raw.glob("*.jpg"))
    random.Random(args.seed).shuffle(files)
    files = files[:min(args.count, len(files))]
    labels = read_existing(args.out)
    pending = [path for path in files if path.name not in labels]
    if not pending:
        print(f"抽样中的 {len(files)} 张均已标注: {args.out}")
        return 0

    root = tk.Tk()
    root.title("智慧安大验证码真值标注")
    root.resizable(False, False)
    progress = tk.Label(root, font=("Segoe UI", 11))
    progress.pack(padx=16, pady=(14, 6))
    image_label = tk.Label(root)
    image_label.pack(padx=16, pady=6)
    entry = tk.Entry(root, justify="center", font=("Consolas", 24), width=10)
    entry.pack(padx=16, pady=8)
    hint = tk.Label(root, text="只输入 4 位 A-Z / 0-9，Enter 保存并继续", font=("Segoe UI", 10))
    hint.pack(padx=16, pady=(0, 14))
    state = {"index": 0, "photo": None}

    def show_current():
        index = state["index"]
        if index >= len(pending):
            messagebox.showinfo("完成", f"已保存 {len(labels)} 条真值到\n{args.out}")
            root.destroy()
            return
        path = pending[index]
        image = Image.open(path).convert("RGB").resize((500, 200), Image.Resampling.NEAREST)
        state["photo"] = ImageTk.PhotoImage(image)
        image_label.configure(image=state["photo"])
        progress.configure(text=f"{len(labels) + 1} / {len(files)}    {path.name}")
        entry.delete(0, tk.END)
        entry.focus_set()

    def submit(_event=None):
        value = entry.get().strip().upper()
        if len(value) != 4 or any(char not in CHARSET for char in value):
            root.bell()
            return
        labels[pending[state["index"]].name] = value
        save(args.out, labels)
        state["index"] += 1
        show_current()

    entry.bind("<Return>", submit)
    show_current()
    root.mainloop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
