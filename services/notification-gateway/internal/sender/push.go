package sender

import (
	"context"

	"github.com/rs/zerolog/log"
)

type PushSender struct{}

func NewPushSender() *PushSender {
	return &PushSender{}
}

func (s *PushSender) Send(ctx context.Context, n Notification) error {
	log.Info().
		Str("transfer_id", n.TransferID).
		Str("sender_id", n.SenderID).
		Str("message", n.Text).
		Msg("sending push notification via FCM")
	return nil
}

func (s *PushSender) Channel() string {
	return "push"
}
