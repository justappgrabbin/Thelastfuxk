import json, sys, importlib.util, random
import tokenizers as HF
spec = importlib.util.spec_from_file_location("mytok", "/home/claude/kleinport/phone/tokenizers.py"); mine = importlib.util.module_from_spec(spec); spec.loader.exec_module(mine)
P = "/home/claude/justappgrabbin/klein-agent/run/tokenizer.json"
a, b = HF.Tokenizer.from_file(P), mine.Tokenizer.from_file(P)
texts = []
for f in ["data/train.jsonl", "data/test.jsonl", "richer_data/train.jsonl"]:
    try:
        for line in open("/home/claude/justappgrabbin/klein-agent/" + f):
            row = json.loads(line)
            for t in row.get("turns", []): texts.append(t["text"])
    except FileNotFoundError: pass
texts = texts[:20000] + ["<|user|>alice: Hello there!<|end|><|model|>", "Café naïve 123 — émoji 🙂 tabs\tand  double  spaces\n\nnew", "don't won't I'll we've", "", "   ", "x__y a_b 3.14 $5", "a _!b __-- ½ ² ٣ Ⅻ", "hey_there!!! ok?", "1,000,000 dollars"]
random.seed(0)
bad = 0
for t in texts:
    ea, eb = a.encode(t), b.encode(t)
    if ea.ids != eb.ids or [tuple(o) for o in ea.offsets] != eb.offsets:
        bad += 1
        if bad < 5: print("MISMATCH", repr(t[:80]), ea.ids[:12], eb.ids[:12], ea.offsets[:6], eb.offsets[:6])
    ids = ea.ids
    for skip in (True, False):
        if a.decode(ids, skip_special_tokens=skip) != b.decode(ids, skip_special_tokens=skip):
            bad += 1
            if bad < 5: print("DECODE", repr(t[:60]), repr(a.decode(ids, skip_special_tokens=skip)), repr(b.decode(ids, skip_special_tokens=skip)))
for tkn in ["<|end|>", "<|user|>", "Thomas", "zzz"]:
    assert a.token_to_id(tkn) == b.token_to_id(tkn), tkn
print("texts", len(texts), "mismatches", bad)
