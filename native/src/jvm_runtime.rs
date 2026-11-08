use jni::JavaVM;
use std::{sync::{mpsc, Arc}, time::Duration};
use tokio::runtime::Runtime;

/// btleplug 0.11 calls JavaVM::get_env(), which DOES NOT attach native threads.
/// Attach every Tokio worker and blocking-pool thread before it runs any work.
/// The JNI crate keeps the attachment in thread-local storage and detaches on exit.
pub(crate) fn build(vm: Arc<JavaVM>) -> Result<Runtime, String> {
    let (started, notifications) = mpsc::channel();
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .thread_name("lovense-jvm")
        .on_thread_start(move || {
            let result = vm.attach_current_thread_permanently()
                .map(|_| ()).map_err(|error| error.to_string());
            let _ = started.send(result.clone());
            // A failed attachment must never continue into Bluetooth JNI calls.
            if let Err(error) = result { panic!("JVM thread attachment failed: {error}"); }
        })
        .enable_all()
        .build().map_err(|error| error.to_string())?;
    // Verify both initial workers before reporting successful startup to Java.
    for _ in 0..2 {
        notifications.recv_timeout(Duration::from_secs(5))
            .map_err(|error| format!("JVM worker startup: {error}"))??;
    }
    Ok(runtime)
}

#[cfg(test)]
mod tests {
    use super::*;
    use jni::{InitArgsBuilder, JNIVersion};

    #[test]
    fn real_jvm_is_attached_on_every_worker_and_blocking_thread() {
        let args = InitArgsBuilder::new().version(JNIVersion::V8).build().unwrap();
        let vm = Arc::new(JavaVM::new(args).expect("A JDK is required for the JNI regression test"));

        // Reproduce the v0.1 failure: an ordinary Tokio worker is detached.
        let plain = tokio::runtime::Builder::new_multi_thread().worker_threads(2).build().unwrap();
        let probe = vm.clone();
        assert!(plain.block_on(plain.spawn(async move { probe.get_env().is_err() })).unwrap());
        drop(plain);

        let runtime = build(vm.clone()).unwrap();
        let barrier = Arc::new(std::sync::Barrier::new(2));
        let mut tasks = Vec::new();
        for _ in 0..2 {
            let vm = vm.clone();
            let barrier = barrier.clone();
            tasks.push(runtime.spawn(async move {
                // Force both physical workers to participate, not just two tasks on one worker.
                barrier.wait();
                let env = vm.get_env().expect("Tokio worker must already be attached");
                env.new_string("JNI worker check").unwrap();
                std::thread::current().id()
            }));
        }
        let ids = runtime.block_on(async { futures::future::join_all(tasks).await });
        assert_ne!(ids[0].as_ref().unwrap(), ids[1].as_ref().unwrap());
        let worker_vm = vm.clone();
        runtime.block_on(runtime.spawn_blocking(move || {
            let env = worker_vm.get_env().expect("Blocking worker must also be attached");
            env.new_string("JNI blocking check").unwrap();
        })).unwrap();
    }
}
