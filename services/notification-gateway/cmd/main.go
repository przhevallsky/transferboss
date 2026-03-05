package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/prometheus/client_golang/prometheus/promhttp"
	"github.com/rs/zerolog"
	"github.com/rs/zerolog/log"
	"github.com/swiftpay/notification-gateway/internal/config"
	"github.com/swiftpay/notification-gateway/internal/consumer"
	"github.com/swiftpay/notification-gateway/internal/handler"
	_ "github.com/swiftpay/notification-gateway/internal/metrics"
	"github.com/swiftpay/notification-gateway/internal/sender"
)

func main() {
	cfg := config.Load()

	level, err := zerolog.ParseLevel(cfg.LogLevel)
	if err != nil {
		level = zerolog.InfoLevel
	}
	zerolog.SetGlobalLevel(level)
	log.Logger = zerolog.New(os.Stdout).With().Timestamp().Logger()

	log.Info().
		Strs("brokers", cfg.KafkaBrokers).
		Str("topic", cfg.Topic).
		Str("group", cfg.ConsumerGroup).
		Int("http_port", cfg.HTTPPort).
		Int("metrics_port", cfg.MetricsPort).
		Msg("starting notification gateway")

	// Health HTTP server
	healthMux := http.NewServeMux()
	healthMux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		fmt.Fprintln(w, "ok")
	})
	healthMux.HandleFunc("/readyz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		fmt.Fprintln(w, "ok")
	})

	healthSrv := &http.Server{
		Addr:    fmt.Sprintf(":%d", cfg.HTTPPort),
		Handler: healthMux,
	}

	go func() {
		log.Info().Int("port", cfg.HTTPPort).Msg("health HTTP server started")
		if err := healthSrv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal().Err(err).Msg("health HTTP server failed")
		}
	}()

	// Metrics HTTP server
	metricsMux := http.NewServeMux()
	metricsMux.Handle("/metrics", promhttp.Handler())

	metricsSrv := &http.Server{
		Addr:    fmt.Sprintf(":%d", cfg.MetricsPort),
		Handler: metricsMux,
	}

	go func() {
		log.Info().Int("port", cfg.MetricsPort).Msg("metrics HTTP server started")
		if err := metricsSrv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal().Err(err).Msg("metrics HTTP server failed")
		}
	}()

	// Delivery pipeline
	router := sender.NewRouter(sender.NewPushSender(), sender.NewSMSSender())
	h := handler.NewDeliveryHandler(router)
	c := consumer.New(cfg.KafkaBrokers, cfg.Topic, cfg.ConsumerGroup, h)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()

	go c.Run(ctx)

	<-ctx.Done()
	log.Info().Msg("shutdown signal received, draining...")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	if err := c.Close(); err != nil {
		log.Error().Err(err).Msg("consumer close error")
	}
	if err := healthSrv.Shutdown(shutdownCtx); err != nil {
		log.Error().Err(err).Msg("health HTTP server shutdown error")
	}
	if err := metricsSrv.Shutdown(shutdownCtx); err != nil {
		log.Error().Err(err).Msg("metrics HTTP server shutdown error")
	}

	log.Info().Msg("shutdown complete")
}
