#!/usr/bin/env python3
"""Checks app string resources against Kotlin usage.

- every R.string / R.plurals reference exists with the right type;
- no unused resources (app_name is used by the manifest);
- stringResource/getString/pluralStringResource calls pass as many format args as the text needs;
- format placeholders are well-formed (%1$s, %1$d, %1$.1f, %%).
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
res = ET.parse(f'{ROOT}/app/src/main/res/values/strings.xml').getroot()
strings = {e.get('name'): ''.join(e.itertext()) for e in res.findall('string')}
plurals = {e.get('name'): [''.join(i.itertext()) for i in e.findall('item')] for e in res.findall('plurals')}

PLACEHOLDER = re.compile(r'%(?:(\d+)\$)?([-#+ 0,(]*\d*(?:\.\d+)?[sdfxX])|%%')
problems = []


def needed_args(text, name):
    indexes = set()
    for m in PLACEHOLDER.finditer(text):
        if m.group(0) == '%%':
            continue
        if m.group(1) is None:
            problems.append(f'{name}: positional placeholder without index: {m.group(0)}')
            continue
        indexes.add(int(m.group(1)))
    stray = re.sub(PLACEHOLDER, '', text)
    if '%' in stray:
        problems.append(f'{name}: stray % (use %%): {text!r}')
    return max(indexes) if indexes else 0


kotlin = {p: open(p).read() for p in glob.glob(f'{ROOT}/app/src/**/*.kt', recursive=True)}
manifest = open(f'{ROOT}/app/src/main/AndroidManifest.xml').read()
used_strings, used_plurals = set(), set()


def split_args(text, start):
    """Arguments of the call whose '(' is at text[start]; paren/brace/string aware."""
    depth, i, args, current, in_str = 0, start, [], '', None
    while i < len(text):
        c = text[i]
        if in_str:
            current += c
            if c == '\\':
                current += text[i + 1]
                i += 1
            elif c == in_str:
                in_str = None
        elif c in '"\'':
            in_str = c
            current += c
        elif c in '([{':
            depth += 1
            if depth > 1:
                current += c
        elif c in ')]}':
            depth -= 1
            if depth == 0:
                args.append(current.strip())
                return [a for a in args if a]
            current += c
        elif c == ',' and depth == 1:
            args.append(current.strip())
            current = ''
        else:
            current += c
        i += 1
    raise ValueError('unbalanced call')


for path, text in kotlin.items():
    short = path.replace(ROOT + '/', '')
    for m in re.finditer(r'R\.string\.(\w+)', text):
        used_strings.add(m.group(1))
        if m.group(1) not in strings:
            problems.append(f'{short}: missing string {m.group(1)}')
    for m in re.finditer(r'R\.plurals\.(\w+)', text):
        used_plurals.add(m.group(1))
        if m.group(1) not in plurals:
            problems.append(f'{short}: missing plurals {m.group(1)}')
    for m in re.finditer(r'\b(stringResource|getString|pluralStringResource)\(', text):
        args = split_args(text, m.end() - 1)
        first = args[0] if args else ''
        ref = re.fullmatch(r'R\.(string|plurals)\.(\w+)', first)
        if not ref:
            continue  # dynamic id, e.g. stringResource(tool.labelRes)
        kind, name = ref.groups()
        if m.group(1) == 'pluralStringResource':
            if kind != 'plurals':
                problems.append(f'{short}: pluralStringResource with a string: {name}')
                continue
            format_args = len(args) - 2
            need = max(needed_args(t, name) for t in plurals.get(name, ['']))
        else:
            if kind != 'string':
                problems.append(f'{short}: {m.group(1)} with plurals {name}')
                continue
            format_args = len(args) - 1
            need = needed_args(strings.get(name, ''), name)
        if format_args != need:
            problems.append(f'{short}: {name} needs {need} format args, call passes {format_args}')

for name in strings:
    if name not in used_strings and f'@string/{name}' not in manifest:
        problems.append(f'unused string {name}')
for name in plurals:
    if name not in used_plurals:
        problems.append(f'unused plurals {name}')

print('\n'.join(problems) if problems else f'resources clean ({len(strings)} strings, {len(plurals)} plurals)')
sys.exit(1 if problems else 0)
