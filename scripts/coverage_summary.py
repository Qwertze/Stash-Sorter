"""Summarize JaCoCo counters and measure executable Java lines added by a pull request."""
import argparse
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET


def changed_lines(base):
    # Git emits unquoted paths for all current Java sources (ASCII with no spaces).
    diff = subprocess.check_output(
        ["git", "diff", "--no-ext-diff", "--no-color", "--unified=0", f"{base}...HEAD", "--", "src/main/java"],
        text=True,
    )
    selected = set()
    path = None
    for line in diff.splitlines():
        if line.startswith("+++ "):
            path = line.removeprefix("+++ b/src/main/java/")
        elif line.startswith("@@ ") and path is not None:
            match = re.match(r"@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@", line)
            start = int(match[1])
            count = int(match[2] or 1)
            selected.update((path, n) for n in range(start, start + count))
    return selected


def show(label, covered, total):
    percentage = f"{100 * covered / total:.2f}%" if total else "n/a"
    print(f"{label}: {covered}/{total} ({percentage})")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    parser.add_argument("--base")
    parser.add_argument("--enforce-changes", action="store_true")
    args = parser.parse_args()
    report = ET.parse(args.report).getroot()
    for kind in ("LINE", "BRANCH"):
        node = report.find(f"counter[@type='{kind}']")
        covered = int(node.attrib["covered"])
        show(kind, covered, covered + int(node.attrib["missed"]))
    if args.base:
        selected = changed_lines(args.base)
        executable = []
        for package in report.findall("package"):
            for source in package.findall("sourcefile"):
                path = package.attrib["name"] + "/" + source.attrib["name"]
                executable.extend(int(line.attrib["ci"]) > 0 for line in source.findall("line")
                                  if (path, int(line.attrib["nr"])) in selected)
        show("Changed executable lines", sum(executable), len(executable))
        if args.enforce_changes and 10 * sum(executable) < 9 * len(executable):
            raise SystemExit("Changed-line coverage is below 90%")
