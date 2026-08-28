export interface NumberGenerator {
  nextLong(): bigint;
  nextInt(): number;
}

export const DEFAULT_ZIPFIAN_CONSTANT = 0.99;
export const DEFAULT_ITEM_COUNT = 10000000000n;
export const ZETAN_1K = 7.728953217284738;
export const ZETAN_10B = 26.46902820178302;
export const FNV_OFFSET_BASIS_64 = 0xcbf29ce484222325n;
export const FNV_PRIME_64 = 0x100000001b3n;

/**
 * Generates random integers following a Zipfian distribution over [base, base + items - 1].
 */
export class ZipfianGenerator implements NumberGenerator {
  private items: bigint;
  private base: bigint;
  private zipfianConstant: number;
  private alpha: number;
  private zetan: number;
  private eta: number;
  private zeta2theta: number;

  constructor(
    min: number | bigint,
    max: number | bigint,
    zipfianConstant: number = DEFAULT_ZIPFIAN_CONSTANT,
    zetan?: number,
  ) {
    const minBig = BigInt(min);
    const maxBig = BigInt(max);
    let items = maxBig - minBig + 1n;
    if (items < 1n) {
      items = 1n;
    }

    this.items = items;
    this.base = minBig;
    this.zipfianConstant = zipfianConstant;
    this.zeta2theta = 1.0 + Math.pow(0.5, zipfianConstant);
    this.alpha = 1.0 / (1.0 - zipfianConstant);
    this.zetan = zetan ?? computeZeta(items, zipfianConstant);
    this.eta =
      (1.0 - Math.pow(2.0 / Number(items), 1.0 - zipfianConstant)) /
      (1.0 - this.zeta2theta / this.zetan);
  }

  public nextLong(): bigint {
    if (this.items <= 1n) {
      return this.base;
    }

    const u = Math.random();
    const uz = u * this.zetan;

    if (uz < 1.0) {
      return this.base;
    }

    if (uz < this.zeta2theta) {
      return this.base + 1n;
    }

    const offset = BigInt(
      Math.floor(
        Number(this.items) *
          Math.pow(this.eta * u - this.eta + 1.0, this.alpha),
      ),
    );
    const result = this.base + offset;
    const maxVal = this.base + this.items - 1n;
    if (result > maxVal) {
      return maxVal;
    }
    return result;
  }

  public nextInt(): number {
    return Number(this.nextLong());
  }
}

/**
 * Generates a scrambled Zipfian distribution scattering popular items across the key space.
 */
export class ScrambledZipfianGenerator implements NumberGenerator {
  private generator: ZipfianGenerator;
  private min: bigint;
  private itemCount: bigint;

  constructor(
    min: number | bigint,
    max: number | bigint,
    zipfianConstant: number = DEFAULT_ZIPFIAN_CONSTANT,
  ) {
    const minBig = BigInt(min);
    const maxBig = BigInt(max);
    this.min = minBig;
    this.itemCount = maxBig - minBig + 1n;

    if (Math.abs(zipfianConstant - DEFAULT_ZIPFIAN_CONSTANT) < 1e-9) {
      this.generator = new ZipfianGenerator(
        0n,
        DEFAULT_ITEM_COUNT - 1n,
        DEFAULT_ZIPFIAN_CONSTANT,
        ZETAN_10B,
      );
    } else {
      this.generator = new ZipfianGenerator(
        0n,
        DEFAULT_ITEM_COUNT - 1n,
        zipfianConstant,
      );
    }
  }

  public nextLong(): bigint {
    const rawZipfian = this.generator.nextLong();
    const hashed = fnvHash64(rawZipfian);
    return this.min + (hashed % this.itemCount);
  }

  public nextInt(): number {
    return Number(this.nextLong());
  }
}

/**
 * Generates uniform random integers in [min, max].
 */
export class UniformIntegerGenerator implements NumberGenerator {
  private min: bigint;
  private range: bigint;

  constructor(min: number | bigint, max: number | bigint) {
    this.min = BigInt(min);
    this.range = BigInt(max) - BigInt(min) + 1n;
  }

  public nextLong(): bigint {
    if (this.range <= 1n) {
      return this.min;
    }
    const randFraction = Math.random();
    const offset = BigInt(Math.floor(randFraction * Number(this.range)));
    return this.min + offset;
  }

  public nextInt(): number {
    return Number(this.nextLong());
  }
}

/**
 * Generates keys with a Zipfian skew towards the most recently inserted records.
 */
export class SkewedLatestGenerator implements NumberGenerator {
  private basis: {value: bigint};
  private zipfianConstant: number;
  private zeta2theta: number;
  private alpha: number;
  private cachedParams: ZipfianParams;

  constructor(
    basis: {value: bigint},
    zipfianConstant: number = DEFAULT_ZIPFIAN_CONSTANT,
  ) {
    this.basis = basis;
    this.zipfianConstant = zipfianConstant;
    this.zeta2theta = 1.0 + Math.pow(0.5, zipfianConstant);
    this.alpha = 1.0 / (1.0 - zipfianConstant);

    const initial = basis.value < 2n ? 2n : basis.value;
    const zetan = computeZeta(initial, zipfianConstant);
    const eta =
      (1.0 - Math.pow(2.0 / Number(initial), 1.0 - zipfianConstant)) /
      (1.0 - this.zeta2theta / zetan);

    this.cachedParams = {
      itemCount: initial,
      zetan,
      eta,
    };
  }

  public nextLong(): bigint {
    const maxBasis = this.basis.value;
    if (maxBasis <= 1n) {
      return 0n;
    }

    if (this.cachedParams.itemCount !== maxBasis) {
      const zetan = computeZeta(maxBasis, this.zipfianConstant);
      const eta =
        (1.0 - Math.pow(2.0 / Number(maxBasis), 1.0 - this.zipfianConstant)) /
        (1.0 - this.zeta2theta / zetan);
      this.cachedParams = {
        itemCount: maxBasis,
        zetan,
        eta,
      };
    }

    const {zetan, eta} = this.cachedParams;
    const u = Math.random();
    const uz = u * zetan;

    if (uz < 1.0) {
      return maxBasis - 1n;
    }
    if (uz < this.zeta2theta) {
      const res = maxBasis - 2n;
      return res < 0n ? 0n : res;
    }

    const offset = BigInt(
      Math.floor(Number(maxBasis) * Math.pow(eta * u - eta + 1.0, this.alpha)),
    );
    const key = maxBasis - 1n - offset;
    return key < 0n ? 0n : key;
  }

  public nextInt(): number {
    return Number(this.nextLong());
  }
}

/**
 * Computes or approximates the generalized harmonic number zeta(n, theta) in O(1) time
 * using precomputed constants and Euler-Maclaurin integral approximation (Jim Gray et al., SIGMOD '94).
 */
export function computeZeta(n: number | bigint, theta: number): number {
  const num = Number(n);
  if (Math.abs(theta - DEFAULT_ZIPFIAN_CONSTANT) < 1e-9) {
    if (num >= 1000) {
      return (
        ZETAN_1K +
        (Math.pow(num, 1.0 - theta) - Math.pow(1000.0, 1.0 - theta)) /
          (1.0 - theta)
      );
    }
    return exactZeta(num, theta);
  }

  const n0 = Math.min(num, 1000);
  let sum = exactZeta(n0, theta);
  if (num > n0) {
    sum +=
      (Math.pow(num, 1.0 - theta) - Math.pow(n0, 1.0 - theta)) / (1.0 - theta);
  }
  return sum;
}

/**
 * Computes the 64-bit FNV-1a hash matching standard YCSB key scrambling.
 * Uses two's complement absolute value to guarantee non-negative signed 64-bit integer parity.
 */
export function fnvHash64(value: number | bigint): bigint {
  let hash = FNV_OFFSET_BASIS_64;
  let v = BigInt.asUintN(64, BigInt(value));
  for (let i = 0; i < 8; i++) {
    const octet = v & 0xffn;
    v >>= 8n;
    hash ^= octet;
    hash = BigInt.asUintN(64, hash * FNV_PRIME_64);
  }
  const signedHash = BigInt.asIntN(64, hash);
  if (signedHash < 0n) {
    if (signedHash === -0x8000000000000000n) {
      return 0n;
    }
    return -signedHash;
  }
  return signedHash;
}

interface ZipfianParams {
  itemCount: bigint;
  zetan: number;
  eta: number;
}

function exactZeta(n: number, theta: number): number {
  let sum = 0.0;
  for (let i = 0; i < n; i++) {
    sum += 1.0 / Math.pow(i + 1, theta);
  }
  return sum;
}
