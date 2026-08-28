import {describe, it} from 'node:test';
import * as assert from 'node:assert';
import {
  Workload,
  KeyDistribution,
  Operation,
  parseWorkload,
  parseDistribution,
  chooseOperation,
} from '../benchmarks/ycsb/workload';
import {
  buildKeyName,
  generateRandomString,
  buildRow,
} from '../benchmarks/ycsb/utils';
import {generateSchemaDdl} from '../benchmarks/ycsb/schema';

describe('YCSB Workload and Utilities Unit Tests', () => {
  it('should parse Workload and KeyDistribution strings properly', () => {
    assert.strictEqual(parseWorkload('a'), Workload.A);
    assert.strictEqual(parseWorkload('B'), Workload.B);
    assert.strictEqual(parseWorkload('c'), Workload.C);
    assert.strictEqual(parseWorkload('D'), Workload.D);
    assert.strictEqual(parseWorkload('e'), Workload.E);
    assert.strictEqual(parseWorkload('F'), Workload.F);

    assert.throws(() => parseWorkload('Z'));

    assert.strictEqual(
      parseDistribution('scrambled-zipfian'),
      KeyDistribution.ScrambledZipfian,
    );
    assert.strictEqual(parseDistribution('zipfian'), KeyDistribution.Zipfian);
    assert.strictEqual(parseDistribution('uniform'), KeyDistribution.Uniform);

    assert.throws(() => parseDistribution('invalid-dist'));
  });

  it('should choose operations matching workload distributions', () => {
    // Workload C is 100% READ
    for (let i = 0; i < 100; i++) {
      assert.strictEqual(chooseOperation(Workload.C), Operation.Read);
    }

    // Workload B has READ and UPDATE only
    const bOps = new Set<Operation>();
    for (let i = 0; i < 500; i++) {
      bOps.add(chooseOperation(Workload.B));
    }
    assert.ok(bOps.has(Operation.Read));
    assert.ok(bOps.has(Operation.Update));
    assert.ok(!bOps.has(Operation.Insert));
    assert.ok(!bOps.has(Operation.Scan));
    assert.ok(!bOps.has(Operation.ReadModifyWrite));

    // Workload E has SCAN and INSERT only
    const eOps = new Set<Operation>();
    for (let i = 0; i < 500; i++) {
      eOps.add(chooseOperation(Workload.E));
    }
    assert.ok(eOps.has(Operation.Scan));
    assert.ok(eOps.has(Operation.Insert));
    assert.ok(!eOps.has(Operation.Read));

    // Workload F has READ and RMW only
    const fOps = new Set<Operation>();
    for (let i = 0; i < 500; i++) {
      fOps.add(chooseOperation(Workload.F));
    }
    assert.ok(fOps.has(Operation.Read));
    assert.ok(fOps.has(Operation.ReadModifyWrite));
  });

  it('should build zero-padded primary key strings correctly', () => {
    assert.strictEqual(buildKeyName(1, 12), 'user000000000001');
    assert.strictEqual(buildKeyName(42n, 12), 'user000000000042');
    assert.strictEqual(buildKeyName(100000, 0), 'user100000');
    assert.strictEqual(buildKeyName(123456789, 5), 'user123456789');
  });

  it('should generate ASCII random strings of exact requested lengths', () => {
    assert.strictEqual(generateRandomString(0), '');
    assert.strictEqual(generateRandomString(10).length, 10);
    assert.strictEqual(generateRandomString(100).length, 100);
    assert.strictEqual(generateRandomString(20000).length, 20000);
  });

  it('should build full YCSB row objects with correct column counts', () => {
    const row = buildRow('user000000000001', 10, 50);
    assert.strictEqual(row.id, 'user000000000001');
    for (let f = 0; f < 10; f++) {
      assert.ok(row[`field${f}`] !== undefined);
      assert.strictEqual(row[`field${f}`].length, 50);
    }
  });

  it('should generate valid Cloud Spanner DDL statements', () => {
    const ddl = generateSchemaDdl('usertable', 10);
    assert.ok(ddl.includes('CREATE TABLE IF NOT EXISTS usertable'));
    assert.ok(ddl.includes('id STRING(MAX)'));
    assert.ok(ddl.includes('field0 STRING(MAX)'));
    assert.ok(ddl.includes('field9 STRING(MAX)'));
    assert.ok(ddl.includes('PRIMARY KEY(id)'));
  });
});
