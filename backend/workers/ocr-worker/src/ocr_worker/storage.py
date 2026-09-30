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

    def put_rendition(self, object_key: str, data: bytes, content_type: str = "application/pdf") -> str:
        """A derived artifact (production prompt 3.11's searchable PDF).

        Written to the OCR OUTPUT bucket, not the source bucket. The
        source bucket holds what the user uploaded and is the one thing
        this system must never be able to corrupt; everything ocr-worker
        produces is reproducible from it and belongs on the other side of
        that line.
        """
        self.client.put_object(
            self._settings.minio_ocr_output_bucket,
            object_key,
            data=io.BytesIO(data),
            length=len(data),
            content_type=content_type,
        )
        return object_key

    def ensure_buckets(self) -> None:
        for bucket in (self._settings.minio_source_bucket, self._settings.minio_ocr_output_bucket):
            if not self.client.bucket_exists(bucket):
                self.client.make_bucket(bucket)


def ocr_output_key(tenant_id, version_id, ocr_output_id) -> str:
    return f"ocr/{tenant_id}/{version_id}/{ocr_output_id}.json"
