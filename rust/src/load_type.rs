use crate::{Commands, BenchmarkMetrics, run_task};
use google_cloud_spanner::client::DatabaseClient;
use opentelemetry::KeyValue;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::Semaphore;
use tokio::time::Instant;

#[derive(clap::ValueEnum, Clone, Debug, PartialEq)]
pub enum LoadType {
    Steady,
    Spiky,
    Gradual,
}

pub struct RunConfig {
    pub db_client: DatabaseClient,
    pub table: String,
    pub command: Commands,
    pub semaphore: Arc<Semaphore>,
    pub metrics: BenchmarkMetrics,
    pub attributes: Vec<KeyValue>,
    pub tps: f64,
    pub duration: Option<Duration>,
    pub start_time: Instant,
    pub burst_factor: f64,
    pub burst_duration: f64,
    pub burst_fraction: f64,
    pub cycle_duration: Duration,
    pub peak_factor: f64,
}

impl LoadType {
    pub async fn run(&self, config: RunConfig) {
        match self {
            LoadType::Steady => self.run_steady(config).await,
            LoadType::Spiky => self.run_spiky(config).await,
            LoadType::Gradual => self.run_gradual(config).await,
        }
    }

    async fn run_steady(&self, config: RunConfig) {
        loop {
            if let Some(dur) = config.duration {
                if config.start_time.elapsed() >= dur {
                    break;
                }
            }

            let permit = match config.semaphore.clone().acquire_owned().await {
                Ok(p) => p,
                Err(_) => break,
            };

            let db_client = config.db_client.clone();
            let table = config.table.clone();
            let command = config.command.clone();
            let metrics = config.metrics.clone();
            let attributes = config.attributes.clone();

            tokio::spawn(run_task(db_client, table, command, permit, metrics, attributes));

            tokio::time::sleep(calculate_poisson_delay(config.tps)).await;
        }
    }

    async fn run_spiky(&self, config: RunConfig) {
        let r_burst = config.tps * config.burst_factor;
        let r_normal = (config.tps - config.burst_fraction * r_burst) / (1.0 - config.burst_fraction);

        let mu2 = 1.0 / config.burst_duration;
        let mu1 = mu2 * config.burst_fraction / (1.0 - config.burst_fraction);

        let mut in_burst = false;
        let mut next_state_change_time = Instant::now() + calculate_poisson_delay(mu1);

        loop {
            if let Some(dur) = config.duration {
                if config.start_time.elapsed() >= dur {
                    break;
                }
            }

            let now = Instant::now();
            if now >= next_state_change_time {
                in_burst = !in_burst;
                let next_delay = if in_burst { calculate_poisson_delay(mu2) } else { calculate_poisson_delay(mu1) };
                next_state_change_time = now + next_delay;
            }

            let current_rate = if in_burst { r_burst } else { r_normal };
            let delay = if current_rate <= 0.0 {
                Duration::from_secs(3600)
            } else {
                calculate_poisson_delay(current_rate)
            };

            let time_to_state_change = if next_state_change_time > now {
                next_state_change_time.duration_since(now)
            } else {
                Duration::from_secs(0)
            };
            
            if delay > time_to_state_change {
                if !time_to_state_change.is_zero() {
                    tokio::time::sleep(time_to_state_change).await;
                }
                continue;
            }

            let permit = match config.semaphore.clone().acquire_owned().await {
                Ok(p) => p,
                Err(_) => break,
            };

            let db_client = config.db_client.clone();
            let table = config.table.clone();
            let command = config.command.clone();
            let metrics = config.metrics.clone();
            let attributes = config.attributes.clone();

            tokio::spawn(run_task(db_client, table, command, permit, metrics, attributes));

            tokio::time::sleep(delay).await;
        }
    }

    async fn run_gradual(&self, config: RunConfig) {
        let cycle_duration_ns = config.cycle_duration.as_nanos() as f64;
        let amplitude = config.tps * (config.peak_factor - 1.0);
        let start_instant = Instant::now();

        loop {
            if let Some(dur) = config.duration {
                if config.start_time.elapsed() >= dur {
                    break;
                }
            }

            let now = Instant::now();
            let elapsed_ns = now.duration_since(start_instant).as_nanos() as u64;
            
            // Calculate rate based on sine wave
            let angle = (2.0 * std::f64::consts::PI * (elapsed_ns % cycle_duration_ns as u64) as f64) / cycle_duration_ns;
            let current_rate = config.tps + amplitude * (angle - std::f64::consts::PI).cos();

            let permit = match config.semaphore.clone().acquire_owned().await {
                Ok(p) => p,
                Err(_) => break,
            };

            let db_client = config.db_client.clone();
            let table = config.table.clone();
            let command = config.command.clone();
            let metrics = config.metrics.clone();
            let attributes = config.attributes.clone();

            tokio::spawn(run_task(db_client, table, command, permit, metrics, attributes));

            tokio::time::sleep(calculate_poisson_delay(current_rate)).await;
        }
    }
}

pub fn parse_duration(duration_str: &str) -> Option<Duration> {
    if duration_str == "inf" || duration_str == "infinite" {
        return None;
    }
    if duration_str.ends_with("ms") {
        let millis = duration_str[..duration_str.len() - 2].parse::<u64>().ok()?;
        Some(Duration::from_millis(millis))
    } else if duration_str.ends_with('s') {
        let secs = duration_str[..duration_str.len() - 1].parse::<u64>().ok()?;
        Some(Duration::from_secs(secs))
    } else if duration_str.ends_with('m') {
        let mins = duration_str[..duration_str.len() - 1].parse::<u64>().ok()?;
        Some(Duration::from_secs(mins * 60))
    } else if duration_str.ends_with('h') {
        let hours = duration_str[..duration_str.len() - 1].parse::<u64>().ok()?;
        Some(Duration::from_secs(hours * 3600))
    } else {
        let secs = duration_str.parse::<u64>().ok()?;
        Some(Duration::from_secs(secs))
    }
}

fn calculate_poisson_delay(rate: f64) -> Duration {
    let u: f64 = rand::random();
    let delay_seconds = -u.ln() / rate;
    Duration::from_secs_f64(delay_seconds)
}
