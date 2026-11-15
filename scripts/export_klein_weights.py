#!/usr/bin/env python3
"""Export a klein-agent checkpoint (run/model.pt) to a torch-free binary the phone's Java KleinModel loads.
Format: b"KLEINW1\n", uint32 header length, JSON header {config, tensors:{name:[shape, offset]}}, then float32 little-endian data.
Run on a machine that has torch:  python3 export_klein_weights.py <klein-agent>/run/model.pt klein-weights.bin
"""
import json, struct, sys
import torch

def export(src, dst):
    ck = torch.load(src, map_location="cpu", weights_only=False)
    state, header, blobs, offset = ck["state"], {"config": ck["config"], "tensors": {}}, [], 0
    for name, t in state.items():
        if "adapters" in name:
            continue                      # per-person LoRA adapters are not used by Chat
        a = t.detach().to(torch.float32).contiguous()
        header["tensors"][name] = [list(a.shape), offset]
        b = a.numpy().astype("<f4").tobytes()
        blobs.append(b); offset += len(b)
    h = json.dumps(header).encode()
    with open(dst, "wb") as f:
        f.write(b"KLEINW1\n"); f.write(struct.pack("<I", len(h))); f.write(h)
        for b in blobs: f.write(b)
    return header

if __name__ == "__main__":
    h = export(sys.argv[1], sys.argv[2])
    print(json.dumps(h["config"]), len(h["tensors"]), "tensors")
