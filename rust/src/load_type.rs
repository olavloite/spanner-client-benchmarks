use crate::{BenchmarkMetrics, Commands, run_task, run_task_closed_loop};
use google_cloud_spanner::client::DatabaseClient;
use opentelemetry::KeyValue;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::{OwnedSemaphorePermit, Semaphore};
use tokio::time::Instant;

#[derive(clap::ValueEnum, Clone, Debug, PartialEq)]
pub enum LoadType {
    Steady,
    Spiky,
    Gradual,
    ClosedLoop,
}

pub struct RunConfig {
    pub db_client: DatabaseClient,
    pub table: String,
    pub command: Commands,
    pub semaphore: Arc<Semaphore>,
    pub threads: usize,
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
            LoadType::ClosedLoop => self.run_closed_loop(config).await,
        }
    }

    async fn run_steady(&self, config: RunConfig) {
        let tps = config.tps;
        let duration = config.duration;
        let start_time = config.start_time;
        let mut next_tick_time = Instant::now();
        let mut poisson_timeline = Instant::now();

        let waiters = Arc::new(AtomicUsize::new(0));
        let last_log = Arc::new(Mutex::new(Instant::now()));

        loop {
            if let Some(dur) = duration {
                if start_time.elapsed() >= dur {
                    break;
                }
            }

            let now = Instant::now();
            if next_tick_time < now {
                next_tick_time = now;
            }
            let tick_duration = Duration::from_millis(1);
            let target_tick_end = next_tick_time + tick_duration;

            if poisson_timeline < next_tick_time {
                poisson_timeline = next_tick_time;
            }

            // Calculate number of tasks for this 1ms tick
            let mut count = 0;
            while poisson_timeline < target_tick_end {
                count += 1;
                let delay = calculate_poisson_delay(tps);
                poisson_timeline += delay;
            }

            for _ in 0..count {
                let db_client = config.db_client.clone();
                let table = config.table.clone();
                let command = config.command.clone();
                let metrics = config.metrics.clone();
                let attributes = config.attributes.clone();
                let semaphore = config.semaphore.clone();
                let waiters = waiters.clone();
                let last_log = last_log.clone();

                tokio::spawn(async move {
                    let permit =
                        match acquire_permit_with_logging(semaphore, &waiters, &last_log).await {
                            Some(p) => p,
                            None => return,
                        };
                    run_task(db_client, table, command, permit, metrics, attributes).await;
                });
            }

            next_tick_time += tick_duration;
            sleep_hybrid(next_tick_time).await;
        }
    }

    async fn run_spiky(&self, config: RunConfig) {
        let tps = config.tps;
        let duration = config.duration;
        let start_time = config.start_time;
        let burst_factor = config.burst_factor;
        let burst_duration = config.burst_duration;
        let burst_fraction = config.burst_fraction;

        let r_burst = tps * burst_factor;
        let r_normal = (tps - burst_fraction * r_burst) / (1.0 - burst_fraction);

        let mu2 = 1.0 / burst_duration;
        let mu1 = mu2 * burst_fraction / (1.0 - burst_fraction);

        let mut in_burst = false;
        let mut next_state_change_time = Instant::now() + calculate_poisson_delay(mu1);
        let mut next_tick_time = Instant::now();
        let mut poisson_timeline = Instant::now();

        let waiters = Arc::new(AtomicUsize::new(0));
        let last_log = Arc::new(Mutex::new(Instant::now()));

        loop {
            if let Some(dur) = duration {
                if start_time.elapsed() >= dur {
                    break;
                }
            }

            let now = Instant::now();
            if next_tick_time < now {
                next_tick_time = now;
            }
            let tick_duration = Duration::from_millis(1);
            let target_tick_end = next_tick_time + tick_duration;

            if poisson_timeline < next_tick_time {
                poisson_timeline = next_tick_time;
            }

            if now >= next_state_change_time {
                in_burst = !in_burst;
                let next_delay = if in_burst {
                    calculate_poisson_delay(mu2)
                } else {
                    calculate_poisson_delay(mu1)
                };
                next_state_change_time = now + next_delay;
            }

            let current_rate = if in_burst { r_burst } else { r_normal };

            // Calculate number of tasks for this 1ms tick
            let mut count = 0;
            if current_rate > 0.0 {
                while poisson_timeline < target_tick_end {
                    count += 1;
                    let delay = calculate_poisson_delay(current_rate);
                    poisson_timeline += delay;
                }
            } else {
                poisson_timeline = target_tick_end;
            }

            for _ in 0..count {
                let db_client = config.db_client.clone();
                let table = config.table.clone();
                let command = config.command.clone();
                let metrics = config.metrics.clone();
                let attributes = config.attributes.clone();
                let semaphore = config.semaphore.clone();
                let waiters = waiters.clone();
                let last_log = last_log.clone();

                tokio::spawn(async move {
                    let permit =
                        match acquire_permit_with_logging(semaphore, &waiters, &last_log).await {
                            Some(p) => p,
                            None => return,
                        };
                    run_task(db_client, table, command, permit, metrics, attributes).await;
                });
            }

            next_tick_time += tick_duration;
            sleep_hybrid(next_tick_time).await;
        }
    }

    async fn run_gradual(&self, config: RunConfig) {
        let tps = config.tps;
        let duration = config.duration;
        let start_time = config.start_time;
        let cycle_duration = config.cycle_duration;
        let peak_factor = config.peak_factor;

        let cycle_duration_ns = cycle_duration.as_nanos() as f64;
        let amplitude = tps * (peak_factor - 1.0);
        let start_instant = Instant::now();
        let mut next_tick_time = Instant::now();
        let mut poisson_timeline = Instant::now();

        let waiters = Arc::new(AtomicUsize::new(0));
        let last_log = Arc::new(Mutex::new(Instant::now()));

        loop {
            if let Some(dur) = duration {
                if start_time.elapsed() >= dur {
                    break;
                }
            }

            let now = Instant::now();
            if next_tick_time < now {
                next_tick_time = now;
            }
            let tick_duration = Duration::from_millis(1);
            let target_tick_end = next_tick_time + tick_duration;

            if poisson_timeline < next_tick_time {
                poisson_timeline = next_tick_time;
            }

            let elapsed_ns = now.duration_since(start_instant).as_nanos() as u64;
            let angle =
                (2.0 * std::f64::consts::PI * (elapsed_ns % cycle_duration_ns as u64) as f64)
                    / cycle_duration_ns;
            let current_rate = tps + amplitude * (angle - std::f64::consts::PI).cos();

            // Calculate number of tasks for this 1ms tick
            let mut count = 0;
            if current_rate > 0.0 {
                while poisson_timeline < target_tick_end {
                    count += 1;
                    let delay = calculate_poisson_delay(current_rate);
                    poisson_timeline += delay;
                }
            } else {
                poisson_timeline = target_tick_end;
            }

            for _ in 0..count {
                let db_client = config.db_client.clone();
                let table = config.table.clone();
                let command = config.command.clone();
                let metrics = config.metrics.clone();
                let attributes = config.attributes.clone();
                let semaphore = config.semaphore.clone();
                let waiters = waiters.clone();
                let last_log = last_log.clone();

                tokio::spawn(async move {
                    let permit =
                        match acquire_permit_with_logging(semaphore, &waiters, &last_log).await {
                            Some(p) => p,
                            None => return,
                        };
                    run_task(db_client, table, command, permit, metrics, attributes).await;
                });
            }

            next_tick_time += tick_duration;
            sleep_hybrid(next_tick_time).await;
        }
    }

    async fn run_closed_loop(&self, config: RunConfig) {
        let duration = config.duration;
        let start_time = config.start_time;

        let mut tasks = Vec::new();

        for _ in 0..config.threads {
            let db_client = config.db_client.clone();
            let table = config.table.clone();
            let command = config.command.clone();
            let metrics = config.metrics.clone();
            let attributes = config.attributes.clone();

            let task = tokio::spawn(async move {
                loop {
                    if let Some(dur) = duration {
                        if start_time.elapsed() >= dur {
                            break;
                        }
                    }
                    run_task_closed_loop(
                        db_client.clone(),
                        table.clone(),
                        command.clone(),
                        metrics.clone(),
                        attributes.clone(),
                    )
                    .await;
                }
            });
            tasks.push(task);
        }

        for task in tasks {
            let _ = task.await;
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

async fn acquire_permit_with_logging(
    semaphore: Arc<Semaphore>,
    waiters: &Arc<AtomicUsize>,
    last_log: &Arc<Mutex<Instant>>,
) -> Option<OwnedSemaphorePermit> {
    // Increment the waiter count. fetch_add returns the previous value, so we add 1 to get the current count.
    let queue_size = waiters.fetch_add(1, Ordering::SeqCst) + 1;

    // Only lock the Mutex and log if queue_size > 1 (meaning tasks are actually queueing).
    // This avoids mutex contention overhead on the hot path when we are under the concurrency limit.
    if queue_size > 1 {
        let mut last_log_guard = last_log.lock().unwrap();
        if last_log_guard.elapsed() > Duration::from_secs(1) {
            println!(
                "Queue size: {} (concurrency limit reached, tasks are queueing)",
                queue_size
            );
            *last_log_guard = Instant::now();
        }
    }

    match semaphore.acquire_owned().await {
        Ok(permit) => {
            waiters.fetch_sub(1, Ordering::SeqCst);
            Some(permit)
        }
        Err(_) => {
            // Err(_) is returned only if the semaphore has been closed (dropped/decommissioned).
            // This occurs during benchmark shutdown, so we clean up the waiter count and return None to exit.
            waiters.fetch_sub(1, Ordering::SeqCst);
            None
        }
    }
}

async fn sleep_hybrid(target_time: Instant) {
    let now = Instant::now();
    if target_time > now {
        let diff = target_time - now;
        if diff > Duration::from_millis(1) {
            tokio::time::sleep(diff - Duration::from_micros(100)).await;
        }
        while Instant::now() < target_time {
            tokio::task::yield_now().await;
        }
    }
}
