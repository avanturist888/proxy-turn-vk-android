package main

import (
	"testing"
	"time"
)

func TestProbeWakesAllSubscribers(t *testing.T) {
	p := newPathProbe()

	// Каждая сессия ждёт пробу самостоятельно, поэтому сигнал обязан быть
	// широковещательным: разбудить нужно все, а не одну случайную.
	const subscribers = 9
	woken := make(chan struct{}, subscribers)
	for i := 0; i < subscribers; i++ {
		ch := p.wait()
		go func() {
			<-ch
			woken <- struct{}{}
		}()
	}

	p.request()

	for i := 0; i < subscribers; i++ {
		select {
		case <-woken:
		case <-time.After(2 * time.Second):
			t.Fatalf("разбужено только %d подписчиков из %d", i, subscribers)
		}
	}
}

func TestProbeRearmsAfterRequest(t *testing.T) {
	p := newPathProbe()

	first := p.wait()
	p.request()

	select {
	case <-first:
	case <-time.After(time.Second):
		t.Fatal("request() должен закрыть текущий канал")
	}

	// Следующая проба должна ждать нового request, иначе сессия увидела бы
	// «пробу» мгновенно и оборвалась бы по несостоявшейся проверке.
	second := p.wait()
	select {
	case <-second:
		t.Fatal("после request() канал следующей пробы не должен быть закрыт")
	default:
	}

	p.request()
	select {
	case <-second:
	case <-time.After(time.Second):
		t.Fatal("повторный request() должен будить подписчиков нового поколения")
	}
}
