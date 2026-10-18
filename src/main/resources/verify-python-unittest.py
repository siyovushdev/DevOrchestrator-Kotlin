"""Controller-owned unittest dispatcher. This parent NEVER imports candidate code.

Only this parent receives the final report path. Discovery and each selected test run
in bounded-protocol child interpreters covered by the existing Linux supervisor.
A clean early worker exit without its complete result is UNKNOWN, not a test PASS.
Arbitrary test semantics and in-worker Python remain untrusted; this is not hostile-code
method-entry attestation or a filesystem/credential sandbox. Protected controller
artifacts and the separately approved disposable OS context remain prerequisites.
"""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

WORKER = r'''
import json
import os
import sys
import unittest
sys.path.insert(0, os.getcwd())
suite = unittest.defaultTestLoader.discover('tests')
def flatten(suite):
    for test in suite:
        if isinstance(test, unittest.TestSuite):
            yield from flatten(test)
        else:
            yield test
cases = list(flatten(suite))
if sys.argv[1] == 'catalog':
    result = {'kind': 'catalog', 'test_ids': [test.id() for test in cases]}
else:
    selected = [test for test in cases if test.id() == sys.argv[2]]
    if len(selected) != 1:
        raise RuntimeError('Discovered test identity changed')
    observed = unittest.TextTestRunner(verbosity=2).run(unittest.TestSuite(selected))
    result = {'kind': 'completed', 'test_id': sys.argv[2], 'tests_run': observed.testsRun,
              'failures': len(observed.failures), 'errors': len(observed.errors),
              'skipped': len(observed.skipped), 'unexpected_successes': len(observed.unexpectedSuccesses),
              'was_successful': observed.wasSuccessful()}
print(json.dumps(result))
'''

source, report_name, minimum = sys.argv[1:]
report = Path(report_name)
MAX_PROTOCOL = 1024 * 1024

def invoke(*args):
    # No final-report path, descriptor or environment variable reaches the worker.
    # stderr streams directly to the outer bounded observer; stdout is a bounded protocol.
    worker = subprocess.Popen([sys.executable, '-I', '-B', '-c', WORKER, *args], cwd=source,
                              stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, close_fds=True)
    raw = worker.stdout.read(MAX_PROTOCOL + 1)
    if len(raw) > MAX_PROTOCOL:
        worker.kill()
        worker.wait()
        raise RuntimeError('Worker protocol exceeds limit')
    if worker.wait() != 0:
        raise RuntimeError('Worker exited without normal completion')
    return json.loads(raw)

catalog = invoke('catalog')
if catalog.get('kind') != 'catalog' or not isinstance(catalog.get('test_ids'), list):
    raise RuntimeError('Incomplete discovery')
test_ids = catalog['test_ids']
if len(test_ids) > 10000 or len(set(test_ids)) != len(test_ids) or any(
        not isinstance(name, str) or not re.fullmatch(r'[A-Za-z_][A-Za-z_0-9.]{0,511}', name) for name in test_ids):
    raise RuntimeError('Invalid discovered identities')
identities = json.dumps(test_ids).encode()
with report.with_suffix('.test-ids.json').open('xb') as output:
    output.write(identities)

counts = dict(tests_run=0, failures=0, errors=0, skipped=0, unexpected_successes=0)
for name in test_ids:
    observed = invoke('run', name)
    if observed.get('kind') != 'completed' or observed.get('test_id') != name or observed.get('tests_run') != 1:
        raise RuntimeError('Incomplete dispatched test')
    for key in ('failures', 'errors', 'skipped', 'unexpected_successes'):
        value = observed.get(key)
        if type(value) is not int or value < 0 or key == 'skipped' and value > 1:
            raise RuntimeError('Invalid test result')
    successful = not any(observed[key] for key in ('failures', 'errors', 'unexpected_successes'))
    if observed.get('was_successful') is not successful:
        raise RuntimeError('Inconsistent unittest result')
    # Count our completed dispatched invocations, not a candidate-authored aggregate.
    counts['tests_run'] += 1
    for key in ('failures', 'errors', 'skipped', 'unexpected_successes'):
        counts[key] += observed[key]

counts.update(completed_invocations=counts['tests_run'], discovery_completed=True,
              test_ids_sha256=hashlib.sha256(identities).hexdigest())
with report.open('x') as output:
    json.dump(counts, output)
passed = counts['tests_run'] - counts['skipped'] >= int(minimum) and not any(
    counts[key] for key in ('failures', 'errors', 'unexpected_successes'))
raise SystemExit(0 if passed else 1)
