# HONDAe2MQTT

Android app that reads real-time data from a Honda e via OBD-II over Bluetooth and publishes sensor values to an MQTT broker. The app also publishes Home Assistant discovery messages and writes CSV logs to external storage.

## Overview

- Connects to a Bluetooth OBD-II adapter (classic Bluetooth, not BLE) and queries the car's CAN bus for live values.
- Publishes selected sensor values to MQTT topics under the `hondae` topic hierarchy.
- Publishes Home Assistant discovery messages (retained, QoS 1) so sensors are automatically discovered.
- Publishes a heartbeat every 10 seconds and only re-publishes sensor topics when values change to reduce broker load.
- Writes a CSV log file to external storage when a vehicle session is active.

## Sensor data published

The app publishes individual sensor topics (examples):

- `hondae/status/soc` — State of Charge (dash display)
- `hondae/status/soc_min` — SoC (min)
- `hondae/status/soc_max` — SoC (max)
- `hondae/status/soc_delta` — SoC delta (max - min)
- `hondae/status/soh` — State of Health
- `hondae/status/power_kw` — Instantaneous power in kW
- `hondae/status/current_a` — Current in A
- `hondae/status/voltage_v` — Pack voltage in V
- `hondae/status/batt_temp_c` — Battery temperature in °C
- `hondae/status/ambient_temp_c` — Ambient temperature in °C
- `hondae/status/is_charging` — Boolean flag if charging
- `hondae/status/charging_mode` — Charging connection type (AC / DC / NC)
- `hondae/status/speed_kmh` — Speed in km/h
- `hondae/status/odo_km` — Odometer in km
- `hondae/status/aux_bat_v` — 12V auxiliary battery voltage
- `hondae/gps/lat` — GPS latitude
- `hondae/gps/lon` — GPS longitude
- `hondae/gps/elevation_m` — GPS elevation (meters)
- `hondae/meta/timestamp` — Unix timestamp of the data
- `hondae/meta/last_can_message` — Unix timestamp of the last successful CAN response
- `hondae/meta/last_can_message_ago_seconds` — Seconds since the last CAN message
- `hondae/meta/last_can_fields_csv` — CSV list of fields present in the last CAN response
- `hondae/heartbeat/status` — Heartbeat status (`online`) published periodically
- `hondae/heartbeat/timestamp` — Heartbeat timestamp
- `hondae/status/bt_connected` — Boolean: app connected to the car via Bluetooth
- `hondae/status/auto_reconnect_enabled` — Boolean: Bluetooth auto-reconnect toggle state
- `hondae/status/poll_fast_s`, `hondae/status/poll_mid_s`, `hondae/status/poll_slow_s` — current poll intervals in seconds

## Control topics (MQTT)

The app listens for command topics to control connection and polling intervals:

- `hondae/cmd/connect` — payloads accepted: `1`, `0`, `true`, `false`, `on`, `off`, `connect`, `disconnect`, `enable`, `disable`
- `hondae/cmd/auto_reconnect` — same payloads as above; controls the auto-reconnect toggle
- `hondae/cmd/poll_fast_s` — integer seconds (1–3600)
- `hondae/cmd/poll_mid_s` — integer seconds (1–3600)
- `hondae/cmd/poll_slow_s` — integer seconds (1–3600)

Commands are processed by the app and the corresponding state topics under `hondae/status/*` are updated after changes.

## Home Assistant discovery

On MQTT connect the app publishes retained discovery messages for sensors, switches and numbers under `homeassistant/...`. Discovery payloads include `state_topic` and `command_topic` where applicable so Home Assistant can create entities automatically.

## How to enter the MQTT broker and credentials

Open the app's Communicate screen and locate the `MQTT URL` input (the UI also shows a hint like `tcp://user:pass@192.168.1.x:1883`).

Enter the broker address in one of these formats:

- No authentication: `tcp://host:port`  
- Username only: `tcp://username@host:port`  
- Username and password: `tcp://username:password@host:port`

Examples:

- `tcp://192.168.1.10:1883`  
- `tcp://mqttuser@192.168.1.10:1883`  
- `tcp://mqttuser:secretpassword@mqtt.example.com:1883`

Notes:

- Credentials are parsed from the URI only when the URL starts with `tcp://` and contains an `@` symbol. The substring before the `@` is treated as `username[:password]`. If a colon is present in that substring, the part after the colon is used as password.
- After entering the URL you can press the keyboard's "Done" button (or toggle the `Send Data via MQTT` switch) to save and (re)connect the MQTT client.

## Usage

1. Pair your Bluetooth OBD-II adapter with the Android device (classic Bluetooth, not BLE).  
2. Open the app, select the paired device and allow the app to connect.  
3. Enable `Send Data via MQTT` and set the `MQTT URL` as described above.  
4. Optionally allow `Bluetooth Auto Reconnect` in the UI.  
5. Sensor values and heartbeat messages will be published to the broker and Home Assistant will discover available entities automatically.

## CSV logging

When a vehicle session begins the app creates a CSV file on external storage. The CSV header is:

`sysTimeMs,ODO,SoC (dash),SoC (min),SoC (max),SoH,Battemp,Ambienttemp,kW,Amp,Volt,AuxBat,Connection,Charging,Speed,Lat,Lon`

## Requirements and known adapters

- Requires a Bluetooth OBD-II adapter with a large message buffer (the app reads long CAN responses). Classic Bluetooth adapters with at least ~864 bytes buffer are recommended.  
- Known working adapters: OBDlink MX Bluetooth, vLinker FD, vLinker MC, vLinker BM+, vLinker BM.  
- ELM327-clone adapters are known **not** to work reliably for the Honda e CAN messages.

## Disclaimer

This app is provided for testing and experimentation. Use at your own risk. Contributions and pull requests are welcome.

## Thanks

This Work is largely based on the Work of [ToniH1987s HONDAeInsight](https://github.com/ToniH1987/HONDAeInsight) and [kobius77s HONDAe2MQTT](https://github.com/kobius77/HONDAe2MQTT)
