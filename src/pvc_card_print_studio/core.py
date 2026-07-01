from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, Mapping

if TYPE_CHECKING:
    from PIL import Image


@dataclass(frozen=True)
class CardRatio:
    """Physical card dimensions used to determine crop ratio."""

    width: float = 86.0
    height: float = 54.0

    def __post_init__(self) -> None:
        if self.width <= 0 or self.height <= 0:
            raise ValueError("Card width and height must be greater than zero.")

    @property
    def aspect(self) -> float:
        return self.width / self.height

    @property
    def label(self) -> str:
        return f"{_format_number(self.width)}:{_format_number(self.height)}"


def parse_ratio_payload(payload: Mapping[str, Any]) -> CardRatio:
    """Parse supported server payloads into a card ratio.

    Supported formats:
    - {"width": 86, "height": 54}
    - {"ratio": 1.5926}
    - {"aspectRatio": "86:54"}
    - {"aspect_ratio": "86:54"}
    - {"size": "86:54"}
    """

    if not isinstance(payload, Mapping):
        raise ValueError("Ratio response must be a JSON object.")

    width = _positive_float(payload.get("width"))
    height = _positive_float(payload.get("height"))
    if width and height:
        return CardRatio(width=width, height=height)

    ratio = _positive_float(payload.get("ratio"))
    if ratio:
        return CardRatio(width=ratio, height=1.0)

    ratio_text = payload.get("aspectRatio") or payload.get("aspect_ratio") or payload.get("size")
    if isinstance(ratio_text, str) and ":" in ratio_text:
        left, right = ratio_text.split(":", maxsplit=1)
        width = _positive_float(left)
        height = _positive_float(right)
        if width and height:
            return CardRatio(width=width, height=height)

    raise ValueError("Unsupported ratio format.")


def cover_crop_box(image_size: tuple[int, int], ratio: CardRatio) -> tuple[int, int, int, int]:
    """Return a centered crop box that covers the requested ratio."""

    image_width, image_height = image_size
    if image_width <= 0 or image_height <= 0:
        raise ValueError("Image dimensions must be greater than zero.")

    image_aspect = image_width / image_height
    target_aspect = ratio.aspect

    if image_aspect > target_aspect:
        crop_width = round(image_height * target_aspect)
        left = round((image_width - crop_width) / 2)
        return (left, 0, left + crop_width, image_height)

    crop_height = round(image_width / target_aspect)
    top = round((image_height - crop_height) / 2)
    return (0, top, image_width, top + crop_height)


def create_print_image(image: "Image.Image", ratio: CardRatio, dpi: int = 300) -> "Image.Image":
    """Create a print-ready RGB image cropped to the card ratio at the requested DPI."""

    if dpi <= 0:
        raise ValueError("DPI must be greater than zero.")

    from PIL import Image

    crop_box = cover_crop_box(image.size, ratio)
    cropped = image.crop(crop_box)
    output_size = (round((ratio.width / 25.4) * dpi), round((ratio.height / 25.4) * dpi))
    return cropped.convert("RGB").resize(output_size, Image.Resampling.LANCZOS)


def _positive_float(value: Any) -> float | None:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return None
    return parsed if parsed > 0 else None


def _format_number(value: float) -> str:
    return f"{value:.2f}".rstrip("0").rstrip(".")
