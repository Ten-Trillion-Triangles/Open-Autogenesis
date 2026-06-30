#!/usr/bin/env python3
import json
import sys

filepath = sys.argv[1] if len(sys.argv) > 1 else 'package.json'
with open(filepath, 'r') as f:
    data = json.load(f)
if 'packageManager' in data:
    del data['packageManager']
    print('Removed packageManager')
with open(filepath, 'w') as f:
    json.dump(data, f, indent=2)
print('Done')