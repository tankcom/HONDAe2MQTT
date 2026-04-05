package de.danielh.hondae_insight;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.Checkable;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import java.io.File;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

// MQTT Imports
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class CommunicateActivity extends AppCompatActivity implements LocationListener {

    public static final int CAN_BUS_SCAN_INTERVALL = 30000;
    public static final int WAIT_FOR_NEW_MESSAGE_TIMEOUT = 1000;
    public static final int WAIT_TIME_BETWEEN_COMMAND_SENDS_MS = 50;

    // CAN Command IDs
    public static final String VIN_ID = "1862F190";
    public static final String AMBIENT_ID = "39627028";
    public static final String SOH_ID = "F6622021";
    public static final String SOC_ID = "F6622029";
    public static final String BATTEMP_ID = "F662202A";
    public static final String ODO_ID = "39627022";

    public static final int RANGE_ESTIMATE_WINDOW_5KM = 5;

    // PREFERENCES KEYS
    private static final String PREFS_KEY_MQTT_URL = "abrp_user_token";
    private static final String PREFS_KEY_MQTT_SWITCH = "iternioSendToAPISwitch";
    private static final String PREFS_KEY_BT_AUTO_RECONNECT = "bt_auto_reconnect";

    private static final String MQTT_BASE_TOPIC = "hondae";
    private static final String MQTT_TOPIC_COMMAND_CONNECT = MQTT_BASE_TOPIC + "/cmd/connect";
    private static final String MQTT_TOPIC_COMMAND_AUTO_RECONNECT = MQTT_BASE_TOPIC + "/cmd/auto_reconnect";

    private static final String NOTIFICATION_CHANNEL_ID = "SoC";
    private static final int NOTIFICATION_ID = 23;

    // Bluetooth Commands
    private final ArrayList<String> _connectionCommands = new ArrayList<>(Arrays.asList(
            "ATWS", "ATE0", "ATSP7", "ATAT1", "ATH1", "ATL0", "ATS0", "ATRV",
            "ATAL", "ATCAF1", "ATSHDA01F1", "ATFCSH18DA01F1", "ATFCSD300000",
            "ATFCSM1", "ATCFC1", "ATCP18", "ATSHDA07F1", "ATFCSH18DA07F1",
            "ATCRA18DAF107", "22F190" //VIN
    ));

    private final ArrayList<String> _loopCommands = new ArrayList<>(Arrays.asList(
            "ATSHDA60F1", "ATFCSH18DA60F1", "ATCRA18DAF160",
            "227028", //AMBIENT
            "2270229", //ODO
            "ATSHDA15F1", "ATFCSH18DA15F1", "ATCRA18DAF115",
            "222021", //SOH VOLT AMP
            "222029", //SOC
            "ATSHDA01F1", "ATFCSH18DA01F1", "ATCRA18DAF101",
            "22202A", // BATTTEMP
            "ATRV" // AUX BAT
    ));

    private final String LOG_FILE_HEADER = "sysTimeMs,ODO,SoC (dash),SoC (min),SoC (max),SoH,Battemp,Ambienttemp,kW,Amp,Volt,AuxBat,Connection,Charging,Speed,Lat,Lon";
    
    // UI Elements
    private TextView _connectionText, _vinText, _messageText, _socMinText, _socMaxText, _socDeltaText,
            _socDashText, _batTempText, _batTempDeltaText, _ambientTempText, _sohText, _kwText, _ampText, _voltText, _auxBatText, _odoText,
            _rangeText, _chargingText, _speedText, _gpsStatusText, _apiStatusText;
    
    private EditText _mqttUrlText;
    private Switch _mqttSwitch;
    private Switch _autoReconnectSwitch;
    
    private CheckBox _isChargingCheckBox;
    
    // Switch for Connection
    private Switch _connectSwitch;

    // Data Variables
    private double _soc, _socMin, _socMax, _socDelta, _soh, _speed, _power, _batTemp, _amp, _volt, _auxBat;
    private byte _ambientTemp;
    private final double[] _socHistory = new double[RANGE_ESTIMATE_WINDOW_5KM + 1];
    private final double[] _socMinHistory = new double[RANGE_ESTIMATE_WINDOW_5KM + 1];
    private final double[] _socMaxHistory = new double[RANGE_ESTIMATE_WINDOW_5KM + 1];
    private final double[] _batTempHistory = new double[RANGE_ESTIMATE_WINDOW_5KM + 1];
    private int _socHistoryPosition = 0;
    private int _lastOdo = Integer.MIN_VALUE, _odo;
    
    private String _vin;
    private String _lat = "0.0", _lon = "0.0";
    private String _gpsStatus = "No Fix";
    private double _elevation;
    
    private ChargingConnection _chargingConnection;
    private boolean _isCharging;
    
    // System Variables
    private PrintWriter _logFileWriter;
    private SharedPreferences _preferences;
    private long _sysTimeMs;
    private long _epoch, _lastEpoch, _lastEpochNotification, _lastEpochSuccessfulApiSend;
    
    private CommunicateViewModel _viewModel;
    private volatile boolean _loopRunning = false;
    private volatile boolean _mqttRunning = false;
    private boolean _carConnected = false;
    private byte _newMessage;

    private final Handler _mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable _reconnectRunnable = () -> {
        if (_viewModel != null && _viewModel.isAutoReconnectEnabled() && _connectSwitch != null && _connectSwitch.isChecked()) {
            _viewModel.connect();
        }
    };

    // MQTT Persistent Client
    private MqttClient _mqttClient;
    private MqttConnectOptions _mqttConnOpts;
    
    // Heartbeat Scheduler (10-second interval)
    private ScheduledExecutorService _heartbeatScheduler;
    private ScheduledFuture<?> _heartbeatTask;
    private long _lastHeartbeatEpoch = 0;

    NotificationCompat.Builder _notificationBuilder;
    NotificationManagerCompat _notificationManagerCompat;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_communicate);
        
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        // --- AUTO-CONNECT LOGIC START ---
        String deviceName = getIntent().getStringExtra("device_name");
        String deviceMac = getIntent().getStringExtra("device_mac");
        
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);

        if (deviceMac != null) {
            prefs.edit().putString("saved_device_name", deviceName)
                        .putString("saved_device_mac", deviceMac)
                        .apply();
        } else {
            deviceName = prefs.getString("saved_device_name", "Unknown Device");
            deviceMac = prefs.getString("saved_device_mac", null);
        }

        // Updated ViewModel init
        _viewModel = new ViewModelProvider(this).get(CommunicateViewModel.class);
        if (deviceMac == null || !_viewModel.setupViewModel(deviceName, deviceMac)) {
            finish();
            return;
        }
        // --- AUTO-CONNECT LOGIC END ---

        _preferences = getPreferences(MODE_PRIVATE);
        _chargingConnection = ChargingConnection.NC;

        // Notification Setup
        _notificationBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.e_logo)
                .setContentTitle("e Insight")
                .setContentText("Start")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        createNotificationChannel();
        _notificationManagerCompat = NotificationManagerCompat.from(this);

        // UI Setup - Find Views
        _connectionText = findViewById(R.id.communicate_connection_text);
        _messageText = findViewById(R.id.communicate_message);
        _vinText = findViewById(R.id.communicate_vin);
        _speedText = findViewById(R.id.communicate_speed);
        
        _gpsStatusText = findViewById(R.id.communicate_gps_status);
        _socMinText = findViewById(R.id.communicate_soc_min);
        _socMaxText = findViewById(R.id.communicate_soc_max);
        _socDashText = findViewById(R.id.communicate_soc_dash);
        _socDeltaText = findViewById(R.id.communicate_soc_delta);
        _chargingText = findViewById(R.id.communicate_charging_connection);
        _isChargingCheckBox = findViewById(R.id.communicate_is_charging);
        _batTempText = findViewById(R.id.communicate_battemp);
        _batTempDeltaText = findViewById(R.id.communicate_battemp_delta);
        _ambientTempText = findViewById(R.id.communicate_ambient_temp);
        _sohText = findViewById(R.id.communicate_soh);
        _kwText = findViewById(R.id.communicate_kw);
        _ampText = findViewById(R.id.communicate_amp);
        _voltText = findViewById(R.id.communicate_volt);
        _auxBatText = findViewById(R.id.communicate_aux_bat);
        _odoText = findViewById(R.id.communicate_odo);
        _rangeText = findViewById(R.id.communicate_range);
        _apiStatusText = findViewById(R.id.communicate_api_status);

        // MQTT UI Setup
        _mqttUrlText = findViewById(R.id.communicate_mqtt_url);
        _mqttSwitch = findViewById(R.id.communicate_mqtt_switch);
        _autoReconnectSwitch = findViewById(R.id.communicate_auto_reconnect_switch);
        
        // Setup Keyboard "Done" action
        _mqttUrlText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        _mqttUrlText.setSingleLine(true);

        // Handle "Enter" / "Done" on keyboard
        _mqttUrlText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {

                // 1. Save settings
                SharedPreferences.Editor edit = _preferences.edit();
                edit.putString(PREFS_KEY_MQTT_URL, v.getText().toString());
                edit.apply();

                // 2. UI cleanup
                v.clearFocus();
                hideKeyboard(v);

                // 3. Force Reconnect
                restartMqtt();
                return true;
            }
            return false;
        });

        _mqttSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> handleMqttSwitch(isChecked));
        _mqttSwitch.setChecked(_preferences.getBoolean(PREFS_KEY_MQTT_SWITCH, false));
        _mqttUrlText.setText(_preferences.getString(PREFS_KEY_MQTT_URL, "tcp://"));

        _autoReconnectSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> handleAutoReconnectSwitch(isChecked));
        boolean autoReconnectEnabled = _preferences.getBoolean(PREFS_KEY_BT_AUTO_RECONNECT, true);
        _autoReconnectSwitch.setChecked(autoReconnectEnabled);
        _viewModel.setAutoReconnectEnabled(autoReconnectEnabled);

        // Connection Switch Setup
        _connectSwitch = findViewById(R.id.communicate_connect);
        // Set initial listener
        _connectSwitch.setOnCheckedChangeListener(this::handleConnectionSwitch);
        
        // Disconnect Button (Icon)
        ImageButton disconnectButton = findViewById(R.id.button_disconnect_device);
        if (disconnectButton != null) {
            disconnectButton.setOnClickListener(v -> {
                // Manually stop everything
                _loopRunning = false;
                _viewModel.disconnect();
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                finish();
            });
        }

        _viewModel.getConnectionStatus().observe(this, this::onConnectionStatus);
        _viewModel.getDeviceName().observe(this, name -> setTitle(getString(R.string.device_name_format, name)));

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Location Permissions
        try {
            if (ContextCompat.checkSelfPermission(getApplicationContext(), android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, 101);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        LocationManager lm = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
        } catch (SecurityException e) {
            // Permission not granted
        }

        checkExternalMedia();
        
        // Connect to MQTT immediately on startup
        new Thread(this::connectToMqtt).start();
    }

    // --- FIX: Simplified Connection Switch Logic ---
    private void handleConnectionSwitch(CompoundButton buttonView, boolean isChecked) {
        if (isChecked) {
            _mainHandler.removeCallbacks(_reconnectRunnable);
            _viewModel.setRetry(true);
            _viewModel.connect();
        } else {
            _mainHandler.removeCallbacks(_reconnectRunnable);
            _viewModel.setRetry(false);
            _viewModel.disconnect();
        }
    }

private void onConnectionStatus(CommunicateViewModel.ConnectionStatus connectionStatus) {
        // 1. Detach listener to prevent loops
        _connectSwitch.setOnCheckedChangeListener(null);

        switch (connectionStatus) {
            case CONNECTED:
                _connectionText.setText(R.string.status_connected);
                _connectSwitch.setChecked(true);
                _connectSwitch.setEnabled(true);
                _mainHandler.removeCallbacks(_reconnectRunnable);
                
                // 2. Prevent double threads AND initialize the flag correctly
                if (!_loopRunning) {
                    _loopRunning = true; // <--- CRITICAL FIX: Set true BEFORE starting thread
                    new Thread(this::connectCAN).start();
                }
                break;

            case CONNECTING:
                _connectionText.setText(R.string.status_connecting);
                _connectSwitch.setChecked(true);
                _connectSwitch.setEnabled(true); 
                _viewModel.setRetry(_viewModel.isAutoReconnectEnabled());
                break;

            case DISCONNECTED:
                _loopRunning = false; // Stop loop
                _connectionText.setText(R.string.status_disconnected);
                _connectSwitch.setChecked(false);
                _connectSwitch.setEnabled(true);
                closeLogFile();
                _mainHandler.removeCallbacks(_reconnectRunnable);
                break;

            case RETRY:
                if (_viewModel.isAutoReconnectEnabled() && _connectSwitch.isChecked()) {
                    _connectionText.setText(R.string.status_reconnecting);
                    _mainHandler.removeCallbacks(_reconnectRunnable);
                    _mainHandler.postDelayed(_reconnectRunnable, 3000);
                } else {
                    _viewModel.disconnect();
                }
                break;
        }

        // 3. Re-attach listener
        _connectSwitch.setOnCheckedChangeListener(this::handleConnectionSwitch);
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        if (_preferences != null && _mqttUrlText != null) {
            SharedPreferences.Editor edit = _preferences.edit();
            String currentUrl = _mqttUrlText.getText().toString();
            if (!currentUrl.isEmpty()) {
                edit.putString(PREFS_KEY_MQTT_URL, currentUrl);
                edit.apply();
            }
        }
    }

    @Override
    protected void onDestroy() {
        // --- FIX: Stop loop to prevent phantom threads ---
        _loopRunning = false; 
        _mainHandler.removeCallbacks(_reconnectRunnable);
        
        // Stop heartbeat scheduler
        if (_heartbeatTask != null && !_heartbeatTask.isCancelled()) {
            _heartbeatTask.cancel(false);
        }
        if (_heartbeatScheduler != null && !_heartbeatScheduler.isShutdown()) {
            _heartbeatScheduler.shutdown();
        }

        try {
            if (_mqttClient != null && _mqttClient.isConnected()) {
                _mqttClient.disconnect();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        super.onDestroy();
    }

    // --- HELPER METHODS ---

    private void restartMqtt() {
        new Thread(() -> {
            try {
                if (_mqttClient != null) {
                    try {
                        if (_mqttClient.isConnected()) _mqttClient.disconnect();
                    } catch (Exception e) {}
                    try { _mqttClient.close(); } catch (Exception e) {}
                    _mqttClient = null; // Force recreation
                }
                connectToMqtt();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void hideKeyboard(View view) {
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private void connectToMqtt() {
        try {
            if (_mqttClient == null) {
                String rawInput = _preferences.getString(PREFS_KEY_MQTT_URL, "").trim();
                String brokerUrl = rawInput;
                String username = null;
                String password = null;

                if (rawInput.contains("@") && rawInput.startsWith("tcp://")) {
                    try {
                        String withoutScheme = rawInput.substring(6);
                        int atIndex = withoutScheme.lastIndexOf("@");
                        if (atIndex != -1) {
                            String userPass = withoutScheme.substring(0, atIndex);
                            String hostPort = withoutScheme.substring(atIndex + 1);
                            brokerUrl = "tcp://" + hostPort;
                            int colonIndex = userPass.indexOf(":");
                            if (colonIndex != -1) {
                                username = userPass.substring(0, colonIndex);
                                password = userPass.substring(colonIndex + 1);
                            } else {
                                username = userPass;
                            }
                        }
                    } catch (Exception e) {
                        brokerUrl = rawInput;
                    }
                }

                _mqttConnOpts = new MqttConnectOptions();
                _mqttConnOpts.setCleanSession(true);
                _mqttConnOpts.setConnectionTimeout(10); 
                _mqttConnOpts.setKeepAliveInterval(60); 
                _mqttConnOpts.setAutomaticReconnect(true);
                
                if (username != null && !username.isEmpty()) {
                    _mqttConnOpts.setUserName(username);
                    if (password != null) {
                        _mqttConnOpts.setPassword(password.toCharArray());
                    }
                }

                String clientId = "HondaE_Android_" + System.currentTimeMillis();
                _mqttClient = new MqttClient(brokerUrl, clientId, new MemoryPersistence());
                _mqttClient.setCallback(new MqttCallbackExtended() {
                    @Override
                    public void connectComplete(boolean reconnect, String serverURI) {
                        subscribeCommandTopics();
                    }

                    @Override
                    public void connectionLost(Throwable cause) {
                        setText(_apiStatusText, "🔴");
                    }

                    @Override
                    public void messageArrived(String topic, MqttMessage message) {
                        handleMqttCommand(topic, new String(message.getPayload()));
                    }

                    @Override
                    public void deliveryComplete(IMqttDeliveryToken token) {
                    }
                });
            }

            if (!_mqttClient.isConnected()) {
                _mqttClient.connect(_mqttConnOpts);
                subscribeCommandTopics();
                startHeartbeatScheduler();
                setText(_apiStatusText, "🔵"); 
            }

        } catch (Exception e) {
            setText(_apiStatusText, "🔴");
        }
    }

    private void publishMqttMessage() { 
        try {
            if (_mqttClient == null || !_mqttClient.isConnected()) {
                connectToMqtt();
            }

            if (_mqttClient != null && _mqttClient.isConnected()) {
                publishMqttTopic("status/soc", String.valueOf(_soc));
                publishMqttTopic("status/soh", String.valueOf(_soh));
                publishMqttTopic("status/power_kw", String.valueOf(_power));
                publishMqttTopic("status/current_a", String.valueOf(_amp));
                publishMqttTopic("status/voltage_v", String.valueOf(_volt));
                publishMqttTopic("status/batt_temp_c", String.valueOf(_batTemp));
                publishMqttTopic("status/ambient_temp_c", String.valueOf(_ambientTemp));
                publishMqttTopic("status/is_charging", String.valueOf(_isCharging));
                publishMqttTopic("status/charging_mode", _chargingConnection.getName());
                publishMqttTopic("status/speed_kmh", String.valueOf(_speed));
                publishMqttTopic("status/odo_km", String.valueOf(_odo));
                publishMqttTopic("status/aux_bat_v", String.valueOf(_auxBat));
                publishMqttTopic("gps/lat", _lat);
                publishMqttTopic("gps/lon", _lon);
                publishMqttTopic("gps/elevation_m", String.valueOf(_elevation));
                publishMqttTopic("meta/timestamp", String.valueOf(_epoch));

                _lastEpochSuccessfulApiSend = _epoch;
                setText(_apiStatusText, "🔵"); 
            } else {
                setText(_apiStatusText, "🔴");
            }

        } catch (Exception e) {
            if (_epoch - _lastEpochSuccessfulApiSend > 9) {
                setText(_apiStatusText, "🔴");
            }
        }
    }

    private void createNotificationChannel() {
        CharSequence name = getString(R.string.channel_name);
        String description = getString(R.string.channel_description);
        int importance = NotificationManager.IMPORTANCE_DEFAULT;
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);
        channel.setDescription(description);
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
    }

    private void connectCAN() { 
        setText(_apiStatusText, "⚪");

        while (_loopRunning && !_carConnected) {
            try {
                for (String command : _connectionCommands) {
                    // IMPORTANT: Check loop state to exit early if disconnected
                    if (!_loopRunning) return;

                    synchronized (_viewModel.getNewMessageParsed()) {
                        _viewModel.sendMessage(command + "\n\r");
                        _viewModel.getNewMessageParsed().wait(WAIT_FOR_NEW_MESSAGE_TIMEOUT);
                        if (_viewModel.isNewMessage()) {
                            final String message = _viewModel.getMessage();
                            if (_viewModel.isNewMessage() && _viewModel.getMessageID().equals(VIN_ID)) {
                                parseVIN(message);
                                setText(_vinText, _vin);
                                _carConnected = true;
                                _viewModel.setNewMessageProcessed();
                                break;
                            } else if (_viewModel.isNewMessage()) {
                                setText(_messageText, message);
                                _viewModel.setNewMessageProcessed();
                            }
                            if (message.matches("\\d+\\.\\dV")) {
                                _auxBat = Double.parseDouble(message.substring(0, message.length() - 1));
                                setText(_auxBatText, message);
                            }
                        }
                    }
                    if (command.length() <= 6) {
                        Thread.sleep(WAIT_TIME_BETWEEN_COMMAND_SENDS_MS);
                    }
                }

                if (!_carConnected) {
                    setText(_messageText, "CAN not responding, BT bleibt verbunden...");
                    Thread.sleep(2000);
                }
            } catch (InterruptedException e) {
                // Thread interrupted, likely due to disconnect
                _loopRunning = false;
                return;
            }
        }

        if (_carConnected && _loopRunning) {
            try {
                Thread.sleep(WAIT_FOR_NEW_MESSAGE_TIMEOUT);
                openNewFileForWriting();
                loop();
            } catch (InterruptedException e) {
                _loopRunning = false;
            }
        }
    }

    private void loop() { 
        _loopRunning = true;
        while (_loopRunning) {
            try {
                _sysTimeMs = System.currentTimeMillis();
                loopMessagesToVariables();

                _epoch = _sysTimeMs / 1000;
                setText(_ambientTempText, _ambientTemp + ".0°C");
                setText(_sohText, String.format(Locale.ENGLISH, "%1$05.2f%%", _soh));
                setText(_ampText, String.format(Locale.ENGLISH, "%1$06.2fA", _amp));
                setText(_voltText, String.format(Locale.ENGLISH, "%1$.1f/%2$.2fV", _volt, _volt / 96));
                setText(_kwText, String.format(Locale.ENGLISH, "%1$05.1fkW", _power));
                
                setText(_socMinText, String.format(Locale.ENGLISH, "%1$05.2f%%", _socMin));
                setText(_socMaxText, String.format(Locale.ENGLISH, "%1$05.2f%%", _socMax));
                setText(_socDeltaText, String.format(Locale.ENGLISH, "%1$4.2f%%", _socDelta));
                setText(_socDashText, String.format(Locale.ENGLISH, "%1$05.2f%%", _soc));
                setText(_chargingText, _chargingConnection.getName());
                setChecked(_isChargingCheckBox, _isCharging);
                setText(_batTempText, _batTemp + "°C");
                setText(_odoText, _odo + "km");

                setText(_speedText, _speed + "km/h");
                setText(_gpsStatusText, _gpsStatus);

                if (_newMessage > 4) {
                    setText(_messageText, String.valueOf(_epoch));
                    
                    if (_lastEpochNotification + 10 < _epoch) {
                        _notificationBuilder.setContentText("SoC " + String.valueOf(_soc) + "%");
                        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                            _notificationManagerCompat.notify(NOTIFICATION_ID, _notificationBuilder.build());
                            _lastEpochNotification = _epoch;
                        }
                    }
                    
                    writeLineToLogFile();
                    
                    if (_mqttRunning && _lastEpoch + 1 < _epoch) {
                        _lastEpoch = _epoch;
                        new Thread(this::publishMqttMessage).start();
                    }
                } else {
                    setText(_messageText, "Incomplete data (" + _newMessage + "), retrying...");
                }
                _newMessage = 0;

                Thread.sleep(CAN_BUS_SCAN_INTERVALL);

            } catch (InterruptedException e) {
                _loopRunning = false;
            } catch (Exception e) {
                String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown Error";
                setText(_messageText, "Error: " + errorMsg + ". Retrying in " + (CAN_BUS_SCAN_INTERVALL/1000) + "s...");
                try {
                    Thread.sleep(CAN_BUS_SCAN_INTERVALL);
                } catch (InterruptedException ie) {
                    _loopRunning = false;
                }
            }
        }
        _carConnected = false;
    }

    private void loopMessagesToVariables() throws InterruptedException {
        for (String command : _loopCommands) {
            if (!_loopRunning) break; // Exit loop immediately if stopped

            synchronized (_viewModel.getNewMessageParsed()) {
                _viewModel.sendMessage(command + "\n\r");
                _viewModel.getNewMessageParsed().wait(WAIT_FOR_NEW_MESSAGE_TIMEOUT);
                
                if (_viewModel.isNewMessage()) {
                    final String message = _viewModel.getMessage();
                    final String messageID = _viewModel.getMessageID();

                    if (messageID.equals(AMBIENT_ID)) {
                        if (message.length() >= 44) {
                            _ambientTemp = Integer.valueOf(message.substring(42, 44), 16).byteValue();
                            _newMessage++;
                        }
                    } else if (messageID.equals(SOH_ID)) {
                        if (message.length() >= 285) {
                            _soh = Integer.parseInt(message.substring(198, 202), 16) / 100.0;
                            _amp = Math.round((Integer.valueOf(message.substring(280, 284), 16).shortValue() / 34.0) * 100.0) / 100.0;
                            _volt = Integer.parseInt(message.substring(76, 80), 16) / 10.0;
                            _power = Math.round(_amp * _volt / 1000.0 * 10.0) / 10.0;
                            _newMessage++;
                        }
                    } else if (messageID.equals(SOC_ID)) {
                        if (message.length() >= 280) {
                            _socMin = Integer.parseInt(message.substring(142, 146), 16) / 100.0;
                            _socMax = Integer.parseInt(message.substring(138, 142), 16) / 100.0;
                            _socDelta = Math.round((_socMax - _socMin) * 100.0) / 100.0;
                            _soc = Integer.parseInt(message.substring(156, 160), 16) / 100.0;
                            
                            if (message.length() > 161) {
                                _isCharging = message.charAt(161) == '1';
                            }
                            
                            String connectionType = message.substring(277, 278);
                            switch (connectionType) {
                                case "2": _chargingConnection = ChargingConnection.AC; break;
                                case "3": _chargingConnection = ChargingConnection.DC; break;
                                default: _chargingConnection = ChargingConnection.NC;
                            }
                            _newMessage++;
                        }
                    } else if (messageID.equals(BATTEMP_ID)) {
                        if (message.length() >= 415) {
                            _batTemp = Integer.valueOf(message.substring(410, 414), 16).shortValue() / 10.0;
                            _newMessage++;
                        }
                    } else if (messageID.equals(ODO_ID)) {
                        if (message.length() >= 26) {
                            _odo = Integer.parseInt(message.substring(18, 26), 16);
                            if (_lastOdo < _odo) {
                                _lastOdo = _odo;
                                _socHistory[_socHistoryPosition] = _soc;
                                _socMinHistory[_socHistoryPosition] = _socMin;
                                _socMaxHistory[_socHistoryPosition] = _socMax;
                                _batTempHistory[_socHistoryPosition] = _batTemp;
                                _socHistoryPosition = (_socHistoryPosition + 1) % (RANGE_ESTIMATE_WINDOW_5KM + 1);
                                
                                double socDelta = _socHistory[(_socHistoryPosition + 1) % (RANGE_ESTIMATE_WINDOW_5KM + 1)] - _soc;
                                double socMinDelta = _socMinHistory[(_socHistoryPosition + 1) % (RANGE_ESTIMATE_WINDOW_5KM + 1)] - _socMin;
                                double socMaxDelta = _socMaxHistory[(_socHistoryPosition + 1) % (RANGE_ESTIMATE_WINDOW_5KM + 1)] - _socMax;
                                double batTempDelta = _batTemp - _batTempHistory[(_socHistoryPosition + 1) % (RANGE_ESTIMATE_WINDOW_5KM + 1)];
                                long socRange = Math.round((_soc / socDelta) * RANGE_ESTIMATE_WINDOW_5KM);
                                long socMinRange = Math.round((_socMin / socMinDelta) * RANGE_ESTIMATE_WINDOW_5KM);
                                long socMaxRange = Math.round((_socMax / socMaxDelta) * RANGE_ESTIMATE_WINDOW_5KM);
                                double batTempChange = batTempDelta / RANGE_ESTIMATE_WINDOW_5KM;
                                if (socRange >= 0 || socMinRange >= 0 || socMaxRange >= 0) {
                                    setText(_rangeText, String.format(Locale.ENGLISH, "%1$03dkm / %2$03dkm / %3$03dkm", socRange, socMinRange, socMaxRange));
                                    setText(_batTempDeltaText, String.format(Locale.ENGLISH, "%1$.2fK/km", batTempChange));
                                } else {
                                    setText(_rangeText, "---km / ---km / ---km");
                                }
                            }
                            _newMessage++;
                        }
                    } else if (message.matches("\\d+\\.\\dV")) {
                        _auxBat = Double.parseDouble(message.substring(0, message.length() - 1));
                        setText(_auxBatText, message);
                        // Count 12V message for publishing
                        _newMessage++;
                        // Immediately publish 12V value to MQTT
                        if (_mqttRunning) {
                            new Thread(() -> {
                                try {
                                    publishMqttTopic("status/aux_bat_v", message.substring(0, message.length() - 1));
                                } catch (Exception e) {
                                    // Silently skip on error
                                }
                            }).start();
                        }
                    }
                    _viewModel.setNewMessageProcessed();
                }
            }
            if (command.length() <= 7) {
                Thread.sleep(WAIT_TIME_BETWEEN_COMMAND_SENDS_MS);
            }
        }
    }

    private void checkExternalMedia() {
        boolean externalStorageWriteable = false;
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            externalStorageWriteable = true;
        } 
        if (!externalStorageWriteable) {
            setText(_messageText, "\n\nExternal Media: writable=" + false);
        }
    }

    private void setText(final TextView text, final String value) {
        runOnUiThread(() -> text.setText(value));
    }

    private void setChecked(final Checkable checkable, final boolean checked) {
        runOnUiThread(() -> checkable.setChecked(checked));
    }

    private void handleMqttSwitch(boolean isChecked) {
        _mqttRunning = isChecked;
        SharedPreferences.Editor edit = _preferences.edit();
        edit.putBoolean(PREFS_KEY_MQTT_SWITCH, isChecked);
        String urlText = _mqttUrlText.getText().toString();
        if (!TextUtils.isEmpty(urlText)) {
            edit.putString(PREFS_KEY_MQTT_URL, urlText);
        }
        edit.apply();
        
        if (isChecked) {
            restartMqtt();
        } else {
            connectToMqtt();
        }
    }

    private void handleAutoReconnectSwitch(boolean isChecked) {
        _viewModel.setAutoReconnectEnabled(isChecked);
        _viewModel.setRetry(isChecked);
        SharedPreferences.Editor edit = _preferences.edit();
        edit.putBoolean(PREFS_KEY_BT_AUTO_RECONNECT, isChecked);
        edit.apply();
    }

    private void subscribeCommandTopics() {
        try {
            if (_mqttClient != null && _mqttClient.isConnected()) {
                _mqttClient.subscribe(MQTT_TOPIC_COMMAND_CONNECT, 1);
                _mqttClient.subscribe(MQTT_TOPIC_COMMAND_AUTO_RECONNECT, 1);
            }
        } catch (Exception e) {
            setText(_apiStatusText, "🔴");
        }
    }

    private void handleMqttCommand(String topic, String payloadRaw) {
        final String payload = payloadRaw == null ? "" : payloadRaw.trim().toLowerCase(Locale.ENGLISH);
        runOnUiThread(() -> {
            if (MQTT_TOPIC_COMMAND_CONNECT.equals(topic)) {
                if (isOnPayload(payload)) {
                    _connectSwitch.setChecked(true);
                } else if (isOffPayload(payload)) {
                    _connectSwitch.setChecked(false);
                }
            } else if (MQTT_TOPIC_COMMAND_AUTO_RECONNECT.equals(topic)) {
                if (isOnPayload(payload)) {
                    _autoReconnectSwitch.setChecked(true);
                } else if (isOffPayload(payload)) {
                    _autoReconnectSwitch.setChecked(false);
                }
            }
        });
    }

    private boolean isOnPayload(String payload) {
        return "1".equals(payload) || "true".equals(payload) || "on".equals(payload) || "connect".equals(payload) || "enable".equals(payload) || "enabled".equals(payload);
    }

    private boolean isOffPayload(String payload) {
        return "0".equals(payload) || "false".equals(payload) || "off".equals(payload) || "disconnect".equals(payload) || "disable".equals(payload) || "disabled".equals(payload);
    }

    private void publishMqttTopic(String subTopic, String payload) throws Exception {
        if (_mqttClient == null || !_mqttClient.isConnected()) {
            return;
        }
        MqttMessage message = new MqttMessage(payload.getBytes());
        message.setQos(0);
        _mqttClient.publish(MQTT_BASE_TOPIC + "/" + subTopic, message);
    }
    
    private void startHeartbeatScheduler() {
        if (_heartbeatScheduler == null || _heartbeatScheduler.isShutdown()) {
            _heartbeatScheduler = Executors.newScheduledThreadPool(1);
        }
        
        if (_heartbeatTask != null && !_heartbeatTask.isCancelled()) {
            _heartbeatTask.cancel(false);
        }
        
        _heartbeatTask = _heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                _epoch = System.currentTimeMillis() / 1000;
                
                // Publish heartbeat
                if (_mqttRunning && _lastHeartbeatEpoch + 9 < _epoch) {
                    _lastHeartbeatEpoch = _epoch;
                    publishMqttTopic("heartbeat/status", "online");
                    publishMqttTopic("heartbeat/timestamp", String.valueOf(_epoch));
                    
                    // Also publish all current values
                    publishMqttMessage();
                    setText(_apiStatusText, "🔵");
                }
            } catch (Exception e) {
                // Silently handle exceptions in heartbeat
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    private void parseVIN(String message) {
        _vin = hexToASCII(message.substring(10, 44));
    }

    private static String hexToASCII(String hexStr) {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < hexStr.length(); i += 2) {
            String str = hexStr.substring(i, i + 2);
            output.append((char) Integer.parseInt(str, 16));
        }
        return output.toString();
    }

    private void openNewFileForWriting() {
        try {
            // Safety check for directory
            File[] dirs = this.getExternalMediaDirs();
            if (dirs == null || dirs.length == 0) return;

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
            Date now = new Date();
            File logFile = new File(dirs[0], _vin + "-" + sdf.format(now) + ".csv");
            logFile.createNewFile();
            _logFileWriter = new PrintWriter(logFile);
            _logFileWriter.println(LOG_FILE_HEADER);
        } catch (Exception e) {
             e.printStackTrace();
        }
    }

    private void writeLineToLogFile() {
        String dataLine = _sysTimeMs + "," + _odo + "," + _soc + ","
                + _socMin + "," + _socMax + "," + _soh + "," + _batTemp + ","
                + _ambientTemp + "," + _power + "," + _amp + "," + _volt + ","
                + _auxBat + "," + _chargingConnection.getName() + "," + _isCharging
                + "," + _speed + "," + _lat + "," + _lon;

        String statusMessage = "";

        if (_logFileWriter == null) {
            statusMessage = "LOG FILE MISSING ❌";
        } else {
            _logFileWriter.println(dataLine);
            if (_logFileWriter.checkError()) {
                statusMessage = "WRITE ERROR ❌";
            }
        }

        if (!statusMessage.isEmpty()) {
            setText(_messageText, statusMessage);
        }
    }

    private void closeLogFile() {
        if (_logFileWriter != null) {
            _logFileWriter.flush();
            _logFileWriter.close();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                onBackPressed();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    public void onLocationChanged(Location location) {
        _speed = Math.round(location.getSpeed() * 36) / 10.0;
        _elevation = Math.round(location.getAltitude() * 10.0) / 10.0;
        
        int accuracy = (int) location.getAccuracy();
        _gpsStatus = "Fix (±" + accuracy + "m)";
        
        _lat = String.valueOf(location.getLatitude());
        _lon = String.valueOf(location.getLongitude());
    }

    enum ChargingConnection {
        NC("NC", 0),
        AC("AC", 0),
        DC("DC", 1);
        private final String _name;
        private final int _dcfc;

        ChargingConnection(String name, int dcfc) {
            _name = name;
            _dcfc = dcfc;
        }

        public String getName() {
            return _name;
        }

        public int getDcfc() {
            return _dcfc;
        }
    }
}
