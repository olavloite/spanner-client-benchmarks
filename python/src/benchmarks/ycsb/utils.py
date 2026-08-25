import random

ASCII_POOL_SIZE = 16384
_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
_ASCII_POOL = "".join(_CHARS[i % len(_CHARS)] for i in range(ASCII_POOL_SIZE))


def build_key_name(key_number: int, zero_padding: int) -> str:
    """
    Formats an integer key into the standard YCSB zero-padded primary key string (e.g. user000000000001).
    If zero_padding is <= 0 or the string representation length >= zero_padding, the key is unpadded (e.g. user42).
    """
    key_str = str(key_number)
    if zero_padding <= 0 or len(key_str) >= zero_padding:
        return f"user{key_str}"
    return f"user{key_str.zfill(zero_padding)}"


def generate_random_string(length: int) -> str:
    """
    Generates an ASCII printable alphanumeric string of the specified length in O(1) time
    by slicing into a precomputed 16 KB static ASCII character pool.
    """
    if length <= 0:
        return ""
    if length <= ASCII_POOL_SIZE:
        max_offset = ASCII_POOL_SIZE - length
        offset = random.randint(0, max_offset) if max_offset > 0 else 0
        return _ASCII_POOL[offset : offset + length]

    chunks = []
    offset = 0
    while offset < length:
        chunk_size = min(length - offset, ASCII_POOL_SIZE)
        max_offset = ASCII_POOL_SIZE - chunk_size
        pool_offset = random.randint(0, max_offset) if max_offset > 0 else 0
        chunks.append(_ASCII_POOL[pool_offset : pool_offset + chunk_size])
        offset += chunk_size
    return "".join(chunks)
