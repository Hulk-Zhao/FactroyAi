# =============================================================================
# 衡策 APS 前端镜像（Vue3 + Vite 构建，Nginx 托管）
#   构建上下文为 aps-frontend 目录：
#     docker build -f aps-frontend/Dockerfile -t aps-frontend:local aps-frontend
# =============================================================================
# syntax=docker/dockerfile:1.7

# --------------------------- 构建阶段 ---------------------------------------
FROM node:22-alpine AS build
WORKDIR /app

ENV NPM_CONFIG_FUND=false \
    NPM_CONFIG_AUDIT=false

# 依赖层独立缓存
COPY package.json package-lock.json ./
RUN npm ci

COPY . .

# 子路径部署参数：compose 传入 APS_BASE 与接口/WS 前缀，
# 三者必须与香港 Nginx 的 location 前缀一致，否则请求会串到同域其它 /api。
ARG APS_BASE=/
ARG VITE_API_BASE_URL=/api/v1
ARG VITE_WS_BASE_URL=
ENV APS_BASE=${APS_BASE} \
    VITE_API_BASE_URL=${VITE_API_BASE_URL} \
    VITE_WS_BASE_URL=${VITE_WS_BASE_URL}

RUN npm run build

# --------------------------- 运行阶段 ---------------------------------------
# nginx 非特权镜像：以 uid 101 运行，容器内监听 8080（非 <1024 特权端口）。
# 注意：宿主机端口映射需指向容器 8080。
FROM nginxinc/nginx-unprivileged:1.31-alpine AS runtime

# 后端地址由 compose/运行时注入；默认与 compose 服务名 aps-backend 保持一致
ENV APS_BACKEND_URL=http://aps-backend:8080

COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
    CMD ["wget", "-qO-", "http://127.0.0.1:8080/healthz"]
