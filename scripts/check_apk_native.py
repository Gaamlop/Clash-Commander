"""Check the actual APK's ARM64 JNI dependency versions before distributing it.

This catches Android linker failures that Linux JVM inference cannot reproduce.
It does not replace initialization/recognition tests on an Android device.
Requires binutils readelf; does not load or execute untrusted native libraries.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile


REQUIRED_LIBS = {
    "libonnxruntime.so", "libonnxruntime4j_jni.so", "libsherpa-onnx-jni.so",
    "libsherpa-onnx-c-api.so", "libsherpa-onnx-cxx-api.so", "libpytorch_jni_lite.so",
}
SPEECH_ASSETS = (
    "silero_vad.onnx",
    "sherpa-onnx-whisper-tiny/tiny-encoder.int8.onnx",
    "sherpa-onnx-whisper-tiny/tiny-decoder.int8.onnx",
    "sherpa-onnx-whisper-tiny/tiny-tokens.txt",
    "sherpa-onnx-zipformer-en-2023-04-01/encoder-epoch-99-avg-1.int8.onnx",
    "sherpa-onnx-zipformer-en-2023-04-01/decoder-epoch-99-avg-1.int8.onnx",
    "sherpa-onnx-zipformer-en-2023-04-01/joiner-epoch-99-avg-1.int8.onnx",
    "sherpa-onnx-zipformer-en-2023-04-01/tokens.txt",
    "sherpa-onnx-zipformer-en-2023-04-01/bpe.vocab",
    "hotwords.txt",
)


def parse_symbols(text):
    imports, exports = set(), set()
    for line in text.splitlines():
        fields = line.split()
        if len(fields) < 8 or not fields[0].rstrip(":").isdigit():
            continue
        _, _, _, _, binding, visibility, index, name, *_ = fields
        name = name.replace("@@", "@")
        if index == "UND" and binding == "GLOBAL":
            imports.add(name)
        elif index != "UND" and binding in ("GLOBAL", "WEAK") and visibility in ("DEFAULT", "PROTECTED"):
            exports.add(name)
    return imports, exports


def parse_version_needs(text):
    needs, library, in_needs = {}, None, False
    for line in text.splitlines():
        if line.startswith("Version "):
            in_needs = line.startswith("Version needs section")
        if not in_needs:
            continue
        match = re.search(r"File: (\S+)", line)
        if match:
            library = match[1]
        match = re.search(r"Name: (\S+)", line)
        if match and library:
            needs[match[1]] = library
    return needs


def check_links(libraries):
    errors, checked = [], []
    for name, info in libraries.items():
        for symbol in sorted(info["imports"]):
            if "@" not in symbol:
                continue
            version = symbol.rsplit("@", 1)[1]
            provider = info["needs"].get(version)
            if provider not in libraries:
                continue  # Android platform libraries are supplied by the device.
            if symbol not in libraries[provider]["exports"]:
                errors.append(f"{name}: missing {symbol} in packaged {provider}")
            else:
                checked.append(f"{name}: {symbol} -> {provider}")
    return errors, checked


def verify_apk(apk):
    libraries = {}
    with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory(prefix="clash-elf-") as temp:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate entries in APK")
        for name in names:
            if not (name.startswith("lib/") and name.endswith(".so")):
                continue
            if not name.startswith("lib/arm64-v8a/"):
                raise ValueError(f"Unexpected ABI: {name}")
            path = Path(temp) / "library.so"
            path.write_bytes(archive.read(name))
            header = path.read_bytes()[:20]
            if header[:6] != b"\x7fELF\x02\x01" or int.from_bytes(header[18:20], "little") != 183:
                raise ValueError(f"Not ARM64 ELF: {name}")
            env = dict(os.environ, LC_ALL="C")
            symbols = subprocess.check_output(["readelf", "--dyn-syms", "-W", str(path)], text=True, env=env)
            versions = subprocess.check_output(["readelf", "-VW", str(path)], text=True, env=env)
            imports, exports = parse_symbols(symbols)
            libraries[Path(name).name] = {"imports": imports, "exports": exports,
                                          "needs": parse_version_needs(versions)}
        missing = REQUIRED_LIBS - libraries.keys()
        if missing:
            raise ValueError(f"Missing native libraries: {sorted(missing)}")
        for asset in SPEECH_ASSETS:
            if archive.getinfo("assets/" + asset).file_size == 0:
                raise ValueError(f"Empty speech asset: {asset}")
    errors, checked = check_links(libraries)
    if errors:
        raise ValueError("\n".join(errors))
    # Ensure both JNI clients actually participated, rather than passing vacuously.
    for client in ("libsherpa-onnx-jni.so", "libonnxruntime4j_jni.so"):
        if not any(line.startswith(client + ": OrtGetApiBase@") for line in checked):
            raise ValueError(f"No versioned ONNX link checked for {client}")
    return {"abi": "arm64-v8a", "libraries": sorted(libraries),
            "verified_versioned_links": checked, "speech_assets": list(SPEECH_ASSETS),
            "android_runtime_tested": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    report = verify_apk(args.apk)
    rendered = json.dumps(report, indent=2) + "\n"
    if args.report:
        args.report.write_text(rendered)
    print(rendered)
