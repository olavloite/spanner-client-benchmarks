const ASCII_POOL_SIZE = 16384;
const CHARS = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
let asciiPool = '';
for (let i = 0; i < ASCII_POOL_SIZE; i++) {
  asciiPool += CHARS[i % CHARS.length];
}

/**
 * Formats an integer key into the standard YCSB zero-padded primary key string (e.g. user000000000001).
 */
export function buildKeyName(
  keyNumber: number | bigint,
  zeroPadding: number,
): string {
  const keyStr = keyNumber.toString();
  if (zeroPadding <= 0 || keyStr.length >= zeroPadding) {
    return `user${keyStr}`;
  }
  const paddingZeros = '0'.repeat(zeroPadding - keyStr.length);
  return `user${paddingZeros}${keyStr}`;
}

/**
 * Generates an ASCII printable alphanumeric string of the specified length in O(1) time
 * by slicing into a precomputed 16 KB static ASCII character pool.
 */
export function generateRandomString(length: number): string {
  if (length <= 0) {
    return '';
  }
  if (length <= ASCII_POOL_SIZE) {
    const maxOffset = ASCII_POOL_SIZE - length;
    const offset =
      maxOffset > 0 ? Math.floor(Math.random() * (maxOffset + 1)) : 0;
    return asciiPool.substring(offset, offset + length);
  }

  let result = '';
  let offset = 0;
  while (offset < length) {
    const chunkSize = Math.min(ASCII_POOL_SIZE, length - offset);
    const maxOffset = ASCII_POOL_SIZE - chunkSize;
    const poolOffset =
      maxOffset > 0 ? Math.floor(Math.random() * (maxOffset + 1)) : 0;
    result += asciiPool.substring(poolOffset, poolOffset + chunkSize);
    offset += chunkSize;
  }
  return result;
}

/**
 * Builds a full YCSB record object with 'id' and all 'fieldN' properties populated with random strings.
 */
export function buildRow(
  key: string,
  fieldCount: number,
  fieldLength: number,
): Record<string, string> {
  const row: Record<string, string> = {id: key};
  for (let f = 0; f < fieldCount; f++) {
    row[`field${f}`] = generateRandomString(fieldLength);
  }
  return row;
}
