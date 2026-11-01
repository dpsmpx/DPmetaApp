"""Маски путей профиля (ТЗ, §7.2).

Маска сравнивается с полным путём от корня репозитория:
`*` и `?` — внутри одного сегмента, `**` — любое число сегментов (и ноль),
`[…]` — набор, `{a,b}` — варианты; маска с `/` на конце — всё под каталогом;
`module:<имя>` — маски модуля из `[modules]`.
"""
import re

from .errors import UsageError


def _translate(mask):
    out, i, n = [], 0, len(mask)
    while i < n:
        if mask.startswith("**", i):
            at_start = i == 0 or mask[i - 1] == "/"
            if at_start and mask.startswith("**/", i):
                out.append("(?:[^/]+/)*")  # «**/» — ноль и больше каталогов
                i += 3
            else:
                out.append(".*")
                i += 2
        elif mask[i] == "*":
            out.append("[^/]*")
            i += 1
        elif mask[i] == "?":
            out.append("[^/]")
            i += 1
        elif mask[i] == "[":
            j = mask.find("]", i + 2)
            if j < 0:
                raise UsageError(f"маска «{mask}»: нет закрывающей «]»")
            body = mask[i + 1:j]
            if body.startswith("!"):
                body = "^" + body[1:]
            out.append("[" + body.replace("\\", "\\\\") + "]")
            i = j + 1
        elif mask[i] == "{":
            j = mask.find("}", i)
            if j < 0:
                raise UsageError(f"маска «{mask}»: нет закрывающей «}}»")
            variants = mask[i + 1:j].split(",")
            out.append("(?:" + "|".join(_translate(v) for v in variants) + ")")
            i = j + 1
        else:
            out.append(re.escape(mask[i]))
            i += 1
    return "".join(out)


def compile_mask(mask):
    """Маска → регэксп полного пути. Каталог («docs/») — всё под ним."""
    if not mask or mask.startswith("/"):
        raise UsageError(f"маска «{mask}»: нужен путь от корня репозитория без «/» в начале")
    if mask.endswith("/"):
        return re.compile(_translate(mask) + ".+")
    return re.compile(_translate(mask))


class MaskSet:
    """Набор масок с раскрытием `module:<имя>`; совпадение — полное."""

    def __init__(self, masks, modules=None):
        self.masks = list(masks)
        self._compiled = []
        for mask in self.masks:
            if mask.startswith("module:"):
                name = mask[len("module:"):]
                if modules is None or name not in modules:
                    raise UsageError(f"маска «{mask}»: модуля «{name}» нет в [modules]")
                self._compiled += [(mask, compile_mask(m)) for m in modules[name]]
            else:
                self._compiled.append((mask, compile_mask(mask)))

    def match(self, path):
        return any(rx.fullmatch(path) for _, rx in self._compiled)

    def matching_mask(self, path):
        """Первая маска (как записана в профиле), совпавшая с путём, или None."""
        for mask, rx in self._compiled:
            if rx.fullmatch(path):
                return mask
        return None

    def dead(self, paths):
        """Маски, которые ни с одним путём не совпали (для `module:` — сам модуль)."""
        alive = set()
        for path in paths:
            for mask, rx in self._compiled:
                if mask not in alive and rx.fullmatch(path):
                    alive.add(mask)
        return [m for m in self.masks if m not in alive]
