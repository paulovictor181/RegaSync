#pragma once
#include <stdint.h>

enum class ManualPhase : uint8_t { Released = 0, Settling = 1, Paused = 2, Measured = 3 };
enum class ManualAction : uint8_t { Pause, Finish, Resume };
struct ManualCommand {
  uint64_t session;
  uint64_t version;
  uint32_t sensor;
  ManualAction action;
};
struct ManualRecord {
  uint32_t magic;
  uint64_t session;
  uint64_t version;
  uint64_t initialPulses;
  uint64_t finalPulses;
  uint32_t sensor;
  ManualPhase phase;
  char boot[128];
};
struct ManualSnapshot {
  ManualRecord record;
  uint64_t currentPulses;
  bool interrupted;
  bool paused;
  bool relayOn;
  char error[120];
};
constexpr uint32_t MANUAL_RECORD_MAGIC = 0x52474D31;
constexpr uint32_t MANUAL_SETTLE_MS = 1500;
