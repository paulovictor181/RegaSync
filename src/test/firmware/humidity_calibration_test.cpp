#include <cassert>
#include <iostream>
#include "../../../firmware/sketch_sep27a/humidity_calibration.h"

int main() {
  HumidityCalibration oldRange{0,4095,1690};
  assert(validHumidityCalibration(oldRange));
  assert(humidityPercent(773,oldRange) == 100);
  assert(humidityPercent(4095,oldRange) == 0);
  HumidityCalibration range{1,3000,600};
  assert(validHumidityCalibration(range));
  assert(humidityPercent(3000,range) == 0);
  assert(humidityPercent(600,range) == 100);
  assert(humidityPercent(1800,range) == 50);
  assert(humidityPercent(3500,range) == 0);
  assert(humidityPercent(400,range) == 100);
  HumidityCalibration reverse{2,600,3000};
  assert(validHumidityCalibration(reverse));
  assert(humidityPercent(600,reverse) == 0);
  assert(humidityPercent(3000,reverse) == 100);
  assert(humidityPercent(1800,reverse) == 50);
  assert(!validHumidityCalibration({1,600,600}));
  assert(!validHumidityCalibration({1,-1,600}));
  assert(!validHumidityCalibration({1,3000,4096}));
  assert(!validHumidityCalibration({UINT32_MAX,3000,600}));
  std::cout << "PASS: faixa ADC de 12 bits, extremos, limites, escala invertida e conversao intermediaria.\n";
}
