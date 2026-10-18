#!/usr/bin/env python3
"""Builds the in-app Help page (app/src/main/assets/userguide.html) from USERGUIDE.md.

Run after editing USERGUIDE.md:  python3 tools/userguide_html.py
Needs the Python "markdown" package. Heading anchors match GitHub's, so the
same links work in both copies.
"""
import pathlib
import re

import markdown

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "USERGUIDE.md"
OUT = ROOT / "app/src/main/assets/userguide.html"


def github_slug(value, separator):
    """GitHub's heading ids: lower case, punctuation dropped, spaces to hyphens."""
    value = value.strip().lower()
    value = re.sub(r"[^\w\- ]", "", value)
    return value.replace(" ", "-")


body = markdown.markdown(
    SRC.read_text(encoding="utf-8"),
    extensions=["tables", "toc"],
    extension_configs={"toc": {"slugify": github_slug}},
)

page = f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Energy Plan – User Guide</title>
<style>
  :root {{ --brand: #2E7D32; --ink: #1a1a1a; --muted: #546E7A; --rule: #CFD8DC; --bg: #ffffff; --code: #F1F3F4; }}
  body {{ margin: 0; padding: 16px; background: var(--bg); color: var(--ink);
         font: 16px/1.55 Roboto, "Segoe UI", sans-serif; max-width: 860px; }}
  h1 {{ color: var(--brand); font-size: 1.6em; margin: 0.2em 0 0.6em; }}
  h2 {{ color: var(--brand); font-size: 1.3em; margin-top: 1.8em; padding-top: 0.6em; border-top: 1px solid var(--rule); }}
  h3 {{ font-size: 1.1em; margin-top: 1.4em; }}
  a {{ color: var(--brand); }}
  table {{ border-collapse: collapse; width: 100%; margin: 0.8em 0; font-size: 0.95em; display: block; overflow-x: auto; }}
  th, td {{ border: 1px solid var(--rule); padding: 6px 8px; text-align: left; vertical-align: top; }}
  th {{ background: #E8F5E9; }}
  code {{ background: var(--code); padding: 1px 4px; border-radius: 3px; font-size: 0.92em; word-break: break-all; }}
  hr {{ border: 0; border-top: 1px solid var(--rule); }}
  li {{ margin: 0.25em 0; }}
</style>
</head>
<body>
{body}
</body>
</html>
"""
OUT.write_text(page, encoding="utf-8")
print(f"Wrote {OUT.relative_to(ROOT)} ({len(page)} bytes)")
