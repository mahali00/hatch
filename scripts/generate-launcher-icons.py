#!/usr/bin/env python3
"""Requires Pillow: python3 -m pip install Pillow
"""

from pathlib import Path
from math import hypot

from PIL import Image, ImageDraw, ImageFilter


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "design/assets/hatch"
RES = ROOT / "app/src/main/res"
DESIGN_RES = ROOT / "design/src/main/res"
BACKGROUND = "#1B1E1D"
LAUNCHER_ART_HEIGHT_DP = 58
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def main():
    master = Image.open(ASSETS / "foreground.png").convert("RGBA")
    bounds = master.getchannel("A").point(lambda value: 255 if value > 16 else 0).getbbox()
    if bounds is None:
        raise ValueError("The foreground has no visible artwork")
    art = master.crop(bounds)
    closed = Image.open(ASSETS / "eyes-closed.png").convert("RGBA").resize(master.size, Image.Resampling.LANCZOS)
    blink = Image.new("RGBA", master.size)
    # Only the generated eyelids are used, so the original face, body and shell
    # stay pixel-identical throughout the blink.
    for box in [(414, 425, 546, 572), (709, 425, 839, 572)]:
        patch = closed.crop(box)
        feather = Image.new("L", patch.size)
        ImageDraw.Draw(feather).rectangle((14, 14, patch.width - 15, patch.height - 15), fill=255)
        patch.putalpha(feather.filter(ImageFilter.GaussianBlur(5)))
        blink.alpha_composite(patch, box[:2])
    blink = blink.crop(bounds)
    alpha = art.getchannel("A")
    weights = list(alpha.getdata())
    mass = sum(weights)
    center_x = sum((index % art.width + 0.5) * weight for index, weight in enumerate(weights)) / mass
    center_y = sum((index // art.width + 0.5) * weight for index, weight in enumerate(weights)) / mass

    def foreground(size):
        # 58dp in the launcher's 72dp viewport leaves breathing room around the tall mascot.
        scale = (LAUNCHER_ART_HEIGHT_DP / 108) * size / art.height
        resized = art.resize(
            (round(art.width * scale), round(art.height * scale)), Image.Resampling.LANCZOS
        )
        layer = Image.new("RGBA", (size, size))
        position = (
            round(size / 2 - center_x * resized.width / art.width),
            round(size / 2 - center_y * resized.height / art.height),
        )
        layer.alpha_composite(resized, position)
        return layer

    def mascot(size, pixels=art):
        scale = size * 0.89 / max(art.size)
        resized = pixels.resize(
            (round(art.width * scale), round(art.height * scale)), Image.Resampling.LANCZOS
        )
        layer = Image.new("RGBA", (size, size))
        layer.alpha_composite(resized, ((size - resized.width) // 2, (size - resized.height) // 2))
        return layer

    for density, size in DENSITIES.items():
        directory = RES / f"drawable-{density}"
        directory.mkdir(exist_ok=True)
        layer = foreground(size)
        layer.save(directory / "ic_launcher_foreground.png", optimize=True)
        visible = layer.getchannel("A")
        furthest = max(
            hypot(x + 0.5 - size / 2, y + 0.5 - size / 2)
            for y in range(size)
            for x in range(size)
            if visible.getpixel((x, y)) > 16
        )
        if furthest > size * 33 / 108:
            raise ValueError(f"{density} artwork exceeds Android's safe circle")
        weights = list(visible.getdata())
        mass = sum(weights)
        centroid = (
            sum((index % size + 0.5) * weight for index, weight in enumerate(weights)) / mass,
            sum((index // size + 0.5) * weight for index, weight in enumerate(weights)) / mass,
        )
        if any(abs(value - size / 2) > 0.6 for value in centroid):
            raise ValueError(f"{density} artwork is not optically centered")
        print(
            f"{density}: {size}x{size}, safe-circle radius {furthest * 108 / size:.1f}dp, "
            f"visual center ({centroid[0]:.1f}, {centroid[1]:.1f})"
        )

    # A launcher displays the central 72dp viewport of the 108dp layer.
    icon = Image.new("RGBA", (1024, 1024), BACKGROUND)
    icon.alpha_composite(foreground(1536).crop((256, 256, 1280, 1280)))
    icon.convert("RGB").save(ASSETS / "icon.png", optimize=True)

    for density, size in DENSITIES.items():
        scale = size / 108
        directory = DESIGN_RES / f"drawable-{density}"
        directory.mkdir(exist_ok=True)
        icon.resize((round(108 * scale),) * 2, Image.Resampling.LANCZOS).save(
            directory / "hatch_icon.png", optimize=True
        )
        mark = mascot(round(144 * scale))
        mark.save(directory / "hatch_mascot.png", optimize=True)
        mascot(round(144 * scale), blink).save(directory / "hatch_blink.png", optimize=True)


if __name__ == "__main__":
    main()
