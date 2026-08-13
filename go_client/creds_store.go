package main

import (
	"encoding/json"
	"log"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// Кеш TURN-кредов, переживающий перезапуск процесса.
//
// Анонимные креды VK живут около десяти минут и хранились только в памяти,
// поэтому любой перезапуск go_client означал новый заход в звонок — а это
// капча и flood control, тем вероятнее, чем чаще переподключения. Режим
// аккаунта от этого защищён файлом -vk-creds-file; здесь тот же приём для
// анонимного.
//
// Файл лежит в приватной директории приложения и содержит недолговечные
// креды, которые VK и так отдаёт по хешу звонка.

// persistedCreds — одна запись кеша на диске.
type persistedCreds struct {
	CacheID     int       `json:"cacheId"`
	Link        string    `json:"link"`
	Username    string    `json:"username"`
	Password    string    `json:"password"`
	ServerAddrs []string  `json:"servers"`
	ExpiresAt   time.Time `json:"expiresAt"`
}

var credsStoreFile struct {
	mu   sync.Mutex
	path string
}

// setCredsStorePath включает персистентный кеш. Пустой путь оставляет
// прежнее поведение — только память.
func setCredsStorePath(path string) {
	credsStoreFile.mu.Lock()
	defer credsStoreFile.mu.Unlock()
	credsStoreFile.path = path
}

func credsStorePath() string {
	credsStoreFile.mu.Lock()
	defer credsStoreFile.mu.Unlock()
	return credsStoreFile.path
}

// loadCredsStore поднимает в память ещё не истёкшие записи. Вызывается один
// раз на старте, до запуска воркеров.
func loadCredsStore() {
	path := credsStorePath()
	if path == "" {
		return
	}

	data, err := os.ReadFile(path)
	if err != nil {
		if !os.IsNotExist(err) {
			log.Printf("[КРЕДЫ] Кеш не прочитан (%v), продолжаем без него", err)
		}
		return
	}

	var entries []persistedCreds
	if err := json.Unmarshal(data, &entries); err != nil {
		log.Printf("[КРЕДЫ] Кеш повреждён (%v), продолжаем без него", err)
		return
	}

	restored := 0
	now := time.Now()
	for _, e := range entries {
		if e.Link == "" || len(e.ServerAddrs) == 0 || !now.Before(e.ExpiresAt) {
			continue
		}
		cache := getStreamCache(e.CacheID * streamsPerCache)
		cache.mutex.Lock()
		cache.creds = TurnCredentials{
			Username:    e.Username,
			Password:    e.Password,
			ServerAddrs: cloneStringSlice(e.ServerAddrs),
			ExpiresAt:   e.ExpiresAt,
			Link:        e.Link,
		}
		cache.mutex.Unlock()
		restored++
	}
	if restored > 0 {
		log.Printf("[КРЕДЫ] Восстановлено из кеша: %d (без обращения к VK)", restored)
	}
}

// saveCredsStore сохраняет живые записи всех кешей. Вызывается после каждого
// успешного получения кредов.
func saveCredsStore() {
	path := credsStorePath()
	if path == "" {
		return
	}

	credentialsStore.mu.RLock()
	ids := make([]int, 0, len(credentialsStore.caches))
	caches := make([]*StreamCredentialsCache, 0, len(credentialsStore.caches))
	for id, c := range credentialsStore.caches {
		ids = append(ids, id)
		caches = append(caches, c)
	}
	credentialsStore.mu.RUnlock()

	now := time.Now()
	entries := make([]persistedCreds, 0, len(caches))
	for i, c := range caches {
		c.mutex.RLock()
		creds := c.creds
		c.mutex.RUnlock()
		if creds.Link == "" || len(creds.ServerAddrs) == 0 || !now.Before(creds.ExpiresAt) {
			continue
		}
		entries = append(entries, persistedCreds{
			CacheID:     ids[i],
			Link:        creds.Link,
			Username:    creds.Username,
			Password:    creds.Password,
			ServerAddrs: cloneStringSlice(creds.ServerAddrs),
			ExpiresAt:   creds.ExpiresAt,
		})
	}

	data, err := json.Marshal(entries)
	if err != nil {
		return
	}

	credsStoreFile.mu.Lock()
	defer credsStoreFile.mu.Unlock()
	// Пишем через временный файл: оборванная запись не должна оставлять
	// обрезанный JSON, из-за которого следующий старт потеряет кеш целиком.
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		log.Printf("[КРЕДЫ] Кеш не сохранён: %v", err)
		return
	}
	if err := os.Rename(tmp, path); err != nil {
		log.Printf("[КРЕДЫ] Кеш не заменён: %v", err)
		_ = os.Remove(tmp)
	}
}

// ensureCredsStoreDir создаёт директорию под файл кеша.
func ensureCredsStoreDir(path string) error {
	dir := filepath.Dir(path)
	if dir == "" || dir == "." {
		return nil
	}
	return os.MkdirAll(dir, 0o700)
}
