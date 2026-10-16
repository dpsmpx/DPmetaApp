"""Реестр встроенных проверок: id → класс. Порядок — порядок прогона (ТЗ, §8.1, §8.2)."""
from .code import Forbidden
from .deps import Deps
from .docs import Links, Refs
from .formats import Scripts, Text
from .integrity import Integrity
from .layout import Layout
from .process import ProcessFiles, ProcessScope, ProvenanceBinaries
from .profile_check import ProfileCheck
from .secrets import Secrets
from .smoke import Smoke
from .synthetic import Synthetic

REGISTRY = {cls.ID: cls for cls in (
    Integrity, ProfileCheck, Layout, Text, Scripts, Secrets, Links, Refs, ProcessFiles, ProvenanceBinaries,
    Forbidden, Synthetic, Deps, Smoke, ProcessScope,
)}
