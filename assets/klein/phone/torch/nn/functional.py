def __getattr__(name):
    def call(*a, **k):
        raise RuntimeError("torch.nn.functional." + name + " is not available on the phone")
    return call
