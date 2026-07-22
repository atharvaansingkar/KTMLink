import React, {useEffect, useRef, useState} from 'react';
import {
  Animated,
  NativeEventEmitter,
  NativeModules,
  Platform,
  SafeAreaView,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';

import {
  requestBluetoothPermissions,
  connectToDevice,
  shootData,
  setHandshakeCallbacks,
  streamLiveNavigation,
  showWelcomeScreen,
} from './src/services/BleManager';
import { parseNavNotification, isUsefulNavData } from './src/services/NavParser';

// ─── Native Bridge ────────────────────────────────────────────────────────────
const {MapScraper, BondedKtmModule} = NativeModules;
const mapScraperEmitter = new NativeEventEmitter(MapScraper);

// ─── Handshake Step Config ────────────────────────────────────────────────────
type StepKey = 'deviceFound' | 'noncesSwapped' | 'helloExchanged' | 'keysGenerated' | 'authenticated';
const STEPS: {key: StepKey; label: string; icon: string}[] = [
  {key: 'deviceFound',    label: 'Device Found (KTM3737)',         icon: '📡'},
  {key: 'noncesSwapped',  label: 'Nonces Swapped (m1↔m2)',         icon: '🔀'},
  {key: 'helloExchanged', label: 'Hello Exchanged (CMD_HELLO)',     icon: '👋'},
  {key: 'keysGenerated',  label: 'Secret Keys Derived (SHA-512)',   icon: '🔑'},
];

// ─── Sub-components ───────────────────────────────────────────────────────────
const PulsingDot = ({active, color = '#FF6600'}: {active: boolean; color?: string}) => {
  const scale = useRef(new Animated.Value(1)).current;
  useEffect(() => {
    if (!active) return;
    const anim = Animated.loop(
      Animated.sequence([
        Animated.timing(scale, {toValue: 1.4, duration: 700, useNativeDriver: true}),
        Animated.timing(scale, {toValue: 1, duration: 700, useNativeDriver: true}),
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
  const [navTitle, setNavTitle] = useState('');
  const [navText, setNavText]   = useState('Waiting for Google Maps...');
  const [navManeuver, setNavManeuver] = useState('');
  const [navEta, setNavEta] = useState('');
  const [navRemaining, setNavRemaining] = useState('');
  const [navDistance, setNavDistance] = useState('');
  const [navIconName, setNavIconName] = useState('');
  const [isListening, setIsListening] = useState(false);
  const [hasPermission, setHasPermission] = useState(false);
  const [lastUpdated, setLastUpdated]     = useState<string | null>(null);

  // BLE state
  const [savedKtmDevice, setSavedKtmDevice] = useState<{id: string; name: string} | null>(null);
  const [connectedDeviceId, setConnectedDeviceId] = useState<string | null>(null);
  const [isConnecting, setIsConnecting]     = useState(false);
  // disconnected = no device found at all
  // paired_offline = device is in OS bonded list but bike is off/out of range
  // connecting | authenticating | connected
  const [connectionStatus, setConnectionStatus] = useState<'disconnected' | 'paired_offline' | 'connecting' | 'authenticating' | 'connected'>('disconnected');

  // UUID debug log (raw) — kept hidden by default
  const [rawUuids, setRawUuids]           = useState<string[]>([]);
  const [showRawUuids, setShowRawUuids]   = useState(false);

  // Write test state
  const [testPayload, setTestPayload]     = useState('Hello Dash');
  const [writeStatus, setWriteStatus]     = useState<string | null>(null);

  // Handshake state machine
  const [steps, setSteps] = useState<Record<StepKey, boolean>>({
    deviceFound: false, noncesSwapped: false, helloExchanged: false,
    keysGenerated: false, authenticated: false,
  });
  const [deviceFoundName, setDeviceFoundName] = useState('');

  const setStep = (key: StepKey) =>
    setSteps(prev => ({...prev, [key]: true}));

  // Nav card flash animation
  const cardFlash = useRef(new Animated.Value(0)).current;
  const flashCard = () => {
    Animated.sequence([
      Animated.timing(cardFlash, {toValue: 1, duration: 150, useNativeDriver: false}),
      Animated.timing(cardFlash, {toValue: 0, duration: 500, useNativeDriver: false}),
    ]).start();
  };
  const cardBorder = cardFlash.interpolate({inputRange: [0, 1], outputRange: ['#2a2a2a', '#FF6600']});

  // Auth banner pulse
  const authPulse = useRef(new Animated.Value(1)).current;
  useEffect(() => {
    if (!steps.authenticated) return;
    const anim = Animated.loop(
      Animated.sequence([
        Animated.timing(authPulse, {toValue: 1.04, duration: 800, useNativeDriver: true}),
        Animated.timing(authPulse, {toValue: 1, duration: 800, useNativeDriver: true}),
      ]),
    );
    anim.start();
    return () => anim.stop();
  }, [steps.authenticated, authPulse]);

  // On mount
  useEffect(() => {
    // Notification permission
    if (MapScraper?.hasPermission) {
      MapScraper.hasPermission().then((granted: boolean) => {
        setHasPermission(granted);
        if (granted) setIsListening(true);
      });
    }

    // Check for OS-bonded KTM device using the native module, then auto-connect
    if (BondedKtmModule?.getPairedDevices) {
      requestBluetoothPermissions().then(granted => {
        if (!granted) return;
        BondedKtmModule.getPairedDevices()
          .then((devices: {name: string; id: string}[]) => {
            const ktm = devices.find((d: {name: string}) => d.name.includes('KTM'));
            if (ktm) {
              setSavedKtmDevice(ktm);
              // Show paired card immediately before attempting connection
              setConnectionStatus('paired_offline');
              // Auto-connect in background
              setIsConnecting(true);
              setConnectedDeviceId(ktm.id);
              setSteps({deviceFound: false, noncesSwapped: false, helloExchanged: false, keysGenerated: false, authenticated: false});
              connectToDevice(ktm.id)
                .then(uuids => setRawUuids(uuids))
                .catch(e => {
                  // Bike is off/out of range — stay passive, do not show error
                  console.log('[App] Bike not reachable yet, will retry when in range.', e);
                  setConnectionStatus('paired_offline');
                  setConnectedDeviceId(null);
                })
                .finally(() => setIsConnecting(false));
            }
          })
          .catch(console.error);
      });
    }

    // Register BLE handshake callbacks
    setHandshakeCallbacks({
      onDeviceFound:    (name) => { setStep('deviceFound'); setDeviceFoundName(name); setConnectionStatus('authenticating'); },
      onNoncesSwapped:  ()     => setStep('noncesSwapped'),
      onHelloExchanged: ()     => setStep('helloExchanged'),
      onKeysGenerated:  ()     => setStep('keysGenerated'),
      onAuthenticated:  ()     => { setStep('authenticated'); setConnectionStatus('connected'); },
    });

    // Maps notifications — pipe to BLE whenever connected
    const subUpdate = mapScraperEmitter.addListener(
      'onMapUpdate',
      (event: {title: string; text: string; subText: string; bigText: string; iconBase64: string}) => {
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
        setLastUpdated(new Date().toLocaleTimeString());
        flashCard();

        streamLiveNavigation(parsed);
      },
    );

    const subRemoved = mapScraperEmitter.addListener('onMapRemoved', () => {
      console.log('[App] onMapRemoved — restoring welcome screen');

      setNavTitle('');
      setNavText('Navigation Ended');
      setNavDistance('');
      setNavManeuver('');
      setNavEta('');
      setNavRemaining('');
      setNavIconName('');

      showWelcomeScreen();
    });

    return () => {
      subUpdate.remove();
      subRemoved.remove();
    };
  }, []);

  // Handlers
  const handleGrantPermission = () => MapScraper?.requestPermission?.();

  const handleConnect = async (deviceId: string) => {
    setIsConnecting(true);
    setConnectedDeviceId(deviceId);
    setConnectionStatus('connecting');
    setSteps({deviceFound: false, noncesSwapped: false, helloExchanged: false, keysGenerated: false, authenticated: false});
    try {
      const uuids = await connectToDevice(deviceId);
      setRawUuids(uuids);
    } catch (e) {
      setRawUuids([`Error: ${String(e)}`]);
      setConnectedDeviceId(null);
      setConnectionStatus('paired_offline');
    } finally {
      setIsConnecting(false);
    }
  };

  const KTM_CHARS = [
    '71ced1ac-0701-44f5-9454-806ff70b3e02',
    '71ced1ac-0702-44f5-9454-806ff70b3e02',
    '71ced1ac-0703-44f5-9454-806ff70b3e02',
    '71ced1ac-0704-44f5-9454-806ff70b3e02',
    '71ced1ac-0705-44f5-9454-806ff70b3e02',
  ];

  const handleShootData = async (charUuid: string) => {
    if (!connectedDeviceId) { setWriteStatus('Not connected'); return; }
    setWriteStatus(`Writing to ...${charUuid.substring(9, 13)}`);
    const result = await shootData(connectedDeviceId, charUuid, testPayload);
    setWriteStatus(result.message);
  };

  return (
    <SafeAreaView style={styles.safeArea}>
      <StatusBar barStyle="light-content" backgroundColor="#0a0a0a" />
      <ScrollView
        style={styles.scroll}
        contentContainerStyle={styles.scrollContent}
        keyboardShouldPersistTaps="handled"
        showsVerticalScrollIndicator={false}>

        {/* ── Header ─────────────────────────────── */}
        <View style={styles.header}>
          <View style={styles.logoRow}>
            <View style={styles.logoAccent} />
            <Text style={styles.logoText}>KTM LINK</Text>
          </View>
          <Text style={styles.greeting}>Hi Atharva, Ready to Race? 🏁</Text>
          <Text style={styles.subGreeting}>Dashboard bridge is active</Text>
        </View>

        {/* ── Maps status ────────────────────────── */}
        <View style={styles.statusRow}>
          <PulsingDot active={isListening} />
          <Text style={styles.statusText}>
            {isListening ? 'Listening for Maps data' : 'Notification access required'}
          </Text>
        </View>

        {/* ── Navigation Feed ────────────────────── */}
        <Text style={styles.sectionLabel}>NAVIGATION FEED</Text>
        <Animated.View style={[styles.card, {borderColor: cardBorder}]}>
          <View style={styles.cardHeader}>
            <Text style={styles.cardIcon}>🗺️</Text>
            <Text style={styles.cardTitle}>{navTitle || 'Google Maps'}</Text>
          </View>
          <Text style={styles.navText}>{navText}</Text>
          
          <View style={styles.parsedDataGrid}>
            <View style={styles.parsedDataCol}>
              <Text style={styles.parsedDataLabel}>1. TURN_ICON</Text>
              <Text style={styles.parsedDataValue}>{navIconName || '-'}</Text>
            </View>
            <View style={styles.parsedDataCol}>
              <Text style={styles.parsedDataLabel}>2. TURN_ROAD</Text>
              <Text style={styles.parsedDataValue}>{navTitle || '-'}</Text>
            </View>
          </View>
          <View style={[styles.parsedDataGrid, {marginTop: 10}]}>
            <View style={styles.parsedDataCol}>
              <Text style={styles.parsedDataLabel}>3. TURN_INFO</Text>
              <Text style={styles.parsedDataValue}>{navManeuver || '-'}</Text>
            </View>
            <View style={styles.parsedDataCol}>
              <Text style={styles.parsedDataLabel}>4. TURN_DISTANCE</Text>
              <Text style={styles.parsedDataValue}>{navDistance || '-'}</Text>
            </View>
          </View>
          <View style={[styles.parsedDataGrid, {marginTop: 10}]}>
            <View style={styles.parsedDataCol}>
              <Text style={styles.parsedDataLabel}>5. ETA</Text>
              <Text style={styles.parsedDataValue}>{navEta || '-'}</Text>
            </View>
            <View style={styles.parsedDataCol}>
              <Text style={styles.parsedDataLabel}>6. REMAINING_DIST</Text>
              <Text style={styles.parsedDataValue}>{navRemaining || '-'}</Text>
            </View>
          </View>

          {lastUpdated && <Text style={styles.timestamp}>Last update: {lastUpdated}</Text>}
          <View style={styles.filterBadge}>
            <Text style={styles.filterBadgeText}>🔍 JUNK FILTER ACTIVE</Text>
          </View>
        </Animated.View>

        {/* ── Permission button ──────────────────── */}
        {!hasPermission && (
          <TouchableOpacity style={styles.permButton} onPress={handleGrantPermission} activeOpacity={0.8}>
            <Text style={styles.permButtonText}>GRANT NOTIFICATION ACCESS</Text>
          </TouchableOpacity>
        )}

        {/* ── Paired Connections ─────────────────── */}
        <Text style={[styles.sectionLabel, {marginTop: 24}]}>PAIRED CONNECTIONS</Text>
        <View style={styles.card}>
          {savedKtmDevice ? (
            <View style={styles.pairedDeviceRow}>
              <Text style={styles.btIcon}>🏍️</Text>
              <View style={styles.pairedDeviceInfo}>
                <Text style={styles.deviceName}>{savedKtmDevice.name}</Text>
                <Text style={styles.deviceMac}>{savedKtmDevice.id}</Text>
                <Text style={[
                  styles.deviceStatus,
                  connectionStatus === 'connected'      && {color: '#00FF00'},
                  connectionStatus === 'connecting'     && {color: '#FF6600'},
                  connectionStatus === 'authenticating' && {color: '#FF6600'},
                  connectionStatus === 'paired_offline' && {color: '#888'},
                  connectionStatus === 'disconnected'   && {color: '#555'},
                ]}>
                  {connectionStatus === 'connected'
                    ? '● Connected'
                    : connectionStatus === 'authenticating'
                    ? '● Authenticating...'
                    : connectionStatus === 'connecting'
                    ? '● Connecting...'
                    : 'Status: Paired (Offline)'}
                </Text>
              </View>
            </View>
          ) : (
            <Text style={styles.btHint}>
              No KTM device found in OS bonded list. Please pair in Android Bluetooth Settings.
            </Text>
          )}
        </View>

        {/* ── Handshake Progression Tracker ──────── */}
        <Text style={[styles.sectionLabel, {marginTop: 24}]}>HANDSHAKE PROGRESSION TRACKER</Text>
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

          {/* AUTHENTICATED BANNER */}
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

        {/* ── Debug: Raw UUID Log (collapsible) ──── */}
        <TouchableOpacity
          style={styles.collapseHeader}
          onPress={() => setShowRawUuids(v => !v)}
          activeOpacity={0.7}>
          <Text style={styles.collapseLabel}>
            {showRawUuids ? '▾' : '▸'} Show Raw Bluetooth Mapping ({rawUuids.length} entries)
          </Text>
        </TouchableOpacity>
        {showRawUuids && (
          <View style={styles.card}>
            {rawUuids.length === 0 ? (
              <Text style={styles.btHint}>No data yet. Connect to a device.</Text>
            ) : (
              rawUuids.map((line, i) => (
                <Text key={i} style={styles.logText}>{line}</Text>
              ))
            )}
          </View>
        )}

        {/* ── Write Tester ───────────────────────── */}
        <Text style={[styles.sectionLabel, {marginTop: 24}]}>CHARACTERISTIC WRITE TEST</Text>
        <View style={styles.card}>
          <TextInput
            style={styles.inputField}
            value={testPayload}
            onChangeText={setTestPayload}
            placeholder="Payload string…"
            placeholderTextColor="#555"
            returnKeyType="done"
          />
          <View style={styles.charBtnGrid}>
            {KTM_CHARS.map(charUuid => (
              <TouchableOpacity
                key={charUuid}
                style={[styles.charBtn, !connectedDeviceId && {opacity: 0.35}]}
                disabled={!connectedDeviceId}
                onPress={() => handleShootData(charUuid)}>
                <Text style={styles.charBtnText}>{charUuid.substring(9, 13)}</Text>
              </TouchableOpacity>
            ))}
          </View>
          {writeStatus && (
            <Text style={[
              styles.writeStatus,
              {color: writeStatus.toLowerCase().includes('fail') ? '#FF4444' : '#00FF00'},
            ]}>
              {writeStatus}
            </Text>
          )}
        </View>

        {/* ── Footer ─────────────────────────────── */}
        <Text style={styles.footer}>
          KTM Link · Phase 4.8.5 Build · {Platform.OS.toUpperCase()}
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

  // Header
  header:       {paddingTop: 32, paddingBottom: 24, borderBottomWidth: 1, borderBottomColor: '#1a1a1a', marginBottom: 20},
  logoRow:      {flexDirection: 'row', alignItems: 'center', marginBottom: 14},
  logoAccent:   {width: 5, height: 26, backgroundColor: '#FF6600', borderRadius: 3, marginRight: 10},
  logoText:     {fontSize: 13, fontWeight: '700', letterSpacing: 4, color: '#FF6600'},
  greeting:     {fontSize: 24, fontWeight: '800', color: '#FFF', marginBottom: 4},
  subGreeting:  {fontSize: 12, color: '#555', letterSpacing: 0.4},

  // Status pill
  statusRow:    {flexDirection: 'row', alignItems: 'center', backgroundColor: '#141414', paddingHorizontal: 14, paddingVertical: 10, borderRadius: 10, borderWidth: 1, borderColor: '#1e1e1e', marginBottom: 24},
  dot:          {width: 10, height: 10, borderRadius: 5, marginRight: 10},
  statusText:   {color: '#777', fontSize: 12},

  // Section label
  sectionLabel: {fontSize: 10, fontWeight: '700', letterSpacing: 2.5, color: '#FF6600', marginBottom: 8},

  // Card base
  card:         {backgroundColor: '#141414', borderRadius: 14, padding: 16, borderWidth: 1, borderColor: '#2a2a2a', marginBottom: 12},
  cardHeader:   {flexDirection: 'row', alignItems: 'center', marginBottom: 12},
  cardIcon:     {fontSize: 20, marginRight: 10},
  cardTitle:    {fontSize: 14, fontWeight: '700', color: '#FFF'},

  // Nav feed
  navText:      {fontSize: 18, fontWeight: '600', color: '#FFF', lineHeight: 26, marginBottom: 14},
  
  parsedDataGrid: {flexDirection: 'row', justifyContent: 'space-between'},
  parsedDataCol:  {flex: 1, marginRight: 8, backgroundColor: '#1a1a1a', padding: 8, borderRadius: 6, borderWidth: 1, borderColor: '#2e2e2e'},
  parsedDataLabel:{fontSize: 9, fontWeight: '700', color: '#777', marginBottom: 2},
  parsedDataValue:{fontSize: 12, fontWeight: '600', color: '#FF6600'},
  
  timestamp:    {fontSize: 10, color: '#444', marginBottom: 10, marginTop: 14},
  filterBadge:  {alignSelf: 'flex-start', backgroundColor: '#1a1a1a', borderWidth: 1, borderColor: '#2e2e2e', borderRadius: 6, paddingHorizontal: 8, paddingVertical: 3},
  filterBadgeText: {fontSize: 9, color: '#FF6600', fontWeight: '700', letterSpacing: 1},

  // Permission btn
  permButton:     {backgroundColor: '#FF6600', borderRadius: 12, paddingVertical: 14, alignItems: 'center', marginBottom: 12},
  permButtonText: {color: '#000', fontWeight: '800', fontSize: 12, letterSpacing: 1.5},

  // BT scanner
  btIconRow:  {flexDirection: 'row', alignItems: 'center', gap: 12, marginBottom: 14},
  btIcon:     {fontSize: 26},
  btTitle:    {fontSize: 14, fontWeight: '700', color: '#FFF'},
  btSubtitle: {fontSize: 11, color: '#555', marginTop: 2},
  btDivider:  {height: 1, backgroundColor: '#1e1e1e', marginBottom: 14},
  btBtn:      {borderWidth: 1, borderColor: '#FF6600', backgroundColor: '#FF660018', borderRadius: 8, paddingVertical: 12, alignItems: 'center', marginBottom: 4},
  btBtnText:  {color: '#FF6600', fontWeight: '700', fontSize: 12, letterSpacing: 1.5},
  btHint:     {color: '#444', fontSize: 11, textAlign: 'center', marginTop: 10},

  deviceRow:   {flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', backgroundColor: '#1a1a1a', padding: 12, borderRadius: 8, marginTop: 8},
  deviceInfo:  {flex: 1, marginRight: 10},
  deviceName:  {color: '#FFF', fontSize: 14, fontWeight: '700'},
  deviceMac:   {color: '#444', fontSize: 10, fontFamily: Platform.OS === 'ios' ? 'Courier' : 'monospace', marginTop: 2},
  deviceStatus:{fontSize: 12, fontWeight: '600', marginTop: 5},
  deviceRssi:  {color: '#555', fontSize: 10, marginTop: 2},
  connectBtn:  {backgroundColor: '#FF6600', paddingHorizontal: 12, paddingVertical: 6, borderRadius: 6},
  connectBtnText: {color: '#000', fontSize: 11, fontWeight: '800'},

  pairedDeviceRow:  {flexDirection: 'row', alignItems: 'flex-start', gap: 14},
  pairedDeviceInfo: {flex: 1},

  // Handshake tracker
  stepRow:       {flexDirection: 'row', alignItems: 'center', paddingVertical: 9, borderBottomWidth: 1, borderBottomColor: '#1e1e1e'},
  stepBullet:    {width: 26, height: 26, borderRadius: 13, borderWidth: 1.5, borderColor: '#333', alignItems: 'center', justifyContent: 'center', marginRight: 12},
  stepBulletDone: {borderColor: '#00FF00', backgroundColor: '#00FF0015'},
  stepNum:       {color: '#555', fontSize: 11, fontWeight: '700'},
  stepTextWrap:  {flex: 1},
  stepLabel:     {color: '#555', fontSize: 12},
  stepLabelDone: {color: '#CCFFCC', fontWeight: '600'},

  authBanner:    {backgroundColor: '#00FF0018', borderWidth: 1.5, borderColor: '#00FF00', borderRadius: 10, paddingVertical: 16, marginTop: 16, alignItems: 'center'},
  authBannerText: {color: '#00FF00', fontSize: 18, fontWeight: '900', letterSpacing: 2},
  authBannerPending:     {backgroundColor: '#141414', borderWidth: 1, borderColor: '#2a2a2a', borderRadius: 10, paddingVertical: 12, marginTop: 16, alignItems: 'center'},
  authBannerPendingText: {color: '#333', fontSize: 12, letterSpacing: 1},

  // Collapsible UUID section
  collapseHeader: {flexDirection: 'row', alignItems: 'center', paddingVertical: 10, marginBottom: 6},
  collapseLabel:  {color: '#FF6600', fontSize: 11, fontWeight: '700', letterSpacing: 1},

  // Log text
  logText: {color: '#00CC00', fontFamily: Platform.OS === 'ios' ? 'Courier' : 'monospace', fontSize: 10, marginBottom: 3},

  // Write tester
  inputField:  {backgroundColor: '#1a1a1a', color: '#FFF', borderWidth: 1, borderColor: '#333', borderRadius: 8, paddingHorizontal: 14, paddingVertical: 10, fontSize: 14, marginBottom: 14},
  charBtnGrid: {flexDirection: 'row', flexWrap: 'wrap', gap: 8, marginBottom: 10},
  charBtn:     {backgroundColor: '#FF660018', borderWidth: 1, borderColor: '#FF6600', borderRadius: 6, paddingHorizontal: 12, paddingVertical: 8},
  charBtnText: {color: '#FF6600', fontWeight: '700', fontSize: 12},
  writeStatus: {fontSize: 12, fontWeight: '700', textAlign: 'center', marginTop: 4},

  // Footer
  footer: {textAlign: 'center', color: '#2a2a2a', fontSize: 10, marginTop: 24, letterSpacing: 1},
});

export default App;
