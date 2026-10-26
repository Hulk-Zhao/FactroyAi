# syntax=docker/dockerfile:1
# Hogo-QA-tool 云端部署镜像（多阶段构建）。

# ---- 阶段 1：前端构建（vite 多文件 HTTP 版） ----
FROM node:22-alpine AS frontend-build
WORKDIR /app/frontend
# 先只拷贝锁文件利用层缓存：依赖未变时 npm ci 不重跑。
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
# 部署基路径：默认相对路径；挂在子路径（hulkai.cn/demo/hogo-qa/）时由
# deploy/hk-web 的 compose 传入 /demo/hogo-qa/，前端据此推导路由 basename 与 API 前缀。
ARG HOGO_BASE=./
ENV HOGO_BASE=${HOGO_BASE}
RUN npm run build:server

# ---- 阶段 2：运行时（后端零 npm 依赖，Node 22 直接运行） ----
FROM node:22-alpine
ENV NODE_ENV=production
WORKDIR /app
COPY backend/ ./backend/
COPY --from=frontend-build /app/frontend/dist-server ./frontend/dist-server

# 运行时目录（自动生成的 HMAC 密钥）需要以非 root 用户写入。
RUN mkdir -p /app/backend/data/runtime && chown -R node:node /app/backend/data
USER node

# STATIC_DIR 默认相对 backend/ 解析为 ../frontend/dist-server，与上面的拷贝路径一致。
EXPOSE 8788
CMD ["node", "backend/server.mjs"]
