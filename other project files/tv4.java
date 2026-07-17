package defpackage;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/* JADX INFO: compiled from: r8-map-id-50dea58a577de17b3fb15fa361f956fd9b2fc093cbacb538b411be86303f9f04 */
/* JADX INFO: loaded from: classes.dex */
public final class tv4 {
    public final mc0 a;
    public final BluetoothGatt b;
    public final xf5 c;
    public final int d;
    public final rl3 e;
    public final Handler f;
    public final qc0 g;
    public final LinkedHashMap h;
    public mv4 i;
    public int j;
    public long k;
    public long l;
    public final long m;
    public volatile boolean n;
    public r86 o;
    public Date p;
    public final AtomicInteger q;
    public final nz r;
    public final q56 s;
    public volatile pv4 t;

    public tv4(mc0 mc0Var, BluetoothGatt bluetoothGatt, xf5 xf5Var, int i, Context context, rl3 rl3Var) {
        mc0Var.getClass();
        xf5Var.getClass();
        context.getClass();
        this.a = mc0Var;
        this.b = bluetoothGatt;
        this.c = xf5Var;
        this.d = i;
        this.e = rl3Var;
        this.f = new Handler(Looper.getMainLooper());
        this.g = new qc0(i, mc0Var, context);
        this.h = new LinkedHashMap();
        this.m = 10000L;
        int i2 = 0;
        this.q = new AtomicInteger(0);
        this.r = new nz(6);
        int i3 = 1;
        this.s = new q56(new nv4(this, i2), new nv4(this, i3), new ov4(this, i2), new ov4(this, i3), new nv4(this, 2));
    }

    public static byte[] d(byte[] bArr) {
        int length = 16 - (bArr.length % 16);
        int length2 = bArr.length + 16 + length;
        byte[] bArr2 = new byte[length2];
        e86 e86Var = f86.q;
        e86Var.d(bArr2, 0, 16);
        System.arraycopy(bArr, 0, bArr2, 16, bArr.length);
        e86Var.d(bArr2, bArr.length + 16, length2);
        bArr2[length2 - 1] = (byte) length;
        return bArr2;
    }

    public final void a(UUID uuid, UUID uuid2, String str) {
        synchronized (this.h) {
            this.h.put(uuid2 + "-indications", new pv4(ki0.ENABLE_INDICATIONS, uuid, uuid2, null, this.q.incrementAndGet(), str));
            k(str);
        }
        this.f.post(new mv4(this, 0));
    }

    public final void b(UUID uuid, UUID uuid2, String str) {
        synchronized (this.h) {
            this.h.put(uuid2 + "-notifications", new pv4(ki0.ENABLE_NOTIFICATIONS, uuid, uuid2, null, this.q.incrementAndGet(), str));
            k(str);
        }
        this.f.post(new mv4(this, 1));
    }

    public final void c(UUID uuid, UUID uuid2, byte[] bArr, String str) {
        bArr.getClass();
        synchronized (this.h) {
            this.h.put(uuid2.toString() + "-WRITE", new pv4(ki0.WRITE, uuid, uuid2, bArr, this.q.incrementAndGet(), str));
            k(str);
        }
        this.f.post(new mv4(this, 2));
    }

    /* JADX WARN: Code restructure failed: missing block: B:32:0x00c4, code lost:
    
        if (r13.invoke(r0) != r1) goto L34;
     */
    /* JADX WARN: Removed duplicated region for block: B:27:0x009f  */
    /* JADX WARN: Removed duplicated region for block: B:7:0x0013  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct code enable 'Show inconsistent code' option in preferences
    */
    public final java.lang.Object e(byte[] r12, defpackage.gq r13, defpackage.we1 r14) throws javax.crypto.BadPaddingException, javax.crypto.NoSuchPaddingException, javax.crypto.IllegalBlockSizeException, java.security.NoSuchAlgorithmException, java.security.InvalidKeyException, java.security.InvalidAlgorithmParameterException {
        /*
            Method dump skipped, instruction units count: 310
            To view this dump change 'Code comments level' option to 'DEBUG'
        */
        throw new UnsupportedOperationException("Method not decompiled: defpackage.tv4.e(byte[], gq, we1):java.lang.Object");
    }

    public final void f(byte[] bArr) {
        Map linkedHashMap;
        byte[] bArrA = this.g.a(bArr);
        Map map = s86.a;
        Byte bValueOf = 16 < bArrA.length ? Byte.valueOf(bArrA[16]) : null;
        boolean z = bValueOf != null && bValueOf.byteValue() == -1;
        Byte bValueOf2 = 17 < bArrA.length ? Byte.valueOf(bArrA[17]) : null;
        byte bByteValue = bValueOf2 != null ? bValueOf2.byteValue() : (byte) 0;
        LinkedHashMap linkedHashMap2 = new LinkedHashMap();
        for (Map.Entry entry : s86.a.entrySet()) {
            linkedHashMap2.put((h73) entry.getKey(), Boolean.valueOf(((bByteValue >> ((Number) entry.getValue()).intValue()) & 1) == 1));
        }
        r86 r86Var = new r86(linkedHashMap2, z);
        r86 r86Var2 = this.o;
        Date date = this.p;
        if (r86Var2 == null || date == null) {
            linkedHashMap = sa2.q;
        } else {
            Map map2 = s86.a;
            LinkedHashMap linkedHashMap3 = r86Var2.b;
            long time = new Date().getTime() - date.getTime();
            LinkedHashMap linkedHashMap4 = new LinkedHashMap();
            for (Map.Entry entry2 : linkedHashMap3.entrySet()) {
                h73 h73Var = (h73) entry2.getKey();
                if (((Boolean) entry2.getValue()).booleanValue()) {
                    Boolean bool = (Boolean) linkedHashMap2.get(h73Var);
                    if (!(bool != null ? bool.booleanValue() : false)) {
                        linkedHashMap4.put(entry2.getKey(), entry2.getValue());
                    }
                }
            }
            linkedHashMap = new LinkedHashMap(ds4.e(linkedHashMap4.size()));
            Iterator it = linkedHashMap4.entrySet().iterator();
            while (it.hasNext()) {
                linkedHashMap.put(((Map.Entry) it.next()).getKey(), Long.valueOf(time));
            }
        }
        this.o = r86Var;
        this.p = new Date();
        gj4 gj4Var = gj4.a;
        gj4.g("BIKECONNECT", "RCM State: " + r86Var);
        if (r86Var.a) {
            Iterator it2 = linkedHashMap.entrySet().iterator();
            while (it2.hasNext()) {
                h73 h73Var2 = (h73) ((Map.Entry) it2.next()).getKey();
                gj4 gj4Var2 = gj4.a;
                gj4.g("BIKECONNECT", "onHandlebarButtonUp: key = " + h73Var2.name());
            }
        }
    }

    public final void g(int i) throws IOException {
        String strM;
        pv4 pv4Var;
        byte[] bArr;
        long jCurrentTimeMillis = System.currentTimeMillis();
        pv4 pv4Var2 = this.t;
        if (pv4Var2 != null) {
            String str = pv4Var2.g;
            strM = tg5.m(pv4Var2.f, "#", str != null ? vf1.j(" (", str, ")") : "");
        } else {
            strM = "unknown";
        }
        String strB = (pv4Var2 == null || (bArr = pv4Var2.d) == null) ? "(no data)" : zz.B(bArr, "", new ov4(2), 30);
        long j = this.l;
        if (j == 0 || this.k > j) {
            this.l = jCurrentTimeMillis;
        }
        long j2 = this.l;
        long j3 = jCurrentTimeMillis - j2;
        long j4 = this.m;
        if (j3 >= j4) {
            pv4Var = pv4Var2;
            if (this.k < j2) {
                gj4 gj4Var = gj4.a;
                String str2 = this.a.d;
                StringBuilder sb = new StringBuilder("No successful writes in the last ");
                sb.append(j4 / 1000);
                sb.append(" seconds for ");
                sb.append(str2);
                sb.append(". Last op: ");
                sb.append(strM);
                sb.append(". Status: ");
                sb.append(i);
                gj4.d(gj4Var, "BIKECONNECT", u62.r(sb, ". Data: ", strB, ". Disconnecting..."), null, 12);
                Object obj = ji0.a;
                String str3 = this.a.d;
                gi0 gi0Var = gi0.QUEUE;
                ii0 ii0Var = ii0.ERROR;
                if (pv4Var != null) {
                    byte[] bArr2 = pv4Var.d;
                }
                this.h.size();
                ji0.a(str3, gi0Var, ii0Var, "Write failure window exceeded", 6656);
                h("WRITE_FAILURE_WINDOW");
                this.l = 0L;
                this.t = null;
                m();
                return;
            }
        } else {
            pv4Var = pv4Var2;
        }
        if (i != 201 && i != 133) {
            gj4.d(gj4.a, "BIKECONNECT", "Non-retryable error writing " + strM + " to " + (pv4Var != null ? pv4Var.c : null) + ": " + i + ". Data: " + strB + ". Disconnecting...", null, 12);
            Object obj2 = ji0.a;
            String str4 = this.a.d;
            gi0 gi0Var2 = gi0.ERROR;
            ii0 ii0Var2 = ii0.ERROR;
            if (pv4Var != null) {
                byte[] bArr3 = pv4Var.d;
            }
            this.h.size();
            ji0.a(str4, gi0Var2, ii0Var2, "Non-retryable write error", 6656);
            h("NON_RETRYABLE_WRITE_FAILURE");
            this.t = null;
            m();
            return;
        }
        pv4 pv4Var3 = this.t;
        if (pv4Var3 == null) {
            return;
        }
        if (pv4Var3.e >= 3) {
            gj4.d(gj4.a, "BIKECONNECT", "Failed to write " + strM + " to " + pv4Var3.c + " after 3 retries due to " + i + ". Data: " + strB + ". Skipping...", null, 12);
            Object obj3 = ji0.a;
            String str5 = this.a.d;
            gi0 gi0Var3 = gi0.QUEUE;
            ii0 ii0Var3 = ii0.ERROR;
            this.h.size();
            ji0.a(str5, gi0Var3, ii0Var3, "Write retries exhausted", 4096);
            h("WRITE_RETRIES_EXHAUSTED");
        } else {
            synchronized (this.h) {
                try {
                    if (this.h.get(pv4Var3.c.toString()) == null) {
                        this.h.put(pv4Var3.c.toString() + "-WRITE", pv4Var3);
                        int i2 = pv4Var3.e + 1;
                        pv4Var3.e = i2;
                        gj4 gj4Var2 = gj4.a;
                        gj4.k("BIKECONNECT", "Failed to write " + strM + " to " + pv4Var3.c + " due to " + i + ". Data: " + strB + ". Retrying (" + i2 + "/3)...");
                        Object obj4 = ji0.a;
                        String str6 = this.a.d;
                        gi0 gi0Var4 = gi0.QUEUE;
                        ii0 ii0Var4 = ii0.WARN;
                        this.h.size();
                        ji0.a(str6, gi0Var4, ii0Var4, "Write retry scheduled", 4096);
                    } else {
                        gj4 gj4Var3 = gj4.a;
                        gj4.k("BIKECONNECT", "Failed to write " + strM + " to " + pv4Var3.c + " due to " + i + ". Data: " + strB + ". Next operation already queued, skipping retry...");
                        Object obj5 = ji0.a;
                        String str7 = this.a.d;
                        gi0 gi0Var5 = gi0.QUEUE;
                        ii0 ii0Var5 = ii0.WARN;
                        strM.concat(" because next operation already queued");
                        this.h.size();
                        ji0.a(str7, gi0Var5, ii0Var5, "Write retry skipped", 4096);
                    }
                } catch (Throwable th) {
                    throw th;
                }
            }
        }
        mv4 mv4Var = this.i;
        if (mv4Var != null) {
            this.f.removeCallbacks(mv4Var);
        }
        mv4 mv4Var2 = new mv4(this, 3);
        this.i = mv4Var2;
        this.f.postDelayed(mv4Var2, 250L);
    }

    public final void h(String str) {
        List listH0;
        synchronized (this.r) {
            listH0 = c21.h0(this.r);
        }
        if (listH0.isEmpty()) {
            gj4 gj4Var = gj4.a;
            gj4.k("BIKECONNECT", "[" + str + "] No recent messages to log for " + this.a.d);
            Object obj = ji0.a;
            ji0.a(this.a.d, gi0.ERROR, ii0.WARN, "Disconnect snapshot empty", 8128);
            return;
        }
        Object obj2 = ji0.a;
        String str2 = this.a.d;
        gi0 gi0Var = gi0.CONNECTION;
        ii0 ii0Var = ii0.WARN;
        listH0.size();
        this.h.size();
        ji0.a(str2, gi0Var, ii0Var, "Disconnect snapshot captured", 7872);
        long jCurrentTimeMillis = System.currentTimeMillis();
        gj4 gj4Var2 = gj4.a;
        gj4.k("BIKECONNECT", "[" + str + "] Last " + listH0.size() + " sent messages for " + this.a.d + ":");
        int i = 0;
        for (Object obj3 : listH0) {
            int i2 = i + 1;
            if (i < 0) {
                d21.r();
                throw null;
            }
            qv4 qv4Var = (qv4) obj3;
            long j = jCurrentTimeMillis - qv4Var.g;
            String str3 = qv4Var.b;
            String strJ = str3 != null ? vf1.j(" (", str3, ")") : "";
            gj4 gj4Var3 = gj4.a;
            int i3 = qv4Var.a;
            ki0 ki0Var = qv4Var.d;
            UUID uuid = qv4Var.c;
            int i4 = qv4Var.f;
            String str4 = qv4Var.e;
            StringBuilder sbV = u62.v("  [", i2, "] #", i3, strJ);
            sbV.append(" | ");
            sbV.append(ki0Var);
            sbV.append(" | char=");
            sbV.append(uuid);
            sbV.append(" | ");
            sbV.append(i4);
            sbV.append("B | ");
            sbV.append(j);
            sbV.append("ms ago | data=");
            sbV.append(str4);
            gj4.k("BIKECONNECT", sbV.toString());
            i = i2;
        }
    }

    public final boolean i(pv4 pv4Var, boolean z) {
        BluetoothGattCharacteristic characteristic;
        BluetoothGattDescriptor descriptor;
        UUID uuid = pv4Var.b;
        BluetoothGatt bluetoothGatt = this.b;
        BluetoothGattService service = bluetoothGatt.getService(uuid);
        if (service == null || (characteristic = service.getCharacteristic(pv4Var.c)) == null || (descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))) == null) {
            return false;
        }
        descriptor.setValue(z ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        bluetoothGatt.setCharacteristicNotification(characteristic, true);
        return bluetoothGatt.writeDescriptor(descriptor);
    }

    public final void j() {
        pv4 pv4Var;
        BluetoothGattCharacteristic characteristic;
        boolean zWriteCharacteristic;
        gi0 gi0Var;
        if (!uq3.g(Looper.myLooper(), Looper.getMainLooper())) {
            this.f.post(new mv4(this, 4));
            return;
        }
        if (this.n) {
            return;
        }
        try {
            synchronized (this.h) {
                if (this.t != null) {
                    pv4Var = null;
                } else {
                    Set setKeySet = this.h.keySet();
                    setKeySet.getClass();
                    String str = (String) c21.F(setKeySet);
                    if (str == null) {
                        pv4Var = null;
                    } else {
                        this.t = (pv4) this.h.remove(str);
                        pv4Var = this.t;
                    }
                }
            }
            if (pv4Var == null) {
                return;
            }
            int i = rv4.a[pv4Var.a.ordinal()];
            if (i == 1) {
                BluetoothGatt bluetoothGatt = this.b;
                BluetoothGattService service = bluetoothGatt.getService(pv4Var.b);
                if (service == null || (characteristic = service.getCharacteristic(pv4Var.c)) == null) {
                    zWriteCharacteristic = false;
                } else {
                    characteristic.setValue(pv4Var.d);
                    characteristic.setWriteType(2);
                    zWriteCharacteristic = bluetoothGatt.writeCharacteristic(characteristic);
                }
            } else if (i == 2) {
                zWriteCharacteristic = i(pv4Var, true);
            } else {
                if (i != 3) {
                    throw new yr0(12);
                }
                zWriteCharacteristic = i(pv4Var, false);
            }
            if (!zWriteCharacteristic) {
                g(201);
                return;
            }
            l(pv4Var);
            this.k = System.currentTimeMillis();
            this.l = 0L;
            Object obj = ji0.a;
            String str2 = this.a.d;
            String str3 = pv4Var.g;
            if (str3 == null || !ve7.t(str3, "Nav ", false)) {
                String str4 = pv4Var.g;
                if (str4 == null || !ve7.t(str4, "PRPC", false)) {
                    String str5 = pv4Var.g;
                    gi0Var = (str5 == null || !ve7.t(str5, "Auth", false)) ? gi0.QUEUE : gi0.AUTH;
                } else {
                    gi0Var = gi0.TELEMETRY;
                }
            } else {
                gi0Var = gi0.NAV;
            }
            if (pv4Var.g == null) {
                pv4Var.a.name();
            }
            this.h.size();
            ji0.a(str2, gi0Var, null, "Operation submitted", 3592);
        } catch (Exception e) {
            gj4.d(gj4.a, "BIKECONNECT", "Error processing next operation", e, 8);
        }
    }

    public final void k(String str) {
        int size = this.h.size();
        int i = this.j;
        mc0 mc0Var = this.a;
        if (size > i) {
            this.j = size;
            if (size >= 3) {
                Object obj = ji0.a;
                ji0.a(mc0Var.d, gi0.QUEUE, size >= 5 ? ii0.WARN : ii0.INFO, "Queue depth spike", 7680);
            }
        }
        gi0 gi0Var = (str == null || !ve7.t(str, "Nav ", false)) ? (str == null || !ve7.t(str, "PRPC", false)) ? (str == null || !ve7.t(str, "Auth", false)) ? gi0.QUEUE : gi0.AUTH : gi0.TELEMETRY : gi0.NAV;
        Object obj2 = ji0.a;
        ji0.a(mc0Var.d, gi0Var, null, "Operation queued", 3592);
    }

    public final void l(pv4 pv4Var) {
        synchronized (this.r) {
            try {
                nz nzVar = this.r;
                if (nzVar.s >= 5) {
                    nzVar.removeFirst();
                }
                nz nzVar2 = this.r;
                int i = pv4Var.f;
                String str = pv4Var.g;
                UUID uuid = pv4Var.c;
                ki0 ki0Var = pv4Var.a;
                byte[] bArr = pv4Var.d;
                String strB = bArr != null ? zz.B(bArr, "", new ru3(29), 30) : "(no data)";
                byte[] bArr2 = pv4Var.d;
                nzVar2.addLast(new qv4(i, str, uuid, ki0Var, strB, bArr2 != null ? bArr2.length : 0, System.currentTimeMillis()));
            } catch (Throwable th) {
                throw th;
            }
        }
    }

    public final void m() throws IOException {
        if (this.n) {
            return;
        }
        this.n = true;
        Object obj = ji0.a;
        String str = this.a.d;
        gi0 gi0Var = gi0.CONNECTION;
        ii0 ii0Var = ii0.ERROR;
        this.h.size();
        ji0.a(str, gi0Var, ii0Var, "Disconnect requested", 7872);
        this.e.invoke();
    }
}
