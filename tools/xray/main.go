// Command dppass-xray-probe — проверка компилятора DPpass самим Xray на хосте
// (docs/TESTING.md, «Эталоны Xray»; шаг hostcheck «Xray»).
//
// Подкоманды:
//
//	geodata -in geo.json -out DIR
//	    синтетические geoip.dat и geosite.dat из таблиц корпуса — своих
//	    данных; настоящие базы в проверки не попадают (данные корпуса —
//	    только синтетические, docs/TESTING.md).
//	route -probe FILE.probe.json -assets DIR
//	    решения маршрутизации Xray для запросов корпуса: конфигурация
//	    собирается так же, как при `xray run -test` (core.New без Start —
//	    без входов, без сети), затем каждый запрос проходит Router.PickRoute.
//	    Ожидания (`outbound`, `rule`) пишет эталонный RouteEvaluator в
//	    тестах Kotlin; здесь только спрашивается ядро.
//
// Код возврата: 0 — всё совпало; 1 — расхождение или ошибка; 2 — неверный вызов.
package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"net/netip"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/xtls/xray-core/common"
	"github.com/xtls/xray-core/common/geodata"
	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/platform"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/outbound"
	"github.com/xtls/xray-core/features/routing"
	"github.com/xtls/xray-core/infra/conf/serial"
	"google.golang.org/protobuf/proto"

	// Все обработчики, транспорты и функции ядра — как в собранном xray:
	// иначе конфигурация собиралась бы не той программой, что её запускает.
	_ "github.com/xtls/xray-core/main/distro/all"
)

func main() {
	if len(os.Args) < 2 {
		usage()
	}
	var err error
	switch os.Args[1] {
	case "geodata":
		err = cmdGeodata(os.Args[2:])
	case "route":
		err = cmdRoute(os.Args[2:])
	default:
		usage()
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "✗", err)
		os.Exit(1)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "использование: dppass-xray-probe geodata -in geo.json -out DIR | route -probe FILE -assets DIR")
	os.Exit(2)
}

// geoTables — таблицы геоданных корпуса: код → подсети и код → правила
// имён (`full:`, `domain:`, `keyword:` — те же виды, что у DomainMatcher DPpass).
type geoTables struct {
	GeoIP   map[string][]string `json:"geoip"`
	GeoSite map[string][]string `json:"geosite"`
}

func cmdGeodata(args []string) error {
	fs := flag.NewFlagSet("geodata", flag.ExitOnError)
	in := fs.String("in", "", "таблицы геоданных (JSON)")
	out := fs.String("out", "", "каталог для geoip.dat и geosite.dat")
	_ = fs.Parse(args)
	if *in == "" || *out == "" {
		usage()
	}
	var t geoTables
	if err := readJSON(*in, &t); err != nil {
		return err
	}
	ips, err := buildGeoIP(t.GeoIP)
	if err != nil {
		return err
	}
	sites, err := buildGeoSite(t.GeoSite)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(*out, 0o755); err != nil {
		return err
	}
	if err := writeProto(filepath.Join(*out, geodata.DefaultGeoIPDat), ips); err != nil {
		return err
	}
	return writeProto(filepath.Join(*out, geodata.DefaultGeoSiteDat), sites)
}

// Коды в файлах Xray — в верхнем регистре (common/geodata/rule_parser.go
// приводит код правила к нему); порядок — по коду, чтобы файл был одним и тем же.
func buildGeoIP(table map[string][]string) (*geodata.GeoIPList, error) {
	list := &geodata.GeoIPList{}
	for _, code := range sortedKeys(table) {
		entry := &geodata.GeoIP{Code: strings.ToUpper(code)}
		for _, s := range table[code] {
			p, err := netip.ParsePrefix(s)
			if err != nil {
				return nil, fmt.Errorf("geoip %s: %w", code, err)
			}
			entry.Cidr = append(entry.Cidr, &geodata.CIDR{Ip: p.Addr().AsSlice(), Prefix: uint32(p.Bits())})
		}
		list.Entry = append(list.Entry, entry)
	}
	return list, nil
}

func buildGeoSite(table map[string][]string) (*geodata.GeoSiteList, error) {
	list := &geodata.GeoSiteList{}
	kinds := map[string]geodata.Domain_Type{
		"full":    geodata.Domain_Full,
		"domain":  geodata.Domain_Domain,
		"keyword": geodata.Domain_Substr,
	}
	for _, code := range sortedKeys(table) {
		entry := &geodata.GeoSite{Code: strings.ToUpper(code)}
		for _, s := range table[code] {
			kind, value, ok := strings.Cut(s, ":")
			t, known := kinds[kind]
			if !ok || !known || value == "" {
				return nil, fmt.Errorf("geosite %s: правило %q — ожидалось full:, domain: или keyword:", code, s)
			}
			entry.Domain = append(entry.Domain, &geodata.Domain{Type: t, Value: value})
		}
		list.Entry = append(list.Entry, entry)
	}
	return list, nil
}

// probeFile — ожидания одного файла случаев маршрутизации (tests/data/xray/routing/*.probe.json).
type probeFile struct {
	Source  string      `json:"source"`
	Config  string      `json:"config"`
	Inbound string      `json:"inbound"`
	Geo     geoTables   `json:"geo"`
	Cases   []probeCase `json:"cases"`
	Skipped []struct {
		Case   int    `json:"case"`
		Reason string `json:"reason"`
	} `json:"skipped"`
}

type probeCase struct {
	Case     int        `json:"case"`
	Query    probeQuery `json:"query"`
	Outbound string     `json:"outbound"`
	Rule     *string    `json:"rule"`
}

type probeQuery struct {
	Domain  string `json:"domain"`
	IP      string `json:"ip"`
	Port    uint16 `json:"port"`
	Network string `json:"network"`
}

func cmdRoute(args []string) error {
	fs := flag.NewFlagSet("route", flag.ExitOnError)
	probePath := fs.String("probe", "", "файл ожиданий *.probe.json")
	assets := fs.String("assets", "", "каталог с geoip.dat и geosite.dat")
	_ = fs.Parse(args)
	if *probePath == "" || *assets == "" {
		usage()
	}
	var p probeFile
	if err := readJSON(*probePath, &p); err != nil {
		return err
	}
	// Ядро ищет геоданные сначала здесь (common/platform/others.go): без
	// этого оно взяло бы настоящие базы из /usr/share/xray, если они есть.
	if err := os.Setenv(platform.AssetLocation, *assets); err != nil {
		return err
	}
	raw, err := os.ReadFile(filepath.Join(filepath.Dir(*probePath), p.Config))
	if err != nil {
		return err
	}
	jsonConfig, err := serial.DecodeJSONConfig(bytes.NewReader(raw))
	if err != nil {
		return fmt.Errorf("%s: %w", p.Config, err)
	}
	pb, err := jsonConfig.Build()
	if err != nil {
		return fmt.Errorf("%s: %w", p.Config, err)
	}
	instance, err := core.New(pb)
	if err != nil {
		return fmt.Errorf("%s: %w", p.Config, err)
	}
	defer instance.Close()
	router, ok := instance.GetFeature(routing.RouterType()).(routing.Router)
	if !ok {
		return errors.New("в конфигурации нет маршрутизатора")
	}
	outbounds, ok := instance.GetFeature(outbound.ManagerType()).(outbound.Manager)
	if !ok {
		return errors.New("в конфигурации нет исходящих")
	}

	mismatches := 0
	for _, c := range p.Cases {
		q, err := c.Query.context(p.Inbound)
		if err != nil {
			return fmt.Errorf("%s#%d: %w", p.Source, c.Case, err)
		}
		gotOutbound, gotRule := "", ""
		route, err := router.PickRoute(q)
		switch {
		case err == nil:
			gotOutbound, gotRule = route.GetOutboundTag(), route.GetRuleTag()
		case errors.Is(err, common.ErrNoClue):
			// Ни одно правило не сработало: диспетчер берёт обработчик по
			// умолчанию — первый исходящий (app/dispatcher/default.go).
			if h := outbounds.GetDefaultHandler(); h != nil {
				gotOutbound = h.Tag()
			}
		default:
			return fmt.Errorf("%s#%d: %w", p.Source, c.Case, err)
		}
		wantRule := ""
		if c.Rule != nil {
			wantRule = *c.Rule
		}
		if gotOutbound != c.Outbound || gotRule != wantRule {
			mismatches++
			fmt.Printf("✗ %s#%d %s: Xray → %s (правило %q), RouteEvaluator → %s (правило %q)\n",
				p.Source, c.Case, c.Query, gotOutbound, gotRule, c.Outbound, wantRule)
		}
	}
	if mismatches > 0 {
		return fmt.Errorf("%s: %d из %d решений Xray расходятся с RouteEvaluator", p.Source, mismatches, len(p.Cases))
	}
	fmt.Printf("✓ %s: %d решений Xray совпали с RouteEvaluator, %d решаются вне ядра\n", p.Source, len(p.Cases), len(p.Skipped))
	return nil
}

func (q probeQuery) String() string {
	b, _ := json.Marshal(q)
	return string(b)
}

// context — запрос как его увидел бы маршрутизатор: адрес назначения, имя
// (если известно), порт и сеть. Резолва нет (GetSkipDNSResolve): решение не
// должно зависеть от настоящего DNS, а проверка — ходить в сеть.
func (q probeQuery) context(inbound string) (routing.Context, error) {
	c := &query{inbound: inbound, domain: q.Domain, port: xnet.Port(q.Port)}
	if q.IP != "" {
		ip, err := netip.ParseAddr(q.IP)
		if err != nil {
			return nil, err
		}
		c.ips = []xnet.IP{ip.AsSlice()}
	}
	switch q.Network {
	case "":
	case "tcp":
		c.network = xnet.Network_TCP
	case "udp":
		c.network = xnet.Network_UDP
	default:
		return nil, fmt.Errorf("сеть %q — ожидалось tcp или udp", q.Network)
	}
	return c, nil
}

// query реализует routing.Context (features/routing/context.go) для одного запроса.
type query struct {
	inbound string
	domain  string
	ips     []xnet.IP
	port    xnet.Port
	network xnet.Network
}

func (q *query) GetInboundTag() string            { return q.inbound }
func (q *query) GetSourceIPs() []xnet.IP          { return nil }
func (q *query) GetSourcePort() xnet.Port         { return 0 }
func (q *query) GetTargetIPs() []xnet.IP          { return q.ips }
func (q *query) GetTargetPort() xnet.Port         { return q.port }
func (q *query) GetLocalIPs() []xnet.IP           { return nil }
func (q *query) GetLocalPort() xnet.Port          { return 0 }
func (q *query) GetTargetDomain() string          { return q.domain }
func (q *query) GetNetwork() xnet.Network         { return q.network }
func (q *query) GetProtocol() string              { return "" }
func (q *query) GetUser() string                  { return "" }
func (q *query) GetVlessRoute() xnet.Port         { return 0 }
func (q *query) GetAttributes() map[string]string { return nil }
func (q *query) GetSkipDNSResolve() bool          { return true }

func readJSON(path string, v any) error {
	raw, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		return fmt.Errorf("%s: %w", path, err)
	}
	return nil
}

func writeProto(path string, m proto.Message) error {
	b, err := proto.MarshalOptions{Deterministic: true}.Marshal(m)
	if err != nil {
		return err
	}
	return os.WriteFile(path, b, 0o644)
}

func sortedKeys(m map[string][]string) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}
