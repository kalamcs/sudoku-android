# Copyright (c) 2026 Kalam Shah
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

"""Prepare reproducible puzzle templates and their SE-rated variations."""

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
import hashlib
import os
from pathlib import Path
import random
import re
import subprocess
from tempfile import TemporaryDirectory

from puzzle_equivalence import DistinctTemplates


RATER_SHA256 = "37831647bf1727be02c159f25aefd8602918185d79d4aee73472eff40cd6736c"
CLASSIC_TECHNIQUES = "1111000011010111011011011011000010100010111111"
BOARDS_PER_TEMPLATE = 4
MEDIUM_COUNT = 192


@dataclass(frozen=True)
class RatedBoard:
    givens: str
    score: int
    solution: str


def rating_tenths(text):
    whole, fraction = text.split(".")
    return int(whole) * 10 + int(fraction)


def rate(jar, boards):
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / "java") if java_home else "java"
    result = subprocess.run(
        [java, "-Xmx192m", "-XX:ActiveProcessorCount=1", "-cp", str(jar), "diuf.sudoku.test.serate",
         f"--techs={CLASSIC_TECHNIQUES}", "-U0", "-G0", "-t1",
         "--input=/dev/stdin", "--format=%n%t%g%t%r%t%i"],
        input="".join(f"{board}\n" for board in boards),
        capture_output=True,
        text=True,
        check=True,
    )
    lines = result.stdout.splitlines()
    if len(lines) != len(boards):
        raise ValueError(f"The SE rater returned {len(lines)} rows for {len(boards)} boards")
    ratings = []
    for ordinal, (line, board) in enumerate(zip(lines, boards), start=1):
        index, givens, score, solution = line.split("\t")
        if int(index) != ordinal or givens != board:
            raise ValueError(f"The SE rater changed input order at row {ordinal}")
        if re.fullmatch(r"[1-9]{81}", solution) and all(
            given == "0" or given == solved for given, solved in zip(givens, solution)
        ):
            ratings.append(RatedBoard(givens, rating_tenths(score), solution))
        else:
            ratings.append(None)
    return ratings


def variations(givens, rng):
    def shuffled_units():
        return [3 * group + offset
                for group in rng.sample(range(3), 3)
                for offset in rng.sample(range(3), 3)]

    boards = [givens]
    while len(boards) < BOARDS_PER_TEMPLATE + 1:
        rows, cols = shuffled_units(), shuffled_units()
        digits = "0" + "".join(rng.sample("123456789", 9))
        transposed = rng.choice((False, True))
        board = "".join(digits[int(givens[c * 9 + r if transposed else r * 9 + c])]
                        for r in rows for c in cols)
        if board not in boards:
            boards.append(board)
    return boards


def templates(paths):
    for path in paths:
        with path.open(encoding="ascii") as stream:
            for line in stream:
                template_id, givens, score = line.split()
                yield template_id, givens.replace(".", "0"), rating_tenths(score)


def prepare_medium(jar, path):
    candidates = list(dict.fromkeys(path.read_text(encoding="ascii").splitlines()))
    print("Rating the locally generated Medium templates and variations", flush=True)
    rng = random.Random("local-medium")
    accepted = 0
    for start in range(0, len(candidates), 96):
        bases = [row for row in rate(jar, candidates[start:start + 96]) if row and 26 <= row.score <= 44]
        if not bases:
            continue
        # The first board is the already rated original. Try four new boards and keep three.
        variants = [board for base in bases for board in variations(base.givens, rng)[1:]]
        ratings = rate(jar, variants)
        for index, base in enumerate(bases):
            offset = index * BOARDS_PER_TEMPLATE
            rated = ratings[offset:offset + BOARDS_PER_TEMPLATE]
            matches = [row for row in rated if row and 26 <= row.score <= 44]
            if len(matches) < BOARDS_PER_TEMPLATE - 1:
                continue
            accepted += 1
            for row in [base, *matches[:BOARDS_PER_TEMPLATE - 1]]:
                yield "medium", f"local-{accepted:04d}", row
            if accepted == MEDIUM_COUNT:
                return
    raise RuntimeError(f"Found {accepted}/{MEDIUM_COUNT} Medium puzzles")


def prepare_templates(jar, source_dir):
    source = list(templates(source_dir / f"{name}.txt" for name in ("hard", "diabolical")))
    buckets = [
        ("hard", 45, 54, 18), ("hard", 55, 64, 18), ("hard", 65, 70, 18),
        ("extreme", 90, 90, 11), ("extreme", 91, 91, 8),
        ("extreme", 92, 92, 4), ("extreme", 93, 93, 1),
    ]
    for tier, minimum, maximum, count in buckets:
        rng = random.Random(f"sudoku-exchange-{tier}-{minimum}")
        eligible = [(template_id, givens) for template_id, givens, score in source
                    if minimum <= score <= maximum]
        rng.shuffle(eligible)
        print(f"Rating {tier} source scores {minimum / 10:.1f}–{maximum / 10:.1f}", flush=True)
        accepted = 0
        # Work in batches so most higher-scoring templates need to be rated only once.
        for start in range(0, len(eligible), count):
            batch = [(template_id, variations(givens, rng))
                     for template_id, givens in eligible[start:start + count]]
            ratings = rate(jar, [board for _, boards in batch for board in boards])
            for index, (template_id, boards) in enumerate(batch):
                offset = index * len(boards)
                rated = ratings[offset:offset + len(boards)]
                low, high = (45, 70) if tier == "hard" else (90, 199)
                matches = [r for r in rated if r and low <= r.score <= high]
                if len(matches) < BOARDS_PER_TEMPLATE or matches[0] != rated[0]:
                    continue
                for row in matches[:BOARDS_PER_TEMPLATE]:
                    yield tier, template_id, row
                accepted += 1
                if accepted == count:
                    break
            if accepted == count:
                break
        if accepted != count:
            raise RuntimeError(f"Found {accepted}/{count} {tier} templates for {minimum}–{maximum}")


def prepared_rows(path):
    with path.open(encoding="ascii") as stream:
        for line in stream:
            if line.startswith("#"):
                continue
            tier, template_id, score, givens, solution = line.rstrip("\n").split("\t")
            if not re.fullmatch(r"[0-9]{81}", givens) or not re.fullmatch(r"[1-9]{81}", solution):
                raise ValueError(f"Invalid prepared board in {path}")
            yield tier, template_id, RatedBoard(givens, int(score), solution)


def rate_expansion(jar, tier, minimum, maximum, count, candidates):
    band = {"medium": range(26, 45), "hard": range(45, 86), "extreme": range(86, 200)}[tier]
    rng = random.Random(f"expansion-variations-{tier}-{minimum}")
    accepted = 0
    print(f"Starting {tier} candidates from {minimum / 10:.1f}–{maximum / 10:.1f}", flush=True)
    for start in range(0, len(candidates), 24):
        candidates_in_batch = candidates[start:start + 24]
        bases = rate(jar, [board for _, board in candidates_in_batch])
        batch = [(key, base, variations(board, rng)[1:])
                 for (key, board), base in zip(candidates_in_batch, bases)
                 if base and base.score in band]
        if not batch:
            continue
        ratings = iter(rate(jar, [board for _, _, boards in batch for board in boards]))
        for template_id, base, boards in batch:
            variants = [next(ratings) for _ in boards]
            matches = [row for row in variants if row and row.score in band]
            if len(matches) < BOARDS_PER_TEMPLATE - 1:
                continue
            for row in [base, *matches[:BOARDS_PER_TEMPLATE - 1]]:
                yield tier, template_id, row
            accepted += 1
            if accepted == count:
                print(f"Verified {count} {tier} templates from {minimum / 10:.1f}–{maximum / 10:.1f}", flush=True)
                return
    raise RuntimeError(f"Found {accepted}/{count} {tier} templates from {minimum}–{maximum}")


def write_rows(path, rows):
    with path.open("w", encoding="ascii", newline="\n") as stream:
        stream.write("# level\ttemplate ID\tSE rating in tenths\tgivens\tsolution\n")
        for tier, template_id, row in rows:
            stream.write(f"{tier}\t{template_id}\t{row.score}\t{row.givens}\t{row.solution}\n")


def expansion_bracket(jar, tier, minimum, maximum, count, candidates, checkpoints):
    cache = None
    if checkpoints:
        digest = hashlib.sha256(repr((RATER_SHA256, CLASSIC_TECHNIQUES, tier, minimum,
                                      maximum, count, candidates)).encode("ascii")).hexdigest()
        cache = checkpoints / f"{tier}-{minimum}-{digest}.tsv"
        try:
            cached = list(prepared_rows(cache))
        except (FileNotFoundError, ValueError):
            pass
        else:
            if len(cached) == count * BOARDS_PER_TEMPLATE:
                return cached
    records = list(rate_expansion(jar, tier, minimum, maximum, count, candidates))
    if cache:
        with TemporaryDirectory(dir=cache.parent, prefix=".checkpoint-") as directory:
            staged = Path(directory) / cache.name
            write_rows(staged, records)
            staged.replace(cache)
    return records


def expand(jar, source_dir, existing, jobs, checkpoints):
    seen = DistinctTemplates()
    ids = set()
    for _, template_id, board in existing:
        if template_id not in ids:
            if not seen.add(board.givens):
                raise ValueError(f"Equivalent base template {template_id}")
            ids.add(template_id)
    source = list(templates(source_dir / f"{kind}.txt" for kind in ("hard", "diabolical")))
    buckets = [
        ("medium", 26, 30, 84), ("medium", 31, 36, 72),
        ("medium", 37, 41, 72), ("medium", 42, 44, 72),
        ("hard", 45, 54, 60), ("hard", 55, 64, 60), ("hard", 65, 72, 60),
        ("hard", 73, 79, 60), ("hard", 80, 85, 60),
        ("extreme", 86, 87, 60), ("extreme", 88, 89, 80), ("extreme", 90, 90, 80),
        ("extreme", 91, 91, 65), ("extreme", 92, 92, 15),
    ]
    with ThreadPoolExecutor(max_workers=jobs) as workers:
        pending = []
        # Submit the slowest brackets first. Collect them in the documented catalog order.
        for tier, minimum, maximum, count in reversed(buckets):
            eligible = [(key, board) for key, board, score in source
                        if minimum <= score <= maximum and key not in ids]
            random.Random(f"expansion-sources-{tier}-{minimum}").shuffle(eligible)
            candidates = []
            # The source used a different rater configuration. Some Medium brackets need a wider shortlist.
            limit = count * 16 if tier == "medium" and minimum >= 37 else count + 36
            for key, board in eligible:
                if seen.add(board):
                    candidates.append((key, board))
                if len(candidates) >= limit:
                    break
            pending.append(workers.submit(
                expansion_bracket, jar, tier, minimum, maximum, count, candidates, checkpoints,
            ))
        for result in reversed(pending):
            yield from result.result()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path, help="Sukaku Explainer v1.18.1 JAR")
    parser.add_argument("source_dir", type=Path, help="Directory containing the pinned Sudoku Exchange files")
    parser.add_argument("medium_candidates", type=Path, help="Board list produced by MediumPuzzleCandidates.java")
    parser.add_argument("output", type=Path, help="Destination for the generated TSV")
    parser.add_argument("--expand", action="store_true", help="Add 300 distinct templates per tier")
    parser.add_argument("--existing", type=Path, help="Use the original prepared bank as the starting point")
    parser.add_argument("--jobs", type=int, default=2, help="Concurrent raters for the expansion")
    parser.add_argument("--checkpoints", type=Path, help="Directory for completed expansion brackets")
    args = parser.parse_args()
    if hashlib.sha256(args.jar.read_bytes()).hexdigest() != RATER_SHA256:
        parser.error("The rater JAR does not match the pinned v1.18.1 release")
    if args.checkpoints:
        args.checkpoints.mkdir(parents=True, exist_ok=True)
    if args.existing:
        rows = list(prepared_rows(args.existing))
    else:
        rows = list(prepare_medium(args.jar, args.medium_candidates))
        rows.extend(prepare_templates(args.jar, args.source_dir))
    if args.expand:
        rows.extend(list(expand(args.jar, args.source_dir, rows, args.jobs, args.checkpoints)))
    if len({row.givens for _, _, row in rows}) != len(rows):
        raise ValueError("The generated bank contains duplicate boards")
    write_rows(args.output, rows)
    print(f"Wrote {dict(Counter(tier for tier, _, _ in rows))}")


if __name__ == "__main__":
    main()
