"""Class shells so klein-agent's model definitions import; nothing here computes. See torch/__init__.py."""
from . import functional  # noqa: F401


class Module:
    def __init__(self, *a, **k):
        raise RuntimeError("PyTorch modules can't be built on the phone; the app's Java KleinModel runs Klein")


class _Shell(Module):
    pass


Linear = Embedding = LayerNorm = Dropout = Sequential = Tanh = ModuleList = ModuleDict = ParameterList = Parameter = _Shell

utils = None


def __getattr__(name):
    return _Shell
