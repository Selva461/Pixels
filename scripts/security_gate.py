#!/usr/bin/env python3
"""Security gate run by CI before anything is built. Fails (exit 1) on any finding.

What it enforces, and why (see SECURITY.md for the threat model):
- The app requests no permissions at all: no INTERNET, so photos cannot leave the device.
- Backups and device transfer exclude all app data; cleartext traffic is off.
- Only the launcher activity is exported; the FileProvider is private and grants per-URI access.
- The FileProvider exposes only the three app-private folders it needs.
- Production code has none of the APIs that turn bugs into remote-code or data-leak problems
  (WebView, dynamic code loading, shell commands, world-readable files, trust-all TLS).
- Logging goes through one logger, so no stray Log calls print user data.
- No secrets or signing keys are committed.
- CI itself runs with read-only repository permissions and never on pull_request_target.

Standard library only, so it runs before Gradle and needs nothing installed.
"""
import hashlib
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ANDROID = '{http://schemas.android.com/apk/res/android}'
findings = []


def finding(message):
    findings.append(message)


def read(path):
    with open(os.path.join(ROOT, path), encoding='utf-8') as handle:
        return handle.read()


def check_manifest():
    manifest = ET.parse(os.path.join(ROOT, 'app/src/main/AndroidManifest.xml')).getroot()
    for permission in manifest.iter('uses-permission'):
        finding(f'manifest: permission requested: {permission.get(ANDROID + "name")} (the app is designed to need none)')
    application = manifest.find('application')
    expected = {'allowBackup': 'false', 'usesCleartextTraffic': 'false'}
    for attribute, value in expected.items():
        if application.get(ANDROID + attribute) != value:
            finding(f'manifest: application android:{attribute} must be "{value}"')
    if application.get(ANDROID + 'dataExtractionRules') != '@xml/data_extraction_rules':
        finding('manifest: android:dataExtractionRules must point at @xml/data_extraction_rules')
    if application.get(ANDROID + 'debuggable') is not None:
        finding('manifest: android:debuggable must not be hard-coded (the build type sets it)')
    for kind in ('activity', 'activity-alias', 'service', 'receiver', 'provider'):
        for component in application.iter(kind):
            name = component.get(ANDROID + 'name')
            exported = component.get(ANDROID + 'exported')
            if exported is None:
                finding(f'manifest: {kind} {name} must declare android:exported explicitly')
            elif exported == 'true' and not (kind == 'activity' and name == '.ui.MainActivity'):
                finding(f'manifest: {kind} {name} is exported; only the launcher activity may be')
    for provider in application.iter('provider'):
        if provider.get(ANDROID + 'grantUriPermissions') != 'true':
            finding(f'manifest: provider {provider.get(ANDROID + "name")} must grant per-URI permissions only')


def check_file_provider_paths():
    paths = ET.parse(os.path.join(ROOT, 'app/src/main/res/xml/file_paths.xml')).getroot()
    allowed = {('cache-path', 'shared/'), ('files-path', 'captures/'), ('files-path', 'imports/')}
    for entry in paths:
        key = (entry.tag, entry.get('path'))
        if key not in allowed:
            finding(f'file_paths.xml: {entry.tag} path="{entry.get("path")}" widens what the FileProvider can serve')


def check_backup_rules():
    rules = ET.parse(os.path.join(ROOT, 'app/src/main/res/xml/data_extraction_rules.xml')).getroot()
    for section in ('cloud-backup', 'device-transfer'):
        node = rules.find(section)
        excluded = {(e.get('domain'), e.get('path')) for e in node.findall('exclude')} if node is not None else set()
        if node is None or node.findall('include'):
            finding(f'data_extraction_rules.xml: {section} must exist and include nothing')
        for domain in ('root', 'file', 'database', 'sharedpref', 'external'):
            if (domain, '.') not in excluded:
                finding(f'data_extraction_rules.xml: {section} must exclude domain "{domain}"')


FORBIDDEN_CODE = [
    (r'\bWebView\b', 'WebView (the app shows no web content)'),
    (r'\bDexClassLoader\b|\bPathClassLoader\b|\bInMemoryDexClassLoader\b', 'dynamic code loading'),
    (r'Runtime\.getRuntime\(\)\.exec|\bProcessBuilder\b', 'shell command execution'),
    (r'MODE_WORLD_READABLE|MODE_WORLD_WRITEABLE', 'world-accessible files'),
    (r'X509TrustManager|HostnameVerifier|ALLOW_ALL_HOSTNAME_VERIFIER', 'custom TLS trust (there is no network code)'),
    (r'\bjava\.net\.(URL|Socket|HttpURLConnection)\b|\bokhttp3\b', 'network access'),
    (r'\bprintStackTrace\(\)', 'printStackTrace (use the logger)'),
    (r'http://(?!schemas\.android\.com)', 'cleartext URL'),
]


def production_sources():
    for base in ('app/src/main', 'engine/domain/src/main'):
        for directory, _, files in os.walk(os.path.join(ROOT, base)):
            for name in files:
                if name.endswith(('.kt', '.java', '.xml')):
                    yield os.path.relpath(os.path.join(directory, name), ROOT)


def check_code():
    for path in production_sources():
        text = read(path)
        for pattern, what in FORBIDDEN_CODE:
            for match in re.finditer(pattern, text):
                line = text.count('\n', 0, match.start()) + 1
                finding(f'{path}:{line}: {what}')
        if path.endswith('.kt') and 'import android.util.Log' in text and not path.endswith('core/logging/LogcatLogger.kt'):
            finding(f'{path}: android.util.Log outside LogcatLogger')


SECRETS = [
    (r'AKIA[0-9A-Z]{16}', 'AWS access key'),
    (r'-----BEGIN (RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----', 'private key'),
    (r'AIza[0-9A-Za-z_\-]{35}', 'Google API key'),
    (r'\bgh[pousr]_[A-Za-z0-9]{36,}', 'GitHub token'),
    (r'\bxox[abposr]-[A-Za-z0-9-]{10,}', 'Slack token'),
    (r'(?i)(password|passwd|secret|api_?key)\s*[:=]\s*["\'][^"\'\s]{8,}["\']', 'hard-coded credential'),
    (r'storePassword|keyPassword', 'signing password in build files'),
]
BINARY_SUFFIXES = ('.jar', '.png', '.jpg', '.jpeg', '.webp', '.gif', '.ico', '.zip', '.tflite')


def tracked_files():
    try:
        output = subprocess.run(['git', 'ls-files'], cwd=ROOT, check=True, capture_output=True, text=True).stdout
        return [line for line in output.splitlines() if line]
    except (OSError, subprocess.CalledProcessError):
        return list(production_sources())


def check_secrets():
    for path in tracked_files():
        if path.endswith(('.jks', '.keystore', '.p12', '.pem', '.key')) or os.path.basename(path) in ('google-services.json', 'local.properties'):
            finding(f'{path}: key, keystore or machine-local file is committed')
            continue
        if path.endswith(BINARY_SUFFIXES) or path == 'scripts/security_gate.py':
            continue
        try:
            text = read(path)
        except (UnicodeDecodeError, FileNotFoundError, IsADirectoryError):
            continue
        for pattern, what in SECRETS:
            if re.search(pattern, text):
                finding(f'{path}: possible {what}')


def check_build_and_ci():
    build = read('app/build.gradle.kts')
    if re.search(r'isDebuggable\s*=\s*true', build):
        finding('app/build.gradle.kts: a build type is forced debuggable')
    for name in os.listdir(os.path.join(ROOT, '.github/workflows')):
        workflow = read(os.path.join('.github/workflows', name))
        if 'pull_request_target' in workflow:
            finding(f'.github/workflows/{name}: pull_request_target runs untrusted code with secrets')
        if not re.search(r'^permissions:\s*\n\s+contents:\s*read\s*$', workflow, re.M):
            finding(f'.github/workflows/{name}: must declare top-level "permissions: contents: read"')
        if re.search(r'\$\{\{\s*github\.event\.(issue|pull_request|comment|review|head_commit)\.', workflow):
            finding(f'.github/workflows/{name}: untrusted event text interpolated into a script')


MODELS_DIR = 'app/src/main/assets/models'


def check_models():
    """Every bundled model must match the checksum recorded in its README (supply-chain pin)."""
    directory = os.path.join(ROOT, MODELS_DIR)
    if not os.path.isdir(directory):
        return
    readme = read(os.path.join(MODELS_DIR, 'README.md')) if os.path.exists(os.path.join(directory, 'README.md')) else ''
    for name in sorted(os.listdir(directory)):
        if name == 'README.md':
            continue
        with open(os.path.join(directory, name), 'rb') as handle:
            digest = hashlib.sha256(handle.read()).hexdigest()
        if f'`{name}`' not in readme or digest not in readme:
            finding(f'{MODELS_DIR}/{name}: checksum {digest} is not recorded in {MODELS_DIR}/README.md')


def main():
    for check in (check_manifest, check_file_provider_paths, check_backup_rules, check_code, check_secrets, check_build_and_ci, check_models):
        check()
    if findings:
        print('Security gate FAILED:')
        print('\n'.join(f'  - {item}' for item in findings))
        return 1
    print('Security gate passed: no permissions, private provider, no backups, no risky APIs, no secrets, least-privilege CI, pinned models.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
