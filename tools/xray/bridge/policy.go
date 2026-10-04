//go:build linux

package bridge

import (
	"fmt"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/proxy/tun"
)

// Службы, которые может запустить мост: то, что пишет компилятор DPpass
// (журнал, DNS, маршрутизация, политики, счётчики), и служебные части,
// которые Xray добавляет к любой конфигурации сам. Остального — API
// ядра (gRPC), метрик по HTTP, обратного прокси, наблюдателя с пробами,
// fakedns — нет: это входы или соединения, которых DPpass не заказывал.
var allowedApps = map[string]bool{
	"xray.app.log.Config":              true,
	"xray.app.dispatcher.Config":       true,
	"xray.app.proxyman.InboundConfig":  true,
	"xray.app.proxyman.OutboundConfig": true,
	"xray.app.router.Config":           true,
	"xray.app.dns.Config":              true,
	"xray.app.policy.Config":           true,
	"xray.app.stats.Config":            true,
}

const tunType = "xray.proxy.tun.Config"

// tunInbound — вход TUN из собранной конфигурации (MTU — с умолчаниями Xray).
type tunInbound struct {
	MTU uint32
}

// checkPolicy проверяет собранную конфигурацию — ту самую protobuf,
// которую получит core.New, а не JSON: у JSON в Go имена полей
// сопоставляются без учёта регистра, и проверка текста пропустила бы
// `"Inbounds"`. Входов, кроме одного TUN, нет (T11): вход на порту был
// бы доступен любому приложению устройства.
func checkPolicy(cfg *core.Config) (*tunInbound, error) {
	for _, app := range cfg.App {
		if !allowedApps[app.Type] {
			return nil, fmt.Errorf("мост: служба %s не допускается (D-25)", app.Type)
		}
	}
	var found *tunInbound
	for _, in := range cfg.Inbound {
		if in.ProxySettings == nil {
			return nil, fmt.Errorf("мост: вход %q без протокола", in.Tag)
		}
		t := in.ProxySettings.Type
		if t != tunType && !(testInbounds && testInboundAllowed(in)) {
			return nil, fmt.Errorf("мост: вход %q (%s) не допускается — входы ядра только TUN (T11)", in.Tag, t)
		}
		if t != tunType {
			continue
		}
		if found != nil {
			return nil, fmt.Errorf("мост: второй вход TUN %q", in.Tag)
		}
		msg, err := in.ProxySettings.GetInstance()
		if err != nil {
			return nil, err
		}
		tc, ok := msg.(*tun.Config)
		if !ok {
			return nil, fmt.Errorf("мост: вход %q: настройки не TUN", in.Tag)
		}
		found = &tunInbound{MTU: tc.MTU}
	}
	return found, nil
}
