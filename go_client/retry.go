package main

import (
	"context"
	"math/rand"
	"sync"
	"time"
)

// The first retry should not pay the penalty for a persistent outage. Keep
// exponential backoff, jitter and the separate TURN Allocate gate under load.
// Ported from upstream 0957a53/d021e6e without the new server's pong dependency.
type workerRetryState struct {
	failures      int
	quotaFailures int
}

type workerRetryPlan struct {
	delay             time.Duration
	refreshQuotaCreds bool
}

func (r *workerRetryState) next(quota bool, sessionAge time.Duration, accountMode bool) workerRetryPlan {
	if sessionAge > time.Minute {
		r.failures = 0
		r.quotaFailures = 0
	}
	r.failures++
	if quota {
		r.quotaFailures++
		return workerRetryPlan{
			delay:             retryBackoff(r.quotaFailures, 2*time.Second, 30*time.Second, time.Second),
			refreshQuotaCreds: r.quotaFailures >= 2 && !accountMode,
		}
	}
	r.quotaFailures = 0
	return workerRetryPlan{delay: retryBackoff(r.failures, 500*time.Millisecond, 8*time.Second, 500*time.Millisecond)}
}

func retryBackoff(attempt int, first, maximum, jitter time.Duration) time.Duration {
	d := first
	for i := 1; i < attempt && d < maximum; i++ {
		d *= 2
	}
	if d > maximum {
		d = maximum
	}
	return d + time.Duration(rand.Int63n(int64(jitter)))
}

func waitForRetry(ctx context.Context, delay time.Duration) error {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return ctx.Err()
	}
}

// The token covers both spacing and the request itself. Waiting for another
// request must be cancellable too; a mutex cannot be revoked by Stop.
type credentialRequestGate struct {
	token        chan struct{}
	lastFinished time.Time
}

func newCredentialRequestGate() *credentialRequestGate {
	return &credentialRequestGate{token: make(chan struct{}, 1)}
}

func (g *credentialRequestGate) acquire(ctx context.Context, interval time.Duration) error {
	select {
	case <-ctx.Done():
		return ctx.Err()
	case g.token <- struct{}{}:
	}
	if err := ctx.Err(); err != nil {
		<-g.token
		return err
	}
	if !g.lastFinished.IsZero() {
		if err := waitForRetry(ctx, interval-time.Since(g.lastFinished)); err != nil {
			<-g.token
			return err
		}
	}
	return nil
}

func (g *credentialRequestGate) release() {
	g.lastFinished = time.Now()
	<-g.token
}

// Workers in one group share the outcome of a refresh, including failures.
// A failed VK request must not immediately be repeated by every queued worker.
type credentialRefreshCooldown struct {
	mu           sync.Mutex
	lastFinished time.Time
	succeeded    bool
}

func (g *credentialRefreshCooldown) run(ctx context.Context, refresh func() bool) bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	if ctx.Err() != nil {
		return false
	}
	if !g.lastFinished.IsZero() && time.Since(g.lastFinished) < 15*time.Second {
		return g.succeeded
	}
	g.succeeded = refresh()
	g.lastFinished = time.Now()
	return g.succeeded
}
