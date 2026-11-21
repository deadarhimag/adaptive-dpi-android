"""Получает зафиксированные исходники HEV и готовит файлы Android-сборки."""

from pathlib import Path
import shutil
import subprocess


ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / ".native"
HEV = CACHE / "hev"
REVISION = "fc3f90040b8d4b537341c96538669a9b2e35d514"
ORIGIN = "https://github.com/heiher/hev-socks5-tunnel.git"
COMPONENTS = {
    ".": ("hev-socks5-tunnel", REVISION),
    "src/core": ("hev-socks5-core", "f8c08a64482c22c48a971bde22ce47c113b4d7af"),
    "third-part/hev-task-system": ("hev-task-system", "ded55c412b3951bc7decb5cb3b9135701e762998"),
    "third-part/lwip": ("lwip", "8115cd6150ab32500db3d4d8bd8ad7ec6648534c"),
    "third-part/yaml": ("yaml", "0c82efd10dc67b9e7f159d2c7f5d1dc3c1b5bc11"),
}


def git(path, *arguments):
    return subprocess.check_output(["git", "-C", str(path), *arguments], text=True).strip()


def main():
    HEV.mkdir(parents=True, exist_ok=True)
    if not (HEV / ".git").exists():
        git(HEV, "init", "--quiet")
        git(HEV, "remote", "add", "origin", ORIGIN)
    if git(HEV, "remote", "get-url", "origin") != ORIGIN:
        raise SystemExit("Неожиданный origin у HEV.")
    if not (HEV / "Android.mk").exists():
        git(HEV, "fetch", "--depth=1", "origin", REVISION)
        git(HEV, "checkout", "--detach", REVISION)
    if git(HEV, "rev-parse", "HEAD") != REVISION:
        raise SystemExit("Кэш HEV содержит другую ревизию.")
    git(HEV, "diff", "--exit-code", "HEAD", "--", ".gitmodules")
    git(HEV, "submodule", "sync")
    git(HEV, "submodule", "update", "--init", "--checkout", "--depth=1")

    licenses = CACHE / "assets" / "native-licenses"
    licenses.mkdir(parents=True, exist_ok=True)
    for relative, (name, revision) in COMPONENTS.items():
        source = HEV / relative
        if git(source, "rev-parse", "HEAD") != revision or git(source, "status", "--porcelain"):
            raise SystemExit(f"Неожиданные изменения в {name}.")
        shutil.copyfile(source / "LICENSE", licenses / f"{name}.txt")

    # JNI читает счётчики одновременно с обновлением в рабочем цикле HEV.
    native = HEV / "src" / "hev-socks5-tunnel.c"
    text = native.read_text(encoding="utf-8")
    for counter in ("tx_packets", "rx_packets", "tx_bytes", "rx_bytes"):
        declaration = f"static size_t stat_{counter};"
        if text.count(declaration) != 1:
            raise SystemExit("Расположение счётчиков HEV изменилось.")
        text = text.replace(declaration, f"static _Atomic size_t stat_{counter};")
    generated = CACHE / "hev-socks5-tunnel-atomic.c"
    if not generated.exists() or generated.read_text(encoding="utf-8") != text:
        generated.write_text(text, encoding="utf-8", newline="\n")
    print(f"HEV и четыре компонента проверены, ревизия {REVISION}.")


if __name__ == "__main__":
    main()
