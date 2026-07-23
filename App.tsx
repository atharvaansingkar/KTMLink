import React, {useEffect, useRef, useState} from 'react';
import {
  AppState,
  Animated,
  Linking,
  NativeEventEmitter,
  NativeModules,
  Platform,
  SafeAreaView,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';

import {requestBluetoothPermissions} from './src/services/BleManager';
import {parseNavNotification, isUsefulNavData} from './src/services/NavParser';
import AsyncStorage from '@react-native-async-storage/async-storage';

// ─── Native Bridge ────────────────────────────────────────────────────────────
const {MapScraper, BondedKtmModule, KTMLinkService} = NativeModules;
const mapScraperEmitter = new NativeEventEmitter(MapScraper);
const serviceEmitter = new NativeEventEmitter(KTMLinkService);

// ─── Types ────────────────────────────────────────────────────────────────────
type ConnectionStatus =
  | 'no_device'        // never paired any KTM
  | 'paired_offline'   // bonded but service not yet connected
  | 'connecting'       // GATT connecting
  | 'authenticating'   // nonce exchange in progress
  | 'connected';       // authenticated, dash active

type StepKey = 'deviceFound' | 'noncesSwapped' | 'helloExchanged' | 'keysGenerated' | 'authenticated';
const STEPS: {key: StepKey; label: string; icon: string}[] = [
  {key: 'deviceFound',    label: 'Device Found',           icon: '📡'},
  {key: 'noncesSwapped',  label: 'Nonces Swapped (m1↔m2)', icon: '🔀'},
  {key: 'helloExchanged', label: 'Hello Exchanged',        icon: '👋'},
  {key: 'keysGenerated',  label: 'Secret Keys Derived',    icon: '🔑'},
];

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
  const [navTitle, setNavTitle]                   = useState('');
  const [navText, setNavText]                     = useState('Waiting for Google Maps...');
  const [navManeuver, setNavManeuver]             = useState('');
  const [navEta, setNavEta]                       = useState('');
  const [navRemaining, setNavRemaining]           = useState('');
  const [navDistance, setNavDistance]             = useState('');
  const [navIconName, setNavIconName]             = useState('');
  const [navTimeRemaining, setNavTimeRemaining]   = useState('');
  const [slot6ShowingDist, setSlot6ShowingDist]   = useState(true);
  const slot6Timer = useRef<ReturnType<typeof setInterval> | null>(null);
  const slot6Dist  = useRef('');
  const slot6Time  = useRef('');
  const [lastUpdated, setLastUpdated]             = useState<string | null>(null);
  const [isListening, setIsListening]             = useState(false);
  const [hasPermission, setHasPermission]         = useState(false);

  // Connection state (driven by service events, not JS BLE)
  const [ktmDevice, setKtmDevice]               = useState<{id: string; name: string} | null>(null);
  const [connectionStatus, setConnectionStatus] = useState<ConnectionStatus>('no_device');
  const [deviceFoundName, setDeviceFoundName]   = useState('');
  const [osPairingRequired, setOsPairingRequired] = useState(false);
  const [isAppRegistered, setIsAppRegistered]     = useState(false);

  // Handshake step tracker
  const [steps, setSteps] = useState<Record<StepKey, boolean>>({
    deviceFound: false, noncesSwapped: false, helloExchanged: false,
    keysGenerated: false, authenticated: false,
  });
  const setStep = (key: StepKey) => setSteps(prev => ({...prev, [key]: true}));
  const resetSteps = () => setSteps({
    deviceFound: false, noncesSwapped: false, helloExchanged: false,
    keysGenerated: false, authenticated: false,
  });

  // In-app debug log (max 40 lines, newest at top)
  const [logLines, setLogLines] = useState<string[]>([]);
  const addLog = (msg: string) => {
    const ts = new Date().toLocaleTimeString('en-IN', {hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit'});
    setLogLines(prev => [`${ts}  ${msg}`, ...prev].slice(0, 40));
  };

  // Nav card flash
  const cardFlash = useRef(new Animated.Value(0)).current;
  const flashCard = () => {
    Animated.sequence([
      Animated.timing(cardFlash, {toValue: 1, duration: 150, useNativeDriver: false}),
      Animated.timing(cardFlash, {toValue: 0, duration: 500, useNativeDriver: false}),
    ]).start();
  };
  const cardBorder = cardFlash.interpolate({inputRange: [0,1], outputRange: ['#2a2a2a','#FF6600']});

  // Auth banner pulse
  const authPulse = useRef(new Animated.Value(1)).current;
  useEffect(() => {
    if (!steps.authenticated) return;
    const anim = Animated.loop(
      Animated.sequence([
        Animated.timing(authPulse, {toValue: 1.04, duration: 800, useNativeDriver: true}),
        Animated.timing(authPulse, {toValue: 1,    duration: 800, useNativeDriver: true}),
      ]),
    );
    anim.start();
    return () => anim.stop();
  }, [steps.authenticated, authPulse]);

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
          setConnectionStatus(prev =>
            prev === 'no_device' ? 'paired_offline' : prev,
          );
        } else {
          setKtmDevice(null);
        }
      })
      .catch(() => {});
      
    AsyncStorage.getItem('APP_IS_REGISTERED').then(val => {
      if (val === 'true') setIsAppRegistered(true);
    });
  };

  // ─── Boot: permissions → find KTM → start service ───────────────────────────
  useEffect(() => {
    refreshState();

    requestBluetoothPermissions().then(granted => {
      if (!granted) return;
      // Start the foreground service — it owns all BLE from here
      KTMLinkService?.startService?.();
    });

    // Re-check permissions + BT device whenever the user returns from Settings
    const appStateSub = AppState.addEventListener('change', state => {
      if (state === 'active') refreshState();
    });

    // ─── Listen to foreground service events ──────────────────────────────────
    const svcSub = serviceEmitter.addListener(
      'onKtmEvent',
      (event: {type: string; name?: string; msg?: string}) => {
        switch (event.type) {
          case 'LOG':
            if (event.msg) addLog(event.msg);
            break;
          case 'PLEASE_PAIR_OS':
            setOsPairingRequired(true);
            setConnectionStatus('offline');
            break;
          case 'CONNECTING':
            resetSteps();
            setConnectionStatus('connecting');
            if (event.name) { setDeviceFoundName(event.name); addLog(`Connecting: ${event.name}`); }
            break;
          case 'DEVICE_FOUND':
            setStep('deviceFound');
            setConnectionStatus('authenticating');
            if (event.name) { setDeviceFoundName(event.name); addLog(`Found: ${event.name}`); }
            break;
          case 'NONCES_SWAPPED':
            setStep('noncesSwapped');
            addLog('NONCES_SWAPPED');
            break;
          case 'HELLO_EXCHANGED':
            setStep('helloExchanged');
            addLog('HELLO_EXCHANGED');
            break;
          case 'KEYS_GENERATED':
            setStep('keysGenerated');
            addLog('KEYS_GENERATED');
            break;
          case 'AUTHENTICATED':
            setStep('authenticated');
            setConnectionStatus('connected');
            setIsAppRegistered(true);
            AsyncStorage.setItem('APP_IS_REGISTERED', 'true');
            addLog('>>> AUTHENTICATED <<<');
            break;
          case 'DISCONNECTED':
            resetSteps();
            setConnectionStatus('paired_offline');
            addLog('DISCONNECTED');
            break;
        }
      },
    );

    // ─── Maps nav events (UI only — service handles BLE writes) ──────────────
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
        // Only restart the alternation timer when the both/single state flips,
        // not on every Maps update — prevents the 0.5s flicker from constant resets.
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

  // ─── Status label helpers ────────────────────────────────────────────────────
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
          <View style={styles.filterBadge}>
            <Text style={styles.filterBadgeText}>JUNK FILTER ACTIVE</Text>
          </View>
        </Animated.View>

        {/* Bike Connection */}
        <Text style={[styles.sectionLabel, {marginTop: 24}]}>BIKE CONNECTION</Text>
        <View style={styles.card}>
          {connectionStatus === 'no_device' ? (
            // First-time: guide user to pair in BT settings
            <View style={styles.noDeviceBox}>
              <Text style={styles.noDeviceTitle}>No KTM paired yet</Text>
              <Text style={styles.noDeviceBody}>
                Pair your KTM via Android Bluetooth Settings first (for calls &amp; media),
                then reopen this app.
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
              
              {(connectionStatus === 'paired_offline' || connectionStatus === 'offline') && !osPairingRequired && (
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

          {/* Scenario 1: Needs OS Bonding */}
          {osPairingRequired && (
            <View style={[styles.registerHint, {backgroundColor: '#331100', borderColor: '#FF3300'}]}>
              <Text style={styles.registerHintTitle}>⚠️ OS Pairing Required</Text>
              <Text style={styles.registerHintBody}>
                To use auto-reconnect, you must first pair your phone with your KTM bike in your phone's Android Bluetooth settings.
              </Text>
            </View>
          )}

          {/* Scenario 2: First-time registration hint — shown while authenticating with no keys stored */}
          {connectionStatus === 'authenticating' && !isAppRegistered && !osPairingRequired && (
            <View style={styles.registerHint}>
              <Text style={styles.registerHintTitle}>Action required on bike</Text>
              <Text style={styles.registerHintBody}>
                Go to{' '}
                <Text style={styles.registerHintPath}>
                  Connectivity → Bluetooth → Phone pairing
                </Text>
                {'\n'}and accept the{' '}
                <Text style={{color: '#FF6600', fontWeight: '700'}}>
                  "Register new KTM app"
                </Text>{' '}
                prompt.{'\n\n'}
                You have ~40 seconds.
              </Text>
            </View>
          )}
          
          {/* Scenario 3: Returning user - session resuming */}
          {connectionStatus === 'authenticating' && isAppRegistered && !osPairingRequired && (
            <View style={[styles.registerHint, {borderColor: '#00AA00', paddingVertical: 12}]}>
              <Text style={[styles.registerHintTitle, {color: '#00AA00', marginBottom: 0}]}>Resuming session...</Text>
            </View>
          )}
        </View>

        {/* Handshake Tracker */}
        <Text style={[styles.sectionLabel, {marginTop: 24}]}>HANDSHAKE PROGRESSION</Text>
        <View style={styles.card}>
          {STEPS.map((step, idx) => {
            const done = steps[step.key];
            return (
              <View key={step.key} style={styles.stepRow}>
                <View style={[styles.stepBullet, done && styles.stepBulletDone]}>
                  <Text style={styles.stepNum}>{done ? '✓' : String(idx + 1)}</Text>
                </View>
                <View style={styles.stepTextWrap}>
                  <Text style={[styles.stepLabel, done && styles.stepLabelDone]}>
                    {step.icon}  {step.label}
                    {step.key === 'deviceFound' && deviceFoundName ? `: ${deviceFoundName}` : ''}
                  </Text>
                </View>
                <PulsingDot active={done} color="#00FF00" />
              </View>
            );
          })}

          {steps.authenticated ? (
            <Animated.View style={[styles.authBanner, {transform: [{scale: authPulse}]}]}>
              <Text style={styles.authBannerText}>{'>>> AUTHENTICATED <<<'}</Text>
            </Animated.View>
          ) : (
            <View style={styles.authBannerPending}>
              <Text style={styles.authBannerPendingText}>Awaiting Authentication…</Text>
            </View>
          )}
        </View>

        {/* Debug log panel — shows handshake trace without needing adb */}
        <Text style={[styles.sectionLabel, {marginTop: 24}]}>HANDSHAKE LOG</Text>
        <View style={styles.logPanel}>
          {logLines.length === 0 ? (
            <Text style={styles.logEmpty}>No events yet</Text>
          ) : (
            logLines.map((line, i) => (
              <Text key={i} style={[
                styles.logLine,
                line.includes('AUTHENTICATED') && styles.logLineAuth,
                line.includes('ERROR') && styles.logLineError,
              ]}>
                {line}
              </Text>
            ))
          )}
        </View>

        <Text style={styles.footer}>
          KTMLink · Phase 2 · {Platform.OS.toUpperCase()}
        </Text>
      </ScrollView>
    </SafeAreaView>
  );
};

// ─── Styles ───────────────────────────────────────────────────────────────────
const styles = StyleSheet.create({
  safeArea:      {flex: 1, backgroundColor: '#0a0a0a'},
  scroll:        {flex: 1},
  scrollContent: {paddingHorizontal: 20, paddingBottom: 50},

  header:       {paddingTop: 32, paddingBottom: 24, borderBottomWidth: 1, borderBottomColor: '#1a1a1a', marginBottom: 20},
  logoRow:      {flexDirection: 'row', alignItems: 'center', marginBottom: 14},
  logoAccent:   {width: 5, height: 26, backgroundColor: '#FF6600', borderRadius: 3, marginRight: 10},
  logoText:     {fontSize: 13, fontWeight: '700', letterSpacing: 4, color: '#FF6600'},
  greeting:     {fontSize: 24, fontWeight: '800', color: '#FFF', marginBottom: 4},
  subGreeting:  {fontSize: 12, color: '#555', letterSpacing: 0.4},

  statusRow:    {flexDirection: 'row', alignItems: 'center', backgroundColor: '#141414', paddingHorizontal: 14, paddingVertical: 10, borderRadius: 10, borderWidth: 1, borderColor: '#1e1e1e', marginBottom: 24},
  dot:          {width: 10, height: 10, borderRadius: 5, marginRight: 10},
  statusText:   {color: '#777', fontSize: 12},

  sectionLabel: {fontSize: 10, fontWeight: '700', letterSpacing: 2.5, color: '#FF6600', marginBottom: 8},

  card:         {backgroundColor: '#141414', borderRadius: 14, padding: 16, borderWidth: 1, borderColor: '#2a2a2a', marginBottom: 12},
  cardHeader:   {flexDirection: 'row', alignItems: 'center', marginBottom: 12},
  cardIcon:     {fontSize: 20, marginRight: 10},
  cardTitle:    {fontSize: 14, fontWeight: '700', color: '#FFF'},

  navText:      {fontSize: 18, fontWeight: '600', color: '#FFF', lineHeight: 26, marginBottom: 14},
  grid:         {flexDirection: 'row', justifyContent: 'space-between'},
  cell:         {flex: 1, marginRight: 8, backgroundColor: '#1a1a1a', padding: 8, borderRadius: 6, borderWidth: 1, borderColor: '#2e2e2e'},
  cellLabel:    {fontSize: 9, fontWeight: '700', color: '#777', marginBottom: 2},
  cellValue:    {fontSize: 12, fontWeight: '600', color: '#FF6600'},
  timestamp:    {fontSize: 10, color: '#444', marginBottom: 10, marginTop: 14},
  filterBadge:  {alignSelf: 'flex-start', backgroundColor: '#1a1a1a', borderWidth: 1, borderColor: '#2e2e2e', borderRadius: 6, paddingHorizontal: 8, paddingVertical: 3},
  filterBadgeText: {fontSize: 9, color: '#FF6600', fontWeight: '700', letterSpacing: 1},

  permButton:     {backgroundColor: '#FF6600', borderRadius: 12, paddingVertical: 14, alignItems: 'center', marginBottom: 12},
  permButtonText: {color: '#000', fontWeight: '800', fontSize: 12, letterSpacing: 1.5},

  // No device
  noDeviceBox:      {alignItems: 'center', paddingVertical: 8},
  noDeviceTitle:    {color: '#FFF', fontSize: 15, fontWeight: '700', marginBottom: 8},
  noDeviceBody:     {color: '#666', fontSize: 12, textAlign: 'center', lineHeight: 18, marginBottom: 16},
  btSettingsBtn:    {backgroundColor: '#FF660018', borderWidth: 1, borderColor: '#FF6600', borderRadius: 10, paddingHorizontal: 18, paddingVertical: 10},
  btSettingsBtnText:{color: '#FF6600', fontWeight: '700', fontSize: 11, letterSpacing: 1},

  // Device row
  deviceRow:    {flexDirection: 'row', alignItems: 'center', gap: 14},
  deviceInfo:   {flex: 1},
  btIcon:       {fontSize: 26},
  deviceName:   {color: '#FFF', fontSize: 14, fontWeight: '700'},
  deviceMac:    {color: '#444', fontSize: 10, fontFamily: Platform.OS === 'ios' ? 'Courier' : 'monospace', marginTop: 2},
  deviceStatus: {fontSize: 12, fontWeight: '600', marginTop: 5},

  // First-time registration hint
  registerHint:       {marginTop: 16, backgroundColor: '#1a1100', borderWidth: 1, borderColor: '#FF660040', borderRadius: 10, padding: 14},
  registerHintTitle:  {color: '#FF6600', fontSize: 13, fontWeight: '700', marginBottom: 6},
  registerHintBody:   {color: '#AAA', fontSize: 12, lineHeight: 20},
  registerHintPath:   {color: '#FFF', fontWeight: '600'},

  // Handshake tracker
  stepRow:          {flexDirection: 'row', alignItems: 'center', paddingVertical: 9, borderBottomWidth: 1, borderBottomColor: '#1e1e1e'},
  stepBullet:       {width: 26, height: 26, borderRadius: 13, borderWidth: 1.5, borderColor: '#333', alignItems: 'center', justifyContent: 'center', marginRight: 12},
  stepBulletDone:   {borderColor: '#00FF00', backgroundColor: '#00FF0015'},
  stepNum:          {color: '#555', fontSize: 11, fontWeight: '700'},
  stepTextWrap:     {flex: 1},
  stepLabel:        {color: '#555', fontSize: 12},
  stepLabelDone:    {color: '#CCFFCC', fontWeight: '600'},
  authBanner:       {backgroundColor: '#00FF0018', borderWidth: 1.5, borderColor: '#00FF00', borderRadius: 10, paddingVertical: 16, marginTop: 16, alignItems: 'center'},
  authBannerText:   {color: '#00FF00', fontSize: 18, fontWeight: '900', letterSpacing: 2},
  authBannerPending:      {backgroundColor: '#141414', borderWidth: 1, borderColor: '#2a2a2a', borderRadius: 10, paddingVertical: 12, marginTop: 16, alignItems: 'center'},
  authBannerPendingText:  {color: '#333', fontSize: 12, letterSpacing: 1},

  logPanel:     {backgroundColor: '#0d0d0d', borderRadius: 10, borderWidth: 1, borderColor: '#1e1e1e', padding: 10, marginBottom: 12, minHeight: 60},
  logEmpty:     {color: '#333', fontSize: 11, fontStyle: 'italic'},
  logLine:      {color: '#888', fontSize: 10, fontFamily: Platform.OS === 'ios' ? 'Courier' : 'monospace', lineHeight: 17},
  logLineAuth:  {color: '#00FF00', fontWeight: '700'},
  logLineError: {color: '#FF4444', fontWeight: '700'},

  footer: {textAlign: 'center', color: '#2a2a2a', fontSize: 10, marginTop: 24, letterSpacing: 1},
});

export default App;
