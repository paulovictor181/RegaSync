#include <cassert>
#include <cmath>
#include <cstdint>
#include <iostream>
#include "../../../firmware/sketch_sep27a/flow_calibration.h"

int main() {
  FlowCalibration calibration;
  assert(!calibration.finish(100, 225));
  assert(!calibration.cancel());
  assert(calibration.start(100));
  assert(!calibration.start(200));
  assert(calibration.finish(10000, 225));
  assert(calibration.status() == FlowCalibrationStatus::Completed);
  assert(calibration.pulses() == 225);
  assert(calibration.factor() == 450.0);
  assert(!calibration.finish(11000, 999));
  assert(calibration.factor() == 450.0);

  assert(calibration.start(12000));
  assert(calibration.finish(22000, 250));
  assert(calibration.factor() == 500.0);
  calibration.storageFailed();
  assert(calibration.status() == FlowCalibrationStatus::StorageError);

  assert(calibration.start(30000));
  assert(!calibration.finish(31000, 0));
  assert(calibration.status() == FlowCalibrationStatus::NoPulses);
  assert(calibration.factor() == 0);

  assert(calibration.start(40000));
  assert(calibration.cancel());
  assert(!calibration.active());
  assert(!calibration.finish(50000, 225));
  assert(calibration.status() == FlowCalibrationStatus::Cancelled);

  assert(calibration.start(0));
  assert(!calibration.expire(119999));
  assert(calibration.expire(120000));
  assert(calibration.status() == FlowCalibrationStatus::TimedOut);
  assert(!calibration.finish(120001, 225));
  assert(calibration.start(1000));
  assert(!calibration.finish(121000, 225));
  assert(calibration.status() == FlowCalibrationStatus::TimedOut);

  // O calculo de tempo funciona mesmo quando millis() passa de UINT32_MAX.
  const uint32_t nearWrap = UINT32_MAX - 500;
  assert(calibration.start(nearWrap));
  assert(!calibration.expire(500));
  assert(calibration.finish(1000, 225));
  assert(calibration.factor() == 450.0);
  assert(calibration.start(nearWrap));
  assert(calibration.expire(static_cast<uint32_t>(nearWrap + 120000)));

  assert(calibration.start(0));
  assert(!calibration.finish(1000, UINT32_MAX));
  assert(calibration.status() == FlowCalibrationStatus::InvalidFactor);
  assert(!FlowCalibration::validFactor(0));
  assert(!FlowCalibration::validFactor(-1));
  assert(!FlowCalibration::validFactor(NAN));
  assert(!FlowCalibration::validFactor(INFINITY));
  assert(FlowCalibration::validFactor(450));
  std::cout << "PASS: 500 ml, persistencia reportada, repeticao, cancelamento, zero pulsos, limites e timeout com rollover.\n";
}
