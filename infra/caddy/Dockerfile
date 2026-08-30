FROM golang:1.26.6-alpine@sha256:3889b425f035be855a72fb4755265311293b6d414521f0a519d819df32222d83 AS caddy-build

ARG CADDY_VERSION=v2.11.4
ARG CADDY_COMMIT=e2eee6a7fce366321294c9c2a79f3146891dcbdf
RUN apk add --no-cache git \
    && git clone --depth 1 --branch "${CADDY_VERSION}" https://github.com/caddyserver/caddy.git /src/caddy \
    && cd /src/caddy \
    && test "$(git rev-parse HEAD)" = "${CADDY_COMMIT}" \
    && go get golang.org/x/net@v0.56.0 \
    && go get golang.org/x/text@v0.39.0 \
    && go get google.golang.org/grpc@v1.82.1 \
    && go mod tidy \
    && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/caddy ./cmd/caddy

FROM alpine:3.23.5@sha256:fd791d74b68913cbb027c6546007b3f0d3bc45125f797758156952bc2d6daf40

ENV XDG_CONFIG_HOME=/config \
    XDG_DATA_HOME=/data
RUN apk upgrade --no-cache \
    && apk add --no-cache ca-certificates \
    && addgroup -S pinkward && adduser -S -G pinkward pinkward \
    && mkdir -p /data/caddy /config/caddy /etc/caddy \
    && chown -R pinkward:pinkward /data /config /etc/caddy
COPY --from=caddy-build /out/caddy /usr/bin/caddy
USER pinkward
ENTRYPOINT ["caddy"]
CMD ["run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"]
