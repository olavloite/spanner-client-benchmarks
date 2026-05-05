import re
from typing import Optional

def parse_duration(duration_str: Optional[str]) -> Optional[float]:
    """
    Parses a human-readable duration string (e.g., "30s", "5m", "2h", "inf", "infinite")
    into float seconds. Returns None for infinite durations.
    """
    if not duration_str:
        return None

    duration_str_lower = duration_str.strip().lower()
    if duration_str_lower in ("inf", "infinite"):
        return None

    match = re.match(r"^(\d+)(s|m|h)$", duration_str_lower)
    if not match:
        # If it's just digits, default to seconds (matching Java and Node behavior)
        if duration_str_lower.isdigit():
            return float(duration_str_lower)
        return None

    value = int(match.group(1))
    unit = match.group(2)

    if unit == "s":
        return float(value)
    elif unit == "m":
        return float(value * 60)
    elif unit == "h":
        return float(value * 3600)

    return None
