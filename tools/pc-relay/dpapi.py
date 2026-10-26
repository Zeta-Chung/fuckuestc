"""Windows DPAPI 封装：用当前 Windows 用户的凭据加密/解密本地文件内容。

被加密的内容只有同一个 Windows 账户在同一个账户配置下能解开：拷到别的机器、
别的用户下会解密失败（这正是我们想要的）。只依赖标准库 ctypes。
"""
from __future__ import annotations

import ctypes
from ctypes import wintypes

CRYPTPROTECT_UI_FORBIDDEN = 0x01
_ENTROPY = b"uestc-checkin-relay/wx-credentials/v1"


class _DataBlob(ctypes.Structure):
    _fields_ = [
        ("cbData", wintypes.DWORD),
        ("pbData", ctypes.POINTER(ctypes.c_char)),
    ]


def _make_blob(data: bytes) -> tuple[_DataBlob, ctypes.Array]:
    buffer = ctypes.create_string_buffer(data, len(data))
    blob = _DataBlob(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_char)))
    return blob, buffer


_crypt32 = ctypes.WinDLL("crypt32", use_last_error=True)
_kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

_crypt32.CryptProtectData.argtypes = [
    ctypes.POINTER(_DataBlob),
    wintypes.LPCWSTR,
    ctypes.POINTER(_DataBlob),
    ctypes.c_void_p,
    ctypes.c_void_p,
    wintypes.DWORD,
    ctypes.POINTER(_DataBlob),
]
_crypt32.CryptProtectData.restype = wintypes.BOOL
_crypt32.CryptUnprotectData.argtypes = [
    ctypes.POINTER(_DataBlob),
    ctypes.POINTER(wintypes.LPWSTR),
    ctypes.POINTER(_DataBlob),
    ctypes.c_void_p,
    ctypes.c_void_p,
    wintypes.DWORD,
    ctypes.POINTER(_DataBlob),
]
_crypt32.CryptUnprotectData.restype = wintypes.BOOL
_kernel32.LocalFree.argtypes = [ctypes.c_void_p]
_kernel32.LocalFree.restype = ctypes.c_void_p


def _run(protect: bool, data: bytes) -> bytes:
    blob_in, keep_alive = _make_blob(data)
    entropy, keep_entropy = _make_blob(_ENTROPY)
    blob_out = _DataBlob()
    if protect:
        ok = _crypt32.CryptProtectData(
            ctypes.byref(blob_in), "UESTC checkin relay",
            ctypes.byref(entropy), None, None,
            CRYPTPROTECT_UI_FORBIDDEN, ctypes.byref(blob_out),
        )
    else:
        ok = _crypt32.CryptUnprotectData(
            ctypes.byref(blob_in), None,
            ctypes.byref(entropy), None, None,
            CRYPTPROTECT_UI_FORBIDDEN, ctypes.byref(blob_out),
        )
    _ = keep_alive, keep_entropy
    if not ok:
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        return ctypes.string_at(blob_out.pbData, blob_out.cbData)
    finally:
        _kernel32.LocalFree(ctypes.cast(blob_out.pbData, ctypes.c_void_p))


def protect(data: bytes) -> bytes:
    """加密为只有当前 Windows 用户能解开的字节串。"""
    return _run(True, data)


def unprotect(data: bytes) -> bytes:
    """解密 protect() 产生的字节串；换用户/换机器会抛 OSError。"""
    return _run(False, data)