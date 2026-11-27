# Copyright (c) 2026 Kalam Shah
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

"""Render Android launcher PNGs from the source SVGs with rsvg-convert."""

import argparse
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET


PROJECT_ROOT = Path(__file__).resolve().parents[1]
SVG_NS = "http://www.w3.org/2000/svg"
DENSITIES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}


def round_icon(full_svg: bytes, foreground_svg: bytes) -> bytes:
    """Use the legacy background gradient and size the board for a round badge."""
    ET.register_namespace("", SVG_NS)
    full = ET.fromstring(full_svg)
    foreground = ET.fromstring(foreground_svg)
    root = ET.Element(full.tag, full.attrib)
    defs = full.find(f"{{{SVG_NS}}}defs")
    root.append(defs)
    clip = ET.SubElement(defs, f"{{{SVG_NS}}}clipPath", id="round")
    ET.SubElement(clip, f"{{{SVG_NS}}}circle", cx="512", cy="512", r="512")

    badge = ET.SubElement(root, f"{{{SVG_NS}}}g", {"clip-path": "url(#round)"})
    ET.SubElement(badge, f"{{{SVG_NS}}}rect", width="1024", height="1024", fill="url(#bg)")
    board = foreground.find(f"{{{SVG_NS}}}g")
    board.set("transform", "translate(512 512) scale(0.9) translate(-512 -512)")
    badge.append(board)
    return ET.tostring(root)


def render(svg: bytes, size: int, output: Path) -> None:
    subprocess.run(
        ["rsvg-convert", "--width", str(size), "--height", str(size), "--output", str(output)],
        input=svg,
        check=True,
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-root", type=Path, default=PROJECT_ROOT,
                        help="Write the assets and Android resource tree under this directory")
    args = parser.parse_args()
    if shutil.which("rsvg-convert") is None:
        parser.exit(1, "rsvg-convert is required (install librsvg2-bin on Ubuntu/WSL).\n")

    full_svg = (PROJECT_ROOT / "assets/icon-full.svg").read_bytes()
    foreground_svg = (PROJECT_ROOT / "assets/icon-foreground.svg").read_bytes()
    round_svg = round_icon(full_svg, foreground_svg)
    for density, (legacy_size, foreground_size) in DENSITIES.items():
        directory = args.output_root / f"android/app/src/main/res/mipmap-{density}"
        directory.mkdir(parents=True, exist_ok=True)
        render(full_svg, legacy_size, directory / "ic_launcher.png")
        render(round_svg, legacy_size, directory / "ic_launcher_round.png")
        render(foreground_svg, foreground_size, directory / "ic_launcher_foreground.png")
        print(f"mipmap-{density}: {legacy_size}px legacy, {foreground_size}px foreground")

    assets = args.output_root / "assets"
    assets.mkdir(parents=True, exist_ok=True)
    render(full_svg, 1024, assets / "icon.png")
    print("assets/icon.png: 1024px")


if __name__ == "__main__":
    main()
