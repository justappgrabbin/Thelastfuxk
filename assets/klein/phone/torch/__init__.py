"""Minimal stand-in for PyTorch on the phone. Klein's math runs in the app's Java KleinModel (parity-tested
against PyTorch); this module only lets klein-agent's Python import cleanly and provides the two tiny tensor
operations its chat path uses directly (torch.tensor(list).softmax(0).tolist()). Training needs real PyTorch."""
import math as _math
from . import nn  # noqa: F401

float32, long, bool = "float32", "long", "bool"


class _Unavailable(RuntimeError):
    pass


def _missing(name):
    def call(*a, **k):
        raise _Unavailable("torch." + name + " is not available on the phone: Klein's model runs in the app's Java engine. "
                           "Learning/rollback still need the PC server with real PyTorch.")
    return call


class Tensor:
    def __init__(self, data):
        self.data = [float(x) for x in data]

    def softmax(self, dim=0):
        if not self.data:
            return Tensor([])
        m = max(self.data)
        e = [_math.exp(x - m) for x in self.data]
        s = sum(e)
        return Tensor([x / s for x in e])

    def tolist(self):
        return list(self.data)

    def __iter__(self):
        return iter(self.data)

    def __len__(self):
        return len(self.data)


def tensor(data, dtype=None, device=None):
    if data and isinstance(data[0], (list, tuple)):
        raise _Unavailable("Only 1-D tensors are supported on the phone")
    return Tensor(data)


class no_grad:
    def __call__(self, fn):
        return fn
    def __enter__(self):
        return self
    def __exit__(self, *a):
        return False


def set_num_threads(n):
    pass


class _Cuda:
    @staticmethod
    def is_available():
        return False


cuda = _Cuda()


def __getattr__(name):
    return _missing(name)
