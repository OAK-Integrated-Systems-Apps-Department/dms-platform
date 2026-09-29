"""Per-page image quality assessment (production prompt 3.3).

Values are computed against images built here with known properties
rather than against golden numbers captured from a previous run: a
golden number would still pass if the measurement silently changed
meaning, which is the failure this is guarding.
"""
from __future__ import annotations

import numpy as np
import pytest
from PIL import Image, ImageDraw, ImageFilter

from ocr_worker import quality


def _text_page(size=(600, 800), fg=0, bg=255) -> Image.Image:
    """A page with sharp, level, high-contrast text-like bars."""
    img = Image.new("L", size, bg)
    draw = ImageDraw.Draw(img)
    for row in range(12):
        y = 60 + row * 55
        draw.rectangle([60, y, size[0] - 60, y + 22], fill=fg)
    return img


class TestEffectiveDpi:
    def test_a_letter_page_scanned_at_300_reports_300(self):
        # 8.5x11in at 300dpi = 2550x3300 px; 612x792pt is that page.
        assert quality.effective_dpi(2550, 3300, 612, 792) == pytest.approx(300.0)

    def test_it_averages_the_axes_so_a_fax_is_not_called_fine(self):
        # Standard fax: 204x98 dpi. Reporting only the width would say 204.
        dpi = quality.effective_dpi(1734, 1078, 612, 792)
        assert dpi == pytest.approx(151.0, abs=1.0)
        assert dpi < 204

    @pytest.mark.parametrize(
        "args",
        [(0, 100, 612, 792), (100, 0, 612, 792), (100, 100, 0, 792), (100, 100, 612, 0)],
    )
    def test_a_degenerate_input_is_none_not_a_number(self, args):
        assert quality.effective_dpi(*args) is None


class TestBlur:
    def test_a_blurred_page_scores_below_its_sharp_original(self):
        sharp = np.array(_text_page())
        blurred = np.array(_text_page().filter(ImageFilter.GaussianBlur(radius=4)))
        assert quality.blur_score(blurred) < quality.blur_score(sharp)

    def test_a_flat_page_has_essentially_no_edge_energy(self):
        flat = np.full((400, 400), 200, dtype=np.uint8)
        assert quality.blur_score(flat) == pytest.approx(0.0, abs=1e-6)


class TestContrast:
    def test_a_washed_out_page_scores_below_a_clean_one(self):
        clean = np.array(_text_page(fg=0, bg=255))
        washed = np.array(_text_page(fg=120, bg=160))
        assert quality.contrast_score(washed) < quality.contrast_score(clean)

    def test_one_stray_bright_pixel_does_not_rescue_a_flat_page(self):
        """Why RMS and not Michelson: peak-to-peak would read 1.0 here."""
        flat = np.full((400, 400), 128, dtype=np.uint8)
        flat[0, 0] = 255
        flat[0, 1] = 0
        assert quality.contrast_score(flat) < 2.0


class TestSkew:
    def test_a_level_page_reports_approximately_zero(self):
        angle = quality.skew_angle(np.array(_text_page()))
        assert angle is not None
        assert abs(angle) < 1.0

    def test_a_tilted_page_reports_the_tilt(self):
        tilted = _text_page().rotate(-5, expand=False, fillcolor=255)
        angle = quality.skew_angle(np.array(tilted))
        assert angle is not None
        assert angle == pytest.approx(5.0, abs=1.5)

    def test_it_is_measured_on_a_downscaled_copy(self):
        """Skew is measured at ~1000px on the long edge. Asserted through
        behaviour rather than by inspecting the resize: the cost must stop
        tracking page size, since a full-resolution Canny + Hough was
        704ms/page for a number that never raises a flag."""
        small = quality._downscaled_for_line_detection(np.zeros((3300, 2550), dtype=np.uint8), 1000)
        assert max(small.shape) == 1000

    def test_a_page_already_small_enough_is_not_resized(self):
        gray = np.zeros((400, 300), dtype=np.uint8)
        assert quality._downscaled_for_line_detection(gray, 1000) is gray

    @pytest.mark.parametrize("tilt", [1.0, 3.0, -2.5, 5.0])
    def test_downscaling_does_not_cost_accuracy(self, tilt):
        """An angle is invariant under uniform scaling, so the whole point
        is that the cheaper measurement is the same measurement."""
        tilted = _text_page(size=(2550, 3300)).rotate(-tilt, expand=False, fillcolor=255)
        measured = quality.skew_angle(np.array(tilted))
        assert measured is not None
        assert measured == pytest.approx(tilt, abs=0.5)

    def test_a_blank_page_is_none_rather_than_zero(self):
        """'Nothing to measure' and 'measured, and it is level' are
        different facts and must not collapse."""
        blank = np.full((400, 400), 255, dtype=np.uint8)
        assert quality.skew_angle(blank) is None

    def test_vertical_rules_do_not_report_a_90_degree_skew(self):
        img = Image.new("L", (600, 800), 255)
        draw = ImageDraw.Draw(img)
        for x in (100, 250, 400, 550):
            draw.rectangle([x, 50, x + 6, 750], fill=0)
        for row in range(6):
            y = 100 + row * 100
            draw.rectangle([100, y, 550, y + 10], fill=0)
        angle = quality.skew_angle(np.array(img))
        assert angle is not None
        assert abs(angle) < 2.0


class TestAssessPage:
    def _assess(self, image, **over):
        kwargs = dict(
            width_pt=612.0, height_pt=792.0, native_pixel_size=None,
            min_dpi=150.0, min_blur=100.0, min_contrast=35.0,
        )
        kwargs.update(over)
        return quality.assess_page(image, **kwargs)

    def test_a_good_page_is_measured_and_unflagged(self):
        # 2550x3300 over 612x792pt is a 300 DPI scan.
        measured, reasons = self._assess(_text_page(size=(2550, 3300)))
        assert measured["measured"] is True
        assert reasons == []
        assert measured["effective_dpi"] == pytest.approx(300.0, abs=1.0)

    def test_a_low_resolution_page_reports_LOW_RESOLUTION(self):
        measured, reasons = self._assess(_text_page(size=(600, 800)))
        assert quality.LOW_RESOLUTION in reasons
        assert measured["effective_dpi"] < 150

    def test_a_blurred_page_reports_BLURRY(self):
        blurred = _text_page(size=(2550, 3300)).filter(ImageFilter.GaussianBlur(radius=8))
        _, reasons = self._assess(blurred)
        assert quality.BLURRY in reasons

    def test_a_washed_out_page_reports_LOW_CONTRAST(self):
        washed = _text_page(size=(2550, 3300), fg=120, bg=150)
        _, reasons = self._assess(washed)
        assert quality.LOW_CONTRAST in reasons

    def test_native_pixel_size_wins_over_the_capped_render(self):
        """A 300 DPI scan rendered small by OCR_RENDER_MAX_MP must not be
        reported as low resolution -- the detail is in the file."""
        small_render = _text_page(size=(600, 800))
        measured, reasons = self._assess(small_render, native_pixel_size=(2550, 3300))
        assert measured["effective_dpi"] == pytest.approx(300.0, abs=1.0)
        assert quality.LOW_RESOLUTION not in reasons

    def test_thresholds_are_echoed_so_a_reason_can_be_read_without_the_config(self):
        measured, _ = self._assess(_text_page(size=(2550, 3300)))
        assert measured["thresholds"] == {
            "min_dpi": 150.0, "min_blur": 100.0, "min_contrast": 35.0,
        }

    def test_skew_is_reported_but_never_raises_the_flag(self):
        """A sharp, well-lit, high-resolution page at an angle is not a
        page anyone should be told to re-scan."""
        tilted = _text_page(size=(2550, 3300)).rotate(-4, expand=False, fillcolor=255)
        measured, reasons = self._assess(tilted)
        assert measured["skew_angle"] is not None
        assert abs(measured["skew_angle"]) > 2.0
        assert reasons == []

    def test_an_unreadable_image_is_measured_false_not_an_exception(self):
        class Exploding:
            mode = "RGB"

            def convert(self, _mode):
                raise RuntimeError("no pixels here")

        measured, reasons = self._assess(Exploding())
        assert measured == {"measured": False}
        assert reasons == []

    def test_a_missing_measurement_never_fires_its_own_threshold(self):
        """'We could not measure the blur' is not evidence of blur."""
        blank = Image.new("L", (2550, 3300), 255)
        measured, reasons = self._assess(blank)
        # A blank page has no lines, so skew is unmeasurable...
        assert measured["skew_angle"] is None
        # ...but it is genuinely flat, so contrast/blur DO fire.
        assert quality.LOW_CONTRAST in reasons
