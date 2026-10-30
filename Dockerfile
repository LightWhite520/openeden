# syntax=docker/dockerfile:1
# Prepare server/build/install/server and build/model-manifest before building.
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install -y --no-install-recommends curl libgomp1 util-linux \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 openeden \
    && useradd --uid 10001 --gid openeden --create-home openeden
WORKDIR /app
COPY --chown=openeden:openeden server/build/install/server/ /opt/openeden/
COPY --chown=openeden:openeden persona/ /app/persona/
COPY build/model-manifest/ /opt/openeden-models/
COPY --chmod=755 deploy/model-entrypoint.sh /usr/local/bin/openeden-entrypoint
RUN mkdir -p /app/data/runtime /app/data/exports /app/data/operator /app/data/models /home/openeden/.djl.ai \
    && chown -R openeden:openeden /app/data \
    && chown openeden:openeden /home/openeden/.djl.ai \
    && chmod +x /opt/openeden/bin/server
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Dfile.encoding=UTF-8" \
    DJL_CACHE_DIR=/home/openeden/.djl.ai \
    OPENEDEN_THYMOS_DEVICE=cpu
USER openeden
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=30m --retries=5 \
    CMD curl --fail --silent --max-time 4 http://127.0.0.1:8080/health > /dev/null || exit 1
ENTRYPOINT ["/usr/local/bin/openeden-entrypoint"]
CMD ["/opt/openeden/bin/server"]
