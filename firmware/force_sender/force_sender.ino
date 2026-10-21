// Force sender for the DIC Android app.
// ESP32 + HX711 load-cell amplifier -> UDP text "123.456\n" to the phone, port 5000.
//
// 1. In the app: Force sensor -> Start local hotspot. Copy SSID and password below.
// 2. Flash this sketch (Arduino IDE, board "ESP32 Dev Module", library "HX711" by Bogdan Necula).
// 3. The phone is the hotspot gateway, so its IP is found automatically (WiFi.gatewayIP()).
//    If both devices are on an ordinary Wi-Fi instead, set PHONE_IP to the IP shown in the app.
// 4. Set CAL_FACTOR so that the output is in newtons (or leave 1 and scale in the app: "Factor").

#include <WiFi.h>
#include <WiFiUdp.h>
#include "HX711.h"

const char* SSID     = "AndroidShare_xxxx";   // from the app
const char* PASSWORD = "xxxxxxxx";            // from the app
const char* PHONE_IP = "";                    // "" = use gateway (phone hotspot)
const uint16_t PORT  = 5000;

const int HX_DOUT = 16, HX_SCK = 4;
const float CAL_FACTOR = 1.0f;                // raw counts -> N
const int RATE_HZ = 50;

HX711 scale;
WiFiUDP udp;
IPAddress target;

void setup() {
  Serial.begin(115200);
  scale.begin(HX_DOUT, HX_SCK);
  scale.set_scale(CAL_FACTOR);
  scale.tare();
  WiFi.mode(WIFI_STA);
  WiFi.begin(SSID, PASSWORD);
  while (WiFi.status() != WL_CONNECTED) { delay(300); Serial.print('.'); }
  target = WiFi.gatewayIP();
  if (strlen(PHONE_IP)) target.fromString(PHONE_IP);
  Serial.printf("\nconnected, sending to %s:%u\n", target.toString().c_str(), PORT);
}

void loop() {
  static uint32_t next = 0;
  if (millis() < next) return;
  next = millis() + 1000 / RATE_HZ;
  if (WiFi.status() != WL_CONNECTED) { WiFi.reconnect(); delay(500); return; }
  if (!scale.is_ready()) return;
  float f = scale.get_units(1);
  char buf[32];
  int n = snprintf(buf, sizeof buf, "%.4f\n", f);
  udp.beginPacket(target, PORT);
  udp.write((const uint8_t*)buf, n);
  udp.endPacket();
}
