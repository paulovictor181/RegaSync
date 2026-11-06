#pragma once

constexpr int PINO_UMIDADE = 32;
constexpr int PINO_FLUXO = 27;
constexpr int PINO_RELE = 26;
constexpr int RELE_LIGADO = HIGH;
constexpr int RELE_DESLIGADO = LOW;
constexpr int ADC_SECO = 4095;
constexpr int ADC_MOLHADO = 1690;
constexpr int NUM_LEITURAS = 30;
constexpr double PULSOS_POR_LITRO = 450.0;
// Cadastre estes numeros no RegaSync com a mesma calibracao acima.
constexpr int NUMERO_SENSOR_UMIDADE = 1;
constexpr int NUMERO_SENSOR_FLUXO = 1;
