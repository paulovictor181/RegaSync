# Faixa do sensor de umidade pelo site

No dashboard, abra **Configurar** no card de umidade e altere **ADC em solo seco** e **ADC em solo molhado**. Ambos devem estar entre 0 e 4095 e ser distintos. Use leituras fisicas estaveis nas duas condicoes; o sistema nao tenta adivinhar uma nova faixa pelos valores recentes. Salve o formulario como administrador.

O backend salva a faixa, aumenta a versao quando os valores mudam e publica a configuracao retida com QoS 1 a cada 10 segundos. O ESP32 aplica somente uma versao maior que a atual, salva na NVS e passa a usar a faixa na conversao de umidade e no controle do rele. A leitura ADC bruta continua sendo enviada ao servidor. A calibracao de fluxo de 500 ml e as pausas da rega manual permanecem independentes.

A tela de configuracao e a pagina de detalhes mostram **Aguardando confirmacao do ESP32** ate o servidor receber a mesma versao e os mesmos valores aplicados. O status e consultado a cada 5 segundos. O ESP envia a confirmacao apos aplicar a faixa (ate o proximo ciclo de leitura, normalmente cerca de um segundo); com a publicacao periodica do servidor, a sincronizacao normalmente leva ate cerca de 15 segundos. Sem rede, os valores ficam pendentes no site e a placa segue usando a ultima faixa salva localmente.

Os campos do formulario representam a faixa salva no banco. Editar um campo sem clicar em salvar nao envia nenhuma alteracao. A migracao V4 adiciona apenas os dados de confirmacao. O historico mantem os valores e a versao usados em cada leitura.

## Monitor Serial

Depois de gravar o firmware atualizado, use 115200 baud e **Nova linha**. Os comandos podem ser enviados a qualquer momento:

```text
STATUS_REDE
STATUS_UMIDADE
```

`STATUS_REDE` informa a etapa atual, se as credenciais foram preenchidas, estado do Wi-Fi, ultimo motivo de desconexao, IP local/sinal, sincronizacao UTC/NTP, MQTT/TLS e tempo desde a ultima dupla de leituras confirmada pelo broker. O snapshot e consultado pela tarefa principal sem acessar o cliente MQTT simultaneamente com a tarefa de rede. Durante uma tentativa de conexao, o snapshot pode ter alguns segundos e indica quando foi atualizado.

A inicializacao tambem imprime as etapas automaticamente. A reconexao Wi-Fi e responsabilidade da biblioteca ESP32; foi removida a chamada manual periodica que podia gerar `sta is connecting`. As mensagens nao imprimem SSID, senhas nem IP publico do broker. Nao basta aparecer `ADC=...` para haver comunicacao com a VPS.

Uma publicacao confirmada por QoS 1 significa que o **broker** a recebeu. Isso nao confirma insercao no banco: o sensor precisa estar cadastrado/ativo e seus tipo/numero precisam coincidir com o firmware.

`STATUS_UMIDADE` informa os valores ADC seco/molhado em uso na placa, a versao da faixa e a ultima leitura local. A versao de calibracao e diferente de `Config=vN` exibida no log: `Config` identifica os ajustes de histerese/rega automatica. A versao de calibracao 0 identifica os padroes locais do header; a primeira faixa recebida do site e versao 1 ou superior.

## Atualizar VPS e placa

Sincronize o projeto, incluindo os scripts do Mosquitto. Recrie o broker para carregar a nova ACL e reconstrua a aplicacao:

```bash
docker compose -f compose.yaml -f compose.prod.yaml up -d --force-recreate mosquitto
docker compose -f compose.yaml -f compose.prod.yaml up -d --build app
```

Na Arduino IDE, abra o sketch atualizado e mantenha `humidity_calibration.h` na mesma pasta. Preencha/corrija `secrets.h` e envie o firmware para a placa. O SSID diferencia maiusculas e minusculas. As credenciais, a calibracao de fluxo e o estado de pausa ja salvos nao sao apagados pelo envio normal do sketch (nao habilite a opcao de apagar toda a flash).

## Contrato

Comando gerenciado pelo servidor, QoS 1 e retido:

```text
/regasync/dispositivos/{MQTT_ESP_USERNAME}/sensores/umidade/{numero}/calibracao
```

```json
{"versao":2,"sensorNumero":1,"adcSeco":3000,"adcMolhado":600}
```

Os numeros acima sao apenas exemplos. O ESP aceita somente o numero de `NUMERO_SENSOR_UMIDADE` no header. A integracao atual utiliza um ESP32, identificado por `MQTT_ESP_USERNAME` no backend e por `MQTT_USER` no sketch. A ACL permite publicar a faixa apenas ao usuario servidor; o ESP pode ler a faixa e publicar seu estado.

A confirmacao usa o estado existente `/regasync/dispositivos/{usuario}/estado`, com `online=true`, `sensorUmidadeNumero`, `versaoCalibracaoUmidade`, `adcSeco`, `adcMolhado` e `medidoEm` UTC. O backend confere usuario/topico, tipo, numero, versao e valores; mensagens antigas ou com valores diferentes nao confirmam a nova faixa.

O endpoint autenticado `GET /api/sensores/{id}/calibracao` responde `adcSeco`, `adcMolhado`, `versao`, `confirmada` e `confirmadaEm`. A alteracao continua usando o formulario administrativo existente, protegido por CSRF e perfil ADMIN. Usuarios comuns podem consultar a confirmacao.
