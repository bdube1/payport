#!/usr/bin/env bash
# Publishes JUnit totals from target/surefire-reports as a GitHub annotation (readable via the API),
# listing every failed/errored/skipped test case. usage: test-summary.sh <label>
LABEL=${1:-tests}
DIR=target/surefire-reports
if ! ls $DIR/TEST-*.xml >/dev/null 2>&1; then echo "::warning title=$LABEL::no surefire reports found"; exit 0; fi
python3 - "$LABEL" "$DIR" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
label, d = sys.argv[1], sys.argv[2]
t = f = e = s = 0; bad = []; skipped = []
for p in glob.glob(d + "/TEST-*.xml"):
    r = ET.parse(p).getroot()
    t += int(r.get("tests", 0)); f += int(r.get("failures", 0)); e += int(r.get("errors", 0)); s += int(r.get("skipped", 0))
    for tc in r.iter("testcase"):
        name = tc.get("classname", "").split(".")[-1] + "." + tc.get("name", "")
        fe = tc.find("failure") if tc.find("failure") is not None else tc.find("error")
        if fe is not None:
            msg = (fe.get("message") or fe.text or "").replace("\n", " ").replace("%", "%25")[:600]
            bad.append(name + " -- " + msg)
        elif tc.find("skipped") is not None: skipped.append(name)
print(f"::notice title={label} totals::tests={t} passed={t-f-e-s} failures={f} errors={e} skipped={s}")
for b in bad[:40]: print(f"::error title={label} failed::{b}")
if skipped: print(f"::notice title={label} skipped::" + ", ".join(skipped[:60]))
PY
