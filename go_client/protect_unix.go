//go:build !windows

package main

import (
	"fmt"
	"net"
	"time"

	"golang.org/x/sys/unix"
)

func protectTransportFD(name string, fd int) error {
	c, err := net.DialUnix("unix", nil, &net.UnixAddr{Name: "@" + name, Net: "unix"})
	if err != nil {
		return fmt.Errorf("VPN socket protector unavailable: %w", err)
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(3 * time.Second))
	if _, _, err := c.WriteMsgUnix([]byte{1}, unix.UnixRights(fd), nil); err != nil {
		return err
	}
	var ack [1]byte
	if n, err := c.Read(ack[:]); err != nil || n != 1 || ack[0] != 1 {
		return fmt.Errorf("VPN socket protection rejected")
	}
	return nil
}
