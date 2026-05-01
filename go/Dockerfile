FROM golang:1.26 AS build
WORKDIR /app
COPY go.mod .
COPY . .
RUN go mod tidy
RUN CGO_ENABLED=0 GOOS=linux go build -o benchmark .

FROM alpine:latest
RUN apk --no-cache add ca-certificates
WORKDIR /root/
COPY --from=build /app/benchmark .
ENTRYPOINT ["./benchmark"]
