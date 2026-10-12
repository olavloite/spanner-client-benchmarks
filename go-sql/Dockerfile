FROM golang:1.27 AS build
WORKDIR /app

RUN apt-get update && apt-get install -y git

COPY build_app.sh .
COPY go.mod .
COPY go.sum .
COPY . .

ARG USE_RELEASED_VERSION=false
ARG CLIENT_BRANCH=main
ARG CLIENT_REPO=""
ENV USE_RELEASED_VERSION=$USE_RELEASED_VERSION
ENV CLIENT_BRANCH=$CLIENT_BRANCH
ENV CLIENT_REPO=$CLIENT_REPO
ENV CGO_ENABLED=0
ENV GOOS=linux

RUN chmod +x build_app.sh && ./build_app.sh
RUN cd generator && go build -o /app/workload-generator .

FROM alpine:latest
RUN apk --no-cache add ca-certificates
WORKDIR /root/
COPY --from=build /app/benchmark-app ./benchmark
COPY --from=build /app/workload-generator /usr/local/bin/workload-generator
COPY entrypoint.sh ./entrypoint.sh
RUN chmod +x ./entrypoint.sh
ENTRYPOINT ["./entrypoint.sh", "./benchmark"]
