#!/usr/bin/env python3
"""Run from repository root after extracting all four ZIPs there."""
from pathlib import Path
import hashlib
parts=[Path('assets/alpine-coder.tar.gz.part%02d' % i) for i in range(1,5)]
for p in parts:
    if not p.is_file(): raise SystemExit(f'Missing {p}; extract all four ZIPs first')
target=Path('assets/alpine-coder.tar.gz')
with target.open('wb') as out:
    for p in parts:
        with p.open('rb') as source:
            while block:=source.read(1024*1024): out.write(block)
expected='8e3cdd1fc06efe3869b36ce501647a462e71abab672f35ad5313e0f94315ccc2'
actual=hashlib.sha256(target.read_bytes()).hexdigest()
if actual!=expected: raise SystemExit('Checksum mismatch: '+actual)
print('Restored and verified:',target,'('+str(target.stat().st_size)+' bytes)')
