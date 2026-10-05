// Command dppass-geodata — набор геоданных DPpass (D-27): geoip.dat и
// geosite.dat в формате Xray закреплённой версии (common/geodata) из
// первичных источников, собранные своим кодом.
//
//	dppass-geodata -dlc DIR -dlc-version V -dbip FILE.csv.gz -dbip-release YYYY-MM -out DIR [-verify]
//
// Источники:
//   - geosite — все списки v2fly/domain-list-community (каталог data
//     Go-модуля закреплённой версии; dlc.go);
//   - geoip — все страны DB-IP «IP to Country Lite» (dbip.go) и своя
//     таблица private (private.go).
//
// В DIR — geoip.dat, geosite.dat, NOTICE.txt (атрибуция DB-IP и
// уведомление MIT dlc) и manifest.json: источники, SHA-256 файлов и
// оглавление (коды geoip, коды geosite с их атрибутами). Сборка
// детерминирована: из тех же источников — те же байты.
//
// -verify: ядро Xray закреплённой версии (тот же go.mod) загружает каждый
// код набора — так проверяется весь набор, а не выборка.
//
// Код возврата: 0 — набор собран; 1 — ошибка источников или сборки; 2 — неверный вызов.
package main

import (
	"bytes"
	"cmp"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strings"

	"github.com/xtls/xray-core/common/geodata"
	"github.com/xtls/xray-core/common/platform"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
	"google.golang.org/protobuf/proto"

	// Разбор конфигурации и маршрутизатор — как в собранном ядре.
	_ "github.com/xtls/xray-core/main/distro/all"
)

// manifestFormat — формат оглавления набора; новая версия — при любом
// несовместимом изменении (его читает приложение, engine.xray.GeodataManifest).
const (
	manifestFormat  = "dppass.geodata"
	manifestVersion = 1
	noticeFile      = "NOTICE.txt"
	manifestFile    = "manifest.json"
)

var (
	dlcVersion = regexp.MustCompile(`^v[0-9]+\.[0-9]+\.[0-9]+-[0-9]{14}-([0-9a-f]{12})$`)
	dbipMonth  = regexp.MustCompile(`^20[0-9]{2}-(0[1-9]|1[0-2])$`)
)

type sources struct {
	dlcDir, dlcVersion, dbipFile, dbipRelease string
}

func main() {
	var s sources
	fs := flag.NewFlagSet("dppass-geodata", flag.ExitOnError)
	fs.StringVar(&s.dlcDir, "dlc", "", "корень Go-модуля github.com/v2fly/domain-list-community")
	fs.StringVar(&s.dlcVersion, "dlc-version", "", "его версия (псевдоверсия с коммитом)")
	fs.StringVar(&s.dbipFile, "dbip", "", "DB-IP IP to Country Lite, CSV в gzip")
	fs.StringVar(&s.dbipRelease, "dbip-release", "", "выпуск DB-IP, ГГГГ-ММ")
	out := fs.String("out", "", "каталог набора")
	verify := fs.Bool("verify", false, "ядро Xray загружает каждый код набора")
	_ = fs.Parse(os.Args[1:])
	if s.dlcDir == "" || s.dlcVersion == "" || s.dbipFile == "" || s.dbipRelease == "" || *out == "" {
		fmt.Fprintln(os.Stderr, "использование: dppass-geodata -dlc DIR -dlc-version V -dbip FILE -dbip-release YYYY-MM -out DIR")
		os.Exit(2)
	}
	summary, err := build(s, *out)
	if err == nil && *verify {
		var n int
		if n, err = verifyWithXray(*out); err == nil {
			summary += fmt.Sprintf("; Xray загрузил все %d кодов", n)
		}
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "✗", err)
		os.Exit(1)
	}
	fmt.Println(summary)
}

// manifest — оглавление набора (manifest.json).
type manifest struct {
	Format        string              `json:"format"`
	FormatVersion int                 `json:"format_version"`
	Sources       manifestSources     `json:"sources"`
	Files         []manifestEntry     `json:"files"`
	GeoIP         []string            `json:"geoip"`
	GeoSite       map[string][]string `json:"geosite"`
}

type manifestSources struct {
	Dlc  dlcSource  `json:"dlc"`
	Dbip dbipSource `json:"dbip"`
}

type dlcSource struct {
	Module  string `json:"module"`
	Version string `json:"version"`
	Commit  string `json:"commit"`
}

type dbipSource struct {
	Release string `json:"release"`
	SHA256  string `json:"sha256"`
}

type manifestEntry struct {
	Name   string `json:"name"`
	SHA256 string `json:"sha256"`
	Size   int    `json:"size"`
}

func build(s sources, out string) (string, error) {
	m := dlcVersion.FindStringSubmatch(s.dlcVersion)
	if m == nil {
		return "", fmt.Errorf("версия dlc %q — ожидалась псевдоверсия Go с коммитом", s.dlcVersion)
	}
	if !dbipMonth.MatchString(s.dbipRelease) {
		return "", fmt.Errorf("выпуск DB-IP %q — ожидалось ГГГГ-ММ", s.dbipRelease)
	}
	license, err := os.ReadFile(filepath.Join(s.dlcDir, "LICENSE"))
	if err != nil {
		return "", fmt.Errorf("dlc: нет LICENSE — уведомление MIT обязательно: %w", err)
	}
	lists, st, err := dlcLists(filepath.Join(s.dlcDir, "data"))
	if err != nil {
		return "", err
	}
	raw, err := os.ReadFile(s.dbipFile)
	if err != nil {
		return "", err
	}
	countries, rows, err := dbipCountries(bytes.NewReader(raw))
	if err != nil {
		return "", err
	}
	priv, err := privateSets()
	if err != nil {
		return "", err
	}
	if _, clash := countries[privateCode]; clash {
		return "", fmt.Errorf("DB-IP: код %s совпал со своей таблицей private", privateCode)
	}
	countries[privateCode] = priv

	ips, ipCodes, nets := geoIPList(countries)
	sites, siteCodes, domains := geoSiteList(lists)
	ipBytes, err := proto.MarshalOptions{Deterministic: true}.Marshal(ips)
	if err != nil {
		return "", err
	}
	siteBytes, err := proto.MarshalOptions{Deterministic: true}.Marshal(sites)
	if err != nil {
		return "", err
	}
	notice := noticeText(s, m[1], license)

	files := []struct {
		name string
		data []byte
	}{
		{geodata.DefaultGeoIPDat, ipBytes},
		{geodata.DefaultGeoSiteDat, siteBytes},
		{noticeFile, notice},
	}
	man := manifest{
		Format:        manifestFormat,
		FormatVersion: manifestVersion,
		Sources: manifestSources{
			Dlc:  dlcSource{Module: "github.com/v2fly/domain-list-community", Version: s.dlcVersion, Commit: m[1]},
			Dbip: dbipSource{Release: s.dbipRelease, SHA256: sum(raw)},
		},
		GeoIP:   ipCodes,
		GeoSite: siteCodes,
	}
	if err := os.MkdirAll(out, 0o755); err != nil {
		return "", err
	}
	for _, f := range files {
		if err := os.WriteFile(filepath.Join(out, f.name), f.data, 0o644); err != nil {
			return "", err
		}
		man.Files = append(man.Files, manifestEntry{Name: f.name, SHA256: sum(f.data), Size: len(f.data)})
	}
	mb, err := json.MarshalIndent(man, "", "  ")
	if err != nil {
		return "", err
	}
	if err := os.WriteFile(filepath.Join(out, manifestFile), append(mb, '\n'), 0o644); err != nil {
		return "", err
	}
	return fmt.Sprintf("✓ геоданные: geoip — %d кодов, %d подсетей (DB-IP %s, %d строк), %.1f МиБ; "+
		"geosite — %d списков, %d правил (dlc %s, %d файлов, приведено к нижнему регистру: %d), %.1f МиБ",
		len(ipCodes), nets, s.dbipRelease, rows, mib(len(ipBytes)),
		len(siteCodes), domains, m[1], st.files, st.lowercased, mib(len(siteBytes))), nil
}

// geoIPList — записи по коду (в верхнем регистре: так их ищет Xray,
// common/geodata/rule_parser.go), подсети — по возрастанию адреса,
// сначала IPv4. Оглавление — коды в нижнем регистре, как в конфигурации.
func geoIPList(countries map[string]map[family]*ipSet) (*geodata.GeoIPList, []string, int) {
	list := &geodata.GeoIPList{}
	var codes []string
	nets := 0
	for _, code := range sortedKeys(countries) {
		entry := &geodata.GeoIP{Code: code}
		for _, f := range []family{v4, v6} {
			set := countries[code][f]
			if set == nil {
				continue
			}
			for _, p := range set.prefixes() {
				entry.Cidr = append(entry.Cidr, &geodata.CIDR{Ip: p.Addr().AsSlice(), Prefix: uint32(p.Bits())})
			}
		}
		if len(entry.Cidr) == 0 {
			continue
		}
		nets += len(entry.Cidr)
		list.Entry = append(list.Entry, entry)
		codes = append(codes, strings.ToLower(code))
	}
	return list, codes, nets
}

// geoSiteList — записи по коду (верхний регистр), правила — в порядке
// resolve; атрибут — ключ со значением true (Xray выбирает по наличию ключа).
// Оглавление — код → атрибуты, встречающиеся в списке.
func geoSiteList(lists map[string][]domainRule) (*geodata.GeoSiteList, map[string][]string, int) {
	list := &geodata.GeoSiteList{}
	codes := map[string][]string{}
	total := 0
	for _, name := range sortedKeys(lists) {
		entry := &geodata.GeoSite{Code: strings.ToUpper(name)}
		attrs := []string{}
		for _, r := range lists[name] {
			d := &geodata.Domain{Type: r.kind, Value: r.value}
			for _, a := range r.attrs {
				d.Attribute = append(d.Attribute, &geodata.Domain_Attribute{Key: a, TypedValue: &geodata.Domain_Attribute_BoolValue{BoolValue: true}})
				attrs = append(attrs, a)
			}
			entry.Domain = append(entry.Domain, d)
		}
		total += len(entry.Domain)
		slices.Sort(attrs)
		codes[name] = slices.Compact(attrs)
		list.Entry = append(list.Entry, entry)
	}
	return list, codes, total
}

// verifyWithXray — конфигурация с правилом на каждый код набора проходит
// core.New (как `xray run -test`: без входов, без сети); возвращает число кодов.
func verifyWithXray(dir string) (int, error) {
	raw, err := os.ReadFile(filepath.Join(dir, manifestFile))
	if err != nil {
		return 0, err
	}
	var m manifest
	if err := json.Unmarshal(raw, &m); err != nil {
		return 0, err
	}
	var ips, sites []string
	for _, c := range m.GeoIP {
		ips = append(ips, "geoip:"+c)
	}
	for _, c := range sortedKeys(m.GeoSite) {
		sites = append(sites, "geosite:"+c)
	}
	rules, _ := json.Marshal([]map[string]any{
		{"ip": ips, "outboundTag": "direct"},
		{"domain": sites, "outboundTag": "direct"},
	})
	cfg := `{"log": {"loglevel": "none"}, "outbounds": [{"tag": "direct", "protocol": "freedom"}], "routing": {"rules": ` + string(rules) + `}}`
	if err := os.Setenv(platform.AssetLocation, dir); err != nil {
		return 0, err
	}
	jc, err := serial.DecodeJSONConfig(strings.NewReader(cfg))
	if err != nil {
		return 0, err
	}
	pb, err := jc.Build()
	if err != nil {
		return 0, fmt.Errorf("Xray не принял набор: %w", err)
	}
	inst, err := core.New(pb)
	if err != nil {
		return 0, fmt.Errorf("Xray не принял набор: %w", err)
	}
	return len(ips) + len(sites), inst.Close()
}

func noticeText(s sources, commit string, license []byte) []byte {
	var b strings.Builder
	fmt.Fprintf(&b, "Геоданные DPpass: собраны своим инструментом (tools/xray/geodata) из первичных источников (D-27).\n\n")
	fmt.Fprintf(&b, "geoip.dat — IP Geolocation by DB-IP (https://db-ip.com)\n")
	fmt.Fprintf(&b, "  «IP to Country Lite», выпуск %s; лицензия CC BY 4.0\n", s.dbipRelease)
	fmt.Fprintf(&b, "  (https://creativecommons.org/licenses/by/4.0/).\n")
	fmt.Fprintf(&b, "  Изменения: диапазоны переведены в подсети; адреса без страны (ZZ) не\n")
	fmt.Fprintf(&b, "  включены; код private — своя таблица DPpass по реестрам особых адресов IANA.\n\n")
	fmt.Fprintf(&b, "geosite.dat — v2fly/domain-list-community, версия %s (коммит %s); лицензия MIT:\n\n", s.dlcVersion, commit)
	b.Write(bytes.TrimRight(license, "\n"))
	b.WriteString("\n")
	return []byte(b.String())
}

func sum(b []byte) string {
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}

func mib(n int) float64 { return float64(n) / (1 << 20) }

func sortedKeys[V any](m map[string]V) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	slices.SortFunc(keys, cmp.Compare[string])
	return keys
}
