package main

import (
	"context"
	"testing"
)

func TestEpochRotateEndsOnlyPreviousGeneration(t *testing.T) {
	e := newSessionEpoch(context.Background())

	first := e.current()
	if first.Err() != nil {
		t.Fatal("контекст первого поколения не должен быть отменён")
	}

	e.rotate()

	if first.Err() == nil {
		t.Fatal("rotate() обязан оборвать сессии прошлого поколения")
	}
	second := e.current()
	if second.Err() != nil {
		t.Fatal("после rotate() новое поколение должно быть живым")
	}
	if second == first {
		t.Fatal("rotate() должен выдать новый контекст, а не переиспользовать прежний")
	}
}

// Отмена контекста процесса (STOP) обязана гасить и текущее поколение, иначе
// воркеры продолжат крутить сессии после запроса на завершение.
func TestEpochFollowsProcessShutdown(t *testing.T) {
	parent, cancel := context.WithCancel(context.Background())
	e := newSessionEpoch(parent)

	cancel()

	if e.current().Err() == nil {
		t.Fatal("завершение процесса должно отменять текущее поколение")
	}
}

// Поколение, созданное после завершения процесса, тоже должно быть мёртвым:
// иначе воркер, успевший вызвать rotate() на выходе, поднял бы новую сессию
// уже после STOP.
func TestEpochRotateAfterShutdownStaysDone(t *testing.T) {
	parent, cancel := context.WithCancel(context.Background())
	e := newSessionEpoch(parent)

	cancel()
	e.rotate()

	if e.current().Err() == nil {
		t.Fatal("после завершения процесса rotate() не должен воскрешать поколение")
	}
}
