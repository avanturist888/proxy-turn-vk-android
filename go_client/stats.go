package main

import (
	"fmt"
	"log"
	"strconv"
	"sync/atomic"
	"time"
)

type Stats struct {
	TotalBytesUp      atomic.Int64
	TotalBytesDown    atomic.Int64
	ActiveConnections atomic.Int32

	// LastRecvUnixMs — когда любой воркер последний раз получил хоть что-то
	// от сервера, включая pong на keepalive. Отдельно от TotalBytesDown:
	// pong отбрасывается в Reader'е и до диспетчера (а значит и до счётчика
	// трафика) не доходит, но именно он доказывает, что путь до сервера жив
	// на простаивающем туннеле.
	LastRecvUnixMs atomic.Int64
	// PongSeen — сервер отвечает на keepalive. У старых серверов pong нет,
	// и тогда тишина не является признаком обрыва (см. session.go).
	PongSeen atomic.Bool
}

func NewStats() *Stats {
	return &Stats{}
}

// NoteRecv отмечает факт приёма пакета от сервера.
func (s *Stats) NoteRecv() {
	s.LastRecvUnixMs.Store(time.Now().UnixMilli())
}

// silenceField — значение поля «Тишина» в строке статистики: сколько секунд
// от сервера не приходило ничего. Android-сторона использует его как признак
// живости транспорта вместо самого факта появления строки в логе (строка
// печатается по таймеру и живёт, пока жив процесс, — см. TunnelManager.kt).
// Без pong измерять тишину бессмысленно, поэтому отдаём "n/a".
func (s *Stats) silenceField() string {
	if !s.PongSeen.Load() {
		return "n/a"
	}
	last := s.LastRecvUnixMs.Load()
	if last == 0 {
		return "n/a"
	}
	secs := (time.Now().UnixMilli() - last) / 1000
	if secs < 0 {
		secs = 0
	}
	return strconv.FormatInt(secs, 10)
}

// statsLine собирает строку статистики. Её разбирает Android-сторона
// (TunnelManager.kt) регулярками по именам полей, поэтому формат — контракт
// между процессами; см. stats_test.go.
func statsLine(active int32, totalMB float64, silence string) string {
	return fmt.Sprintf("Активных: %d | Трафик: %.2f МБ | Тишина: %s", active, totalMB, silence)
}

func (s *Stats) RunLoop(shutdown <-chan struct{}) {
	ticker := time.NewTicker(3 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-shutdown:
			return
		case <-ticker.C:
			active := s.ActiveConnections.Load()
			up := s.TotalBytesUp.Load()
			down := s.TotalBytesDown.Load()
			totalMB := float64(up+down) / (1024.0 * 1024.0)

			log.Printf("[СТАТИСТИКА] %s", statsLine(active, totalMB, s.silenceField()))
		}
	}
}
