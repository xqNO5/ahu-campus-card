"""Train and export the compact adwmh captcha CNN ensemble."""

import argparse
import csv
import random
import struct
from pathlib import Path

import numpy as np
import tensorflow as tf
from PIL import Image


IMAGE_HEIGHT = 40
IMAGE_WIDTH = 100
CROP_WIDTH = 48
CENTERS = (12, 37, 62, 87)
CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
SEEDS = (7, 42, 137)
MAGIC = b"AHUCAP1\0"
VERSION = 1


def parse_args():
    parser = argparse.ArgumentParser(description="训练紧凑型智慧安大验证码模型")
    parser.add_argument("--raw", type=Path, default=Path("raw"))
    parser.add_argument("--labels", type=Path, default=Path("labels.csv"))
    parser.add_argument("--ground-truth", type=Path)
    parser.add_argument("--validation-set", type=Path)
    parser.add_argument("--out", type=Path, default=Path("models"))
    parser.add_argument("--epochs", type=int, default=100)
    parser.add_argument("--batch", type=int, default=64)
    parser.add_argument("--validation-size", type=int, default=180)
    parser.add_argument("--min-accuracy", type=float, default=0.80)
    parser.add_argument("--max-model-kb", type=float, default=1024.0)
    return parser.parse_args()


def read_rows(path: Path):
    with path.open(encoding="utf-8") as stream:
        return list(csv.DictReader(stream))


def valid_label(label: str) -> bool:
    return len(label) == 4 and all(char in CHARSET for char in label)


def load_images(raw: Path, rows):
    records = []
    for row in rows:
        path = raw / row["filename"]
        first = row.get("label", "").strip().upper()
        second = row.get("secondary_label", "").strip().upper()
        if path.is_file():
            image = np.asarray(Image.open(path).convert("RGB"), np.float32) / 255.0
            if image.shape == (IMAGE_HEIGHT, IMAGE_WIDTH, 3):
                records.append((path.name, image, first, second))
    return records


def crop(image: np.ndarray, position: int) -> np.ndarray:
    half = CROP_WIDTH // 2
    padded = np.pad(image, ((0, 0), (half, half), (0, 0)), constant_values=1.0)
    center = CENTERS[position] + half
    return padded[:, center - half:center + half, :]


def encode(label: str):
    return [CHARSET.index(char) for char in label]


def choose_sets(records, ground_truth: Path | None, validation_set: Path | None, validation_size: int):
    by_name = {record[0]: record for record in records}
    consensus = [(name, first) for name, _, first, second in records
                 if first == second and valid_label(first)]
    if validation_set:
        validation = [
            (row["filename"], row["label"].strip().upper())
            for row in read_rows(validation_set)
            if row["filename"] in by_name and valid_label(row["label"].strip().upper())
        ]
    else:
        random.Random(42).shuffle(consensus)
        count = min(validation_size, max(1, len(consensus) // 5))
        validation = consensus[:count]
    if not validation:
        raise ValueError("validation set 中没有可用的四位标签")

    if ground_truth:
        truth = []
        for row in read_rows(ground_truth):
            name = row["filename"]
            label = row["label"].strip().upper()
            if name in by_name and valid_label(label):
                truth.append((name, label))
        if not truth:
            raise ValueError("ground truth 中没有可用的四位标签")
        truth_names = {name for name, _ in truth}
        validation = [row for row in validation if row[0] not in truth_names]
        return validation, truth, "人工真值"
    return validation, validation, "双模型一致伪标签"


def make_data(records, validation_rows, test_rows):
    excluded_names = {name for name, _ in validation_rows + test_rows}
    train_x, train_y = [], []
    for name, image, first, second in records:
        if name in excluded_names or not (valid_label(first) and valid_label(second)):
            continue
        for position in range(4):
            if first[position] == second[position]:
                train_x.append(crop(image, position))
                train_y.append(CHARSET.index(first[position]))

    by_name = {record[0]: record[1] for record in records}
    def evaluation_data(rows):
        images = np.stack([crop(by_name[name], position)
                           for name, _ in rows for position in range(4)])
        labels = np.asarray([CHARSET.index(label[position])
                             for _, label in rows for position in range(4)], np.int32)
        return images, labels
    validation_x, validation_y = evaluation_data(validation_rows)
    test_x, test_y = evaluation_data(test_rows)
    return (
        np.stack(train_x),
        np.asarray(train_y, np.int32),
        validation_x,
        validation_y,
        test_x,
        test_y,
    )


def build_training_model(seed: int):
    random.seed(seed)
    np.random.seed(seed)
    tf.random.set_seed(seed)
    inputs = tf.keras.Input((IMAGE_HEIGHT, CROP_WIDTH, 3))
    x = tf.keras.layers.RandomTranslation(
        0.04, 0.04, fill_mode="constant", fill_value=1.0,
    )(inputs)
    x = tf.keras.layers.RandomRotation(
        0.015, fill_mode="constant", fill_value=1.0,
    )(x)
    x = tf.keras.layers.Conv2D(
        24, 5, strides=2, use_bias=False, name="conv1",
    )(x)
    x = tf.keras.layers.BatchNormalization(name="bn1")(x)
    x = tf.keras.layers.ReLU()(x)
    x = tf.keras.layers.MaxPooling2D(2)(x)
    x = tf.keras.layers.Conv2D(48, 3, use_bias=False, name="conv2")(x)
    x = tf.keras.layers.BatchNormalization(name="bn2")(x)
    x = tf.keras.layers.ReLU()(x)
    x = tf.keras.layers.Dropout(0.3)(x)
    x = tf.keras.layers.Flatten()(x)
    outputs = tf.keras.layers.Dense(len(CHARSET), name="classifier")(x)
    return tf.keras.Model(inputs, outputs)


def train_model(seed, train_x, train_y, validation_x, validation_y, epochs, batch):
    model = build_training_model(seed)
    model.compile(
        optimizer=tf.keras.optimizers.AdamW(1e-3, weight_decay=1e-4),
        loss=tf.keras.losses.SparseCategoricalCrossentropy(from_logits=True),
        metrics=["sparse_categorical_accuracy"],
    )
    model.fit(
        train_x,
        train_y,
        validation_data=(validation_x, validation_y),
        epochs=epochs,
        batch_size=batch,
        verbose=2,
        callbacks=[tf.keras.callbacks.EarlyStopping(
            monitor="val_sparse_categorical_accuracy",
            patience=12,
            restore_best_weights=True,
        )],
    )
    return model


def fold_batch_norm(model, conv_name, bn_name):
    kernel = model.get_layer(conv_name).get_weights()[0]
    bn = model.get_layer(bn_name)
    gamma, beta, mean, variance = bn.get_weights()
    multiplier = gamma / np.sqrt(variance + bn.epsilon)
    folded_kernel = kernel * multiplier.reshape(1, 1, 1, -1)
    folded_bias = beta - mean * multiplier
    return np.transpose(folded_kernel, (3, 0, 1, 2)), folded_bias


def quantize(weights, biases):
    flat = weights.reshape(weights.shape[0], -1)
    scales = np.max(np.abs(flat), axis=1) / 127.0
    scales[scales == 0] = 1.0
    values = np.clip(np.rint(flat / scales[:, None]), -127, 127).astype(np.int8)
    values = values.reshape(weights.shape)
    return values, scales.astype("<f4"), np.asarray(biases, dtype="<f4")


def quantized_layers(model):
    conv1 = quantize(*fold_batch_norm(model, "conv1", "bn1"))
    conv2 = quantize(*fold_batch_norm(model, "conv2", "bn2"))
    dense_kernel, dense_bias = model.get_layer("classifier").get_weights()
    dense = quantize(np.transpose(dense_kernel, (1, 0)), dense_bias)
    return conv1, conv2, dense


def build_quantized_keras(layers):
    inputs = tf.keras.Input((IMAGE_HEIGHT, CROP_WIDTH, 3))
    x = tf.keras.layers.Conv2D(24, 5, strides=2, activation="relu")(inputs)
    x = tf.keras.layers.MaxPooling2D(2)(x)
    x = tf.keras.layers.Conv2D(48, 3, activation="relu")(x)
    x = tf.keras.layers.Flatten()(x)
    outputs = tf.keras.layers.Dense(len(CHARSET))(x)
    model = tf.keras.Model(inputs, outputs)
    conv1, conv2, dense = layers
    model.layers[1].set_weights([
        np.transpose(conv1[0] * conv1[1][:, None, None, None], (1, 2, 3, 0)), conv1[2],
    ])
    model.layers[3].set_weights([
        np.transpose(conv2[0] * conv2[1][:, None, None, None], (1, 2, 3, 0)), conv2[2],
    ])
    model.layers[5].set_weights([
        np.transpose(dense[0] * dense[1][:, None], (1, 0)), dense[2],
    ])
    return model


def export_model(path: Path, all_layers):
    with path.open("wb") as stream:
        stream.write(MAGIC)
        stream.write(struct.pack("<6i", VERSION, len(all_layers), IMAGE_HEIGHT, IMAGE_WIDTH,
                                 CROP_WIDTH, len(CENTERS)))
        stream.write(struct.pack("<4i", *CENTERS))
        stream.write(struct.pack("<i", len(CHARSET)))
        stream.write(CHARSET.encode("ascii"))
        for layers in all_layers:
            for values, scales, biases in layers:
                stream.write(values.tobytes(order="C"))
                stream.write(scales.tobytes(order="C"))
                stream.write(biases.tobytes(order="C"))


def accuracy(logits, labels):
    predictions = np.argmax(logits, axis=-1).reshape(-1, 4)
    expected = labels.reshape(-1, 4)
    return float(np.mean(np.all(predictions == expected, axis=1))), float(np.mean(predictions == expected))


def main() -> int:
    args = parse_args()
    records = load_images(args.raw, read_rows(args.labels))
    validation_rows, test_rows, test_kind = choose_sets(
        records, args.ground_truth, args.validation_set, args.validation_size,
    )
    train_x, train_y, validation_x, validation_y, test_x, test_y = make_data(
        records, validation_rows, test_rows,
    )
    print(
        f"训练字符: {len(train_y)}; 验证图片: {len(validation_rows)}; "
        f"测试图片: {len(test_rows)} ({test_kind})",
    )
    if len(train_y) < 1000:
        raise ValueError("一致字符训练样本不足 1000")

    layers = []
    quantized_logits = []
    for seed in SEEDS:
        model = train_model(
            seed, train_x, train_y, validation_x, validation_y, args.epochs, args.batch,
        )
        model_layers = quantized_layers(model)
        layers.append(model_layers)
        quantized_model = build_quantized_keras(model_layers)
        quantized_logits.append(quantized_model.predict(test_x, batch_size=128, verbose=0))

    prefix_metrics = []
    for count in range(1, len(quantized_logits) + 1):
        metrics = accuracy(np.mean(quantized_logits[:count], axis=0), test_y)
        prefix_metrics.append(metrics)
        print(f"量化集成 {count} 模型: 整图={metrics[0]:.2%}, 字符={metrics[1]:.2%}")
    selected_count = next(
        (index + 1 for index, (candidate, _) in enumerate(prefix_metrics)
         if candidate >= args.min_accuracy),
        len(prefix_metrics),
    )
    exact, char = prefix_metrics[selected_count - 1]
    args.out.mkdir(parents=True, exist_ok=True)
    output = args.out / "captcha_compact.bin"
    export_model(output, layers[:selected_count])
    size_kb = output.stat().st_size / 1024
    print(
        f"选用 {selected_count} 模型: 整图={exact:.2%}, 字符={char:.2%} ({test_kind})",
    )
    print(f"模型: {output} ({size_kb:.1f} KiB)")
    if not args.ground_truth:
        print("警告: 当前是伪标签代理指标；提供 --ground-truth 后才能声明真实准确率。")
    if exact < args.min_accuracy:
        raise RuntimeError(f"准确率质量门失败: {exact:.2%} < {args.min_accuracy:.2%}")
    if size_kb > args.max_model_kb:
        raise RuntimeError(f"模型体积质量门失败: {size_kb:.1f} KiB > {args.max_model_kb:.1f} KiB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
