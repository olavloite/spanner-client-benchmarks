import random
from enum import Enum


class Operation(str, Enum):
    READ = "READ"
    UPDATE = "UPDATE"
    INSERT = "INSERT"
    SCAN = "SCAN"
    READ_MODIFY_WRITE = "RMW"


class Workload(str, Enum):
    A = "A"
    B = "B"
    C = "C"
    D = "D"
    E = "E"
    F = "F"

    def next_operation(self) -> Operation:
        """
        Selects an operation according to standard YCSB workload proportions.
        - Workload A: 50% READ, 50% UPDATE
        - Workload B: 95% READ, 5% UPDATE
        - Workload C: 100% READ
        - Workload D: 95% READ, 5% INSERT
        - Workload E: 95% SCAN, 5% INSERT
        - Workload F: 50% READ, 50% READ_MODIFY_WRITE
        """
        p = random.random()
        if self == Workload.A:
            return Operation.READ if p < 0.50 else Operation.UPDATE
        elif self == Workload.B:
            return Operation.READ if p < 0.95 else Operation.UPDATE
        elif self == Workload.C:
            return Operation.READ
        elif self == Workload.D:
            return Operation.READ if p < 0.95 else Operation.INSERT
        elif self == Workload.E:
            return Operation.SCAN if p < 0.95 else Operation.INSERT
        elif self == Workload.F:
            return Operation.READ if p < 0.50 else Operation.READ_MODIFY_WRITE
        raise ValueError(f"unsupported workload '{self}'")


class KeyDistribution(str, Enum):
    SCRAMBLED_ZIPFIAN = "scrambled-zipfian"
    ZIPFIAN = "zipfian"
    UNIFORM = "uniform"


def parse_workload(s: str) -> Workload:
    """Parses a case-insensitive string into a Workload enum (e.g. 'A', 'b')."""
    cleaned = s.strip().upper()
    try:
        return Workload(cleaned)
    except ValueError:
        raise ValueError(
            f"unknown YCSB workload '{s}' (expected A, B, C, D, E, or F)"
        ) from None


def parse_distribution(s: str) -> KeyDistribution:
    """Parses a case-insensitive string into a KeyDistribution enum."""
    cleaned = s.strip().lower().replace("_", "-")
    if cleaned in ("scrambled-zipfian", "scrambledzipfian"):
        return KeyDistribution.SCRAMBLED_ZIPFIAN
    elif cleaned == "zipfian":
        return KeyDistribution.ZIPFIAN
    elif cleaned == "uniform":
        return KeyDistribution.UNIFORM
    else:
        raise ValueError(
            f"unknown key distribution '{s}' (expected scrambled-zipfian, zipfian, or uniform)"
        )
