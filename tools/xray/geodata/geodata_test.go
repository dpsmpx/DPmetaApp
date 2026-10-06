package main

import (
	"bytes"
	"compress/gzip"
	"encoding/json"
	"errors"
	"net/netip"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"github.com/xtls/xray-core/common"
	"github.com/xtls/xray-core/common/geodata"
	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/platform"
	"github.com/xtls/xray-core/common/session"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/routing"
	routingsession "github.com/xtls/xray-core/features/routing/session"
	"github.com/xtls/xray-core/infra/conf/serial"
	"google.golang.org/protobuf/proto"

	_ "github.com/xtls/xray-core/main/distro/all"
)

// Все данные здесь синтетические: документационные адреса (RFC 5737,
// RFC 3849), имена example.* и коды стран AA…, которых нет в ISO 3166.

func prefixes(t *testing.T, f family, first, last string) []string {
	t.Helper()
	s := newSet(f, span{toU128(netip.MustParseAddr(first)), toU128(netip.MustParseAddr(last))})
	var out []string
	for _, p := range s.prefixes() {
		out = append(out, p.String())
	}
	return out
}

func TestRangesBecomeTheFewestAlignedPrefixes(t *testing.T) {
	cases := []struct {
		f           family
		first, last string
		want        []string
	}{
		{v4, "192.0.2.1", "192.0.2.6", []string{"192.0.2.1/32", "192.0.2.2/31", "192.0.2.4/31", "192.0.2.6/32"}},
		{v4, "192.0.2.0", "192.0.2.255", []string{"192.0.2.0/24"}},
		{v4, "0.0.0.0", "255.255.255.255", []string{"0.0.0.0/0"}},
		{v4, "255.255.255.255", "255.255.255.255", []string{"255.255.255.255/32"}},
		{v6, "::", "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", []string{"::/0"}},
		{v6, "2001:db8::", "2001:db8:0:1:ffff:ffff:ffff:ffff", []string{"2001:db8::/63"}},
		{v6, "2001:db8::ffff:ffff:ffff:ffff", "2001:db8:0:1::", []string{"2001:db8::ffff:ffff:ffff:ffff/128", "2001:db8:0:1::/128"}},
	}
	for _, c := range cases {
		if got := prefixes(t, c.f, c.first, c.last); !slices.Equal(got, c.want) {
			t.Errorf("%s–%s: %v, ожидалось %v", c.first, c.last, got, c.want)
		}
	}
}

func TestSetMergesNeighboursAndSubtracts(t *testing.T) {
	_, a, _ := prefixSpan(netip.MustParsePrefix("192.0.2.0/25"))
	_, b, _ := prefixSpan(netip.MustParsePrefix("192.0.2.128/25"))
	_, hole, _ := prefixSpan(netip.MustParsePrefix("192.0.2.64/26"))
	s := newSet(v4, b, a)
	if len(s.spans) != 1 {
		t.Fatalf("соседние диапазоны не слиты: %v", s.spans)
	}
	s.remove(hole)
	var got []string
	for _, p := range s.prefixes() {
		got = append(got, p.String())
	}
	want := []string{"192.0.2.0/26", "192.0.2.128/25"}
	if !slices.Equal(got, want) {
		t.Errorf("после вычитания %v, ожидалось %v", got, want)
	}
	if s.contains(netip.MustParseAddr("192.0.2.70")) || !s.contains(netip.MustParseAddr("192.0.2.10")) {
		t.Errorf("contains не совпадает с диапазонами %v", s.spans)
	}
}

// private — недостижимое по реестрам IANA; решает самый узкий блок.
func TestPrivateFollowsTheNarrowestRegistryBlock(t *testing.T) {
	sets, err := privateSets()
	if err != nil {
		t.Fatal(err)
	}
	in := func(s string) bool {
		a := netip.MustParseAddr(s)
		return sets[familyOf(a)].contains(a)
	}
	for _, a := range []string{
		"10.1.2.3", "100.64.0.1", "127.0.0.1", "169.254.1.1", "172.31.255.255", "192.168.0.1",
		"192.0.0.8", "192.0.0.170", "192.0.2.1", "192.88.99.2", "198.19.255.255", "224.0.0.251", "239.255.255.250",
		"240.0.0.1", "255.255.255.255", "0.0.0.0",
		"::1", "::", "::ffff:192.0.2.1", "64:ff9b:1::1", "2001:2::1", "2001:db8::1", "3fff::1", "fd00::1", "fe80::1", "ff02::fb",
	} {
		if !in(a) {
			t.Errorf("%s — не private, а реестр называет его недостижимым", a)
		}
	}
	for _, a := range []string{
		"192.0.0.9", "192.0.0.10", "192.31.196.1", "192.88.99.1", "192.175.48.1", "1.0.0.1",
		"64:ff9b::192.0.2.1", "2001::1", "2001:1::1", "2001:3::1", "2001:4:112::1", "2001:10::1", "2001:20::1", "2002::1", "2620:4f:8000::1",
	} {
		if in(a) {
			t.Errorf("%s — private, а реестр не называет его недостижимым", a)
		}
	}
}

func gz(t *testing.T, text string) []byte {
	t.Helper()
	var b bytes.Buffer
	// Без сжатия (хранимые блоки): байты не зависят от эвристик компрессора
	// версии Go, и сумма источника в эталоне оглавления постоянна.
	w, err := gzip.NewWriterLevel(&b, gzip.NoCompression)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := w.Write([]byte(text)); err != nil {
		t.Fatal(err)
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	return b.Bytes()
}

const dbipSample = `0.0.0.0,0.255.255.255,ZZ
192.0.2.0,192.0.2.127,AA
192.0.2.128,192.0.2.255,AA
198.51.100.0,198.51.100.255,AB
2001:db8::,2001:db8:ffff:ffff:ffff:ffff:ffff:ffff,AA
`

func TestDbipMergesNeighboursAndSkipsUnknown(t *testing.T) {
	countries, rows, err := dbipCountries(bytes.NewReader(gz(t, dbipSample)))
	if err != nil {
		t.Fatal(err)
	}
	if rows != 5 {
		t.Errorf("строк %d, ожидалось 5", rows)
	}
	if _, ok := countries[dbipUnknown]; ok {
		t.Error("ZZ — не страна, а попал в набор")
	}
	if got := countries["AA"][v4].prefixes(); len(got) != 1 || got[0].String() != "192.0.2.0/24" {
		t.Errorf("AA IPv4: %v, ожидалось 192.0.2.0/24", got)
	}
	if got := countries["AA"][v6].prefixes(); len(got) != 1 || got[0].String() != "2001:db8::/32" {
		t.Errorf("AA IPv6: %v", got)
	}
}

func TestDbipRejectsWhatItCannotRead(t *testing.T) {
	for name, text := range map[string]string{
		"три поля":         "192.0.2.0,192.0.2.255\n",
		"код страны":       "192.0.2.0,192.0.2.255,aa\n",
		"задом наперёд":    "192.0.2.255,192.0.2.0,AA\n",
		"пересечение":      "192.0.2.0,192.0.2.255,AA\n192.0.2.128,192.0.2.200,AB\n",
		"разные семейства": "192.0.2.0,2001:db8::,AA\n",
		"не адрес":         "192.0.2.x,192.0.2.255,AA\n",
		"пустой файл":      "",
		"не по порядку":    "198.51.100.0,198.51.100.255,AA\n192.0.2.0,192.0.2.255,AB\n",
		"IPv4 внутри IPv6": "::ffff:192.0.2.0,::ffff:192.0.2.255,AA\n",
	} {
		if _, _, err := dbipCountries(bytes.NewReader(gz(t, text))); err == nil {
			t.Errorf("%s: файл принят", name)
		}
	}
}

func writeDlc(t *testing.T, files map[string]string) string {
	t.Helper()
	root := t.TempDir()
	data := filepath.Join(root, "data")
	if err := os.MkdirAll(data, 0o755); err != nil {
		t.Fatal(err)
	}
	for name, text := range files {
		if err := os.WriteFile(filepath.Join(data, name), []byte(text), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(filepath.Join(root, "LICENSE"), []byte("synthetic license\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	return root
}

var dlcSample = map[string]string{
	"example-a": `# список a
example.org @ads
full:www.example.org
keyword:example
regexp:^cdn[0-9]\.example\.net$
Example.Net # регистр имени не важен
example.org @ads &example-c
`,
	"example-b": `include:example-a @-ads
domain:example.com @cn @ads
example.com @cn
`,
	"example-all": `include:example-a
include:example-b @cn
`,
	// Атрибуты есть, но не у одного правила: @ads@cn не выберет ничего.
	"example-split": `example.org @ads
example.net @cn
`,
}

func rendered(rules []domainRule) []string {
	var out []string
	for _, r := range rules {
		s := geodata.Domain_Type_name[int32(r.kind)] + ":" + r.value
		for _, a := range r.attrs {
			s += " @" + a
		}
		out = append(out, s)
	}
	return out
}

func TestDlcResolvesIncludesFiltersAndAffiliations(t *testing.T) {
	lists, st, err := dlcLists(filepath.Join(writeDlc(t, dlcSample), "data"))
	if err != nil {
		t.Fatal(err)
	}
	if st.files != 4 || st.lowercased != 1 {
		t.Errorf("статистика %+v", st)
	}
	want := map[string][]string{
		"example-a":   {"Regex:^cdn[0-9]\\.example\\.net$", "Substr:example", "Domain:example.net", "Domain:example.org @ads", "Full:www.example.org"},
		"example-b":   {"Regex:^cdn[0-9]\\.example\\.net$", "Substr:example", "Domain:example.com @ads @cn", "Domain:example.com @cn", "Domain:example.net", "Full:www.example.org"},
		"example-all": {"Regex:^cdn[0-9]\\.example\\.net$", "Substr:example", "Domain:example.com @ads @cn", "Domain:example.com @cn", "Domain:example.net", "Domain:example.org @ads", "Full:www.example.org"},
		// Список только из аффилиаций — тоже список (файла у него нет).
		"example-c":     {"Domain:example.org @ads"},
		"example-split": {"Domain:example.net @cn", "Domain:example.org @ads"},
	}
	if len(lists) != len(want) {
		t.Errorf("списки %v", sortedKeys(lists))
	}
	for name, w := range want {
		if got := rendered(lists[name]); !slices.Equal(got, w) {
			t.Errorf("%s:\n  получено  %v\n  ожидалось %v", name, got, w)
		}
	}
}

func TestDlcRejectsWhatItCannotRead(t *testing.T) {
	for name, files := range map[string]map[string]string{
		"неизвестный вид":     {"a": "port:443\n"},
		"не домен":            {"a": "example..org\n"},
		"регулярное":          {"a": "regexp:(\n"},
		"мусор после правила": {"a": "example.org ads\n"},
		"фильтр не у include": {"a": "example.org @-ads\n"},
		"нет такого списка":   {"a": "include:b\n"},
		"по кругу":            {"a": "include:b\n", "b": "include:a\n"},
		"имя списка":          {"A@b": "example.org\n"},
	} {
		if _, _, err := dlcLists(filepath.Join(writeDlc(t, files), "data")); err == nil {
			t.Errorf("%s: принято", name)
		}
	}
}

func buildSample(t *testing.T) (string, manifest) {
	t.Helper()
	dlc := writeDlc(t, dlcSample)
	dbip := filepath.Join(t.TempDir(), "dbip.csv.gz")
	if err := os.WriteFile(dbip, gz(t, dbipSample), 0o644); err != nil {
		t.Fatal(err)
	}
	out := t.TempDir()
	if _, err := build(sources{dlc, "v0.0.0-20260101000000-0123456789ab", dbip, "2026-01"}, out); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(filepath.Join(out, manifestFile))
	if err != nil {
		t.Fatal(err)
	}
	var m manifest
	if err := json.Unmarshal(raw, &m); err != nil {
		t.Fatal(err)
	}
	return out, m
}

// D-27: сборка детерминирована — из тех же источников те же байты.
func TestBuildIsDeterministicAndManifestDescribesFiles(t *testing.T) {
	a, m := buildSample(t)
	b, _ := buildSample(t)
	for _, name := range []string{geodata.DefaultGeoIPDat, geodata.DefaultGeoSiteDat, noticeFile, manifestFile} {
		x, _ := os.ReadFile(filepath.Join(a, name))
		y, _ := os.ReadFile(filepath.Join(b, name))
		if len(x) == 0 || !bytes.Equal(x, y) {
			t.Errorf("%s: две сборки из одних источников разошлись", name)
		}
	}
	for _, f := range m.Files {
		data, _ := os.ReadFile(filepath.Join(a, f.Name))
		if sum(data) != f.SHA256 || len(data) != f.Size {
			t.Errorf("%s: SHA-256 или размер не совпали с оглавлением", f.Name)
		}
	}
	if !slices.Equal(m.GeoIP, []string{"aa", "ab", "private"}) {
		t.Errorf("коды geoip %v", m.GeoIP)
	}
	for code, want := range map[string][][]string{
		"example-a":     {{"ads"}},
		"example-b":     {{"ads", "cn"}}, // @cn входит в @ads@cn
		"example-split": {{"ads"}, {"cn"}},
	} {
		if got := m.GeoSite[code]; !slices.EqualFunc(got, want, slices.Equal) {
			t.Errorf("сочетания атрибутов %s: %v, ожидалось %v", code, got, want)
		}
	}
	if m.Sources.Dlc.Commit != "0123456789ab" || m.Sources.Dbip.Release != "2026-01" {
		t.Errorf("источники %+v", m.Sources)
	}
	notice, _ := os.ReadFile(filepath.Join(a, noticeFile))
	for _, must := range []string{"IP Geolocation by DB-IP", "https://db-ip.com", "CC BY 4.0", "synthetic license"} {
		if !strings.Contains(string(notice), must) {
			t.Errorf("NOTICE.txt без %q", must)
		}
	}
}

// golden — эталон оглавления синтетического набора в корпусе: его же
// разбирает приложение (GeodataTest, Kotlin), так договор «сборщик →
// приложение» не разойдётся молча. Обновить: DPPASS_GOLDEN_UPDATE=1 go test ./geodata.
const golden = "../../../tests/data/geodata/manifest.json"

func TestManifestMatchesGolden(t *testing.T) {
	out, _ := buildSample(t)
	got, err := os.ReadFile(filepath.Join(out, manifestFile))
	if err != nil {
		t.Fatal(err)
	}
	if os.Getenv("DPPASS_GOLDEN_UPDATE") == "1" {
		if err := os.MkdirAll(filepath.Dir(golden), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(golden, got, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	want, err := os.ReadFile(golden)
	if err != nil {
		t.Fatalf("нет эталона %s: DPPASS_GOLDEN_UPDATE=1 go test ./geodata", golden)
	}
	if !bytes.Equal(got, want) {
		t.Errorf("оглавление синтетического набора разошлось с эталоном %s — формат изменён? "+
			"Новая версия формата — вместе с разбором в приложении (engine.xray.GeodataManifest)", golden)
	}
}

// Набор читает само ядро той версии, что в приложении: коды и атрибуты
// находятся, неизвестный код — отказ.
func TestXrayAcceptsTheSet(t *testing.T) {
	out, _ := buildSample(t)
	t.Setenv(platform.AssetLocation, out)
	accept := func(rule string) error {
		cfg := `{"log": {"loglevel": "none"}, "outbounds": [{"tag": "direct", "protocol": "freedom"}],
			"routing": {"rules": [{` + rule + `, "outboundTag": "direct"}]}}`
		jc, err := serial.DecodeJSONConfig(strings.NewReader(cfg))
		if err != nil {
			return err
		}
		pb, err := jc.Build()
		if err != nil {
			return err
		}
		inst, err := core.New(pb)
		if err != nil {
			return err
		}
		return inst.Close()
	}
	for _, ok := range []string{`"ip": ["geoip:aa", "geoip:private"]`, `"domain": ["geosite:example-all@ads", "geosite:example-c"]`} {
		if err := accept(ok); err != nil {
			t.Errorf("%s: %v", ok, err)
		}
	}
	for _, bad := range []string{`"ip": ["geoip:zz"]`, `"domain": ["geosite:example-d"]`} {
		if err := accept(bad); err == nil {
			t.Errorf("%s: кода нет в наборе, а конфигурация принята", bad)
		}
	}
	var ips geodata.GeoIPList
	raw, _ := os.ReadFile(filepath.Join(out, geodata.DefaultGeoIPDat))
	if err := proto.Unmarshal(raw, &ips); err != nil {
		t.Fatal(err)
	}
	for _, e := range ips.Entry {
		if e.Code != strings.ToUpper(e.Code) {
			t.Errorf("код %q не в верхнем регистре — Xray его не найдёт", e.Code)
		}
	}
}

// Оглавление говорит о сочетаниях атрибутов то же, что выбирает само ядро
// (D-05): по geosite:список@a@b Xray находит имя, только если у одного
// правила списка есть все атрибуты сразу, — ровно когда сочетание покрыто
// оглавлением. Проверяется каждое сочетание атрибутов каждого списка
// синтетического набора, в том числе @ads@cn у example-split, где эти
// атрибуты есть только врозь.
func TestManifestAttrSetsAreWhatXrayMatches(t *testing.T) {
	out, m := buildSample(t)
	t.Setenv(platform.AssetLocation, out)
	names := []string{"example.org", "www.example.org", "example.net", "cdn1.example.net", "example.com"}
	uncovered := 0
	for _, code := range sortedKeys(m.GeoSite) {
		var attrs []string
		for _, set := range m.GeoSite[code] {
			attrs = append(attrs, set...)
		}
		slices.Sort(attrs)
		attrs = slices.Compact(attrs)
		for mask := 1; mask < 1<<len(attrs); mask++ {
			var query []string
			for i, a := range attrs {
				if mask&(1<<i) != 0 {
					query = append(query, a)
				}
			}
			rule := "geosite:" + code + "@" + strings.Join(query, "@")
			covered := slices.ContainsFunc(m.GeoSite[code], func(set []string) bool { return containsAll(set, query) })
			if !covered {
				uncovered++
			}
			if got := xrayMatchesAny(t, rule, names); got != covered {
				t.Errorf("%s: ядро находит имя — %v, оглавление покрывает сочетание — %v", rule, got, covered)
			}
		}
	}
	if uncovered == 0 {
		t.Error("в примере нет непокрытого сочетания атрибутов — отказ не проверен")
	}
}

// xrayMatchesAny — сработает ли правило маршрутизации Xray с условием
// domain: [rule] хотя бы для одного из имён (Router.PickRoute без запуска
// ядра и без сети, как dppass-xray-probe route).
func xrayMatchesAny(t *testing.T, rule string, names []string) bool {
	t.Helper()
	cfg := `{"log": {"loglevel": "none"},
		"outbounds": [{"tag": "direct", "protocol": "freedom"}, {"tag": "hit", "protocol": "freedom"}],
		"routing": {"rules": [{"domain": [` + strconvQuote(rule) + `], "outboundTag": "hit"}]}}`
	jc, err := serial.DecodeJSONConfig(strings.NewReader(cfg))
	if err != nil {
		t.Fatal(err)
	}
	pb, err := jc.Build()
	if err != nil {
		t.Fatalf("%s: %v", rule, err)
	}
	inst, err := core.New(pb)
	if err != nil {
		t.Fatalf("%s: %v", rule, err)
	}
	defer inst.Close()
	router := inst.GetFeature(routing.RouterType()).(routing.Router)
	for _, name := range names {
		ctx := &routingsession.Context{
			Outbound: &session.Outbound{Target: xnet.TCPDestination(xnet.DomainAddress(name), 443)},
			Content:  &session.Content{SkipDNSResolve: true},
		}
		route, err := router.PickRoute(ctx)
		switch {
		case err == nil && route.GetOutboundTag() == "hit":
			return true
		case err != nil && !errors.Is(err, common.ErrNoClue):
			t.Fatalf("%s, %s: %v", rule, name, err)
		}
	}
	return false
}

func strconvQuote(s string) string {
	b, _ := json.Marshal(s)
	return string(b)
}
