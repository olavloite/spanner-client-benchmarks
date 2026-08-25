/**
 * Standard YCSB core workloads (A through F).
 */
export enum Workload {
  A = 'A',
  B = 'B',
  C = 'C',
  D = 'D',
  E = 'E',
  F = 'F',
}

/**
 * Key selection distribution strategies.
 */
export enum KeyDistribution {
  ScrambledZipfian = 'scrambled-zipfian',
  Zipfian = 'zipfian',
  Uniform = 'uniform',
}

/**
 * Individual YCSB transaction operation types.
 */
export enum Operation {
  Read = 'READ',
  Update = 'UPDATE',
  Insert = 'INSERT',
  Scan = 'SCAN',
  ReadModifyWrite = 'RMW',
}

/**
 * Parses a string into a Workload enum.
 */
export function parseWorkload(s: string): Workload {
  const normalized = s.trim().toUpperCase();
  switch (normalized) {
    case 'A':
      return Workload.A;
    case 'B':
      return Workload.B;
    case 'C':
      return Workload.C;
    case 'D':
      return Workload.D;
    case 'E':
      return Workload.E;
    case 'F':
      return Workload.F;
    default:
      throw new Error(
        `Unknown YCSB workload "${s}" (expected A, B, C, D, E, or F)`,
      );
  }
}

/**
 * Parses a string into a KeyDistribution enum.
 */
export function parseDistribution(s: string): KeyDistribution {
  const normalized = s.trim().toLowerCase().replace(/_/g, '-');
  switch (normalized) {
    case 'scrambled-zipfian':
    case 'scrambledzipfian':
      return KeyDistribution.ScrambledZipfian;
    case 'zipfian':
      return KeyDistribution.Zipfian;
    case 'uniform':
      return KeyDistribution.Uniform;
    default:
      throw new Error(
        `Unknown key distribution "${s}" (expected scrambled-zipfian, zipfian, or uniform)`,
      );
  }
}

/**
 * Chooses an operation according to the specified YCSB workload distribution.
 */
export function chooseOperation(workload: Workload): Operation {
  const r = Math.random();
  switch (workload) {
    case Workload.A:
      // 50% Read, 50% Update
      return r < 0.5 ? Operation.Read : Operation.Update;

    case Workload.B:
      // 95% Read, 5% Update
      return r < 0.95 ? Operation.Read : Operation.Update;

    case Workload.C:
      // 100% Read
      return Operation.Read;

    case Workload.D:
      // 95% Read (latest), 5% Insert
      return r < 0.95 ? Operation.Read : Operation.Insert;

    case Workload.E:
      // 95% Scan, 5% Insert
      return r < 0.95 ? Operation.Scan : Operation.Insert;

    case Workload.F:
      // 50% Read, 50% Read-Modify-Write
      return r < 0.5 ? Operation.Read : Operation.ReadModifyWrite;

    default:
      return Operation.Read;
  }
}
