// Production entry points are JNI-only; host builds exercise the hardware-free tests.
#![cfg_attr(not(any(target_os = "android", target_os = "ios")), allow(dead_code))]

use buttplug::{
    client::{ButtplugClient, ButtplugClientDevice, ButtplugClientEvent, LinearCommand, RotateCommand, ScalarCommand},
    core::{connector::ButtplugInProcessClientConnectorBuilder, message::ActuatorType},
    server::{ButtplugServerBuilder, device::{ServerDeviceManagerBuilder, ServerDeviceManager}},
    util::device_configuration::{load_protocol_configs, DEVICE_CONFIGURATION_JSON},
};
use futures::{FutureExt, StreamExt};
use serde::Deserialize;
use serde_json::{json, Value};
use std::{collections::{HashMap, VecDeque}, sync::{Arc, Mutex, OnceLock,
    atomic::{AtomicBool, AtomicU64, Ordering}}, time::{Duration, Instant}};
use tokio::{runtime::Runtime, sync::{mpsc, watch, Notify}, time::timeout};
use tracing_subscriber::{layer::{Context, SubscriberExt}, Layer};
mod live_control;
#[cfg(test)]
mod wire_tests;
use live_control::{LiveFrame, LiveQueue, OutputValue, PendingFrame};
#[cfg(any(target_os = "android", test))]
mod jvm_runtime;
#[cfg(any(target_os = "android", target_os = "ios"))]
use buttplug::server::device::hardware::communication::btleplug::BtlePlugCommunicationManagerBuilder;

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
enum Command {
    Resume {}, Suspend {}, Stop {}, Refresh {},
    Scan { enabled: bool },
    Battery { device: u32 },
    Scalar { device: u32, index: u32, value: f64 },
    VibrationFrame { device: u32, request: u64, values: Vec<OutputValue> },
    Rotate { device: u32, index: u32, value: f64, clockwise: bool },
    Linear { device: u32, index: u32, value: f64, duration: u32 },
}

impl Command {
    fn validate(&self) -> Result<(), String> {
        let value = match self {
            Self::VibrationFrame { values, .. } => { live_control::validate_values(values)?; None },
            Self::Scalar { value, .. } | Self::Rotate { value, .. } => Some(*value),
            Self::Linear { value, duration, .. } => {
                if !(100..=10000).contains(duration) {
                    return Err("Movement duration must be 100–10000 ms".into());
                }
                Some(*value)
            }
            _ => None,
        };
        if value.is_some_and(|v| !v.is_finite() || !(0.0..=1.0).contains(&v)) {
            return Err("Output must be between 0 and 100 percent".into());
        }
        Ok(())
    }
}

struct Shared {
    events: Mutex<VecDeque<Value>>,
    heartbeat: Mutex<Instant>,
    active: AtomicBool,
    epoch: AtomicU64,
    urgent: watch::Sender<u64>,
    stopping: AtomicBool,
    live: Mutex<LiveQueue>,
    live_release: Notify,
    capabilities: Mutex<HashMap<u32, Vec<(ActuatorType, u32)>>>,
    battery_busy: AtomicBool,
    manager: OnceLock<Arc<ServerDeviceManager>>,
}

impl Shared {
    fn emit(&self, event: Value) {
        let mut events = self.events.lock().unwrap_or_else(|e| e.into_inner());
        if events.len() >= 128 { events.pop_front(); }
        events.push_back(event);
    }
    fn error(&self, message: impl ToString) {
        self.emit(json!({"type":"error", "message":message.to_string()}));
    }
    fn request_stop(&self, suspend: bool) {
        let mut live = self.live.lock().unwrap_or_else(|e| e.into_inner());
        self.stopping.store(true, Ordering::SeqCst);
        if suspend { self.active.store(false, Ordering::SeqCst); }
        let epoch = self.epoch.fetch_add(1, Ordering::SeqCst) + 1;
        live.clear();
        // A second Stop supersedes any completion still waiting for the UI.
        self.events.lock().unwrap_or_else(|e| e.into_inner())
            .retain(|event| event["type"] != "stopped");
        self.urgent.send_replace(epoch);
    }
}

struct Bridge {
    _runtime: Runtime,
    shared: Arc<Shared>,
    commands: mpsc::Sender<(u64, Command)>,
}
static BRIDGE: OnceLock<Bridge> = OnceLock::new();

struct EngineLogLayer(std::sync::Weak<Shared>);
impl<S: tracing::Subscriber> Layer<S> for EngineLogLayer {
    fn on_event(&self, event: &tracing::Event<'_>, _: Context<'_, S>) {
        if *event.metadata().level() > tracing::Level::INFO { return; }
        struct Fields(String);
        impl tracing::field::Visit for Fields {
            fn record_debug(&mut self, field: &tracing::field::Field, value: &dyn std::fmt::Debug) {
                use std::fmt::Write;
                if self.0.len() < 1500 { let _ = write!(self.0, "{}={value:?} ", field.name()); }
            }
        }
        let mut fields = Fields(String::new());
        event.record(&mut fields);
        if let Some(shared) = self.0.upgrade() {
            shared.emit(json!({"type":"log", "message":format!("{} {}: {}",
                event.metadata().level(), event.metadata().target(), fields.0)}));
        }
    }
}

// Filter before constructing the hardware manager: unrelated brands are never connected.
fn lovense_config() -> Result<String, String> {
    let mut config: Value = serde_json::from_str(DEVICE_CONFIGURATION_JSON).map_err(|e| e.to_string())?;
    config["protocols"].as_object_mut().ok_or("Missing protocol definitions")?
        .retain(|name, _| name == "lovense");
    Ok(config.to_string())
}

fn create_bridge(runtime: Runtime) -> Result<Bridge, String> {
    let (commands, rx) = mpsc::channel(32);
    let (urgent, urgent_rx) = watch::channel(0);
    let shared = Arc::new(Shared { events: Mutex::new(VecDeque::new()),
        heartbeat: Mutex::new(Instant::now()), active: AtomicBool::new(true),
        epoch: AtomicU64::new(0), urgent, stopping: AtomicBool::new(false),
        live: Mutex::new(LiveQueue::default()), live_release: Notify::new(), capabilities: Mutex::new(HashMap::new()), battery_busy: AtomicBool::new(false), manager: OnceLock::new() });
    let subscriber = tracing_subscriber::registry().with(EngineLogLayer(Arc::downgrade(&shared)));
    if tracing::subscriber::set_global_default(subscriber).is_err() {
        shared.error("Native diagnostics subscriber could not be installed");
    }
    shared.emit(json!({"type":"log", "message":"Native runtime ready: both workers attached to JVM"}));
    let engine_shared = shared.clone();
    runtime.spawn(async move {
        let result = std::panic::AssertUnwindSafe(run_engine(engine_shared.clone(), rx, urgent_rx)).catch_unwind().await;
        let error = match result {
            Ok(Ok(())) => None,
            Ok(Err(error)) => Some(error),
            Err(_) => Some("Native engine panicked; force-stop and reopen the app. See debug log.".to_owned()),
        };
        if let Some(error) = error {
            engine_shared.active.store(false, Ordering::SeqCst);
            engine_shared.emit(json!({"type":"fatal", "message":error}));
        }
    });
    let watchdog_shared = shared.clone();
    runtime.spawn(async move {
        let mut tick = tokio::time::interval(Duration::from_millis(250));
        loop {
            tick.tick().await;
            let elapsed = watchdog_shared.heartbeat.lock().unwrap_or_else(|e| e.into_inner()).elapsed();
            if elapsed > Duration::from_secs(2) && watchdog_shared.active.load(Ordering::SeqCst) {
                watchdog_shared.request_stop(true);
                watchdog_shared.emit(json!({"type":"log", "message":"UI heartbeat lost; stopping outputs"}));
            }
        }
    });
    Ok(Bridge { _runtime: runtime, shared, commands })
}

fn device_json(device: &ButtplugClientDevice) -> Value {
    json!({"id":device.index(), "name":device.name(),
        "messages":device.message_attributes(), "battery":device.has_battery_level()})
}

fn snapshot(client: &ButtplugClient, shared: &Shared) {
    let connected = client.devices();
    *shared.capabilities.lock().unwrap_or_else(|e| e.into_inner()) = connected.iter().map(|d|
        (d.index(), d.scalar_attributes().iter().map(|a| (*a.actuator_type(), *a.step_count())).collect())).collect();
    let devices: Vec<Value> = connected.iter().map(|d| {
        let mut value = device_json(d);
        if let Some(info) = shared.manager.get().and_then(|manager| manager.device_info(d.index())) {
            // Local identity for exact-device recovery, never part of relay metadata.
            value["identity"] = json!(format!("{:?}", info.identifier()));
            value["address"] = json!(info.identifier().address());
        }
        value
    }).collect();
    shared.emit(json!({"type":"devices", "devices":devices}));
}

async fn stop_now(client: &ButtplugClient, shared: &Shared) {
    let stop_epoch = shared.epoch.load(Ordering::SeqCst);
    // StopAllDevices in Buttplug 9.0.9 discards individual device errors.
    // Await each device directly, and identify which devices actually completed.
    let devices = client.devices();
    let stopped_devices: Vec<u32> = devices.iter().map(|device| device.index()).collect();
    let result = timeout(Duration::from_secs(3), checked_stops(devices.iter().map(|device| device.stop()))).await;
    let success = matches!(result, Ok(Ok(())));
    if !success {
        shared.active.store(false, Ordering::SeqCst);
        shared.error(format!("Stop could not be confirmed: {result:?}"));
    }
    if !shared.active.load(Ordering::SeqCst) {
        let _ = timeout(Duration::from_secs(2), client.stop_scanning()).await;
        shared.emit(json!({"type":"scanning", "enabled":false}));
    }
    let _live = shared.live.lock().unwrap_or_else(|e| e.into_inner());
    if stop_epoch == shared.epoch.load(Ordering::SeqCst) {
        shared.stopping.store(false, Ordering::SeqCst);
        shared.emit(json!({"type":"stopped", "confirmed":success,
            "devices":if success { stopped_devices } else { vec![] },
            "active":shared.active.load(Ordering::SeqCst)}));
    }
}

async fn checked_stops<F, E>(stops: impl IntoIterator<Item = F>) -> Result<(), E>
where F: std::future::Future<Output = Result<(), E>> {
    // Attempt all stops even when another device fails.
    for result in futures::future::join_all(stops).await { result?; }
    Ok(())
}

async fn run_engine(shared: Arc<Shared>, mut commands: mpsc::Receiver<(u64, Command)>,
                    mut urgent: watch::Receiver<u64>) -> Result<(), String> {
    let config = load_protocol_configs(&Some(lovense_config()?), &None, false)
        .map_err(|e| e.to_string())?.finish().map_err(|e| e.to_string())?;
    let mut manager_builder = ServerDeviceManagerBuilder::new(config);
    #[cfg(any(target_os = "android", target_os = "ios"))]
    manager_builder.comm_manager(BtlePlugCommunicationManagerBuilder::default());
    let manager = manager_builder.finish().map_err(|e| e.to_string())?;
    let server = ButtplugServerBuilder::new(manager).finish().map_err(|e| e.to_string())?;
    let _ = shared.manager.set(server.device_manager());
    let connector = ButtplugInProcessClientConnectorBuilder::default().server(server).finish();
    let client = ButtplugClient::new("Lovense Dev");
    client.connect(connector).await.map_err(|e| e.to_string())?;
    let events = client.event_stream();
    futures::pin_mut!(events);
    shared.emit(json!({"type":"ready", "active":shared.active.load(Ordering::SeqCst)}));
    snapshot(&client, &shared);
    // A notification wakes idle output immediately; a per-device deadline paces
    // subsequent frames. Only the latest pending frame survives a slow write.
    let scheduler_clock = tokio::time::Instant::now();
    loop {
        let now = scheduler_clock.elapsed().as_millis() as u64;
        let deadline = shared.live.lock().unwrap_or_else(|e| e.into_inner()).next_wake_ms(now);
        let wake_at = scheduler_clock + Duration::from_millis(deadline.unwrap_or(now.saturating_add(60_000)));
        tokio::select! {
            biased;
            changed = urgent.changed() => {
                if changed.is_err() { break; }
                stop_now(&client, &shared).await;
            }
            event = events.next() => {
                match event {
                    Some(ButtplugClientEvent::DeviceAdded(_)) => snapshot(&client, &shared),
                    Some(ButtplugClientEvent::DeviceRemoved(device)) => {
                        shared.live.lock().unwrap_or_else(|e| e.into_inner()).remove(device.index());
                        snapshot(&client, &shared);
                    }
                    Some(ButtplugClientEvent::ScanningFinished) => shared.emit(json!({"type":"scanning", "enabled":false})),
                    Some(ButtplugClientEvent::Error(error)) => shared.error(error),
                    Some(ButtplugClientEvent::ServerDisconnect) | Some(ButtplugClientEvent::PingTimeout) | None => {
                        shared.active.store(false, Ordering::SeqCst);
                        stop_now(&client, &shared).await;
                        return Err("Device engine disconnected. Close and reopen the app.".into());
                    }
                    _ => {}
                }
            }
            _ = tokio::time::sleep_until(wake_at), if deadline.is_some() => {
                let pending = shared.live.lock().unwrap_or_else(|e| e.into_inner())
                    .take_ready(shared.epoch.load(Ordering::SeqCst), scheduler_clock.elapsed().as_millis() as u64);
                if let Some(pending) = pending {
                    apply_live(&client, &shared, &mut urgent, pending).await;
                }
            }
            _ = shared.live_release.notified() => { /* recompute earliest eligible deadline */ }
            entry = commands.recv() => {
                let Some((epoch, command)) = entry else { break; };
                // Never replay movement queued before a Stop or lifecycle pause.
                if epoch != shared.epoch.load(Ordering::SeqCst) { continue; }
                if !shared.active.load(Ordering::SeqCst) { continue; }
                if let Command::Battery { device } = command {
                    if shared.battery_busy.swap(true, Ordering::SeqCst) { continue; }
                    let selected = find_device(&client, device);
                    let battery_shared = shared.clone();
                    // Battery responses can take seconds. Their small GATT request
                    // stays serialized in Java, but waiting for the response must
                    // not occupy the foreground output scheduler.
                    tokio::spawn(async move {
                        let result = timeout(Duration::from_secs(5), async {
                            let device = selected?;
                            if !device.has_battery_level() { return Err("Battery reading unavailable".to_owned()); }
                            device.battery_level().await.map_err(|e| e.to_string())
                        }).await;
                        if epoch == battery_shared.epoch.load(Ordering::SeqCst) {
                            match result {
                                Ok(Ok(level)) => battery_shared.emit(json!({"type":"battery", "device":device, "percent":(level * 100.0).round() as i32})),
                                Ok(Err(error)) => battery_shared.error(error),
                                Err(_) => battery_shared.error("Battery reading timed out"),
                            }
                        }
                        battery_shared.battery_busy.store(false, Ordering::SeqCst);
                    });
                    continue;
                }
                tokio::select! {
                    biased;
                    _ = urgent.changed() => stop_now(&client, &shared).await,
                    result = timeout(Duration::from_secs(5), execute(&client, &shared, command)) => {
                        match result {
                            Ok(Ok(())) => {},
                            Ok(Err(error)) => shared.error(error),
                            Err(_) => {
                                shared.error("Command timed out; stopping outputs");
                                shared.request_stop(true);
                            }
                        }
                    }
                }
            }
        }
    }
    stop_now(&client, &shared).await;
    let _ = client.disconnect().await;
    Ok(())
}

fn find_device(client: &ButtplugClient, id: u32) -> Result<Arc<ButtplugClientDevice>, String> {
    client.devices().into_iter().find(|d| d.index() == id && d.connected())
        .ok_or_else(|| "Device is no longer connected".to_owned())
}

fn normalize_frame(attributes: &[(ActuatorType, u32)], values: &mut [OutputValue]) -> Result<(), String> {
    live_control::validate_values(values)?;
    for value in values {
        let (kind, steps) = attributes.get(value.index as usize).ok_or("Unknown vibration feature index")?;
        if *kind != ActuatorType::Vibrate { return Err("The selected feature is not a vibration control".into()); }
        value.value = live_control::normalize_vibration(value.value, *steps);
    }
    Ok(())
}

fn vibration_map(types: &[ActuatorType], values: &[OutputValue]) -> Result<HashMap<u32, (f64, ActuatorType)>, String> {
    let mut map = HashMap::new();
    for value in values {
        let actuator = types.get(value.index as usize).ok_or("Unknown vibration feature index")?;
        if *actuator != ActuatorType::Vibrate {
            return Err("The selected feature is not a vibration control".into());
        }
        map.insert(value.index, (value.value, *actuator));
    }
    Ok(map)
}

async fn apply_live(client: &ButtplugClient, shared: &Shared, urgent: &mut watch::Receiver<u64>, pending: PendingFrame) {
    let frame = pending.frame;
    if frame.epoch != shared.epoch.load(Ordering::SeqCst)
        || !shared.active.load(Ordering::SeqCst) || shared.stopping.load(Ordering::SeqCst) { return; }
    let queue_ms = frame.enqueued_at.elapsed().as_millis() as u64;
    let io_start = Instant::now();
    let apply = async {
        let device = find_device(client, frame.device)?;
        let types: Vec<ActuatorType> = device.scalar_attributes().iter().map(|a| *a.actuator_type()).collect();
        let mut normalized = frame.values.clone();
        let attributes: Vec<_> = device.scalar_attributes().iter().map(|a| (*a.actuator_type(), *a.step_count())).collect();
        normalize_frame(&attributes, &mut normalized)?;
        for value in &mut normalized { value.value = live_control::vibration_wire_value(value.value, attributes[value.index as usize].1); }
        let values = vibration_map(&types, &normalized)?;
        device.scalar(&ScalarCommand::ScalarMap(values)).await.map_err(|e| e.to_string())
    };
    tokio::select! {
        biased;
        _ = urgent.changed() => stop_now(client, shared).await,
        result = timeout(Duration::from_secs(2), apply) => {
            match result {
                Ok(Ok(())) => {
                    if frame.epoch != shared.epoch.load(Ordering::SeqCst) { return; }
                    if pending.release_barrier {
                        shared.emit(json!({"type":"vibration_release_applied", "device":frame.device}));
                    } else {
                        shared.emit(json!({"type":"vibration_applied", "device":frame.device, "request":frame.request, "queue_ms":queue_ms, "io_ms":io_start.elapsed().as_millis() as u64}));
                    }
                }
                Ok(Err(error)) => {
                    shared.error(format!("Live vibration command failed: {error}"));
                    shared.request_stop(true);
                }
                Err(_) => {
                    shared.error("Live vibration command timed out; stopping outputs");
                    shared.request_stop(true);
                }
            }
        }
    }
}

async fn execute(client: &ButtplugClient, shared: &Shared, command: Command) -> Result<(), String> {
    command.validate()?;
    match command {
        Command::Refresh {} | Command::Resume {} => {
            shared.emit(json!({"type":"ready", "active":shared.active.load(Ordering::SeqCst)}));
            snapshot(client, shared);
        }
        Command::Scan { enabled } => {
            if enabled { client.start_scanning().await } else { client.stop_scanning().await }
                .map_err(|e| e.to_string())?;
            shared.emit(json!({"type":"scanning", "enabled":enabled}));
        }
        Command::Battery { device } => {
            let device = find_device(client, device)?;
            if !device.has_battery_level() { return Err("Battery reading unavailable".into()); }
            let level = device.battery_level().await.map_err(|e| e.to_string())?;
            shared.emit(json!({"type":"battery", "device":device.index(), "percent":(level * 100.0).round() as i32}));
        }
        Command::Scalar { device, index, value } => {
            let device = find_device(client, device)?;
            let attrs = device.scalar_attributes();
            let attr = attrs.get(index as usize).ok_or("Unknown scalar feature")?;
            let value = if *attr.actuator_type() == ActuatorType::Vibrate {
                live_control::vibration_wire_value(value, *attr.step_count())
            } else { value };
            let values = HashMap::from([(index, (value, *attr.actuator_type()))]);
            device.scalar(&ScalarCommand::ScalarMap(values)).await.map_err(|e| e.to_string())?;
            shared.emit(json!({"type":"log", "message":format!("{} / {}: {}% accepted", device.name(), attr.actuator_type(), (value*100.0).round())}));
        }
        Command::Rotate { device, index, value, clockwise } => {
            let device = find_device(client, device)?;
            device.message_attributes().rotate_cmd().as_ref().and_then(|v| v.get(index as usize))
                .ok_or("Unknown rotation feature")?;
            device.rotate(&RotateCommand::RotateMap(HashMap::from([(index, (value, clockwise))])))
                .await.map_err(|e| e.to_string())?;
            shared.emit(json!({"type":"log", "message":"Rotation command accepted"}));
        }
        Command::Linear { device, index, value, duration } => {
            let device = find_device(client, device)?;
            device.message_attributes().linear_cmd().as_ref().and_then(|v| v.get(index as usize))
                .ok_or("Unknown linear feature")?;
            device.linear(&LinearCommand::LinearMap(HashMap::from([(index, (duration, value))])))
                .await.map_err(|e| e.to_string())?;
            shared.emit(json!({"type":"log", "message":"Position command accepted"}));
        }
        Command::Stop {} | Command::Suspend {} | Command::VibrationFrame { .. } => unreachable!("Handled outside the FIFO"),
    }
    Ok(())
}

fn submit(text: &str) -> Result<(), String> {
    let bridge = BRIDGE.get().ok_or("Engine has not started")?;
    let command: Command = serde_json::from_str(text).map_err(|e| e.to_string())?;
    if let Err(error) = command.validate() {
        if matches!(command, Command::VibrationFrame { .. }) { bridge.shared.request_stop(true); }
        return Err(error);
    }
    match command {
        Command::Stop {} => { bridge.shared.request_stop(false); return Ok(()); }
        Command::Suspend {} => { bridge.shared.request_stop(true); return Ok(()); }
        Command::Resume {} => {
            *bridge.shared.heartbeat.lock().unwrap_or_else(|e| e.into_inner()) = Instant::now();
            bridge.shared.active.store(true, Ordering::SeqCst);
        }
        _ if !bridge.shared.active.load(Ordering::SeqCst) => return Err("Engine paused; tap Scan to resume".into()),
        _ => {}
    }
    if let Command::VibrationFrame { device, request, mut values } = command {
        // Validate metadata and normalize BEFORE zero transitions reach LiveQueue.
        let normalized = bridge.shared.capabilities.lock().unwrap_or_else(|e| e.into_inner())
            .get(&device).ok_or_else(|| "Device is no longer connected".to_owned())
            .and_then(|attrs| normalize_frame(attrs, &mut values));
        if let Err(error) = normalized { bridge.shared.request_stop(true); return Err(error); }
        // Share this lock with request_stop(): either the frame is inserted
        // before Stop clears it, or the closed gate rejects it afterward.
        let mut queue = bridge.shared.live.lock().unwrap_or_else(|e| e.into_inner());
        if bridge.shared.stopping.load(Ordering::SeqCst) { return Err("Stop is still in progress".into()); }
        if !bridge.shared.active.load(Ordering::SeqCst) { return Err("Engine paused; tap Scan to resume".into()); }
        let frame = LiveFrame { enqueued_at: Instant::now(), epoch: bridge.shared.epoch.load(Ordering::SeqCst), device, request, values };
        let result = queue.enqueue(frame);
        drop(queue);
        match result {
            Ok(_) => bridge.shared.live_release.notify_one(),
            Err(error) => { bridge.shared.request_stop(true); return Err(error); },
        }
        return Ok(());
    }
    let epoch = bridge.shared.epoch.load(Ordering::SeqCst);
    bridge.commands.try_send((epoch, command)).map_err(|_| "Command queue full or engine unavailable".to_owned())
}

#[cfg(target_os = "android")]
mod android {
    use super::*;
    use jni::{JNIEnv, objects::{JClass, JString}, sys::jstring};

    fn string(env: &JNIEnv, value: String) -> jstring {
        env.new_string(value).map(|s| s.into_inner()).unwrap_or(std::ptr::null_mut())
    }
    fn guarded(action: impl FnOnce() -> Result<(), String>) -> String {
        match std::panic::catch_unwind(std::panic::AssertUnwindSafe(action)) {
            Ok(Ok(())) => String::new(),
            Ok(Err(error)) => error,
            Err(_) => "Native engine failed. Close and reopen the app.".to_owned(),
        }
    }
    #[no_mangle]
    pub extern "system" fn Java_dev_prototype_lovense_NativeBridge_initialize(env: JNIEnv, _: JClass) -> jstring {
        let result = guarded(|| {
            if BRIDGE.get().is_some() { return Ok(()); }
            // Called from Java after permission checks, while the application class loader is visible.
            jni_utils::init(&env).map_err(|e| e.to_string())?;
            btleplug::platform::init(&env).map_err(|e| e.to_string())?;
            let vm = Arc::new(env.get_java_vm().map_err(|e| e.to_string())?);
            let runtime = jvm_runtime::build(vm)?;
            BRIDGE.set(create_bridge(runtime)?).map_err(|_| "Engine already initialized".to_owned())
        });
        string(&env, result)
    }
    #[no_mangle]
    pub extern "system" fn Java_dev_prototype_lovense_NativeBridge_submit(env: JNIEnv, _: JClass, value: JString) -> jstring {
        let result = guarded(|| {
            let value: String = env.get_string(value).map_err(|e| e.to_string())?.into();
            submit(&value)
        });
        string(&env, result)
    }
    #[no_mangle]
    pub extern "system" fn Java_dev_prototype_lovense_NativeBridge_drain(env: JNIEnv, _: JClass) -> jstring {
        let result = std::panic::catch_unwind(|| {
            let Some(bridge) = BRIDGE.get() else { return "[]".to_owned(); };
            *bridge.shared.heartbeat.lock().unwrap_or_else(|e| e.into_inner()) = Instant::now();
            let values: Vec<Value> = bridge.shared.events.lock().unwrap_or_else(|e| e.into_inner()).drain(..).collect();
            serde_json::to_string(&values).unwrap_or_else(|_| "[]".to_owned())
        });
        string(&env, result.unwrap_or_else(|_| "[]".to_owned()))
    }
}

/// C-ABI boundary for the iOS app. btleplug's CoreBluetooth backend needs no JVM
/// attachment, so this is a plain Tokio runtime with no equivalent of jvm_runtime.
#[cfg(target_os = "ios")]
mod ios {
    use super::*;
    use std::ffi::{CStr, CString};
    use std::os::raw::c_char;

    fn string(value: String) -> *mut c_char {
        CString::new(value).unwrap_or_default().into_raw()
    }
    fn guarded(action: impl FnOnce() -> Result<(), String>) -> String {
        match std::panic::catch_unwind(std::panic::AssertUnwindSafe(action)) {
            Ok(Ok(())) => String::new(),
            Ok(Err(error)) => error,
            Err(_) => "Native engine failed. Close and reopen the app.".to_owned(),
        }
    }
    #[no_mangle]
    pub extern "C" fn lovense_native_initialize() -> *mut c_char {
        let result = guarded(|| {
            if BRIDGE.get().is_some() { return Ok(()); }
            let runtime = tokio::runtime::Builder::new_multi_thread()
                .worker_threads(2).thread_name("lovense-ble").enable_all()
                .build().map_err(|e| e.to_string())?;
            BRIDGE.set(create_bridge(runtime)?).map_err(|_| "Engine already initialized".to_owned())
        });
        string(result)
    }
    #[no_mangle]
    pub extern "C" fn lovense_native_submit(value: *const c_char) -> *mut c_char {
        let result = guarded(|| {
            if value.is_null() { return Err("Null command".to_owned()); }
            // Safety: callers must pass a valid, NUL-terminated string they still own;
            // it is only read for the duration of this call.
            let text = unsafe { CStr::from_ptr(value) }.to_str().map_err(|e| e.to_string())?;
            submit(text)
        });
        string(result)
    }
    #[no_mangle]
    pub extern "C" fn lovense_native_drain() -> *mut c_char {
        let result = std::panic::catch_unwind(|| {
            let Some(bridge) = BRIDGE.get() else { return "[]".to_owned(); };
            *bridge.shared.heartbeat.lock().unwrap_or_else(|e| e.into_inner()) = Instant::now();
            let values: Vec<Value> = bridge.shared.events.lock().unwrap_or_else(|e| e.into_inner()).drain(..).collect();
            serde_json::to_string(&values).unwrap_or_else(|_| "[]".to_owned())
        });
        string(result.unwrap_or_else(|_| "[]".to_owned()))
    }
    /// Every string returned above must be released through this, exactly once.
    #[no_mangle]
    pub extern "C" fn lovense_native_free_string(value: *mut c_char) {
        if value.is_null() { return; }
        // Safety: value must be a pointer this module previously returned, not yet freed.
        unsafe { drop(CString::from_raw(value)); }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn output_validation_rejects_out_of_range_and_nonfinite_values() {
        for value in [-0.01, 1.01, f64::NAN, f64::INFINITY] {
            assert!(Command::Scalar { device:0, index:0, value }.validate().is_err());
        }
        for value in [0.0, 0.5, 1.0] {
            assert!(Command::Scalar { device:0, index:0, value }.validate().is_ok());
        }
    }
    #[test]
    fn linear_duration_is_bounded() {
        for duration in [0, 99, 10001, u32::MAX] {
            assert!(Command::Linear { device:0, index:0, value:0.5, duration }.validate().is_err());
        }
    }
    #[test]
    fn config_excludes_other_brands_and_loads_with_upstream_schema() {
        let text = lovense_config().unwrap();
        let value: Value = serde_json::from_str(&text).unwrap();
        let protocols = value["protocols"].as_object().unwrap();
        assert_eq!(protocols.len(), 1);
        assert!(protocols.contains_key("lovense"));
        load_protocol_configs(&Some(text), &None, false).unwrap().finish().unwrap();
    }
    #[test]
    fn malformed_commands_and_unknown_fields_are_rejected() {
        assert!(serde_json::from_str::<Command>(r#"{"type":"scalar","device":0,"index":0}"#).is_err());
        assert!(serde_json::from_str::<Command>(r#"{"type":"stop","value":1}"#).is_err());
    }
    #[test]
    fn stop_invalidates_queued_output_and_suspend_closes_the_gate() {
        let (urgent, _) = watch::channel(0);
        let shared = Shared { events:Mutex::new(VecDeque::new()), heartbeat:Mutex::new(Instant::now()),
            active:AtomicBool::new(true), epoch:AtomicU64::new(7), urgent,
            stopping:AtomicBool::new(false), live:Mutex::new(LiveQueue::default()), live_release:Notify::new(), capabilities:Mutex::new(HashMap::new()), battery_busy:AtomicBool::new(false), manager:OnceLock::new() };
        let queued_epoch = shared.epoch.load(Ordering::SeqCst);
        shared.live.lock().unwrap().enqueue(LiveFrame { enqueued_at: Instant::now(), epoch:queued_epoch, device:0, request:1,
            values:vec![OutputValue { index:0, value:0.7 }] }).unwrap();
        shared.emit(json!({"type":"stopped", "confirmed":true, "active":true}));
        shared.request_stop(false);
        assert_ne!(queued_epoch, shared.epoch.load(Ordering::SeqCst));
        assert!(shared.active.load(Ordering::SeqCst));
        assert!(shared.stopping.load(Ordering::SeqCst));
        assert!(shared.live.lock().unwrap().take(queued_epoch, false).is_none());
        assert!(shared.events.lock().unwrap().is_empty());
        shared.request_stop(true);
        assert!(!shared.active.load(Ordering::SeqCst));
    }
    #[test]
    fn live_vibration_preserves_mixed_scalar_indices_and_rejects_other_actuators() {
        let types = [ActuatorType::Vibrate, ActuatorType::Oscillate, ActuatorType::Vibrate];
        let values = [OutputValue { index: 0, value: 0.2 }, OutputValue { index: 2, value: 0.7 }];
        let map = vibration_map(&types, &values).unwrap();
        assert_eq!(map.get(&0).unwrap().0, 0.2);
        assert_eq!(map.get(&2).unwrap().0, 0.7);
        assert!(!map.contains_key(&1));
        assert!(vibration_map(&types, &[OutputValue { index: 1, value: 0.2 }]).is_err());
        assert!(vibration_map(&types, &[OutputValue { index: 9, value: 0.2 }]).is_err());
    }
    #[test]
    fn live_frame_wire_format_validates_feature_values_and_rejects_extra_fields() {
        let valid: Command = serde_json::from_str(r#"{"type":"vibration_frame","device":4,"request":7,"values":[{"index":2,"value":0.5}]}"#).unwrap();
        valid.validate().unwrap();
        assert!(serde_json::from_str::<Command>(r#"{"type":"vibration_frame","device":4,"request":7,"values":[{"index":2,"value":0.5,"extra":true}]}"#).is_err());
        let invalid: Command = serde_json::from_str(r#"{"type":"vibration_frame","device":4,"request":7,"values":[]}"#).unwrap();
        assert!(invalid.validate().is_err());
    }
    #[tokio::test]
    async fn embedded_client_handshake_and_stop_work_without_hardware() {
        let config = load_protocol_configs(&Some(lovense_config().unwrap()), &None, false).unwrap().finish().unwrap();
        let manager = ServerDeviceManagerBuilder::new(config).finish().unwrap();
        let server = ButtplugServerBuilder::new(manager).finish().unwrap();
        let connector = ButtplugInProcessClientConnectorBuilder::default().server(server).finish();
        let client = ButtplugClient::new("test");
        client.connect(connector).await.unwrap();
        assert!(client.devices().is_empty());
        client.stop_all_devices().await.unwrap();
        client.disconnect().await.unwrap();
    }
}
