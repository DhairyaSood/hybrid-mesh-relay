# ESP32-to-ESP32 LoRa link

Wire each ESP32 to an AI-Thinker RA-02 using the pinout in `pinout.md`. Both boards need the same SPI/radio settings and a 433 MHz antenna connected to the RA-02 before transmitting.

The RA-02 is a 3.3 V device. Do not connect its VCC pin to 5 V. Use a stable 3.3 V supply with enough current for the module's transmit bursts, and connect the supply ground to the ESP32 ground. Keep antenna connections secure and do not power the transmitter without an antenna.

The initial firmware is a point-to-point radio bring-up test, not a mesh relay protocol. Build from `firmware/relay` with `pio run`, flash each board with `pio run -t upload`, then open each serial monitor at 115200 baud. Set a different `LORA_NODE_ID` in `include/config.h` for each board. At either monitor, enter `send hello` to transmit; the other board prints the received packet and radio RSSI/SNR.
