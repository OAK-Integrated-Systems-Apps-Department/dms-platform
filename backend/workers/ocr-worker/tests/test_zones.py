from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from ocr_worker.repository import OcrRepository  # noqa: E402
from ocr_worker.zones import ZoneRegion, validate_normalized_box  # noqa: E402

from conftest import seed_zone  # noqa: E402


def test_zone_region_converts_fractional_box_to_pixels():
    region = ZoneRegion(name="invoice_number", field="invoice_number", page=0, x0=0.5, y0=0.0, x1=1.0, y1=0.1)
    assert region.to_pixel_box(2000, 1000) == (1000, 0, 2000, 100)


def test_zone_region_rejects_degenerate_box():
    with pytest.raises(ValueError):
        ZoneRegion(name="bad", field="bad", page=0, x0=0.5, y0=0.5, x1=0.5, y1=0.9)


def test_zone_region_rejects_out_of_bounds_box():
    with pytest.raises(ValueError):
        ZoneRegion(name="bad", field="bad", page=0, x0=-0.1, y0=0.0, x1=0.5, y1=0.5)


@pytest.mark.parametrize(
    "box",
    [
        (0.0, 0.0, 0.0, 1.0),  # zero width
        (0.0, 0.0, 1.0, 0.0),  # zero height
        (0.2, 0.2, 0.1, 0.9),  # x1 < x0
        (1.5, 0.0, 2.0, 1.0),  # out of [0,1]
    ],
)
def test_validate_normalized_box_rejects_ambiguous_rubber_band_selections(box):
    with pytest.raises(ValueError):
        validate_normalized_box(*box)


def test_validate_normalized_box_accepts_well_formed_selection():
    validate_normalized_box(0.1, 0.1, 0.9, 0.9)  # should not raise


def test_get_zone_regions_returns_configured_zones_for_document_type(session_factory, seeded_version):
    seed_zone(
        session_factory,
        tenant_id=seeded_version["tenant_id"],
        document_type_id=seeded_version["document_type_id"],
        zone_name="invoice_number",
        field_name="invoice_number",
        x0=0.1, y0=0.1, x1=0.5, y1=0.2,
    )

    with session_factory() as session:
        regions = OcrRepository(session).get_zone_regions(seeded_version["document_type_id"])

    assert len(regions) == 1
    assert regions[0].name == "invoice_number"
    assert regions[0].field == "invoice_number"


def test_get_zone_regions_is_empty_for_document_type_with_no_template(session_factory, seeded_version):
    # Alternate flow: "document type does not match any configured zone OCR
    # template -> system falls back to basic full-document OCR" -- an empty
    # list (not an exception) is what lets the pipeline's `if zone_regions:`
    # check drive that fallback.
    with session_factory() as session:
        regions = OcrRepository(session).get_zone_regions(seeded_version["document_type_id"])

    assert regions == []
