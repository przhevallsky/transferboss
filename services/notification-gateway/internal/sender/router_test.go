package sender_test

import (
	"context"
	"testing"

	"github.com/swiftpay/notification-gateway/internal/sender"
)

type stubSender struct {
	ch string
}

func (s *stubSender) Send(_ context.Context, _ sender.Notification) error { return nil }
func (s *stubSender) Channel() string                                     { return s.ch }

func TestRouter_Get_Registered(t *testing.T) {
	push := &stubSender{ch: "push"}
	sms := &stubSender{ch: "sms"}
	r := sender.NewRouter(push, sms)

	if s, ok := r.Get("push"); !ok || s != push {
		t.Error("expected to find push sender")
	}
	if s, ok := r.Get("sms"); !ok || s != sms {
		t.Error("expected to find sms sender")
	}
}

func TestRouter_Get_Unknown(t *testing.T) {
	r := sender.NewRouter()

	if _, ok := r.Get("email"); ok {
		t.Error("expected email to be not found")
	}
}
