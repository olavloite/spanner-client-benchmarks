use crate::BenchmarkMetrics;
use opentelemetry::KeyValue;
use tokio::task::JoinHandle;

pub struct ResourceMonitor {
    handle: Option<JoinHandle<()>>,
}

impl ResourceMonitor {
    pub fn start(
        probe_interval_str: &str,
        metrics: BenchmarkMetrics,
        attributes: Vec<KeyValue>,
    ) -> Self {
        let handle = start_resource_monitoring(probe_interval_str, metrics, attributes);
        ResourceMonitor { handle }
    }
}

impl Drop for ResourceMonitor {
    fn drop(&mut self) {
        if let Some(handle) = self.handle.take() {
            handle.abort();
        }
    }
}

fn start_resource_monitoring(
    probe_interval_str: &str,
    metrics: BenchmarkMetrics,
    attributes: Vec<KeyValue>,
) -> Option<JoinHandle<()>> {
    if probe_interval_str != "0" && probe_interval_str != "0s" && !probe_interval_str.is_empty() {
        if let Some(probe_duration) = crate::load_type::parse_duration(probe_interval_str) {
            if probe_duration.as_millis() > 0 {
                let handle = tokio::spawn(async move {
                    run_resource_monitor_loop(probe_duration, metrics, attributes).await;
                });
                return Some(handle);
            }
        }
    }
    None
}

fn get_cpu_limit() -> f64 {
    static CPU_LIMIT: std::sync::OnceLock<f64> = std::sync::OnceLock::new();
    *CPU_LIMIT.get_or_init(|| {
        if let Ok(limit_str) = std::env::var("BENCHMARK_CPU_LIMIT") {
            if let Ok(limit) = limit_str.parse::<f64>() {
                if limit > 0.0 {
                    return limit;
                }
            }
        }
        std::thread::available_parallelism()
            .map(|n| n.get() as f64)
            .unwrap_or(1.0)
    })
}

async fn run_resource_monitor_loop(
    probe_duration: std::time::Duration,
    metrics: BenchmarkMetrics,
    attributes: Vec<KeyValue>,
) {
    let mut sys = sysinfo::System::new();
    let cpu_limit = get_cpu_limit();
    if let Ok(pid) = sysinfo::get_current_pid() {
        let mut interval = tokio::time::interval(probe_duration);
        loop {
            interval.tick().await;
            sys.refresh_processes(sysinfo::ProcessesToUpdate::Some(&[pid]), true);
            if let Some(process) = sys.process(pid) {
                let memory = process.memory() as f64;
                let cpu = (process.cpu_usage() / 100.0) as f64;
                metrics.memory_usage.record(memory, &attributes);
                metrics.cpu_utilization.record(cpu / cpu_limit, &attributes);
            }
        }
    }
}
