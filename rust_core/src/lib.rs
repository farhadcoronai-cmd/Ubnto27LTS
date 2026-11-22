mod awg_config;
mod doh_engine;

use doh_engine::{build_dns_probe_query, run_tun_doh_loop, validate_dns_wire_response, DohEngineState};
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use serde::{Deserialize, Serialize};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};
use tokio::net::TcpStream;
use tokio::runtime::Runtime;
use tokio::task::JoinHandle;

static TOKIO_RUNTIME: OnceLock<Runtime> = OnceLock::new();
static DOH_HANDLE: OnceLock<Mutex<Option<ActiveDohSession>>> = OnceLock::new();

struct ActiveDohSession {
    state: Arc<DohEngineState>,
    task: JoinHandle<()>,
}

fn get_runtime() -> &'static Runtime {
    TOKIO_RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("codm-rust-net")
            .enable_all()
            .build()
            .expect("Failed to initialize Tokio 2-thread runtime")
    })
}

fn get_doh_slot() -> &'static Mutex<Option<ActiveDohSession>> {
    DOH_HANDLE.get_or_init(|| Mutex::new(None))
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct DohBenchmarkItem {
    url: String,
    reachable: bool,
    avg_rtt_ms: f64,
    min_rtt_ms: f64,
    jitter_ms: f64,
    http_status: u16,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct EndpointBenchmarkResult {
    endpoint: String,
    reachable: bool,
    avg_rtt_ms: f64,
    jitter_ms: f64,
    probe_method: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct BenchmarkReport {
    doh_results: Vec<DohBenchmarkItem>,
    endpoint_result: Option<EndpointBenchmarkResult>,
    best_doh_url: Option<String>,
}

fn jstring_to_rust(env: &mut JNIEnv, input: JString) -> Option<String> {
    if input.is_null() {
        return None;
    }
    env.get_string(&input)
        .ok()
        .map(|s| s.to_string_lossy().into_owned())
}

fn rust_to_jstring(env: &mut JNIEnv, value: &str) -> jstring {
    env.new_string(value)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_example_bridge_NativeBridge_nativeValidateAndParseAwgConfig(
    mut env: JNIEnv,
    _class: JClass,
    config_text: JString,
) -> jstring {
    let raw = jstring_to_rust(&mut env, config_text).unwrap_or_default();
    let result = awg_config::validate_and_parse_config(&raw);
    let json = serde_json::to_string(&result).unwrap_or_else(|_| "{\"valid\":false}".to_string());
    rust_to_jstring(&mut env, &json)
}

#[no_mangle]
pub extern "system" fn Java_com_example_bridge_NativeBridge_nativeOptimizeAwgConfigForCodm(
    mut env: JNIEnv,
    _class: JClass,
    config_text: JString,
    enforce_split_tunnel: jboolean,
) -> jstring {
    let raw = jstring_to_rust(&mut env, config_text).unwrap_or_default();
    let split = enforce_split_tunnel == JNI_TRUE;
    let result = awg_config::optimize_for_codm_gaming(&raw, split);
    let json = serde_json::to_string(&result).unwrap_or_else(|_| "{\"success\":false}".to_string());
    rust_to_jstring(&mut env, &json)
}

#[no_mangle]
pub extern "system" fn Java_com_example_bridge_NativeBridge_nativeStartDohEngine(
    mut env: JNIEnv,
    _class: JClass,
    tun_fd: jint,
    doh_url: JString,
) -> jboolean {
    if tun_fd < 0 {
        return JNI_FALSE;
    }
    let url = jstring_to_rust(&mut env, doh_url)
        .filter(|s| !s.trim().is_empty())
        .unwrap_or_else(|| "https://1.1.1.1/dns-query".to_string());

    stop_active_doh_session();

    let Ok(state) = DohEngineState::new(url) else {
        return JNI_FALSE;
    };
    let state = Arc::new(state);
    let state_loop = Arc::clone(&state);

    let rt = get_runtime();
    let task = rt.spawn(async move {
        run_tun_doh_loop(tun_fd, state_loop).await;
    });

    if let Ok(mut slot) = get_doh_slot().lock() {
        *slot = Some(ActiveDohSession { state, task });
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_bridge_NativeBridge_nativeStopDohEngine(
    _env: JNIEnv,
    _class: JClass,
) {
    stop_active_doh_session();
}

#[no_mangle]
pub extern "system" fn Java_com_example_bridge_NativeBridge_nativeUpdateDohUrl(
    mut env: JNIEnv,
    _class: JClass,
    doh_url: JString,
) {
    if let Some(url) = jstring_to_rust(&mut env, doh_url) {
        if !url.trim().is_empty() {
            if let Ok(slot) = get_doh_slot().lock() {
                if let Some(ref session) = *slot {
                    session.state.update_url(url);
                }
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_bridge_NativeBridge_nativeGetDohStatsJson(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let stats = if let Ok(slot) = get_doh_slot().lock() {
        slot.as_ref().map(|s| s.state.snapshot_stats())
    } else {
        None
    };

    let json = match stats {
        Some(s) => serde_json::to_string(&s).unwrap_or_else(|_| "{}".to_string()),
        None => serde_json::to_string(&doh_engine::DohEngineStats {
            running: false,
            active_url: String::new(),
            total_queries: 0,
            cache_hits: 0,
            cache_misses: 0,
            failed_queries: 0,
            cache_entries: 0,
            last_rtt_ms: 0.0,
            avg_rtt_ms: 0.0,
        })
        .unwrap_or_else(|_| "{}".to_string()),
    };

    rust_to_jstring(&mut env, &json)
}

#[no_mangle]
pub extern "system" fn Java_com_example_bridge_NativeBridge_nativeBenchmarkDohAndEndpointJson(
    mut env: JNIEnv,
    _class: JClass,
    doh_urls_json: JString,
    endpoint_host_port: JString,
) -> jstring {
    let urls_raw = jstring_to_rust(&mut env, doh_urls_json).unwrap_or_else(|| "[]".to_string());
    let urls: Vec<String> = serde_json::from_str(&urls_raw).unwrap_or_default();
    let endpoint = jstring_to_rust(&mut env, endpoint_host_port).unwrap_or_default();

    let rt = get_runtime();
    let report = rt.block_on(run_concurrent_benchmarks(urls, endpoint));
    let json = serde_json::to_string(&report).unwrap_or_else(|_| "{}".to_string());
    rust_to_jstring(&mut env, &json)
}

fn stop_active_doh_session() {
    let existing = if let Ok(mut slot) = get_doh_slot().lock() {
        slot.take()
    } else {
        None
    };
    if let Some(session) = existing {
        session.state.stop();
        let _ = get_runtime().block_on(async {
            let _ = tokio::time::timeout(Duration::from_millis(400), session.task).await;
        });
    }
}

async fn run_concurrent_benchmarks(
    urls: Vec<String>,
    endpoint: String,
) -> BenchmarkReport {
    let client = reqwest::Client::builder()
        .use_rustls_tls()
        .timeout(Duration::from_secs(3))
        .connect_timeout(Duration::from_secs(2))
        .tcp_nodelay(true)
        .build();

    let mut handles = Vec::with_capacity(urls.len());
    if let Ok(client) = client {
        for url in urls {
            let c = client.clone();
            handles.push(tokio::spawn(async move { benchmark_single_doh(c, url).await }));
        }
    }

    let endpoint_handle = if !endpoint.trim().is_empty() {
        Some(tokio::spawn(async move { benchmark_endpoint(endpoint).await }))
    } else {
        None
    };

    let mut doh_results = Vec::new();
    for h in handles {
        if let Ok(item) = h.await {
            doh_results.push(item);
        }
    }

    // Sort reachable resolvers fastest-first
    doh_results.sort_by(|a, b| match (a.reachable, b.reachable) {
        (true, false) => std::cmp::Ordering::Less,
        (false, true) => std::cmp::Ordering::Greater,
        _ => a
            .avg_rtt_ms
            .partial_cmp(&b.avg_rtt_ms)
            .unwrap_or(std::cmp::Ordering::Equal),
    });

    let best_doh_url = doh_results
        .iter()
        .find(|d| d.reachable)
        .map(|d| d.url.clone());

    let endpoint_result = match endpoint_handle {
        Some(h) => h.await.ok(),
        None => None,
    };

    BenchmarkReport {
        doh_results,
        endpoint_result,
        best_doh_url,
    }
}

async fn benchmark_single_doh(client: reqwest::Client, url: String) -> DohBenchmarkItem {
    let Ok(wire_query) = build_dns_probe_query("prod.codm.activision.com.", 0xC0D1) else {
        return DohBenchmarkItem {
            url,
            reachable: false,
            avg_rtt_ms: 0.0,
            min_rtt_ms: 0.0,
            jitter_ms: 0.0,
            http_status: 0,
        };
    };

    let mut rtts = Vec::with_capacity(3);
    let mut last_status: u16 = 0;

    for idx in 0..3 {
        let started = Instant::now();
        let res = client
            .post(&url)
            .header("Content-Type", "application/dns-message")
            .header("Accept", "application/dns-message")
            .body(wire_query.clone())
            .send()
            .await;

        if let Ok(resp) = res {
            last_status = resp.status().as_u16();
            if resp.status().is_success() {
                if let Ok(bytes) = resp.bytes().await {
                    if validate_dns_wire_response(&bytes) {
                        let ms = started.elapsed().as_secs_f64() * 1000.0;
                        // Discard cold TLS/HTTP2 handshake spike on sample 0 if subsequent samples succeed
                        if idx > 0 || rtts.is_empty() {
                            rtts.push(ms);
                        }
                    }
                }
            }
        }
        tokio::time::sleep(Duration::from_millis(25)).await;
    }

    // Prefer warm HTTP/2 samples (index 1 & 2) for steady-state RTT when available
    let steady = if rtts.len() >= 2 { &rtts[1..] } else { &rtts[..] };
    if steady.is_empty() {
        return DohBenchmarkItem {
            url,
            reachable: false,
            avg_rtt_ms: 0.0,
            min_rtt_ms: 0.0,
            jitter_ms: 0.0,
            http_status: last_status,
        };
    }

    let avg = steady.iter().sum::<f64>() / steady.len() as f64;
    let min = steady.iter().copied().fold(f64::INFINITY, f64::min);
    let jitter = compute_jitter(steady);

    DohBenchmarkItem {
        url,
        reachable: true,
        avg_rtt_ms: round2(avg),
        min_rtt_ms: round2(min),
        jitter_ms: round2(jitter),
        http_status: last_status,
    }
}

/// Non-root endpoint RTT & jitter probe (never opens SOCK_RAW ICMP sockets).
/// Measures DNS lookup + non-root UDP socket datagram round-trip or TCP reachability.
async fn benchmark_endpoint(endpoint: String) -> EndpointBenchmarkResult {
    let trimmed = endpoint.trim().to_string();
    let mut rtts = Vec::with_capacity(4);
    let mut method = "TCP+DNS Non-Root Probe".to_string();

    for _ in 0..4 {
        let started = Instant::now();
        // Attempt non-root TCP handshake RTT first (works when peer/firewall responds or RSTs)
        let tcp_res = tokio::time::timeout(
            Duration::from_millis(1200),
            TcpStream::connect(&trimmed),
        )
        .await;

        match tcp_res {
            Ok(Ok(stream)) => {
                drop(stream);
                rtts.push(started.elapsed().as_secs_f64() * 1000.0);
                method = "TCP Handshake RTT".to_string();
            }
            Ok(Err(e)) => {
                // ConnectionRefused means the remote host/firewall actively replied with TCP RST
                // (common on WireGuard/AmneziaWG UDP-only servers), giving accurate path RTT!
                if e.kind() == std::io::ErrorKind::ConnectionRefused {
                    rtts.push(started.elapsed().as_secs_f64() * 1000.0);
                    method = "TCP RST Path RTT".to_string();
                }
            }
            Err(_) => {
                // Silent UDP firewall drop: fall back to DNS resolution + non-root UDP socket bind latency
                if let Ok(Ok(addrs)) = tokio::time::timeout(
                    Duration::from_millis(800),
                    tokio::net::lookup_host(&trimmed),
                )
                .await
                {
                    if let Some(addr) = addrs.into_iter().next() {
                        let udp_start = Instant::now();
                        if let Ok(sock) = tokio::net::UdpSocket::bind("0.0.0.0:0").await {
                            if sock.connect(addr).await.is_ok() {
                                let _ = sock.send(&[0x01, 0x00, 0x00, 0x00]).await;
                                rtts.push((started.elapsed() + udp_start.elapsed()).as_secs_f64() * 1000.0);
                                method = "UDP Route + DNS Probe".to_string();
                            }
                        }
                    }
                }
            }
        }
        tokio::time::sleep(Duration::from_millis(40)).await;
    }

    if rtts.is_empty() {
        EndpointBenchmarkResult {
            endpoint: trimmed,
            reachable: false,
            avg_rtt_ms: 0.0,
            jitter_ms: 0.0,
            probe_method: method,
        }
    } else {
        let avg = rtts.iter().sum::<f64>() / rtts.len() as f64;
        let jitter = compute_jitter(&rtts);
        EndpointBenchmarkResult {
            endpoint: trimmed,
            reachable: true,
            avg_rtt_ms: round2(avg),
            jitter_ms: round2(jitter),
            probe_method: method,
        }
    }
}

fn compute_jitter(samples: &[f64]) -> f64 {
    if samples.len() < 2 {
        return 0.0;
    }
    let mut delta_sum = 0.0;
    for w in samples.windows(2) {
        delta_sum += (w[1] - w[0]).abs();
    }
    delta_sum / (samples.len() - 1) as f64
}

fn round2(val: f64) -> f64 {
    (val * 100.0).round() / 100.0
}
