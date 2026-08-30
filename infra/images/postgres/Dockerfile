FROM golang:1.26.6-alpine@sha256:3889b425f035be855a72fb4755265311293b6d414521f0a519d819df32222d83 AS gosu-build

ARG GOSU_VERSION=1.19
ARG GOSU_COMMIT=6456aaa0f3c854d199d0f037f068eb97515b7513
RUN apk add --no-cache git \
    && git clone --depth 1 --branch "${GOSU_VERSION}" https://github.com/tianon/gosu.git /src/gosu \
    && test "$(git -C /src/gosu rev-parse HEAD)" = "${GOSU_COMMIT}"
WORKDIR /src/gosu
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/gosu .

FROM postgres:17.11-alpine@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73

RUN apk upgrade --no-cache
COPY --from=gosu-build /out/gosu /usr/local/bin/gosu
