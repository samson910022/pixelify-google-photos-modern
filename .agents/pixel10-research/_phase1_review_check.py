#!/usr/bin/env python3
import re
from pathlib import Path

src = Path("app/src/main/java/io/github/samson910022/pixelifyphotos/DeviceProps.kt").read_text()
test = Path("app/src/test/java/io/github/samson910022/pixelifyphotos/DevicePropsTest.kt").read_text()

names = re.findall(r'DeviceEntries\(\s*"([^"]+)"', src)
features = re.findall(r'Features\("([^"]+)"', src)
assert "tehua" not in src.lower()
assert re.search(r'defaultDeviceName\s*=\s*"Pixel XL"', src)
assert re.search(r'defaultFeatures\s*=\s*getFeaturesUpTo\("Pixel 2016"\)', src)
assert len(features) == 13
assert features[-3:] == ["Pixel 2023", "Pixel 2024", "Pixel 2025"]
assert "PIXEL_2025_EXPERIENCE" in src and "PIXEL_2025_PRELOAD" in src
assert "Pixel 2025 mid-year" not in src

chunks = re.split(r"\n\s*DeviceEntries\(", src)[1:]
parsed = []
for ch in chunks:
    name = re.match(r'\s*"([^"]+)"', ch).group(1)
    fl = re.search(
        r'\)\s*,\s*\n\s*"(None|Pixel [^"]+)"\s*,\s*\n\s*(getAndroidVersionFromLabel\("([^"]+)"\)|null)',
        ch,
    )
    feature = fl.group(1) if fl else None
    android = fl.group(3) if fl and fl.group(3) else None
    prop_region = ch[: fl.start()] if fl else ch
    props = dict(re.findall(r'Pair\("([^"]+)",\s*"([^"]*)"\)', prop_region))
    parsed.append((name, props, feature, android))
assert len(parsed) == 26

def get(name):
    return next(p for p in parsed if p[0] == name)

# 9a
n, props, feature, android = get("Pixel 9a")
assert props["DEVICE"] == "tegu" and props["PRODUCT"] == "tegu"
assert props["FINGERPRINT"] == "google/tegu/tegu:16/BP4A.260105.004.E1/14587043:user/release-keys"
assert props["ID"] == "BP4A.260105.004.E1" and props["INCREMENTAL"] == "14587043"
assert "SECURITY_PATCH" not in props
assert feature == "Pixel 2024" and android == "Android 16"
joined = " ".join(props.values())
for bad in ["tehua", "BP1A.250405.002", "13115780"]:
    assert bad not in joined

for name, dev, fp in [
    ("Pixel 10", "frankel", "google/frankel/frankel:16/BD3A.250721.001/13808258:user/release-keys"),
    ("Pixel 10 Pro", "blazer", "google/blazer/blazer:16/BD3A.250721.001/13808258:user/release-keys"),
    ("Pixel 10 Pro XL", "mustang", "google/mustang/mustang:16/BD3A.250721.001/13808258:user/release-keys"),
]:
    n, props, feature, android = get(name)
    assert props["DEVICE"] == dev and props["PRODUCT"] == dev
    assert props["FINGERPRINT"] == fp
    assert props["ID"] == "BD3A.250721.001"
    assert props["INCREMENTAL"] == "13808258"
    assert props["SECURITY_PATCH"] == "2025-08-05"
    assert feature == "Pixel 2025" and android == "Android 16"

for name, dev in [("Pixel 10 Pro Fold", "rango"), ("Pixel 10a", "stallion")]:
    n, props, feature, android = get(name)
    assert props["DEVICE"] == dev and props["PRODUCT"] == dev
    assert props["BRAND"] == "google" and props["MANUFACTURER"] == "Google"
    for k in ["FINGERPRINT", "ID", "INCREMENTAL", "SECURITY_PATCH"]:
        assert k not in props
    assert feature == "Pixel 2025" and android == "Android 16"

assert 'experimentalIdentityOnly = setOf("Pixel 10 Pro Fold", "Pixel 10a")' in test
assert "it.deviceName !in experimentalIdentityOnly" in test
assert "assertEquals(13, DeviceProps.allFeatures.size)" in test
assert "assertEquals(26, DeviceProps.allDevices.size)" in test
assert 'assertEquals("Pixel XL", DeviceProps.defaultDeviceName)' in test
assert "assertEquals(1, DeviceProps.defaultFeatures.size)" in test
print("ALL_CONTRACTS PASS")
