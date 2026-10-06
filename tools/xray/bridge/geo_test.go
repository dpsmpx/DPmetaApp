//go:build linux

package bridge

import (
	"bytes"
	"errors"
	"net/netip"
	"os"
	"path/filepath"
	"testing"

	"github.com/xtls/xray-core/common"
	"github.com/xtls/xray-core/common/geodata"
	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/routing"
	"github.com/xtls/xray-core/infra/conf/serial"
	"google.golang.org/protobuf/proto"
)

// writeGeoSet — синтетический набор для GeoMatch: в geosite есть домен,
// полное имя, ключевое слово, regexp и список с атрибутами врозь (как bing
// в dlc: записи @ads и записи @cn, ни одной с обоими); в geoip — IPv4 и
// IPv6.
func writeGeoSet(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	attr := func(keys ...string) []*geodata.Domain_Attribute {
		var out []*geodata.Domain_Attribute
		for _, k := range keys {
			out = append(out, &geodata.Domain_Attribute{Key: k, TypedValue: &geodata.Domain_Attribute_BoolValue{BoolValue: true}})
		}
		return out
	}
	sites := &geodata.GeoSiteList{Entry: []*geodata.GeoSite{
		{Code: "AA", Domain: []*geodata.Domain{
			{Type: geodata.Domain_Domain, Value: "example.org"},
			{Type: geodata.Domain_Full, Value: "only.example.net"},
			{Type: geodata.Domain_Substr, Value: "kw-test"},
			{Type: geodata.Domain_Regex, Value: `^r[0-9]+\.example\.com$`},
		}},
		{Code: "SPLIT", Domain: []*geodata.Domain{
			{Type: geodata.Domain_Domain, Value: "ads.example", Attribute: attr("ads")},
			{Type: geodata.Domain_Domain, Value: "cn.example", Attribute: attr("cn")},
		}},
	}}
	cidr := func(s string) *geodata.CIDR {
		p := netip.MustParsePrefix(s)
		return &geodata.CIDR{Ip: p.Addr().AsSlice(), Prefix: uint32(p.Bits())}
	}
	ips := &geodata.GeoIPList{Entry: []*geodata.GeoIP{
		{Code: "AA", Cidr: []*geodata.CIDR{cidr("192.0.2.0/24"), cidr("2001:db8:aa::/48")}},
	}}
	for name, m := range map[string]proto.Message{geodata.DefaultGeoIPDat: ips, geodata.DefaultGeoSiteDat: sites} {
		b, err := proto.Marshal(m)
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, name), b, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	return dir
}

type geoCase struct {
	kind  GeoKind
	code  string
	value string
	want  bool
}

var geoCases = []geoCase{
	{GeoSite, "aa", "example.org", true},
	{GeoSite, "aa", "www.example.org", true},
	{GeoSite, "aa", "WWW.Example.ORG", true}, // маршрутизатор приводит имя к нижнему регистру
	{GeoSite, "aa", "example.org.evil", false},
	{GeoSite, "aa", "only.example.net", true},
	{GeoSite, "aa", "x.only.example.net", false},
	{GeoSite, "aa", "a-kw-test-b.example", true},
	{GeoSite, "aa", "r42.example.com", true},
	{GeoSite, "aa", "rx.example.com", false},
	{GeoSite, "aa", "", false},
	{GeoSite, "split", "ads.example", true},
	{GeoSite, "split@ads", "ads.example", true},
	{GeoSite, "split@ads", "cn.example", false},
	{GeoSite, "split@cn", "cn.example", true},
	// D-27: сочетание, которого нет ни у одной записи, ядро не применяет ни к одному имени.
	{GeoSite, "split@ads@cn", "ads.example", false},
	{GeoSite, "split@ads@cn", "cn.example", false},
	{GeoIP, "aa", "192.0.2.77", true},
	{GeoIP, "aa", "192.0.3.1", false},
	{GeoIP, "aa", "2001:db8:aa::1", true},
	{GeoIP, "aa", "2001:db8:ab::1", false},
}

func (c geoCase) bytes() []byte {
	if c.kind == GeoIP {
		return netip.MustParseAddr(c.value).AsSlice()
	}
	return []byte(c.value)
}

// D-27: объяснение решений берёт geosite и geoip у ядра — GeoMatch отвечает
// так же, как маршрутизатор Xray с правилом на этот код (Router.PickRoute).
func TestGeoMatchAgreesWithRouter(t *testing.T) {
	setup(t)
	if err := SetAssetDir(writeGeoSet(t)); err != nil {
		t.Fatal(err)
	}
	for _, c := range geoCases {
		got, err := GeoMatch(c.kind, c.code, c.bytes())
		if err != nil {
			t.Fatalf("%+v: %v", c, err)
		}
		if got != c.want {
			t.Errorf("%+v: GeoMatch → %v", c, got)
		}
		if routed := routerMatches(t, c); routed != got {
			t.Errorf("%+v: GeoMatch → %v, маршрутизатор Xray → %v", c, got, routed)
		}
	}
}

func TestGeoMatchRejectsUnknownCodesAndBadCalls(t *testing.T) {
	setup(t)
	if err := SetAssetDir(writeGeoSet(t)); err != nil {
		t.Fatal(err)
	}
	ip := netip.MustParseAddr("192.0.2.1").AsSlice()
	for name, c := range map[string]struct {
		kind  GeoKind
		code  string
		value []byte
		want  Kind
	}{
		"нет списка":         {GeoSite, "zz", []byte("example.org"), KindConfig},
		"нет страны":         {GeoIP, "zz", ip, KindConfig},
		"другой файл":        {GeoSite, "ext:other.dat:aa", []byte("example.org"), KindContract},
		"обращение условия":  {GeoIP, "!aa", ip, KindContract},
		"пустой код":         {GeoSite, "", []byte("example.org"), KindContract},
		"верхний регистр":    {GeoSite, "AA", []byte("example.org"), KindContract},
		"адрес не той длины": {GeoIP, "aa", []byte{192, 0, 2}, KindContract},
		"неизвестный вид":    {GeoKind(3), "aa", []byte("example.org"), KindContract},
	} {
		_, err := GeoMatch(c.kind, c.code, c.value)
		if err == nil {
			t.Errorf("%s: принято", name)
			continue
		}
		if k := kindOf(t, err); k != c.want {
			t.Errorf("%s: вид %v, ожидался %v (%v)", name, k, c.want, err)
		}
	}
}

// Набор сменился (другой каталог): код снова сверяется с файлами — как у
// ядра, которое отвергает конфигурацию с кодом, которого в наборе нет.
func TestGeoMatchFollowsAssetDir(t *testing.T) {
	setup(t)
	if err := SetAssetDir(writeGeoSet(t)); err != nil {
		t.Fatal(err)
	}
	if ok, err := GeoMatch(GeoSite, "split", []byte("ads.example")); err != nil || !ok {
		t.Fatalf("в наборе: ok=%v err=%v", ok, err)
	}
	other := t.TempDir()
	writeGeodata(t, other) // только AA: списка SPLIT здесь нет
	if err := SetAssetDir(other); err != nil {
		t.Fatal(err)
	}
	_, err := GeoMatch(GeoSite, "split", []byte("ads.example"))
	if err == nil {
		t.Fatal("кода нет в новом наборе, а мост ответил по прежнему")
	}
	if k := kindOf(t, err); k != KindConfig {
		t.Fatalf("вид %v, ожидался KindConfig (%v)", k, err)
	}
	if _, err := Validate(cfg(tunIn, `"routing": {"rules": [{"domain": ["geosite:split"], "outboundTag": "block"}]}`)); err == nil {
		t.Fatal("ядро приняло код, которого нет в наборе")
	}
}

// routerMatches — сработало ли у маршрутизатора Xray правило с одним кодом c.
func routerMatches(t *testing.T, c geoCase) bool {
	t.Helper()
	field := `"domain": ["geosite:` + c.code + `"]`
	q := &geoQuery{domain: c.value}
	if c.kind == GeoIP {
		field = `"ip": ["geoip:` + c.code + `"]`
		q = &geoQuery{ips: []xnet.IP{c.bytes()}}
	}
	raw := cfg(tunIn, `"routing": {"rules": [{`+field+`, "outboundTag": "block", "ruleTag": "geo"}]}`)
	jc, err := serial.DecodeJSONConfig(bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}
	pb, err := jc.Build()
	if err != nil {
		t.Fatal(err)
	}
	inst, err := core.New(pb)
	if err != nil {
		t.Fatal(err)
	}
	defer inst.Close()
	r := inst.GetFeature(routing.RouterType()).(routing.Router)
	route, err := r.PickRoute(q)
	if errors.Is(err, common.ErrNoClue) {
		return false
	}
	if err != nil {
		t.Fatal(err)
	}
	return route.GetRuleTag() == "geo"
}

// geoQuery — routing.Context одного запроса: имя или адрес назначения, без резолва.
type geoQuery struct {
	domain string
	ips    []xnet.IP
}

func (q *geoQuery) GetInboundTag() string            { return "tun-in" }
func (q *geoQuery) GetSourceIPs() []xnet.IP          { return nil }
func (q *geoQuery) GetSourcePort() xnet.Port         { return 0 }
func (q *geoQuery) GetTargetIPs() []xnet.IP          { return q.ips }
func (q *geoQuery) GetTargetPort() xnet.Port         { return 443 }
func (q *geoQuery) GetLocalIPs() []xnet.IP           { return nil }
func (q *geoQuery) GetLocalPort() xnet.Port          { return 0 }
func (q *geoQuery) GetTargetDomain() string          { return q.domain }
func (q *geoQuery) GetNetwork() xnet.Network         { return xnet.Network_TCP }
func (q *geoQuery) GetProtocol() string              { return "" }
func (q *geoQuery) GetUser() string                  { return "" }
func (q *geoQuery) GetVlessRoute() xnet.Port         { return 0 }
func (q *geoQuery) GetAttributes() map[string]string { return nil }
func (q *geoQuery) GetSkipDNSResolve() bool          { return true }
