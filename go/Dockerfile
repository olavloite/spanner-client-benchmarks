FROM golang:1.26 AS build
WORKDIR /app

RUN apt-get update && apt-get install -y git

COPY build_app.sh .
COPY go.mod .
COPY go.sum .
COPY . .

ARG USE_RELEASED_VERSION=false
ARG CLIENT_BRANCH=main
ENV USE_RELEASED_VERSION=$USE_RELEASED_VERSION
ENV CLIENT_BRANCH=$CLIENT_BRANCH
ENV CGO_ENABLED=0
ENV GOOS=linux

RUN chmod +x build_app.sh && ./build_app.sh

FROM alpine:latest
RUN apk --no-cache add ca-certificates
WORKDIR /root/
COPY --from=build /app/benchmark-app ./benchmark
ENTRYPOINT ["./benchmark"]
