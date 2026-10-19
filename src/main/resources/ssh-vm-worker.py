"""Stage 1 SSH worker. Fixed Owner installation, not an Issue-facing shell API.

No outer sandbox around Codex. Only unittest children enter bubblewrap. A durable
claim is never retried; query reads saved facts and never executes a task.
"""
import base64
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import resource
import stat
import subprocess
import sys
import time

LIMIT = 24 * 1024 * 1024
REPO = 1424152600
INSTALL = Path(__file__).resolve().parent


def sha(data):
    return hashlib.sha256(data).hexdigest()


def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('duplicate field')
        result[key] = value
    return result


def load(data):
    return json.loads(data, object_pairs_hook=unique)


def encoded(value):
    return json.dumps(value, separators=(',', ':'), sort_keys=True).encode()


def read(path, limit=LIMIT):
    s = path.lstat()
    if not stat.S_ISREG(s.st_mode) or s.st_nlink != 1 or s.st_size > limit:
        raise ValueError('unsafe file')
    with path.open('rb') as stream:
        result = stream.read(limit + 1)
    if len(result) > limit:
        raise ValueError('oversize')
    return result


def sync_dir(path):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def write(path, data):
    # Publish only complete, fsynced evidence. Never replace an existing fact.
    pending = path.with_name(path.name + '.pending')
    with pending.open('xb') as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    os.link(pending, path)  # atomic no-replace publication
    pending.unlink()
    sync_dir(path.parent)


def installation_digest():
    return sha(b''.join(read(INSTALL / name) for name in
                        ('ssh-vm-worker.py', 'native-codex-supervisor.py', 'verify-python-unittest.py')))


def config(path):
    if path.resolve() != path or path.stat().st_uid != os.getuid() or path.stat().st_mode & 0o077:
        raise ValueError('private config required')
    c = load(read(path, 8192))
    if set(c) != {'root', 'modelHome', 'codex'}:
        raise ValueError('config schema')
    for key in c:
        p = Path(c[key])
        if not p.is_absolute() or p.resolve() != p:
            raise ValueError('canonical path required')
    root, home = Path(c['root']), Path(c['modelHome'])
    # unittest's minimal OS mounts must never contain delegated secrets/state.
    for exposed in (Path('/usr'), Path('/bin').resolve(), Path('/sbin').resolve(), Path('/lib').resolve(), Path('/lib64').resolve(), Path('/proc'), Path('/dev'), Path('/sys'), Path('/etc')):
        if any(p == exposed or exposed in p.parents for p in (root, home)):
            raise ValueError('state/model HOME overlaps minimal OS mounts')
    model = home / '.codex'
    if model.is_symlink() or (model.exists() and (not model.is_dir() or model.resolve() != model)):
        raise ValueError('canonical dedicated Codex directory required')
    auth = model / 'auth.json'
    if auth.exists() or auth.is_symlink():
        metadata = auth.lstat()
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1:
            raise ValueError('regular private model auth required')
    if not root.is_dir() or not home.is_dir() or root == home or root in home.parents or home in root.parents:
        raise ValueError('overlapping roots')
    if any(p.stat().st_uid != os.getuid() or p.stat().st_mode & 0o077 for p in (root, home)) or INSTALL in root.parents or root == INSTALL or root in INSTALL.parents:
        raise ValueError('private separate state required')
    return c


def safe_name(name):
    parts = name.split('/')
    if not name or len(name) > 1024 or any(x in ('', '.', '..', '.git', '.codex', '.ssh') for x in parts) or any(ord(x) < 32 or 127 <= ord(x) <= 159 or x == '\\' for x in name) or name.startswith('/'):
        raise ValueError('unsafe export path')


def image(files, destination=None):
    if not isinstance(files, list) or len(files) > 1000:
        raise ValueError('image size')
    names, total, decoded = set(), 0, []
    for f in files:
        if set(f) != {'path', 'data', 'sha256', 'executable'} or type(f['executable']) is not bool:
            raise ValueError('image schema')
        safe_name(f['path'])
        if f['path'] in names:
            raise ValueError('duplicate path')
        names.add(f['path'])
        data = base64.b64decode(f['data'], validate=True)
        total += len(data)
        if len(data) > 1024 * 1024 or total > 8 * 1024 * 1024 or sha(data) != f['sha256']:
            raise ValueError('image digest/limit')
        decoded.append((f, data))
    if any(any(other.startswith(name + '/') for other in names) for name in names):
        raise ValueError('file/directory conflict')
    parents = {str(p) for name in names for p in Path(name).parents if str(p) != '.'}
    if len(parents) >= 2000:
        raise ValueError('directory limit')
    if destination is not None:
        destination.mkdir(mode=0o700)
        for f, data in decoded:
            p = destination / f['path']
            p.parent.mkdir(parents=True, exist_ok=True)
            with p.open('xb') as output:
                output.write(data)
            p.chmod(0o700 if f['executable'] else 0o600)


def inventory(root):
    result, total, directories = [], 0, 0
    for directory, dirs, files in os.walk(root, followlinks=False):
        directories += 1
        if directories > 2000:
            raise ValueError('directory limit')
        if Path(directory) == root and '.git' in dirs:
            dirs.remove('.git')
        for name in dirs:
            p = Path(directory) / name
            safe_name(p.relative_to(root).as_posix())
            if p.is_symlink():
                raise ValueError('symlink directory')
        for name in sorted(files):
            p = Path(directory) / name
            relative = p.relative_to(root).as_posix()
            safe_name(relative)
            data = read(p, 1024 * 1024)
            total += len(data)
            if len(result) >= 1000 or total > 8 * 1024 * 1024:
                raise ValueError('export limit')
            result.append({'path': relative, 'data': base64.b64encode(data).decode(), 'sha256': sha(data),
                           'executable': bool(p.stat().st_mode & 0o111)})
    return sorted(result, key=lambda x: x['path'])


def env(home='/nonexistent'):
    return {'PATH': '/usr/local/bin:/usr/bin:/bin', 'HOME': home, 'LANG': 'C.UTF-8',
            'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': '/dev/null', 'GIT_TERMINAL_PROMPT': '0'}


def git(cwd, *args):
    result = subprocess.run(['/usr/bin/git', '-c', 'core.hooksPath=/dev/null', *args], cwd=cwd,
                            env=env(), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
    if result.returncode or len(result.stdout) > 1024 * 1024 or len(result.stderr) > 1024 * 1024:
        raise ValueError('git evidence failed')
    return result.stdout.decode().strip()


def tree(source, directory):
    directory.mkdir()
    empty = directory / 'template'
    empty.mkdir()
    objects = directory / 'objects.git'
    git(directory, 'init', '--bare', '--template=' + str(empty), str(objects))
    args = ('--git-dir=' + str(objects), '--work-tree=' + str(source))
    git(source, *args, 'read-tree', '--empty')
    git(source, *args, 'add', '--force', '--all', '--', '.')
    return git(source, *args, 'write-tree')


def request(data):
    if len(data) > LIMIT:
        raise ValueError('request limit')
    r = load(data)
    if set(r) != {'version', 'repositoryId', 'identity', 'workerSha256', 'files', 'bundle', 'bundleSha256', 'prompt', 'model', 'effort', 'minimumTests', 'timeoutMs'} or type(r['version']) is not int or r['version'] != 1 or type(r['repositoryId']) is not int or r['repositoryId'] != REPO or r['workerSha256'] != installation_digest():
        raise ValueError('request/installation mismatch')
    i = r['identity']
    if set(i) != {'attemptId', 'workflowId', 'issueNumber', 'baseSha', 'sourceTree', 'generation', 'role', 'candidateId', 'manifestSha256', 'codingSessionId'}:
        raise ValueError('identity schema')
    ident = lambda s: isinstance(s, str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,127}', s)
    hexsha = lambda s, n: isinstance(s, str) and re.fullmatch('[0-9a-f]{' + str(n) + '}', s)
    if not ident(i['attemptId']) or not ident(i['workflowId']) or type(i['issueNumber']) is not int or i['issueNumber'] <= 0 or type(i['generation']) is not int or i['generation'] not in range(3) or i['role'] not in ('CODING', 'TESTS', 'REVIEW') or not hexsha(i['baseSha'], 40) or not hexsha(i['sourceTree'], 40):
        raise ValueError('identity mismatch')
    if i['role'] != 'CODING' and (not ident(i['candidateId']) or not hexsha(i['manifestSha256'], 64) or not ident(i['codingSessionId'])):
        raise ValueError('candidate binding required')
    if not isinstance(r['prompt'], str) or len(r['prompt'].encode()) > 65536 or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._:/+-]{0,127}', r['model']) or r['effort'] not in ('default', 'low', 'medium', 'high', 'xhigh') or type(r['timeoutMs']) is not int or not 50 <= r['timeoutMs'] <= 120000 or type(r['minimumTests']) is not int or not 1 <= r['minimumTests'] <= 10000:
        raise ValueError('execution parameters')
    image(r['files'])
    bundle = base64.b64decode(r['bundle'], validate=True)
    if len(bundle) > 4 * 1024 * 1024 or sha(bundle) != r['bundleSha256']:
        raise ValueError('bundle digest')
    return r


def supervisor_module():
    spec = importlib.util.spec_from_file_location('trusted_supervisor', INSTALL / 'native-codex-supervisor.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_prefix(source):
    args = ['/usr/bin/bwrap', '--unshare-all', '--die-with-parent', '--new-session', '--cap-drop', 'ALL',
            '--ro-bind', '/usr', '/usr', '--proc', '/proc', '--dev', '/dev', '--tmpfs', '/tmp']
    for name in ('bin', 'sbin', 'lib', 'lib64'):
        p = Path('/') / name
        if p.is_symlink():
            args += ['--symlink', os.readlink(p), str(p)]
        elif p.exists():
            args += ['--ro-bind', str(p), str(p)]
    args += ['--ro-bind', str(source), str(source), '--chdir', str(source), '--']
    return args


def native_policy(c, source, attempt, role, supervisor):
    # Permission profiles do not compose with legacy --sandbox settings. Refuse
    # inherited user/system configuration rather than silently weaken this profile.
    home = Path(c['modelHome'])
    for path in (home / '.codex' / 'config.toml', Path('/etc/codex/config.toml'), Path('/etc/codex/managed_config.toml')):
        if path.exists() or path.is_symlink():
            raise ValueError('review dedicated Codex configuration first')
    # Codex 0.163 may exec this standard argv0 helper by PATH inside bwrap.
    # Keep only that alias readable, outside credential HOME and denied tmp roots.
    helpers = INSTALL / 'native-bin'
    helpers.mkdir(mode=0o700, exist_ok=True)
    if helpers.resolve() != helpers or helpers.stat().st_mode & 0o077:
        raise ValueError('private helper directory required')
    alias = helpers / 'codex-linux-sandbox'
    if not alias.is_symlink() and not alias.exists():
        alias.symlink_to(c['codex'])
    if set(helpers.iterdir()) != {alias} or not alias.is_symlink() or alias.resolve() != Path(c['codex']):
        raise ValueError('native helper alias mismatch')
    access = 'write' if role == 'CODING' else 'read'
    binaries = [Path(c['codex'])]
    parent = binaries[0].parent
    for name in ('code-mode-host', 'codex-code-mode-host', '../codex-resources/bwrap', '../codex-path/rg'):
        helper = (parent / name).resolve()
        if helper.is_file():
            binaries.append(helper)
    binary_rules = ''.join(',' + json.dumps(str(p)) + '="read"' for p in [*binaries, helpers])
    profile = ('permissions.devorch={extends=":workspace",filesystem={":root"="deny",'
               '":minimal"="read",":tmpdir"="deny",":slash_tmp"="deny",'
               '":workspace_roots"={"."="' + access + '"}' + binary_rules + '},network={enabled=false}}')
    overrides = ['--config', 'default_permissions="devorch"', '--config', profile,
                 '--config', 'approval_policy=never']
    canary = home / '.codex' / 'devorch-policy-canary'
    if not canary.exists():
        write(canary, b'ARTIFICIAL_POLICY_SECRET')
    if read(canary, 64) != b'ARTIFICIAL_POLICY_SECRET':
        raise ValueError('canary changed')
    # Exact-file tests, not globs. A read-only profile must also deny source writes.
    probe = source / ('devorch-policy-probe-' + os.urandom(12).hex())
    write(probe, b'public-source-probe')
    script = ("from pathlib import Path\n"
              "for text in " + repr([str(canary), str(attempt / 'request.json'), str(INSTALL / 'ssh-vm-worker.py'),
                                    '/proc/self/root' + str(canary), '/proc/' + str(os.getpid()) + '/environ']) + ":\n"
              " p=Path(text)\n"
              " try: p.read_bytes()\n"
              " except OSError: pass\n"
              " else: raise SystemExit(91)\n"
              " try: p.open('r+b')\n"
              " except OSError: pass\n"
              " else: raise SystemExit(92)\n"
              "p=Path(" + repr(str(alias)) + ")\n"
              "try: p.open('r+b')\nexcept OSError: pass\nelse: raise SystemExit(94)\n"
              "try: p.unlink()\nexcept OSError: pass\nelse: raise SystemExit(95)\n"
              "p=Path(" + repr(str(probe)) + ")\nassert p.read_bytes()==b'public-source-probe'\n")
    if role == 'CODING':
        script += "p.write_bytes(b'probe'); assert p.read_bytes()==b'probe'; p.write_bytes(b'public-source-probe')\n"
    else:
        script += "try: p.write_bytes(b'probe')\nexcept OSError: pass\nelse: raise SystemExit(93)\n"
    argv = [c['codex'], 'sandbox', *overrides,
            '--', '/usr/bin/python3', '-I', '-S', '-c', script]
    child_env = env(str(home))
    child_env['CODEX_HOME'] = str(home / '.codex')
    child_env['PATH'] = str(helpers) + ':' + child_env['PATH']
    with (attempt / 'policy.log').open('xb') as output:
        os.dup2(output.fileno(), 1)
        os.dup2(output.fileno(), 2)
        raw = supervisor.run({'argv': argv, 'cwd': str(source), 'env': child_env,
                              'timeout_ms': 10000, 'namespace_boundary': True})
    write(attempt / 'policy-receipt.json', encoded(raw))
    if raw.get('exit_code') != 0 or raw.get('cause') != 'exited' or not raw.get('cleanup_echild') or read(probe, 64) != b'public-source-probe' or read(canary, 64) != b'ARTIFICIAL_POLICY_SECRET':
        raise ValueError('native permission policy not enforced')
    probe.unlink()
    return overrides


def execute(c, attempt):
    r = request(read(attempt / 'request.json'))
    supervisor = supervisor_module()
    identity = {'boot_id': Path('/proc/sys/kernel/random/boot_id').read_text().strip(),
                'machine_id': Path('/etc/machine-id').read_text().strip(), 'supervisor': supervisor.identity(os.getpid())}
    write(attempt / 'started.json', encoded(identity))
    raw = None
    try:
        source = attempt / 'source'
        image(r['files'], source)
        if tree(source, attempt / 'input-tree') != r['identity']['sourceTree']:
            raise ValueError('input tree mismatch')
        write(attempt / 'base.bundle', base64.b64decode(r['bundle'], validate=True))
        # The actual pinned commit, not a synthetic local receipt/commit.
        git(source, 'init', '--template=' + str(attempt / 'input-tree' / 'template'))
        git(source, 'fetch', '--no-tags', str(attempt / 'base.bundle'), 'HEAD')
        if git(source, 'rev-parse', 'FETCH_HEAD') != r['identity']['baseSha']:
            raise ValueError('base commit mismatch')
        git(source, 'update-ref', 'HEAD', r['identity']['baseSha'])
        git(source, 'read-tree', r['identity']['baseSha'])
        resource.setrlimit(resource.RLIMIT_FSIZE, (LIMIT, LIMIT))
        role = r['identity']['role']
        if role == 'TESTS':
            argv = ['/usr/bin/python3', '-I', '-B', str(INSTALL / 'verify-python-unittest.py'), str(source),
                    str(attempt / 'tests.json'), str(r['minimumTests']), json.dumps(test_prefix(source))]
            child_env = env()
        else:
            home = c['modelHome']
            read(Path(home) / '.codex' / 'auth.json', 1024 * 1024)  # presence only; never logged/exported
            policy = native_policy(c, source, attempt, role, supervisor)
            argv = [c['codex'], 'exec', '--ignore-user-config', '--ignore-rules', '--strict-config', *policy, '--model', r['model'], '--json',
                    '--output-last-message', str(attempt / 'last-message.txt')]
            if r['effort'] != 'default':
                argv += ['--config', 'model_reasoning_effort=' + r['effort']]
            argv += ['-']  # new exec session; never resume a Coding session for Review
            child_env = env(home)
            child_env['CODEX_HOME'] = str(Path(home) / '.codex')
            child_env['PATH'] = str(INSTALL / 'native-bin') + ':' + child_env['PATH']
        write(attempt / 'prompt', r['prompt'].encode())
        # Kernel bounds each captured file, including adversarial CLI output. No nested Codex bwrap.
        with (attempt / 'prompt').open('rb') as stdin, (attempt / 'events.log').open('xb') as stdout, (attempt / 'stderr.log').open('xb') as stderr:
            os.dup2(stdin.fileno(), 0)
            os.dup2(stdout.fileno(), 1)
            os.dup2(stderr.fileno(), 2)
            raw = supervisor.run({'argv': argv, 'cwd': str(source), 'env': child_env,
                                  'timeout_ms': r['timeoutMs'], 'namespace_boundary': role == 'TESTS'})
        write(attempt / 'supervisor.json', encoded(raw))
        if not raw.get('cleanup_echild'):
            raise ValueError('cleanup not proven')
        files = inventory(source)
        output_tree = tree(source, attempt / 'output-tree')
        if role != 'CODING' and output_tree != r['identity']['sourceTree']:
            raise ValueError('read-only source changed')
        artifacts = {}
        for name in ('events.log', 'stderr.log', 'last-message.txt', 'tests.json', 'tests.test-ids.json'):
            p = attempt / name
            if p.exists():
                data = read(p, 1024 * 1024)
                artifacts[name] = {'data': base64.b64encode(data).decode(), 'sha256': sha(data)}
        result = {'state': 'FINISHED', 'receipt': raw, 'outputTree': output_tree, 'files': files, 'artifacts': artifacts}
        result.update({'version': 1, 'requestSha256': sha(read(attempt / 'request.json')), 'workerSha256': installation_digest(), 'identity': r['identity'], 'started': identity, 'nativePolicyReceipt': load(read(attempt / 'policy-receipt.json')) if role != 'TESTS' else None})
        payload = encoded(result)
        if len(payload) > LIMIT:
            raise ValueError('result limit')
        write(attempt / 'result.json', payload)
    except BaseException:
        try:
            supervisor.emergency_cleanup()
        except BaseException:
            pass
        # A failure receipt is diagnostic only; no positive success or cleanup is invented.
        write(attempt / 'failure.json', encoded({'state': 'UNKNOWN', 'code': 'EXECUTION_OR_EVIDENCE_FAILED'}))


def query(root, attempt_id, digest):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,127}', attempt_id) or not re.fullmatch('[0-9a-f]{64}', digest):
        raise ValueError('query identity')
    attempt = root / attempt_id
    if not attempt.exists():
        return {'state': 'NOT_FOUND'}
    if sha(read(attempt / 'request.json')) != digest:
        raise ValueError('query digest mismatch')
    if (attempt / 'result.json').exists():
        return load(read(attempt / 'result.json'))
    result = {'state': 'UNKNOWN'}
    if (attempt / 'started.json').exists():
        started = load(read(attempt / 'started.json'))
        result['started'] = started
        try:
            current = supervisor_module().identity(started['supervisor']['pid'])
            boot = Path('/proc/sys/kernel/random/boot_id').read_text().strip()
            if current == started['supervisor'] and boot == started['boot_id'] and not (attempt / 'failure.json').exists():
                result['state'] = 'RUNNING'
        except (OSError, ValueError):
            pass
    return result


def serve(c, config_path):
    wire = sys.stdin.buffer.readline(2 * LIMIT + 1)
    if len(wire) > 2 * LIMIT or not wire.endswith(b'\n'):
        raise ValueError('wire limit')
    wire = load(wire)
    root = Path(c['root'])
    if wire.get('op') == 'query' and set(wire) == {'op', 'attemptId', 'requestSha256'}:
        return query(root, wire['attemptId'], wire['requestSha256'])
    if set(wire) != {'op', 'requestBase64'} or wire['op'] != 'submit':
        raise ValueError('operation')
    data = base64.b64decode(wire['requestBase64'], validate=True)
    r = request(data)
    i = r['identity']
    with (root / 'worker.lock').open('a+b') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        attempt = root / i['attemptId']
        if attempt.exists():
            return query(root, i['attemptId'], sha(data))  # never execute duplicate
        # A single uncertain claim blocks new work. No blind retry, even after reboot.
        for previous in root.iterdir():
            if previous.is_dir() and not (previous / 'result.json').is_file():
                return {'state': 'BLOCKED_UNKNOWN'}
            if previous.is_dir():
                old = load(read(previous / 'request.json'))['identity']
                if (old['workflowId'], old['role'], old['generation']) == (i['workflowId'], i['role'], i['generation']):
                    return {'state': 'DUPLICATE_SLOT'}
        attempt.mkdir(mode=0o700)
        sync_dir(root)  # persist the claim directory itself before launch
        write(attempt / 'request.json', data)  # fsynced claim BEFORE Popen
        with open(os.devnull, 'rb') as stdin, (attempt / 'launcher.log').open('xb') as output:
            subprocess.Popen(['/usr/bin/python3', '-I', '-S', str(INSTALL / 'ssh-vm-worker.py'), '_execute',
                              str(config_path), i['attemptId']], stdin=stdin, stdout=output, stderr=output,
                             start_new_session=True, close_fds=True, env=env())
    # Caller may disappear at any point. Detached supervisor keeps the claim/result.
    deadline = time.monotonic() + r['timeoutMs'] / 1000 + 10
    while time.monotonic() < deadline:
        result = query(root, i['attemptId'], sha(data))
        if result['state'] == 'FINISHED' or (attempt / 'failure.json').exists():
            return result
        time.sleep(0.05)
    return query(root, i['attemptId'], sha(data))


if __name__ == '__main__':
    os.umask(0o077)
    try:
        if len(sys.argv) < 3:
            raise ValueError('fixed invocation required')
        cfg_path = Path(sys.argv[2])
        cfg = config(cfg_path)
        if sys.argv[1] == '_execute' and len(sys.argv) == 4 and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,127}', sys.argv[3]):
            execute(cfg, Path(cfg['root']) / sys.argv[3])
        elif sys.argv[1] == 'serve' and len(sys.argv) == 3:
            print(json.dumps(serve(cfg, cfg_path), separators=(',', ':')), flush=True)
        else:
            raise ValueError('fixed invocation required')
    except BaseException:
        # Never echo credentials, prompt, arbitrary subprocess stderr, config or exception text.
        print('{"state":"DENIED","code":"WORKER_PROTOCOL_OR_CONFIG_REJECTED"}', flush=True)
        sys.exit(1)
