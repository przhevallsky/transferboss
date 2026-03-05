package handler

import (
	"context"

	"github.com/rs/zerolog/log"
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

type LoggingHandler struct{}

func NewLoggingHandler() *LoggingHandler {
	return &LoggingHandler{}
}

func (h *LoggingHandler) Handle(ctx context.Context, event NotificationEvent) error {
	log.Info().
		Str("transfer_id", event.TransferID).
		Str("sender_id", event.SenderID).
		Str("event_type", event.EventType).
		Strs("channels", event.Channels).
		Msg("notification event received")
	return nil
}
