# Grafana Tempo 3.1.1 on Alpine, built exactly like the Grow managed-stack template `grafana-tempo`
# (platform agent-core/src/migrations/seeds/gro1285-observability-templates.ts, commit 9dc61370b):
#   base alpine:3.24.4, official release tarball verified by SHA-256 per arch, non-root user 10001,
#   config /etc/tempo/tempo.yaml (byte-identical to the template's TEMPO_CONFIG JSON).
# The stack catalog records the upstream tag grafana/tempo:3.1.1; that image is distroless, which is
# why the platform (and this fixture) re-packages the same release binary on Alpine.
FROM alpine:3.24.4

ARG TEMPO_VERSION=3.1.1
ARG TEMPO_SHA256_AMD64=cc7efc370d2e5cfdf93792060da4ea9d6c2a3aae348ccc95cbaaaf3393e1d339
ARG TEMPO_SHA256_ARM64=3e2fcb4836f9baca220583ebcf487bc2917bcaa79baeb17d870c8e1d94a7326b

RUN ARCH="$(uname -m | sed -e s/aarch64/arm64/ -e s/x86_64/amd64/)" \
 && case "$ARCH" in amd64) SUM=${TEMPO_SHA256_AMD64} ;; arm64) SUM=${TEMPO_SHA256_ARM64} ;; *) exit 1 ;; esac \
 && wget -qO /tmp/tempo.tgz "https://github.com/grafana/tempo/releases/download/v${TEMPO_VERSION}/tempo_${TEMPO_VERSION}_linux_${ARCH}.tar.gz" \
 && echo "$SUM  /tmp/tempo.tgz" | sha256sum -c - \
 && tar -xzf /tmp/tempo.tgz -C /usr/local/bin tempo \
 && rm -f /tmp/tempo.tgz \
 && addgroup -S -g 10001 tempo && adduser -S -D -H -u 10001 -G tempo tempo \
 && mkdir -p /etc/tempo /var/tempo

COPY tempo.yaml /etc/tempo/tempo.yaml

WORKDIR /var/tempo
EXPOSE 3200 4317 4318

# Same start command as the template. TEMPO_EXTRA_ARGS lets the compose file start a second,
# multi-tenant instance (-multitenancy.enabled=true) from the same image.
ENV TEMPO_EXTRA_ARGS=""
CMD ["/bin/sh", "-c", "chown -R 10001:10001 /var/tempo && exec su -s /bin/sh tempo -c \"/usr/local/bin/tempo -config.file=/etc/tempo/tempo.yaml $TEMPO_EXTRA_ARGS\""]
