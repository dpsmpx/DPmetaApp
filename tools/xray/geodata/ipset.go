package main

import (
	"fmt"
	"math/bits"
	"net/netip"
	"slices"
)

// u128 — адрес как число: IPv4 занимает младшие 32 бита, IPv6 — все 128.
// Множества адресов считаются диапазонами чисел, а не подсетями: так
// объединение, вычитание и перевод в подсети — простая арифметика.
type u128 struct{ hi, lo uint64 }

func (a u128) cmp(b u128) int {
	switch {
	case a.hi != b.hi:
		if a.hi < b.hi {
			return -1
		}
		return 1
	case a.lo != b.lo:
		if a.lo < b.lo {
			return -1
		}
		return 1
	}
	return 0
}

func (a u128) add(b u128) (u128, bool) {
	lo, c := bits.Add64(a.lo, b.lo, 0)
	hi, c := bits.Add64(a.hi, b.hi, c)
	return u128{hi, lo}, c != 0
}

func (a u128) inc() (u128, bool) { return a.add(u128{0, 1}) }

func (a u128) dec() u128 {
	lo, b := bits.Sub64(a.lo, 1, 0)
	hi, _ := bits.Sub64(a.hi, 0, b)
	return u128{hi, lo}
}

func (a u128) trailingZeros() int {
	if a.lo != 0 {
		return bits.TrailingZeros64(a.lo)
	}
	if a.hi != 0 {
		return 64 + bits.TrailingZeros64(a.hi)
	}
	return 128
}

// ones — число из k единичных битов (k ≤ 128): размер блока 2^k минус один.
func ones(k int) u128 {
	switch {
	case k <= 0:
		return u128{}
	case k < 64:
		return u128{0, 1<<uint(k) - 1}
	case k == 64:
		return u128{0, ^uint64(0)}
	case k < 128:
		return u128{1<<uint(k-64) - 1, ^uint64(0)}
	}
	return u128{^uint64(0), ^uint64(0)}
}

// family — семейство адресов: 32 или 128 бит.
type family int

const (
	v4 family = 32
	v6 family = 128
)

func (f family) String() string {
	if f == v4 {
		return "IPv4"
	}
	return "IPv6"
}

func familyOf(a netip.Addr) family {
	if a.Is4() {
		return v4
	}
	return v6
}

func toU128(a netip.Addr) u128 {
	if a.Is4() {
		b := a.As4()
		return u128{0, uint64(b[0])<<24 | uint64(b[1])<<16 | uint64(b[2])<<8 | uint64(b[3])}
	}
	b := a.As16()
	var hi, lo uint64
	for i := 0; i < 8; i++ {
		hi = hi<<8 | uint64(b[i])
		lo = lo<<8 | uint64(b[8+i])
	}
	return u128{hi, lo}
}

func fromU128(f family, x u128) netip.Addr {
	if f == v4 {
		v := uint32(x.lo)
		return netip.AddrFrom4([4]byte{byte(v >> 24), byte(v >> 16), byte(v >> 8), byte(v)})
	}
	var b [16]byte
	for i := 0; i < 8; i++ {
		b[7-i] = byte(x.hi >> (8 * i))
		b[15-i] = byte(x.lo >> (8 * i))
	}
	return netip.AddrFrom16(b)
}

// span — диапазон адресов [first, last] одного семейства, включительно.
type span struct{ first, last u128 }

// ipSet — множество адресов одного семейства: диапазоны по возрастанию,
// не пересекаются и не соприкасаются (соседние слиты).
type ipSet struct {
	fam   family
	spans []span
}

func newSet(f family, spans ...span) *ipSet {
	s := &ipSet{fam: f, spans: spans}
	s.normalize()
	return s
}

// prefixSpan — диапазон подсети; адрес подсети должен быть её началом.
func prefixSpan(p netip.Prefix) (family, span, error) {
	if !p.IsValid() {
		return 0, span{}, fmt.Errorf("неверная подсеть %v", p)
	}
	if p.Masked() != p {
		return 0, span{}, fmt.Errorf("подсеть %v: адрес не совпадает с началом подсети %v", p, p.Masked())
	}
	f := familyOf(p.Addr())
	first := toU128(p.Addr())
	last, _ := first.add(ones(int(f) - p.Bits()))
	return f, span{first, last}, nil
}

func (s *ipSet) normalize() {
	slices.SortFunc(s.spans, func(a, b span) int { return a.first.cmp(b.first) })
	out := s.spans[:0]
	for _, r := range s.spans {
		if n := len(out); n > 0 {
			prev := &out[n-1]
			next, overflow := prev.last.inc()
			if overflow || r.first.cmp(next) <= 0 {
				if r.last.cmp(prev.last) > 0 {
					prev.last = r.last
				}
				continue
			}
		}
		out = append(out, r)
	}
	s.spans = out
}

// add — объединение с диапазоном.
func (s *ipSet) add(r span) {
	s.spans = append(s.spans, r)
	s.normalize()
}

// remove — вычитание диапазона.
func (s *ipSet) remove(r span) {
	var out []span
	for _, x := range s.spans {
		if x.last.cmp(r.first) < 0 || x.first.cmp(r.last) > 0 {
			out = append(out, x)
			continue
		}
		if x.first.cmp(r.first) < 0 {
			out = append(out, span{x.first, r.first.dec()})
		}
		if x.last.cmp(r.last) > 0 {
			next, _ := r.last.inc()
			out = append(out, span{next, x.last})
		}
	}
	s.spans = out
}

func (s *ipSet) contains(a netip.Addr) bool {
	if familyOf(a) != s.fam {
		return false
	}
	x := toU128(a)
	i, _ := slices.BinarySearchFunc(s.spans, x, func(r span, t u128) int {
		switch {
		case r.last.cmp(t) < 0:
			return -1
		case r.first.cmp(t) > 0:
			return 1
		}
		return 0
	})
	return i < len(s.spans) && s.spans[i].first.cmp(x) <= 0 && x.cmp(s.spans[i].last) <= 0
}

// prefixes — наименьший набор подсетей, точно покрывающий множество, по
// возрастанию адреса. Каждый диапазон режется на самые крупные блоки,
// выровненные по своему размеру.
func (s *ipSet) prefixes() []netip.Prefix {
	var out []netip.Prefix
	width := int(s.fam)
	for _, r := range s.spans {
		cur := r.first
		for {
			k := min(cur.trailingZeros(), width)
			for k > 0 {
				end, overflow := cur.add(ones(k))
				if !overflow && end.cmp(r.last) <= 0 {
					break
				}
				k--
			}
			out = append(out, netip.PrefixFrom(fromU128(s.fam, cur), width-k))
			end, _ := cur.add(ones(k))
			if end.cmp(r.last) >= 0 {
				break
			}
			cur, _ = end.inc()
		}
	}
	return out
}
