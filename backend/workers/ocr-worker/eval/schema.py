"""The seeded document-type schemas, mirrored from dms-core's V30 seed.

Why mirrored rather than read from the database: the eval harness runs
in-process against an in-memory SQLite session (the pattern in
tests/conftest.py), so there is no dms-core schema to query -- and making
the harness depend on a live Postgres would cost it the property that
makes it useful, that it runs in seconds without the stack.

The cost of mirroring is drift. `test_eval_schema.py` pins these values
against the migration file itself so a schema change in V30 fails a test
rather than silently invalidating every accuracy number.

Source: backend/dms-core/src/main/resources/db/migration/
V30__seed_metadata_field_catalogue_and_document_types.sql
"""

from __future__ import annotations

from dataclasses import dataclass


# metadata_field_options, V30. Only the dropdowns that actually have
# seeded options -- `language` is deliberately left optionless there.
OPTIONS: dict[str, list[str]] = {
    "classification_level": ["public", "internal", "confidential", "restricted"],
    "currency": ["USD", "EUR", "GBP", "LKR"],
    "retention_class": ["short_term", "medium_term", "long_term", "permanent"],
}


# metadata_fields, V30: field_key -> data_type.
DATA_TYPES: dict[str, str] = {
    "classification_level": "dropdown",
    "department": "lookup",
    "tags": "multi_select",
    "retention_class": "dropdown",
    "description": "long_text",
    "author": "text",
    "keywords": "multi_select",
    "language": "dropdown",
    "reference_number": "text",
    "effective_date": "date",
    "expiry_date": "date",
    "review_due_date": "date",
    "invoice_number": "text",
    "invoice_date": "date",
    "amount": "decimal",
    "currency": "dropdown",
    "vendor_supplier_name": "text",
    "purchase_order_number": "text",
    "contract_party": "text",
    "contract_value": "decimal",
    "contract_start_date": "date",
    "contract_end_date": "date",
    "employee_id": "text",
    "project_code": "dropdown",
    "cost_centre": "dropdown",
}


# document_type_fields, V30: type_code -> [(field_key, is_mandatory)].
TYPE_FIELDS: dict[str, list[tuple[str, bool]]] = {
    "CONTRACT": [
        ("contract_party", True),
        ("contract_value", True),
        ("contract_start_date", True),
        ("contract_end_date", True),
        ("effective_date", False),
        ("expiry_date", False),
        ("review_due_date", False),
        ("classification_level", True),
        ("retention_class", True),
        ("department", False),
        ("tags", False),
        ("description", False),
        ("reference_number", False),
    ],
    "INVOICE": [
        ("invoice_number", True),
        ("invoice_date", True),
        ("amount", True),
        ("currency", True),
        ("vendor_supplier_name", True),
        ("purchase_order_number", False),
        ("classification_level", True),
        ("department", False),
        ("cost_centre", False),
        ("tags", False),
        ("description", False),
    ],
    "POLICY": [
        ("classification_level", True),
        ("department", True),
        ("retention_class", True),
        ("effective_date", True),
        ("expiry_date", False),
        ("review_due_date", True),
        ("description", False),
        ("author", False),
        ("keywords", False),
        ("language", False),
        ("tags", False),
        ("reference_number", False),
    ],
}


@dataclass(frozen=True)
class EvalFieldSpec:
    """Mirrors extraction.FieldSpec, but carries `is_mandatory` too.

    The harness needs mandatory-ness to score `requires_review` (section
    3.10 defines it as "any mandatory field not extracted OR ..."), which
    the runtime FieldSpec has no reason to know about.
    """

    field_key: str
    data_type: str
    options: list[str] | None
    is_mandatory: bool


def field_specs_for(type_code: str) -> list[EvalFieldSpec]:
    return [
        EvalFieldSpec(
            field_key=key,
            data_type=DATA_TYPES[key],
            options=OPTIONS.get(key),
            is_mandatory=mandatory,
        )
        for key, mandatory in TYPE_FIELDS[type_code]
    ]


def mandatory_keys(type_code: str) -> set[str]:
    return {key for key, mandatory in TYPE_FIELDS[type_code] if mandatory}


# The labels the corpus prints on the page for each field.
#
# Every one of these is an alias extraction.py's `_LABEL_ALIASES` already
# knows, so the corpus exercises the production extraction path rather
# than a parallel one invented for the test. Where a field has no alias
# entry at all (contract_party, contract_value, contract_start_date,
# contract_end_date, review_due_date, language, project_code,
# employee_id), the label is the field key title-cased -- which is what
# extraction.py falls back to -- and those fields are expected to score
# poorly at baseline. That is a real finding about coverage, not a defect
# in the corpus, and the per-field report is where it shows up.
PAGE_LABELS: dict[str, str] = {
    "invoice_number": "Invoice Number",
    "invoice_date": "Invoice Date",
    "amount": "Total Amount",
    "currency": "Currency",
    "vendor_supplier_name": "Vendor / Supplier",
    "purchase_order_number": "PO Number",
    "classification_level": "Classification",
    "department": "Department",
    "cost_centre": "Cost Centre",
    "tags": "Tags",
    "description": "Description",
    "contract_party": "Contract Party",
    "contract_value": "Contract Value",
    "contract_start_date": "Contract Start Date",
    "contract_end_date": "Contract End Date",
    "effective_date": "Effective Date",
    "expiry_date": "Expiry Date",
    "review_due_date": "Review Due Date",
    "retention_class": "Retention Class",
    "reference_number": "Reference Number",
    "author": "Author",
    "keywords": "Keywords",
    "language": "Language",
}
