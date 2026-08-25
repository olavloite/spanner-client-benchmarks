import math
import random
from typing import Any, Callable, Optional, Union

# Standard Zipfian skew parameter (0.99) defined in the YCSB specification.
DEFAULT_ZIPFIAN_CONSTANT = 0.99
DEFAULT_ITEM_COUNT = 10000000000
ZETAN_1K = 7.728953217284738
ZETAN_10B = 26.46902820178302
FNV_OFFSET_BASIS_64 = 0xCBF29CE484222325
FNV_PRIME_64 = 0x100000001B3


class ZipfianGenerator:
    """
    Generates random integers following a Zipfian distribution over [min_val, max_val].
    """

    def __init__(
        self,
        min_val: int,
        max_val: int,
        zipfian_constant: float = DEFAULT_ZIPFIAN_CONSTANT,
        zetan: Optional[float] = None,
    ):
        items = max_val - min_val + 1
        if items < 1:
            items = 1
        if zetan is None:
            zetan = ZipfianGenerator.compute_zeta(items, zipfian_constant)

        zeta2theta = 1.0 + math.pow(0.5, zipfian_constant)
        alpha = 1.0 / (1.0 - zipfian_constant)
        denom = 1.0 - zeta2theta / zetan
        if abs(denom) < 1e-12:
            eta = 1.0
        else:
            eta = (1.0 - math.pow(2.0 / float(items), 1.0 - zipfian_constant)) / denom

        self.items = items
        self.base = min_val
        self.zipfian_constant = zipfian_constant
        self.alpha = alpha
        self.zetan = zetan
        self.eta = eta
        self.zeta2theta = zeta2theta

    @staticmethod
    def compute_zeta(items: int, zipfian_constant: float) -> float:
        """
        Computes or approximates zeta(n, theta) = sum_{i=1}^n 1 / (i^theta).
        Uses exact summation up to 1000 items and Euler-Maclaurin integration for n > 1000.
        """
        if items <= 0:
            return 0.0

        if abs(zipfian_constant - DEFAULT_ZIPFIAN_CONSTANT) < 1e-9:
            if items == 1000:
                return ZETAN_1K
            if items == DEFAULT_ITEM_COUNT:
                return ZETAN_10B
            if items > 1000:
                return ZETAN_1K + (
                    math.pow(float(items), 1.0 - zipfian_constant)
                    - math.pow(1000.0, 1.0 - zipfian_constant)
                ) / (1.0 - zipfian_constant)
            return sum(
                1.0 / math.pow(float(i), zipfian_constant) for i in range(1, items + 1)
            )

        n0 = min(items, 1000)
        sum_zeta = sum(
            1.0 / math.pow(float(i), zipfian_constant) for i in range(1, n0 + 1)
        )
        if items > n0:
            sum_zeta += (
                math.pow(float(items), 1.0 - zipfian_constant)
                - math.pow(float(n0), 1.0 - zipfian_constant)
            ) / (1.0 - zipfian_constant)
        return sum_zeta

    def next_value(self) -> int:
        """Generates the next integer in the Zipfian distribution."""
        u = random.random()
        uz = u * self.zetan

        if uz < 1.0:
            return self.base

        if uz < self.zeta2theta:
            return self.base + 1

        val = int(
            float(self.items) * math.pow(self.eta * u - self.eta + 1.0, self.alpha)
        )
        val = max(0, min(self.items - 1, val))
        return self.base + val


class ScrambledZipfianGenerator:
    """
    Generates scrambled Zipfian distributed integers over [min_val, max_val].
    Scrambles the sequence across the 10-billion item space by hashing each Zipfian item with 64-bit FNV-1a.
    """

    def __init__(
        self,
        min_val: int,
        max_val: int,
        zipfian_constant: float = DEFAULT_ZIPFIAN_CONSTANT,
    ):
        self.min_val = min_val
        self.max_val = max_val
        self.item_count = max_val - min_val + 1
        if self.item_count < 1:
            self.item_count = 1

        if abs(zipfian_constant - DEFAULT_ZIPFIAN_CONSTANT) < 1e-9:
            self.generator = ZipfianGenerator(
                0, DEFAULT_ITEM_COUNT - 1, DEFAULT_ZIPFIAN_CONSTANT, zetan=ZETAN_10B
            )
        else:
            self.generator = ZipfianGenerator(
                0, DEFAULT_ITEM_COUNT - 1, zipfian_constant
            )

    @staticmethod
    def fnv_hash_64(val: int) -> int:
        """
        Performs 64-bit FNV-1a non-cryptographic hashing matching upstream YCSB and Java/Go/Rust implementations.
        Converts negative signed 64-bit integers to positive via two's complement absolute value.
        """
        h = FNV_OFFSET_BASIS_64
        for i in range(8):
            byte = (val >> (i * 8)) & 0xFF
            h = ((h ^ byte) * FNV_PRIME_64) & 0xFFFFFFFFFFFFFFFF

        signed_h = h if h < 0x8000000000000000 else h - 0x10000000000000000
        if signed_h < 0:
            return 0 if signed_h == -0x8000000000000000 else -signed_h
        return signed_h

    def next_value(self) -> int:
        """Generates the next scrambled Zipfian integer in [min_val, max_val]."""
        raw = self.generator.next_value()
        scrambled = ScrambledZipfianGenerator.fnv_hash_64(raw) % self.item_count
        return self.min_val + scrambled


class SkewedLatestGenerator:
    """
    Generates keys with a Zipfian skew towards the most recently inserted records.
    Used in YCSB Workload D ("Read Latest").
    Caches zeta and eta parameters across reads since the basis sequence only increments on insert operations.
    """

    class _ZipfianParams:
        def __init__(
            self, item_count: int, zipfian_constant: float, zeta2_theta: float
        ):
            self.item_count = item_count
            self.zetan = ZipfianGenerator.compute_zeta(item_count, zipfian_constant)
            denom = 1.0 - zeta2_theta / self.zetan
            if abs(denom) < 1e-12:
                self.eta = 1.0
            else:
                self.eta = (
                    1.0 - math.pow(2.0 / float(item_count), 1.0 - zipfian_constant)
                ) / denom

    def __init__(
        self,
        basis: Union[int, Callable[[], int], Any],
        zipfian_constant: float = DEFAULT_ZIPFIAN_CONSTANT,
    ):
        self.basis = basis
        self.zipfian_constant = zipfian_constant
        self.zeta2_theta = 1.0 + math.pow(0.5, zipfian_constant)
        self.alpha = 1.0 / (1.0 - zipfian_constant)
        initial_max = self._get_max()
        self._cached_params = self._ZipfianParams(
            max(2, initial_max), zipfian_constant, self.zeta2_theta
        )

    def _get_max(self) -> int:
        if callable(self.basis):
            return self.basis()
        elif hasattr(self.basis, "value"):
            return self.basis.value
        elif hasattr(self.basis, "get"):
            return self.basis.get()
        return int(self.basis)

    def next_value(self) -> int:
        """Generates the next skewed latest integer."""
        max_item = self._get_max()
        if max_item <= 1:
            return 0

        params = self._cached_params
        if params is None or params.item_count != max_item:
            params = self._ZipfianParams(
                max_item, self.zipfian_constant, self.zeta2_theta
            )
            self._cached_params = params

        u = random.random()
        uz = u * params.zetan

        if uz < 1.0:
            return max_item - 1
        if uz < 1.0 + math.pow(0.5, self.zipfian_constant):
            return max(0, max_item - 2)

        offset = int(
            float(max_item) * math.pow(params.eta * u - params.eta + 1.0, self.alpha)
        )
        key = max_item - 1 - offset
        return max(0, key)


# Module-level aliases for clean backwards compatibility
compute_zeta = ZipfianGenerator.compute_zeta
fnv_hash_64 = ScrambledZipfianGenerator.fnv_hash_64
