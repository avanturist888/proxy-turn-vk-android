package main

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

// resetCredsState очищает глобальное состояние между тестами.
func resetCredsState(t *testing.T) {
	t.Helper()
	credentialsStore.mu.Lock()
	credentialsStore.caches = make(map[int]*StreamCredentialsCache)
	credentialsStore.mu.Unlock()
	setCredsStorePath("")
}

func TestCredsStoreRoundTrip(t *testing.T) {
	resetCredsState(t)
	defer resetCredsState(t)

	path := filepath.Join(t.TempDir(), "creds.json")
	setCredsStorePath(path)

	const streamID = 0
	cache := getStreamCache(streamID)
	cache.mutex.Lock()
	cache.creds = TurnCredentials{
		Username:    "user1",
		Password:    "pass1",
		ServerAddrs: []string{"1.2.3.4:3478", "5.6.7.8:3478"},
		ExpiresAt:   time.Now().Add(5 * time.Minute),
		Link:        "hash1",
	}
	cache.mutex.Unlock()
	saveCredsStore()

	// Перезапуск процесса: память пуста, файл на месте.
	credentialsStore.mu.Lock()
	credentialsStore.caches = make(map[int]*StreamCredentialsCache)
	credentialsStore.mu.Unlock()

	loadCredsStore()

	restored := getStreamCache(streamID)
	restored.mutex.RLock()
	defer restored.mutex.RUnlock()
	if restored.creds.Link != "hash1" || restored.creds.Username != "user1" {
		t.Fatalf("креды не восстановлены: %+v", restored.creds)
	}
	if len(restored.creds.ServerAddrs) != 2 {
		t.Fatalf("потеряны адреса TURN: %+v", restored.creds.ServerAddrs)
	}
}

// Истёкшие креды VK бесполезны: подняться на них не выйдет, а принятые за
// действительные они увели бы воркеров в заведомо провальные попытки вместо
// честного похода за новыми.
func TestCredsStoreDropsExpired(t *testing.T) {
	resetCredsState(t)
	defer resetCredsState(t)

	path := filepath.Join(t.TempDir(), "creds.json")
	setCredsStorePath(path)

	cache := getStreamCache(0)
	cache.mutex.Lock()
	cache.creds = TurnCredentials{
		Username:    "stale",
		Password:    "stale",
		ServerAddrs: []string{"1.2.3.4:3478"},
		ExpiresAt:   time.Now().Add(-time.Minute),
		Link:        "hash1",
	}
	cache.mutex.Unlock()
	saveCredsStore()

	credentialsStore.mu.Lock()
	credentialsStore.caches = make(map[int]*StreamCredentialsCache)
	credentialsStore.mu.Unlock()

	loadCredsStore()

	restored := getStreamCache(0)
	restored.mutex.RLock()
	defer restored.mutex.RUnlock()
	if restored.creds.Link != "" {
		t.Fatalf("истёкшие креды не должны восстанавливаться: %+v", restored.creds)
	}
}

// Отсутствие и повреждение файла — штатные ситуации: клиент обязан
// продолжить работу и просто сходить за кредами заново.
func TestCredsStoreSurvivesBadFile(t *testing.T) {
	resetCredsState(t)
	defer resetCredsState(t)

	setCredsStorePath(filepath.Join(t.TempDir(), "missing.json"))
	loadCredsStore()

	bad := filepath.Join(t.TempDir(), "broken.json")
	if err := os.WriteFile(bad, []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	setCredsStorePath(bad)
	loadCredsStore()

	credentialsStore.mu.RLock()
	defer credentialsStore.mu.RUnlock()
	if len(credentialsStore.caches) != 0 {
		t.Fatalf("из битого файла ничего не должно восстановиться, кешей=%d", len(credentialsStore.caches))
	}
}
