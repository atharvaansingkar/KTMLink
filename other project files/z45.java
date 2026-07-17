package defpackage;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/* JADX INFO: compiled from: r8-map-id-50dea58a577de17b3fb15fa361f956fd9b2fc093cbacb538b411be86303f9f04 */
/* JADX INFO: loaded from: classes.dex */
public final class z45 extends BluetoothGattCallback {
    public final i96 a;
    public final mc0 b;
    public final int c;
    public final xf5 d;
    public final Context e;
    public BluetoothGatt f;
    public final lq0 g;
    public tv4 h;
    public ArrayList i;
    public final AtomicBoolean j;
    public final Handler k;
    public final List l;

    public z45(i96 i96Var, mc0 mc0Var, int i, xf5 xf5Var, Context context) {
        mc0Var.getClass();
        xf5Var.getClass();
        this.a = i96Var;
        this.b = mc0Var;
        this.c = i;
        this.d = xf5Var;
        this.e = context;
        vg7 vg7VarB = ana.b();
        pu1 pu1Var = q22.a;
        this.g = dv9.a(qu9.d(vg7VarB, ll4.a));
        this.j = new AtomicBoolean(false);
        this.k = new Handler(Looper.getMainLooper());
        this.l = Collections.synchronizedList(new ArrayList());
    }

    public static eq5 a(int i, int i2) {
        return i != -2 ? i != -1 ? i != 0 ? i != 8 ? i != 19 ? i != 22 ? i != 62 ? i != 133 ? new eq5(tg5.r("Gatt disconnect status ", i), "Unknown or device-specific disconnect reason") : new eq5("Generic GATT error", "Android returned the catch-all GATT 133 error") : new eq5("Connection failed to establish", "Link could not be established or was lost immediately") : new eq5("Local host terminated", "Android or app side terminated the BLE link") : new eq5("Peer terminated connection", "Bike or platform reported remote user termination") : new eq5("Connection timeout", "BLE link supervision likely timed out") : i2 == 0 ? new eq5("Link closed", "Remote bike or Android stack closed the connection cleanly") : new eq5("Link state changed", "Gatt reported a non-error state change") : new eq5("Manual disconnect during connect", "App triggered cleanup before connection finished") : new eq5("Forced disconnect fallback", "Disconnect callback never arrived, GATT was force-closed");
    }

    public final void b(int i) {
        if (this.j.compareAndSet(false, true)) {
            this.k.removeCallbacksAndMessages(null);
            tv4 tv4Var = this.h;
            if (tv4Var != null) {
                tv4Var.h("DISCONNECTED");
                HashMap map = lv4.a;
                String str = tv4Var.a.a;
                HashMap map2 = lv4.a;
                tv4 tv4Var2 = (tv4) map2.get(str);
                if (tv4Var2 != null && tv4Var2.d == tv4Var.d) {
                    map2.remove(str);
                }
                mv4 mv4Var = tv4Var.i;
                if (mv4Var != null) {
                    tv4Var.f.removeCallbacks(mv4Var);
                }
                tv4Var.i = null;
                tv4Var.f.removeCallbacksAndMessages(null);
                tv4Var.h.clear();
                tv4Var.t = null;
                tv4Var.k = 0L;
                tv4Var.l = 0L;
                tv4Var.n = false;
            }
            this.h = null;
            this.i = null;
            dv9.d(this.g, null);
            this.a.g(this.b, i, Integer.valueOf(this.c));
        }
    }

    @Override // android.bluetooth.BluetoothGattCallback
    public final void onCharacteristicChanged(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic bluetoothGattCharacteristic, byte[] bArr) {
        bluetoothGatt.getClass();
        bluetoothGattCharacteristic.getClass();
        bArr.getClass();
        tv4 tv4Var = this.h;
        if (tv4Var == null) {
            return;
        }
        p0b.d(this.g, null, null, new ua(bluetoothGattCharacteristic, tv4Var, bArr, bluetoothGatt, this, (ue1) null, 10), 3);
    }

    @Override // android.bluetooth.BluetoothGattCallback
    public final void onCharacteristicWrite(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic bluetoothGattCharacteristic, int i) {
        bluetoothGatt.getClass();
        bluetoothGattCharacteristic.getClass();
        try {
            if (!this.b.c()) {
                gj4.d(gj4.a, "BIKECONNECT", "Not connected, cannot write characteristic", null, 12);
                return;
            }
            tv4 tv4Var = this.h;
            if (tv4Var == null || tv4Var.n) {
                return;
            }
            mv4 mv4Var = tv4Var.i;
            if (mv4Var != null) {
                tv4Var.f.removeCallbacks(mv4Var);
            }
            tv4Var.i = null;
            if (i == 0) {
                tv4Var.k = System.currentTimeMillis();
                tv4Var.l = 0L;
                tv4Var.t = null;
                tv4Var.j();
                return;
            }
            Object obj = ji0.a;
            String str = this.b.d;
            gi0 gi0Var = gi0.QUEUE;
            ii0 ii0Var = ii0.WARN;
            bluetoothGattCharacteristic.getUuid();
            ji0.a(str, gi0Var, ii0Var, "Characteristic write callback failure", 7072);
            tv4Var.g(i);
        } catch (Exception e) {
            gj4.d(gj4.a, "BIKECONNECT", "Error handling characteristic write", e, 8);
        }
    }

    @Override // android.bluetooth.BluetoothGattCallback
    public final void onConnectionStateChange(BluetoothGatt bluetoothGatt, int i, int i2) {
        lq0 lq0Var = this.g;
        int i3 = this.c;
        bluetoothGatt.getClass();
        try {
            gj4 gj4Var = gj4.a;
            gj4.g("BIKECONNECT", "New state: " + i2 + ", status: " + i + ", Connection ID: " + i3);
            char c = '\n';
            ue1 ue1Var = null;
            mc0 mc0Var = this.b;
            if (i2 != 0) {
                if (i2 == 2) {
                    if (this.h != null) {
                        gj4.k("BIKECONNECT", "Ignoring duplicate STATE_CONNECTED callback for Connection ID: " + i3);
                        return;
                    }
                    gj4.k("BIKECONNECT", "Connected to gatt");
                    Object obj = ji0.a;
                    ji0.a(mc0Var.d, gi0.CONNECTION, null, "Gatt connected", 7112);
                    this.f = bluetoothGatt;
                    this.j.set(false);
                    tv4 tv4Var = new tv4(this.b, bluetoothGatt, this.d, this.c, this.e, new rl3(9, this));
                    this.h = tv4Var;
                    lv4.a(tv4Var);
                    p0b.d(lq0Var, null, null, new cc(bluetoothGatt, ue1Var, c), 3);
                    p0b.d(lq0Var, null, null, new cc(this, ue1Var, 11), 3);
                    return;
                }
                if (i2 != 3) {
                    return;
                }
            }
            boolean zC = mc0Var.c();
            String str = mc0Var.d;
            if (zC) {
                gj4.k("BIKECONNECT", "Disconnected from gatt");
            } else {
                gj4.k("BIKECONNECT", "gatt connection failed");
            }
            eq5 eq5VarA = a(i, i2);
            Object obj2 = ji0.a;
            ji0.a(str, gi0.CONNECTION, ii0.WARN, mc0Var.c() ? "Gatt disconnected" : "Gatt connection failed", 5056);
            ArrayList arrayList = this.i;
            if ((!((Boolean) mc0Var.g.getValue()).booleanValue() || ve7.t(mc0Var.c, "LC", false)) && arrayList != null) {
                StringBuilder sb = new StringBuilder();
                int size = arrayList.size();
                int i4 = 0;
                while (i4 < size) {
                    Object obj3 = arrayList.get(i4);
                    i4++;
                    y45 y45Var = (y45) obj3;
                    sb.append("Service: " + y45Var.a);
                    sb.append(c);
                    ArrayList arrayList2 = y45Var.b;
                    int size2 = arrayList2.size();
                    int i5 = 0;
                    while (i5 < size2) {
                        Object obj4 = arrayList2.get(i5);
                        i5++;
                        x45 x45Var = (x45) obj4;
                        String str2 = x45Var.a;
                        int i6 = x45Var.b;
                        pb1.b(16);
                        String string = Integer.toString(i6, 16);
                        string.getClass();
                        sb.append("  - Characteristic: " + str2 + ", Handle: " + i6 + " (0x" + oe7.L(4, string) + ")");
                        sb.append('\n');
                        c = '\n';
                        arrayList = arrayList;
                    }
                }
                String string2 = sb.toString();
                gj4 gj4Var2 = gj4.a;
                gj4.g("BIKECONNECT", "Bike " + str + " disconnected without pairing. Services info:" + string2);
            }
            try {
                Object objInvoke = bluetoothGatt.getClass().getMethod("refresh", null).invoke(bluetoothGatt, null);
                objInvoke.getClass();
            } catch (Exception e) {
                gj4.d(gj4.a, "BIKECONNECT", "Failed to refresh GATT cache", e, 8);
            }
            b(i);
        } catch (Exception e2) {
            gj4.d(gj4.a, "BIKECONNECT", "Error handling connection state change", e2, 8);
        }
    }

    @Override // android.bluetooth.BluetoothGattCallback
    public final void onDescriptorWrite(BluetoothGatt bluetoothGatt, BluetoothGattDescriptor bluetoothGattDescriptor, int i) {
        bluetoothGatt.getClass();
        bluetoothGattDescriptor.getClass();
        if (i != 0) {
            if (i == 5) {
                gj4.d(gj4.a, "BIKECONNECT", "Insufficient Authentication error", null, 12);
            } else {
                gj4.d(gj4.a, "BIKECONNECT", tg5.r("Descriptor write failed, status: ", i), null, 12);
            }
            Object obj = ji0.a;
            String str = this.b.d;
            gi0 gi0Var = gi0.ERROR;
            ii0 ii0Var = ii0.ERROR;
            bluetoothGattDescriptor.getCharacteristic().getUuid();
            ji0.a(str, gi0Var, ii0Var, "Descriptor write failed", 7072);
            bluetoothGatt.disconnect();
            return;
        }
        gj4 gj4Var = gj4.a;
        gj4.g("BIKECONNECT", "Successfully wrote descriptor for indications, status: GATT_SUCCESS");
        Object obj2 = ji0.a;
        String str2 = this.b.d;
        gi0 gi0Var2 = gi0.QUEUE;
        bluetoothGattDescriptor.getCharacteristic().getUuid();
        ji0.a(str2, gi0Var2, null, "Descriptor write success", 2984);
        tv4 tv4Var = this.h;
        if (tv4Var != null) {
            tv4Var.t = null;
        }
        tv4 tv4Var2 = this.h;
        if (tv4Var2 != null) {
            tv4Var2.j();
        }
    }

    @Override // android.bluetooth.BluetoothGattCallback
    public final void onMtuChanged(BluetoothGatt bluetoothGatt, int i, int i2) {
        bluetoothGatt.getClass();
        super.onMtuChanged(bluetoothGatt, i, i2);
        mc0 mc0Var = this.b;
        try {
            if (i2 != 0) {
                gj4.d(gj4.a, "BIKECONNECT", "MTU change failed with status " + i2 + ". Disconnecting...", null, 12);
                Object obj = ji0.a;
                ji0.a(mc0Var.d, gi0.ERROR, ii0.ERROR, "MTU change failed", 7136);
                bluetoothGatt.disconnect();
                return;
            }
            gj4 gj4Var = gj4.a;
            gj4.b("BIKECONNECT", "MTU changed to " + i);
            Object obj2 = ji0.a;
            ji0.a(mc0Var.d, gi0.CONNECTION, null, "MTU updated", 3016);
            if (!bluetoothGatt.requestConnectionPriority(1)) {
                gj4.k("BIKECONNECT", "Failed to request connection priority");
            }
            tv4 tv4Var = this.h;
            if (tv4Var != null) {
                tv4Var.a(bn0.a(ax0.MAIN_SERVICE), bn0.a(ax0.AUTHENTICATION_REQUEST), "Enable auth indications");
            }
        } catch (Exception e) {
            gj4.d(gj4.a, "BIKECONNECT", "Error handling MTU change", e, 8);
        }
    }

    @Override // android.bluetooth.BluetoothGattCallback
    public final void onServicesDiscovered(BluetoothGatt bluetoothGatt, int i) {
        bluetoothGatt.getClass();
        mc0 mc0Var = this.b;
        try {
            if (i != 0) {
                gj4.d(gj4.a, "BIKECONNECT", "Failed to discover services, status: " + i + ". Disconnecting...", null, 12);
                Object obj = ji0.a;
                ji0.a(mc0Var.d, gi0.ERROR, ii0.ERROR, "Service discovery failed", 7136);
                bluetoothGatt.disconnect();
                return;
            }
            gj4 gj4Var = gj4.a;
            gj4.g("BIKECONNECT", "Services discovered successfully");
            Object obj2 = ji0.a;
            String str = mc0Var.d;
            String str2 = mc0Var.d;
            gi0 gi0Var = gi0.CONNECTION;
            bluetoothGatt.getServices().size();
            ji0.a(str, gi0Var, null, "Services discovered", 3016);
            List<BluetoothGattService> services = bluetoothGatt.getServices();
            services.getClass();
            List<BluetoothGattService> list = services;
            ArrayList arrayList = new ArrayList(d21.g(list, 10));
            for (BluetoothGattService bluetoothGattService : list) {
                String string = bluetoothGattService.getUuid().toString();
                string.getClass();
                List<BluetoothGattCharacteristic> characteristics = bluetoothGattService.getCharacteristics();
                characteristics.getClass();
                List<BluetoothGattCharacteristic> list2 = characteristics;
                ArrayList arrayList2 = new ArrayList(d21.g(list2, 10));
                for (BluetoothGattCharacteristic bluetoothGattCharacteristic : list2) {
                    String string2 = bluetoothGattCharacteristic.getUuid().toString();
                    string2.getClass();
                    arrayList2.add(new x45(string2, bluetoothGattCharacteristic.getInstanceId()));
                }
                arrayList.add(new y45(string, arrayList2));
            }
            this.i = arrayList;
            String strE = w0b.e(arrayList);
            if (strE == null) {
                bluetoothGatt.requestMtu(517);
                return;
            }
            gj4 gj4Var2 = gj4.a;
            gj4.k("BIKECONNECT", "Unsupported BCCU service layout for " + str2 + ": " + strE);
            o67 o67Var = t67.a;
            t67.a("Navigation may not be enabled on " + str2 + ". Contact support.", true);
            Object obj3 = ji0.a;
            ji0.a(str2, gi0.ERROR, ii0.WARN, "Unsupported BCCU service layout", 5056);
            bluetoothGatt.disconnect();
        } catch (Exception e) {
            gj4.d(gj4.a, "BIKECONNECT", "Error handling services discovered", e, 8);
        }
    }

    @Override // android.bluetooth.BluetoothGattCallback
    public final void onCharacteristicChanged(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic bluetoothGattCharacteristic) {
        byte[] bArr;
        bluetoothGatt.getClass();
        bluetoothGattCharacteristic.getClass();
        byte[] value = bluetoothGattCharacteristic.getValue();
        if (value == null || (bArr = (byte[]) value.clone()) == null) {
            return;
        }
        onCharacteristicChanged(bluetoothGatt, bluetoothGattCharacteristic, bArr);
    }
}
