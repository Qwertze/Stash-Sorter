"""Check source whitespace without changing contributors' files."""
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
inputs = [*root.glob("*.kts"), *root.glob("*.md"), *root.glob("*.properties"),
          *(root / "src").rglob("*.java"), *(root / ".github").rglob("*.yml"),
          *(root / "docs").rglob("*.md"), *(root / "scripts").glob("*.py")]
errors = []
for path in sorted(inputs):
    data = path.read_bytes().replace(b"\r\n", b"\n")  # Accept native checkouts on Windows.
    if not data.endswith(b"\n"):
        errors.append(f"{path.relative_to(root)}: missing final newline")
    for number, line in enumerate(data.splitlines(), 1):
        if line.endswith((b" ", b"\t")) or line.startswith(b"\t"):
            errors.append(f"{path.relative_to(root)}:{number}: trailing whitespace or tab indent")
if errors:
    sys.exit("\n".join(errors))
print(f"Whitespace checks passed for {len(inputs)} files")
