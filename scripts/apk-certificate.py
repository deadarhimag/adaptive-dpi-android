"""Отпечаток сертификата из APK Signing Block v2/v3 для сравнения на Windows."""

import argparse
import hashlib
import struct
from pathlib import Path


def record(data):
    """Прочитать одно поле с 32-битной длиной из блока подписи."""
    if len(data) < 4:
        raise ValueError("Повреждён блок подписи APK")
    size = struct.unpack_from("<I", data)[0]
    if size > len(data) - 4:
        raise ValueError("Некорректная длина поля подписи APK")
    return data[4:4 + size], data[4 + size:]


def signing_block(path):
    with path.open("rb") as apk:
        apk.seek(0, 2)
        tail_start = max(0, apk.tell() - 22 - 65_535)
        apk.seek(tail_start)
        tail = apk.read()
        end = tail.rfind(b"PK\x05\x06")
        while end >= 0:
            if len(tail) - end >= 22 and len(tail) - end == 22 + struct.unpack_from("<H", tail, end + 20)[0]:
                break
            end = tail.rfind(b"PK\x05\x06", 0, end)
        if end < 0:
            raise ValueError("Не найден конец ZIP APK")
        central_size, central = struct.unpack_from("<II", tail, end + 12)
        if central < 32 or central + central_size != tail_start + end:
            raise ValueError("Некорректное оглавление ZIP APK")
        apk.seek(central - 24)
        footer = apk.read(24)
        size = struct.unpack_from("<Q", footer)[0]
        if footer[8:] != b"APK Sig Block 42" or not 24 <= size <= min(central - 8, 16 << 20):
            raise ValueError("Не найден поддерживаемый блок подписи APK")
        apk.seek(central - size - 8)
        block = apk.read(size + 8)
        if struct.unpack_from("<Q", block)[0] != size:
            raise ValueError("Разные длины блока подписи APK")
        return memoryview(block)[8:-24]


def certificate_sha256(path):
    # Android проверяет установленный APK, а apksigner проверяет файлы CI.
    # Здесь читаем только сертификат, чтобы сравнить их перед установкой.
    block = signing_block(path)
    hashes = set()
    while block:
        if len(block) < 12:
            raise ValueError("Повреждён блок подписи APK")
        size, scheme = struct.unpack_from("<QI", block)
        if not 4 <= size <= len(block) - 8:
            raise ValueError("Повреждена запись подписи APK")
        value, block = block[12:8 + size], block[8 + size:]
        if scheme not in (0x7109871A, 0xF05368C0, 0x1B93AD61):  # v2, v3, v3.1
            continue
        signers, unused = record(value)
        signer, other_signers = record(signers)
        if unused or other_signers:
            raise ValueError("Ожидалась подпись APK одним ключом")
        signed, _ = record(signer)
        _, after_digests = record(signed)
        certificates, _ = record(after_digests)
        certificate, _ = record(certificates)
        if len(certificate) == 0 or certificate[0] != 0x30:
            raise ValueError("Не найден сертификат X.509")
        hashes.add(hashlib.sha256(certificate).hexdigest())
    if len(hashes) != 1:
        raise ValueError("Ожидался один сертификат подписи APK v2/v3")
    return hashes.pop()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    try:
        print(certificate_sha256(args.apk))
    except (OSError, ValueError, struct.error) as error:
        parser.exit(1, f"{error}\n")
