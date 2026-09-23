# syntax=docker/dockerfile:1
#
# Build context for this Dockerfile is backend/workers/ocr-worker/ (like
# rendition-worker etc. will be) -- unlike dms-core's Dockerfile, there's no
# shared parent project file this needs to reach outside its own directory.
#
# Multi-stage to keep the runtime image lean: PaddleOCR/paddlepaddle and
# Docling pull in real model weights and native deps, so building wheels
# happens in a separate stage from the slim runtime layer.

FROM python:3.12-slim AS build
WORKDIR /build

# libgl1/libglib2.0-0: required by opencv-python (a paddleocr dependency)
# even in headless use -- omitting them fails at import time, not at
# install time, so it's easy to miss locally and only see in a container.
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential \
    && rm -rf /var/lib/apt/lists/*

# docling's [standard] extra (pulled in unconditionally by docling>=2.0
# itself, not something this project's own extras request) depends on
# transformers/accelerate, which depend on torch. Installed the normal
# way, `pip install torch` resolves PyPI's default CUDA-enabled build,
# which drags in the *entire* NVIDIA CUDA toolkit (cublas/cudnn/nccl/
# nvshmem/triton/cufft/cusolver/...) -- multiple GB -- for a service that
# only ever runs OCR on CPU. Installing the CPU-only build from PyTorch's
# own index FIRST, with PYTHONPATH pointed at this stage's --prefix so pip
# can see it, makes the later resolution treat torch as already satisfied
# and skip the CUDA wheels entirely.
#
# This runs before COPY pyproject.toml/src on purpose: it doesn't depend
# on either, and Docker invalidates every later layer once an earlier COPY
# changes -- ordering it first means an unrelated pyproject.toml edit
# (e.g. bumping a version) doesn't force re-downloading a ~200MB wheel.
ENV PYTHONPATH=/install/lib/python3.12/site-packages \
    PIP_DEFAULT_TIMEOUT=120 \
    PIP_RETRIES=8
# torch and torchvision MUST come from the same index in the same
# command: torchvision ships compiled ops (torchvision::nms etc.) built
# against one specific torch ABI. Installing torch here and letting pip
# resolve torchvision separately from plain PyPI afterward (a natural
# thing to do, since docling only depends on torch/transformers directly,
# not torchvision -- torchvision is transformers' own optional pull for
# image models) pairs mismatched builds and breaks at import time.
# Verified: `import torchvision` raised "RuntimeError: operator
# torchvision::nms does not exist" until both came from the same
# whl/cpu index together.
RUN pip install --no-cache-dir --prefix=/install torch torchvision --index-url https://download.pytorch.org/whl/cpu

COPY pyproject.toml .
COPY src src
RUN pip install --no-cache-dir --prefix=/install .

FROM python:3.12-slim AS runtime
# libgomp1: paddlepaddle's native libpaddle.so dlopen()s libgomp.so.1 for
# its OpenMP kernels and doesn't declare it as a package dependency. It
# only appeared to work without this because numpy's wheel bundles its own
# vendored copy, which happens to satisfy the SONAME lookup process-wide
# *if* numpy is imported first -- import-order-dependent and not something
# to rely on. Verified missing via a fresh `docker exec ... python -c
# "import paddle"` in this same image failing with "ImportError: libgomp.
# so.1: cannot open shared object file" until this was added.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libgl1 \
        libglib2.0-0 \
        libgomp1 \
    && rm -rf /var/lib/apt/lists/* \
    && addgroup --system oakdms \
    && adduser --system --home /home/oakdms --ingroup oakdms oakdms \
    && mkdir -p /home/oakdms/.paddlex /home/oakdms/.cache/huggingface \
    && chown -R oakdms:oakdms /home/oakdms

# /home/oakdms/.paddlex and /home/oakdms/.cache/huggingface are mounted as
# named volumes in docker-compose.yml (model weights are tens of MB, not
# worth re-downloading on every restart) -- pre-creating them with oakdms
# ownership here matters because Docker only copies an image path's
# existing permissions into a *fresh* named volume on first mount; a path
# that doesn't exist yet in the image gets an empty root-owned directory
# instead, which the oakdms process can't write to.
# Verified: without this, first use after adding the volume failed with
# "PermissionError: [Errno 13] Permission denied: '/home/oakdms/.paddlex/temp'".
WORKDIR /app
COPY --from=build /install /usr/local
COPY src src

ENV PYTHONPATH=/app/src \
    HOME=/home/oakdms

USER oakdms
EXPOSE 8090

# /readyz (not /healthz) -- gated on every OCR process-pool worker having
# actually finished constructing+warming up its own PaddleOCR/Docling
# models (see api.py/worker_pool.py). /healthz always returns ok regardless
# of warm-up state (verified live), which let docker-compose's
# service_healthy dependency -- dms-core waits on it -- pass before models
# were loaded. start-period raised from 40s: real model construction across
# OCR_WORKER_PROCESSES spawned processes takes noticeably longer than the
# single-process FastAPI-only startup this was originally tuned for.
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8090/readyz').read()" || exit 1

CMD ["python", "-m", "uvicorn", "ocr_worker.main:app", "--host", "0.0.0.0", "--port", "8090"]
