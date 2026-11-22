use base64::{engine::general_purpose::STANDARD, Engine as _};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HeaderSpec {
    pub raw: String,
    pub min: u32,
    pub max: u32,
    pub is_range: bool,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AwgParameters {
    pub jc: Option<u16>,
    pub jmin: Option<u16>,
    pub jmax: Option<u16>,
    pub s1: Option<u16>,
    pub s2: Option<u16>,
    pub s3: Option<u16>,
    pub s4: Option<u16>,
    pub h1: Option<HeaderSpec>,
    pub h2: Option<HeaderSpec>,
    pub h3: Option<HeaderSpec>,
    pub h4: Option<HeaderSpec>,
    pub i1: Option<String>,
    pub i2: Option<String>,
    pub i3: Option<String>,
    pub i4: Option<String>,
    pub i5: Option<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AwgSummary {
    pub has_private_key: bool,
    pub has_public_key: bool,
    pub endpoint: Option<String>,
    pub mtu: Option<u16>,
    pub persistent_keepalive: Option<u16>,
    pub addresses: Vec<String>,
    pub dns_servers: Vec<String>,
    pub allowed_ips: Vec<String>,
    pub included_applications: Vec<String>,
    pub excluded_applications: Vec<String>,
    pub is_amnezia_wg: bool,
    pub awg_version_hint: String,
    pub awg_params: AwgParameters,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AwgValidationResult {
    pub valid: bool,
    pub errors: Vec<String>,
    pub warnings: Vec<String>,
    pub summary: AwgSummary,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AwgOptimizeResult {
    pub success: bool,
    pub optimized_config: String,
    pub applied_mtu: u16,
    pub applied_keepalive: u16,
    pub split_tunnel_codm_applied: bool,
    pub changes: Vec<String>,
    pub validation: AwgValidationResult,
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum Section {
    None,
    Interface,
    Peer,
}

#[derive(Debug, Clone)]
struct ConfigLine {
    section: Section,
    raw: String,
    key: Option<String>,
    value: Option<String>,
}

pub fn validate_and_parse_config(raw_text: &str) -> AwgValidationResult {
    let mut errors = Vec::new();
    let mut warnings = Vec::new();
    let mut summary = AwgSummary::default();

    let mut current_section = Section::None;
    let mut interface_map: HashMap<String, String> = HashMap::new();
    let mut peer_map: HashMap<String, String> = HashMap::new();
    let mut peer_count: usize = 0;

    for (line_idx, raw_line) in raw_text.lines().enumerate() {
        let line_num = line_idx + 1;
        let trimmed = raw_line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') || trimmed.starts_with(';') {
            continue;
        }

        if trimmed.starts_with('[') && trimmed.ends_with(']') {
            let header = &trimmed[1..trimmed.len() - 1].trim().to_ascii_lowercase();
            match header.as_str() {
                "interface" => current_section = Section::Interface,
                "peer" => {
                    peer_count += 1;
                    current_section = Section::Peer;
                }
                other => {
                    errors.push(format!("Line {line_num}: Unknown section [{other}]"));
                    current_section = Section::None;
                }
            }
            continue;
        }

        let Some((k, v)) = trimmed.split_once('=') else {
            errors.push(format!("Line {line_num}: Expected Key = Value syntax"));
            continue;
        };

        let key = k.trim().to_string();
        let key_lower = key.to_ascii_lowercase();
        let val = v.trim().to_string();

        match current_section {
            Section::Interface => {
                interface_map.insert(key_lower, val);
            }
            Section::Peer => {
                if peer_count == 1 {
                    peer_map.insert(key_lower, val);
                }
            }
            Section::None => {
                errors.push(format!("Line {line_num}: Key '{key}' found outside [Interface] or [Peer]"));
            }
        }
    }

    if interface_map.is_empty() {
        errors.push("Missing [Interface] section".to_string());
    }
    if peer_count == 0 {
        errors.push("Missing [Peer] section".to_string());
    } else if peer_count > 1 {
        warnings.push("Multiple [Peer] sections detected; optimizing primary peer".to_string());
    }

    // Validate [Interface] PrivateKey (32-byte Curve25519 Base64)
    if let Some(pk) = interface_map.get("privatekey") {
        summary.has_private_key = true;
        if let Err(e) = validate_wg_key(pk) {
            errors.push(format!("Invalid Interface PrivateKey: {e}"));
        }
    } else {
        errors.push("Missing [Interface] PrivateKey".to_string());
    }

    // Validate Address
    if let Some(addr_raw) = interface_map.get("address") {
        let addrs: Vec<String> = split_csv(addr_raw);
        if addrs.is_empty() {
            errors.push("[Interface] Address cannot be empty".to_string());
        } else {
            for a in &addrs {
                if !is_valid_cidr_or_ip(a) {
                    errors.push(format!("Invalid [Interface] Address CIDR: '{a}'"));
                }
            }
            summary.addresses = addrs;
        }
    } else {
        errors.push("Missing [Interface] Address".to_string());
    }

    // Validate DNS (optional)
    if let Some(dns_raw) = interface_map.get("dns") {
        let dns_list = split_csv(dns_raw);
        for d in &dns_list {
            if d.parse::<IpAddr>().is_err() {
                warnings.push(format!("Non-IP DNS entry '{d}' in [Interface] DNS"));
            }
        }
        summary.dns_servers = dns_list;
    }

    // Validate MTU (optional)
    if let Some(mtu_raw) = interface_map.get("mtu") {
        match mtu_raw.parse::<u16>() {
            Ok(mtu) if (1280..=1500).contains(&mtu) => summary.mtu = Some(mtu),
            Ok(mtu) => {
                warnings.push(format!("MTU {mtu} is outside recommended 1280..1500 range"));
                summary.mtu = Some(mtu);
            }
            Err(_) => errors.push(format!("Invalid [Interface] MTU: '{mtu_raw}'")),
        }
    }

    // Validate ListenPort (optional)
    if let Some(port_raw) = interface_map.get("listenport") {
        match port_raw.parse::<u16>() {
            Ok(p) if p > 0 => {}
            _ => errors.push(format!("Invalid [Interface] ListenPort: '{port_raw}'")),
        }
    }

    // Split tunneling fields
    if let Some(inc) = interface_map.get("includedapplications") {
        summary.included_applications = split_csv(inc);
    }
    if let Some(exc) = interface_map.get("excludedapplications") {
        summary.excluded_applications = split_csv(exc);
    }

    // Validate AmneziaWG 1.5 / 2.0 parameters in [Interface]
    let mut awg = AwgParameters::default();
    awg.jc = parse_opt_u16(&interface_map, "jc", 0, 128, "Jc", &mut errors);
    awg.jmin = parse_opt_u16(&interface_map, "jmin", 0, 1280, "Jmin", &mut errors);
    awg.jmax = parse_opt_u16(&interface_map, "jmax", 0, 1280, "Jmax", &mut errors);

    if let (Some(jmin), Some(jmax)) = (awg.jmin, awg.jmax) {
        if jmin > jmax {
            errors.push(format!("AmneziaWG Jmin ({jmin}) cannot exceed Jmax ({jmax})"));
        }
    }
    if awg.jc.unwrap_or(0) > 16 {
        warnings.push("Jc > 16 may add handshake/burst overhead for competitive gaming".to_string());
    }

    awg.s1 = parse_opt_u16(&interface_map, "s1", 0, 1132, "S1", &mut errors);
    awg.s2 = parse_opt_u16(&interface_map, "s2", 0, 1188, "S2", &mut errors);
    awg.s3 = parse_opt_u16(&interface_map, "s3", 0, 1280, "S3", &mut errors);
    awg.s4 = parse_opt_u16(&interface_map, "s4", 0, 1280, "S4", &mut errors);

    if let (Some(s1), Some(s2)) = (awg.s1, awg.s2) {
        if s1 as usize + 56 == s2 as usize {
            errors.push(format!(
                "AmneziaWG constraint violated: S1 + 56 ({}) must not equal S2 ({s2})",
                s1 + 56
            ));
        }
    }

    awg.h1 = parse_header_spec(&interface_map, "h1", "H1", &mut errors);
    awg.h2 = parse_header_spec(&interface_map, "h2", "H2", &mut errors);
    awg.h3 = parse_header_spec(&interface_map, "h3", "H3", &mut errors);
    awg.h4 = parse_header_spec(&interface_map, "h4", "H4", &mut errors);

    // Check H1..H4 overlap
    let headers: Vec<(&str, &HeaderSpec)> = [
        ("H1", awg.h1.as_ref()),
        ("H2", awg.h2.as_ref()),
        ("H3", awg.h3.as_ref()),
        ("H4", awg.h4.as_ref()),
    ]
    .into_iter()
    .filter_map(|(name, opt)| opt.map(|h| (name, h)))
    .collect();

    for i in 0..headers.len() {
        for j in (i + 1)..headers.len() {
            let (n1, h1) = headers[i];
            let (n2, h2) = headers[j];
            if h1.min <= h2.max && h2.min <= h1.max {
                errors.push(format!(
                    "Header signatures {n1} ({}) and {n2} ({}) overlap",
                    h1.raw, h2.raw
                ));
            }
        }
    }

    // Validate CPS Protocol Mimicry I1..I5
    awg.i1 = parse_cps_packet(&interface_map, "i1", "I1", &mut errors);
    awg.i2 = parse_cps_packet(&interface_map, "i2", "I2", &mut errors);
    awg.i3 = parse_cps_packet(&interface_map, "i3", "I3", &mut errors);
    awg.i4 = parse_cps_packet(&interface_map, "i4", "I4", &mut errors);
    awg.i5 = parse_cps_packet(&interface_map, "i5", "I5", &mut errors);

    let has_v2_features = awg.s3.is_some()
        || awg.s4.is_some()
        || awg.h1.as_ref().is_some_and(|h| h.is_range)
        || awg.h2.as_ref().is_some_and(|h| h.is_range)
        || awg.h3.as_ref().is_some_and(|h| h.is_range)
        || awg.h4.as_ref().is_some_and(|h| h.is_range);

    let has_v15_features = awg.i1.is_some()
        || awg.i2.is_some()
        || awg.i3.is_some()
        || awg.i4.is_some()
        || awg.i5.is_some();

    let has_basic_awg = awg.jc.is_some()
        || awg.jmin.is_some()
        || awg.jmax.is_some()
        || awg.s1.is_some()
        || awg.s2.is_some()
        || awg.h1.is_some();

    summary.is_amnezia_wg = has_v2_features || has_v15_features || has_basic_awg;
    summary.awg_version_hint = if has_v2_features {
        "AmneziaWG 2.0".to_string()
    } else if has_v15_features {
        "AmneziaWG 1.5 (CPS)".to_string()
    } else if has_basic_awg {
        "AmneziaWG 1.0".to_string()
    } else {
        "Standard WireGuard".to_string()
    };
    summary.awg_params = awg;

    // Validate [Peer] fields
    if let Some(pub_key) = peer_map.get("publickey") {
        summary.has_public_key = true;
        if let Err(e) = validate_wg_key(pub_key) {
            errors.push(format!("Invalid [Peer] PublicKey: {e}"));
        }
    } else {
        errors.push("Missing [Peer] PublicKey".to_string());
    }

    if let Some(psk) = peer_map.get("presharedkey") {
        if let Err(e) = validate_wg_key(psk) {
            errors.push(format!("Invalid [Peer] PresharedKey: {e}"));
        }
    }

    if let Some(ep) = peer_map.get("endpoint") {
        summary.endpoint = Some(ep.clone());
        if !is_valid_endpoint(ep) {
            errors.push(format!("Invalid [Peer] Endpoint '{ep}' (expected host:port)"));
        }
    } else {
        warnings.push("No [Peer] Endpoint specified".to_string());
    }

    if let Some(aips_raw) = peer_map.get("allowedips") {
        let aips = split_csv(aips_raw);
        if aips.is_empty() {
            errors.push("[Peer] AllowedIPs cannot be empty".to_string());
        } else {
            for cidr in &aips {
                if !is_valid_cidr_or_ip(cidr) {
                    errors.push(format!("Invalid [Peer] AllowedIPs CIDR: '{cidr}'"));
                }
            }
            summary.allowed_ips = aips;
        }
    } else {
        errors.push("Missing [Peer] AllowedIPs".to_string());
    }

    if let Some(ka_raw) = peer_map.get("persistentkeepalive") {
        match ka_raw.parse::<u16>() {
            Ok(ka) => {
                summary.persistent_keepalive = Some(ka);
                if ka == 0 || ka > 30 {
                    warnings.push(format!(
                        "PersistentKeepalive={ka} may allow CGNAT timeout during CODM matches; 25s recommended"
                    ));
                }
            }
            Err(_) => errors.push(format!("Invalid PersistentKeepalive: '{ka_raw}'")),
        }
    } else {
        warnings.push("PersistentKeepalive is missing; 25s recommended for mobile CGNAT stability".to_string());
    }

    AwgValidationResult {
        valid: errors.is_empty(),
        errors,
        warnings,
        summary,
    }
}

pub fn optimize_for_codm_gaming(
    raw_text: &str,
    enforce_codm_split_tunnel: bool,
) -> AwgOptimizeResult {
    let initial_validation = validate_and_parse_config(raw_text);
    let mut changes = Vec::new();

    // Compute gaming MTU in [1280, 1360] accounting for AmneziaWG S1/S2/S4 + WG overhead.
    let s1 = initial_validation.summary.awg_params.s1.unwrap_or(0);
    let s2 = initial_validation.summary.awg_params.s2.unwrap_or(0);
    let s4 = initial_validation.summary.awg_params.s4.unwrap_or(0);
    let max_transport_pad = s4.max(s1 / 2).max(s2 / 4).min(80);
    // Mobile LTE/5G path MTU (~1420) - 80B WG/IPv6/UDP envelope - padding, clamped to [1280, 1360]
    let computed_mtu = (1360u16.saturating_sub(max_transport_pad)).clamp(1280, 1360);
    let applied_keepalive: u16 = 25;

    let parsed_lines = parse_config_lines(raw_text);
    let mut output_lines: Vec<String> = Vec::with_capacity(parsed_lines.len() + 8);

    let mut has_interface = false;
    let mut has_peer = false;
    let mut interface_mtu_written = false;
    let mut interface_inc_written = false;
    let mut interface_exc_written = false;
    let mut peer_keepalive_written = false;

    // Track where sections end so we can inject missing keys cleanly
    let mut in_interface = false;
    let mut in_peer = false;

    for line in &parsed_lines {
        match line.section {
            Section::Interface => {
                if !in_interface && line.key.is_none() && line.raw.trim().eq_ignore_ascii_case("[Interface]") {
                    has_interface = true;
                    in_interface = true;
                    in_peer = false;
                    output_lines.push(line.raw.clone());
                    continue;
                }
                if let (Some(k), Some(_v)) = (&line.key, &line.value) {
                    let k_lower = k.to_ascii_lowercase();
                    if k_lower == "mtu" {
                        output_lines.push(format!("MTU = {computed_mtu}"));
                        interface_mtu_written = true;
                        changes.push(format!("Tuned MTU to {computed_mtu} (anti-fragmentation for mobile/CGNAT)"));
                        continue;
                    }
                    if enforce_codm_split_tunnel && k_lower == "includedapplications" {
                        output_lines.push("IncludedApplications = com.activision.callofduty.shooter".to_string());
                        interface_inc_written = true;
                        changes.push("Locked IncludedApplications to Call of Duty: Mobile".to_string());
                        continue;
                    }
                    if enforce_codm_split_tunnel && k_lower == "excludedapplications" {
                        // Do not mix IncludedApplications and ExcludedApplications
                        interface_exc_written = true;
                        changes.push("Removed ExcludedApplications in favor of strict CODM IncludedApplications".to_string());
                        continue;
                    }
                }
                output_lines.push(line.raw.clone());
            }
            Section::Peer => {
                if in_interface {
                    flush_interface_additions(
                        &mut output_lines,
                        &mut interface_mtu_written,
                        &mut interface_inc_written,
                        enforce_codm_split_tunnel,
                        computed_mtu,
                        &mut changes,
                    );
                    in_interface = false;
                }
                if !in_peer && line.key.is_none() && line.raw.trim().eq_ignore_ascii_case("[Peer]") {
                    has_peer = true;
                    in_peer = true;
                    output_lines.push(line.raw.clone());
                    continue;
                }
                if let (Some(k), Some(_v)) = (&line.key, &line.value) {
                    if k.eq_ignore_ascii_case("persistentkeepalive") {
                        output_lines.push(format!("PersistentKeepalive = {applied_keepalive}"));
                        peer_keepalive_written = true;
                        changes.push(format!("Enforced PersistentKeepalive = {applied_keepalive}s"));
                        continue;
                    }
                }
                output_lines.push(line.raw.clone());
            }
            Section::None => {
                output_lines.push(line.raw.clone());
            }
        }
    }

    if in_interface {
        flush_interface_additions(
            &mut output_lines,
            &mut interface_mtu_written,
            &mut interface_inc_written,
            enforce_codm_split_tunnel,
            computed_mtu,
            &mut changes,
        );
    }

    if in_peer && !peer_keepalive_written {
        output_lines.push(format!("PersistentKeepalive = {applied_keepalive}"));
        changes.push(format!("Added PersistentKeepalive = {applied_keepalive}s"));
    }

    let _ = (interface_exc_written, has_interface, has_peer);
    let optimized_config = output_lines.join("\n").trim_end().to_string() + "\n";
    let final_validation = validate_and_parse_config(&optimized_config);

    AwgOptimizeResult {
        success: final_validation.valid,
        optimized_config,
        applied_mtu: computed_mtu,
        applied_keepalive,
        split_tunnel_codm_applied: enforce_codm_split_tunnel,
        changes,
        validation: final_validation,
    }
}

fn flush_interface_additions(
    output_lines: &mut Vec<String>,
    mtu_written: &mut bool,
    inc_written: &mut bool,
    enforce_codm_split: bool,
    computed_mtu: u16,
    changes: &mut Vec<String>,
) {
    if !*mtu_written {
        output_lines.push(format!("MTU = {computed_mtu}"));
        *mtu_written = true;
        changes.push(format!("Added gaming MTU = {computed_mtu}"));
    }
    if enforce_codm_split && !*inc_written {
        output_lines.push("IncludedApplications = com.activision.callofduty.shooter".to_string());
        *inc_written = true;
        changes.push("Enabled Per-App Split Tunnel for CODM (com.activision.callofduty.shooter)".to_string());
    }
}

fn parse_config_lines(raw_text: &str) -> Vec<ConfigLine> {
    let mut section = Section::None;
    let mut out = Vec::new();
    for line in raw_text.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with('[') && trimmed.ends_with(']') {
            let hdr = trimmed[1..trimmed.len() - 1].trim().to_ascii_lowercase();
            section = match hdr.as_str() {
                "interface" => Section::Interface,
                "peer" => Section::Peer,
                _ => Section::None,
            };
            out.push(ConfigLine {
                section: section.clone(),
                raw: line.to_string(),
                key: None,
                value: None,
            });
            continue;
        }
        if !trimmed.is_empty() && !trimmed.starts_with('#') && !trimmed.starts_with(';') {
            if let Some((k, v)) = trimmed.split_once('=') {
                out.push(ConfigLine {
                    section: section.clone(),
                    raw: line.to_string(),
                    key: Some(k.trim().to_string()),
                    value: Some(v.trim().to_string()),
                });
                continue;
            }
        }
        out.push(ConfigLine {
            section: section.clone(),
            raw: line.to_string(),
            key: None,
            value: None,
        });
    }
    out
}

fn validate_wg_key(b64: &str) -> Result<(), String> {
    let decoded = STANDARD
        .decode(b64.trim())
        .map_err(|e| format!("invalid Base64 ({e})"))?;
    if decoded.len() != 32 {
        return Err(format!("expected 32 bytes, got {}", decoded.len()));
    }
    if decoded.iter().all(|&b| b == 0) {
        return Err("all-zero key is invalid".to_string());
    }
    Ok(())
}

fn split_csv(raw: &str) -> Vec<String> {
    raw.split(',')
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .collect()
}

fn is_valid_cidr_or_ip(input: &str) -> bool {
    let s = input.trim();
    if let Some((ip_part, prefix_part)) = s.split_once('/') {
        let Ok(prefix) = prefix_part.parse::<u8>() else {
            return false;
        };
        if ip_part.parse::<Ipv4Addr>().is_ok() {
            return prefix <= 32;
        }
        if ip_part.parse::<Ipv6Addr>().is_ok() {
            return prefix <= 128;
        }
        false
    } else {
        s.parse::<IpAddr>().is_ok()
    }
}

fn is_valid_endpoint(ep: &str) -> bool {
    let trimmed = ep.trim();
    if trimmed.starts_with('[') {
        let Some(end_bracket) = trimmed.find(']') else {
            return false;
        };
        let ip_part = &trimmed[1..end_bracket];
        if ip_part.parse::<Ipv6Addr>().is_err() {
            return false;
        }
        let rest = &trimmed[end_bracket + 1..];
        let Some(port_str) = rest.strip_prefix(':') else {
            return false;
        };
        return port_str.parse::<u16>().is_ok_and(|p| p > 0);
    }

    let Some((host, port_str)) = trimmed.rsplit_once(':') else {
        return false;
    };
    if host.is_empty() || host.contains(' ') {
        return false;
    }
    port_str.parse::<u16>().is_ok_and(|p| p > 0)
}

fn parse_opt_u16(
    map: &HashMap<String, String>,
    key_lower: &str,
    min: u16,
    max: u16,
    label: &str,
    errors: &mut Vec<String>,
) -> Option<u16> {
    let raw = map.get(key_lower)?;
    match raw.parse::<u16>() {
        Ok(v) if (min..=max).contains(&v) => Some(v),
        Ok(v) => {
            errors.push(format!("AmneziaWG {label}={v} out of range ({min}..={max})"));
            None
        }
        Err(_) => {
            errors.push(format!("AmneziaWG {label}='{raw}' must be an integer"));
            None
        }
    }
}

fn parse_header_spec(
    map: &HashMap<String, String>,
    key_lower: &str,
    label: &str,
    errors: &mut Vec<String>,
) -> Option<HeaderSpec> {
    let raw = map.get(key_lower)?.trim().to_string();
    if let Some((left, right)) = raw.split_once('-') {
        let Ok(min_v) = left.trim().parse::<u32>() else {
            errors.push(format!("AmneziaWG {label} range start invalid: '{raw}'"));
            return None;
        };
        let Ok(max_v) = right.trim().parse::<u32>() else {
            errors.push(format!("AmneziaWG {label} range end invalid: '{raw}'"));
            return None;
        };
        if min_v > max_v {
            errors.push(format!("AmneziaWG {label} range min > max: '{raw}'"));
            return None;
        }
        if min_v < 5 {
            errors.push(format!("AmneziaWG {label} range must be >= 5 to avoid WireGuard collision"));
            return None;
        }
        Some(HeaderSpec {
            raw,
            min: min_v,
            max: max_v,
            is_range: true,
        })
    } else {
        match raw.parse::<u32>() {
            Ok(v) if v >= 5 => Some(HeaderSpec {
                raw,
                min: v,
                max: v,
                is_range: false,
            }),
            Ok(_) => {
                errors.push(format!("AmneziaWG {label} must be >= 5"));
                None
            }
            Err(_) => {
                errors.push(format!("AmneziaWG {label}='{raw}' must be u32 or min-max range"));
                None
            }
        }
    }
}

fn parse_cps_packet(
    map: &HashMap<String, String>,
    key_lower: &str,
    label: &str,
    errors: &mut Vec<String>,
) -> Option<String> {
    let raw = map.get(key_lower)?.trim().to_string();
    if raw.is_empty() {
        errors.push(format!("AmneziaWG CPS {label} cannot be empty"));
        return None;
    }
    // Validate balanced <...> CPS tags if tag syntax is used
    if raw.contains('<') {
        let mut depth = 0i32;
        for ch in raw.chars() {
            match ch {
                '<' => depth += 1,
                '>' => {
                    depth -= 1;
                    if depth < 0 {
                        errors.push(format!("AmneziaWG CPS {label} has unmatched '>'"));
                        return None;
                    }
                }
                _ => {}
            }
        }
        if depth != 0 {
            errors.push(format!("AmneziaWG CPS {label} has unclosed '<' tag"));
            return None;
        }
    }
    Some(raw)
}
