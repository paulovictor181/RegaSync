async function refreshHumidityCalibrations() {
  const targets = document.querySelectorAll("[data-humidity-calibration-status]");
  for (const target of targets) {
    try {
      const response = await fetch("/api/sensores/" + target.dataset.sensorId + "/calibracao");
      if (response.redirected || response.status === 401) {
        window.location.href = "/login";
        return;
      }
      if (!response.ok) continue;
      const state = await response.json();
      target.textContent = state.confirmada
        ? "Versão " + state.versao + " aplicada no ESP32."
        : "Versão " +
          state.versao +
          " aguardando confirmação do ESP32. A placa precisa estar conectada.";
      const range = target.parentElement.querySelector("[data-humidity-range]");
      if (range)
        range.textContent = "ADC seco: " + state.adcSeco + " · ADC molhado: " + state.adcMolhado;
    } catch (error) {
      target.textContent =
        "Não foi possível consultar a confirmação da faixa. Tentaremos novamente.";
    }
  }
}
refreshHumidityCalibrations();
setInterval(refreshHumidityCalibrations, 5000);
