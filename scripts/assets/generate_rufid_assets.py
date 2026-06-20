#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "artwork" / "source" / "rufid-usb-android.png"
BACKGROUND = (245, 246, 244, 255)
TRANSPARENT = (0, 0, 0, 0)

LAUNCHER_SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

ADAPTIVE_FOREGROUND_SIZES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}


def lanczos() -> int:
    return getattr(Image, "Resampling", Image).LANCZOS


def open_source() -> Image.Image:
    if not SOURCE.exists():
        raise SystemExit(f"Missing source image: {SOURCE}")
    return Image.open(SOURCE).convert("RGBA")


def contain(
    image: Image.Image,
    width: int,
    height: int,
    padding: float = 0.08,
    background: tuple[int, int, int, int] = BACKGROUND,
) -> Image.Image:
    canvas = Image.new("RGBA", (width, height), background)
    max_w = max(1, int(width * (1 - padding * 2)))
    max_h = max(1, int(height * (1 - padding * 2)))
    fitted = image.copy()
    fitted.thumbnail((max_w, max_h), lanczos())
    x = (width - fitted.width) // 2
    y = (height - fitted.height) // 2
    canvas.alpha_composite(fitted, (x, y))
    return canvas


def round_icon(image: Image.Image) -> Image.Image:
    result = image.copy()
    mask = Image.new("L", result.size, 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0, result.width - 1, result.height - 1), fill=255)
    result.putalpha(mask)
    return result


def save_png(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="PNG", optimize=True)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def generate() -> None:
    source = open_source()

    for density, size in LAUNCHER_SIZES.items():
        icon = contain(source, size, size, padding=0.16, background=TRANSPARENT)
        out_dir = ROOT / "app" / "src" / "main" / "res" / f"mipmap-{density}"
        save_png(icon, out_dir / "ic_launcher.png")
        save_png(round_icon(icon), out_dir / "ic_launcher_round.png")

    for density, size in ADAPTIVE_FOREGROUND_SIZES.items():
        foreground = contain(source, size, size, padding=0.18, background=TRANSPARENT)
        out_dir = ROOT / "app" / "src" / "main" / "res" / f"mipmap-{density}"
        save_png(foreground, out_dir / "ic_launcher_foreground.png")

    save_png(contain(source, 512, 512, padding=0.08), ROOT / "fastlane" / "metadata" / "android" / "en-US" / "images" / "icon.png")
    save_png(contain(source, 1024, 500, padding=0.05), ROOT / "fastlane" / "metadata" / "android" / "en-US" / "images" / "featureGraphic.png")
    save_png(contain(source, 256, 256, padding=0.08), ROOT / "artwork" / "generated" / "readme-logo.png")
    save_png(contain(source, 512, 512, padding=0.08), ROOT / "artwork" / "generated" / "rufid-icon-512.png")
    save_png(contain(source, 1280, 640, padding=0.12), ROOT / "artwork" / "generated" / "repo-social-preview.png")


def expected_assets() -> dict[Path, tuple[int, int]]:
    assets: dict[Path, tuple[int, int]] = {}
    for density, size in LAUNCHER_SIZES.items():
        base = ROOT / "app" / "src" / "main" / "res" / f"mipmap-{density}"
        assets[base / "ic_launcher.png"] = (size, size)
        assets[base / "ic_launcher_round.png"] = (size, size)
    for density, size in ADAPTIVE_FOREGROUND_SIZES.items():
        base = ROOT / "app" / "src" / "main" / "res" / f"mipmap-{density}"
        assets[base / "ic_launcher_foreground.png"] = (size, size)
    assets[ROOT / "fastlane" / "metadata" / "android" / "en-US" / "images" / "icon.png"] = (512, 512)
    assets[ROOT / "fastlane" / "metadata" / "android" / "en-US" / "images" / "featureGraphic.png"] = (1024, 500)
    assets[ROOT / "artwork" / "generated" / "readme-logo.png"] = (256, 256)
    assets[ROOT / "artwork" / "generated" / "rufid-icon-512.png"] = (512, 512)
    assets[ROOT / "artwork" / "generated" / "repo-social-preview.png"] = (1280, 640)
    return assets


def validate() -> list[dict[str, object]]:
    manifest = []
    for path, expected_size in expected_assets().items():
        if not path.exists():
            raise SystemExit(f"Missing generated asset: {path}")
        with Image.open(path) as image:
            if image.size != expected_size:
                raise SystemExit(f"{path} has size {image.size}, expected {expected_size}")
            rgba = image.convert("RGBA")
            extrema = rgba.getextrema()
            color_ranges = extrema[:3]
            if not any(low != high for low, high in color_ranges):
                raise SystemExit(f"{path} appears blank")
            alpha_min, alpha_max = extrema[3]
            manifest.append(
                {
                    "path": str(path.relative_to(ROOT)).replace("\\", "/"),
                    "width": image.width,
                    "height": image.height,
                    "mode": image.mode,
                    "alpha": [alpha_min, alpha_max],
                    "sha256": sha256(path),
                }
            )
    return manifest


def write_manifest(manifest: list[dict[str, object]]) -> None:
    out = ROOT / "artwork" / "generated" / "ASSET_MANIFEST.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate Rufid image assets from the checked-in source PNG.")
    parser.add_argument("--check-only", action="store_true", help="Validate existing generated assets without rewriting them.")
    args = parser.parse_args()
    if not args.check_only:
        generate()
    manifest = validate()
    if not args.check_only:
        write_manifest(manifest)
    print(f"Validated {len(manifest)} Rufid image assets.")


if __name__ == "__main__":
    main()
