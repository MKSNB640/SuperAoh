import re
import sys

path = '/sdcard/Claude的逆向学习/jadx_full_output/resources/assets/game/Governments.json'
src = open(path, encoding='utf-8', errors='replace').read()
keys = {}
for m in re.finditer(r'([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(-?[0-9.]+)f?', src):
    k, v = m.group(1), m.group(2)
    try:
        f = float(v)
    except ValueError:
        continue
    lo, hi = keys.get(k, (f, f))
    keys[k] = (min(lo, f), max(hi, f))

for k in sorted(keys):
    lo, hi = keys[k]
    print('%-40s %8s .. %8s' % (k, lo, hi))
print('--- records:', src.count('Name:'))
