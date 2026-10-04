//go:build linux && !dppass_testinbound

package bridge

import (
	"strings"
	"testing"
)

// В сборке для APK тестового входа нет: dokodemo-door даже на loopback отвергается (T11).
func TestProductionBuildRejectsLoopbackInbound(t *testing.T) {
	setup(t)
	in := `{"tag": "test-in", "protocol": "dokodemo-door", "listen": "127.0.0.1", "port": 10809,
		"settings": {"address": "127.0.0.1", "port": 9, "network": "tcp"}}`
	if _, err := Validate(cfg(in)); err == nil || kindOf(t, err) != KindConfig || !strings.Contains(err.Error(), "мост:") {
		t.Fatalf("ожидался отказ политики, получено %v", err)
	}
}
