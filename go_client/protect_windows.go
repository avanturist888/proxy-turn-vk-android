//go:build windows

package main

import "errors"

func protectTransportFD(name string, fd int) error {
	return errors.New("Android socket protection is unavailable on Windows")
}
