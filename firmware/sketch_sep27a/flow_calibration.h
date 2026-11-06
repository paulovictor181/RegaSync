#pragma once
#include <stdint.h>

enum class FlowCalibrationStatus : uint8_t {
  Idle, Collecting, Completed, Cancelled, TimedOut, NoPulses, InvalidFactor, StorageError
};

// Independente de GPIO/NVS para verificar o calculo e as transicoes em testes.
class FlowCalibration {
 public:
  static constexpr uint32_t targetMl = 500;
  static constexpr uint32_t timeoutMs = 120000;

  bool start(uint32_t now) {
    if (active()) return false;
    startedAt_ = now;
    pulses_ = 0;
    factor_ = 0;
    status_ = FlowCalibrationStatus::Collecting;
    return true;
  }

  bool finish(uint32_t now, uint32_t pulses) {
    if (!active()) return false;
    pulses_ = pulses;
    if (expire(now)) return false;
    if (pulses == 0) {
      status_ = FlowCalibrationStatus::NoPulses;
      return false;
    }
    factor_ = pulses * (1000.0 / targetMl);
    if (!validFactor(factor_)) {
      status_ = FlowCalibrationStatus::InvalidFactor;
      return false;
    }
    status_ = FlowCalibrationStatus::Completed;
    return true;
  }

  bool expire(uint32_t now) {
    if (!active() || static_cast<uint32_t>(now - startedAt_) < timeoutMs) return false;
    status_ = FlowCalibrationStatus::TimedOut;
    return true;
  }

  bool cancel() {
    if (!active()) return false;
    status_ = FlowCalibrationStatus::Cancelled;
    return true;
  }

  void storageFailed() { status_ = FlowCalibrationStatus::StorageError; }
  bool active() const { return status_ == FlowCalibrationStatus::Collecting; }
  FlowCalibrationStatus status() const { return status_; }
  uint32_t pulses() const { return pulses_; }
  double factor() const { return factor_; }
  static bool validFactor(double value) { return value > 0 && value <= 99999999.0; }

  static const char* label(FlowCalibrationStatus value) {
    switch (value) {
      case FlowCalibrationStatus::Collecting: return "coletando";
      case FlowCalibrationStatus::Completed: return "concluida";
      case FlowCalibrationStatus::Cancelled: return "cancelada";
      case FlowCalibrationStatus::TimedOut: return "tempo_esgotado";
      case FlowCalibrationStatus::NoPulses: return "sem_pulsos";
      case FlowCalibrationStatus::InvalidFactor: return "fator_invalido";
      case FlowCalibrationStatus::StorageError: return "falha_persistencia";
      default: return "inativa";
    }
  }

 private:
  uint32_t startedAt_ = 0;
  uint32_t pulses_ = 0;
  double factor_ = 0;
  FlowCalibrationStatus status_ = FlowCalibrationStatus::Idle;
};
