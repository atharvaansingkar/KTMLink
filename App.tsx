import React, {useEffect, useRef, useState} from 'react';
import {
  AppState,
  Animated,
  Linking,
  NativeEventEmitter,
  NativeModules,
  PermissionsAndroid,
  Platform,
  SafeAreaView,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';

import {parseNavNotification, isUsefulNavData} from './src/services/NavParser';
import AsyncStorage from '@react-native-async-storage/async-storage';

const requestBluetoothPermissions = async (): Promise<boolean> => {
  if (Platform.OS === 'android') {
    if (Platform.Version >= 31) {
      const perms: any[] = [
        PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN,
        PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT,
        PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
      ];
      if (Platform.Version >= 33) {
        perms.push('android.permission.POST_NOTIFICATIONS');
      }
      const result = await PermissionsAndroid.requestMultiple(perms);
      return (
        result['android.permission.BLUETOOTH_CONNECT'] === PermissionsAndroid.RESULTS.GRANTED &&
        result['android.permission.BLUETOOTH_SCAN'] === PermissionsAndroid.RESULTS.GRANTED &&
        result['android.permission.ACCESS_FINE_LOCATION'] === PermissionsAndroid.RESULTS.GRANTED
      );
    } else {
      const result = await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
      );
      return result === PermissionsAndroid.RESULTS.GRANTED;
    }
  }
  return true;
};

// ─── Native Bridge ────────────────────────────────────────────────────────────
const {MapScraper, BondedKtmModule, KTMLinkService} = NativeModules;
const mapScraperEmitter = new NativeEventEmitter(MapScraper);
const serviceEmitter    = new NativeEventEmitter(KTMLinkService);

// ─── Types ────────────────────────────────────────────────────────────────────
type ConnectionStatus =
  | 'no_device'
  | 'paired_offline'
  | 'connecting'
  | 'authenticating'
  | 'connected';

// ─── Sub-components ───────────────────────────────────────────────────────────
const PulsingDot = ({active, color = '#FF6600'}: {active: boolean; color?: string}) => {
  const scale = useRef(new Animated.Value(1)).current;
  useEffect(() => {
    if (!active) {scale.setValue(1); return;}
    const anim = Animated.loop(
      Animated.sequence([
        Animated.timing(scale, {toValue: 1.4, duration: 700, useNativeDriver: true}),
        Animated.timing(scale, {toValue: 1,   duration: 700, useNativeDriver: true}),
      ]),
    );
    anim.start();
    return () => anim.stop();
  }, [active, scale]);
  return (
    <Animated.View
      style={[styles.dot, {backgroundColor: active ? color : '#333', transform: [{scale}]}]}
    />
  );
};

// ─── Main App ─────────────────────────────────────────────────────────────────
const App = () => {
  // Maps nav state
  const [navTitle, setNavTitle]                 = useState('');
  const [navText, setNavText]                   = useState('Waiting for Google Maps...');
  const [navManeuver, setNavManeuver]           = useState('');
  const [navEta, setNavEta]                     = useState('');
  const [navRemaining, setNavRemaining]         = useState('');
  const [navDistance, setNavDistance]           = useState('');
  const [navIconName, setNavIconName]           = useState('');
  const [navTimeRemaining, setNavTimeRemaining] = useState('');
  const [lastUpdated, setLastUpdated]           = useState<string | null>(null);
  const [isListening, setIsListening]           = useState(false);
  const [hasPermission, setHasPermission]       = useState(false);

  // Slot6 alternation (UI display only)
  const [slot6ShowingDist, setSlot6ShowingDist] = useState(true);
  const slot6Timer = useRef<ReturnType<typeof setInterval> | null>(null);
  const slot6Dist  = useRef('');
  const slot6Time  = useRef('');

  // Connection state
  const [ktmDevice, setKtmDevice]                 = useState<{id: string; name: string} | null>(null);
  const [connectionStatus, setConnectionStatus]   = useState<ConnectionStatus>('no_device');
  const [deviceFoundName, setDeviceFoundName]     = useState('');
  const [osPairingRequired, setOsPairingRequired] = useState(false);
  const [isAppRegistered, setIsAppRegistered]     = useState(false);


  // Nav card flash
  const cardFlash = useRef(new Animated.Value(0)).current;
  const flashCard = () => {
    Animated.sequence([
      Animated.timing(cardFlash, {toValue: 1, duration: 150, useNativeDriver: false}),
      Animated.timing(cardFlash, {toValue: 0, duration: 500, useNativeDriver: false}),
    ]).start();
  };
  const cardBorder = cardFlash.interpolate({inputRange: [0,1], outputRange: ['#2a2a2a','#FF6600']});

  // ─── Re-check state when app comes to foreground ─────────────────────────────
  const refreshState = () => {
    if (MapScraper?.hasPermission) {
      MapScraper.hasPermission().then((granted: boolean) => {
        setHasPermission(granted);
        if (granted) setIsListening(true);
      });
    }
    BondedKtmModule?.getPairedDevices?.()
      .then((devices: {name: string; id: string}[]) => {
        const ktm = devices.find((d: {name: string}) => d.name.includes('KTM'));
        if (ktm) {
          setKtmDevice(ktm);
          setOsPairingRequired(false);
          setConnectionStatus(prev => prev === 'no_device' ? 'paired_offline' : prev);
        } else {
          setKtmDevice(null);
        }
      })
      .catch(() => {});
    AsyncStorage.getItem('APP_IS_REGISTERED').then(val => {
      if (val === 'true') setIsAppRegistered(true);
    });
  };

  // ─── Boot ────────────────────────────────────────────────────────────────────
  useEffect(() => {
    refreshState();

    requestBluetoothPermissions().then(granted => {
      if (!granted) return;
      KTMLinkService?.startService?.();
    });

    const appStateSub = AppState.addEventListener('change', state => {
      if (state === 'active') refreshState();
    });

    // ─── Service events ───────────────────────────────────────────────────────
    const svcSub = serviceEmitter.addListener(
      'onKtmEvent',
      (event: {type: string; name?: string; msg?: string; sender?: string; body?: string; notifType?: string}) => {
        switch (event.type) {
          case 'CONNECTING':
            setConnectionStatus('connecting');
            if (event.name) setDeviceFoundName(event.name);
            break;
          case 'DEVICE_FOUND':
            setConnectionStatus('authenticating');
            if (event.name) setDeviceFoundName(event.name);
            break;
          case 'NONCES_SWAPPED':
          case 'HELLO_EXCHANGED':
          case 'KEYS_GENERATED':
            // handshake steps — no UI action needed
            break;
          case 'AUTHENTICATED':
            setConnectionStatus('connected');
            setIsAppRegistered(true);
            AsyncStorage.setItem('APP_IS_REGISTERED', 'true');
            break;
          case 'DISCONNECTED':
            setConnectionStatus('paired_offline');
            clearNotifFeed();
            break;
          case 'PLEASE_PAIR_OS':
            setOsPairingRequired(true);
            setConnectionStatus('paired_offline');
            break;
          case 'NOTIF':
            if (event.sender) {
              addNotif(event.sender, event.body ?? '', (event.notifType ?? 'message') as 'call' | 'message');
            }
            break;
        }
      },
    );

    // ─── Maps nav events ──────────────────────────────────────────────────────
    const subUpdate = mapScraperEmitter.addListener(
      'onMapUpdate',
      (event: {title: string; text: string; subText: string; bigText: string}) => {
        const {title = '', text = '', subText = '', bigText = ''} = event;
        if (!isUsefulNavData(title, text)) return;
        const parsed = parseNavNotification(title, text, subText, bigText);

        setNavTitle(parsed.road);
        setNavText(text || 'Continue on route');
        setNavManeuver(parsed.maneuver);
        setNavEta(parsed.eta);
        setNavRemaining(parsed.remainingDistance);
        setNavDistance(parsed.distance);
        setNavIconName(parsed.turnIconName);
        setNavTimeRemaining(parsed.timeRemaining);
        setLastUpdated(new Date().toLocaleTimeString());
        flashCard();

        const newDist = parsed.remainingDistance;
        const newTime = parsed.timeRemaining;
        const hadBoth = !!(slot6Dist.current && slot6Time.current);
        const hasBoth = !!(newDist && newTime);
        slot6Dist.current = newDist;
        slot6Time.current = newTime;
        if (hasBoth !== hadBoth) {
          if (slot6Timer.current) {clearInterval(slot6Timer.current); slot6Timer.current = null;}
          setSlot6ShowingDist(true);
          if (hasBoth) {
            slot6Timer.current = setInterval(() => setSlot6ShowingDist(v => !v), 2000);
          }
        }
      },
    );

    const subRemoved = mapScraperEmitter.addListener('onMapRemoved', () => {
      if (slot6Timer.current) {clearInterval(slot6Timer.current); slot6Timer.current = null;}
      slot6Dist.current = '';
      slot6Time.current = '';
      setSlot6ShowingDist(true);
      setNavTitle('');
      setNavText('Navigation Ended');
      setNavDistance('');
      setNavManeuver('');
      setNavEta('');
      setNavRemaining('');
      setNavIconName('');
      setNavTimeRemaining('');
    });

    return () => {
      appStateSub.remove();
      svcSub.remove();
      subUpdate.remove();
      subRemoved.remove();
    };
  }, []);

  // ─── Status helpers ───────────────────────────────────────────────────────────
  const statusLabel = () => {
    switch (connectionStatus) {
      case 'connected':      return '● Connected';
      case 'authenticating': return '● Authenticating...';
      case 'connecting':     return '● Connecting...';
      case 'paired_offline': return 'Paired (offline)';
      default:               return 'Not paired';
    }
  };
  const statusColor = () => {
    switch (connectionStatus) {
      case 'connected':      return '#00FF00';
      case 'authenticating':
      case 'connecting':     return '#FF6600';
      default:               return '#555';
    }
  };

  // ─── Render ──────────────────────────────────────────────────────────────────
  return (
    <SafeAreaView style={styles.safeArea}>
      <StatusBar barStyle="light-content" backgroundColor="#0a0a0a" />
      <ScrollView
        style={styles.scroll}
        contentContainerStyle={styles.scrollContent}
        showsVerticalScrollIndicator={false}>

        {/* Header */}
        <View style={styles.header}>
          <View style={styles.logoRow}>
            <View style={styles.logoAccent} />
            <Text style={styles.logoText}>KTM LINK</Text>
          </View>
          <Text style={styles.greeting}>Hi Atharva, Ready to Race?</Text>
          <Text style={styles.subGreeting}>Dashboard bridge is active</Text>
        </View>

        {/* Maps listening status */}
        <View style={styles.statusRow}>
          <PulsingDot active={isListening} />
          <Text style={styles.statusText}>
            {isListening ? 'Listening for Maps data' : 'Notification access required'}
          </Text>
        </View>

        {!hasPermission && (
          <TouchableOpacity
            style={styles.permButton}
            onPress={() => MapScraper?.requestPermission?.()}
            activeOpacity={0.8}>
            <Text style={styles.permButtonText}>GRANT NOTIFICATION ACCESS</Text>
          </TouchableOpacity>
        )}

        {/* Navigation Feed */}
        <Text style={styles.sectionLabel}>NAVIGATION FEED</Text>
        <Animated.View style={[styles.card, {borderColor: cardBorder}]}>
          <View style={styles.cardHeader}>
            <Text style={styles.cardIcon}>🗺️</Text>
            <Text style={styles.cardTitle}>{navTitle || 'Google Maps'}</Text>
          </View>
          <Text style={styles.navText}>{navText}</Text>
          <View style={styles.grid}>
            <View style={styles.cell}>
              <Text style={styles.cellLabel}>1. TURN_ICON</Text>
              <Text style={styles.cellValue}>{navIconName || '-'}</Text>
            </View>
            <View style={styles.cell}>
              <Text style={styles.cellLabel}>2. TURN_ROAD</Text>
              <Text style={styles.cellValue}>{navTitle || '-'}</Text>
            </View>
          </View>
          <View style={[styles.grid, {marginTop: 10}]}>
            <View style={styles.cell}>
              <Text style={styles.cellLabel}>3. TURN_INFO</Text>
              <Text style={styles.cellValue}>{navManeuver || '-'}</Text>
            </View>
            <View style={styles.cell}>
              <Text style={styles.cellLabel}>4. TURN_DISTANCE</Text>
              <Text style={styles.cellValue}>{navDistance || '-'}</Text>
            </View>
          </View>
          <View style={[styles.grid, {marginTop: 10}]}>
            <View style={styles.cell}>
              <Text style={styles.cellLabel}>5. ETA</Text>
              <Text style={styles.cellValue}>{navEta || '-'}</Text>
            </View>
            <View style={styles.cell}>
              <Text style={styles.cellLabel}>
                {navRemaining && navTimeRemaining
                  ? `6. ${slot6ShowingDist ? 'REMAINING KM' : 'TIME LEFT'}`
                  : '6. REMAINING'}
              </Text>
              <Text style={styles.cellValue}>
                {navRemaining && navTimeRemaining
                  ? (slot6ShowingDist ? navRemaining : navTimeRemaining)
                  : navRemaining || navTimeRemaining || '-'}
              </Text>
            </View>
          </View>
          {lastUpdated && <Text style={styles.timestamp}>Last update: {lastUpdated}</Text>}
        </Animated.View>

        {/* Bike Connection */}
        <Text style={[styles.sectionLabel, {marginTop: 24}]}>BIKE CONNECTION</Text>
        <View style={styles.card}>
          {connectionStatus === 'no_device' ? (
            <View style={styles.noDeviceBox}>
              <Text style={styles.noDeviceTitle}>No KTM paired yet</Text>
              <Text style={styles.noDeviceBody}>
                Pair your KTM via Android Bluetooth Settings first, then reopen this app.
              </Text>
              <TouchableOpacity
                style={styles.btSettingsBtn}
                onPress={() => Linking.sendIntent('android.settings.BLUETOOTH_SETTINGS').catch(() => {})}
                activeOpacity={0.8}>
                <Text style={styles.btSettingsBtnText}>OPEN BLUETOOTH SETTINGS</Text>
              </TouchableOpacity>
            </View>
          ) : (
            <View>
              <View style={styles.deviceRow}>
                <Text style={styles.btIcon}>🏍️</Text>
                <View style={styles.deviceInfo}>
                  <Text style={styles.deviceName}>
                    {deviceFoundName || ktmDevice?.name || 'KTM'}
                  </Text>
                  {ktmDevice?.id ? (
                    <Text style={styles.deviceMac}>{ktmDevice.id}</Text>
                  ) : null}
                  <Text style={[styles.deviceStatus, {color: statusColor()}]}>
                    {statusLabel()}
                  </Text>
                </View>
              </View>

              {connectionStatus === 'paired_offline' && !osPairingRequired && (
                <TouchableOpacity
                  style={[styles.btSettingsBtn, {marginTop: 16, backgroundColor: '#0066AA20', borderColor: '#0066AA'}]}
                  onPress={() => {
                    setConnectionStatus('connecting');
                    KTMLinkService?.connectDirectly?.();
                  }}
                  activeOpacity={0.8}>
                  <Text style={[styles.btSettingsBtnText, {color: '#00AAFF', textAlign: 'center'}]}>CONNECT TO BIKE</Text>
                </TouchableOpacity>
              )}
            </View>
          )}

          {osPairingRequired && (
            <View style={[styles.hintBox, {backgroundColor: '#331100', borderColor: '#FF3300'}]}>
              <Text style={styles.hintTitle}>⚠️ OS Pairing Required</Text>
              <Text style={styles.hintBody}>
                Pair your phone with your KTM in Android Bluetooth settings first.
              </Text>
            </View>
          )}

          {connectionStatus === 'authenticating' && !isAppRegistered && !osPairingRequired && (
            <View style={styles.hintBox}>
              <Text style={styles.hintTitle}>Action required on bike</Text>
              <Text style={styles.hintBody}>
                Go to{' '}
                <Text style={styles.hintPath}>Connectivity → Bluetooth → Phone pairing</Text>
                {'\n'}and accept the{' '}
                <Text style={{color: '#FF6600', fontWeight: '700'}}>"Register new KTM app"</Text>
                {' '}prompt.{'\n\n'}You have ~40 seconds.
              </Text>
            </View>
          )}

          {connectionStatus === 'authenticating' && isAppRegistered && !osPairingRequired && (
            <View style={[styles.hintBox, {borderColor: '#00AA00', paddingVertical: 12}]}>
              <Text style={[styles.hintTitle, {color: '#00AA00', marginBottom: 0}]}>Resuming session...</Text>
            </View>
          )}
        </View>

        <Text style={styles.footer}>KTMLink · Phase 3 · {Platform.OS.toUpperCase()}</Text>
      </ScrollView>
    </SafeAreaView>
  );
};

// ─── Styles ───────────────────────────────────────────────────────────────────
const styles = StyleSheet.create({
  safeArea:      {flex: 1, backgroundColor: '#0a0a0a'},
  scroll:        {flex: 1},
  scrollContent: {paddingHorizontal: 20, paddingBottom: 50},

  header:      {paddingTop: 32, paddingBottom: 24, borderBottomWidth: 1, borderBottomColor: '#1a1a1a', marginBottom: 20},
  logoRow:     {flexDirection: 'row', alignItems: 'center', marginBottom: 14},
  logoAccent:  {width: 5, height: 26, backgroundColor: '#FF6600', borderRadius: 3, marginRight: 10},
  logoText:    {fontSize: 13, fontWeight: '700', letterSpacing: 4, color: '#FF6600'},
  greeting:    {fontSize: 24, fontWeight: '800', color: '#FFF', marginBottom: 4},
  subGreeting: {fontSize: 12, color: '#555', letterSpacing: 0.4},

  statusRow:  {flexDirection: 'row', alignItems: 'center', backgroundColor: '#141414', paddingHorizontal: 14, paddingVertical: 10, borderRadius: 10, borderWidth: 1, borderColor: '#1e1e1e', marginBottom: 24},
  dot:        {width: 10, height: 10, borderRadius: 5, marginRight: 10},
  statusText: {color: '#777', fontSize: 12},

  sectionLabel: {fontSize: 10, fontWeight: '700', letterSpacing: 2.5, color: '#FF6600', marginBottom: 8},

  card:       {backgroundColor: '#141414', borderRadius: 14, padding: 16, borderWidth: 1, borderColor: '#2a2a2a', marginBottom: 12},
  cardHeader: {flexDirection: 'row', alignItems: 'center', marginBottom: 12},
  cardIcon:   {fontSize: 20, marginRight: 10},
  cardTitle:  {fontSize: 14, fontWeight: '700', color: '#FFF'},

  navText:   {fontSize: 18, fontWeight: '600', color: '#FFF', lineHeight: 26, marginBottom: 14},
  grid:      {flexDirection: 'row', justifyContent: 'space-between'},
  cell:      {flex: 1, marginRight: 8, backgroundColor: '#1a1a1a', padding: 8, borderRadius: 6, borderWidth: 1, borderColor: '#2e2e2e'},
  cellLabel: {fontSize: 9, fontWeight: '700', color: '#777', marginBottom: 2},
  cellValue: {fontSize: 12, fontWeight: '600', color: '#FF6600'},
  timestamp: {fontSize: 10, color: '#444', marginTop: 14},

  permButton:     {backgroundColor: '#FF6600', borderRadius: 12, paddingVertical: 14, alignItems: 'center', marginBottom: 12},
  permButtonText: {color: '#000', fontWeight: '800', fontSize: 12, letterSpacing: 1.5},

  noDeviceBox:   {alignItems: 'center', paddingVertical: 8},
  noDeviceTitle: {color: '#FFF', fontSize: 15, fontWeight: '700', marginBottom: 8},
  noDeviceBody:  {color: '#666', fontSize: 12, textAlign: 'center', lineHeight: 18, marginBottom: 16},
  btSettingsBtn:     {backgroundColor: '#FF660018', borderWidth: 1, borderColor: '#FF6600', borderRadius: 10, paddingHorizontal: 18, paddingVertical: 10},
  btSettingsBtnText: {color: '#FF6600', fontWeight: '700', fontSize: 11, letterSpacing: 1},

  deviceRow:   {flexDirection: 'row', alignItems: 'center', gap: 14},
  deviceInfo:  {flex: 1},
  btIcon:      {fontSize: 26},
  deviceName:  {color: '#FFF', fontSize: 14, fontWeight: '700'},
  deviceMac:   {color: '#444', fontSize: 10, fontFamily: Platform.OS === 'ios' ? 'Courier' : 'monospace', marginTop: 2},
  deviceStatus:{fontSize: 12, fontWeight: '600', marginTop: 5},

  hintBox:   {marginTop: 16, backgroundColor: '#1a1100', borderWidth: 1, borderColor: '#FF660040', borderRadius: 10, padding: 14},
  hintTitle: {color: '#FF6600', fontSize: 13, fontWeight: '700', marginBottom: 6},
  hintBody:  {color: '#AAA', fontSize: 12, lineHeight: 20},
  hintPath:  {color: '#FFF', fontWeight: '600'},

  notifEmpty: {color: '#333', fontSize: 12, fontStyle: 'italic', textAlign: 'center', paddingVertical: 8},
  notifRow:   {flexDirection: 'row', alignItems: 'flex-start', paddingVertical: 10, borderBottomWidth: 1, borderBottomColor: '#1e1e1e'},
  notifIcon:  {fontSize: 18, marginRight: 12, marginTop: 1},
  notifContent: {flex: 1},
  notifTopRow:  {flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 2},
  notifSender:  {color: '#FFF', fontSize: 13, fontWeight: '700'},
  notifTs:      {color: '#444', fontSize: 10, fontFamily: Platform.OS === 'ios' ? 'Courier' : 'monospace'},
  notifBody:    {color: '#888', fontSize: 12, lineHeight: 18},

  footer: {textAlign: 'center', color: '#2a2a2a', fontSize: 10, marginTop: 24, letterSpacing: 1},
});

export default App;
