# EPEVER MPPT Controller

Android app that runs an EPEVER Tracer-AN MPPT from a phone over USB RS-485, using a JBD / Xiaoxiang Bluetooth BMS as the source of battery truth.

The MPPT charges normally until the BMS reports the SOC ceiling (default 95%). After that the app holds the pack at the BMS-reported voltage (default 13.42 V), not the MPPT's own battery voltage. Those two readings disagree, so the app tracks `MPPT V − BMS V` and writes an offset-trimmed CV block to the EPEVER. Solar kWh comes from the MPPT generated-energy registers; battery Wh is still integrated from BMS current.

Launcher name: **EMPPT**. It uses applicationId `com.example.emppt` so the old Riden **MPPT** app can stay installed as a backup.

## Charge policy

1. Bulk: native EPEVER charging up to the configured max battery voltage.
2. Hold: when BMS SOC reaches the ceiling, write a CV near `13.42 V + EMA(MPPT V − BMS V)`.
3. Resume bulk when SOC falls to the resume threshold (default 93%).
4. If BMS voltage overshoots hold by 0.15 V, turn the charge coil off.
5. Balance days raise the SOC target to 100% and use the 100% hold voltage.
6. BMS charge-blocking alarms inhibit charging with a growing retry backoff.

Voltage writes go to holding registers `0x9003–0x9009` (EEPROM). The app writes only on transitions, and only when CV has moved by at least 0.05 V with a 12 s cooldown.

## Hardware

- EPEVER Tracer-AN MPPT (80 A, 12/24/36/48 V, 150 V PV), Modbus RTU 115200 8N1 slave 1
- Official CC-USB-RS485-150U (often Exar XR21V1410) or a generic CH340 / FTDI RS-485 adapter
- RJ45: pins 3/4 = B, 5/6 = A, 7/8 = GND. Leave 1/2 (+5 V) disconnected
- Unplug the MT50 remote while the phone is master — two Modbus masters collide
- JBD / Xiaoxiang Bluetooth BMS on the LiFePO4 pack

## Features

- BMS dashboard for SOC, pack voltage/current, cell delta, balancing, and alarms
- MPPT dashboard for PV V/A/W, charge current, charge stage, device/RTS temperature
- Offset-trimmed voltage hold after the SOC ceiling
- Hybrid energy: EPEVER generated Wh plus app-integrated BMS Wh
- Balance-day schedule with force/cancel
- USB disconnect, low-SOC, and SOC-drift alarms
- Daily ops logs (telemetry + events) and 30-day history charts
- Foreground keep-alive so control continues with the screen off

## Development

Native Android, Kotlin, Jetpack Compose.

```powershell
.\gradlew.bat assembleDebug
```

Install and launch on a connected phone:

```powershell
.\gradlew.bat installDebug
adb shell am start -n com.example.emppt/com.example.ridenbmscontroller.MainActivity
```

## Logging

```powershell
.\synclogs.ps1
.\syncall.ps1
```

Old Riden sky-disturbance and crash-episode logs are deleted on the next log-summary refresh.

## Safety

This app writes charge-enable and voltage setpoints to real charging hardware. Keep conservative limits, verify BMS alarm handling, and treat the BMS as the final protection layer.
