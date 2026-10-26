# 后端镜像（广州）：FastAPI + Chroma + 云端 embedding（无 torch / 无本地模型）
# 国内构建可覆盖：--build-arg PYTHON_IMAGE=docker.m.daocloud.io/library/python:3.12-slim-bookworm
ARG PYTHON_IMAGE=python:3.12-slim-bookworm
FROM ${PYTHON_IMAGE}

ARG PIP_INDEX_URL=https://mirrors.cloud.tencent.com/pypi/simple
ARG PIP_TRUSTED_HOST=mirrors.cloud.tencent.com

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_INDEX_URL=${PIP_INDEX_URL} \
    PIP_TRUSTED_HOST=${PIP_TRUSTED_HOST} \
    PYTHONPATH=/app/src \
    TZ=Asia/Shanghai \
    LANG=C.UTF-8

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl tzdata \
    && rm -rf /var/lib/apt/lists/*

# 依赖层：先装依赖，改业务代码不触发重装
COPY pyproject.toml README.md ./
COPY src ./src
RUN pip install --no-cache-dir --timeout 120 --retries 3 .

# 只读种子统一放 /app/seed（不落 volume，升级镜像即可更新语料）
COPY data /app/seed
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh && mkdir -p /app/data /app/logs

EXPOSE 8004

HEALTHCHECK --interval=30s --timeout=10s --start-period=300s --retries=3 \
    CMD curl -fsS http://localhost:8004/api/v2/health || exit 1

ENTRYPOINT ["/entrypoint.sh"]
