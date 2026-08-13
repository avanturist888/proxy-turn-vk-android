package main

import (
	"context"
	"sync"
)

// sessionEpoch — поколение транспортных сессий.
//
// Смена сети одномоментно убивает все TURN-аллокации: сокеты остаются с
// src-адресом исчезнувшего интерфейса. Раньше единственным способом это
// пережить был перезапуск всего процесса из Kotlin (killProcess + start), но
// он выбрасывает и то, что смены сети переживает: кеш TURN-кредов VK,
// диспетчер вместе с выученным адресом локального WireGuard и TUN-fd. Новый
// заход в VK-звонок за кредами — это лишний риск капчи и flood control, а при
// частых переключениях сети он повторяется на каждое.
//
// Поколение отделяет «жизнь процесса» от «жизни текущего набора сессий»:
// rotate() рвёт все сессии разом, оставляя процесс и его кеши нетронутыми, а
// воркеры сразу поднимают новые поверх уже актуальных маршрутов.
type sessionEpoch struct {
	parent context.Context
	mu     sync.Mutex
	ctx    context.Context
	cancel context.CancelFunc
}

func newSessionEpoch(parent context.Context) *sessionEpoch {
	e := &sessionEpoch{parent: parent}
	e.ctx, e.cancel = context.WithCancel(parent)
	return e
}

// current возвращает контекст текущего поколения. Его отмена означает «эта
// сессия больше не нужна», а не «процесс завершается» — воркер по ней
// переподключается немедленно, без обычной паузы между попытками.
func (e *sessionEpoch) current() context.Context {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.ctx
}

// rotate завершает текущее поколение и открывает следующее.
func (e *sessionEpoch) rotate() {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.cancel != nil {
		e.cancel()
	}
	e.ctx, e.cancel = context.WithCancel(e.parent)
}
