#!/usr/bin/env python3
"""Run klein-agent's web_app on the phone with no PyTorch, tokenizers or pyswisseph installed.

- The neural forward pass is done by The Last Fuck's Java KleinModel over a localhost bridge (parity-tested with PyTorch).
- tokenizers / swisseph / torch are replaced by the pure-Python stand-ins next to this file.
- Everything else (LiveFive readings, contact model, situation selector, skills, Space check) is klein-agent's own unchanged code.

usage: python3 klein_phone.py --root /root/klein-agent --bridge http://127.0.0.1:PORT --bridge-token TOKEN [--port 7860]
"""
import argparse
import json
import os
import random
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)                                   # stand-ins win over anything else

BRIDGE = {"url": None, "token": None}


def bridge(path, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(BRIDGE["url"] + path, data=data, method="POST" if data else "GET",
                                 headers={"Content-Type": "application/json", "Authorization": "Bearer " + BRIDGE["token"]})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(req, timeout=120) as r:
        out = json.loads(r.read())
    if "error" in out:
        raise RuntimeError("Klein engine: " + out["error"])
    return out


class RemoteModel:
    """Same call contract the chat code needs, backed by the Java engine."""
    def eval(self):
        return self

    def logits(self, ids, feat, links, cond, start):
        return bridge("/forward", dict(ids=ids, feat=feat, links=[list(l) for l in links], cond=cond, start=start))["logits"]


def install(root):
    sys.path.insert(1, os.path.join(root, "klein_tools_fixed", "neural"))
    import dialogue_experiment as E
    import klein_lm as K
    import klein_features as KF
    import tokenizers

    config = bridge("/config")
    cfg = K.Config(**{k: v for k, v in config.items() if k in K.Config.__dataclass_fields__})

    def load_checkpoint(run, device):
        tok = tokenizers.Tokenizer.from_file(os.path.join(str(run), "tokenizer.json"))
        if tok.token_to_id("<|end|>") is None:
            raise ValueError("tokenizer.json has no <|end|> token")
        return RemoteModel(), tok, cfg, {"step": config.get("step"), "engine": "java"}

    original_conditioning = K.conditioning

    def conditioning(natal_jd, transit_jd, extra=None, dim=16):
        import dimension_clock as clock
        names = ["Evolution", "Being", "Design", "Movement"]
        a = clock.activations(transit_jd, natal=natal_jd)
        v = [a[n] for n in names] + [clock.space(transit_jd, natal=natal_jd)[0]]
        if natal_jd is not None:
            n0 = clock.activations(natal_jd)
            v += [n0[n] for n in names]
        v += list(extra or [])
        return [float(x) for x in (v + [0.0] * dim)[:dim]]
    conditioning.__doc__ = original_conditioning.__doc__

    def generate(model, tok, cfg, prefix, cond, device, max_new=32, sample=True):
        # Same loop as dialogue_experiment.generate: features recomputed from the fixed sampled ids each step,
        # top-20 at temperature 0.8 when sampling, argmax otherwise. Sampling uses Python's RNG instead of torch's.
        generated = []
        prefix_ids = tok.encode(prefix).ids
        stop = tok.token_to_id("<|end|>")
        for _ in range(max_new):
            d = E.features_for_ids((prefix_ids + generated)[-cfg.ctx:], tok)
            ids = d["ids"][-cfg.ctx:]
            offset = len(d["ids"]) - len(ids)
            links = [(q - offset, k - offset, ch) for q, k, ch in d["links"] if q >= offset and k >= offset]
            row = model.logits(ids, d["feat"][-cfg.ctx:], links, cond, len(ids) - 1)[0]
            if sample:
                top = sorted(range(len(row)), key=lambda i: row[i], reverse=True)[:min(20, cfg.vocab)]
                scaled = [row[i] / .8 for i in top]
                m = max(scaled)
                weights = [pow(2.718281828459045, s - m) for s in scaled]
                nxt = random.choices(top, weights=weights)[0]
            else:
                nxt = max(range(len(row)), key=lambda i: row[i])
            if nxt == stop:
                break
            generated.append(nxt)
        return tok.decode(generated).strip()

    def reply_score(model, tok, cfg, turns, reply, cond, device):
        # Same as dialogue_experiment.reply_score: -mean cross-entropy of the reply tokens (+ <|end|>) given the
        # rendered conversation, using train.collate's window/target/link rules.
        prefix = E.rendering(turns)
        start = len(tok.encode(prefix).ids)
        d = KF.featurize(prefix + reply + "<|end|>", tok)
        n_all = len(d["ids"])
        s = max(0, n_all - cfg.ctx - 1)
        if start <= s:
            return -100.
        e = n_all
        ids = d["ids"][s:e - 1]
        tgt = d["ids"][s + 1:e]
        feat = d["feat"][s:e - 1]
        links = [(q - s, k - s, ch) for q, k, ch in d["links"] if s <= k and q < e - 1 and s <= q]
        first = start - s - 1
        rows = model.logits(ids, feat, links, cond, first)
        import math
        total = 0.0
        for i, row in enumerate(rows):
            m = max(row)
            lse = m + math.log(sum(math.exp(x - m) for x in row))
            total += lse - row[tgt[first + i]]
        return -total / len(rows)

    E.load_checkpoint, E.generate, E.reply_score, K.conditioning = load_checkpoint, generate, reply_score, conditioning
    return E


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True)
    ap.add_argument("--bridge", required=True)
    ap.add_argument("--bridge-token", required=True)
    ap.add_argument("--port", type=int, default=7860)
    ap.add_argument("--host", default="127.0.0.1")
    a = ap.parse_args()
    BRIDGE.update(url=a.bridge.rstrip("/"), token=a.bridge_token)
    install(a.root)
    sys.path.insert(1, a.root)
    os.chdir(a.root)
    import web_app
    server = web_app.Server((a.host, a.port), a.root)
    print("Klein (phone engine) on http://%s:%d" % (a.host, a.port), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
