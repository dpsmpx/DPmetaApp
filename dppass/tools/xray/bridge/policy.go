//go:build linux

package bridge

import (
	"fmt"
	"strings"

	"github.com/xtls/xray-core/app/proxyman"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/proxy/socks"
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

// Вход раздачи туннеля другим устройствам (D-51): SOCKS (и HTTP на том же
// порту) с паролем, на частном IPv4-адресе интерфейса раздачи.
const (
	socksType      = "xray.proxy.socks.ServerConfig"
	shareTagPrefix = "dppass:share-" // XrayBackend.SHARE_TAG_PREFIX
	minSharePort   = 1024
)

// tunInbound — вход TUN из собранной конфигурации (MTU — с умолчаниями Xray).
type tunInbound struct {
	MTU uint32
}

// checkPolicy проверяет собранную конфигурацию — ту самую protobuf,
// которую получит core.New, а не JSON: у JSON в Go имена полей
// сопоставляются без учёта регистра, и проверка текста пропустила бы
// `"Inbounds"`. Входов, кроме одного TUN и входов раздачи (SOCKS с паролем
// на частном IPv4, D-51), нет (T11): вход на порту без пароля был
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
		if t == socksType && strings.HasPrefix(in.Tag, shareTagPrefix) {
			if err := checkShareInbound(in); err != nil {
				return nil, err
			}
			continue
		}
		if t != tunType && !(testInbounds && testInboundAllowed(in)) {
			return nil, fmt.Errorf("мост: вход %q (%s) не допускается — входы ядра только TUN и раздача с паролем (T11, D-51)", in.Tag, t)
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

// checkShareInbound — вход раздачи (D-51, T11): только с логином и паролем,
// только на частном IPv4-адресе (не 0.0.0.0, не loopback, не публичный) и
// с портом от 1024, без транспортных надстроек.
func checkShareInbound(in *core.InboundHandlerConfig) error {
	rs, err := in.ReceiverSettings.GetInstance()
	if err != nil {
		return err
	}
	recv, ok := rs.(*proxyman.ReceiverConfig)
	if !ok || recv.Listen == nil {
		return fmt.Errorf("мост: вход раздачи %q без адреса — слушал бы все интерфейсы (D-51)", in.Tag)
	}
	addr := recv.Listen.AsAddress()
	if !addr.Family().IsIPv4() || !addr.IP().IsPrivate() {
		return fmt.Errorf("мост: вход раздачи %q на %s — только частный IPv4 интерфейса раздачи (D-51)", in.Tag, addr)
	}
	if recv.PortList == nil || len(recv.PortList.Range) != 1 || recv.PortList.Range[0].From != recv.PortList.Range[0].To ||
		recv.PortList.Range[0].From < minSharePort {
		return fmt.Errorf("мост: вход раздачи %q — один порт от %d (D-51)", in.Tag, minSharePort)
	}
	if recv.StreamSettings != nil && (recv.StreamSettings.SecurityType != "" || len(recv.StreamSettings.SecuritySettings) > 0) {
		return fmt.Errorf("мост: вход раздачи %q с надстройкой транспорта (D-51)", in.Tag)
	}
	msg, err := in.ProxySettings.GetInstance()
	if err != nil {
		return err
	}
	sc, ok := msg.(*socks.ServerConfig)
	if !ok || sc.AuthType != socks.AuthType_PASSWORD || len(sc.Accounts) == 0 {
		return fmt.Errorf("мост: вход раздачи %q без пароля (T11)", in.Tag)
	}
	for user, pass := range sc.Accounts {
		if user == "" || pass == "" {
			return fmt.Errorf("мост: вход раздачи %q с пустым логином или паролем (T11)", in.Tag)
		}
	}
	return nil
}
