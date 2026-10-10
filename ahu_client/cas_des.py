"""Faithful Python port of the CAS login page's `strEnc` 3DES.

The school's `des.js` is a hand-rolled DES that packs each UTF-16 code unit into 16
bits MSB-first and zero-pads partial 64-bit blocks.  It is *not* byte-compatible with
a standard DES implementation, so we port it bit-for-bit.  `tests/test_des.py` pins this
port against the original JavaScript via Node.

The literal tables live in `des_tables.py`, extracted from the served file by
`tools/extract_des_tables.py`.
"""

from __future__ import annotations

from .des_tables import EP, FP, IP, KEY_SHIFT, P_BOX, S1, S2, S3, S4, S5, S6, S7, S8

_SBOXES = (S1, S2, S3, S4, S5, S6, S7, S8)


def _str_to_bt(s: str) -> list[int]:
    """Pack up to 4 UTF-16 code units into 64 bits, MSB first, zero-padded."""
    bt = [0] * 64
    n = len(s)
    if n < 4:
        for i in range(n):
            k = ord(s[i])
            for j in range(16):
                bt[16 * i + j] = (k >> (15 - j)) & 1
        # remaining slots stay 0, which is exactly what the original's zero-fill does
    else:
        for i in range(4):
            k = ord(s[i])
            for j in range(16):
                bt[16 * i + j] = (k >> (15 - j)) & 1
    return bt


def _get_key_bytes(key: str) -> list[list[int]]:
    bts = []
    it, rem = divmod(len(key), 4)
    for i in range(it):
        bts.append(_str_to_bt(key[i * 4 : i * 4 + 4]))
    if rem > 0:
        bts.append(_str_to_bt(key[it * 4 :]))
    return bts


def _bt64_to_hex(byte_data: list[int]) -> str:
    return "".join(f"{byte_data[i * 4] * 8 + byte_data[i * 4 + 1] * 4 + byte_data[i * 4 + 2] * 2 + byte_data[i * 4 + 3]:X}" for i in range(16))


def _init_permute(d: list[int]) -> list[int]:
    return [d[i] for i in IP]


def _expand_permute(right: list[int]) -> list[int]:
    return [right[i] for i in EP]


def _xor(a: list[int], b: list[int]) -> list[int]:
    return [x ^ y for x, y in zip(a, b)]


def _s_box_permute(expand_byte: list[int]) -> list[int]:
    out = [0] * 32
    for m in range(8):
        i = expand_byte[m * 6 + 0] * 2 + expand_byte[m * 6 + 5]
        j = (
            expand_byte[m * 6 + 1] * 8
            + expand_byte[m * 6 + 2] * 4
            + expand_byte[m * 6 + 3] * 2
            + expand_byte[m * 6 + 4]
        )
        binary = format(_SBOXES[m][i][j], "04b")
        for b in range(4):
            out[m * 4 + b] = int(binary[b])
    return out


def _p_permute(sbox_byte: list[int]) -> list[int]:
    return [sbox_byte[i] for i in P_BOX]


def _finally_permute(end_byte: list[int]) -> list[int]:
    return [end_byte[i] for i in FP]


def _generate_keys(key_byte: list[int]) -> list[list[int]]:
    key = [0] * 56
    for i in range(7):
        for j, k in zip(range(8), range(7, -1, -1)):
            key[i * 8 + j] = key_byte[8 * k + i]

    keys = []
    for i in range(16):
        for _ in range(KEY_SHIFT[i]):
            temp_left, temp_right = key[0], key[28]
            for k in range(27):
                key[k] = key[k + 1]
                key[28 + k] = key[29 + k]
            key[27] = temp_left
            key[55] = temp_right
        # PC-2, in the original's index order
        idx = [
            13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3,
            25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39,
            50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31,
        ]
        keys.append([key[t] for t in idx])
    return keys


def _des(data_byte: list[int], key_byte: list[int], decrypt: bool = False) -> list[int]:
    keys = _generate_keys(key_byte)
    ip_byte = _init_permute(data_byte)
    left, right = ip_byte[:32], ip_byte[32:]
    order = range(15, -1, -1) if decrypt else range(16)
    for i in order:
        temp_right = _xor(_p_permute(_s_box_permute(_xor(_expand_permute(right), keys[i]))), left)
        left, right = right, temp_right
    merged = right + left  # the original swaps halves before the final permutation
    return _finally_permute(merged)


def str_enc(data: str, first_key: str = "1", second_key: str = "2", third_key: str = "3") -> str:
    """Equivalent of the page's `strEnc(data, firstKey, secondKey, thirdKey)`."""
    if not data:
        return ""
    keys = [k for k in (first_key, second_key, third_key) if k]
    key_bt = [_get_key_bytes(k) for k in keys]

    out = []
    leng = len(data)
    blocks = [data[i * 4 : i * 4 + 4] for i in range(leng // 4)]
    if leng % 4:
        blocks.append(data[(leng // 4) * 4 :])

    for block in blocks:
        bt = _str_to_bt(block)
        for kb in key_bt:
            for k in kb:
                bt = _des(bt, k)
        out.append(_bt64_to_hex(bt))
    return "".join(out)

