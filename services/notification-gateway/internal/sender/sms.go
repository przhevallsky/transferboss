package sender

import (
	"context"

	"github.com/rs/zerolog/log"
)

type SMSSender struct{}

func NewSMSSender() *SMSSender {
	return &SMSSender{}
}

func (s *SMSSender) Send(ctx context.Context, n Notification) error {
	log.Info().
		Str("transfer_id", n.TransferID).
		Str("sender_id", n.SenderID).
		Str("message", n.Text).
		Msg("sending SMS notification via Twilio")
	return nil
}

func (s *SMSSender) Channel() string {
	return "sms"
}
