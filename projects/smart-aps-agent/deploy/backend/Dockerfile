# =============================================================================
# 衡策 APS 后端「运行时」镜像（M2-QA-03）
#   只提供 JRE 21 + curl（健康检查用），不编译任何业务代码。
#   业务 fat jar 由 compose 以只读方式挂载到 /app/app.jar：
#     APS_BACKEND_JAR=../aps-admin/target/aps-admin-0.1.0-SNAPSHOT.jar
#   这样容器内运行的 jar 与仓库制品是同一份文件（SHA256 可逐字节核对），
#   也避免部署机/部署脚本重新 mvn package（构建制品由 W9 统一交付）。
#
#   构建上下文为 deploy/backend（镜像里不含 jar）：
#     docker build -t aps-stack/aps-backend:local deploy/backend
#
#   源码构建方式仍保留在 aps-admin/Dockerfile，本文件不替代它。
# =============================================================================
FROM eclipse-temurin:21-jre-jammy

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

# 非 root 运行；curl 供 HEALTHCHECK 与排障使用
# hadolint ignore=DL3008
RUN groupadd --system --gid 1001 aps \
    && useradd --system --uid 1001 --gid aps --home-dir /app --no-create-home --shell /usr/sbin/nologin aps \
    && apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
USER 1001:1001
EXPOSE 8080

# 与 README 一致：GET /api/v1/health
HEALTHCHECK --interval=10s --timeout=5s --start-period=90s --retries=12 \
    CMD ["curl", "-fsS", "http://127.0.0.1:8080/api/v1/health"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
