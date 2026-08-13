package main

import (
	"regexp"
	"testing"
	"time"
)

// Регулярки Android-стороны (TunnelManager.kt, обработчик строки
// [СТАТИСТИКА]). Формат строки — межпроцессный контракт между go_client и
// Kotlin, поэтому проверяем его целиком, а не только новое поле.
var (
	ktActiveRe  = regexp.MustCompile(`Активных:\s*(\d+)`)
	ktTrafficRe = regexp.MustCompile(`Трафик:\s*([\d.,]+)`)
	ktSilenceRe = regexp.MustCompile(`Тишина:\s*(\d+)`)
)

func TestSilenceFieldWithoutPong(t *testing.T) {
	s := NewStats()
	s.NoteRecv()

	// Сервер ни разу не ответил на keepalive: измерять тишину нечем, иначе
	// на старом wdtt-server простаивающий (но живой) туннель выглядел бы
	// мёртвым.
	if got := s.silenceField(); got != "n/a" {
		t.Fatalf("silenceField() без pong = %q, ожидалось \"n/a\"", got)
	}
}

func TestSilenceFieldReportsSeconds(t *testing.T) {
	s := NewStats()
	s.PongSeen.Store(true)
	s.LastRecvUnixMs.Store(time.Now().Add(-42 * time.Second).UnixMilli())

	got := s.silenceField()
	if got != "42" {
		t.Fatalf("silenceField() = %q, ожидалось \"42\"", got)
	}
}

func TestSilenceFieldNeverNegative(t *testing.T) {
	s := NewStats()
	s.PongSeen.Store(true)
	// Часы могли уйти назад — отрицательная тишина сломала бы регулярку
	// Android-стороны (\d+ не примет минус) и признак живости стал бы null.
	s.LastRecvUnixMs.Store(time.Now().Add(5 * time.Second).UnixMilli())

	if got := s.silenceField(); got != "0" {
		t.Fatalf("silenceField() при часах назад = %q, ожидалось \"0\"", got)
	}
}

// TestStatsLineParsedByAndroid — строка статистики должна разбираться теми же
// регулярками, что применяет TunnelManager.kt: добавление поля «Тишина» не
// должно ломать разбор уже существующих полей.
func TestStatsLineParsedByAndroid(t *testing.T) {
	s := NewStats()
	s.ActiveConnections.Store(9)
	s.PongSeen.Store(true)
	s.LastRecvUnixMs.Store(time.Now().Add(-7 * time.Second).UnixMilli())

	line := statsLine(s.ActiveConnections.Load(), 12.34, s.silenceField())

	if m := ktActiveRe.FindStringSubmatch(line); m == nil || m[1] != "9" {
		t.Fatalf("Активных не разобрано из %q: %v", line, m)
	}
	if m := ktTrafficRe.FindStringSubmatch(line); m == nil || m[1] != "12.34" {
		t.Fatalf("Трафик не разобран из %q: %v", line, m)
	}
	if m := ktSilenceRe.FindStringSubmatch(line); m == nil || m[1] != "7" {
		t.Fatalf("Тишина не разобрана из %q: %v", line, m)
	}
}

// Со старым сервером поле «Тишина» равно n/a — Android должен получить null
// (регулярка не совпадает) и откатиться на прежнюю эвристику, а не прочитать
// случайное число.
func TestStatsLineSilenceNotAvailable(t *testing.T) {
	line := statsLine(9, 1.0, "n/a")
	if m := ktSilenceRe.FindStringSubmatch(line); m != nil {
		t.Fatalf("Тишина: n/a не должна разбираться как число, получено %v", m)
	}
}
