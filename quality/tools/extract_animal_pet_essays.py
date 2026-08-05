"""Extract the canonical DOCX corpus without modifying its source files."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from zipfile import ZipFile
from xml.etree import ElementTree


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "animal-pet-essays" / "source"
FIXTURES = ROOT / "animal-pet-essays" / "fixtures"
MANIFEST = ROOT / "animal-pet-essays" / "fixture-manifest.json"
WORD_NS = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest().upper()


def paragraphs(path: Path) -> list[str]:
    with ZipFile(path) as archive:
        document = ElementTree.fromstring(archive.read("word/document.xml"))
    values: list[str] = []
    for paragraph in document.iter(WORD_NS + "p"):
        text = "".join(node.text or "" for node in paragraph.iter(WORD_NS + "t")).strip()
        if text:
            values.append(text)
    return values


def main() -> None:
    FIXTURES.mkdir(parents=True, exist_ok=True)
    records = []
    for source in sorted(SOURCE.glob("*.docx")):
        blocks = paragraphs(source)
        if len(blocks) != 10:
            raise RuntimeError(f"{source.name}: expected 10 sections, found {len(blocks)}")
        fixture = FIXTURES / f"{source.stem}.txt"
        fixture.write_text("\n\n".join(blocks) + "\n", encoding="utf-8", newline="\n")
        records.append(
            {
                "source": source.name,
                "sourceSha256": sha256(source),
                "fixture": fixture.name,
                "fixtureSha256": sha256(fixture),
                "sections": len(blocks),
                "words": sum(len(block.split()) for block in blocks),
            }
        )
    if len(records) != 20:
        raise RuntimeError(f"expected 20 essays, found {len(records)}")
    MANIFEST.write_text(
        json.dumps({"schema": 1, "essays": records}, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(f"extracted={len(records)} sections={sum(item['sections'] for item in records)}")


if __name__ == "__main__":
    main()
