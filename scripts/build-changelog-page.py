#!/usr/bin/env python3
"""Renders CHANGELOG.md into web/changelog.html (between the two markers), so the website, the app's "What's new" and the repository
show one document. Run it after every version bump:  python3 scripts/build-changelog-page.py
Only dated versions are shown (an undated heading is an internal build); the engineering history stays in CHANGELOG.md."""
import html, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
START, END = "<!-- changelog:start -->", "<!-- changelog:end -->"


def inline(text: str) -> str:
    out = html.escape(text, quote=False)
    out = re.sub(r"\[([^\]]+)\]\(([^)\s]+)\)", r'<a href="\2">\1</a>', out)
    out = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", out)
    return re.sub(r"`([^`]+)`", r"<code>\1</code>", out)


def parse(markdown: str):
    entries, cur, section = [], None, None
    for raw in markdown.splitlines():
        line = raw.rstrip()
        head = re.match(r"^##\s+\[?(\d+\.\d+\.\d+)\]?\s*(.*)$", line)
        if head:
            date = re.search(r"\d{4}-\d{2}-\d{2}", head.group(2))
            cur = {"version": head.group(1), "date": date.group(0) if date else None, "intro": [], "sections": []}
            entries.append(cur)
            section = None
            continue
        if cur is None:
            continue
        sec = re.match(r"^###\s+(.*\S)\s*$", line)
        if sec:
            section = {"title": sec.group(1), "items": []}
            cur["sections"].append(section)
            continue
        if section is None:
            if line.strip():
                cur["intro"].append(line.strip())
            continue
        if line.startswith(("- ", "* ")):
            section["items"].append(line[2:].strip())
        elif line.strip() and section["items"]:
            section["items"][-1] += " " + line.strip()
    return entries


def render(entries) -> str:
    parts = []
    for e in entries:
        if not e["date"]:
            continue
        body = [f'<article class="info-card release" id="v{e["version"].replace(".", "-")}"><h2>Version {e["version"]} <small>{e["date"]}</small></h2>']
        if e["intro"]:
            body.append(f'<p>{inline(" ".join(e["intro"]))}</p>')
        for s in e["sections"]:
            if s["title"].lower().startswith("development history") or not s["items"]:
                continue
            body.append(f'<h3>{inline(s["title"])}</h3><ul>' + "".join(f"<li>{inline(i)}</li>" for i in s["items"]) + "</ul>")
        body.append("</article>")
        parts.append("".join(body))
    return "\n".join(parts)


def main() -> int:
    entries = parse((ROOT / "CHANGELOG.md").read_text(encoding="utf-8"))
    page = ROOT / "web" / "changelog.html"
    text = page.read_text(encoding="utf-8")
    if START not in text or END not in text:
        print("web/changelog.html has no changelog markers", file=sys.stderr)
        return 1
    new = text[: text.index(START) + len(START)] + "\n" + render(entries) + "\n" + text[text.index(END):]
    page.write_text(new, encoding="utf-8")
    print(f"rendered {sum(1 for e in entries if e['date'])} release(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
