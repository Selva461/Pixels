import os, sys, xml.etree.ElementTree as ET
res = ET.parse(sys.argv[1]).getroot()
out = ["package com.pixels.enhancer", "", "object R {"]
for kind in ("string", "plurals"):
    out.append(f"    object {kind} {{")
    for i, e in enumerate(res.findall(kind)):
        out.append(f"        const val {e.get('name')} = {0x7f000000 + i}")
    out.append("    }")
# Drawables and mipmaps by file name (res/values/strings.xml -> res/).
res_dir = os.path.dirname(os.path.dirname(os.path.abspath(sys.argv[1])))
for kind, offset in (("drawable", 0x7f010000), ("mipmap", 0x7f020000)):
    names = sorted({os.path.splitext(f)[0] for d in os.listdir(res_dir) if d == kind or d.startswith(kind + "-") for f in os.listdir(os.path.join(res_dir, d))})
    out.append(f"    object {kind} {{")
    out += [f"        const val {n} = {offset + i}" for i, n in enumerate(names)]
    out.append("    }")
out += ["}", "", "object BuildConfig {", "    @JvmField val DEBUG: Boolean = java.lang.Boolean.parseBoolean(\"true\")", "    const val VERSION_NAME = \"1.0\"", "    const val APPLICATION_ID = \"com.pixels.enhancer\"", "}"]
open(sys.argv[2], "w").write("\n".join(out) + "\n")
