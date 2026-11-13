import json, sys, os
mode, root = sys.argv[1], sys.argv[2]
msgs = ["hello there", "Thomas lives in Rome because he works there.", "Ann went to the park with Thomas.", "Where does Thomas live?",
        "I feel happy today.", "what can you do", "/find birth chart", "Ann works in Paris."]
if mode == "phone":
    sys.path.insert(0, "/home/claude/kleinport/phone")
    import klein_phone
    klein_phone.BRIDGE.update(url="http://127.0.0.1:" + sys.argv[3], token=sys.argv[4])
    E = klein_phone.install(root)
    import torch, tokenizers, swisseph
    assert "kleinport/phone" in torch.__file__ and "kleinport/phone" in tokenizers.__file__ and "kleinport/phone" in swisseph.__file__
else:
    sys.path.insert(0, root + "/klein_tools_fixed/neural")
    import dialogue_experiment as E
gen = E.generate
E.generate = lambda *a, **k: gen(*a, **dict(k, sample=False))
sys.path.insert(0, root + "/klein_tools_fixed/neural")
from skilled_agent import Agent
os.chdir(root)
a = Agent(root)
out = []
for m in msgs:
    r = a.respond(m)
    tr = a.chat.last_trace if r.get("kind") == "neural_conversation" else None
    out.append(dict(msg=m, reply=r["reply"], kind=r["kind"],
                    cands=[(c["text"], round(c["neural_score"], 4), round(c["score"], 4)) for c in tr["candidates"]] if tr else None))
json.dump(out, open("/home/claude/kleinport/out_" + mode + ".json", "w"), indent=1)
print(mode, "done", [o["reply"][:40] for o in out])
