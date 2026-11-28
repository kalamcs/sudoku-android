# Copyright (c) 2026 Kalam Shah
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

"""Find equivalent Sudoku grids under box-preserving permutations and digit relabeling."""

from collections import defaultdict
from itertools import permutations, product


GROUP_ORDERS = tuple(permutations(range(3)))


def rows(board):
    return tuple(board[i:i + 9] for i in range(0, 81, 9))


def transpose(grid):
    return tuple("".join(column) for column in zip(*grid))


def axis_profile(grid):
    return tuple(tuple(sorted(3 - row[i:i + 3].count("0") for i in (0, 3, 6))) for row in grid)


def grouped_profile(profile):
    return tuple(sorted(tuple(sorted(profile[i:i + 3])) for i in (0, 3, 6)))


def fingerprint(board):
    grid = rows(board)
    axes = tuple(sorted(grouped_profile(axis_profile(axis)) for axis in (grid, transpose(grid))))
    counts = tuple(sorted(board.count(digit) for digit in "123456789"))
    return axes, counts


def matching_axis_orders(source, target):
    for groups in GROUP_ORDERS:
        compatible_groups = []
        for target_group, source_group in enumerate(groups):
            target_start, source_start = target_group * 3, source_group * 3
            target_profile = target[target_start:target_start + 3]
            choices = []
            for offsets in GROUP_ORDERS:
                order = tuple(source_start + offset for offset in offsets)
                if tuple(source[i] for i in order) == target_profile:
                    choices.append(order)
            if not choices:
                break
            compatible_groups.append(choices)
        if len(compatible_groups) != 3:
            continue
        for groups_in_order in product(*compatible_groups):
            yield tuple(index for group in groups_in_order for index in group)


def same_digits(source, target, row_order, column_order):
    labels = {"0": "0"}
    used_labels = {"0"}
    for source_row, target_row in zip(row_order, target):
        for source_column, target_digit in zip(column_order, target_row):
            source_digit = source[source_row][source_column]
            known_label = labels.get(source_digit)
            if known_label is not None:
                if known_label != target_digit:
                    return False
            elif target_digit in used_labels:
                return False
            else:
                labels[source_digit] = target_digit
                used_labels.add(target_digit)
    return True


def transformation(first, second):
    target = rows(second)
    target_rows, target_columns = axis_profile(target), axis_profile(transpose(target))
    original = rows(first)
    for transposed, source in ((False, original), (True, transpose(original))):
        columns = tuple(matching_axis_orders(axis_profile(transpose(source)), target_columns))
        if not columns:
            continue
        for row_order in matching_axis_orders(axis_profile(source), target_rows):
            for order in columns:
                if same_digits(source, target, row_order, order):
                    return row_order, order, transposed
    return None


def equivalent(first, second):
    return transformation(first, second) is not None


class DistinctTemplates:
    def __init__(self):
        self._groups = defaultdict(list)

    def add(self, board):
        candidates = self._groups[fingerprint(board)]
        # A fingerprint match narrows the search. It does not prove equivalence.
        if any(equivalent(other, board) for other in candidates):
            return False
        candidates.append(board)
        return True
