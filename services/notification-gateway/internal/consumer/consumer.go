// Package consumer provides a Kafka consumer loop for notification events.
// Known limitation: segmentio/kafka-go uses a simple round-robin group balancer
// and does not support CooperativeStickyAssignor. Rebalances will stop-the-world.
package consumer

import (
	"context"
	"encoding/json"

	"github.com/rs/zerolog/log"
	"github.com/segmentio/kafka-go"
	"github.com/swiftpay/notification-gateway/internal/handler"
	"github.com/swiftpay/notification-gateway/internal/metrics"
)

type Consumer struct {
	reader  *kafka.Reader
	handler handler.Handler
}

func New(brokers []string, topic, group string, h handler.Handler) *Consumer {
	r := kafka.NewReader(kafka.ReaderConfig{
		Brokers:  brokers,
		Topic:    topic,
		GroupID:  group,
		MinBytes: 1,
		MaxBytes: 10e6,
	})
	return &Consumer{reader: r, handler: h}
}

func (c *Consumer) Run(ctx context.Context) {
	log.Info().Str("topic", c.reader.Config().Topic).Msg("consumer started")
	for {
		msg, err := c.reader.FetchMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				log.Info().Msg("consumer stopping: context cancelled")
				return
			}
			log.Error().Err(err).Msg("failed to fetch message")
			continue
		}

		var event handler.NotificationEvent
		if err := json.Unmarshal(msg.Value, &event); err != nil {
			log.Error().Err(err).Str("key", string(msg.Key)).Msg("failed to unmarshal event")
			if commitErr := c.reader.CommitMessages(ctx, msg); commitErr != nil {
				log.Error().Err(commitErr).Msg("failed to commit poison message")
			}
			continue
		}

		if err := c.handler.Handle(ctx, event); err != nil {
			log.Error().Err(err).Str("transfer_id", event.TransferID).Msg("handler failed")
			continue
		}

		if err := c.reader.CommitMessages(ctx, msg); err != nil {
			log.Error().Err(err).Msg("failed to commit message")
		} else {
			metrics.MessagesProcessed.Inc()
		}
	}
}

func (c *Consumer) Close() error {
	return c.reader.Close()
}
