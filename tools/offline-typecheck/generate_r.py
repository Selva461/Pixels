import sys, xml.etree.ElementTree as ET
res = ET.parse(sys.argv[1]).getroot()
out = ["package com.pixels.enhancer", "", "object R {"]
for kind in ("string", "plurals"):
    out.append(f"    object {kind} {{")
    for i, e in enumerate(res.findall(kind)):
        out.append(f"        const val {e.get('name')} = {0x7f000000 + i}")
    out.append("    }")
out += ["}", "", "object BuildConfig {", "    @JvmField val DEBUG: Boolean = java.lang.Boolean.parseBoolean(\"true\")", "    const val VERSION_NAME = \"1.0\"", "    const val APPLICATION_ID = \"com.pixels.enhancer\"", "}"]
open(sys.argv[2], "w").write("\n".join(out) + "\n")
