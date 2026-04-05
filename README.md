# HONDAeInsight going HONDAe2MQTT

A WIP Android App to read Real-Time-Data off of the Honda e via OBD2 ([thanks to the work of DanielH1987](https://github.com/DanielH1987/HONDAeInsight)) and publish it via MQTT.

## Neue Funktionen (2026)

### Verbesserte Bluetooth-Verbindung
- Die App trennt die Bluetooth-Verbindung nicht mehr, wenn nur die 12V-Batterie erkannt wird. Die Verbindung bleibt permanent bestehen, solange sie nicht manuell getrennt wird.
- Automatisches Wiederverbinden kann über einen neuen UI-Toggle ("Bluetooth Auto Reconnect") aktiviert/deaktiviert werden.

### MQTT-Steuerung
- Die App kann per MQTT-Topic ferngesteuert werden:
	- `hondae/cmd/connect` (Payload: `on`, `true`, `1`, `connect`, `enable` → Verbindung aufbauen; `off`, `false`, `0`, `disconnect`, `disable` → Verbindung trennen)
	- `hondae/cmd/auto_reconnect` (Payload: analog wie oben; steuert den Auto-Reconnect-Toggle)
	- `hondae/cmd/poll_fast_s` (Payload: Integer-Sekunden für Poll-Frequenz bis 3 Minuten nach letztem CAN-Datenpunkt)
	- `hondae/cmd/poll_mid_s` (Payload: Integer-Sekunden für Poll-Frequenz zwischen 3 und 30 Minuten)
	- `hondae/cmd/poll_slow_s` (Payload: Integer-Sekunden für Poll-Frequenz ab 30 Minuten)
- Die App hört automatisch auf diese Topics und setzt die Verbindung/Toggles entsprechend.

### MQTT Topic-Struktur
- Sensorwerte werden jetzt auf **einzelnen Topics** veröffentlicht (statt als JSON in einem Topic):

	- `hondae/status/soc` (State of Charge)
	- `hondae/status/soc_min` (Min SoC)
	- `hondae/status/soc_max` (Max SoC)
	- `hondae/status/soc_delta` (SoC Delta)
	- `hondae/status/soh` (State of Health)
	- `hondae/status/power_kw`
	- `hondae/status/current_a`
	- `hondae/status/voltage_v`
	- `hondae/status/batt_temp_c`
	- `hondae/status/ambient_temp_c`
	- `hondae/status/is_charging`
	- `hondae/status/charging_mode`
	- `hondae/status/speed_kmh`
	- `hondae/status/odo_km`
	- `hondae/status/aux_bat_v` (auch wenn Auto aus ist)
	- `hondae/gps/lat`, `hondae/gps/lon`, `hondae/gps/elevation_m`
	- `hondae/meta/timestamp`
	- `hondae/heartbeat/status` (publiziert `online` alle 10 Sekunden)
	- `hondae/heartbeat/timestamp` (Unix-Timestamp der Heartbeat)
	- `hondae/meta/last_can_message` (Unix-Timestamp der letzten erfolgreichen CAN-Nachricht)
	- `hondae/meta/last_can_message_ago_seconds` (Sekunden seit der letzten CAN-Nachricht)
	- `hondae/meta/last_can_fields_csv` (CSV-Liste der zuletzt in der CAN-Antwort enthaltenen Felder, z. B. `SoH,SoC,12VBat,BatteryTemp`)
	- `hondae/status/bt_connected` (true/false - aktueller Verbindungsstatus mit Auto)
	- `hondae/status/auto_reconnect_enabled` (true/false - aktueller Toggle-Status für Auto-Reconnect)
	- `hondae/status/poll_fast_s` (aktueller Fast-Poll-Wert in Sekunden)
	- `hondae/status/poll_mid_s` (aktueller Mid-Poll-Wert in Sekunden)
	- `hondae/status/poll_slow_s` (aktueller Slow-Poll-Wert in Sekunden)

### Heartbeat-Mechanismus
- Die App publiziert automatisch alle 10 Sekunden einen Heartbeat auf `hondae/heartbeat/status` mit dem Wert `online`.
- Dies funktioniert auch, wenn das Auto aus ist (12V ist verfügbar).
- Der Heartbeat triggert auch eine MQTT-Publish aller aktuellen Sensordaten (nur wenn sich Werte geändert haben).
- Damit kann man zuverlässig prüfen, ob die App noch mit dem MQTT-Broker verbunden und aktiv ist.

### Home Assistant Auto Discovery
- Die App publiziert beim MQTT-Connect automatisch MQTT Discovery-Messages für alle Sensoren.
- Home Assistant erkennt die Sensoren sofort und erstellt automatisch Entities.
- Die Sensoren sind im Device "Honda e Insight" organisiert mit eindeutiger Identifikation.
- Discovery-Messages sind retained (QoS 1) für zuverlässige Erkennung.
- Schreibbare Entitäten sind korrekt als bidirektionale HA-Controls angelegt:
	- `switch`: Connect, Auto Reconnect (`command_topic` unter `hondae/cmd/*`, `state_topic` unter `hondae/status/*`)
	- `number`: Poll Fast/Mid/Slow (`command_topic` unter `hondae/cmd/*`, `state_topic` unter `hondae/status/*`)

### Optimiertes MQTT Publishing
- Sensoren-Topics werden nur aktualisiert, wenn sich der Wert tatsächlich ändert.
- Verhindert unnötige MQTT-Messages und reduziert Traffic zum Broker.
- Beispiel: Wenn SoC unverändert bleibt, wird das Topic nicht erneut published.
- Als Resultat: minimale Last auf MQTT-Broker und Speicherverbrauch.

### Responsive Status Topics
- Die Topics `hondae/status/bt_connected` und `hondae/status/auto_reconnect_enabled` zeigen immer den aktuellen Status der App.
- Sie werden bei jeder MQTT-Publish aktualisiert (alle 10 Sekunden oder bei Werteänderungen).
- Damit können externe Systeme sehen, was die App gerade macht.

### Filterung von ungültigen Werten
- Die folgenden Topics werden nur gepublisht, wenn der Wert nicht 0 ist (da 0 bedeutet: Auto sendet keine Daten):
  - `hondae/status/soc` (0% = Auto aus)
  - `hondae/status/soh` (0 = Auto aus)
  - `hondae/status/voltage_v` (0 = Auto aus)
  - `hondae/status/batt_temp_c` (0 = Auto aus)
  - `hondae/status/ambient_temp_c` (0 = Auto aus)
  - `hondae/status/odo_km` (0 = Auto aus)
  - `hondae/status/aux_bat_v` (0 = ungültig, nur dann publish wenn > 0)

### Hinweise
- Die MQTT-URL wird wie bisher im UI eingetragen (`tcp://user:pass@host:port`).
- Der 12V-Wert wird sofort nach Empfang zu MQTT gesendet (auch einzeln, nicht nur im Sekundentakt).
- Der weiße Punkt neben "Send data via mqtt" zeigt den MQTT-Status: 🔵 = verbunden/aktiv, 🔴 = nicht verbunden.
- Die neuen Funktionen sind ab Commit April 2026 verfügbar.


---
first tests by repurposing existing functions:
- csv to be written to local storage
- API key input field used for broker connection tcp://username:password@IP_ADDRESS:PORT
- slowing down communication and prolong timeouts for higher reliablity of can-bus comms
- optimizing UI logics for usability




from the original readme:

This App is for testing purposes only! Feel free to add features, fix bugs and create pull requests.

You'll need an OBDII-Adapter that has at least 864 bytes message buffer with Bluetooth - not BLE!
A cheap ELM327-Clone won't work because of the length auf den CAN messages the e sends.

OBDII-Adapter known to be working are:
- OBDlink MX Bluetooth
- vLinker FD
- vLinker MC
- vLinker BM+
- vLinker BM

OBDII-Adapter not working:
- ELM327-Clone

Based heavily on https://github.com/harry1453/android-bluetooth-serial example App - Thanks^2!
