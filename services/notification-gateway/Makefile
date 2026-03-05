.PHONY: build run test lint

build:
	go build -o bin/notification-gateway ./cmd

run: build
	./bin/notification-gateway

test:
	go test ./...

lint:
	go vet ./...
