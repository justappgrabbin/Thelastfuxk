"""Pure-Python stand-in for the `tokenizers` package, covering what klein-agent uses:
Tokenizer.from_file(tokenizer.json) for a ByteLevel BPE model, encode(text).ids/.offsets, decode(ids), token_to_id.
Written for the phone's Alpine Linux, where the Rust `tokenizers` wheel is not available. Parity-tested against HF tokenizers.
"""
import json
import re

# GPT-2 / HF ByteLevel(use_regex=True) split pattern. \p{L} -> [^\W\d_], \p{N} -> \d (Python's re has no \p classes).
_SPLIT = re.compile(r"""'s|'t|'re|'ve|'m|'ll|'d| ?[^\W\d_]+| ?\d+| ?(?:[^\s\w]|_)+|\s+(?!\S)|\s+""", re.UNICODE)


def _bytes_to_unicode():
    bs = list(range(ord("!"), ord("~") + 1)) + list(range(ord("¡"), ord("¬") + 1)) + list(range(ord("®"), ord("ÿ") + 1))
    cs = bs[:]
    n = 0
    for b in range(256):
        if b not in bs:
            bs.append(b); cs.append(256 + n); n += 1
    return dict(zip(bs, map(chr, cs)))


class Encoding:
    def __init__(self, ids, tokens, offsets):
        self.ids, self.tokens, self.offsets = ids, tokens, offsets


class Tokenizer:
    def __init__(self, spec):
        model = spec["model"]
        if model.get("type") != "BPE":
            raise ValueError("Only BPE tokenizers are supported")
        self.vocab = model["vocab"]
        self.inv = {v: k for k, v in self.vocab.items()}
        merges = model["merges"]
        self.ranks = {}
        for i, m in enumerate(merges):
            a, b = (m.split(" ", 1) if isinstance(m, str) else m)
            self.ranks[(a, b)] = i
        self.added = {t["content"]: t["id"] for t in spec.get("added_tokens", [])}
        self.special = {t["id"] for t in spec.get("added_tokens", []) if t.get("special")}
        for t, i in self.added.items():
            self.inv[i] = t
        self.added_re = re.compile("|".join(re.escape(t) for t in sorted(self.added, key=len, reverse=True))) if self.added else None
        pre = spec.get("pre_tokenizer") or {}
        self.add_prefix_space = bool(pre.get("add_prefix_space", False))
        self.byte_enc = _bytes_to_unicode()
        self.byte_dec = {v: k for k, v in self.byte_enc.items()}
        self.cache = {}

    @classmethod
    def from_file(cls, path):
        with open(path, encoding="utf-8") as f:
            return cls(json.load(f))

    def token_to_id(self, token):
        return self.added.get(token, self.vocab.get(token))

    def _bpe(self, word):
        if word in self.cache:
            return self.cache[word]
        parts = list(word)
        while len(parts) > 1:
            best, idx = None, -1
            for i in range(len(parts) - 1):
                r = self.ranks.get((parts[i], parts[i + 1]))
                if r is not None and (best is None or r < best):
                    best, idx = r, i
            if best is None:
                break
            a, b = parts[idx], parts[idx + 1]
            merged, i = [], 0
            while i < len(parts):
                if i < len(parts) - 1 and parts[i] == a and parts[i + 1] == b:
                    merged.append(a + b); i += 2
                else:
                    merged.append(parts[i]); i += 1
            parts = merged
        self.cache[word] = parts
        return parts

    def _encode_plain(self, text, base, ids, toks, offs):
        if self.add_prefix_space and base == 0 and text and not text[0].isspace():
            text = " " + text; shift = -1
        else:
            shift = 0
        for m in _SPLIT.finditer(text):
            piece = m.group()
            # character offset for each byte of the piece
            char_of_byte = []
            for ci, ch in enumerate(piece):
                char_of_byte.extend([ci] * len(ch.encode("utf-8")))
            mapped = "".join(self.byte_enc[b] for b in piece.encode("utf-8"))
            pos = 0
            for sub in self._bpe(mapped):
                nb = len(sub)
                cs = char_of_byte[pos]; ce = char_of_byte[pos + nb - 1] + 1
                pos += nb
                tid = self.vocab.get(sub)
                if tid is None:
                    raise ValueError("Token not in vocabulary: %r" % sub)
                ids.append(tid); toks.append(sub)
                offs.append((max(0, base + m.start() + cs + shift), base + m.start() + ce + shift))

    def encode(self, text):
        ids, toks, offs = [], [], []
        pos = 0
        if self.added_re:
            for m in self.added_re.finditer(text):
                if m.start() > pos:
                    self._encode_plain(text[pos:m.start()], pos, ids, toks, offs)
                ids.append(self.added[m.group()]); toks.append(m.group()); offs.append((m.start(), m.end()))
                pos = m.end()
        if pos < len(text):
            self._encode_plain(text[pos:], pos, ids, toks, offs)
        return Encoding(ids, toks, offs)

    def decode(self, ids, skip_special_tokens=True):
        out, buf = [], []
        def flush():
            if buf:
                out.append(bytes(self.byte_dec[c] for c in "".join(buf)).decode("utf-8", errors="replace")); buf.clear()
        for i in ids:
            t = self.inv.get(i)
            if t is None:
                continue
            if i in self.added:
                pass
            if t in self.added:
                if skip_special_tokens and i in self.special:
                    continue
                flush(); out.append(t)
            else:
                buf.append(t)
        flush()
        return "".join(out)


class _TrainingOnly:
    """Placeholders for tokenizer *training* APIs (models, pre_tokenizers, decoders, trainers): import-only on the phone."""
    def __getattr__(self, name):
        def call(*a, **k):
            raise RuntimeError("Tokenizer training isn't available on the phone; use the PC setup")
        return call


models = pre_tokenizers = decoders = trainers = normalizers = processors = _TrainingOnly()
