# ESP32 + AI-Thinker RA-02 pinout

This firmware targets a classic ESP32 DevKit (`esp32dev`) and an AI-Thinker RA-02 / SX1278 module at 433 MHz. The GPIO assignments below are defaults from `firmware/relay/include/config.h`; update that file if your ESP32 board uses different exposed pins.

| RA-02 pin | ESP32 GPIO |
| --- | ---: |
| SCK | 18 |
| MISO | 19 |
| MOSI | 23 |
| NSS / CS | 5 |
| RESET | 14 |
| DIO0 | 26 |
| DIO1 | Not connected |
| VCC | 3.3 V supply |
| GND | GND |

Use the same frequency and RadioLib defaults on both relay boards. Give each board a unique `LORA_NODE_ID` in `config.h` before flashing it.
