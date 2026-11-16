package main

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func assertDelay(t *testing.T, got time.Duration, minimum, maximum time.Duration) {
	t.Helper()
	if got < minimum || got >= maximum {
		t.Fatalf("delay %v outside [%v, %v)", got, minimum, maximum)
	}
}

func TestWorkerRetriesBackOffAndResetAfterEstablishedSession(t *testing.T) {
	var r workerRetryState
	first := r.next(false, 0, false)
	assertDelay(t, first.delay, 500*time.Millisecond, time.Second)
	// A transient failure no longer waits the previous minimum five seconds.
	if first.refreshQuotaCreds {
		t.Fatal("ordinary failure requested quota credentials")
	}
	for _, base := range []time.Duration{time.Second, 2 * time.Second, 4 * time.Second, 8 * time.Second, 8 * time.Second} {
		assertDelay(t, r.next(false, 0, false).delay, base, base+500*time.Millisecond)
	}
	assertDelay(t, r.next(false, 2*time.Minute, false).delay, 500*time.Millisecond, time.Second)
}

func TestQuotaRetriesRefreshOnlyRepeatedAnonymousFailures(t *testing.T) {
	var r workerRetryState
	for i, base := range []time.Duration{2 * time.Second, 4 * time.Second, 8 * time.Second, 16 * time.Second, 30 * time.Second, 30 * time.Second} {
		p := r.next(true, 0, false)
		assertDelay(t, p.delay, base, base+time.Second)
		if p.refreshQuotaCreds != (i > 0) {
			t.Fatalf("quota attempt %d refresh=%v", i+1, p.refreshQuotaCreds)
		}
	}
	// A different error breaks the consecutive quota streak.
	r.next(false, 0, false)
	if p := r.next(true, 0, false); p.refreshQuotaCreds || p.delay >= 3*time.Second {
		t.Fatalf("quota streak not reset: %+v", p)
	}
	for i := 0; i < 4; i++ {
		if r.next(true, 0, true).refreshQuotaCreds {
			t.Fatal("account mode must not trigger unattended credential refresh")
		}
	}
}

func TestRetryWaitAndQueuedCredentialRequestCancelPromptly(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := waitForRetry(ctx, time.Hour); !errors.Is(err, context.Canceled) {
		t.Fatalf("retry wait: %v", err)
	}
	g := newCredentialRequestGate()
	if err := g.acquire(context.Background(), 0); err != nil {
		t.Fatal(err)
	}
	defer g.release()
	queuedCtx, queuedCancel := context.WithCancel(context.Background())
	defer queuedCancel()
	started := make(chan struct{})
	done := make(chan error, 1)
	go func() {
		close(started)
		done <- g.acquire(queuedCtx, 0)
	}()
	<-started
	select {
	case err := <-done:
		t.Fatalf("queued request returned before cancellation: %v", err)
	case <-time.After(20 * time.Millisecond):
	}
	queuedCancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("queued request: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("cancelled request waited for the active request to finish")
	}
}

func TestCredentialGateSerializesAndSpacesRequests(t *testing.T) {
	g := newCredentialRequestGate()
	if err := g.acquire(context.Background(), 0); err != nil {
		t.Fatal(err)
	}
	firstHeld := true
	acquired := make(chan time.Time, 1)
	releaseSecond := make(chan struct{})
	done := make(chan struct{})
	const interval = 60 * time.Millisecond
	go func() {
		defer close(done)
		if err := g.acquire(context.Background(), interval); err != nil {
			return
		}
		acquired <- time.Now()
		<-releaseSecond
		g.release()
	}()
	defer func() {
		if firstHeld {
			g.release()
		}
		close(releaseSecond)
		<-done
	}()
	select {
	case <-acquired:
		t.Fatal("two credential requests overlapped")
	case <-time.After(20 * time.Millisecond):
	}
	releasedAt := time.Now()
	g.release()
	firstHeld = false
	select {
	case at := <-acquired:
		if elapsed := at.Sub(releasedAt); elapsed < interval {
			t.Fatalf("spacing shortened: %v", elapsed)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("next request never acquired the gate")
	}
}

func TestCredentialGateCancellationDuringSpacingReleasesToken(t *testing.T) {
	g := newCredentialRequestGate()
	if err := g.acquire(context.Background(), 0); err != nil {
		t.Fatal(err)
	}
	g.release()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- g.acquire(ctx, time.Hour) }()
	deadline := time.Now().Add(time.Second)
	for len(g.token) == 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if len(g.token) == 0 {
		t.Fatal("request did not enter spacing")
	}
	cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("spacing: %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("spacing ignored cancellation")
	}
	if err := g.acquire(context.Background(), 0); err != nil {
		t.Fatal(err)
	}
	g.release()
}

func TestConcurrentQuotaRefreshFailuresShareCooldownOutcome(t *testing.T) {
	var gate credentialRefreshCooldown
	var calls atomic.Int32
	entered := make(chan struct{})
	finish := make(chan struct{})
	refresh := func() bool {
		if calls.Add(1) == 1 {
			close(entered)
		}
		<-finish
		return false
	}
	const workers = 9
	results := make(chan bool, workers)
	go func() { results <- gate.run(context.Background(), refresh) }()
	<-entered
	var started sync.WaitGroup
	started.Add(workers - 1)
	for i := 1; i < workers; i++ {
		go func() {
			started.Done()
			results <- gate.run(context.Background(), refresh)
		}()
	}
	started.Wait()
	close(finish)
	for i := 0; i < workers; i++ {
		if <-results {
			t.Fatal("failed refresh was reported as successful")
		}
	}
	if got := calls.Load(); got != 1 {
		t.Fatalf("concurrent failures issued %d refreshes, want one", got)
	}
	if gate.run(context.Background(), func() bool { t.Fatal("failed refresh cooldown ignored"); return true }) {
		t.Fatal("cooldown lost the failed outcome")
	}
	// Expiry permits a fresh attempt; callers then share its successful outcome.
	gate.lastFinished = time.Now().Add(-16 * time.Second)
	if !gate.run(context.Background(), func() bool { return true }) {
		t.Fatal("refresh stayed blocked after cooldown")
	}
	if !gate.run(context.Background(), func() bool { t.Fatal("successful refresh cooldown ignored"); return false }) {
		t.Fatal("cooldown lost the successful outcome")
	}
}
