package main

import (
	"cmp"
	"fmt"
	"net/netip"
	"slices"
)

// privateCode — код набора для адресов, которые не ведут к узлу в
// интернете. Не страна из чужой базы, а своя таблица (D-27): в DB-IP
// такие диапазоны помечены «ZZ» и в набор не попадают.
const privateCode = "PRIVATE"

// specialBlock — блок реестра особых адресов: private=true — блок не
// достижим глобально («Globally Reachable: False»).
type specialBlock struct {
	prefix  string
	private bool
	ref     string
}

// specialBlocks — реестры IANA «IPv4 Special-Purpose Address Registry» и
// «IPv6 Special-Purpose Address Registry» (RFC 6890; сверено 5 октября
// 2026 по www.iana.org/assignments), записанные своими словами: блок и
// достижим ли он глобально. Блоки без ответа в реестре («N/A» или пусто)
// — private=false: в private попадает только то, что реестр прямо
// называет недостижимым.
//
// Внутри блока решает самый узкий блок реестра: 192.0.0.9/32 достижим,
// хотя 192.0.0.0/24 — нет; из 192.88.99.0/24 (без ответа) недостижим
// только 192.88.99.2/32.
//
// Кроме реестра — групповые адреса (RFC 5771, RFC 4291): они не адресуют
// один узел, и пускать их через сервер незачем.
var specialBlocks = []specialBlock{
	{"0.0.0.0/8", true, "RFC 791: «эта сеть»"},
	{"0.0.0.0/32", true, "RFC 1122: «этот узел»"},
	{"10.0.0.0/8", true, "RFC 1918: частные сети"},
	{"100.64.0.0/10", true, "RFC 6598: общее адресное пространство (CGNAT)"},
	{"127.0.0.0/8", true, "RFC 1122: петля"},
	{"169.254.0.0/16", true, "RFC 3927: локальные для канала"},
	{"172.16.0.0/12", true, "RFC 1918: частные сети"},
	{"192.0.0.0/24", true, "RFC 6890: назначения протоколов IETF"},
	{"192.0.0.0/29", true, "RFC 7335: продолжение службы IPv4"},
	{"192.0.0.8/32", true, "RFC 7600: фиктивный адрес IPv4"},
	{"192.0.0.9/32", false, "RFC 7723: anycast PCP"},
	{"192.0.0.10/32", false, "RFC 8155: anycast TURN"},
	{"192.0.0.170/32", true, "RFC 8880, RFC 7050: обнаружение NAT64/DNS64"},
	{"192.0.0.171/32", true, "RFC 8880, RFC 7050: обнаружение NAT64/DNS64"},
	{"192.0.2.0/24", true, "RFC 5737: документация (TEST-NET-1)"},
	{"192.31.196.0/24", false, "RFC 7535: AS112-v4"},
	{"192.52.193.0/24", false, "RFC 7450: AMT"},
	{"192.88.99.0/24", false, "RFC 7526: устаревший anycast ретрансляторов 6to4 (в реестре без ответа)"},
	{"192.88.99.2/32", true, "RFC 6751: anycast ретранслятора 6a44"},
	{"192.168.0.0/16", true, "RFC 1918: частные сети"},
	{"192.175.48.0/24", false, "RFC 7534: прямое делегирование AS112"},
	{"198.18.0.0/15", true, "RFC 2544: замеры производительности"},
	{"198.51.100.0/24", true, "RFC 5737: документация (TEST-NET-2)"},
	{"203.0.113.0/24", true, "RFC 5737: документация (TEST-NET-3)"},
	{"224.0.0.0/4", true, "RFC 5771: групповые адреса"},
	{"240.0.0.0/4", true, "RFC 1112: зарезервировано"},
	{"255.255.255.255/32", true, "RFC 8190, RFC 919: ограниченная широковещательная рассылка"},

	{"::1/128", true, "RFC 4291: петля"},
	{"::/128", true, "RFC 4291: неуказанный адрес"},
	{"::ffff:0:0/96", true, "RFC 4291: IPv4, отображённые в IPv6"},
	{"64:ff9b::/96", false, "RFC 6052: трансляция IPv4–IPv6"},
	{"64:ff9b:1::/48", true, "RFC 8215: локальная трансляция IPv4–IPv6"},
	{"100::/64", true, "RFC 6666: только для отбрасывания"},
	{"100:0:0:1::/64", true, "RFC 9780: фиктивный префикс IPv6"},
	{"2001::/23", true, "RFC 2928: назначения протоколов IETF (кроме более узких блоков)"},
	{"2001::/32", false, "RFC 4380: TEREDO (в реестре N/A)"},
	{"2001:1::1/128", false, "RFC 7723: anycast PCP"},
	{"2001:1::2/128", false, "RFC 8155: anycast TURN"},
	{"2001:1::3/128", false, "RFC 9665: anycast DNS-SD SRP"},
	{"2001:2::/48", true, "RFC 5180: замеры производительности"},
	{"2001:3::/32", false, "RFC 7450: AMT"},
	{"2001:4:112::/48", false, "RFC 7535: AS112-v6"},
	{"2001:10::/28", false, "RFC 4843: устаревший ORCHID (в реестре без ответа)"},
	{"2001:20::/28", false, "RFC 7343: ORCHIDv2"},
	{"2001:30::/28", false, "RFC 9374: DET"},
	{"2001:db8::/32", true, "RFC 3849: документация"},
	{"2002::/16", false, "RFC 3056: 6to4 (в реестре N/A)"},
	{"2620:4f:8000::/48", false, "RFC 7534: прямое делегирование AS112"},
	{"3fff::/20", true, "RFC 9637: документация"},
	{"5f00::/16", true, "RFC 9602: идентификаторы SRv6"},
	{"fc00::/7", true, "RFC 4193: уникальные локальные"},
	{"fe80::/10", true, "RFC 4291: локальные для канала"},
	{"ff00::/8", true, "RFC 4291: групповые адреса"},
}

// privateSets — множества private по семействам: блоки от широких к
// узким, каждый более узкий блок перекрывает решение широкого.
func privateSets() (map[family]*ipSet, error) {
	type block struct {
		fam  family
		bits int
		sp   span
		priv bool
	}
	var blocks []block
	for _, b := range specialBlocks {
		p, err := netip.ParsePrefix(b.prefix)
		if err != nil {
			return nil, fmt.Errorf("таблица private: %s: %w", b.prefix, err)
		}
		f, sp, err := prefixSpan(p)
		if err != nil {
			return nil, fmt.Errorf("таблица private: %w", err)
		}
		blocks = append(blocks, block{f, p.Bits(), sp, b.private})
	}
	slices.SortStableFunc(blocks, func(a, b block) int { return cmp.Compare(a.bits, b.bits) })
	sets := map[family]*ipSet{v4: newSet(v4), v6: newSet(v6)}
	for _, b := range blocks {
		if b.priv {
			sets[b.fam].add(b.sp)
		} else {
			sets[b.fam].remove(b.sp)
		}
	}
	return sets, nil
}
