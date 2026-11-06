#pragma once
#include <stdint.h>

struct HumidityCalibration {
  uint32_t version;
  int dryAdc;
  int wetAdc;
};

inline bool validHumidityCalibration(const HumidityCalibration& value) {
  return value.version <= 2147483647UL && value.dryAdc >= 0 && value.dryAdc <= 4095 &&
         value.wetAdc >= 0 && value.wetAdc <= 4095 && value.dryAdc != value.wetAdc;
}

inline int humidityPercent(int adc, const HumidityCalibration& value) {
  if (!validHumidityCalibration(value)) return 0;
  double percent = 100.0 * (value.dryAdc - adc) / (value.dryAdc - value.wetAdc);
  if (percent <= 0) return 0;
  if (percent >= 100) return 100;
  return static_cast<int>(percent);
}
