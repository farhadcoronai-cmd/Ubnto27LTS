use hickory_proto::op::{Message, ResponseCode};
use hickory_proto::rr::RecordType;
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, VecDeque};
use std::fs::File;
use std::io::{Read, Write};
use std::os::unix::io::FromRawFd;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant};
use tokio::io::unix::AsyncFd;
use tokio::sync::Notify;

const DNS_CACHE_MAX_ENTRIES: usize = 512;
const MIN_CACHE_TTL_SECS: u64 = 15;
const MAX_CACHE_TTL_SECS: u64 = 3600;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DohEngineStats {
    pub running: bool,
    pub active_url: String,
    pub total_queries: u64,
    pub cache_hits: u64,
    pub cache_misses: u64,
    pub failed_queries: u64,
    pub cache_entries: usize,
    pub last_rtt_ms: f64,
    pub avg_rtt_ms: f64,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
struct CacheKey {
    qname: String,
    qtype: u16,
    qclass: u16,
}

#[derive(Debug, Clone)]
struct CacheEntry {
    wire_bytes: Vec<u8>,
    inserted_at: Instant,
    ttl_secs: u64,
}

struct TtlLruCache {
    map: HashMap<CacheKey, CacheEntry>,
    order: VecDeque<CacheKey>,
}

impl TtlLruCache {
    fn new() -> Self {
        Self {
            map: HashMap::with_capacity(DNS_CACHE_MAX_ENTRIES),
            order: VecDeque::with_capacity(DNS_CACHE_MAX_ENTRIES),
        }
    }

    fn get(&mut self, key: &CacheKey, query_id: u16) -> Option<Vec<u8>> {
        let now = Instant::now();
        let entry = self.map.get(key)?;
        let elapsed = now.duration_since(entry.inserted_at).as_secs();
        if elapsed >= entry.ttl_secs {
            self.map.remove(key);
            self.order.retain(|k| k != key);
            return None;
        }

        let mut wire = entry.wire_bytes.clone();
        if wire.len() < 2 {
            return None;
        }
        wire[0..2].copy_from_slice(&query_id.to_be_bytes());

        // Promote to MRU
        self.order.retain(|k| k != key);
        self.order.push_back(key.clone());
        Some(wire)
    }

    fn insert(&mut self, key: CacheKey, wire_bytes: Vec<u8>, ttl_secs: u64) {
        if wire_bytes.len() < 12 {
            return;
        }
        if self.map.contains_key(&key) {
            self.order.retain(|k| k != &key);
        } else if self.map.len() >= DNS_CACHE_MAX_ENTRIES {
            if let Some(oldest) = self.order.pop_front() {
                self.map.remove(&oldest);
            }
        }
        self.order.push_back(key.clone());
        self.map.insert(
            key,
            CacheEntry {
                wire_bytes,
                inserted_at: Instant::now(),
                ttl_secs,
            },
        );
    }

    fn len(&self) -> usize {
        self.map.len()
    }
}

pub struct DohEngineState {
    running: AtomicBool,
    stop_notify: Notify,
    active_url: RwLock<String>,
    total_queries: AtomicU64,
    cache_hits: AtomicU64,
    cache_misses: AtomicU64,
    failed_queries: AtomicU64,
    last_rtt_us: AtomicU64,
    total_rtt_us: AtomicU64,
    rtt_samples: AtomicU64,
    cache: Mutex<TtlLruCache>,
    http_client: reqwest::Client,
}

impl DohEngineState {
    pub fn new(initial_url: String) -> Result<Self, String> {
        let http_client = reqwest::Client::builder()
            .use_rustls_tls()
            .timeout(Duration::from_secs(4))
            .connect_timeout(Duration::from_secs(3))
            .pool_max_idle_per_host(4)
            .tcp_nodelay(true)
            .build()
            .map_err(|e| format!("Failed to build rustls HTTP/2 client: {e}"))?;

        Ok(Self {
            running: AtomicBool::new(true),
            stop_notify: Notify::new(),
            active_url: RwLock::new(initial_url),
            total_queries: AtomicU64::new(0),
            cache_hits: AtomicU64::new(0),
            cache_misses: AtomicU64::new(0),
            failed_queries: AtomicU64::new(0),
            last_rtt_us: AtomicU64::new(0),
            total_rtt_us: AtomicU64::new(0),
            rtt_samples: AtomicU64::new(0),
            cache: Mutex::new(TtlLruCache::new()),
            http_client,
        })
    }

    pub fn update_url(&self, new_url: String) {
        if let Ok(mut guard) = self.active_url.write() {
            *guard = new_url;
        }
    }

    pub fn stop(&self) {
        self.running.store(false, Ordering::Release);
        self.stop_notify.notify_waiters();
    }

    pub fn snapshot_stats(&self) -> DohEngineStats {
        let samples = self.rtt_samples.load(Ordering::Relaxed);
        let avg_rtt_ms = if samples > 0 {
            (self.total_rtt_us.load(Ordering::Relaxed) as f64 / samples as f64) / 1000.0
        } else {
            0.0
        };
        let last_rtt_ms = (self.last_rtt_us.load(Ordering::Relaxed) as f64) / 1000.0;
        let cache_entries = self.cache.lock().map(|c| c.len()).unwrap_or(0);
        let active_url = self
            .active_url
            .read()
            .map(|u| u.clone())
            .unwrap_or_default();

        DohEngineStats {
            running: self.running.load(Ordering::Acquire),
            active_url,
            total_queries: self.total_queries.load(Ordering::Relaxed),
            cache_hits: self.cache_hits.load(Ordering::Relaxed),
            cache_misses: self.cache_misses.load(Ordering::Relaxed),
            failed_queries: self.failed_queries.load(Ordering::Relaxed),
            cache_entries,
            last_rtt_ms,
            avg_rtt_ms,
        }
    }
}

pub async fn run_tun_doh_loop(tun_fd: i32, state: Arc<DohEngineState>) {
    // Set TUN fd to non-blocking so AsyncFd integrates cleanly with 2-thread Tokio runtime
    unsafe {
        let flags = libc::fcntl(tun_fd, libc::F_GETFL);
        if flags >= 0 {
            libc::fcntl(tun_fd, libc::F_SETFL, flags | libc::O_NONBLOCK);
        }
    }

    let file = unsafe { File::from_raw_fd(tun_fd) };
    let Ok(async_tun) = AsyncFd::new(file) else {
        state.running.store(false, Ordering::Release);
        return;
    };
    let async_tun = Arc::new(async_tun);

    let mut buf = vec![0u8; 4096];

    while state.running.load(Ordering::Acquire) {
        let read_result = tokio::select! {
            _ = state.stop_notify.notified() => break,
            guard_res = async_tun.readable() => {
                let Ok(mut guard) = guard_res else {
                    break;
                };
                match guard.try_io(|inner| (&*inner.get_ref()).read(&mut buf)) {
                    Ok(Ok(n)) => Some(n),
                    Ok(Err(_)) => break,
                    Err(_would_block) => None,
                }
            }
        };

        let Some(pkt_len) = read_result else {
            continue;
        };
        if pkt_len == 0 {
            break;
        }

        let Some(parsed) = parse_ipv4_udp_dns(&buf[..pkt_len]) else {
            continue;
        };

        let state_clone = Arc::clone(&state);
        let tun_clone = Arc::clone(&async_tun);
        tokio::spawn(async move {
            handle_dns_packet(parsed, state_clone, tun_clone).await;
        });
    }

    state.running.store(false, Ordering::Release);
}

#[derive(Debug, Clone)]
struct ParsedUdpDnsPacket {
    src_ip: [u8; 4],
    dst_ip: [u8; 4],
    src_port: u16,
    dst_port: u16,
    dns_payload: Vec<u8>,
}

fn parse_ipv4_udp_dns(pkt: &[u8]) -> Option<ParsedUdpDnsPacket> {
    if pkt.len() < 28 {
        return None;
    }
    let version = pkt[0] >> 4;
    if version != 4 {
        return None;
    }
    let ihl = ((pkt[0] & 0x0F) as usize) * 4;
    if ihl < 20 || pkt.len() < ihl + 8 {
        return None;
    }
    let total_len = u16::from_be_bytes([pkt[2], pkt[3]]) as usize;
    if total_len < ihl + 8 || total_len > pkt.len() {
        return None;
    }
    // UDP protocol = 17
    if pkt[9] != 17 {
        return None;
    }

    let mut src_ip = [0u8; 4];
    let mut dst_ip = [0u8; 4];
    src_ip.copy_from_slice(&pkt[12..16]);
    dst_ip.copy_from_slice(&pkt[16..20]);

    let src_port = u16::from_be_bytes([pkt[ihl], pkt[ihl + 1]]);
    let dst_port = u16::from_be_bytes([pkt[ihl + 2], pkt[ihl + 3]]);
    if dst_port != 53 {
        return None;
    }

    let udp_len = u16::from_be_bytes([pkt[ihl + 4], pkt[ihl + 5]]) as usize;
    if udp_len < 8 || ihl + udp_len > total_len {
        return None;
    }

    let dns_payload = pkt[ihl + 8..ihl + udp_len].to_vec();
    if dns_payload.len() < 12 {
        return None;
    }

    Some(ParsedUdpDnsPacket {
        src_ip,
        dst_ip,
        src_port,
        dst_port,
        dns_payload,
    })
}

async fn handle_dns_packet(
    pkt: ParsedUdpDnsPacket,
    state: Arc<DohEngineState>,
    async_tun: Arc<AsyncFd<File>>,
) {
    state.total_queries.fetch_add(1, Ordering::Relaxed);

    let Ok(req_msg) = Message::from_vec(&pkt.dns_payload) else {
        state.failed_queries.fetch_add(1, Ordering::Relaxed);
        return;
    };

    let query_id = req_msg.id();
    let cache_key = req_msg.queries().first().map(|q| CacheKey {
        qname: q.name().to_ascii().to_ascii_lowercase(),
        qtype: u16::from(q.query_type()),
        qclass: u16::from(q.query_class()),
    });

    // 1. Check TTL-aware LRU DNS cache
    if let Some(ref key) = cache_key {
        if let Ok(mut guard) = state.cache.lock() {
            if let Some(cached_wire) = guard.get(key, query_id) {
                state.cache_hits.fetch_add(1, Ordering::Relaxed);
                state.last_rtt_us.store(0, Ordering::Relaxed);
                let reply_pkt = build_ipv4_udp_reply(&pkt, &cached_wire);
                write_packet_to_tun(&async_tun, &reply_pkt).await;
                return;
            }
        }
    }

    state.cache_misses.fetch_add(1, Ordering::Relaxed);

    // 2. Dispatch RFC 8484 HTTP/2 POST over rustls
    let url = state
        .active_url
        .read()
        .map(|u| u.clone())
        .unwrap_or_else(|_| "https://1.1.1.1/dns-query".to_string());

    let started = Instant::now();
    let resp_result = state
        .http_client
        .post(&url)
        .header("Content-Type", "application/dns-message")
        .header("Accept", "application/dns-message")
        .body(pkt.dns_payload.clone())
        .send()
        .await;

    let reply_wire = match resp_result {
        Ok(resp) if resp.status().is_success() => match resp.bytes().await {
            Ok(bytes) => {
                let elapsed_us = started.elapsed().as_micros() as u64;
                state.last_rtt_us.store(elapsed_us, Ordering::Relaxed);
                state.total_rtt_us.fetch_add(elapsed_us, Ordering::Relaxed);
                state.rtt_samples.fetch_add(1, Ordering::Relaxed);

                let wire_vec = bytes.to_vec();
                if let (Some(key), Ok(parsed_resp)) = (cache_key, Message::from_vec(&wire_vec)) {
                    if parsed_resp.response_code() == ResponseCode::NoError
                        && !parsed_resp.answers().is_empty()
                    {
                        let min_ttl = parsed_resp
                            .answers()
                            .iter()
                            .map(|r| r.ttl() as u64)
                            .min()
                            .unwrap_or(60)
                            .clamp(MIN_CACHE_TTL_SECS, MAX_CACHE_TTL_SECS);
                        if let Ok(mut guard) = state.cache.lock() {
                            guard.insert(key, wire_vec.clone(), min_ttl);
                        }
                    }
                }
                wire_vec
            }
            Err(_) => {
                state.failed_queries.fetch_add(1, Ordering::Relaxed);
                build_servfail(&req_msg)
            }
        },
        _ => {
            state.failed_queries.fetch_add(1, Ordering::Relaxed);
            build_servfail(&req_msg)
        }
    };

    if !reply_wire.is_empty() {
        let reply_pkt = build_ipv4_udp_reply(&pkt, &reply_wire);
        write_packet_to_tun(&async_tun, &reply_pkt).await;
    }
}

fn build_servfail(req: &Message) -> Vec<u8> {
    let mut err_msg = Message::error_msg(req.id(), req.op_code(), ResponseCode::ServFail);
    for q in req.queries() {
        err_msg.add_query(q.clone());
    }
    err_msg.to_vec().unwrap_or_default()
}

async fn write_packet_to_tun(async_tun: &AsyncFd<File>, packet: &[u8]) {
    for _ in 0..4 {
        let Ok(mut guard) = async_tun.writable().await else {
            return;
        };
        match guard.try_io(|inner| (&*inner.get_ref()).write(packet)) {
            Ok(Ok(_)) => return,
            Ok(Err(_)) => return,
            Err(_would_block) => continue,
        }
    }
}

fn build_ipv4_udp_reply(req: &ParsedUdpDnsPacket, dns_reply: &[u8]) -> Vec<u8> {
    let udp_len = (8 + dns_reply.len()) as u16;
    let total_len = 20 + udp_len;

    let mut pkt = Vec::with_capacity(total_len as usize);

    // IPv4 Header (20 bytes)
    pkt.push(0x45); // Version 4, IHL 5
    pkt.push(0x00); // DSCP/ECN
    pkt.extend_from_slice(&total_len.to_be_bytes());
    pkt.extend_from_slice(&0u16.to_be_bytes()); // Identification
    pkt.extend_from_slice(&0x4000u16.to_be_bytes()); // Flags (DF) + Fragment Offset
    pkt.push(64); // TTL
    pkt.push(17); // Protocol: UDP
    pkt.extend_from_slice(&0u16.to_be_bytes()); // Header Checksum placeholder
    pkt.extend_from_slice(&req.dst_ip); // Reply Src IP = Request Dst IP
    pkt.extend_from_slice(&req.src_ip); // Reply Dst IP = Request Src IP

    let ip_csum = ipv4_checksum(&pkt[..20]);
    pkt[10..12].copy_from_slice(&ip_csum.to_be_bytes());

    // UDP Header (8 bytes)
    pkt.extend_from_slice(&req.dst_port.to_be_bytes()); // Src port 53
    pkt.extend_from_slice(&req.src_port.to_be_bytes()); // Dst port
    pkt.extend_from_slice(&udp_len.to_be_bytes());
    pkt.extend_from_slice(&0u16.to_be_bytes()); // UDP Checksum placeholder
    pkt.extend_from_slice(dns_reply);

    let udp_csum = udp_ipv4_checksum(&req.dst_ip, &req.src_ip, &pkt[20..]);
    pkt[26..28].copy_from_slice(&udp_csum.to_be_bytes());

    pkt
}

fn ipv4_checksum(header: &[u8]) -> u16 {
    let mut sum: u32 = 0;
    for chunk in header.chunks_exact(2) {
        sum += u16::from_be_bytes([chunk[0], chunk[1]]) as u32;
    }
    while (sum >> 16) != 0 {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    !(sum as u16)
}

fn udp_ipv4_checksum(src_ip: &[u8; 4], dst_ip: &[u8; 4], udp_segment: &[u8]) -> u16 {
    let mut sum: u32 = 0;

    // Pseudo-header
    sum += u16::from_be_bytes([src_ip[0], src_ip[1]]) as u32;
    sum += u16::from_be_bytes([src_ip[2], src_ip[3]]) as u32;
    sum += u16::from_be_bytes([dst_ip[0], dst_ip[1]]) as u32;
    sum += u16::from_be_bytes([dst_ip[2], dst_ip[3]]) as u32;
    sum += 17u32; // Protocol UDP
    sum += udp_segment.len() as u32;

    let mut i = 0;
    while i + 1 < udp_segment.len() {
        sum += u16::from_be_bytes([udp_segment[i], udp_segment[i + 1]]) as u32;
        i += 2;
    }
    if i < udp_segment.len() {
        sum += (udp_segment[i] as u32) << 8;
    }

    while (sum >> 16) != 0 {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }

    let csum = !(sum as u16);
    if csum == 0 {
        0xFFFF
    } else {
        csum
    }
}

pub fn build_dns_probe_query(domain: &str, id: u16) -> Result<Vec<u8>, String> {
    let name = hickory_proto::rr::Name::from_ascii(domain)
        .map_err(|e| format!("Invalid domain '{domain}': {e}"))?;
    let mut msg = Message::new();
    msg.set_id(id);
    msg.set_message_type(hickory_proto::op::MessageType::Query);
    msg.set_op_code(hickory_proto::op::OpCode::Query);
    msg.set_recursion_desired(true);
    msg.add_query(hickory_proto::op::Query::query(name, RecordType::A));
    msg.to_vec()
        .map_err(|e| format!("DNS query encode failed: {e}"))
}

pub fn validate_dns_wire_response(bytes: &[u8]) -> bool {
    Message::from_vec(bytes).is_ok()
}
