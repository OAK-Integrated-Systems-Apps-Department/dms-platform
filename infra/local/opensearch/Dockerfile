# OpenSearch with the ICU analysis plugin (Document Search & Retrieval, DSR).
#
# Why a custom image: the search mapping uses icu_tokenizer / icu_folding /
# icu_collation_keyword (word-breaking and folding for Sinhala, Tamil, Thai,
# CJK and accented Latin). The official image does not bundle analysis-icu and
# the plugin version must equal the core version, hence one build arg for both.
#
# Build ONLY this image:  docker compose build opensearch
# (never together with the OCR image, whose build crashes on a small Docker VM).
ARG OS_VERSION=3.7.0
FROM opensearchproject/opensearch:${OS_VERSION}
RUN /usr/share/opensearch/bin/opensearch-plugin install --batch analysis-icu
