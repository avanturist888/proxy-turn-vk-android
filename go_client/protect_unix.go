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
	n, err := c.Read(ack[:])
	if err != nil {
		return fmt.Errorf("VPN socket protection acknowledgement failed: %w", err)
	}
	if n != 1 {
		return fmt.Errorf("VPN socket protection acknowledgement missing")
	}
	switch ack[0] {
	case 1:
		return nil
	case 2:
		return fmt.Errorf("VPN transport has no available underlying network")
	case 3:
		return fmt.Errorf("VPN transport could not bind to the underlying network")
	case 4:
		return fmt.Errorf("VPN transport socket protection failed")
	case 5:
		return fmt.Errorf("VPN transport owner or underlying network changed")
	default:
		return fmt.Errorf("VPN socket protection rejected")
	}
}
