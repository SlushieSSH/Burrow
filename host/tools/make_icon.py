import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw

SIZES = (16, 20, 24, 32, 40, 48, 64, 96, 128, 256)
SUPERSAMPLE = 8
WHITE = (255, 255, 255, 255)
BLACK = (0, 0, 0, 255)


def ring_thickness(size: int) -> float:
    return 0.17 if size <= 24 else 0.15


def gap_degrees(size: int) -> float:
    return 96 if size <= 24 else 84


def render(size: int) -> Image.Image:
    canvas_size = size * SUPERSAMPLE
    canvas = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    draw.rounded_rectangle((0, 0, canvas_size - 1, canvas_size - 1), radius=canvas_size * 0.22, fill=BLACK)

    thickness = canvas_size * ring_thickness(size)
    outer_radius = canvas_size * 0.33
    center_x = canvas_size / 2
    center_y = canvas_size / 2 + canvas_size * 0.035
    outer = (center_x - outer_radius, center_y - outer_radius, center_x + outer_radius, center_y + outer_radius)
    draw.ellipse(outer, fill=WHITE)
    inner_radius = outer_radius - thickness
    draw.ellipse((center_x - inner_radius, center_y - inner_radius, center_x + inner_radius, center_y + inner_radius), fill=BLACK)

    half_gap = math.radians(gap_degrees(size) / 2)
    reach = outer_radius * 1.6
    wedge = [
        (center_x, center_y),
        (center_x + reach * math.sin(-half_gap), center_y - reach * math.cos(half_gap)),
        (center_x, center_y - reach),
        (center_x + reach * math.sin(half_gap), center_y - reach * math.cos(half_gap)),
    ]
    draw.polygon(wedge, fill=BLACK)

    bar_width = thickness
    bar_top = center_y - outer_radius - canvas_size * 0.06
    bar_bottom = center_y - inner_radius * 0.15
    draw.rectangle((center_x - bar_width / 2, bar_top, center_x + bar_width / 2, bar_bottom), fill=WHITE)
    return canvas.resize((size, size), Image.LANCZOS)


def main(output_path: Path) -> None:
    images = [render(size) for size in SIZES]
    images[-1].save(output_path, format="ICO", sizes=[(size, size) for size in SIZES], append_images=images[:-1])
    images[-1].save(output_path.with_suffix(".png"))


if __name__ == "__main__":
    main(Path(sys.argv[1]))
