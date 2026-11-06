# ESP32 RegaSync

O sketch em `sketch_sep27a/` adapta o circuito original: GPIO 32 (umidade), GPIO 27 (fluxo), GPIO 26 (rele ativo em HIGH), ADC 12 bits, media de 30 amostras, ADC seco 4095, ADC molhado 1690 e fluxo 450 pulsos/litro. A histerese inicial liga abaixo de 38% e desliga a partir de 45%. Calibracao, pinos e numeros dos sensores ficam em `device_config.h`.

## Preparar e gravar

1. Na Arduino IDE, instale o core **esp32** da Espressif e as bibliotecas **ArduinoMqttClient** (verificado com 0.1.8) e **ArduinoJson** (verificado com 7.4.3).
2. Abra `sketch_sep27a/sketch_sep27a.ino`. Copie `secrets.example.h` para `secrets.h` na mesma pasta e preencha Wi-Fi, IP/hostname da VPS, usuario `MQTT_ESP_USERNAME` e senha `MQTT_ESP_PASSWORD`. O host deve conter apenas IP/hostname. Mantenha TLS ativo e porta 8883 para a VPS.
3. Selecione a placa e porta USB corretas e envie pela IDE. A verificacao de compilacao foi feita com **ESP32 Dev Module**, core 3.3.12. O envio USB e a validacao dos sensores dependem da placa conectada.
4. No RegaSync, cadastre umidade numero 1 (ADC seco 4095 e molhado 1690) e fluxo numero 1 (450 pulsos/litro inicialmente), ou ajuste os numeros/calibracao no header para corresponder aos cadastros. Depois de calibrar o fluxo pelo copo de 500 ml, atualize o cadastro do sensor com o fator calculado pelo ESP. O comando MQTT de configuracao nao altera a calibracao.

`secrets.h` e ignorado pelo Git. O exemplo tem marcadores que deixam a rede desativada ate serem preenchidos. As raizes publicas ISRG X1/X2 em `trusted_roots.h` permitem validar certificados Let's Encrypt sem desativar verificacao TLS. O firmware sincroniza UTC via NTP antes de iniciar TLS/publicar leituras. A rede precisa permitir DNS/NTP e saida TCP 8883.

## Leituras

O site tambem oferece [sessoes de rega manual com pausa confirmada do automatico](../docs/manual-irrigation.md), no card do sensor de fluxo. Essa funcao exige o sketch atualizado com `manual_irrigation.h` e as novas ACLs do broker.

O envio ocorre a cada 5 segundos inicialmente, com MQTT 3.1.1, QoS 1 e retain=false. O controle local continua a cada segundo, mesmo sem rede. A tarefa de rede usa filas e nao bloqueia a leitura nem o rele.

| Topico | Conteudo |
| --- | --- |
| `/umidade/1` | `id`, `medidoEm` UTC ISO 8601 e `adc` bruto |
| `/fluxo/1` | `id`, `medidoEm`, `pulsos` e `intervaloMs` reais da janela de envio |
| `/regasync/dispositivos/esp32/estado` | Configuracao aplicada, versao, rele, medidas e estado de conexao |

O nome `esp32` nos topicos de configuracao/estado corresponde a `MQTT_USER`, que deve ser igual a `MQTT_ESP_USERNAME` no servidor. Use esse usuario em apenas uma placa por vez, pois o identificador MQTT e exclusivo.

Os IDs combinam usuario, contador persistente de inicializacoes, identificador aleatorio do boot e sequencia. Uma mensagem reenviada apos desconexao conserva seu ID. O servidor deduplica por sensor/ID. A fila em RAM suporta 30 janelas; quando cheia, descarta novas janelas e informa no Serial. Antes da sincronizacao NTP, leituras locais continuam, mas nao sao enviadas. Nao ha armazenamento ilimitado de telemetria nem recuperacao das amostras em RAM apos desligamento. O volume exibido no Serial/estado e o volume desde o boot.

## Configuracao recebida pelo ESP

Publique um snapshot completo em `/regasync/dispositivos/esp32/config`, com **QoS 1 e retain=true**:

```json
{
  "versao": 1,
  "umidadeLigar": 38,
  "umidadeDesligar": 45,
  "regaAutomatica": true,
  "intervaloPublicacaoMs": 5000
}
```

- `versao`: inteiro de 1 a 2147483647, maior que a ultima versao aplicada. Incremente em cada alteracao. A versao 0 e reservada aos padroes locais.
- `umidadeLigar` e `umidadeDesligar`: inteiros entre 0 e 100, com ligar estritamente menor que desligar.
- `regaAutomatica=false`: desliga o rele e suspende o acionamento automatico; as leituras continuam.
- `intervaloPublicacaoMs`: inteiro entre 1000 e 60000. Altera a frequencia de publicacao; o controle local continua a cada segundo.

Mensagens incompletas, valores invalidos e versoes antigas nao alteram o controle. A configuracao e persistida na NVS antes de ser aplicada e permanece apos reiniciar. O estado retido em `/estado` confirma a versao efetivamente aplicada, normalmente em ate um segundo. O heartbeat e publicado a cada 10 segundos. O Last Will define `online=false` quando o broker detecta a desconexao; `online=true` retido deve ser interpretado junto ao horario da ultima atualizacao.

## Enviar um ajuste pela VPS

Atualize os scripts `mosquitto/start.sh` e `mosquitto/start-prod.sh` na VPS e recrie apenas o broker para aplicar as novas ACLs:

```bash
docker compose -f compose.yaml -f compose.prod.yaml up -d --force-recreate mosquitto
cp firmware/config.example.json /tmp/esp32-config.json
nano /tmp/esp32-config.json
sh scripts/configure-esp32.sh esp32 /tmp/esp32-config.json --prod
```

Execute na raiz do projeto. O script usa internamente as credenciais do servidor ja configuradas no container; nao imprime senhas. Sem `--prod`, usa o Compose de desenvolvimento. A confirmacao QoS 1 do comando indica que o broker recebeu a configuracao. Confira `/estado` para confirmar que a placa a aplicou:

```bash
docker compose -f compose.yaml -f compose.prod.yaml exec -T mosquitto sh -c 'mosquitto_sub -h 127.0.0.1 -u "$MQTT_SERVER_USERNAME" -P "$MQTT_SERVER_PASSWORD" -t "/regasync/dispositivos/$MQTT_ESP_USERNAME/estado" -q 1 -v'
```

Os ajustes desta etapa sao enviados por esse comando MQTT; nao ha formulario web para configurar o dispositivo. As telas existentes continuam responsaveis pelo cadastro e pela calibracao usada no banco.

O usuario servidor pode publicar `/config` e ler `/estado`. O usuario ESP pode somente ler sua propria configuracao e publicar seu estado, alem das leituras dos sensores. Ele nao pode alterar o topico de configuracao. As permissoes sao verificadas pelo broker.

## Calibrar o fluxo com um copo de 500 ml

O volume real vem da marca do copo. A coleta termina quando voce envia o comando; o firmware nao estima a chegada aos 500 ml usando o fator que esta sendo calibrado.

1. Grave o sketch atualizado, abra o Monitor Serial em **115200 baud** e selecione **Nova linha** (CR/LF tambem funciona).
2. Deixe a tubulacao cheia de agua e sem ar antes da medicao. Prepare um copo vazio com a marca de **500 ml**, com toda a agua que atravessa o sensor direcionada para ele.
3. Envie `CALIBRAR_FLUXO`. Esse comando **liga o rele/bomba**, zera a contagem de calibracao e suspende a rega automatica.
4. Ao atingir a marca de **500 ml**, envie `FINALIZAR_FLUXO`. O rele desliga imediatamente e o ESP calcula `pulsosPorLitro = pulsos / 0,5 = pulsos × 2`. Considere o atraso humano e a agua que continua escoando ao desligar; repita a coleta para conferir o resultado. O calculo pressupoe que foram coletados exatamente 500 ml.
5. O resultado aparece no Serial. Por exemplo, **225 pulsos em 500 ml = 450 pulsos/litro**. O novo fator e salvo na NVS e passa a valer para vazao e volume local apos a confirmacao de gravacao.
6. No RegaSync, abra **Configurar** no card do sensor de fluxo e coloque o resultado em **Pulsos por litro**. O backend recebe pulsos brutos e utiliza sua propria calibracao: a atualizacao no cadastro e manual nesta etapa. As leituras antigas preservam sua calibracao original.
7. Quando tiver concluido, envie `RETOMAR_REGA`. Isso libera a rega automatica apenas se `regaAutomatica` estiver habilitada na configuracao atual.

Comandos adicionais: `STATUS_FLUXO` mostra o fator atual e a coleta; `CANCELAR_FLUXO` desliga o rele e descarta a coleta; `AJUDA` lista os comandos. Coleta sem pulsos, fator invalido ou falha ao salvar preservam o fator anterior. Ha um limite de **120 segundos**: ao expirar, a coleta e descartada e o rele desliga. Depois de concluir, cancelar ou atingir o limite, a rega permanece pausada ate `RETOMAR_REGA`. Essa pausa tambem fica salva na NVS, portanto um reset durante a calibracao inicia com o rele desligado e a rega pausada. Um novo ajuste MQTT com `regaAutomatica=false` tambem cancela a coleta ativa.

O topico `/estado` inclui `pulsosPorLitro`, `calibracaoFluxo`, `pulsosCalibracao`, `volumeReferenciaMl` e `regaPausada`. A primeira inicializacao usa 450; nas seguintes, o fator salvo prevalece sobre o valor padrao do header. O volume acumulado desde o boot nao e recalculado retroativamente quando o fator muda.

## Verificacao local

```bash
arduino-cli compile --fqbn esp32:esp32:esp32 firmware/sketch_sep27a
c++ -std=c++17 -Wall -Wextra -Werror -pedantic src/test/firmware/flow_calibration_test.cpp -o /tmp/regasync-flow-calibration-test
/tmp/regasync-flow-calibration-test
docker run --rm --network none -e MQTT_SERVER_USERNAME=server-check -e MQTT_SERVER_PASSWORD=check-server-only -e MQTT_ESP_USERNAME=esp32 -e MQTT_ESP_PASSWORD=check-esp-only -v "$PWD/mosquitto:/bootstrap:ro" -v "$PWD/src/test/scripts/mqtt-contract.sh:/check.sh:ro" --entrypoint /bin/sh eclipse-mosquitto:2 /check.sh
```

O teste C++ verifica a conversao de 500 ml, cancelamento, coleta sem pulsos, limites e timeout, inclusive quando millis() ultrapassa seu limite. O comando Docker verifica autenticacao, ACLs e configuracao retida em um broker isolado, sem publicar portas nem usar senhas/volumes reais.

Referencias: [ArduinoMqttClient](https://github.com/arduino-libraries/ArduinoMqttClient), [Preferences/NVS](https://docs.espressif.com/projects/arduino-esp32/en/latest/tutorials/preferences.html), [certificados ISRG](https://letsencrypt.org/certificates/).
