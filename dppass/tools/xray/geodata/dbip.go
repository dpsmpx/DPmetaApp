package main

import (
	"bufio"
	"compress/gzip"
	"fmt"
	"io"
	"net/netip"
	"regexp"
	"strings"
)

// dbipUnknown — пометка DB-IP для адресов без страны (зарезервированные,
// частные): это не страна, в набор не входит; такие адреса покрывает
// своя таблица private.
const dbipUnknown = "ZZ"

var countryCode = regexp.MustCompile(`^[A-Z]{2}$`)

// dbipCountries — DB-IP «IP to Country Lite» (CSV в gzip: начало,конец,код
// страны) → множества адресов по стране и семейству. Разбор строгий:
// строка не по формату, диапазон задом наперёд, пересечение с предыдущей
// строкой — ошибка с номером строки, а не пропуск.
func dbipCountries(r io.Reader) (map[string]map[family]*ipSet, int, error) {
	gz, err := gzip.NewReader(r)
	if err != nil {
		return nil, 0, fmt.Errorf("DB-IP: %w", err)
	}
	defer gz.Close()
	countries := map[string]map[family]*ipSet{}
	spans := map[string]map[family][]span{}
	last := map[family]*u128{}
	sc := bufio.NewScanner(gz)
	n := 0
	for sc.Scan() {
		n++
		line := sc.Text()
		fields := strings.Split(line, ",")
		if len(fields) != 3 {
			return nil, 0, fmt.Errorf("DB-IP, строка %d: ожидалось «начало,конец,страна», а не %q", n, line)
		}
		first, err1 := netip.ParseAddr(fields[0])
		lastAddr, err2 := netip.ParseAddr(fields[1])
		if err1 != nil || err2 != nil || first.Zone() != "" || lastAddr.Zone() != "" || first.Is4In6() || lastAddr.Is4In6() {
			return nil, 0, fmt.Errorf("DB-IP, строка %d: неверный адрес в %q", n, line)
		}
		f := familyOf(first)
		if familyOf(lastAddr) != f {
			return nil, 0, fmt.Errorf("DB-IP, строка %d: начало и конец из разных семейств адресов", n)
		}
		sp := span{toU128(first), toU128(lastAddr)}
		if sp.first.cmp(sp.last) > 0 {
			return nil, 0, fmt.Errorf("DB-IP, строка %d: начало диапазона больше конца", n)
		}
		if prev := last[f]; prev != nil && sp.first.cmp(*prev) <= 0 {
			return nil, 0, fmt.Errorf("DB-IP, строка %d: диапазон пересекается с предыдущими или идёт не по порядку", n)
		}
		end := sp.last
		last[f] = &end
		code := fields[2]
		if !countryCode.MatchString(code) {
			return nil, 0, fmt.Errorf("DB-IP, строка %d: код страны %q", n, code)
		}
		if code == dbipUnknown {
			continue
		}
		if spans[code] == nil {
			spans[code] = map[family][]span{}
		}
		spans[code][f] = append(spans[code][f], sp)
	}
	if err := sc.Err(); err != nil {
		return nil, 0, fmt.Errorf("DB-IP: %w", err)
	}
	if n == 0 {
		return nil, 0, fmt.Errorf("DB-IP: файл пуст")
	}
	for code, byFam := range spans {
		countries[code] = map[family]*ipSet{}
		for f, s := range byFam {
			countries[code][f] = newSet(f, s...)
		}
	}
	return countries, n, nil
}
