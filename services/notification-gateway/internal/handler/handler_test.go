package handler_test

import (
	"context"
	"errors"
	"testing"

	"github.com/swiftpay/notification-gateway/internal/handler"
	"github.com/swiftpay/notification-gateway/internal/sender"
)

// fakeSender records calls and optionally returns an error.
type fakeSender struct {
	channel string
	calls   []sender.Notification
	err     error
}

func (f *fakeSender) Send(_ context.Context, n sender.Notification) error {
	f.calls = append(f.calls, n)
	return f.err
}

func (f *fakeSender) Channel() string { return f.channel }

func TestDeliveryHandler_SingleChannel(t *testing.T) {
	push := &fakeSender{channel: "push"}
	router := sender.NewRouter(push)
	h := handler.NewDeliveryHandler(router)

	event := handler.NotificationEvent{
		TransferID:       "txn-001",
		SenderID:         "user-42",
		EventType:        "transfer.completed",
		NotificationText: "Your transfer is complete",
		Channels:         []string{"push"},
	}

	if err := h.Handle(context.Background(), event); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if len(push.calls) != 1 {
		t.Fatalf("expected 1 push call, got %d", len(push.calls))
	}
	if push.calls[0].TransferID != "txn-001" {
		t.Errorf("expected transfer_id txn-001, got %s", push.calls[0].TransferID)
	}
	if push.calls[0].Channel != "push" {
		t.Errorf("expected channel push, got %s", push.calls[0].Channel)
	}
}

func TestDeliveryHandler_MultiChannel(t *testing.T) {
	push := &fakeSender{channel: "push"}
	sms := &fakeSender{channel: "sms"}
	router := sender.NewRouter(push, sms)
	h := handler.NewDeliveryHandler(router)

	event := handler.NotificationEvent{
		TransferID:       "txn-002",
		SenderID:         "user-99",
		EventType:        "transfer.failed",
		NotificationText: "Transfer failed",
		Channels:         []string{"push", "sms"},
	}

	if err := h.Handle(context.Background(), event); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if len(push.calls) != 1 {
		t.Fatalf("expected 1 push call, got %d", len(push.calls))
	}
	if len(sms.calls) != 1 {
		t.Fatalf("expected 1 sms call, got %d", len(sms.calls))
	}
}

func TestDeliveryHandler_UnknownChannel(t *testing.T) {
	push := &fakeSender{channel: "push"}
	router := sender.NewRouter(push)
	h := handler.NewDeliveryHandler(router)

	event := handler.NotificationEvent{
		TransferID: "txn-003",
		Channels:   []string{"email"},
	}

	// Unknown channel is skipped, no error returned
	if err := h.Handle(context.Background(), event); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(push.calls) != 0 {
		t.Errorf("push should not be called for email channel")
	}
}

func TestDeliveryHandler_PartialFailure_ReturnsNil(t *testing.T) {
	push := &fakeSender{channel: "push", err: errors.New("FCM unavailable")}
	sms := &fakeSender{channel: "sms"}
	router := sender.NewRouter(push, sms)
	h := handler.NewDeliveryHandler(router)

	event := handler.NotificationEvent{
		TransferID:       "txn-004",
		NotificationText: "Transfer update",
		Channels:         []string{"push", "sms"},
	}

	// Partial success is OK — no error returned
	if err := h.Handle(context.Background(), event); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if len(push.calls) != 1 {
		t.Fatalf("expected 1 push call, got %d", len(push.calls))
	}
	if len(sms.calls) != 1 {
		t.Fatalf("expected 1 sms call (despite push failure), got %d", len(sms.calls))
	}
}

func TestDeliveryHandler_AllChannelsFail_ReturnsError(t *testing.T) {
	push := &fakeSender{channel: "push", err: errors.New("FCM unavailable")}
	sms := &fakeSender{channel: "sms", err: errors.New("SMS gateway down")}
	router := sender.NewRouter(push, sms)
	h := handler.NewDeliveryHandler(router)

	event := handler.NotificationEvent{
		TransferID:       "txn-005",
		NotificationText: "Transfer update",
		Channels:         []string{"push", "sms"},
	}

	err := h.Handle(context.Background(), event)
	if err == nil {
		t.Fatal("expected error when all channels fail, got nil")
	}

	if len(push.calls) != 1 {
		t.Fatalf("expected 1 push call, got %d", len(push.calls))
	}
	if len(sms.calls) != 1 {
		t.Fatalf("expected 1 sms call, got %d", len(sms.calls))
	}
}

func TestDeliveryHandler_SingleChannelFails_ReturnsError(t *testing.T) {
	push := &fakeSender{channel: "push", err: errors.New("FCM unavailable")}
	router := sender.NewRouter(push)
	h := handler.NewDeliveryHandler(router)

	event := handler.NotificationEvent{
		TransferID: "txn-006",
		Channels:   []string{"push"},
	}

	err := h.Handle(context.Background(), event)
	if err == nil {
		t.Fatal("expected error when single channel fails, got nil")
	}
}
