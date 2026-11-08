use serde::Deserialize;
use std::collections::{BTreeSet, HashMap, VecDeque};

pub(crate) const MAX_LIVE_DEVICES: usize = 8;
pub(crate) const MAX_FRAME_FEATURES: usize = 16;
pub(crate) const LIVE_CADENCE_MS: u64 = 33;

pub(crate) fn normalize_vibration(value: f64, steps: u32) -> f64 {
    if !value.is_finite() { return 0.0; }
    let requested = value.clamp(0.0, 1.0);
    if requested <= 0.05 { return 0.0; }
    let quantized = if steps > 0 { (requested * steps as f64).round() / steps as f64 } else { requested };
    if quantized <= 0.05 { 0.0 } else { quantized }
}

/// Buttplug uses ceil(value * steps). Keep canonical step fractions just below
/// their exact boundary so floating-point multiplication cannot add a step.
pub(crate) fn vibration_wire_value(value: f64, steps: u32) -> f64 {
    let normalized = normalize_vibration(value, steps);
    if steps == 0 || normalized == 0.0 { return normalized; }
    let step = (normalized * steps as f64).round();
    let mut wire = normalized;
    while wire * steps as f64 > step { wire = f64::from_bits(wire.to_bits() - 1); }
    wire
}

#[derive(Clone, Debug, Deserialize, PartialEq)]
#[serde(deny_unknown_fields)]
pub(crate) struct OutputValue {
    pub index: u32,
    pub value: f64,
}

pub(crate) fn validate_values(values: &[OutputValue]) -> Result<(), String> {
    if values.is_empty() || values.len() > MAX_FRAME_FEATURES {
        return Err(format!("A vibration frame requires 1–{MAX_FRAME_FEATURES} values"));
    }
    let mut indices = BTreeSet::new();
    for value in values {
        if !value.value.is_finite() || !(0.0..=1.0).contains(&value.value) {
            return Err("Vibration output must be between 0 and 100 percent".into());
        }
        if !indices.insert(value.index) {
            return Err("Duplicate vibration feature index".into());
        }
    }
    Ok(())
}

#[derive(Clone, Debug)]
pub(crate) struct LiveFrame {
    pub enqueued_at: std::time::Instant,
    pub epoch: u64,
    pub device: u32,
    pub request: u64,
    pub values: Vec<OutputValue>,
}

pub(crate) struct PendingFrame {
    pub frame: LiveFrame,
    // A mandatory release can be synthesized from the newest frame. Do not
    // acknowledge that frame's request ID until its complete values are applied.
    pub release_barrier: bool,
}

struct DeviceState {
    desired: LiveFrame,
    releases: BTreeSet<u32>,
    queued: bool,
    next_ready_ms: u64,
}

/// One latest frame per device, rather than a FIFO of obsolete finger positions.
/// Zero transitions remain mandatory even if a fresh touch arrives before I/O.
#[derive(Default)]
pub(crate) struct LiveQueue {
    devices: HashMap<u32, DeviceState>,
    order: VecDeque<u32>,
}

impl LiveQueue {
    pub fn clear(&mut self) {
        self.devices.clear();
        self.order.clear();
    }

    pub fn remove(&mut self, device: u32) {
        self.devices.remove(&device);
        self.order.retain(|id| *id != device);
    }

    /// Returns true when a release should wake the I/O loop immediately.
    pub fn enqueue(&mut self, frame: LiveFrame) -> Result<bool, String> {
        validate_values(&frame.values)?;
        if !self.devices.contains_key(&frame.device) && self.devices.len() >= MAX_LIVE_DEVICES {
            return Err("Too many live devices; stop before changing the control selection".into());
        }
        let id = frame.device;
        let state = self.devices.entry(id).or_insert_with(|| DeviceState {
            desired: LiveFrame { values: Vec::new(), ..frame.clone() },
            releases: BTreeSet::new(), queued: false, next_ready_ms: 0,
        });
        if state.desired.epoch != frame.epoch {
            state.releases.clear();
            state.desired.values.clear();
        }
        for previous in &state.desired.values {
            let next = frame.values.iter().find(|v| v.index == previous.index);
            if previous.value > 0.0 && next.is_none_or(|v| v.value == 0.0) {
                state.releases.insert(previous.index);
            }
        }
        if state.releases.len() > MAX_FRAME_FEATURES * 4 {
            return Err("Too many changing live feature selections; stop first".into());
        }
        let urgent = !state.releases.is_empty();
        state.desired = frame;
        if !state.queued {
            state.queued = true;
            self.order.push_back(id);
        }
        Ok(urgent)
    }

    pub fn take(&mut self, epoch: u64, releases_only: bool) -> Option<PendingFrame> {
        self.take_eligible(epoch, releases_only, u64::MAX)
    }

    pub fn next_wake_ms(&self, now: u64) -> Option<u64> {
        self.devices.values().filter(|s| s.queued).map(|s|
            if s.releases.is_empty() { s.next_ready_ms.max(now) } else { now }).min()
    }

    pub fn take_ready(&mut self, epoch: u64, now: u64) -> Option<PendingFrame> {
        self.take_eligible(epoch, true, now).or_else(|| self.take_eligible(epoch, false, now))
    }

    fn take_eligible(&mut self, epoch: u64, releases_only: bool, now: u64) -> Option<PendingFrame> {
        for _ in 0..self.order.len() {
            let id = self.order.pop_front()?;
            let Some(state) = self.devices.get_mut(&id) else { continue; };
            if state.desired.epoch != epoch {
                self.devices.remove(&id);
                continue;
            }
            if (releases_only && state.releases.is_empty()) || (state.releases.is_empty() && now < state.next_ready_ms) {
                self.order.push_back(id);
                continue;
            }
            let mut frame = state.desired.clone();
            let mut synthesized = false;
            for index in std::mem::take(&mut state.releases) {
                if let Some(value) = frame.values.iter_mut().find(|v| v.index == index) {
                    if value.value != 0.0 { synthesized = true; }
                    value.value = 0.0;
                } else {
                    synthesized = true;
                    frame.values.push(OutputValue { index, value: 0.0 });
                }
            }
            state.queued = synthesized;
            state.next_ready_ms = now.saturating_add(LIVE_CADENCE_MS);
            if synthesized { self.order.push_back(id); }
            return Some(PendingFrame { frame, release_barrier: synthesized });
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn frame(request: u64, a: f64, b: f64) -> LiveFrame {
        LiveFrame { enqueued_at: std::time::Instant::now(), epoch: 1, device: 4, request, values: vec![
            OutputValue { index: 0, value: a }, OutputValue { index: 2, value: b },
        ] }
    }
    #[test]
    fn inclusive_rest_applies_before_and_after_quantization() {
        for steps in [0, 1, 7, 20, 100, 1000] {
            for value in [0.0, 0.001, 0.0499999, 0.05] { assert_eq!(normalize_vibration(value, steps), 0.0); }
        }
        assert_eq!(normalize_vibration(0.050001, 20), 0.0);
        assert_eq!(normalize_vibration(0.0749, 20), 0.0);
        assert_eq!(normalize_vibration(0.075, 20), 0.1);
        assert_eq!(normalize_vibration(0.050001, 0), 0.050001);
        assert_eq!(normalize_vibration(2.0, 20), 1.0);
        assert_eq!(normalize_vibration(f64::NAN, 20), 0.0);
    }
    #[test]
    fn eager_cadence_latest_only_and_independent_devices_with_fake_clock() {
        let mut queue = LiveQueue::default();
        queue.enqueue(frame(1, 0.2, 0.3)).unwrap();
        assert_eq!(queue.next_wake_ms(100), Some(100));
        assert_eq!(queue.take_ready(1, 100).unwrap().frame.request, 1);
        for id in 2..=99 { queue.enqueue(frame(id, 0.7, 0.3)).unwrap(); }
        assert!(queue.take_ready(1, 132).is_none());
        let mut other = frame(4, 0.4, 0.4); other.device = 7;
        queue.enqueue(other).unwrap();
        assert_eq!(queue.take_ready(1, 132).unwrap().frame.device, 7);
        assert_eq!(queue.take_ready(1, 133).unwrap().frame.request, 99);
        // A slow write doesn't cause FIFO catchup; only the latest survives.
        for id in 100..=200 { queue.enqueue(frame(id, 0.8, 0.3)).unwrap(); }
        assert_eq!(queue.take_ready(1, 500).unwrap().frame.request, 200);
        assert!(queue.take_ready(1, 500).is_none());
    }
    #[test]
    fn normalized_release_bypasses_cadence_and_retouch_waits() {
        let mut queue = LiveQueue::default();
        queue.enqueue(frame(1, 0.8, 0.3)).unwrap(); queue.take_ready(1, 10).unwrap();
        queue.enqueue(frame(2, normalize_vibration(0.05, 20), 0.3)).unwrap();
        queue.enqueue(frame(3, 0.7, 0.3)).unwrap();
        let release = queue.take_ready(1, 11).unwrap();
        assert!(release.release_barrier); assert_eq!(release.frame.values[0].value, 0.0);
        assert!(queue.take_ready(1, 43).is_none());
        assert_eq!(queue.take_ready(1, 44).unwrap().frame.request, 3);
    }
    #[test]
    fn drag_replaces_obsolete_values_without_a_backlog() {
        let mut queue = LiveQueue::default();
        for request in 1..=10_000 { queue.enqueue(frame(request, request as f64 / 10_000.0, 0.4)).unwrap(); }
        let output = queue.take(1, false).unwrap();
        assert_eq!(output.frame.request, 10_000);
        assert_eq!(output.frame.values[0].value, 1.0);
        assert!(queue.take(1, false).is_none());
    }
    #[test]
    fn release_survives_a_retouch_and_preserves_the_other_control() {
        let mut queue = LiveQueue::default();
        queue.enqueue(frame(1, 0.8, 0.3)).unwrap();
        queue.take(1, false).unwrap();
        assert!(queue.enqueue(frame(2, 0.0, 0.3)).unwrap());
        queue.enqueue(frame(3, 0.7, 0.5)).unwrap();
        let release = queue.take(1, true).unwrap();
        assert!(release.release_barrier);
        assert_eq!(release.frame.values, vec![OutputValue { index: 0, value: 0.0 }, OutputValue { index: 2, value: 0.5 }]);
        let latest = queue.take(1, false).unwrap();
        assert!(!latest.release_barrier);
        assert_eq!(latest.frame.request, 3);
        assert_eq!(latest.frame.values[0].value, 0.7);
    }
    #[test]
    fn normal_release_acknowledges_the_complete_frame() {
        let mut queue = LiveQueue::default();
        queue.enqueue(frame(1, 0.8, 0.3)).unwrap();
        queue.enqueue(frame(2, 0.0, 0.3)).unwrap();
        let release = queue.take(1, true).unwrap();
        assert!(!release.release_barrier);
        assert_eq!(release.frame.request, 2);
        assert!(queue.take(1, false).is_none());
    }
    #[test]
    fn stop_epoch_discards_even_late_old_submissions() {
        let mut queue = LiveQueue::default();
        queue.enqueue(frame(1, 0.8, 0.3)).unwrap();
        queue.clear();
        queue.enqueue(frame(2, 0.9, 0.7)).unwrap();
        assert!(queue.take(2, false).is_none());
    }
    #[test]
    fn device_removal_discards_pending_values_and_releases() {
        let mut queue = LiveQueue::default();
        queue.enqueue(frame(1, 0.8, 0.3)).unwrap();
        queue.enqueue(frame(2, 0.0, 0.3)).unwrap();
        queue.remove(4);
        assert!(queue.take(1, true).is_none());
        assert!(queue.take(1, false).is_none());
    }
    #[test]
    fn frames_are_bounded_and_duplicate_indices_rejected() {
        assert!(validate_values(&[]).is_err());
        assert!(validate_values(&[OutputValue { index: 0, value: 0.1 }, OutputValue { index: 0, value: 0.2 }]).is_err());
        for value in [f64::NAN, f64::INFINITY, -0.1, 1.1] {
            assert!(validate_values(&[OutputValue { index: 0, value }]).is_err());
        }
        let mut queue = LiveQueue::default();
        for device in 0..MAX_LIVE_DEVICES as u32 { let mut f = frame(1, 0.5, 0.2); f.device = device; queue.enqueue(f).unwrap(); }
        let mut extra = frame(1, 0.5, 0.2); extra.device = MAX_LIVE_DEVICES as u32;
        assert!(queue.enqueue(extra).is_err());
    }
}
