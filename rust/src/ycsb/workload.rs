// Copyright 2026 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

use clap::ValueEnum;
use rand::{RngExt, rng};

/// Standard YCSB core workload definitions (Workloads A through F).
#[derive(ValueEnum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum YcsbWorkload {
    /// Workload A: 50% Read, 50% Update (Heavy Update).
    #[value(name = "A", alias = "a")]
    A,
    /// Workload B: 95% Read, 5% Update (Read Mostly).
    #[value(name = "B", alias = "b")]
    B,
    /// Workload C: 100% Read (Read Only).
    #[value(name = "C", alias = "c")]
    C,
    /// Workload D: 95% Read, 5% Insert (Read Latest).
    #[value(name = "D", alias = "d")]
    D,
    /// Workload E: 95% Scan, 5% Insert (Short Scans).
    #[value(name = "E", alias = "e")]
    E,
    /// Workload F: 50% Read, 50% Read-Modify-Write (Transactional Atomic Update).
    #[value(name = "F", alias = "f")]
    F,
}

impl YcsbWorkload {
    /// Returns the uppercase string name of the workload (e.g. `"A"`, `"B"`).
    pub fn name(&self) -> &'static str {
        match self {
            Self::A => "A",
            Self::B => "B",
            Self::C => "C",
            Self::D => "D",
            Self::E => "E",
            Self::F => "F",
        }
    }

    /// Selects the next operation according to the workload's target probability distribution.
    pub(crate) fn next_operation(&self) -> Operation {
        let mut random_number_generator = rng();
        let sample = random_number_generator.random_range(0.0..1.0);

        match self {
            Self::A => {
                // 50% Read, 50% Update
                if sample < 0.50 {
                    Operation::Read
                } else {
                    Operation::Update
                }
            }
            Self::B => {
                // 95% Read, 5% Update
                if sample < 0.95 {
                    Operation::Read
                } else {
                    Operation::Update
                }
            }
            Self::C => {
                // 100% Read
                Operation::Read
            }
            Self::D => {
                // 95% Read, 5% Insert
                if sample < 0.95 {
                    Operation::Read
                } else {
                    Operation::Insert
                }
            }
            Self::E => {
                // 95% Scan, 5% Insert
                if sample < 0.95 {
                    Operation::Scan
                } else {
                    Operation::Insert
                }
            }
            Self::F => {
                // 50% Read, 50% Read-Modify-Write
                if sample < 0.50 {
                    Operation::Read
                } else {
                    Operation::ReadModifyWrite
                }
            }
        }
    }
}

/// Key request distribution strategy used across YCSB operations.
#[derive(ValueEnum, Clone, Copy, Debug, PartialEq, Eq)]
pub enum KeyDistribution {
    /// Standard Zipfian distribution centered at key 0.
    #[value(name = "zipfian", alias = "ZIPFIAN")]
    Zipfian,
    /// Uniformly distributed random keys across the entire key space.
    #[value(name = "uniform", alias = "UNIFORM")]
    Uniform,
    /// Scrambled Zipfian distribution scattering hot keys across the entire key space using FNV-1a.
    #[value(
        name = "scrambled-zipfian",
        alias = "scrambled_zipfian",
        alias = "SCRAMBLED_ZIPFIAN",
        alias = "scrambledzipfian"
    )]
    ScrambledZipfian,
}

/// Primitive database operation executed by a YCSB worker.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Operation {
    /// Single row lookup by primary key.
    Read,
    /// In-place single row column update.
    Update,
    /// New row insertion with monotonic key sequence.
    Insert,
    /// Range scan starting from random key for a given limit.
    Scan,
    /// Transactional read-modify-write on a row.
    ReadModifyWrite,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn workload_c_always_reads() -> anyhow::Result<()> {
        for _ in 0..1000 {
            assert_eq!(
                YcsbWorkload::C.next_operation(),
                Operation::Read,
                "Workload C must always return Read operation"
            );
        }
        Ok(())
    }

    #[test]
    fn workload_a_distribution() -> anyhow::Result<()> {
        let mut read_count = 0;
        let mut update_count = 0;
        let iterations = 10_000;

        for _ in 0..iterations {
            match YcsbWorkload::A.next_operation() {
                Operation::Read => read_count += 1,
                Operation::Update => update_count += 1,
                other => anyhow::bail!("Unexpected operation in Workload A: {:?}", other),
            }
        }

        let read_ratio = read_count as f64 / iterations as f64;
        assert!(
            (read_ratio - 0.50).abs() < 0.03,
            "Workload A read ratio {:.3} should be close to 0.50",
            read_ratio
        );
        assert_eq!(
            read_count + update_count,
            iterations,
            "Total operations must equal iterations"
        );
        Ok(())
    }

    #[test]
    fn workload_b_distribution() -> anyhow::Result<()> {
        let mut read_count = 0;
        let mut update_count = 0;
        let iterations = 10_000;

        for _ in 0..iterations {
            match YcsbWorkload::B.next_operation() {
                Operation::Read => read_count += 1,
                Operation::Update => update_count += 1,
                other => anyhow::bail!("Unexpected operation in Workload B: {:?}", other),
            }
        }

        let read_ratio = read_count as f64 / iterations as f64;
        assert!(
            (read_ratio - 0.95).abs() < 0.02,
            "Workload B read ratio {:.3} should be close to 0.95",
            read_ratio
        );
        assert_eq!(
            read_count + update_count,
            iterations,
            "Total operations must equal iterations"
        );
        Ok(())
    }

    #[test]
    fn workload_d_distribution() -> anyhow::Result<()> {
        let mut read_count = 0;
        let mut insert_count = 0;
        let iterations = 10_000;

        for _ in 0..iterations {
            match YcsbWorkload::D.next_operation() {
                Operation::Read => read_count += 1,
                Operation::Insert => insert_count += 1,
                other => anyhow::bail!("Unexpected operation in Workload D: {:?}", other),
            }
        }

        let read_ratio = read_count as f64 / iterations as f64;
        assert!(
            (read_ratio - 0.95).abs() < 0.02,
            "Workload D read ratio {:.3} should be close to 0.95",
            read_ratio
        );
        assert_eq!(
            read_count + insert_count,
            iterations,
            "Total operations must equal iterations"
        );
        Ok(())
    }

    #[test]
    fn workload_e_distribution() -> anyhow::Result<()> {
        let mut scan_count = 0;
        let mut insert_count = 0;
        let iterations = 10_000;

        for _ in 0..iterations {
            match YcsbWorkload::E.next_operation() {
                Operation::Scan => scan_count += 1,
                Operation::Insert => insert_count += 1,
                other => anyhow::bail!("Unexpected operation in Workload E: {:?}", other),
            }
        }

        let scan_ratio = scan_count as f64 / iterations as f64;
        assert!(
            (scan_ratio - 0.95).abs() < 0.02,
            "Workload E scan ratio {:.3} should be close to 0.95",
            scan_ratio
        );
        assert_eq!(
            scan_count + insert_count,
            iterations,
            "Total operations must equal iterations"
        );
        Ok(())
    }

    #[test]
    fn workload_f_distribution() -> anyhow::Result<()> {
        let mut read_count = 0;
        let mut rmw_count = 0;
        let iterations = 10_000;

        for _ in 0..iterations {
            match YcsbWorkload::F.next_operation() {
                Operation::Read => read_count += 1,
                Operation::ReadModifyWrite => rmw_count += 1,
                other => anyhow::bail!("Unexpected operation in Workload F: {:?}", other),
            }
        }

        let read_ratio = read_count as f64 / iterations as f64;
        assert!(
            (read_ratio - 0.50).abs() < 0.03,
            "Workload F read ratio {:.3} should be close to 0.50",
            read_ratio
        );
        assert_eq!(
            read_count + rmw_count,
            iterations,
            "Total operations must equal iterations"
        );
        Ok(())
    }
}
