package metrics

import "github.com/prometheus/client_golang/prometheus"

var (
	DeliveryTotal = prometheus.NewCounterVec(
		prometheus.CounterOpts{
			Name: "notification_delivery_total",
			Help: "Total number of notification deliveries by channel and status",
		},
		[]string{"channel", "status"},
	)

	DeliveryDuration = prometheus.NewHistogramVec(
		prometheus.HistogramOpts{
			Name:    "notification_delivery_duration_seconds",
			Help:    "Duration of notification delivery by channel",
			Buckets: prometheus.DefBuckets,
		},
		[]string{"channel"},
	)

	MessagesProcessed = prometheus.NewCounter(
		prometheus.CounterOpts{
			Name: "notification_consumer_messages_processed_total",
			Help: "Total number of Kafka messages processed by the consumer",
		},
	)
)

func init() {
	prometheus.MustRegister(DeliveryTotal, DeliveryDuration, MessagesProcessed)
}
