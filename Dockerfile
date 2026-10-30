# syntax=docker/dockerfile:1
# Build the distribution first: ./gradlew :server:installDist ensureThymosAffectModel
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install -y --no-install-recommends curl libgomp1 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 openeden \
    && useradd --uid 10001 --gid openeden --create-home openeden
WORKDIR /app
COPY --chown=openeden:openeden server/build/install/server/ /opt/openeden/
COPY --chown=openeden:openeden persona/ /app/persona/
COPY --chown=openeden:openeden data/models/local-model-artifact.json /app/data/models/local-model-artifact.json
COPY --chown=openeden:openeden data/models/djl/vqvae/ /app/data/models/djl/vqvae/
COPY --chown=openeden:openeden data/models/djl/text/ /app/data/models/djl/text/
COPY --chown=openeden:openeden data/models/djl/emotional/ /app/data/models/djl/emotional/
COPY --chown=openeden:openeden data/models/thymos-6d/ /app/data/models/thymos-6d/
RUN mkdir -p /app/data/runtime /app/data/exports /app/data/operator \
    && chown -R openeden:openeden /app/data \
    && chmod +x /opt/openeden/bin/server
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Dfile.encoding=UTF-8" \
    DJL_CACHE_DIR=/home/openeden/.djl.ai \
    OPENEDEN_THYMOS_DEVICE=cpu
USER openeden
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=180s --retries=5 \
    CMD curl --fail --silent --max-time 4 http://127.0.0.1:8080/health > /dev/null || exit 1
ENTRYPOINT ["/opt/openeden/bin/server"]
