#!/usr/bin/env python3
"""Apply one mutation, run the suite, record which test methods failed, revert."""
import subprocess, sys, glob, re, os, shutil

CORE = 'proj/src/main/java/ie/ardaralibraries/bookline/circulation/Core.java'
BILL = 'proj/src/main/java/ie/ardaralibraries/bookline/billing/Billing.java'

MUTATIONS = {
    'A': (CORE, '''        if (copy.status() != CopyStatus.AVAILABLE) {
            throw new CopyUnavailableException("Copy " + barcode + " is not available");
        }
''', ''),
    'B': (BILL, '''            if (!calendar.isOpenOn(day)) {
                continue;
            }
''', ''),
    'C': (BILL, '''        BigDecimal headroom = cap.subtract(accruedSoFar);
        if (headroom.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return rate.min(headroom);
''', '''        return rate;
'''),
}

def run_suite():
    subprocess.run(['mvn', '-q', '-o', 'test'], cwd='proj',
                   capture_output=True, text=True)
    failed, total, fails, errors = [], 0, 0, 0
    for f in glob.glob('proj/target/surefire-reports/*.xml'):
        txt = open(f).read()
        for m in re.finditer(r'<testcase name="([^"]+)" classname="([^"]+)"(.*?)(/>|</testcase>)',
                             txt, re.S):
            name, cls, body = m.group(1), m.group(2), m.group(3) + m.group(4)
            total += 1
            if '<failure' in body or '<error' in body:
                kind = 'failure' if '<failure' in body else 'error'
                if kind == 'failure': fails += 1
                else: errors += 1
                failed.append((cls.split('.')[-1], name, kind))
    return total, fails, errors, failed

def apply(key):
    path, old, new = MUTATIONS[key]
    src = open(path).read()
    assert old in src, f'mutation {key} target not found'
    open(path, 'w').write(src.replace(old, new, 1))

def main():
    for path in (CORE, BILL):
        shutil.copy(path, path + '.orig')
    results = {}
    for key in ['A', 'B', 'C']:
        apply(key)
        total, fails, errors, failed = run_suite()
        results[key] = (total, fails, errors, failed)
        print(f'\n===== MUTATION {key} =====')
        print(f'executions={total}  failures={fails}  errors={errors}  '
              f'killed={len(failed)}  survived={total - len(failed)}')
        for cls, name, kind in sorted(failed):
            print(f'   [{kind:7}] {cls}.{name}')
        for path in (CORE, BILL):
            shutil.copy(path + '.orig', path)
    for path in (CORE, BILL):
        os.remove(path + '.orig')
    return results

if __name__ == '__main__':
    main()
