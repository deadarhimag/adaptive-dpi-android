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


def replace_at(text, before, after, count=1):
    if text.count(before) != count:
        raise SystemExit("Не найдено ожидаемое место для наблюдения за TUN в HEV.")
    return text.replace(before, after)


def write_generated(name, text):
    path = CACHE / name
    if not path.exists() or path.read_text(encoding="utf-8") != text:
        path.write_text(text, encoding="utf-8", newline="\n")


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
    text = '#include "transport-diagnostics.h"\n' + replace_at(
        text, "    s = hev_tunnel_write (tun_fd, p);\n    if (s <= 0) {",
        "    s = hev_tunnel_write (tun_fd, p);\n    if (s <= 0) {\n"
        "        adaptive_transport_fault (ADAPTIVE_TUN_WRITE, (int)s, s < 0 ? errno : 0);",
    )
    write_generated("hev-socks5-tunnel-atomic.c", text)

    # -1 — errno от read/readv. -2 возвращает yielder при остановке цикла.
    text = (HEV / "src" / "hev-tunnel-linux.c").read_text(encoding="utf-8")
    for call in (
        "    s = hev_task_io_read (fd, buf->payload, buf->len, yielder, yielder_data);",
        "    s = hev_task_io_readv (fd, iov, iovc, yielder, yielder_data);",
    ):
        text = replace_at(text, call, call + "\n    if (s <= 0)\n"
                          "        adaptive_transport_fault (ADAPTIVE_TUN_READ, (int)s, s == -1 ? errno : 0);")
    text = replace_at(text, "    if (!buf)\n        return NULL;",
                      "    if (!buf) {\n        adaptive_transport_fault (ADAPTIVE_TUN_BUFFER, -1, 0);\n"
                      "        return NULL;\n    }", count=2)
    write_generated("hev-tunnel-linux-diagnostic.c", '#include "transport-diagnostics.h"\n' + text)
    print(f"HEV и четыре компонента проверены, ревизия {REVISION}.")


if __name__ == "__main__":
    main()
