package main

import (
	"cmp"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strings"

	"github.com/xtls/xray-core/common/geodata"
)

// Списки доменов v2fly/domain-list-community (dlc) по описанию формата в
// его README: файл каталога data — список, имя файла — код. Разбор свой
// (D-20, D-27), строгий: непонятная строка — ошибка с файлом и номером
// строки, а не пропуск (D-05).

var (
	// Код списка — тот, что можно написать в конфигурации DPpass после
	// geosite: (docs/CONFIG_FORMAT.md, без «@» — он отделяет атрибуты), и
	// не длиннее 64: Xray ищет запись по коду с длиной в один байт.
	listName = regexp.MustCompile(`^[a-z0-9][a-z0-9_!.-]{0,63}$`)
	// Атрибут не начинается с «-»: «@-атрибут» — фильтр include, а не атрибут правила.
	attrName = regexp.MustCompile(`^[a-z0-9_!.][a-z0-9_!.-]{0,63}$`)
	// Имя домена в нижнем регистре: метки из букв, цифр, «-» и «_».
	domainName = regexp.MustCompile(`^[a-z0-9_-]+(\.[a-z0-9_-]+)*$`)
	keyword    = regexp.MustCompile(`^[a-z0-9_.-]+$`)
)

// domainRule — правило списка: вид, значение и атрибуты (по алфавиту, без повторов).
type domainRule struct {
	kind  geodata.Domain_Type
	value string
	attrs []string
}

func (r domainRule) key() string {
	return fmt.Sprintf("%d\x00%s\x00%s", r.kind, r.value, strings.Join(r.attrs, "@"))
}

func (r domainRule) has(attr string) bool { return slices.Contains(r.attrs, attr) }

// inclusion — include:список с фильтром: правила, у которых есть все атрибуты
// must и нет ни одного из mustNot.
type inclusion struct {
	list    string
	must    []string
	mustNot []string
	where   string
}

type dlcList struct {
	rules    []domainRule
	includes []inclusion
}

// dlcStats — что сборщик сделал с данными: для отчёта о сборке.
type dlcStats struct {
	files      int
	lowercased int
}

// dlcLists — каталог data → списки с раскрытыми include и аффилиациями,
// без повторов, в постоянном порядке.
func dlcLists(dir string) (map[string][]domainRule, dlcStats, error) {
	var st dlcStats
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, st, fmt.Errorf("dlc: %w", err)
	}
	lists := map[string]*dlcList{}
	get := func(name string) *dlcList {
		if lists[name] == nil {
			lists[name] = &dlcList{}
		}
		return lists[name]
	}
	for _, e := range entries {
		if !e.Type().IsRegular() {
			return nil, st, fmt.Errorf("dlc: %s — не обычный файл", e.Name())
		}
		if !listName.MatchString(e.Name()) {
			return nil, st, fmt.Errorf("dlc: имя списка %q нельзя написать в конфигурации (geosite:)", e.Name())
		}
		raw, err := os.ReadFile(filepath.Join(dir, e.Name()))
		if err != nil {
			return nil, st, fmt.Errorf("dlc: %w", err)
		}
		st.files++
		if err := parseList(e.Name(), string(raw), get, &st); err != nil {
			return nil, st, err
		}
	}
	if st.files == 0 {
		return nil, st, fmt.Errorf("dlc: каталог %s пуст", dir)
	}
	resolved := map[string][]domainRule{}
	for name := range lists {
		if _, err := resolve(name, lists, resolved, nil); err != nil {
			return nil, st, err
		}
	}
	return resolved, st, nil
}

func parseList(name, text string, get func(string) *dlcList, st *dlcStats) error {
	list := get(name)
	for i, line := range strings.Split(text, "\n") {
		where := fmt.Sprintf("dlc: %s:%d", name, i+1)
		if c := strings.IndexByte(line, '#'); c >= 0 {
			line = line[:c]
		}
		fields := strings.Fields(line)
		if len(fields) == 0 {
			continue
		}
		head, tail := fields[0], fields[1:]
		if target, ok := strings.CutPrefix(head, "include:"); ok {
			inc := inclusion{list: target, where: where}
			if !listName.MatchString(target) {
				return fmt.Errorf("%s: include:%q — неверное имя списка", where, target)
			}
			for _, t := range tail {
				switch {
				case strings.HasPrefix(t, "@-") && attrName.MatchString(t[2:]):
					inc.mustNot = append(inc.mustNot, t[2:])
				case strings.HasPrefix(t, "@") && attrName.MatchString(t[1:]):
					inc.must = append(inc.must, t[1:])
				default:
					return fmt.Errorf("%s: у include ожидались фильтры @атрибут или @-атрибут, а не %q", where, t)
				}
			}
			list.includes = append(list.includes, inc)
			continue
		}
		rule, lowered, err := parseRule(head)
		if err != nil {
			return fmt.Errorf("%s: %w", where, err)
		}
		if lowered {
			st.lowercased++
		}
		var affiliations []string
		for _, t := range tail {
			switch {
			case strings.HasPrefix(t, "@") && attrName.MatchString(t[1:]):
				rule.attrs = append(rule.attrs, t[1:])
			case strings.HasPrefix(t, "&") && listName.MatchString(t[1:]):
				affiliations = append(affiliations, t[1:])
			default:
				return fmt.Errorf("%s: после правила ожидались @атрибут или &список, а не %q", where, t)
			}
		}
		slices.Sort(rule.attrs)
		rule.attrs = slices.Compact(rule.attrs)
		list.rules = append(list.rules, rule)
		for _, a := range affiliations {
			get(a).rules = append(get(a).rules, rule)
		}
	}
	return nil
}

// parseRule — правило без атрибутов. Имена доменов — без учёта регистра
// (DNS), поэтому домен, полное имя и подстрока приводятся к нижнему
// регистру — смысл не меняется; lowered — пришлось ли приводить.
// Регулярное выражение не трогается: оно проверяется компиляцией.
func parseRule(s string) (domainRule, bool, error) {
	kind, value := geodata.Domain_Domain, s
	if p, v, ok := strings.Cut(s, ":"); ok {
		switch p {
		case "domain":
			kind = geodata.Domain_Domain
		case "full":
			kind = geodata.Domain_Full
		case "keyword":
			kind = geodata.Domain_Substr
		case "regexp":
			kind = geodata.Domain_Regex
		default:
			return domainRule{}, false, fmt.Errorf("неизвестный вид правила %q", p+":")
		}
		value = v
	}
	if kind == geodata.Domain_Regex {
		if _, err := regexp.Compile(value); err != nil || value == "" {
			return domainRule{}, false, fmt.Errorf("регулярное выражение %q не компилируется", value)
		}
		return domainRule{kind: kind, value: value}, false, nil
	}
	lower := strings.ToLower(value)
	ok := domainName.MatchString(lower) && len(lower) <= 253
	if kind == geodata.Domain_Substr {
		ok = keyword.MatchString(lower)
	}
	if !ok {
		return domainRule{}, false, fmt.Errorf("значение %q — не имя домена", value)
	}
	return domainRule{kind: kind, value: lower}, lower != value, nil
}

// resolve раскрывает include списка (рекурсивно, с фильтрами), убирает
// повторы и сортирует. Одинаковые правила с разными атрибутами остаются
// разными: Xray выбирает по атрибутам правила, и их слияние расширило бы
// выборку вида geosite:список@a@b.
func resolve(name string, lists map[string]*dlcList, done map[string][]domainRule, stack []string) ([]domainRule, error) {
	if r, ok := done[name]; ok {
		return r, nil
	}
	if i := slices.Index(stack, name); i >= 0 {
		return nil, fmt.Errorf("dlc: include по кругу: %s", strings.Join(append(stack[i:], name), " → "))
	}
	list := lists[name]
	stack = append(stack, name)
	rules := slices.Clone(list.rules)
	for _, inc := range list.includes {
		if lists[inc.list] == nil {
			return nil, fmt.Errorf("%s: include:%s — такого списка нет", inc.where, inc.list)
		}
		sub, err := resolve(inc.list, lists, done, stack)
		if err != nil {
			return nil, err
		}
		for _, r := range sub {
			if allHave(r, inc.must) && noneHave(r, inc.mustNot) {
				rules = append(rules, r)
			}
		}
	}
	slices.SortFunc(rules, func(a, b domainRule) int {
		return cmp.Or(cmp.Compare(a.value, b.value), cmp.Compare(a.kind, b.kind), cmp.Compare(strings.Join(a.attrs, "@"), strings.Join(b.attrs, "@")))
	})
	rules = slices.CompactFunc(rules, func(a, b domainRule) bool { return a.key() == b.key() })
	done[name] = rules
	return rules, nil
}

func allHave(r domainRule, attrs []string) bool {
	for _, a := range attrs {
		if !r.has(a) {
			return false
		}
	}
	return true
}

func noneHave(r domainRule, attrs []string) bool {
	for _, a := range attrs {
		if r.has(a) {
			return false
		}
	}
	return true
}
