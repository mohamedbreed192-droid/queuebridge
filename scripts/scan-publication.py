#!/usr/bin/env python3
"""Scan git-index publication content for locally generated credential values.
Run alongside a general secret scanner; never prints matched secret values.
"""
from pathlib import Path
import subprocess
import sys
root = Path(__file__).resolve().parents[1]
secrets = []
local_env = root / '.env'
if local_env.exists():
    secrets = [line.split('=', 1)[1].encode() for line in local_env.read_text().splitlines()
               if '_PASSWORD=' in line and len(line.split('=', 1)[1]) >= 16]
files = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).split(b'\0')
findings = []
for name in filter(None, files):
    filename = name.decode()
    if filename == '.env' or filename.startswith('target/'):
        findings.append(filename)
    content = subprocess.check_output(['git', 'show', ':' + filename], cwd=root)
    if any(secret in content for secret in secrets):
        findings.append(filename)
if findings:
    print('Publication blocked; review files: ' + ', '.join(sorted(set(findings))))
    sys.exit(1)
print(f'PASS: scanned {len(list(filter(None, files)))} indexed files; no generated local credentials found.')
