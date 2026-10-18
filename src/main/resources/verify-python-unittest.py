"""Fixed offline controller command. Exit 0 requires actual non-skipped unittest execution.

The generated tests remain untrusted code. Run only in the separately approved disposable
OS context, without valuable credentials/files/network authority; env -i is not isolation.
"""
import json
from pathlib import Path
import sys
import unittest

source, report, minimum = sys.argv[1:]
sys.path.insert(0, source)
suite = unittest.defaultTestLoader.discover(str(Path(source) / "tests"))
result = unittest.TextTestRunner(verbosity=2).run(suite)
evidence = {"tests_run": result.testsRun, "failures": len(result.failures),
            "errors": len(result.errors), "skipped": len(result.skipped)}
with open(report, "x") as output:
    json.dump(evidence, output)
raise SystemExit(0 if result.wasSuccessful() and result.testsRun - len(result.skipped) >= int(minimum) else 1)
