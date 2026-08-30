FROM golang:1.26.6-alpine@sha256:3889b425f035be855a72fb4755265311293b6d414521f0a519d819df32222d83 AS build

ARG POSTGRES_EXPORTER_VERSION=v0.20.1
ARG POSTGRES_EXPORTER_COMMIT=867fbcac31cd18c143e244190ea9168cca069827
RUN apk add --no-cache git \
    && git clone --depth 1 --branch "${POSTGRES_EXPORTER_VERSION}" https://github.com/prometheus-community/postgres_exporter.git /src/postgres_exporter \
    && test "$(git -C /src/postgres_exporter rev-parse HEAD)" = "${POSTGRES_EXPORTER_COMMIT}"
WORKDIR /src/postgres_exporter
RUN go get golang.org/x/net@v0.56.0 golang.org/x/text@v0.39.0 \
    && go mod tidy \
    && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/postgres_exporter ./cmd/postgres_exporter

FROM prometheuscommunity/postgres-exporter:v0.20.1@sha256:ac5ec343104fae0e2d84a27bb8d69b38430a11910c5382cad85d478d2bab713e

COPY --from=build /out/postgres_exporter /bin/postgres_exporter
