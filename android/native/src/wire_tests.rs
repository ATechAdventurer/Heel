//! Exercise upstream quantization AND actual Lovense wire formatting, without BLE.
use super::*;
use buttplug::core::{errors::ButtplugDeviceError, message::{DeviceFeature, DeviceFeatureActuator, FeatureType, ButtplugActuatorFeatureMessageType, ScalarCmdV4, ScalarSubcommandV4, Endpoint}};
use buttplug::server::device::{hardware::*, protocol::{actuator_command_manager::ActuatorCommandManager, lovense::Lovense, ProtocolHandler}};
use futures::future::BoxFuture;
use std::collections::HashSet;

struct NoIo;
#[tokio::test]
async fn individual_stop_failures_are_not_reported_as_success() {
    let attempted = Arc::new(std::sync::atomic::AtomicU32::new(0));
    let stops = [Ok(()), Err("device disconnected"), Ok(())].into_iter().map(|result| {
        let attempted = attempted.clone();
        async move { attempted.fetch_add(1, Ordering::SeqCst); result }
    });
    assert_eq!(checked_stops(stops).await, Err("device disconnected"));
    assert_eq!(attempted.load(Ordering::SeqCst), 3);
    assert_eq!(checked_stops([async { Ok::<(), &str>(()) }]).await, Ok(()));
}
impl HardwareInternal for NoIo {
    fn disconnect(&self) -> BoxFuture<'static, Result<(), ButtplugDeviceError>> { panic!("No physical I/O in wire tests") }
    fn event_stream(&self) -> tokio::sync::broadcast::Receiver<HardwareEvent> { panic!("No physical I/O in wire tests") }
    fn read_value(&self, _: &HardwareReadCmd) -> BoxFuture<'static, Result<HardwareReading, ButtplugDeviceError>> { panic!("No physical I/O in wire tests") }
    fn write_value(&self, _: &HardwareWriteCmd) -> BoxFuture<'static, Result<(), ButtplugDeviceError>> { panic!("No physical I/O in wire tests") }
    fn subscribe(&self, _: &HardwareSubscribeCmd) -> BoxFuture<'static, Result<(), ButtplugDeviceError>> { panic!("No physical I/O in wire tests") }
    fn unsubscribe(&self, _: &HardwareUnsubscribeCmd) -> BoxFuture<'static, Result<(), ButtplugDeviceError>> { panic!("No physical I/O in wire tests") }
}
#[test]
fn all_hush_steps_and_representative_resolutions_reach_exact_wire_payload() {
    let hardware = Arc::new(Hardware::new("test", "test", &[Endpoint::Tx], Box::new(NoIo)));
    let lovense = Lovense::new(hardware, "Z", 1, false, false);
    for steps in [7, 20, 100, 255, 1000] {
        for k in 0..=steps {
            let actuator = DeviceFeatureActuator::new(&(0..=steps), &(0..=steps), &HashSet::from([ButtplugActuatorFeatureMessageType::ScalarCmd]));
            let manager = ActuatorCommandManager::new(&vec![DeviceFeature::new("Vibration", FeatureType::Vibrate, &Some(actuator), &None)]);
            let value = live_control::vibration_wire_value(k as f64 / steps as f64, steps);
            let command = ScalarCmdV4::new(0, vec![ScalarSubcommandV4::new(0, value, ActuatorType::Vibrate)]);
            let converted = manager.update_scalar(&command, false).unwrap();
            let payload = lovense.handle_scalar_cmd(&converted).unwrap();
            let expected_step = if k as f64 / steps as f64 <= 0.05 { 0 } else { k };
            assert_eq!(converted[0], Some((ActuatorType::Vibrate, expected_step)), "steps={steps} k={k}");
            assert_eq!(payload.len(), 1);
            match &payload[0] {
                HardwareCommand::Write(write) => assert_eq!(write.data(), format!("Vibrate:{expected_step};").as_bytes(), "steps={steps} k={k}"),
                _ => panic!("Expected vibration write"),
            }
        }
    }
}
#[test]
fn validated_metadata_normalizes_without_renumbering_mixed_features() {
    let attrs = [(ActuatorType::Rotate,20),(ActuatorType::Vibrate,20),(ActuatorType::Vibrate,100)];
    let mut values = vec![OutputValue{index:1,value:0.05},OutputValue{index:2,value:0.326}];
    normalize_frame(&attrs, &mut values).unwrap();
    assert_eq!(values, vec![OutputValue{index:1,value:0.0},OutputValue{index:2,value:0.33}]);
    assert!(normalize_frame(&attrs, &mut [OutputValue{index:0,value:0.5}]).is_err());
    assert!(normalize_frame(&attrs, &mut [OutputValue{index:3,value:0.5}]).is_err());
}
