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
# This runs before COPY pyproject.toml on purpose: it doesn't depend on
# it, and Docker invalidates every later layer once an earlier COPY
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
# The pip download cache is a BuildKit cache mount rather than discarded
# (`--no-cache-dir`): it lives outside the image, so it costs no image
# size, but a layer that does get invalidated re-installs from wheels
# already on disk instead of downloading several GB again.
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install --prefix=/install torch torchvision --index-url https://download.pytorch.org/whl/cpu

# Only the third-party dependencies are installed here, from pyproject.toml
# alone. The project's own code is NOT installed: the runtime stage copies
# src and runs it through PYTHONPATH=/app/src, so an installed copy was
# dead weight -- and, worse, having `COPY src` ahead of this step meant
# editing any source file re-ran the whole multi-GB dependency install.
# Now this layer is invalidated only when pyproject.toml changes.
# (tomllib reads the dependency list so it is declared in one place.)
COPY pyproject.toml .
RUN python -c "import tomllib; print('\n'.join(tomllib.load(open('pyproject.toml', 'rb'))['project']['dependencies']))" > requirements.txt
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install --prefix=/install -r requirements.txt

FROM python:3.12-slim AS runtime-base
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

# /home/oakdms/.paddlex and /home/oakdms/.cache/huggingface hold the model
# weights, baked in by the prefetch step below. docker-compose.yml also
# mounts named volumes over them -- pre-creating them with oakdms
# ownership here matters because Docker only copies an image path's
# existing permissions and CONTENTS into a *fresh* named volume on first
# mount; a path that doesn't exist yet in the image gets an empty
# root-owned directory instead, which the oakdms process can't write to.
# Verified: without this, first use after adding the volume failed with
# "PermissionError: [Errno 13] Permission denied: '/home/oakdms/.paddlex/temp'".
WORKDIR /app
COPY --from=build /install /usr/local
COPY src src
COPY scripts/prefetch_models.py scripts/prefetch_models.py

ENV PYTHONPATH=/app/src \
    HOME=/home/oakdms

USER oakdms

# Model weights, baked in (production prompt 3.1: "Pre-download all model
# weights at image build time and warm them up at process start"). Without
# this a cold start fetches ~650 MB from the PaddleX model server and the
# HuggingFace Hub before it can read its first document -- which makes a
# restricted-egress deployment impossible and a third-party outage into a
# failed rollout.
#
# Runs AS oakdms and after HOME is set, deliberately: the caches land
# under /home/oakdms owned by the user that will read them. Running it as
# root and chowning afterwards works too, and doubles the layer size by
# copying every file.
#
# `scripts/prefetch_models.py` drives the worker's own `warm_up()`
# methods rather than a list of model names, so the baked set cannot
# drift from the set the runtime asks for. Verified empirically by
# running the built image with `--network none` and no volumes -- see the
# README.
#
# `OCR_LANGUAGES` is set only when the build arg is non-empty. Exporting
# it unconditionally passes an EMPTY string when the arg is unset, which
# overrides `Settings.ocr_languages`' own default rather than falling back
# to it -- the prefetch then reported "all weights cached for languages:"
# with nothing after the colon and baked only the orientation model, 6.7M
# where 146M was expected. The empty-string case is the one that looks
# like it works.
ARG OCR_PREFETCH_LANGS=""
RUN if [ -n "$OCR_PREFETCH_LANGS" ]; then export OCR_LANGUAGES="$OCR_PREFETCH_LANGS"; fi \
    && python scripts/prefetch_models.py \
    # The prefetch's own scratch space, not weights. `temp` is recreated
    # at runtime; `func_ret` is a memoisation cache keyed on absolute
    # paths that means nothing in a fresh container.
    && rm -rf /home/oakdms/.paddlex/temp /home/oakdms/.paddlex/func_ret \
    && du -sh /home/oakdms/.paddlex /home/oakdms/.cache/huggingface

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


# ----------------------------------------------------------------------
# test -- `docker build --target test -t ocr-worker-test:latest .`
# ----------------------------------------------------------------------
# The production image deliberately has no pytest, which is right for a
# runtime image and is also why the CI gate's unit-test step failed the
# first time it was run against it: "No module named pytest". The dev
# scripts under scripts/dev/ all invoke `ocr-worker-test:latest`, which
# until now existed only on whichever machine had built it by hand and
# was reproducible from nothing in this repo.
#
# Built FROM runtime-base, so it is the production image plus test
# dependencies -- same Python, same native libs, same baked model
# weights. A test image assembled separately would be a second
# environment that can disagree with the one that ships.
FROM runtime-base AS test
USER root
COPY pyproject.toml .
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install "pytest>=8.3" "pytest-asyncio>=0.24" \
        "pytest-mock>=3.14" "httpx>=0.27" "pypdf>=5.0" \
    && chown -R oakdms:oakdms /home/oakdms
USER oakdms
CMD ["python", "-m", "pytest", "tests", "-q"]


# ----------------------------------------------------------------------
# runtime -- the default target
# ----------------------------------------------------------------------
# Last stage wins when `docker build` is run with no --target, so this
# has to come after `test` for a plain build to produce the PRODUCTION
# image. It adds nothing: ENV, USER, WORKDIR, EXPOSE, HEALTHCHECK and CMD
# are all inherited from runtime-base.
FROM runtime-base AS runtime
