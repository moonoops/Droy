package io.droyagent.androiduse;

import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.ParcelUuid;
import android.os.SystemClock;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native Bluetooth transport; device protocols and message framing remain with the caller. */
public final class BluetoothTools {
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Context context;
    private final BluetoothAdapter adapter;
    private final DeviceDataStore data;
    private final Map<String, DeviceRef> devices = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> scans = new ConcurrentHashMap<>();
    private volatile Cancellation accessCheck = () -> { throw new InterruptedException(); };

    public BluetoothTools(Context context) {
        this.context = context.getApplicationContext();
        BluetoothManager manager = this.context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        data = new DeviceDataStore(this.context);
    }

    /** Product-wide live authorization; deliberately independent from a completed tool call. */
    public void setAccessCheck(Cancellation check) { accessCheck = Objects.requireNonNull(check); }

    public JSONObject capabilities() throws Exception {
        boolean connect = permission(connectPermission()), scan = permission(scanPermission());
        return new JSONObject().put("supported", adapter != null)
                .put("enabled", adapter == null ? false : connect ? adapter.isEnabled() : JSONObject.NULL)
                .put("scanPermission", scan).put("connectPermission", connect)
                .put("ble", context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
                .put("transports", new JSONArray().put("ble_gatt_client").put("rfcomm"));
    }

    public JSONObject execute(JSONObject args, String owner, Cancellation cancellation) throws Exception {
        cancellation.check();
        if (owner == null || owner.isBlank()) throw error("invalid_argument", R.string.hardware_bt_argument);
        String operation = args.optString("operation", "status");
        if (operation.equals("disconnect")) {
            Session session = owned(args.optString("sessionId"), owner);
            closeSession(session); return new JSONObject().put("status", "closed").put("sessionId", session.id);
        }
        if (operation.equals("status")) {
            JSONObject result = capabilities();
            JSONArray connections = new JSONArray(), bonded = new JSONArray();
            for (Session session : sessions.values()) if (session.owner.equals(owner)) connections.put(session.status());
            if (adapter != null && permission(connectPermission())) {
                for (BluetoothDevice device : adapter.getBondedDevices()) {
                    if (bonded.length() == 100) break;
                    DeviceRef ref = remember(device, owner);
                    bonded.put(deviceInfo(device, ref.id).put("name", nullable(device.getName()))
                            .put("bondState", device.getBondState()).put("serviceUuids", uuids(device.getUuids())));
                }
            }
            return result.put("connections", connections).put("bondedDevices", bonded);
        }
        requireAdapter();
        if (operation.equals("scan")) return scan(args, owner, cancellation);
        requirePermission(connectPermission());
        if (operation.equals("services")) return queryServices(args, owner, cancellation);
        if (operation.equals("bond")) {
            DeviceRef ref = device(args.optString("deviceRef"), owner);
            if (ref.device.getBondState() == BluetoothDevice.BOND_BONDED)
                return new JSONObject().put("status", "already_bonded").put("deviceRef", ref.id);
            cancellation.dispatched();
            boolean accepted = ref.device.createBond();
            return new JSONObject().put("status", accepted ? "bond_requested" : "rejected")
                    .put("deviceRef", ref.id).put("bondState", ref.device.getBondState());
        }
        if (operation.equals("connect")) return connect(args, owner, cancellation);
        Session session = owned(args.optString("sessionId"), owner);
        session.lock(cancellation);
        try {
            requireConnected(session); accessCheck.check(); cancellation.check();
            return switch (operation) {
                case "discover" -> discover(session, args, cancellation);
                case "read" -> read(session, args, cancellation);
                case "write" -> write(session, args, cancellation);
                case "subscribe" -> subscribe(session, args, cancellation);
                case "events" -> events(session, args);
                default -> throw error("invalid_argument", R.string.hardware_bt_argument);
            };
        } finally { session.operationLock.unlock(); }
    }

    private JSONObject scan(JSONObject args, String owner, Cancellation cancellation) throws Exception {
        requirePermission(scanPermission());
        String transport = args.optString("transport", "ble");
        int duration = HardwareSupport.integer(args, "durationMs", 3000, 100, 10000);
        int limit = HardwareSupport.integer(args, "limit", 50, 1, 100);
        AtomicBoolean stopped = new AtomicBoolean();
        if (scans.putIfAbsent(owner, stopped) != null) throw error("bluetooth_busy", R.string.hardware_bt_busy);
        Map<String, JSONObject> rows = Collections.synchronizedMap(new LinkedHashMap<>());
        AtomicReference<Exception> callbackFailure = new AtomicReference<>();
        long started = System.currentTimeMillis();
        try {
            if (transport.equals("ble")) {
                BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
                if (scanner == null) throw error("bluetooth_unavailable", R.string.hardware_bt_unavailable);
                CompletableFuture<Integer> failure = new CompletableFuture<>();
                ScanCallback callback = new ScanCallback() {
                    @Override public void onScanResult(int type, ScanResult result) {
                        collectBle(result, rows, owner, limit, stopped, callbackFailure);
                    }
                    @Override public void onBatchScanResults(List<ScanResult> results) {
                        for (ScanResult result : results) collectBle(result, rows, owner, limit, stopped, callbackFailure);
                    }
                    @Override public void onScanFailed(int code) { failure.complete(code); }
                };
                cancellation.dispatched();
                scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback);
                try {
                    waitScan(duration, stopped, callbackFailure, cancellation);
                    if (failure.isDone()) throw error("bluetooth_scan_failed", R.string.hardware_bt_failed)
                            .withDetails(new JSONObject().put("androidStatus", failure.getNow(-1)));
                } finally { stopped.set(true); if (permission(scanPermission())) scanner.stopScan(callback); }
            } else if (transport.equals("classic")) {
                requirePermission(connectPermission());
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override public void onReceive(Context ignored, Intent intent) {
                        if (!BluetoothDevice.ACTION_FOUND.equals(intent.getAction()) || stopped.get()) return;
                        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                        if (device == null) return;
                        try {
                            accessCheck.check();
                            synchronized (rows) {
                                if (!rows.containsKey(device.getAddress()) && rows.size() >= limit) return;
                                DeviceRef ref = remember(device, owner);
                                rows.put(device.getAddress(), deviceInfo(device, ref.id)
                                        .put("name", nullable(device.getName()))
                                        .put("rssiDbm", intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE))
                                        .put("observedAt", System.currentTimeMillis()).put("transport", "classic"));
                            }
                        } catch (Exception failure) { callbackFailure.compareAndSet(null, failure); stopped.set(true); }
                    }
                };
                IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                else context.registerReceiver(receiver, filter);
                try {
                    if (adapter.isDiscovering()) throw error("bluetooth_busy", R.string.hardware_bt_busy);
                    cancellation.dispatched();
                    if (!adapter.startDiscovery()) throw error("bluetooth_rejected", R.string.hardware_bt_rejected);
                    try { waitScan(duration, stopped, callbackFailure, cancellation); }
                    finally { stopped.set(true); if (permission(scanPermission())) adapter.cancelDiscovery(); }
                } finally { context.unregisterReceiver(receiver); }
            } else throw error("invalid_argument", R.string.hardware_bt_argument);
            cancellation.check();
            JSONArray results = new JSONArray();
            synchronized (rows) { for (JSONObject row : rows.values()) results.put(row); }
            return new JSONObject().put("status", "completed").put("transport", transport)
                    .put("devices", results).put("startedAt", started).put("finishedAt", System.currentTimeMillis())
                    .put("limitReached", rows.size() >= limit);
        } finally { scans.remove(owner, stopped); }
    }

    private void collectBle(ScanResult result, Map<String, JSONObject> rows, String owner, int limit, AtomicBoolean stopped,
                            AtomicReference<Exception> callbackFailure) {
        if (stopped.get()) return;
        try {
            accessCheck.check();
            BluetoothDevice device = result.getDevice();
            synchronized (rows) {
                if (!rows.containsKey(device.getAddress()) && rows.size() >= limit) return;
                DeviceRef ref = remember(device, owner);
                ScanRecord record = result.getScanRecord();
                JSONObject row = deviceInfo(device, ref.id).put("rssiDbm", result.getRssi())
                        .put("timestampNanos", Long.toString(result.getTimestampNanos()))
                        .put("observedAt", System.currentTimeMillis()).put("transport", "ble")
                        .put("connectable", result.isConnectable());
                if (record != null) {
                    row.put("name", nullable(record.getDeviceName())).put("advertisementHex", BluetoothRules.hex(record.getBytes()))
                            .put("serviceUuids", uuids(record.getServiceUuids() == null ? null : record.getServiceUuids().toArray(new ParcelUuid[0])));
                    JSONArray manufacturer = new JSONArray();
                    android.util.SparseArray<byte[]> entries = record.getManufacturerSpecificData();
                    for (int i = 0; i < entries.size(); i++) manufacturer.put(new JSONObject().put("companyId", entries.keyAt(i))
                            .put("hex", BluetoothRules.hex(entries.valueAt(i))));
                    JSONArray serviceData = new JSONArray();
                    for (Map.Entry<ParcelUuid, byte[]> entry : record.getServiceData().entrySet())
                        serviceData.put(new JSONObject().put("uuid", entry.getKey().toString()).put("hex", BluetoothRules.hex(entry.getValue())));
                    row.put("manufacturerData", manufacturer).put("serviceData", serviceData);
                }
                rows.put(device.getAddress(), row);
            }
        } catch (Exception failure) { callbackFailure.compareAndSet(null, failure); stopped.set(true); }
    }

    private void waitScan(int duration, AtomicBoolean stopped, AtomicReference<Exception> callbackFailure, Cancellation cancellation) throws Exception {
        long until = SystemClock.elapsedRealtime() + duration;
        while (SystemClock.elapsedRealtime() < until) {
            cancellation.check(); accessCheck.check(); requirePermission(scanPermission());
            if (callbackFailure.get() != null) throw callbackFailure.get();
            if (stopped.get()) throw new InterruptedException();
            Thread.sleep(Math.min(50, Math.max(1, until - SystemClock.elapsedRealtime())));
        }
    }

    private JSONObject connect(JSONObject args, String owner, Cancellation cancellation) throws Exception {
        DeviceRef ref = device(args.optString("deviceRef"), owner);
        String transport = args.optString("transport", "ble");
        if (!Set.of("ble", "rfcomm").contains(transport)) throw error("invalid_argument", R.string.hardware_bt_argument);
        Session session = new Session(owner, ref, transport);
        synchronized (sessions) {
            if (sessions.size() >= 8) throw error("bluetooth_session_limit", R.string.hardware_bt_limit);
            sessions.put(session.id, session);
        }
        session.lock(cancellation);
        try {
            cancellation.dispatched(); accessCheck.check();
            if (transport.equals("ble")) {
                session.pending = new Pending("connect", null);
                session.gatt = ref.device.connectGatt(context, false, callback(session), BluetoothDevice.TRANSPORT_LE);
                if (session.gatt == null) throw error("bluetooth_rejected", R.string.hardware_bt_rejected);
                complete(session, session.pending, args, cancellation);
                if (args.has("mtu")) {
                    int mtu = HardwareSupport.integer(args, "mtu", 23, 23, 517);
                    Pending pending = begin(session, "mtu", null);
                    cancellation.dispatched();
                    if (!session.gatt.requestMtu(mtu)) throw error("bluetooth_rejected", R.string.hardware_bt_rejected);
                    complete(session, pending, args, cancellation);
                }
            } else {
                UUID uuid;
                try { uuid = BluetoothRules.uuid(args.opt("uuid")); }
                catch (IllegalArgumentException invalid) { throw error("invalid_argument", R.string.hardware_bt_argument); }
                session.socket = ref.device.createRfcommSocketToServiceRecord(uuid);
                CompletableFuture<Void> connected = background(() -> { session.socket.connect(); return null; });
                waitFor(connected, args, cancellation);
                session.connected = true;
            }
            cancellation.check();
            return session.status().put("status", "connected");
        } catch (Exception failure) { closeSession(session); throw failure; }
        finally { session.operationLock.unlock(); }
    }

    private JSONObject queryServices(JSONObject args, String owner, Cancellation cancellation) throws Exception {
        DeviceRef ref = device(args.optString("deviceRef"), owner);
        CompletableFuture<ParcelUuid[]> result = new CompletableFuture<>();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (!BluetoothDevice.ACTION_UUID.equals(intent.getAction()) || !ref.device.equals(device)) return;
                android.os.Parcelable[] values = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID);
                List<ParcelUuid> uuids = new ArrayList<>();
                if (values != null) for (android.os.Parcelable value : values) if (value instanceof ParcelUuid uuid) uuids.add(uuid);
                result.complete(uuids.toArray(new ParcelUuid[0]));
            }
        };
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_UUID);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver, filter);
        try {
            cancellation.dispatched();
            if (!ref.device.fetchUuidsWithSdp()) throw error("bluetooth_rejected", R.string.hardware_bt_rejected);
            return new JSONObject().put("status", "service_query_completed").put("deviceRef", ref.id)
                    .put("serviceUuids", uuids(waitFor(result, args, cancellation))).put("source", "android_sdp")
                    .put("observedAt", System.currentTimeMillis());
        } finally { context.unregisterReceiver(receiver); }
    }

    private JSONObject discover(Session session, JSONObject args, Cancellation cancellation) throws Exception {
        requireGatt(session);
        Pending pending = begin(session, "discover", null);
        cancellation.dispatched();
        if (!session.gatt.discoverServices()) { session.pending = null; throw error("bluetooth_rejected", R.string.hardware_bt_rejected); }
        complete(session, pending, args, cancellation);
        session.attributes.clear();
        JSONArray services = new JSONArray();
        for (BluetoothGattService service : session.gatt.getServices()) {
            JSONObject item = new JSONObject().put("uuid", service.getUuid().toString()).put("instanceId", service.getInstanceId());
            JSONArray characteristics = new JSONArray();
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                String ref = session.addAttribute(characteristic);
                JSONArray descriptors = new JSONArray();
                for (BluetoothGattDescriptor descriptor : characteristic.getDescriptors())
                    descriptors.put(new JSONObject().put("attributeRef", session.addAttribute(descriptor))
                            .put("uuid", descriptor.getUuid().toString()).put("permissions", descriptor.getPermissions()));
                characteristics.put(new JSONObject().put("attributeRef", ref).put("uuid", characteristic.getUuid().toString())
                        .put("instanceId", characteristic.getInstanceId()).put("properties", characteristic.getProperties())
                        .put("permissions", characteristic.getPermissions()).put("descriptors", descriptors));
            }
            services.put(item.put("characteristics", characteristics));
        }
        return session.status().put("status", "services_discovered").put("services", services);
    }

    private JSONObject read(Session session, JSONObject args, Cancellation cancellation) throws Exception {
        if (session.transport.equals("rfcomm")) return readSocket(session, args, cancellation);
        Object attribute = attribute(session, args);
        if (attribute instanceof BluetoothGattCharacteristic characteristic) {
            if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0)
                throw error("bluetooth_attribute_unavailable", R.string.hardware_bt_attribute);
        }
        Pending pending = begin(session, "read", attribute);
        cancellation.dispatched();
        boolean accepted;
        if (attribute instanceof BluetoothGattCharacteristic characteristic) {
            accepted = session.gatt.readCharacteristic(characteristic);
        } else accepted = session.gatt.readDescriptor((BluetoothGattDescriptor) attribute);
        if (!accepted) { session.pending = null; throw error("bluetooth_rejected", R.string.hardware_bt_rejected); }
        Reply reply = complete(session, pending, args, cancellation);
        return payload(session, reply.value).put("status", "gatt_read_completed").put("attributeRef", args.getString("attributeRef"));
    }

    private JSONObject write(Session session, JSONObject args, Cancellation cancellation) throws Exception {
        byte[] bytes = data.readPayload(args, session.owner, session.transport.equals("rfcomm") ? 65536 : 512);
        if (session.transport.equals("rfcomm")) {
            cancellation.dispatched();
            try {
                waitFor(background(() -> { session.socket.getOutputStream().write(bytes); return null; }), args, cancellation);
            } catch (Exception failure) { closeSession(session); throw failure; }
            return session.status().put("status", "stream_write_completed").put("bytes", bytes.length).put("deviceExecutionVerified", false);
        }
        Object attribute = attribute(session, args);
        int writeType;
        try { writeType = BluetoothRules.writeType(args.optString("writeType", "with_response")); }
        catch (IllegalArgumentException invalid) { throw error("invalid_argument", R.string.hardware_bt_argument); }
        int effectiveWriteType = attribute instanceof BluetoothGattCharacteristic ? writeType : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        try { BluetoothRules.writeLength(bytes.length, session.mtu, effectiveWriteType); }
        catch (IllegalArgumentException invalid) { throw error("payload_too_large", R.string.hardware_bt_size)
                .withDetails(new JSONObject().put("negotiatedMtu", session.mtu)
                        .put("maxBytes", Math.min(512, session.mtu - (effectiveWriteType == 4 ? 15 : 3)))); }
        if (attribute instanceof BluetoothGattCharacteristic characteristic) {
            int property = writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT ? BluetoothGattCharacteristic.PROPERTY_WRITE
                    : writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE ? BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                    : BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE;
            if ((characteristic.getProperties() & property) == 0) throw error("bluetooth_attribute_unavailable", R.string.hardware_bt_attribute);
        }
        Pending pending = begin(session, "write", attribute);
        cancellation.dispatched();
        boolean accepted;
        if (attribute instanceof BluetoothGattCharacteristic characteristic) {
            if (Build.VERSION.SDK_INT >= 33) accepted = session.gatt.writeCharacteristic(characteristic, bytes, writeType) == BluetoothStatusCodes.SUCCESS;
            else { characteristic.setValue(bytes); characteristic.setWriteType(writeType); accepted = session.gatt.writeCharacteristic(characteristic); }
        } else {
            BluetoothGattDescriptor descriptor = (BluetoothGattDescriptor) attribute;
            if (Build.VERSION.SDK_INT >= 33) accepted = session.gatt.writeDescriptor(descriptor, bytes) == BluetoothStatusCodes.SUCCESS;
            else { descriptor.setValue(bytes); accepted = session.gatt.writeDescriptor(descriptor); }
        }
        if (!accepted) { session.pending = null; throw error("bluetooth_rejected", R.string.hardware_bt_rejected); }
        complete(session, pending, args, cancellation);
        return session.status().put("status", "gatt_write_completed").put("bytes", bytes.length)
                .put("writeType", args.optString("writeType", "with_response")).put("deviceExecutionVerified", false);
    }

    private JSONObject subscribe(Session session, JSONObject args, Cancellation cancellation) throws Exception {
        Object attribute = attribute(session, args);
        if (!(attribute instanceof BluetoothGattCharacteristic characteristic))
            throw error("bluetooth_attribute_unavailable", R.string.hardware_bt_attribute);
        String mode = args.optString("mode", "notification");
        boolean enabled = !mode.equals("off");
        int property = mode.equals("indication") ? BluetoothGattCharacteristic.PROPERTY_INDICATE : BluetoothGattCharacteristic.PROPERTY_NOTIFY;
        if (!Set.of("off", "notification", "indication").contains(mode)
                || enabled && (characteristic.getProperties() & property) == 0)
            throw error("bluetooth_attribute_unavailable", R.string.hardware_bt_attribute);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
        if (descriptor == null) throw error("bluetooth_attribute_unavailable", R.string.hardware_bt_attribute);
        cancellation.dispatched();
        if (!session.gatt.setCharacteristicNotification(characteristic, enabled)) throw error("bluetooth_rejected", R.string.hardware_bt_rejected);
        byte[] bytes = !enabled ? BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                : mode.equals("indication") ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        Pending pending = begin(session, "write", descriptor);
        boolean accepted;
        if (Build.VERSION.SDK_INT >= 33) accepted = session.gatt.writeDescriptor(descriptor, bytes) == BluetoothStatusCodes.SUCCESS;
        else { descriptor.setValue(bytes); accepted = session.gatt.writeDescriptor(descriptor); }
        if (!accepted) { closeSession(session); throw error("bluetooth_rejected", R.string.hardware_bt_rejected); }
        complete(session, pending, args, cancellation);
        return session.status().put("status", enabled ? "subscribed" : "unsubscribed").put("mode", mode)
                .put("attributeRef", args.getString("attributeRef"));
    }

    private JSONObject events(Session session, JSONObject args) throws Exception {
        int limit = HardwareSupport.integer(args, "limit", 20, 1, 32);
        Object raw = args.opt("cursor");
        long cursor = raw == null ? 0 : raw instanceof Number number && number.longValue() == number.doubleValue() ? number.longValue() : -1;
        try { return session.events.read(cursor, limit).put("sessionId", session.id).put("status", "available"); }
        catch (IllegalArgumentException invalid) { throw error("invalid_argument", R.string.hardware_bt_argument); }
    }

    private JSONObject readSocket(Session session, JSONObject args, Cancellation cancellation) throws Exception {
        int count = HardwareSupport.integer(args, "maxBytes", 4096, 1, 65536);
        int timeout = HardwareSupport.integer(args, "timeoutMs", 3000, 100, 10000);
        long until = SystemClock.elapsedRealtime() + Math.min(timeout, cancellation.remainingMillis(timeout));
        while (session.socket.getInputStream().available() == 0) {
            cancellation.check(); accessCheck.check(); requireConnected(session);
            if (SystemClock.elapsedRealtime() >= until)
                return session.status().put("status", "no_data").put("bytes", 0);
            Thread.sleep(25);
        }
        try {
            byte[] bytes = waitFor(background(() -> {
                byte[] buffer = new byte[Math.min(count, Math.max(1, session.socket.getInputStream().available()))];
                int size = session.socket.getInputStream().read(buffer);
                if (size < 0) throw new IOException("end_of_stream");
                return Arrays.copyOf(buffer, size);
            }), args, cancellation);
            return payload(session, bytes).put("status", "stream_read_completed");
        } catch (Exception failure) { closeSession(session); throw failure; }
    }

    private JSONObject payload(Session session, byte[] bytes) throws Exception {
        JSONObject result = session.status().put("bytes", bytes.length);
        if (bytes.length <= 512) result.put("hex", BluetoothRules.hex(bytes));
        return result.put("data", data.put(session.owner, bytes,
                new JSONObject().put("source", "bluetooth").put("transport", session.transport).put("capturedAt", System.currentTimeMillis()), false, "bluetooth"));
    }

    private BluetoothGattCallback callback(Session session) {
        return new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt gatt, int status, int state) {
                if (!session.callbackAllowed()) {
                    // A connect callback can precede assigning session.gatt.
                    try { gatt.close(); }
                    catch (SecurityException revoked) { session.closeFailure = "system_permission_revoked"; }
                    return;
                }
                session.gatt = gatt;
                if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                    session.connected = true; session.reply("connect", null, status, null);
                } else if (state == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    session.connected = false; session.replyAny(status == BluetoothGatt.GATT_SUCCESS ? -1 : status); closeSession(session);
                }
            }
            @Override public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
                if (!session.callbackAllowed()) return;
                if (status == BluetoothGatt.GATT_SUCCESS) session.mtu = mtu;
                session.reply("mtu", null, status, null);
            }
            @Override public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (session.callbackAllowed()) session.reply("discover", null, status, null);
            }
            @Override public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
                if (session.callbackAllowed()) session.reply("read", characteristic, status, value);
            }
            @Override public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                if (Build.VERSION.SDK_INT < 33) onCharacteristicRead(gatt, characteristic, characteristic.getValue(), status);
            }
            @Override public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status, byte[] value) {
                if (session.callbackAllowed()) session.reply("read", descriptor, status, value);
            }
            @Override public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                if (Build.VERSION.SDK_INT < 33) onDescriptorRead(gatt, descriptor, status, descriptor.getValue());
            }
            @Override public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                if (session.callbackAllowed()) session.reply("write", characteristic, status, null);
            }
            @Override public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                if (session.callbackAllowed()) session.reply("write", descriptor, status, null);
            }
            @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
                if (!session.callbackAllowed()) return;
                try {
                    String ref = session.refFor(characteristic);
                    if (ref == null) return;
                    session.events.add(new JSONObject().put("attributeRef", ref).put("uuid", characteristic.getUuid().toString())
                            .put("hex", BluetoothRules.hex(value)).put("bytes", value.length)
                            .put("capturedAt", System.currentTimeMillis()).put("elapsedRealtimeNanos", Long.toString(SystemClock.elapsedRealtimeNanos())));
                } catch (org.json.JSONException invalid) { closeSession(session); }
            }
            @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                if (Build.VERSION.SDK_INT < 33) onCharacteristicChanged(gatt, characteristic, characteristic.getValue());
            }
            @Override public void onServiceChanged(BluetoothGatt gatt) {
                if (!session.callbackAllowed()) return;
                session.attributes.clear(); session.replyAny(-1);
                session.events.add(event("services_changed"));
            }
        };
    }

    private static JSONObject event(String type) {
        try { return new JSONObject().put("type", type).put("capturedAt", System.currentTimeMillis()); }
        catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    private Pending begin(Session session, String kind, Object target) throws Exception {
        requireGatt(session);
        if (session.pending != null) throw error("bluetooth_busy", R.string.hardware_bt_busy);
        Pending pending = new Pending(kind, target); session.pending = pending; return pending;
    }

    private Reply complete(Session session, Pending pending, JSONObject args, Cancellation cancellation) throws Exception {
        try {
            Reply reply = waitFor(pending.future, args, cancellation);
            if (reply.status != BluetoothGatt.GATT_SUCCESS) throw error("bluetooth_operation_failed", R.string.hardware_bt_failed)
                    .withDetails(new JSONObject().put("androidStatus", reply.status).put("operation", pending.kind));
            return reply;
        } catch (Exception failure) {
            // Discard late callbacks after timeout/cancel instead of matching a later request.
            closeSession(session); throw failure;
        } finally { if (session.pending == pending) session.pending = null; }
    }

    private <T> T waitFor(CompletableFuture<T> future, JSONObject args, Cancellation cancellation) throws Exception {
        int timeout = HardwareSupport.integer(args, "timeoutMs", 8000, 100, 10000);
        long until = SystemClock.elapsedRealtime() + Math.min(timeout, cancellation.remainingMillis(timeout));
        for (;;) {
            cancellation.check(); accessCheck.check(); requirePermission(connectPermission());
            long left = until - SystemClock.elapsedRealtime();
            if (left <= 0) throw error("bluetooth_timeout", R.string.hardware_bt_timeout);
            try { return future.get(Math.min(50, left), TimeUnit.MILLISECONDS); }
            catch (TimeoutException waiting) { /* Recheck authorization while platform I/O is pending. */ }
            catch (ExecutionException failed) {
                throw error("bluetooth_io_failed", R.string.hardware_bt_failed)
                        .withDetails(new JSONObject().put("failureType", failed.getCause().getClass().getSimpleName()));
            }
        }
    }

    private interface Io<T> { T run() throws Exception; }
    private static <T> CompletableFuture<T> background(Io<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread worker = new Thread(() -> { try { result.complete(action.run()); } catch (Exception failure) { result.completeExceptionally(failure); } }, "droy-bluetooth-io");
        worker.setDaemon(true); worker.start(); return result;
    }

    private void requireAdapter() throws Exception {
        if (adapter == null) throw error("bluetooth_unavailable", R.string.hardware_bt_unavailable);
        if (permission(connectPermission()) && !adapter.isEnabled()) throw error("bluetooth_disabled", R.string.hardware_bt_off);
    }
    private void requireConnected(Session session) throws Exception {
        if (session.closed || !session.connected) throw error("bluetooth_disconnected", R.string.hardware_bt_disconnected);
    }
    private void requireGatt(Session session) throws Exception {
        requireConnected(session);
        if (session.gatt == null) throw error("bluetooth_attribute_unavailable", R.string.hardware_bt_attribute);
    }
    private void requirePermission(String permission) throws Exception {
        if (!permission(permission)) throw error("system_permission_denied", R.string.hardware_bt_permission);
    }
    private boolean permission(String value) { return context.checkSelfPermission(value) == PackageManager.PERMISSION_GRANTED; }
    private static String connectPermission() { return Build.VERSION.SDK_INT >= 31 ? Manifest.permission.BLUETOOTH_CONNECT : Manifest.permission.BLUETOOTH; }
    private static String scanPermission() { return Build.VERSION.SDK_INT >= 31 ? Manifest.permission.BLUETOOTH_SCAN : Manifest.permission.ACCESS_FINE_LOCATION; }
    private ActionException error(String code, int resource) { return HardwareSupport.error(context, code, resource); }
    private static Object nullable(Object value) { return value == null ? JSONObject.NULL : value; }
    private static JSONArray uuids(ParcelUuid[] uuids) {
        JSONArray array = new JSONArray(); if (uuids != null) for (ParcelUuid uuid : uuids) array.put(uuid.toString()); return array;
    }
    private JSONObject deviceInfo(BluetoothDevice device, String ref) throws Exception {
        return new JSONObject().put("deviceRef", ref).put("address", device.getAddress());
    }

    private DeviceRef remember(BluetoothDevice device, String owner) throws Exception {
        synchronized (devices) {
            for (DeviceRef ref : devices.values()) if (ref.owner.equals(owner) && ref.device.equals(device)) return ref;
            if (devices.size() >= 512) throw error("bluetooth_reference_limit", R.string.hardware_bt_limit);
            DeviceRef ref = new DeviceRef(UUID.randomUUID().toString(), owner, device); devices.put(ref.id, ref); return ref;
        }
    }
    private DeviceRef device(String id, String owner) throws Exception {
        DeviceRef ref = devices.get(id);
        if (ref == null || !ref.owner.equals(owner)) throw error("bluetooth_reference_invalid", R.string.hardware_bt_reference);
        return ref;
    }
    private Session owned(String id, String owner) throws Exception {
        Session session = sessions.get(id);
        if (session == null || !session.owner.equals(owner)) throw error("bluetooth_reference_invalid", R.string.hardware_bt_reference);
        return session;
    }
    private Object attribute(Session session, JSONObject args) throws Exception {
        requireGatt(session);
        Object value = session.attributes.get(args.optString("attributeRef"));
        if (value == null) throw error("bluetooth_reference_invalid", R.string.hardware_bt_reference);
        return value;
    }

    public boolean hasOwnerSessions(String owner) {
        for (Session session : sessions.values())
            if (!session.closed && session.owner.equals(owner)) return true;
        return false;
    }

    public void closeOwner(String owner) {
        AtomicBoolean scan = scans.get(owner); if (scan != null) scan.set(true);
        for (Session session : sessions.values()) if (session.owner.equals(owner)) closeSession(session);
        devices.entrySet().removeIf(entry -> entry.getValue().owner.equals(owner));
    }
    public void close() {
        for (AtomicBoolean scan : scans.values()) scan.set(true);
        for (Session session : sessions.values()) closeSession(session);
        devices.clear();
    }
    private void closeSession(Session session) {
        synchronized (session) {
            if (session.closed) return;
            session.closed = true; session.connected = false;
            sessions.remove(session.id, session); session.attributes.clear();
            session.replyAny(-1);
            if (session.gatt != null) {
                try { session.gatt.close(); }
                catch (SecurityException revoked) { session.closeFailure = "system_permission_revoked"; }
            }
            if (session.socket != null) {
                try { session.socket.close(); }
                catch (IOException failed) { session.closeFailure = failed.getClass().getSimpleName(); }
            }
        }
    }

    private record DeviceRef(String id, String owner, BluetoothDevice device) {}
    private record Reply(int status, byte[] value) {}
    private static final class Pending {
        final String kind; final Object target; final CompletableFuture<Reply> future = new CompletableFuture<>();
        Pending(String kind, Object target) { this.kind = kind; this.target = target; }
    }
    private final class Session {
        final String id = UUID.randomUUID().toString(), owner, transport;
        final DeviceRef device;
        final ReentrantLock operationLock = new ReentrantLock();
        final Map<String, Object> attributes = new ConcurrentHashMap<>();
        final BluetoothEventBuffer events = new BluetoothEventBuffer(64);
        volatile BluetoothGatt gatt; volatile BluetoothSocket socket;
        volatile boolean connected, closed; volatile int mtu = 23;
        volatile Pending pending; volatile String closeFailure;
        Session(String owner, DeviceRef device, String transport) { this.owner = owner; this.device = device; this.transport = transport; }
        void lock(Cancellation cancellation) throws Exception {
            while (!operationLock.tryLock(50, TimeUnit.MILLISECONDS)) { cancellation.check(); accessCheck.check(); }
        }
        String addAttribute(Object attribute) { String ref = UUID.randomUUID().toString(); attributes.put(ref, attribute); return ref; }
        String refFor(Object attribute) {
            for (Map.Entry<String, Object> entry : attributes.entrySet()) if (entry.getValue() == attribute) return entry.getKey();
            return null;
        }
        boolean callbackAllowed() {
            if (closed) return false;
            try { accessCheck.check(); requirePermission(connectPermission()); return true; }
            catch (Exception revoked) { closeSession(this); return false; }
        }
        void reply(String kind, Object target, int status, byte[] value) {
            Pending operation = pending;
            if (operation != null && operation.kind.equals(kind) && operation.target == target)
                operation.future.complete(new Reply(status, value == null ? new byte[0] : value.clone()));
        }
        void replyAny(int status) { Pending operation = pending; if (operation != null) operation.future.complete(new Reply(status, new byte[0])); }
        JSONObject status() throws Exception {
            return new JSONObject().put("sessionId", id).put("deviceRef", device.id).put("transport", transport)
                    .put("connected", connected && !closed).put("negotiatedMtu", transport.equals("ble") ? mtu : JSONObject.NULL)
                    .put("closeFailure", nullable(closeFailure));
        }
    }
}
