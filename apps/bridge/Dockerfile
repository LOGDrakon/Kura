# Image du Kura Bridge (mode base SQL). Construire depuis apps/bridge :
#   docker build -t kura-bridge .
#   docker run -d -p 8787:8787 -v /etc/kura:/etc/kura:ro -e KURA_TOKEN=… kura-bridge
FROM golang:1.26 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
ARG VERSION=dev
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" -o /kura-bridge ./cmd/kura-bridge

# Image minimale, sans shell, utilisateur non root. Pour le mode script, partez d'une image
# contenant votre interpréteur (python:3-slim…) et copiez-y /kura-bridge.
FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /kura-bridge /kura-bridge
ENV KURA_BRIDGE_CONFIG=/etc/kura/kura-bridge.yaml
EXPOSE 8787
ENTRYPOINT ["/kura-bridge"]
