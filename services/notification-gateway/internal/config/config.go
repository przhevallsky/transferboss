package config

import (
	"os"
	"strconv"
	"strings"
)

type Config struct {
	KafkaBrokers  []string
	Topic         string
	ConsumerGroup string
	LogLevel      string
	HTTPPort      int
}

func Load() Config {
	return Config{
		KafkaBrokers:  getEnvSlice("KAFKA_BROKERS", []string{"localhost:9092"}),
		Topic:         getEnv("NOTIFICATION_TOPIC", "notification.delivery"),
		ConsumerGroup: getEnv("CONSUMER_GROUP", "notification-delivery-consumer"),
		LogLevel:      getEnv("LOG_LEVEL", "info"),
		HTTPPort:      getEnvInt("HTTP_PORT", 8085),
	}
}

func getEnv(key, defaultVal string) string {
	if val := os.Getenv(key); val != "" {
		return val
	}
	return defaultVal
}

func getEnvSlice(key string, defaultVal []string) []string {
	if val := os.Getenv(key); val != "" {
		return strings.Split(val, ",")
	}
	return defaultVal
}

func getEnvInt(key string, defaultVal int) int {
	if val := os.Getenv(key); val != "" {
		if i, err := strconv.Atoi(val); err == nil {
			return i
		}
	}
	return defaultVal
}
