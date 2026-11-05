#!/usr/bin/env python3
"""Merge duplicate declare-styleable blocks across AARs into the first occurrence."""
import glob, re, sys, os

root = sys.argv[1]
files = sorted(glob.glob(os.path.join(root, "*/res/values/values.xml")))
pattern = re.compile(r'<declare-styleable name="([^"]+)">(.*?)</declare-styleable>', re.S)
attr_pat = re.compile(r'<attr[^>]*(?:/>|>.*?</attr>)', re.S)

# Strip XML comments first: otherwise commented-out <attr/> entries are
# mistaken for real attributes and re-emitted as real ones.
comment_pat = re.compile(r'<!--.*?-->', re.S)
for f in files:
    txt = open(f, encoding='utf-8', errors='ignore').read()
    stripped = comment_pat.sub('', txt)
    if stripped != txt:
        open(f, 'w', encoding='utf-8').write(stripped)

# Pass 1: collect all attr blocks per styleable, preserving order, dedup by attr name
all_attrs = {}  # name -> list of (attr_name, block)
first_file = {}
for f in files:
    txt = open(f, encoding='utf-8', errors='ignore').read()
    for m in pattern.finditer(txt):
        name, body = m.group(1), m.group(2)
        if name not in all_attrs:
            all_attrs[name] = []
            first_file[name] = f
        seen = {a for a, _ in all_attrs[name]}
        for am in attr_pat.finditer(body):
            an = re.search(r'name="([^"]+)"', am.group(0))
            if an and an.group(1) not in seen:
                all_attrs[name].append((an.group(1), am.group(0)))
                seen.add(an.group(1))

# Pass 2: rewrite
for f in files:
    txt = open(f, encoding='utf-8', errors='ignore').read()
    def repl(m):
        name = m.group(1)
        if first_file[name] != f:
            return ''
        blocks = '\n'.join(b for _, b in all_attrs[name])
        return f'<declare-styleable name="{name}">\n{blocks}\n</declare-styleable>'
    new_txt = pattern.sub(repl, txt)
    if new_txt != txt:
        open(f, 'w', encoding='utf-8').write(new_txt)
        print(f"merged styleables in {f}")
print("styleable merge done")
