package dev.prototype.lovense;

import android.app.*;
import android.bluetooth.BluetoothAdapter;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.*;
import android.os.*;

/** Visible, user-started connected-device service; no Activity references and no boot auto-start. */
public final class ConnectionService extends Service {
    private static final String CHANNEL = "heel-connection";
    static final String PAUSE = "dev.prototype.lovense.PAUSE";
    private ControlRuntime runtime;
    private PowerManager.WakeLock wake;
    private ConnectivityManager connectivity;
    private String lastStatus = "";
    static void start(Context context) { context.startForegroundService(new Intent(context, ConnectionService.class)); }
    private final ConnectivityManager.NetworkCallback networks = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { new Handler(Looper.getMainLooper()).post(() -> runtime.relay.networkAvailable()); }
    };
    private final BroadcastReceiver bluetooth = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,-1);
            if (state == BluetoothAdapter.STATE_OFF) runtime.bluetoothOff();
            else if (state == BluetoothAdapter.STATE_ON) runtime.bluetoothOn();
        }
    };
    @Override public void onCreate() {
        super.onCreate(); runtime = ControlRuntime.get(this);
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Connection", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps your paired connection available when switching apps."); channel.setSound(null,null); manager.createNotificationChannel(channel);
        Notification notification = notification("Keeping your pair connected");
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE); else startForeground(7,notification);
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Heel:connection"); wake.setReferenceCounted(false);
        connectivity = getSystemService(ConnectivityManager.class); connectivity.registerDefaultNetworkCallback(networks);
        registerReceiver(bluetooth,new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        runtime.notificationChanged(this::refresh); runtime.start(); refresh();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if (intent != null && PAUSE.equals(intent.getAction())) runtime.pause(true);
        return START_NOT_STICKY;
    }
    private void refresh() {
        boolean awake = runtime.gate.active() || runtime.gate.pending() || runtime.state.optBoolean("ready") || runtime.gate.canRecover() && !runtime.safeWord;
        if (awake && !wake.isHeld()) wake.acquire(10 * 60 * 1000L);
        else if (!awake && wake.isHeld()) wake.release();
        String status = runtime.safeWord ? "Safe Word · stopped" : !runtime.online ? "Reconnecting…" : runtime.gate.active() ? "Your pair is connected" : "Paused · connection stays ready";
        if (!status.equals(lastStatus)) { lastStatus = status; getSystemService(NotificationManager.class).notify(7,notification(status)); }
    }
    private Notification notification(String text) {
        Intent open = new Intent(this,WelcomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause = PendingIntent.getService(this,1,new Intent(this,ConnectionService.class).setAction(PAUSE),PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_connection).setContentTitle(getString(R.string.app_name)).setContentText(text)
                .setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(null,"Pause",pause).build()).build();
    }
    @Override public void onDestroy() {
        runtime.notificationChanged(null); runtime.stop(); connectivity.unregisterNetworkCallback(networks); unregisterReceiver(bluetooth);
        if (wake != null && wake.isHeld()) wake.release(); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
