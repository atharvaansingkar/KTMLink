package defpackage;

/* JADX WARN: Failed to restore enum class, 'enum' modifier and super class removed */
/* JADX WARN: Unknown enum class pattern. Please report as an issue! */
/* JADX INFO: compiled from: r8-map-id-50dea58a577de17b3fb15fa361f956fd9b2fc093cbacb538b411be86303f9f04 */
/* JADX INFO: loaded from: classes.dex */
public final class ax0 {
    private static final /* synthetic */ cd2 $ENTRIES;
    private static final /* synthetic */ ax0[] $VALUES;
    public static final ax0 AUTHENTICATION_REPLY;
    public static final ax0 AUTHENTICATION_REQUEST;
    public static final ax0 BASE_GET_VIN_REQUEST_CHARACTERISTIC;
    public static final ax0 BASE_SERVICE;
    public static final ax0 BASE_VIN_CHARACTERISTIC;
    public static final ax0 ETA;
    public static final ax0 MAIN_SERVICE;
    public static final ax0 NAVIGATION_STATE;
    public static final ax0 NOTIFICATION;
    public static final ax0 PRPC_NOTIFICATION_CHARACTERISTIC;
    public static final ax0 PRPC_REQUEST_CHARACTERISTIC;
    public static final ax0 PRPC_RESPONSE_CHARACTERISTIC;
    public static final ax0 PRPC_SERVICE;
    public static final ax0 RCM_REMOTE_CONTROL_CHARACTERISTIC;
    public static final ax0 RCM_SERVICE;
    public static final ax0 REMAINING_DISTANCE;
    public static final ax0 TBT_FAVORITE_CHARACTERISTIC;
    public static final ax0 TBT_LAST_DESTINATION_CHARACTERISTIC;
    public static final ax0 TBT_NAVIGATION_REQUEST_CHARACTERISTIC;
    public static final ax0 TBT_NAVIGATION_RESPONSE_CHARACTERISTIC;
    public static final ax0 TURN_DISTANCE;
    public static final ax0 TURN_EXTRA_INFO;
    public static final ax0 TURN_ICON;
    public static final ax0 TURN_INFO;
    private final String uuidString;

    static {
        ax0 ax0Var = new ax0("BASE_SERVICE", 0, "71ced1ac-0000-44f5-9454-806ff70b3e02");
        BASE_SERVICE = ax0Var;
        ax0 ax0Var2 = new ax0("BASE_GET_VIN_REQUEST_CHARACTERISTIC", 1, "71ced1ac-0001-44f5-9454-806ff70b3e02");
        BASE_GET_VIN_REQUEST_CHARACTERISTIC = ax0Var2;
        ax0 ax0Var3 = new ax0("BASE_VIN_CHARACTERISTIC", 2, "71ced1ac-0002-44f5-9454-806ff70b3e02");
        BASE_VIN_CHARACTERISTIC = ax0Var3;
        ax0 ax0Var4 = new ax0("RCM_REMOTE_CONTROL_CHARACTERISTIC", 3, "71ced1ac-0103-44f5-9454-806ff70b3e02");
        RCM_REMOTE_CONTROL_CHARACTERISTIC = ax0Var4;
        ax0 ax0Var5 = new ax0("RCM_SERVICE", 4, "71ced1ac-0100-44f5-9454-806ff70b3e02");
        RCM_SERVICE = ax0Var5;
        ax0 ax0Var6 = new ax0("MAIN_SERVICE", 5, "71ced1ac-0700-44f5-9454-806ff70b3e02");
        MAIN_SERVICE = ax0Var6;
        ax0 ax0Var7 = new ax0("AUTHENTICATION_REQUEST", 6, "71ced1ac-0701-44f5-9454-806ff70b3e02");
        AUTHENTICATION_REQUEST = ax0Var7;
        ax0 ax0Var8 = new ax0("AUTHENTICATION_REPLY", 7, "71ced1ac-0702-44f5-9454-806ff70b3e02");
        AUTHENTICATION_REPLY = ax0Var8;
        ax0 ax0Var9 = new ax0("NAVIGATION_STATE", 8, "71ced1ac-0703-44f5-9454-806ff70b3e02");
        NAVIGATION_STATE = ax0Var9;
        ax0 ax0Var10 = new ax0("TURN_ICON", 9, "71ced1ac-0704-44f5-9454-806ff70b3e02");
        TURN_ICON = ax0Var10;
        ax0 ax0Var11 = new ax0("TURN_DISTANCE", 10, "71ced1ac-0705-44f5-9454-806ff70b3e02");
        TURN_DISTANCE = ax0Var11;
        ax0 ax0Var12 = new ax0("TURN_EXTRA_INFO", 11, "71ced1ac-0706-44f5-9454-806ff70b3e02");
        TURN_EXTRA_INFO = ax0Var12;
        ax0 ax0Var13 = new ax0("TURN_INFO", 12, "71ced1ac-0707-44f5-9454-806ff70b3e02");
        TURN_INFO = ax0Var13;
        ax0 ax0Var14 = new ax0("ETA", 13, "71ced1ac-0708-44f5-9454-806ff70b3e02");
        ETA = ax0Var14;
        ax0 ax0Var15 = new ax0("REMAINING_DISTANCE", 14, "71ced1ac-0709-44f5-9454-806ff70b3e02");
        REMAINING_DISTANCE = ax0Var15;
        ax0 ax0Var16 = new ax0("NOTIFICATION", 15, "71ced1ac-070a-44f5-9454-806ff70b3e02");
        NOTIFICATION = ax0Var16;
        ax0 ax0Var17 = new ax0("TBT_NAVIGATION_REQUEST_CHARACTERISTIC", 16, "71ced1ac-070b-44f5-9454-806ff70b3e02");
        TBT_NAVIGATION_REQUEST_CHARACTERISTIC = ax0Var17;
        ax0 ax0Var18 = new ax0("TBT_NAVIGATION_RESPONSE_CHARACTERISTIC", 17, "71ced1ac-070c-44f5-9454-806ff70b3e02");
        TBT_NAVIGATION_RESPONSE_CHARACTERISTIC = ax0Var18;
        ax0 ax0Var19 = new ax0("TBT_LAST_DESTINATION_CHARACTERISTIC", 18, "71ced1ac-070d-44f5-9454-806ff70b3e02");
        TBT_LAST_DESTINATION_CHARACTERISTIC = ax0Var19;
        ax0 ax0Var20 = new ax0("TBT_FAVORITE_CHARACTERISTIC", 19, "71ced1ac-070e-44f5-9454-806ff70b3e02");
        TBT_FAVORITE_CHARACTERISTIC = ax0Var20;
        ax0 ax0Var21 = new ax0("PRPC_SERVICE", 20, "71ced1ac-0600-44f5-9454-806ff70b3e02");
        PRPC_SERVICE = ax0Var21;
        ax0 ax0Var22 = new ax0("PRPC_REQUEST_CHARACTERISTIC", 21, "71ced1ac-0601-44f5-9454-806ff70b3e02");
        PRPC_REQUEST_CHARACTERISTIC = ax0Var22;
        ax0 ax0Var23 = new ax0("PRPC_RESPONSE_CHARACTERISTIC", 22, "71ced1ac-0602-44f5-9454-806ff70b3e02");
        PRPC_RESPONSE_CHARACTERISTIC = ax0Var23;
        ax0 ax0Var24 = new ax0("PRPC_NOTIFICATION_CHARACTERISTIC", 23, "71ced1ac-0603-44f5-9454-806ff70b3e02");
        PRPC_NOTIFICATION_CHARACTERISTIC = ax0Var24;
        ax0[] ax0VarArr = {ax0Var, ax0Var2, ax0Var3, ax0Var4, ax0Var5, ax0Var6, ax0Var7, ax0Var8, ax0Var9, ax0Var10, ax0Var11, ax0Var12, ax0Var13, ax0Var14, ax0Var15, ax0Var16, ax0Var17, ax0Var18, ax0Var19, ax0Var20, ax0Var21, ax0Var22, ax0Var23, ax0Var24};
        $VALUES = ax0VarArr;
        $ENTRIES = new dd2(ax0VarArr);
    }

    public ax0(String str, int i, String str2) {
        this.uuidString = str2;
    }

    public static ax0 valueOf(String str) {
        return (ax0) Enum.valueOf(ax0.class, str);
    }

    public static ax0[] values() {
        return (ax0[]) $VALUES.clone();
    }

    public final String a() {
        return this.uuidString;
    }
}
