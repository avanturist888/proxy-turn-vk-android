package main

import (
	"testing"
	"time"
)

func TestBackoffDelayGrowsAndCaps(t *testing.T) {
	// Джиттер добавляет до 2с, поэтому проверяем диапазоны, а не точные
	// значения.
	cases := []struct {
		attempt  int
		min, max time.Duration
	}{
		{1, 5 * time.Second, 7 * time.Second},
		{2, 10 * time.Second, 12 * time.Second},
		{3, 20 * time.Second, 22 * time.Second},
		{4, 40 * time.Second, 42 * time.Second},
		{5, time.Minute, 62 * time.Second},
		{50, time.Minute, 62 * time.Second},
	}
	for _, c := range cases {
		got := backoffDelay(c.attempt)
		if got < c.min || got > c.max {
			t.Errorf("backoffDelay(%d) = %s, ожидалось от %s до %s", c.attempt, got, c.min, c.max)
		}
	}
}

// Нулевой и отрицательный номер попытки не должны давать нулевую паузу:
// иначе ошибка в вызывающем коде превратилась бы в шквал запросов к VK.
func TestBackoffDelayHandlesBadAttempt(t *testing.T) {
	for _, attempt := range []int{0, -1, -100} {
		if got := backoffDelay(attempt); got < 5*time.Second {
			t.Errorf("backoffDelay(%d) = %s, ожидалась пауза не меньше 5с", attempt, got)
		}
	}
}
