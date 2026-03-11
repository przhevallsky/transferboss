.PHONY: build run test lint clean

build:
	go build -o bin/notification-gateway ./cmd

run: build
	./bin/notification-gateway

test:
	go test ./...

lint:
	go vet ./...

clean:
	rm -rf bin/
