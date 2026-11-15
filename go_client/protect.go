package main

import (
	"context"
	"net"
	"os"
	"strings"
	"syscall"
	"time"
)

// Protection is configured only for the Android subprocess. An unavailable
// protector is an error, never a reason to open an unprotected transport socket.
func transportControl(network, address string, raw syscall.RawConn) error {
	name := os.Getenv("WDTT_PROTECT_SOCKET")
	if name == "" {
		return nil // Desktop tests/standalone builds have no Android VPN owner.
	}
	if host, _, err := net.SplitHostPort(address); err == nil {
		if ip := net.ParseIP(strings.Trim(host, "[]")); ip != nil && ip.IsLoopback() {
			return nil
		}
	}
	var protectErr error
	if err := raw.Control(func(fd uintptr) { protectErr = protectTransportFD(name, int(fd)) }); err != nil {
		return err
	}
	return protectErr
}

func transportDialer(timeout time.Duration) *net.Dialer {
	return &net.Dialer{Timeout: timeout, KeepAlive: 30 * time.Second, Control: transportControl}
}

func protectedDial(ctx context.Context, network, address string) (net.Conn, error) {
	return transportDialer(10*time.Second).DialContext(ctx, network, address)
}
