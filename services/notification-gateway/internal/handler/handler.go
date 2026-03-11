package handler

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/rs/zerolog/log"
	"github.com/swiftpay/notification-gateway/internal/metrics"
	"github.com/swiftpay/notification-gateway/internal/sender"
)

type NotificationEvent struct {
	TransferID       string   `json:"transfer_id"`
	SenderID         string   `json:"sender_id"`
	EventType        string   `json:"event_type"`
	NotificationText string   `json:"notification_text"`
	Channels         []string `json:"channels"`
}

type Handler interface {
	Handle(ctx context.Context, event NotificationEvent) error
}

type DeliveryHandler struct {
	router *sender.Router
}

func NewDeliveryHandler(router *sender.Router) *DeliveryHandler {
	return &DeliveryHandler{router: router}
}

func (h *DeliveryHandler) Handle(ctx context.Context, event NotificationEvent) error {
	log.Info().
		Str("transfer_id", event.TransferID).
		Str("event_type", event.EventType).
		Strs("channels", event.Channels).
		Msg("processing notification event")

	var (
		attempted int
		failed    int
		errs      []error
	)

	for _, ch := range event.Channels {
		start := time.Now()
		n := sender.Notification{
			TransferID: event.TransferID,
			SenderID:   event.SenderID,
			EventType:  event.EventType,
			Text:       event.NotificationText,
			Channel:    ch,
		}

		s, ok := h.router.Get(ch)
		if !ok {
			log.Warn().Str("channel", ch).Str("transfer_id", event.TransferID).Msg("unknown delivery channel, skipping")
			continue
		}

		attempted++
		err := s.Send(ctx, n)
		duration := time.Since(start).Seconds()
		metrics.DeliveryDuration.WithLabelValues(ch).Observe(duration)

		if err != nil {
			failed++
			errs = append(errs, fmt.Errorf("channel %s: %w", ch, err))
			metrics.DeliveryTotal.WithLabelValues(ch, "failure").Inc()
			log.Error().Err(err).Str("channel", ch).Str("transfer_id", event.TransferID).Msg("delivery failed")
		} else {
			metrics.DeliveryTotal.WithLabelValues(ch, "success").Inc()
		}
	}

	if attempted > 0 && failed == attempted {
		return fmt.Errorf("all %d delivery channels failed: %w", failed, errors.Join(errs...))
	}

	return nil
}
