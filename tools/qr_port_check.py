"""Line-by-line mirror of the Kotlin QrEncoder, checked against a reference encoder.

This exists purely to validate the algorithm before trusting the Kotlin: same tables, same
control flow, same mask selection. Any divergence from `qrcode` is a bug in my port.
"""
from __future__ import annotations

ECC_CODEWORDS_PER_BLOCK = [
    [-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30],
    [-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28],
    [-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30],
    [-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30],
]
NUM_EC_BLOCKS = [
    [-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25],
    [-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49],
    [-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68],
    [-1, 1, 1, 2, 4, 4, 4, 5, 5, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81],
]
ALIGN = [
    [], [6, 18], [6, 22], [6, 26], [6, 30], [6, 34], [6, 22, 38], [6, 24, 42], [6, 26, 46],
    [6, 28, 50], [6, 30, 54], [6, 32, 58], [6, 34, 62], [6, 26, 46, 66], [6, 26, 48, 70],
    [6, 26, 50, 74], [6, 30, 54, 78], [6, 30, 56, 82], [6, 30, 58, 86], [6, 34, 62, 90],
    [6, 28, 50, 72, 94], [6, 26, 50, 74, 98], [6, 30, 54, 78, 102], [6, 28, 54, 80, 106],
    [6, 32, 58, 84, 110], [6, 30, 58, 86, 114], [6, 34, 62, 90, 118], [6, 26, 50, 74, 98, 122],
    [6, 30, 54, 78, 102, 126], [6, 26, 52, 78, 104, 130], [6, 30, 56, 82, 108, 134],
    [6, 34, 60, 86, 112, 138], [6, 30, 58, 86, 114, 142], [6, 34, 62, 90, 118, 146],
    [6, 30, 54, 78, 102, 126, 150], [6, 24, 50, 76, 102, 128, 154], [6, 28, 54, 80, 106, 132, 158],
    [6, 32, 58, 84, 110, 136, 162], [6, 26, 54, 82, 110, 138, 166], [6, 30, 58, 86, 114, 142, 170],
]

ECC_FORMAT_BITS = {"L": 1, "M": 0, "Q": 3, "H": 2}
ORD = {"L": 0, "M": 1, "Q": 2, "H": 3}


def num_raw_data_modules(version: int) -> int:
    result = (16 * version + 128) * version + 64
    if version >= 2:
        num_align = version // 7 + 2
        result -= (25 * num_align - 10) * num_align - 55
        if version >= 7:
            result -= 36
    return result


def num_data_codewords(version: int, ecc: str) -> int:
    return num_raw_data_modules(version) // 8 - ECC_CODEWORDS_PER_BLOCK[ORD[ecc]][version] * NUM_EC_BLOCKS[ORD[ecc]][version]


def char_count_bits(version: int) -> int:
    return 8 if version <= 9 else 16


def choose_version(data_len: int, ecc: str) -> int:
    for v in range(1, 41):
        if 4 + char_count_bits(v) + data_len * 8 <= num_data_codewords(v, ecc) * 8:
            return v
    raise ValueError("too long")


def multiply(x: int, y: int) -> int:
    z = 0
    a, b = x, y
    for i in range(7, -1, -1):
        z = (z << 1) ^ ((z >> 7) * 0x11D)
        z ^= ((b >> i) & 1) * a
    return z & 0xFF


def rs_divisor(degree: int):
    result = [0] * degree
    result[degree - 1] = 1
    root = 1
    for _ in range(degree):
        for j in range(len(result)):
            result[j] = multiply(result[j], root)
            if j + 1 < len(result):
                result[j] ^= result[j + 1]
        root = multiply(root, 0x02)
    return result


def rs_remainder(data, divisor):
    result = [0] * len(divisor)
    for b in data:
        factor = b ^ result[0]
        result = result[1:] + [0]
        for i in range(len(divisor)):
            result[i] ^= multiply(divisor[i], factor)
    return result


class BitList:
    def __init__(self):
        self.bits = []

    def append(self, value, length):
        for i in range(length - 1, -1, -1):
            self.bits.append((value >> i) & 1)

    @property
    def size(self):
        return len(self.bits)

    def to_bytes(self):
        out = bytearray()
        for i in range(0, len(self.bits) - len(self.bits) % 8, 8):
            byte = 0
            for j in range(8):
                byte = (byte << 1) | self.bits[i + j]
            out.append(byte)
        return bytes(out)


def build_codewords(data: bytes, version: int, ecc: str) -> bytes:
    bits = BitList()
    bits.append(0b0100, 4)
    bits.append(len(data), char_count_bits(version))
    for b in data:
        bits.append(b, 8)

    capacity_bits = num_data_codewords(version, ecc) * 8
    bits.append(0, min(4, capacity_bits - bits.size))
    bits.append(0, (8 - bits.size % 8) % 8)
    pad = 0xEC
    while bits.size < capacity_bits:
        bits.append(pad, 8)
        pad = 0x11 if pad == 0xEC else 0xEC

    data_codewords = bits.to_bytes()
    num_blocks = NUM_EC_BLOCKS[ORD[ecc]][version]
    block_ecc_len = ECC_CODEWORDS_PER_BLOCK[ORD[ecc]][version]
    raw_codewords = num_raw_data_modules(version) // 8
    num_short = num_blocks - raw_codewords % num_blocks
    short_len = raw_codewords // num_blocks

    divisor = rs_divisor(block_ecc_len)
    blocks = []
    offset = 0
    for i in range(num_blocks):
        data_len = short_len - block_ecc_len + (0 if i < num_short else 1)
        dat = data_codewords[offset:offset + data_len]
        offset += data_len
        blocks.append(bytes(dat) + bytes(rs_remainder(dat, divisor)))

    result = bytearray(raw_codewords)
    k = 0
    max_data_len = short_len - block_ecc_len + 1
    for i in range(max_data_len):
        for block in blocks:
            data_len = len(block) - block_ecc_len
            if i < data_len:
                result[k] = block[i]
                k += 1
    for i in range(block_ecc_len):
        for block in blocks:
            result[k] = block[len(block) - block_ecc_len + i]
            k += 1
    return bytes(result)


def build_base(version: int):
    """Everything except data placement, masking and format/version info."""
    size = version * 4 + 17
    modules = [[False] * size for _ in range(size)]
    is_function = [[False] * size for _ in range(size)]

    def setf(x, y, dark):
        modules[y][x] = dark
        is_function[y][x] = True

    for i in range(size):
        setf(6, i, i % 2 == 0)
        setf(i, 6, i % 2 == 0)

    for corner in range(3):
        cx = 3 if corner != 1 else size - 4
        cy = 3 if corner != 2 else size - 4
        for dy in range(-4, 5):
            for dx in range(-4, 5):
                x, y = cx + dx, cy + dy
                if 0 <= x < size and 0 <= y < size:
                    dist = max(abs(dx), abs(dy))
                    setf(x, y, dist != 2 and dist != 4)

    positions = ALIGN[version - 1]
    for i in range(len(positions)):
        for j in range(len(positions)):
            if (i == 0 and j == 0) or (i == 0 and j == len(positions) - 1) or (i == len(positions) - 1 and j == 0):
                continue
            cx, cy = positions[j], positions[i]
            for dy in range(-2, 3):
                for dx in range(-2, 3):
                    setf(cx + dx, cy + dy, max(abs(dx), abs(dy)) != 1)

    for i in range(9):
        if i != 6:
            setf(8, i, False)
            setf(i, 8, False)
    for i in range(8):
        setf(size - 1 - i, 8, False)
        setf(8, size - 1 - i, False)
    setf(8, size - 8, True)
    if version >= 7:
        for i in range(18):
            a = size - 11 + i % 3
            b = i // 3
            setf(a, b, False)
            setf(b, a, False)
    return modules, is_function


def draw_data(modules, is_function, codewords, size):
    i = 0
    right = size - 1
    while right >= 1:
        if right == 6:
            right = 5
        for vert in range(size):
            for j in range(2):
                x = right - j
                upward = ((right + 1) & 2) == 0
                y = size - 1 - vert if upward else vert
                if not is_function[y][x] and i < len(codewords) * 8:
                    modules[y][x] = ((codewords[i >> 3] >> (7 - (i & 7))) & 1) != 0
                    i += 1
        right -= 2


def apply_mask(modules, is_function, mask, size):
    for y in range(size):
        for x in range(size):
            if is_function[y][x]:
                continue
            if mask == 0:
                inv = (x + y) % 2 == 0
            elif mask == 1:
                inv = y % 2 == 0
            elif mask == 2:
                inv = x % 3 == 0
            elif mask == 3:
                inv = (x + y) % 3 == 0
            elif mask == 4:
                inv = (x // 3 + y // 2) % 2 == 0
            elif mask == 5:
                inv = x * y % 2 + x * y % 3 == 0
            elif mask == 6:
                inv = (x * y % 2 + x * y % 3) % 2 == 0
            else:
                inv = ((x + y) % 2 + x * y % 3) % 2 == 0
            if inv:
                modules[y][x] = not modules[y][x]


def draw_format(modules, ecc, mask, size):
    data = (ECC_FORMAT_BITS[ecc] << 3) | mask
    rem = data
    for _ in range(10):
        rem = (rem << 1) ^ ((rem >> 9) * 0x537)
    bits = ((data << 10) | rem) ^ 0x5412

    def bit(i):
        return (bits >> i) & 1

    # Primary copy, split across the top-left finder.
    # Vertical: bits 0..5 down column 8 from row 0, then bit 6 at row 7 and bit 7 at row 8.
    for i in range(6):
        modules[i][8] = bool(bit(i))
    modules[7][8] = bool(bit(6))
    modules[8][8] = bool(bit(7))
    # Horizontal: bit 8 at column 7, then bits 9..14 at columns 5,4,3,2,1,0.
    modules[8][7] = bool(bit(8))
    for i in range(9, 15):
        modules[8][14 - i] = bool(bit(i))

    # Second copy: bits 0..7 along row 8 from the right edge inwards.
    for i in range(8):
        modules[8][size - 1 - i] = bool(bit(i))
    # Second copy: bits 8..14 up column 8 starting at row size-7.
    for i in range(8, 15):
        modules[size - 15 + i][8] = bool(bit(i))

    # Fixed dark module
    modules[size - 8][8] = True


def draw_version(modules, version, size):
    rem = version
    for _ in range(12):
        rem = (rem << 1) ^ ((rem >> 11) * 0x1F25)
    bits = (version << 12) | rem
    for i2 in range(18):
        b = ((bits >> i2) & 1) != 0
        a = size - 11 + i2 % 3
        c = i2 // 3
        modules[c][a] = b
        modules[a][c] = b


def penalty_of(modules, size):
    container = [0] * (size + 1)
    for y in range(size):
        prev = modules[y][0]
        length = 0
        for x in range(size):
            if modules[y][x] == prev:
                length += 1
            else:
                if length >= 5:
                    container[length] += 1
                length = 1
                prev = modules[y][x]
        if length >= 5:
            container[length] += 1
    for x in range(size):
        prev = modules[0][x]
        length = 0
        for y in range(size):
            if modules[y][x] == prev:
                length += 1
            else:
                if length >= 5:
                    container[length] += 1
                length = 1
                prev = modules[y][x]
        if length >= 5:
            container[length] += 1
    result = sum(container[n] * (n - 2) for n in range(5, size + 1))

    for y in range(size - 1):
        for x in range(size - 1):
            c = modules[y][x]
            if c == modules[y][x + 1] == modules[y + 1][x] == modules[y + 1][x + 1]:
                result += 3

    p1 = [True, False, True, True, True, False, True, False, False, False, False]
    p2 = [False, False, False, False, True, False, True, True, True, False, True]

    def matches(x, y, dx, dy, pat):
        for i2 in range(len(pat)):
            if modules[y + dy * i2][x + dx * i2] != pat[i2]:
                return False
        return True

    for y in range(size):
        for x in range(size - 10):
            if matches(x, y, 1, 0, p1) or matches(x, y, 1, 0, p2):
                result += 40
    for x in range(size):
        for y in range(size - 10):
            if matches(x, y, 0, 1, p1) or matches(x, y, 0, 1, p2):
                result += 40

    dark = sum(1 for yy in range(size) for xx in range(size) if modules[yy][xx])
    total = size * size
    result += abs(dark * 20 - total * 10) // total * 10
    return result


def build_matrix(version: int, ecc: str, codewords: bytes):
    size = version * 4 + 17

    # Mask selection follows the reference implementation's convention: the draft used for
    # scoring has *no* format/version bits, which leaves those positions in the "not yet
    # set" state the penalty function sees there.
    best_mask, best_penalty = 0, None
    for mask in range(8):
        modules, is_function = build_base(version)
        draw_data(modules, is_function, codewords, size)
        apply_mask(modules, is_function, mask, size)
        p = penalty_of(modules, size)
        if best_penalty is None or p < best_penalty:
            best_penalty, best_mask = p, mask

    modules, is_function = build_base(version)
    draw_data(modules, is_function, codewords, size)
    apply_mask(modules, is_function, best_mask, size)
    draw_format(modules, ecc, best_mask, size)
    if version >= 7:
        draw_version(modules, version, size)
    return modules, best_mask


def encode(text: str, ecc: str = "L"):
    data = text.encode("utf-8")
    version = choose_version(len(data), ecc)
    return build_matrix(version, ecc, build_codewords(data, version, ecc))
