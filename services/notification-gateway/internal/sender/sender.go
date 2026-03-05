package sender

import "context"

type Notification struct {
	TransferID string
	SenderID   string
	EventType  string
	Text       string
	Channel    string
}

type Sender interface {
	Send(ctx context.Context, notification Notification) error
	Channel() string
}
