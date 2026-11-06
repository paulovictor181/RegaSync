#pragma once

// Copie para secrets.h e preencha. Este arquivo nao deve receber senhas reais.
constexpr char WIFI_SSID[] = "PREENCHA_WIFI";
constexpr char WIFI_PASSWORD[] = "PREENCHA_SENHA_WIFI";
// Apenas o IP/hostname, sem https:// e sem numero de porta.
constexpr char MQTT_HOST[] = "PREENCHA_IP_DA_VPS";
constexpr uint16_t MQTT_PORT = 8883;
constexpr char MQTT_USER[] = "esp32";  // MQTT_ESP_USERNAME no servidor
constexpr char MQTT_PASSWORD[] = "PREENCHA_MQTT_ESP_PASSWORD";
constexpr bool MQTT_USE_TLS = true;

// Em producao mantenha TLS ligado. Para desenvolvimento local isolado,
// pode usar false e a porta 1883, liberada apenas na sua rede local.
