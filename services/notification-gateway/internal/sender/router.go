package sender

type Router struct {
	senders map[string]Sender
}

func NewRouter(senders ...Sender) *Router {
	m := make(map[string]Sender, len(senders))
	for _, s := range senders {
		m[s.Channel()] = s
	}
	return &Router{senders: m}
}

func (r *Router) Get(channel string) (Sender, bool) {
	s, ok := r.senders[channel]
	return s, ok
}
