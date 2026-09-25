from __future__ import annotations

import io
import json
from typing import Any

from .config import Settings


class ObjectStorage:
    """Thin wrapper around the MinIO SDK.

    Source objects (storage_object_key) live in `minio_source_bucket` --
    written by whatever ingestion path lands the clean file there (upload
    pipeline / scan-worker, once it exists). OCR output JSON is written to
    `minio_ocr_output_bucket` under a key this service controls.
    """

    def __init__(self, settings: Settings):
        self._settings = settings
        self._client = None  # lazy: keeps `minio` an optional import for unit tests

    @property
    def client(self):
        if self._client is None:
            from minio import Minio

            self._client = Minio(
                self._settings.minio_endpoint,
                access_key=self._settings.minio_access_key,
                secret_key=self._settings.minio_secret_key,
                secure=self._settings.minio_secure,
            )
        return self._client

    def get_object_bytes(self, object_key: str) -> bytes:
        response = self.client.get_object(self._settings.minio_source_bucket, object_key)
        try:
            return response.read()
        finally:
            response.close()
            response.release_conn()

    def put_source_object(self, object_key: str, data: bytes, content_type: str) -> str:
        self.client.put_object(
            self._settings.minio_source_bucket,
            object_key,
            data=io.BytesIO(data),
            length=len(data),
            content_type=content_type,
        )
        return object_key

    def put_ocr_output_json(self, object_key: str, data: dict[str, Any]) -> str:
        payload = json.dumps(data, ensure_ascii=False, indent=2).encode("utf-8")
        self.client.put_object(
            self._settings.minio_ocr_output_bucket,
            object_key,
            data=io.BytesIO(payload),
            length=len(payload),
            content_type="application/json",
        )
        return object_key

    def get_ocr_output_json(self, object_key: str) -> dict[str, Any]:
        response = self.client.get_object(self._settings.minio_ocr_output_bucket, object_key)
        try:
            return json.loads(response.read())
        finally:
            response.close()
            response.release_conn()

    def latest_ocr_output_key(self, tenant_id, version_id, *, structured: bool = False) -> str | None:
        """Newest OCR output (or, with structured=True, newest structured
        result) stored for one version, by upload time -- ocr_output_key's
        layout groups every run of a version under one prefix."""
        prefix = f"ocr/{tenant_id}/{version_id}/"
        newest = None
        for obj in self.client.list_objects(self._settings.minio_ocr_output_bucket, prefix=prefix, recursive=True):
            name = obj.object_name
            if not name.endswith(".json") or name.endswith(STRUCTURED_SUFFIX) != structured:
                continue
            if newest is None or obj.last_modified > newest.last_modified:
                newest = obj
        return newest.object_name if newest is not None else None

    def ensure_buckets(self) -> None:
        for bucket in (self._settings.minio_source_bucket, self._settings.minio_ocr_output_bucket):
            if not self.client.bucket_exists(bucket):
                self.client.make_bucket(bucket)


STRUCTURED_SUFFIX = ".structured.json"


def ocr_output_key(tenant_id, version_id, ocr_output_id) -> str:
    return f"ocr/{tenant_id}/{version_id}/{ocr_output_id}.json"


def structured_output_key(extracted_text_ref: str) -> str:
    """The structured (LLM) result lives next to the OCR output it was built
    from: ocr/{tenant}/{version}/{ocr_output_id}.structured.json."""
    return extracted_text_ref.removesuffix(".json") + STRUCTURED_SUFFIX
