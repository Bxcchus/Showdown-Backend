FROM golang:1.26.6-alpine@sha256:3889b425f035be855a72fb4755265311293b6d414521f0a519d819df32222d83 AS build

ARG ALERTMANAGER_VERSION=v0.34.0
ARG ALERTMANAGER_COMMIT=085f0ef7eb41da24cab8cd000f1345b6250f2edb
RUN apk add --no-cache git nodejs npm \
    && git clone --depth 1 --branch "${ALERTMANAGER_VERSION}" https://github.com/prometheus/alertmanager.git /src/alertmanager \
    && test "$(git -C /src/alertmanager rev-parse HEAD)" = "${ALERTMANAGER_COMMIT}"
WORKDIR /src/alertmanager
RUN go get golang.org/x/mod@v0.40.0 \
    && go mod tidy \
    && cd ui/app \
    && npm ci \
    && npm run build \
    && cd ../.. \
    && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/alertmanager ./cmd/alertmanager \
    && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/amtool ./cmd/amtool

FROM prom/alertmanager:v0.34.0@sha256:690c7b525f4367aa91f73e2f91c632206d32e97c6384bdbf2fb7a861b420340d

COPY --from=build /out/alertmanager /bin/alertmanager
COPY --from=build /out/amtool /bin/amtool
