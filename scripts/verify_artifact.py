"""Verify the installation layout and bytecode contract of one built plugin."""
import argparse
import json
from pathlib import Path
from zipfile import ZipFile


def verify(jar, properties):
    with ZipFile(jar) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("duplicate archive entries")
        meta = json.loads(archive.read("zenithproxy.plugin.json"))
        for key, expected in {
            "id": properties["plugin_id"],
            "version": properties["plugin_version"],
            "mcVersions": [properties["mc"]],
            "entrypoint": "com.porter.qwertestash.QwerteStashPlugin",
        }.items():
            if meta.get(key) != expected:
                raise ValueError(f"unexpected plugin metadata for {key}")
        archive.getinfo("LICENSE")
        archive.getinfo(meta["entrypoint"].replace(".", "/") + ".class")
        recipes = json.loads(archive.read(f"qwerte-stash/recipes/{properties['mc']}.json"))
        if not recipes:
            raise ValueError("empty recipe data")
        for name in names:
            if name.endswith(".class"):
                if not name.startswith("com/porter/qwertestash/") or name.endswith("Test.class"):
                    raise ValueError(f"unexpected class in plugin: {name}")
                header = archive.read(name)[:8]
                if header[:4] != bytes.fromhex("CAFEBABE") or int.from_bytes(header[6:8], "big") != 65:
                    raise ValueError(f"unexpected class format: {name}")
        if archive.testzip() is not None:
            raise ValueError("CRC check failed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    args = parser.parse_args()
    props = (Path(__file__).resolve().parents[1] / "gradle.properties").read_text().splitlines()
    verify(args.jar, dict(line.split("=", 1) for line in props if "=" in line and not line.startswith("#")))
    print(f"Verified {args.jar.name}")
