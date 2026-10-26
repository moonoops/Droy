#!/usr/bin/env python3
"""Build a locked ARM64 Android Node/shell/search payload from Termux packages.

Only --update-lock reads the moving package index. Normal builds replay exact
package URLs and SHA-256 hashes and fail if any dependency or library is absent.
"""

import argparse
import gzip
import hashlib
import io
import json
import posixpath
from pathlib import Path, PurePosixPath
import re
import shutil
import struct
import subprocess
import sys
import tarfile
import tempfile
import urllib.request


REPOSITORY = "https://packages.termux.dev/apt/termux-main/"
INDEX = REPOSITORY + "dists/stable/main/binary-aarch64/Packages.gz"
PREFIX = "data/data/com.termux/files/usr/"
ROOTS = ["nodejs-lts", "ca-certificates", "termux-licenses", "bash", "ripgrep"]
EXECUTABLES = {
    "node": {"source": "bin/node", "destination": "libdroy_node.so"},
    "bash": {"source": "bin/bash", "destination": "libdroy_bash.so"},
    "rg": {"source": "bin/rg", "destination": "libdroy_rg.so"},
}
PATCHELF_VERSION = "patchelf 0.19.2"
SYSTEM_LIBRARIES = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
}
REPO_ROOT = Path(__file__).resolve().parent.parent
PAYLOAD_PATHS = ("jniLibs", "assets", "headers", "runtime-manifest.json")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def fetch(url):
    with urllib.request.urlopen(url, timeout=90) as response:
        return response.read()


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")


def parse_index(raw):
    packages = {}
    for paragraph in gzip.decompress(raw).decode().split("\n\n"):
        fields = {}
        previous = None
        for line in paragraph.splitlines():
            if line.startswith(" ") and previous:
                fields[previous] += "\n" + line[1:]
            elif ": " in line:
                previous, value = line.split(": ", 1)
                fields[previous] = value
        if "Package" in fields:
            packages[fields["Package"]] = fields
    return packages


def dependencies(expression, available):
    selected = []
    for group in expression.split(","):
        if not group.strip():
            continue
        alternatives = [re.split(r"[\s(:]", x.strip())[0] for x in group.split("|")]
        name = next((x for x in alternatives if x in available), None)
        if not name:
            raise ValueError("Unresolved package dependency: " + group)
        selected.append(name)
    return selected


def update_lock(path):
    raw = fetch(INDEX)
    index = parse_index(raw)
    todo, packages = list(ROOTS), {}
    while todo:
        name = todo.pop(0)
        if name in packages:
            continue
        source = index[name]
        deps = dependencies(source.get("Depends", ""), index)
        packages[name] = {
            "name": name,
            "version": source["Version"],
            "architecture": source["Architecture"],
            "url": REPOSITORY + source["Filename"],
            "sha256": source["SHA256"],
            "size": int(source["Size"]),
            "depends": source.get("Depends", ""),
            "resolved_dependencies": deps,
            "homepage": source.get("Homepage", ""),
        }
        todo.extend(deps)
    lock = {
        "schema_version": 1,
        "architecture": "arm64-v8a",
        "roots": ROOTS,
        "index_url": INDEX,
        "index_sha256": sha256(raw),
        "patchelf_version": PATCHELF_VERSION,
        "packages": [packages[key] for key in sorted(packages)],
    }
    # Some packages supply a placeholder or template instead of their full notice.
    # Supplement from official versioned upstream files, never a moving branch.
    sources = [
        ("libicu", "LICENSE.upstream", "https://raw.githubusercontent.com/unicode-org/icu/release-"
         + packages["libicu"]["version"].split("-")[0] + "/LICENSE"),
        ("libc++", "LICENSE.llvm", "https://raw.githubusercontent.com/llvm/llvm-project/llvmorg-21.1.8/libcxx/LICENSE.TXT"),
        ("pcre2", "LICENSE.upstream", "https://raw.githubusercontent.com/PCRE2Project/pcre2/pcre2-"
         + packages["pcre2"]["version"].split("-")[0] + "/LICENCE.md"),
    ]
    lock["supplemental_notices"] = []
    for package, filename, url in sources:
        notice = fetch(url)
        if len(notice) < 100 or b"404: Not Found" in notice:
            raise ValueError("Invalid supplemental license: " + url)
        lock["supplemental_notices"].append({
            "package": package, "filename": filename, "url": url, "sha256": sha256(notice),
        })
    write_json(path, lock)
    return lock


def read_lock(path):
    lock = json.loads(path.read_text())
    if lock.get("schema_version") != 1 or lock.get("architecture") != "arm64-v8a":
        raise ValueError("Unsupported runtime lock format or architecture")
    packages = {p["name"]: p for p in lock["packages"]}
    if len(packages) != len(lock["packages"]):
        raise ValueError("Duplicate package in lock")
    for root in ROOTS:
        if root not in packages:
            raise ValueError("Missing root package: " + root)
    for package in packages.values():
        if package["architecture"] not in {"aarch64", "all"}:
            raise ValueError("Unexpected package architecture")
        if not re.fullmatch(r"[a-f0-9]{64}", package["sha256"]):
            raise ValueError("Invalid package checksum")
        if not package["url"].startswith(REPOSITORY + "pool/"):
            raise ValueError("Package URL is outside the locked Termux repository")
        if dependencies(package["depends"], packages) != package["resolved_dependencies"]:
            raise ValueError("Dependency resolution no longer matches lock")
    return lock


def ar_members(data):
    if not data.startswith(b"!<arch>\n"):
        raise ValueError("Downloaded package is not an ar archive")
    offset = 8
    while offset < len(data):
        header = data[offset:offset + 60]
        if len(header) != 60 or header[58:] != b"`\n":
            raise ValueError("Invalid ar member header")
        name = header[:16].decode("ascii").strip().rstrip("/")
        size = int(header[48:58])
        offset += 60
        payload = data[offset:offset + size]
        if len(payload) != size:
            raise ValueError("Truncated ar member")
        yield name, payload
        offset += size + size % 2


def tar_payload(data):
    members = [(name, body) for name, body in ar_members(data) if name.startswith("data.tar")]
    if len(members) != 1:
        raise ValueError("Expected one data archive per package")
    name, body = members[0]
    if name.endswith(".zst"):
        body = subprocess.run(["zstd", "-dc"], input=body, capture_output=True, check=True).stdout
    return tarfile.open(fileobj=io.BytesIO(body), mode="r:*")


def relative_source(name):
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts:
        raise ValueError("Unsafe archive path: " + name)
    clean = str(path)
    return clean[len(PREFIX):] if clean.startswith(PREFIX) else None


def unpack(package, data, work):
    root = work / package["name"]
    records = {}
    with tar_payload(data) as archive:
        for member in archive:
            relative = relative_source(member.name)
            if not relative:
                continue
            library = relative.startswith("lib/") and "/" not in relative[4:] and ".so" in relative
            document = relative.startswith("share/LICENSES/") or relative.startswith("share/doc/") and any(
                word in Path(relative).name.lower() for word in ("copyright", "license", "copying")
            )
            header = package["name"] == "nodejs-lts" and relative.startswith("include/node/")
            terminfo = package["name"] == "ncurses" and relative.startswith("share/terminfo/")
            executable = relative in {value["source"] for value in EXECUTABLES.values()}
            if not (library or document or header or terminfo or executable or relative in {"etc/tls/cert.pem", "etc/resolv.conf"}):
                continue
            record = {"package": package["name"], "source": relative}
            if member.isfile():
                destination = root / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(archive.extractfile(member).read())
                record["path"] = destination
            elif member.issym():
                target = PurePosixPath(posixpath.normpath(str(PurePosixPath(relative).parent / member.linkname)))
                if target.is_absolute() or ".." in target.parts:
                    raise ValueError("Unsupported library symlink: " + member.name)
                record["link"] = str(target)
            elif member.islnk():
                target = relative_source(member.linkname)
                if not target:
                    raise ValueError("Unsupported archive hard link")
                record["link"] = target
            else:
                continue
            records[relative] = record
    return records


def resolve_record(records, name):
    visited = set()
    while name in records:
        if name in visited:
            raise ValueError("Cyclic archive link: " + name)
        visited.add(name)
        record = records[name]
        if "path" in record:
            return record
        name = record["link"]
    raise ValueError("Missing archive file: " + name)


def patch_command(patchelf, *args):
    return subprocess.run([patchelf, *map(str, args)], check=True, capture_output=True, text=True).stdout.strip()


def validate_elf(path):
    data = path.read_bytes()
    if data[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", data, 18)[0] != 183:
        raise ValueError("Not a little-endian AArch64 ELF: " + str(path))
    program_offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    alignments = []
    for index in range(count):
        offset = program_offset + index * entry_size
        if struct.unpack_from("<I", data, offset)[0] == 1:
            alignments.append(struct.unpack_from("<Q", data, offset + 48)[0])
    return min(alignments) if alignments else 0


def android_library_name(name):
    if not name.startswith("lib") or ".so" not in name:
        raise ValueError("Unexpected dynamic library name: " + name)
    before, after = name.split(".so", 1)
    suffix = re.sub(r"[^A-Za-z0-9_]", "_", after).strip("_")
    return before + ("_" + suffix if suffix else "") + ".so"


def validate_output_directory(output):
    existing = [output / name for name in PAYLOAD_PATHS if (output / name).exists() or (output / name).is_symlink()]
    if not existing:
        return
    if any(path.is_symlink() for path in existing):
        raise ValueError("Generated payload paths must not be symbolic links")
    manifest_path = output / "runtime-manifest.json"
    if not manifest_path.is_file():
        raise ValueError("Output contains existing payload paths without a runtime manifest; use a dedicated output directory")
    manifest = json.loads(manifest_path.read_text())
    if not isinstance(manifest, dict) or any(manifest.get(key) != value for key, value in {
        "schema_version": 1, "architecture": "arm64-v8a", "executable": "libdroy_node.so",
    }.items()):
        raise ValueError("Output does not contain a recognized DroyAgent runtime manifest")


def publish_payload(staged, output, manifest):
    validate_output_directory(output)
    output.mkdir(parents=True, exist_ok=True)
    pending = Path(tempfile.mkdtemp(prefix=".droy-node-publish-", dir=output))
    replacement = pending / "new"
    previous = pending / "previous"
    replacement.mkdir()
    previous.mkdir()
    backed_up, published = [], []
    cleanup = True
    try:
        # Copy on the destination filesystem before touching the last good payload.
        for name in PAYLOAD_PATHS[:-1]:
            shutil.copytree(staged / name, replacement / name)
        write_json(replacement / "runtime-manifest.json", manifest)
        try:
            for name in PAYLOAD_PATHS:
                destination = output / name
                if destination.exists():
                    destination.replace(previous / name)
                    backed_up.append(name)
                (replacement / name).replace(destination)
                published.append(name)
        except BaseException:
            try:
                for name in reversed(published):
                    (output / name).replace(replacement / name)
                for name in reversed(backed_up):
                    (previous / name).replace(output / name)
            except BaseException as error:
                # Never remove the only remaining old files if recovery also fails.
                cleanup = False
                raise OSError("Payload recovery failed; preserved files are in " + str(pending)) from error
            raise
    finally:
        if cleanup:
            shutil.rmtree(pending)


def build(lock, output, patchelf):
    validate_output_directory(output)
    patch_version = patch_command(patchelf, "--version")
    if patch_version != lock["patchelf_version"]:
        raise ValueError("Expected " + lock["patchelf_version"] + ", found " + patch_version)
    cache = output / ".cache"
    cache.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="droy-node-") as temporary:
        work = Path(temporary)
        records = {}
        for package in lock["packages"]:
            cached = cache / package["url"].rsplit("/", 1)[1]
            if not cached.exists():
                print("Downloading " + package["name"] + " " + package["version"], flush=True)
                payload = fetch(package["url"])
                if len(payload) != package["size"] or sha256(payload) != package["sha256"]:
                    raise ValueError("Download checksum mismatch: " + package["name"])
                cached.write_bytes(payload)
            data = cached.read_bytes()
            if len(data) != package["size"] or sha256(data) != package["sha256"]:
                raise ValueError("Cached package checksum mismatch: " + str(cached))
            extracted = unpack(package, data, work)
            for source, record in extracted.items():
                if source in records:
                    raise ValueError("Duplicate package file: " + source)
                records[source] = record

        selected = {
            executable["source"]: {**resolve_record(records, executable["source"]),
                                   "destination": executable["destination"]}
            for executable in EXECUTABLES.values()
        }
        executable_sources = set(selected)
        aliases = {}
        pending = list(selected)
        while pending:
            key = pending.pop(0)
            record = selected[key]
            validate_elf(record["path"])
            needed = patch_command(patchelf, "--print-needed", record["path"]).splitlines()
            record["original_needed"] = needed
            for dependency in needed:
                if dependency in SYSTEM_LIBRARIES:
                    continue
                source = resolve_record(records, "lib/" + dependency)
                source_key = source["source"]
                if source_key not in selected:
                    selected[source_key] = {
                        **source, "destination": android_library_name(Path(source_key).name),
                    }
                    pending.append(source_key)
                aliases[dependency] = selected[source_key]["destination"]

        staged = work / "payload"
        libraries = staged / "jniLibs/arm64-v8a"
        assets = staged / "assets/node"
        libraries.mkdir(parents=True)
        assets.mkdir(parents=True)
        headers = staged / "headers/node"
        for source, record in records.items():
            if source.startswith("include/node/") and "path" in record:
                destination = headers / source[len("include/node/"):]
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(record["path"], destination)
        names = [record["destination"] for record in selected.values()]
        if len(names) != len(set(names)):
            raise ValueError("Relocated library name collision")
        files = []
        for source, record in sorted(selected.items()):
            destination = libraries / record["destination"]
            shutil.copyfile(record["path"], destination)
            destination.chmod(0o755)
            arguments = ["--set-rpath", "$ORIGIN"]
            if source not in executable_sources:
                arguments += ["--set-soname", record["destination"]]
            for dependency in record["original_needed"]:
                if dependency in aliases and dependency != aliases[dependency]:
                    arguments += ["--replace-needed", dependency, aliases[dependency]]
            patch_command(patchelf, *arguments, destination)
            needed = patch_command(patchelf, "--print-needed", destination).splitlines()
            unresolved = set(needed) - set(names) - SYSTEM_LIBRARIES
            if unresolved:
                raise ValueError("Incomplete ELF dependency closure: " + str(unresolved))
            if patch_command(patchelf, "--print-rpath", destination) != "$ORIGIN":
                raise ValueError("RPATH relocation failed")
            files.append({
                "path": "jniLibs/arm64-v8a/" + destination.name,
                "package": record["package"], "original_path": PREFIX + source,
                "original_sha256": sha256(record["path"].read_bytes()),
                "sha256": sha256(destination.read_bytes()), "size": destination.stat().st_size,
                "original_needed": record["original_needed"], "needed": needed,
                "minimum_load_alignment": validate_elf(destination),
            })
        interpreter = "/system/bin/linker64"
        for executable in EXECUTABLES.values():
            actual = patch_command(patchelf, "--print-interpreter", libraries / executable["destination"])
            if actual != interpreter:
                raise ValueError("Unexpected Android ELF interpreter for " + executable["source"] + ": " + actual)

        terminfo = []
        for source, record in sorted(records.items()):
            if not source.startswith("share/terminfo/"):
                continue
            resolved = resolve_record(records, source)
            destination = assets / source[len("share/"):]
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(resolved["path"], destination)
            terminfo.append({"path": str(destination.relative_to(staged)), "sha256": sha256(destination.read_bytes())})
        if not (assets / "terminfo/x/xterm-256color").is_file():
            raise ValueError("Missing xterm-256color terminal description")

        certificate = resolve_record(records, "etc/tls/cert.pem")
        shutil.copyfile(certificate["path"], assets / "ca-certificates.crt")
        if b"-----BEGIN CERTIFICATE-----" not in (assets / "ca-certificates.crt").read_bytes():
            raise ValueError("CA certificate bundle is not PEM")
        # Preserve package-provided redistribution notices alongside the binaries.
        licenses = []
        selected_packages = {record["package"] for record in selected.values()} | {certificate["package"]}
        for source, record in sorted(records.items()):
            if source.startswith("share/doc/") and record["package"] in selected_packages:
                resolved = resolve_record(records, source)
                notice = resolved["path"].read_bytes()
                if notice.strip() == b"404: Not Found":
                    continue
                destination = assets / "licenses" / record["package"] / Path(source).name
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(resolved["path"], destination)
                licenses.append({"package": record["package"], "path": str(destination.relative_to(staged)),
                                 "sha256": sha256(destination.read_bytes())})
        for source in lock["supplemental_notices"]:
            package, filename = source["package"], source["filename"]
            if package not in selected_packages or Path(filename).name != filename:
                raise ValueError("Invalid supplemental license destination")
            cached = cache / (package + "-" + filename)
            if not cached.exists():
                notice = fetch(source["url"])
                if sha256(notice) != source["sha256"]:
                    raise ValueError("Supplemental license checksum mismatch")
                cached.write_bytes(notice)
            notice = cached.read_bytes()
            if sha256(notice) != source["sha256"]:
                raise ValueError("Cached supplemental license checksum mismatch")
            destination = assets / "licenses" / package / filename
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(notice)
            licenses.append({"package": package, "path": str(destination.relative_to(staged)),
                             "url": source["url"], "sha256": source["sha256"]})
        missing_notices = sorted(selected_packages - {entry["package"] for entry in licenses})
        if missing_notices:
            raise ValueError("Missing upstream package copyright notices: " + ", ".join(missing_notices))
        packages = [{key: value for key, value in package.items()} for package in lock["packages"]]
        node_version = next(package["version"] for package in packages if package["name"] == "nodejs-lts")
        manifest = {
            "schema_version": 1, "architecture": "arm64-v8a", "node_version": node_version,
            "executable": "libdroy_node.so", "interpreter": interpreter,
            "executables": {name: value["destination"] for name, value in EXECUTABLES.items()},
            "patchelf_version": patch_version,
            "system_libraries": sorted(SYSTEM_LIBRARIES), "packages": packages,
            "files": files, "licenses": licenses, "terminfo": terminfo,
            "certificate": {"path": "assets/node/ca-certificates.crt", "sha256": sha256((assets / "ca-certificates.crt").read_bytes())},
            "environment": {
                "LD_LIBRARY_PATH": "<applicationInfo.nativeLibraryDir>",
                "NODE_EXTRA_CA_CERTS": "<filesDir>/runtime/node/ca-certificates.crt",
                "TERMINFO": "<filesDir>/runtime-support/terminfo",
                "DROY_BASH_PATH": "<filesDir>/bin/bash",
                "DROY_RG_PATH": "<filesDir>/bin/rg",
                "HOME": "<filesDir>/home", "TMPDIR": "<cacheDir>/runtime",
            },
        }
        write_json(assets / "runtime-manifest.json", manifest)
        publish_payload(staged, output, manifest)
        print(json.dumps({"output": str(output), "node_version": node_version, "libraries": len(files),
                          "payload_bytes": sum(item["size"] for item in files), "elf_dependency_closure": "complete"}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True, help="Local generated payload/cache directory")
    parser.add_argument("--lock", type=Path, default=REPO_ROOT / "runtime/node-runtime.lock.json")
    parser.add_argument("--update-lock", action="store_true", help="Explicitly resolve current official package index and update lock")
    parser.add_argument("--patchelf", default=shutil.which("patchelf") or "/opt/homebrew/bin/patchelf")
    args = parser.parse_args()
    output = args.output.expanduser().resolve()
    if output == REPO_ROOT or REPO_ROOT in output.parents:
        parser.error("Generated native payload must be outside the source repository")
    if args.update_lock:
        update_lock(args.lock)
    elif not args.lock.exists():
        parser.error("Runtime lock is absent; initialize it explicitly with --update-lock")
    lock = read_lock(args.lock)
    build(lock, output, args.patchelf)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError, KeyError) as error:
        print("Android Node preparation failed: " + str(error), file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError) and error.stderr:
            print(error.stderr, file=sys.stderr)
        sys.exit(1)
