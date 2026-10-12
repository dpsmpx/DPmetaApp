"""Модель: проверка каталогов и ссылок, детерминированная сборка (F-05…F-07, D-02)."""

import copy
import json
import tempfile
import unittest
from pathlib import Path

from tests.fixture import base_source, model_from
from tools.dpvis import model as modelmod

REPO = Path(__file__).resolve().parents[1]
SOURCE = REPO / "model" / "ecosystem.toml"


def problems(mutate) -> list[str]:
    src = base_source()
    mutate(src)
    return modelmod.validate(src)


def node(src, nid):
    return next(n for n in src["node"] if n["id"] == nid)


def flow(src, fid):
    return next(f for f in src["flow"] if f["id"] == fid)


class RealModelTest(unittest.TestCase):
    def test_real_model_is_valid(self):
        src, _ = modelmod.load_source(SOURCE)
        self.assertEqual(modelmod.validate(src), [])

    def test_build_is_deterministic_and_committed(self):
        first = modelmod.expected_text(SOURCE)
        second = modelmod.expected_text(SOURCE)
        self.assertEqual(first, second)
        committed = (REPO / "data" / "model.json").read_text(encoding="utf-8")
        self.assertEqual(committed, first, "data/model.json отстал: python3 -m tools.dpvis model build")

    def test_every_dpmeta_contract_is_cited_or_excluded(self):
        # C-01…C-10 на 5 октября 2026; контракт архива исключён с причиной (D-20)
        model = json.loads(modelmod.expected_text(SOURCE))
        cited = {c for f in model["flows"] for c in f["contracts"]}
        excluded = {f"DPmeta:{c}" for c in model["registry"].get("exclude_contracts", [])}
        self.assertEqual(excluded, {"DPmeta:C-08"})
        self.assertTrue(model["registry"].get("exclude_reason"))
        self.assertEqual(cited | excluded, {f"DPmeta:C-{i:02d}" for i in range(1, 11)})
        self.assertFalse(cited & excluded)

    def test_defaults_are_explicit(self):
        model = model_from(base_source())
        work = next(f for f in model["flows"] if f["id"] == "work.alpha")
        self.assertEqual((work["driver"], work["status"], work["bidirectional"]), ("ai", "active", False))
        self.assertEqual(next(f for f in model["flows"] if f["id"] == "decide.alpha")["signal"]["scope"], "main")
        self.assertEqual(next(n for n in model["nodes"] if n["id"] == "author")["visibility"], "private")
        self.assertEqual(len(model["source"]["sha256"]), 64)


class ValidationCatchesTest(unittest.TestCase):
    """Каждое правило проверки — образец, который оно ловит (CLAUDE.md, «Проверки»)."""

    def assertCatches(self, mutate, fragment):
        found = problems(mutate)
        self.assertTrue(any(fragment in p for p in found), f"{fragment!r} не найдено в {found}")

    def test_clean_sample_passes(self):
        self.assertEqual(problems(lambda s: None), [])

    def test_unknown_node_kind(self):
        self.assertCatches(lambda s: node(s, "alpha").update(kind="robot"), "неизвестный вид 'robot'")

    def test_unknown_flow_kind(self):
        self.assertCatches(lambda s: flow(s, "work.alpha").update(kind="magic"), "неизвестный вид 'magic'")

    def test_missing_node_in_flow(self):
        self.assertCatches(lambda s: flow(s, "work.alpha").update(to="nowhere"), "to: нет узла 'nowhere'")

    def test_duplicate_id(self):
        self.assertCatches(lambda s: s["node"].append(copy.deepcopy(node(s, "alpha"))), "повтор id 'alpha'")

    def test_public_text_must_be_text(self):
        self.assertCatches(lambda s: node(s, "alpha").update(public_role=""), "public_role — непустая строка")
        self.assertCatches(lambda s: flow(s, "work.alpha").update(public_what=3), "public_what — непустая строка")

    def test_real_public_texts_cover_shown_nodes(self):
        from tools.dpvis import public
        m = json.loads(modelmod.expected_text(SOURCE))
        doc = public.public_bundle(m, None)
        bare = [n["id"] for n in doc["model"]["nodes"] if "role" not in n]
        self.assertEqual(bare, [], "у каждого узла на сайте — публичная роль (public_role)")

    def test_ref_to_unknown_project(self):
        self.assertCatches(lambda s: node(s, "alpha").update(refs=["Nope:README.md"]), "проекта 'Nope'")

    def test_ref_without_project(self):
        self.assertCatches(lambda s: node(s, "alpha").update(refs=["README.md"]), "не в форме")

    def test_node_without_refs(self):
        self.assertCatches(lambda s: node(s, "alpha").update(refs=[]), "нет ссылок")

    def test_flow_without_refs_and_contracts(self):
        self.assertCatches(lambda s: flow(s, "facts.alpha.beta").update(contracts=[]), "ни ссылок")

    def test_bad_contract(self):
        self.assertCatches(lambda s: flow(s, "decide.alpha").update(contracts=["DPmeta:RISK-01"]), "DPmeta:C-NN")

    def test_probes_are_gone(self):
        # D-19: к сайту и сервисам DPvisualisation не обращается
        self.assertCatches(lambda s: node(s, "site").update(probes=[{"url": "http://site.example/"}]),
                           "неизвестный ключ 'probes'")
        self.assertCatches(lambda s: s["allow"].update(hosts=["site.example"]), "пустой список")
        self.assertCatches(lambda s: flow(s, "work.alpha").update(signal={"type": "probe", "node": "site"}),
                           "неизвестный сигнал")

    def test_registry_exclusions_need_reason_and_no_node(self):
        self.assertCatches(lambda s: s["registry"].update(exclude=["owner/Gamma"]), "exclude_reason")
        self.assertCatches(lambda s: s["registry"].update(exclude=["owner/Alpha"], exclude_reason="архив"),
                           "есть узлом модели")
        self.assertCatches(lambda s: s["registry"].update(exclude_contracts=["9"], exclude_reason="x"), "C-NN")

    def test_signal_on_non_repo(self):
        self.assertCatches(lambda s: flow(s, "work.alpha")["signal"].update(repo="site"), "не узел-репозиторий")

    def test_signal_on_planned_flow(self):
        self.assertCatches(lambda s: flow(s, "work.alpha").update(status="planned"), "сигнал — только")


    def test_unknown_key(self):
        self.assertCatches(lambda s: node(s, "alpha").update(colour="red"), "неизвестный ключ 'colour'")

    def test_repo_without_slug(self):
        self.assertCatches(lambda s: node(s, "alpha").pop("repo"), "нет slug")

    def test_unknown_layer(self):
        self.assertCatches(lambda s: node(s, "alpha").update(layer="space"), "неизвестный слой")

    def test_registry_on_non_repo(self):
        self.assertCatches(lambda s: s["registry"].update(node="site"), "registry: node")

    def test_compile_raises_with_all_problems(self):
        src = base_source()
        node(src, "alpha")["kind"] = "robot"
        flow(src, "work.alpha")["to"] = "nowhere"
        with self.assertRaises(modelmod.ModelError) as ctx:
            modelmod.compile_model(src, b"x")
        self.assertGreaterEqual(len(ctx.exception.problems), 2)

    def test_bad_toml_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "m.toml"
            path.write_text("schema = = 1", encoding="utf-8")
            with self.assertRaises(modelmod.ModelError):
                modelmod.load_source(path)


if __name__ == "__main__":
    unittest.main()
