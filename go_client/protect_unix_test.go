//go:build !windows

package main

import (
	"fmt"
	"golang.org/x/sys/unix"
	"net"
	"strings"
	"testing"
	"time"
)

func TestUnavailableProtectorFailsClosed(t *testing.T) {
	fd, err := unix.Socket(unix.AF_INET, unix.SOCK_DGRAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer unix.Close(fd)
	if err := protectTransportFD(fmt.Sprintf("wdtt_absent_%d", time.Now().UnixNano()), fd); err == nil {
		t.Fatal("missing protection bridge accepted an unprotected socket")
	}
}

func TestProtectionRequiresAcknowledgement(t *testing.T) {
	for _, ack := range []byte{0, 1, 2, 3, 4, 5} {
		t.Run(string(rune('0'+ack)), func(t *testing.T) {
			// Abstract Unix socket, as used by the Android protection bridge.
			name := fmt.Sprintf("wdtt_protect_test_%d", time.Now().UnixNano())
			server, err := net.ListenUnix("unix", &net.UnixAddr{Name: "@" + name, Net: "unix"})
			if err != nil {
				t.Fatal(err)
			}
			defer server.Close()
			seen := make(chan bool, 1)
			go func() {
				c, e := server.AcceptUnix()
				if e != nil {
					seen <- false
					return
				}
				defer c.Close()
				_ = c.SetDeadline(time.Now().Add(time.Second))
				data := make([]byte, 1)
				oob := make([]byte, 64)
				_, n, _, _, e := c.ReadMsgUnix(data, oob)
				if e != nil {
					seen <- false
					return
				}
				msgs, e := unix.ParseSocketControlMessage(oob[:n])
				if e != nil {
					seen <- false
					return
				}
				count := 0
				for _, m := range msgs {
					fds, _ := unix.ParseUnixRights(&m)
					for _, fd := range fds {
						count++
						unix.Close(fd)
					}
				}
				_, _ = c.Write([]byte{ack})
				seen <- count == 1 && data[0] == 1
			}()
			fd, e := unix.Socket(unix.AF_INET, unix.SOCK_DGRAM, 0)
			if e != nil {
				t.Fatal(e)
			}
			defer unix.Close(fd)
			err = protectTransportFD(name, fd)
			if (err == nil) != (ack == 1) {
				t.Fatalf("ack=%d err=%v", ack, err)
			}
			if ack == 2 && !strings.Contains(err.Error(), "no available underlying network") {
				t.Fatal("missing network must be distinguishable from a DNS provider failure")
			}
			if !<-seen {
				t.Fatal("socket descriptor not delivered")
			}
		})
	}
}
