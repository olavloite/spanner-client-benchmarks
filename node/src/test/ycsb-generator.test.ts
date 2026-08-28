import {describe, it} from 'node:test';
import * as assert from 'node:assert';
import {
  computeZeta,
  fnvHash64,
  ZipfianGenerator,
  ScrambledZipfianGenerator,
  UniformIntegerGenerator,
  SkewedLatestGenerator,
} from '../benchmarks/ycsb/generator';

describe('YCSB Generators Unit Tests', () => {
  it('should compute zeta(n, theta) matching canonical mathematical approximation', () => {
    const zeta1k = computeZeta(1000, 0.99);
    assert.ok(Math.abs(zeta1k - 7.72895) < 0.01);

    const zeta100k = computeZeta(100000, 0.99);
    assert.ok(zeta100k > zeta1k);
  });

  it('should compute 64-bit FNV-1a hash matching canonical Java, Go, and Rust implementations', () => {
    // Canonical known test vectors for YCSB FNV-1a 64-bit hashing:
    const hash0 = fnvHash64(0n);
    assert.strictEqual(
      hash0,
      6284781860667377211n,
      'fnvHash64(0) must produce 6284781860667377211',
    );

    // Verify non-negativity for various test values
    const testVals = [0n, 1n, 42n, 100000n, 9999999999n, -1n, -42n];
    for (const v of testVals) {
      const h = fnvHash64(v);
      assert.ok(
        h >= 0n,
        `fnvHash64(${v}) = ${h} must be non-negative signed 64-bit`,
      );
    }
  });

  it('should generate Zipfian distributed values strictly within [min, max]', () => {
    const min = 100n;
    const max = 200n;
    const gen = new ZipfianGenerator(min, max);

    for (let i = 0; i < 1000; i++) {
      const val = gen.nextLong();
      assert.ok(
        val >= min && val <= max,
        `Generated value ${val} out of range [${min}, ${max}]`,
      );
    }
  });

  it('should generate Scrambled Zipfian values strictly within [min, max]', () => {
    const min = 0n;
    const max = 99999n;
    const gen = new ScrambledZipfianGenerator(min, max);

    for (let i = 0; i < 1000; i++) {
      const val = gen.nextLong();
      assert.ok(
        val >= min && val <= max,
        `Generated scrambled value ${val} out of range [${min}, ${max}]`,
      );
    }
  });

  it('should generate Uniform integer values strictly within [min, max]', () => {
    const min = 50n;
    const max = 150n;
    const gen = new UniformIntegerGenerator(min, max);

    for (let i = 0; i < 1000; i++) {
      const val = gen.nextLong();
      assert.ok(
        val >= min && val <= max,
        `Generated uniform value ${val} out of range [${min}, ${max}]`,
      );
    }
  });

  it('should generate Skewed Latest values tracking insert basis', () => {
    const basis = {value: 1000n};
    const gen = new SkewedLatestGenerator(basis);

    for (let i = 0; i < 1000; i++) {
      const val = gen.nextLong();
      assert.ok(
        val >= 0n && val < basis.value,
        `Generated skewed latest value ${val} out of range [0, ${basis.value - 1n}]`,
      );
    }
  });
});
