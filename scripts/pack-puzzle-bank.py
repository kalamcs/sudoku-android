# Copyright (c) 2026 Kalam Shah
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

"""Convert verified TSV template groups into the Android seekable asset format."""

import argparse
from collections import Counter
from itertools import groupby
from pathlib import Path

from puzzle_equivalence import DistinctTemplates, GROUP_ORDERS, transformation


LEVELS = {"medium": range(26, 45), "hard": range(45, 86), "extreme": range(86, 200)}
DIGITS = "123456789"


def axis_index(order):
    index = GROUP_ORDERS.index(tuple(order[start] // 3 for start in (0, 3, 6)))
    for start in (0, 3, 6):
        index = index * 6 + GROUP_ORDERS.index(tuple(cell % 3 for cell in order[start:start + 3]))
    return index


def digit_index(labels):
    remaining = list(DIGITS)
    index = 0
    for digit in DIGITS:
        position = remaining.index(labels[digit])
        index = index * len(remaining) + position
        remaining.pop(position)
    return index


def encode_variant(givens, solution, variant, solved_variant):
    order = transformation(givens, variant)
    if order is None:
        raise ValueError("A variant is outside its template's equivalence class")
    rows, columns, transposed = order
    cells = [c * 9 + r if transposed else r * 9 + c for r in rows for c in columns]
    reordered = "".join(solution[i] for i in cells)
    labels = dict(zip(reordered, solved_variant))
    labels["0"] = "0"
    if ("".join(labels[digit] for digit in reordered) != solved_variant or
            "".join(labels[givens[i]] for i in cells) != variant):
        raise ValueError("The reconstructed variation differs from the rated board")
    code = axis_index(rows) | axis_index(columns) << 11 | int(transposed) << 22 | digit_index(labels) << 23
    return code.to_bytes(6, "little")


def read_rows(path):
    with path.open(encoding="ascii") as source:
        for line in source:
            if not line.startswith("#"):
                yield line.rstrip("\n").split("\t")


def pack(source, output):
    levels = {level: bytearray() for level in LEVELS}
    seen = DistinctTemplates()
    counts = Counter()
    for (level, template_id), group in groupby(read_rows(source), key=lambda row: tuple(row[:2])):
        original, *variants = list(group)
        _, _, base_rating, givens, solution = original
        if len(variants) != 3 or not seen.add(givens):
            raise ValueError(f"Invalid or repeated template {template_id}")
        if any(int(row[2]) not in LEVELS[level] for row in [original, *variants]):
            raise ValueError(f"{template_id} has a board outside {level}")
        record = bytearray([int(base_rating)])
        record.extend(bytes.fromhex(givens + solution))
        for _, _, rating, variant, solved in variants:
            record.append(int(rating))
            record.extend(encode_variant(givens, solution, variant, solved))
        if len(record) != 103:
            raise ValueError(f"{template_id} has an invalid record length")
        levels[level].extend(record)
        counts[level] += 1
    for level, catalog in levels.items():
        if not catalog:
            raise ValueError(f"No templates for {level}")
    output.mkdir(parents=True, exist_ok=True)
    for level, catalog in levels.items():
        (output / f"{level}.sudoku").write_bytes(catalog)
    print(f"Packed templates {dict(counts)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    pack(args.source, args.output)
