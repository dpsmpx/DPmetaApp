//go:build linux

package bridge

import (
	"net/netip"
	"os"
	"path/filepath"
	"testing"

	"github.com/xtls/xray-core/common/geodata"
	"google.golang.org/protobuf/proto"
)

// writeGeodata — набор геоданных с одним кодом каждого вида: geoip AA и
// geosite AA (коды в файлах Xray — в верхнем регистре).
func writeGeodata(t *testing.T, dir string) {
	t.Helper()
	p := netip.MustParsePrefix("192.0.2.0/24")
	ips := &geodata.GeoIPList{Entry: []*geodata.GeoIP{{
		Code: "AA",
		Cidr: []*geodata.CIDR{{Ip: p.Addr().AsSlice(), Prefix: uint32(p.Bits())}},
	}}}
	sites := &geodata.GeoSiteList{Entry: []*geodata.GeoSite{{
		Code:   "AA",
		Domain: []*geodata.Domain{{Type: geodata.Domain_Domain, Value: "example.org"}},
	}}}
	for name, m := range map[string]proto.Message{geodata.DefaultGeoIPDat: ips, geodata.DefaultGeoSiteDat: sites} {
		b, err := proto.Marshal(m)
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, name), b, 0o600); err != nil {
			t.Fatal(err)
		}
	}
}

// D-27: код, которого нет в наборе геоданных, — отказ проверки
// конфигурации, а не условие, которое молча никогда не выполняется (D-05).
func TestGeodataUnknownCodeIsRejected(t *testing.T) {
	setup(t)
	dir := t.TempDir()
	writeGeodata(t, dir)
	if err := SetAssetDir(dir); err != nil {
		t.Fatal(err)
	}
	rule := func(cond string) []byte {
		return cfg(tunIn, `"routing": {"rules": [{`+cond+`, "outboundTag": "block"}]}`)
	}
	for _, known := range []string{`"ip": ["geoip:aa"]`, `"domain": ["geosite:aa"]`} {
		if full, err := Validate(rule(known)); err != nil || !full {
			t.Errorf("%s есть в наборе: full=%v err=%v", known, full, err)
		}
	}
	for _, unknown := range []string{`"ip": ["geoip:zz"]`, `"domain": ["geosite:zz"]`} {
		_, err := Validate(rule(unknown))
		if err == nil {
			t.Errorf("%s нет в наборе — конфигурация принята", unknown)
			continue
		}
		if k := kindOf(t, err); k != KindConfig {
			t.Errorf("%s нет в наборе: ожидался KindConfig, получено %v (%v)", unknown, k, err)
		}
	}
}
