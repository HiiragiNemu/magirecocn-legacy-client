#!/usr/bin/env python3
from pathlib import Path
import subprocess
r=Path(__file__).resolve().parents[1]
j=r/'patch/src/main/java/io/kamihama/magianative'
assert 'static final boolean ENABLED = false;' in (j/'CNPublicResources.java').read_text()
s=(j/'CNUpdateSources.java').read_text()
assert s.count('if (CNPublicResources.ENABLED)')==3
assert 'static constexpr char CLIENT_VERSION[] = "1.0.194";' in (r/'magia-native/src/MagiaLegacy.cpp').read_text()
print('PASS194_SCOPE: only font release; 195 independent publishers disabled')
subprocess.run(['python3',str(r/'tools/test-public-resource-fallback.py')],check=True)
