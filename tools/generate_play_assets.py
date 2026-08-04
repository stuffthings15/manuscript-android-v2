from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "play" / "assets"
SCALE = 3

BACKGROUND = "#17120D"
SURFACE = "#211A12"
SURFACE_RAISED = "#2E2418"
GOLD = "#E7B85C"
GOLD_DARK = "#6B4D1E"
PARCHMENT = "#F2D6A2"
TEXT = "#FFF4DE"
MUTED = "#D5C3A2"
BORDER = "#A08A68"
INK = "#3B291C"


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    name = "seguisb.ttf" if bold else "segoeui.ttf"
    return ImageFont.truetype(str(Path("C:/Windows/Fonts") / name), size * SCALE)


def rounded(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int], radius: int, fill: str, outline: str | None = None, width: int = 1) -> None:
    scaled = tuple(value * SCALE for value in box)
    draw.rounded_rectangle(scaled, radius=radius * SCALE, fill=fill, outline=outline, width=width * SCALE)


def draw_mark(image: Image.Image, box: tuple[int, int, int, int]) -> None:
    draw = ImageDraw.Draw(image)
    left, top, right, bottom = (value * SCALE for value in box)
    width = right - left
    height = bottom - top
    radius = int(min(width, height) * 0.18)
    draw.rounded_rectangle((left, top, right, bottom), radius=radius, fill=BACKGROUND, outline=GOLD, width=max(4, int(width * 0.055)))

    page_left = left + int(width * 0.20)
    page_top = top + int(height * 0.18)
    page_right = left + int(width * 0.72)
    page_bottom = top + int(height * 0.81)
    draw.rounded_rectangle((page_left, page_top, page_right, page_bottom), radius=int(width * 0.06), fill=PARCHMENT, outline=GOLD_DARK, width=max(3, int(width * 0.025)))
    draw.rounded_rectangle((page_left + int(width * 0.03), page_top, page_right - int(width * 0.03), page_top + int(height * 0.10)), radius=int(width * 0.04), fill="#E2B86F")
    draw.rounded_rectangle((page_left + int(width * 0.10), page_bottom - int(height * 0.10), page_right - int(width * 0.02), page_bottom), radius=int(width * 0.04), fill="#E2B86F")
    for offset, line_width in ((0.34, 0.29), (0.46, 0.33), (0.58, 0.25)):
        y = top + int(height * offset)
        draw.rounded_rectangle(
            (page_left + int(width * 0.08), y, page_left + int(width * (0.08 + line_width)), y + int(height * 0.035)),
            radius=int(height * 0.014),
            fill=INK,
        )

    pen_left = left + int(width * 0.57)
    pen_top = top + int(height * 0.60)
    pen_right = left + int(width * 0.88)
    pen_bottom = top + int(height * 0.91)
    draw.polygon(
        [(pen_left, pen_top + int(height * 0.09)), (pen_left + int(width * 0.09), pen_top), (pen_right, pen_bottom - int(height * 0.09)), (pen_right - int(width * 0.09), pen_bottom)],
        fill=GOLD,
    )
    draw.polygon(
        [(pen_left, pen_top + int(height * 0.09)), (pen_left - int(width * 0.03), pen_bottom + int(height * 0.02)), (pen_left + int(width * 0.09), pen_bottom - int(height * 0.03))],
        fill="#F7E7C6",
    )


def app_icon() -> None:
    image = Image.new("RGB", (512 * SCALE, 512 * SCALE), BACKGROUND)
    draw_mark(image, (28, 28, 484, 484))
    image.resize((512, 512), Image.Resampling.LANCZOS).save(OUTPUT / "app-icon-512.png", optimize=True)


def feature_graphic() -> None:
    image = Image.new("RGB", (1024 * SCALE, 500 * SCALE), BACKGROUND)
    draw = ImageDraw.Draw(image)
    for y in range(500 * SCALE):
        blend = y / (500 * SCALE)
        red = int(23 + 13 * blend)
        green = int(18 + 10 * blend)
        blue = int(13 + 7 * blend)
        draw.line((0, y, 1024 * SCALE, y), fill=(red, green, blue))

    draw_mark(image, (62, 76, 410, 424))
    draw.text((455 * SCALE, 112 * SCALE), "Manuscript", font=font(68, bold=True), fill=TEXT)
    draw.text((458 * SCALE, 212 * SCALE), "Forge Perfect Writing", font=font(36, bold=True), fill=GOLD)
    draw.text((460 * SCALE, 286 * SCALE), "Private, on-device correction", font=font(24), fill=MUTED)

    rounded(draw, (456, 348, 948, 414), 18, SURFACE_RAISED, BORDER, 2)
    draw.text((486 * SCALE, 362 * SCALE), "Proofread  •  Rewrite  •  Tone", font=font(22, bold=True), fill=TEXT)

    image.resize((1024, 500), Image.Resampling.LANCZOS).save(OUTPUT / "feature-graphic-1024x500.png", optimize=True)


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    app_icon()
    feature_graphic()
    print(OUTPUT)


if __name__ == "__main__":
    main()
