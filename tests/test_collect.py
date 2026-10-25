"""Сборщик среза на синтетических клонах (F-09…F-20, D-04, D-08, D-11)."""

import contextlib
import io
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from tests.fixture import Repo, base_source, build_ecosystem, model_from, utc
from tools.dpvis import collect, formats, model as modelmod

NOW = utc("2026-10-05T12:00:00")


class CollectTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.root = build_ecosystem(Path(cls.tmp.name))
        cls.model = model_from(base_source())
        cls.state = collect.collect(cls.model, cls.root, NOW)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_window_and_schema(self):
        s = self.state
        self.assertEqual(s["schema"], "dpvis-state/1")
        self.assertEqual(s["as_of"], "2026-10-05T12:00:00Z")
        self.assertEqual(s["window"], {"start": "2026-09-06", "end": "2026-10-05", "days": 30,
                                       "timezone": "+03:00"})
        self.assertEqual(formats.validate_document(s), [])
        self.assertEqual(formats.validate_pair(self.model, s), [])

    def test_repo_days_ai_human_merges(self):
        g = self.state["nodes"]["alpha"]["git"]
        self.assertEqual(g["ref"], "origin/main")
        days = g["days"]
        self.assertEqual([i for i, v in enumerate(days["ai"]) if v], [27, 28])   # 3 и 4 октября
        self.assertEqual([i for i, v in enumerate(days["human"]) if v], [25])    # 1 октября
        self.assertEqual([i for i, v in enumerate(days["merges"]) if v], [29])   # 5 октября
        self.assertEqual(g["window"], {"ai": 2, "human": 1, "merges": 1})
        # все ветки: плюс невлитый коммит ИИ 5 октября; коммит после среза не считается
        self.assertEqual([i for i, v in enumerate(g["days_all"]["ai"]) if v], [27, 28, 29])
        self.assertEqual(sum(g["days_all"]["human"]), 1)
        self.assertEqual(g["history"], "complete")

    def test_unmerged_branch_and_activity(self):
        node = self.state["nodes"]["alpha"]
        branches = node["git"]["branches"]
        self.assertEqual([(b["name"], b["ahead"]) for b in branches], [("claude/feature", 1)])
        self.assertEqual(node["activity"]["state"], "working")
        rules = {r["rule"] for r in node["activity"]["reasons"]}
        self.assertEqual(rules, {"main", "branch"})
        self.assertEqual(self.state["nodes"]["beta"]["activity"]["state"], "idle")

    def test_head_is_last_commit_not_after_now(self):
        g = self.state["nodes"]["alpha"]["git"]
        self.assertEqual(g["head_time"], "2026-10-05T10:00:00Z")

    def test_actors(self):
        nodes = self.state["nodes"]
        self.assertEqual(nodes["author"]["activity"]["state"], "working")
        self.assertEqual(nodes["author"]["activity"]["last"], "2026-10-05T10:00:00Z")
        self.assertEqual(nodes["claude"]["activity"]["last"], "2026-10-05T11:00:00Z")
        self.assertEqual(sum(nodes["claude"]["days"]), 4)

    def test_flows(self):
        f = self.state["flows"]
        self.assertEqual(f["work.alpha"]["state"], "flowing")
        self.assertEqual((f["work.alpha"]["total"], f["work.alpha"]["week"]), (3, 3))
        self.assertEqual(f["decide.alpha"]["state"], "flowing")
        self.assertEqual(f["decide.alpha"]["total"], 1)
        self.assertEqual(f["facts.alpha.beta"]["state"], "quiet")
        self.assertEqual([i for i, v in enumerate(f["facts.alpha.beta"]["days"]) if v], [14])
        self.assertEqual(f["plan.beta.site"], {"state": "planned"})
        self.assertEqual(f["manual.phone"], {"state": "unobserved"})
        self.assertEqual(f["kit.beta.site"], {"state": "unobserved"})

    def test_nodes_without_clone_or_signal(self):
        nodes = self.state["nodes"]
        self.assertEqual(nodes["ghost"]["activity"]["state"], "unknown")
        self.assertEqual(nodes["site"]["activity"]["state"], "unobserved", "сайт не опрашивается (D-19)")
        self.assertEqual(nodes["phone"]["activity"]["state"], "unobserved")

    def test_diagnostics_and_registry(self):
        codes = {(d["code"], d["subject"]) for d in self.state["diagnostics"]}
        self.assertIn(("CLONE_NOT_FOUND", "ghost"), codes)
        self.assertIn(("REGISTRY_DRIFT", "owner/Gamma"), codes)
        self.assertIn(("CONTRACT_UNCOVERED", "DPmeta:C-02"), codes)
        self.assertIn(("CONTRACT_NOT_FOUND", "DPmeta:C-09"), codes)
        reg = self.state["registry"]
        self.assertEqual(reg["missing_in_model"], ["owner/Gamma"])
        self.assertEqual(reg["not_in_registry"], ["owner/Ghost"])
        self.assertEqual(reg["contracts_covered"], ["C-01"])

    def test_registry_exclusions_with_reason(self):
        # архив вне текущей системы (D-20): не дрейф и не непокрытый контракт, но виден в сверке
        src = base_source()
        src["registry"].update(exclude=["owner/Gamma"], exclude_contracts=["C-02"],
                               exclude_reason="архив — вне текущей системы")
        state = collect.collect(model_from(src), self.root, NOW)
        codes = {(d["code"], d["subject"]) for d in state["diagnostics"]}
        self.assertNotIn(("REGISTRY_DRIFT", "owner/Gamma"), codes)
        self.assertNotIn(("CONTRACT_UNCOVERED", "DPmeta:C-02"), codes)
        reg = state["registry"]
        self.assertEqual(reg["missing_in_model"], [])
        self.assertEqual(reg["excluded"], ["owner/Gamma"])
        self.assertEqual(reg["contracts_excluded"], ["C-02"])
        self.assertEqual(reg["exclude_reason"], "архив — вне текущей системы")
        self.assertEqual(formats.validate_document(state), [])

    def test_deterministic(self):
        again = collect.collect(self.model, self.root, NOW)
        self.assertEqual(modelmod.dumps(again), modelmod.dumps(self.state))

    def test_no_commit_text_or_email(self):
        text = modelmod.dumps(self.state)
        for leak in ("секрет-текст-коммита", "example.invalid", "noreply@anthropic.com",
                     "Co-Authored-By", "невлитая работа"):
            self.assertNotIn(leak, text)

    def test_earlier_now_hides_later_commits(self):
        early = collect.collect(self.model, self.root, utc("2026-10-02T00:00:00"))
        g = early["nodes"]["alpha"]["git"]
        self.assertEqual(g["window"], {"ai": 0, "human": 1, "merges": 0})
        self.assertEqual(early["window"]["end"], "2026-10-02")

    def test_clone_override_and_root_none(self):
        state = collect.collect(self.model, None, NOW, overrides={"alpha": self.root / "alpha"})
        self.assertIn("git", state["nodes"]["alpha"])
        self.assertEqual(state["nodes"]["beta"]["activity"]["state"], "unknown")
        self.assertEqual(state["registry"], {"checked": False, "node": "beta"})

    def test_cli_writes_file(self):
        from tools.dpvis.__main__ import main

        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "model.toml"
            src.write_text(_toml(base_source()), encoding="utf-8")
            out = Path(tmp) / "state.json"
            with contextlib.redirect_stdout(io.StringIO()):
                code = main(["collect", "--root", str(self.root), "--now", "2026-10-05T12:00:00Z",
                             "--model-source", str(src), "--out", str(out)])
            self.assertEqual(code, 0)
            data = json.loads(out.read_text(encoding="utf-8"))
            self.assertEqual(data["as_of"], "2026-10-05T12:00:00Z")


def _toml(src: dict) -> str:
    """Минимальная запись словаря модели в TOML для теста команды."""
    def val(v):
        if isinstance(v, bool):
            return "true" if v else "false"
        if isinstance(v, (int, float)):
            return str(v)
        if isinstance(v, str):
            return json.dumps(v, ensure_ascii=False)
        if isinstance(v, list):
            return "[" + ", ".join(val(x) for x in v) + "]"
        if isinstance(v, dict):
            return "{ " + ", ".join(f"{k} = {val(x)}" for k, x in v.items()) + " }"
        raise TypeError(v)

    lines = []
    for key, value in src.items():
        if not isinstance(value, (list, dict)) or (isinstance(value, list) and value and not isinstance(value[0], dict)):
            lines.append(f"{key} = {val(value)}")
    for key, value in src.items():
        if isinstance(value, dict):
            lines.append(f"\n[{key}]")
            lines += [f"{k} = {val(v)}" for k, v in value.items()]
    for key, value in src.items():
        if isinstance(value, list) and value and isinstance(value[0], dict):
            for item in value:
                lines.append(f"\n[[{key}]]")
                lines += [f"{k} = {val(v)}" for k, v in item.items()]
    return "\n".join(lines) + "\n"



class HistoryExcludeTest(unittest.TestCase):
    """Влитая история — у своего узла до слияния (D-24)."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        tmp = Path(cls.tmp.name)
        guest = Repo(tmp / "src" / "guest")
        guest.commit("2026-09-20T10:00:00", author="Claude", message="гость: ИИ", files={"guest/g1.txt": "1"})
        guest.commit("2026-09-21T10:00:00", message="гость: человек", files={"guest/g2.txt": "2"})
        cls.guest_root = guest.git("rev-list", "--max-parents=0", "HEAD").strip()
        guest.branch("extra")
        guest.commit("2026-10-03T10:00:00", author="Claude", message="гость: невлитое", files={"guest/g3.txt": "3"})
        guest.checkout("main")
        host = Repo(tmp / "src" / "host")
        host.commit("2026-09-25T10:00:00", message="свой корень")
        host.commit("2026-09-26T10:00:00", author="Claude", message="своё: ИИ")
        host.git("fetch", "-q", str(guest.path), "main:guest-main", "extra:guest-extra")
        host.git("merge", "-q", "--no-ff", "--allow-unrelated-histories", "-m", "гость — в host",
                 "guest-main", when=utc("2026-10-01T10:00:00"))
        host.commit("2026-10-02T10:00:00", author="Claude", message="после слияния: ИИ",
                    files={"guest/after.txt": "работа в каталоге гостя"})
        host.git("branch", "-D", "guest-main")
        root = tmp / "clones"
        root.mkdir()
        host.clone_to(root / "Host")
        cls.root = root

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def source(self, exclude: bool) -> dict:
        host = {"id": "host", "name": "Host", "kind": "repo", "layer": "product", "repo": "owner/Host",
                "group": "control", "visibility": "private", "role": "хозяин", "refs": ["Host:README.md"]}
        if exclude:
            host["history_exclude"] = [{"root": self.guest_root, "reason": "история гостя до слияния",
                                        "refs": ["Host:README.md"]}]
        return {
            "schema": 1, "title": "Влитая история", "as_of": "2026-10-05", "timezone": "+03:00",
            "allow": {"hosts": []},
            "layer": [{"id": "actors", "name": "Участники"}, {"id": "product", "name": "Продукты"}],
            "node": [
                {"id": "author", "name": "Автор", "kind": "actor", "layer": "actors", "role": "решения",
                 "refs": ["Host:README.md"]},
                {"id": "claude", "name": "Сессии", "kind": "actor", "layer": "actors", "role": "работа",
                 "refs": ["Host:README.md"]},
                host,
            ],
            "flow": [
                {"id": "work.host", "from": "claude", "to": "host", "kind": "work", "what": "работа",
                 "transport": "git", "refs": ["Host:README.md"],
                 "signal": {"type": "git-commits", "repo": "host", "who": "ai", "scope": "all"}},
                {"id": "path.host", "from": "claude", "to": "host", "kind": "work", "what": "каталог гостя",
                 "transport": "git", "refs": ["Host:README.md"],
                 "signal": {"type": "git-path", "repo": "host", "paths": ["guest/"]}},
            ],
        }

    def collect(self, exclude: bool) -> dict:
        model = model_from(self.source(exclude))
        state = collect.collect(model, self.root, NOW)
        self.assertEqual(formats.validate_document(state), [])
        self.assertEqual(formats.validate_pair(model, state), [])
        return state

    def test_without_rule_guest_history_counts(self):
        s = self.collect(False)
        g = s["nodes"]["host"]["git"]
        self.assertEqual(g["first_time"], "2026-09-20T10:00:00Z")
        self.assertEqual(g["window"], {"ai": 3, "human": 2, "merges": 1})
        self.assertEqual([b["name"] for b in g["branches"]], ["guest-extra"])
        self.assertNotIn("excluded", g)
        self.assertEqual(s["flows"]["path.host"]["total"], 3)

    def test_guest_history_before_merge_is_not_counted(self):
        s = self.collect(True)
        g = s["nodes"]["host"]["git"]
        self.assertEqual(g["first_time"], "2026-09-25T10:00:00Z")      # свой корень, а не гостя
        self.assertEqual(g["window"], {"ai": 2, "human": 1, "merges": 1})  # слияние и работа после — свои
        self.assertEqual(g["excluded"], 3)                              # два влитых и невлитое гостя
        self.assertEqual(g["branches"], [])                             # вершина ветки — во влитой истории
        self.assertEqual(s["flows"]["work.host"]["total"], 2)
        self.assertEqual(s["flows"]["path.host"]["total"], 1)           # только работа в guest/ после слияния
        self.assertEqual(sum(s["nodes"]["claude"]["days"]), 2)
        self.assertNotIn("HISTORY_ROOT_NOT_FOUND", [d["code"] for d in s["diagnostics"]])

    def test_shallow_clone_with_history_exclude_is_a_diagnostic(self):
        shallow = self.root.parent / "shallow"
        shallow.mkdir(exist_ok=True)
        subprocess.run(["git", "clone", "-q", "--depth", "2", (self.root / "Host").as_uri(),
                        str(shallow / "Host")], check=True, capture_output=True)
        s = collect.collect(model_from(self.source(True)), shallow, NOW)
        self.assertIn(("HISTORY_ROOT_NOT_FOUND", "host"),
                      [(d["code"], d["subject"]) for d in s["diagnostics"]])

    def test_unknown_root_is_a_diagnostic_not_silent_zero(self):
        src = self.source(True)
        src["node"][2]["history_exclude"][0]["root"] = "0" * 40
        s = collect.collect(model_from(src), self.root, NOW)
        self.assertIn(("HISTORY_ROOT_NOT_FOUND", "host"),
                      [(d["code"], d["subject"]) for d in s["diagnostics"]])
        self.assertEqual(s["nodes"]["host"]["git"]["excluded"], 0)


if __name__ == "__main__":
    unittest.main()
