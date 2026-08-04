"""Generate and verify Android Manuscript vectors from V2 canonical assets."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path


ANDROID_ROOT = Path(__file__).resolve().parents[1]
CANONICAL_ROOT = ANDROID_ROOT / "brand" / "canonical"
CONTRACT_PATH = ANDROID_ROOT / "docs" / "product-contract" / "manuscript-android-v2.json"
DRAWABLE_ROOT = ANDROID_ROOT / "app" / "src" / "main" / "res" / "drawable"
PROVENANCE_PATH = ANDROID_ROOT / "evidence" / "brand" / "generated-brand-assets.json"

PATHS = """    <path android:fillColor="#17120D" android:pathData="M220,0H804A220,220 0,0 1,1024,220V804A220,220 0,0 1,804,1024H220A220,220 0,0 1,0,804V220A220,220 0,0 1,220,0Z" />
    <path android:fillColor="@android:color/transparent" android:strokeColor="#D6A85F" android:strokeWidth="58" android:pathData="M242,54H782A188,188 0,0 1,970,242V782A188,188 0,0 1,782,970H242A188,188 0,0 1,54,782V242A188,188 0,0 1,242,54Z" />
    <path android:fillColor="#F2D6A2" android:strokeColor="#8B5B2B" android:strokeWidth="34" android:strokeLineJoin="round" android:pathData="M314,194H710C768,194 814,240 814,298C814,340 788,379 748,395V732C748,784 706,826 654,826H314C262,826 220,784 220,732V288C220,236 262,194 314,194Z" />
    <path android:fillColor="#E2B86F" android:pathData="M314,194H710C660,210 636,249 636,300H270C270,242 284,209 314,194Z" />
    <path android:fillColor="#E2B86F" android:pathData="M654,826H314C364,810 388,771 388,720H754C754,778 720,811 654,826Z" />
    <path android:fillColor="@android:color/transparent" android:strokeColor="#3B291C" android:strokeWidth="42" android:strokeLineCap="round" android:pathData="M338,404H624M338,506H586M338,608H526" />
    <path android:fillColor="#D6A85F" android:strokeColor="#3B291C" android:strokeWidth="26" android:strokeLineJoin="round" android:pathData="M590,710L770,453L828,434L824,495L644,752L552,799Z" />
    <path android:fillColor="@android:color/transparent" android:strokeColor="#F7E7C6" android:strokeWidth="24" android:strokeLineCap="round" android:pathData="M748,484L797,518" />"""


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def contract() -> dict:
    return json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))


def validate_canonical_assets(data: dict) -> tuple[str, str]:
    svg = CANONICAL_ROOT / "manuscript.svg"
    png = CANONICAL_ROOT / "manuscript.png"
    svg_hash = sha256(svg)
    png_hash = sha256(png)
    brand = data["brand"]
    if svg_hash != brand["canonicalSvgSha256"]:
        raise SystemExit(f"Canonical SVG hash changed: {svg_hash}")
    if png_hash != brand["canonicalPngSha256"]:
        raise SystemExit(f"Canonical PNG hash changed: {png_hash}")
    svg_colors = {
        color.upper()
        for color in re.findall(r"#[0-9A-Fa-f]{6}", svg.read_text(encoding="utf-8"))
    }
    allowed = {color.upper() for color in brand["palette"]}
    unexpected = svg_colors - allowed
    if unexpected:
        raise SystemExit(f"Canonical SVG contains unlocked colors: {sorted(unexpected)}")
    if 'viewBox="0 0 1024 1024"' not in svg.read_text(encoding="utf-8"):
        raise SystemExit("Canonical SVG must keep its 1024 by 1024 view box")
    return svg_hash, png_hash


def vector(width_dp: int, master_hash: str, body: str) -> str:
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Generated from canonical V2 manuscript.svg SHA256 {master_hash}. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{width_dp}dp"
    android:height="{width_dp}dp"
    android:viewportWidth="1024"
    android:viewportHeight="1024">
{body}
</vector>
"""


def expected_outputs(data: dict, svg_hash: str) -> dict[Path, str]:
    # The whole framed artwork must survive every launcher mask. The Android
    # guarantee is a 66dp-of-108dp safe circle (61.1%); the art's farthest
    # rounded corner sits at 61.8% of its half-diagonal, so scale 0.49 keeps
    # the complete gold frame visible on every OEM, with the matching
    # near-black background filling the rest of the mask.
    scale = data["brand"]["adaptiveForegroundScale"]
    translate = 512 * (1 - scale)
    launcher_body = f"""    <group
        android:scaleX="{scale:.2f}"
        android:scaleY="{scale:.2f}"
        android:translateX="{translate:.2f}"
        android:translateY="{translate:.2f}">
{PATHS}
    </group>"""
    return {
        DRAWABLE_ROOT / "ic_manuscript.xml": vector(108, svg_hash, PATHS),
        DRAWABLE_ROOT / "ic_manuscript_assist.xml": vector(36, svg_hash, PATHS),
        DRAWABLE_ROOT / "ic_manuscript_launcher_foreground.xml": vector(
            108,
            svg_hash,
            launcher_body,
        ),
    }


def provenance(data: dict, svg_hash: str, png_hash: str, outputs: dict[Path, str]) -> str:
    record = {
        "contractVersion": data["contractVersion"],
        "generator": "tools/generate_android_brand_assets.py",
        "canonicalSvgSha256": svg_hash,
        "canonicalPngSha256": png_hash,
        "adaptiveForegroundScale": data["brand"]["adaptiveForegroundScale"],
        "outputs": {
            path.relative_to(ANDROID_ROOT).as_posix(): hashlib.sha256(
                text.encode("utf-8")
            ).hexdigest().upper()
            for path, text in sorted(outputs.items(), key=lambda item: str(item[0]))
        },
    }
    return json.dumps(record, indent=2, sort_keys=True) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--write",
        action="store_true",
        help="Write deterministic generated vectors and provenance.",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Verify generated vectors and provenance without writing.",
    )
    args = parser.parse_args()
    if args.write == args.check:
        parser.error("Choose exactly one of --write or --check")

    data = contract()
    svg_hash, png_hash = validate_canonical_assets(data)
    outputs = expected_outputs(data, svg_hash)
    outputs[PROVENANCE_PATH] = provenance(data, svg_hash, png_hash, outputs)

    if args.write:
        for path, text in outputs.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8", newline="\n")
        return 0

    mismatches = []
    for path, expected in outputs.items():
        actual = path.read_text(encoding="utf-8") if path.is_file() else None
        if actual != expected:
            mismatches.append(path.relative_to(ANDROID_ROOT).as_posix())
    if mismatches:
        raise SystemExit("Generated brand files are stale: " + ", ".join(mismatches))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
