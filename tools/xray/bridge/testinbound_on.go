//go:build linux && dppass_testinbound

package bridge

import (
	"github.com/xtls/xray-core/app/proxyman"
	"github.com/xtls/xray-core/core"
)

// Сборка только для тестов на хосте (D-25): вход dokodemo-door на
// loopback подаёт ядру соединения без TUN. В библиотеку для APK этот
// файл не входит (тег dppass_testinbound задаёт только tools/xray/native.py host).
const testInbounds = true

func testInboundAllowed(in *core.InboundHandlerConfig) bool {
	if in.ProxySettings.Type != "xray.proxy.dokodemo.Config" {
		return false
	}
	rs, err := in.ReceiverSettings.GetInstance()
	if err != nil {
		return false
	}
	recv, ok := rs.(*proxyman.ReceiverConfig)
	if !ok || recv.Listen == nil {
		return false
	}
	ip := recv.Listen.AsAddress()
	return ip.Family().IsIP() && ip.IP().IsLoopback()
}
