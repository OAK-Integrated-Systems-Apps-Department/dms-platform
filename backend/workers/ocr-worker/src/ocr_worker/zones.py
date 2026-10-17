from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ZoneRegion:
    """A region expressed as fractions of page width/height (0.0-1.0), so a
    template defined against one scan resolution still lands correctly on a
    document rendered at a different DPI (OS-FR-003).
    """

    name: str
    field: str
    page: int
    x0: float
    y0: float
    x1: float
    y1: float

    def __post_init__(self) -> None:
        validate_normalized_box(self.x0, self.y0, self.x1, self.y1)

    def to_pixel_box(self, page_width_px: int, page_height_px: int) -> tuple[int, int, int, int]:
        return (
            round(self.x0 * page_width_px),
            round(self.y0 * page_height_px),
            round(self.x1 * page_width_px),
            round(self.y1 * page_height_px),
        )


def validate_normalized_box(x0: float, y0: float, x1: float, y1: float) -> None:
    """Shared validation for zone-template regions AND rubber-band
    selections (OS-FR-004's "user-drawn arbitrary region"). A degenerate or
    out-of-bounds box is the "ambiguous rubber-band selection" alternate
    flow -- callers should turn this into a 422, not a crash.
    """
    for name, value in (("x0", x0), ("y0", y0), ("x1", x1), ("y1", y1)):
        if not 0.0 <= value <= 1.0:
            raise ValueError(f"{name}={value} is outside the normalized [0.0, 1.0] range")
    if x1 <= x0 or y1 <= y0:
        raise ValueError(f"degenerate region: ({x0},{y0})-({x1},{y1})")
