# Rega manual pelo site

A rede tem um medidor de fluxo antes da bifurcacao entre ramal automatico e manual. A sessao pausa o rele automatico no ESP32, mede os pulsos acumulados durante a rega manual e restaura a configuracao anterior ao finalizar. Um botao nao abre a valvula manual: a abertura e o fechamento continuam fisicos.

## Uso

1. No card do **sensor de fluxo**, clique em **Rega manual**.
2. Clique em **Iniciar rega manual e pausar automatico**. A pagina aguarda o ESP32 desligar o rele e esperar 1,5 segundo para estabilizar o fluxo antes de registrar o contador inicial. Abra o ramal manual somente depois de aparecer **Rega manual em andamento**.
3. Acompanhe os litros e o tempo. O volume e a diferenca entre os contadores de pulsos dividida pelo fator de pulsos/litro do cadastro, salvo no inicio da sessao. Repeticoes e reconexoes MQTT nao somam pulsos duas vezes.
4. **Feche a valvula manual**, depois clique em finalizar. O ESP captura o contador final mantendo o rele desligado. Apos salvar esse resultado, o servidor solicita a retomada do controle anterior e aguarda a confirmacao. Se a rega automatica ja estava desativada, ela continua desativada.

Somente quem iniciou a sessao ou um administrador pode finalizar. O historico e paginado no banco; cada usuario consulta suas sessoes e administradores podem consultar as sessoes do sensor. Ha apenas uma sessao ativa por ESP32.

## Instalacao desta atualizacao

O backend e o firmware precisam desta versao, junto com os scripts de ACL do Mosquitto. Grave o novo sketch com `manual_irrigation.h` na mesma pasta da Arduino IDE, preservando `secrets.h` e `device_config.h`. O sensor de fluxo cadastrado deve ter o mesmo numero de `NUMERO_SENSOR_FLUXO`.

O backend utiliza `MQTT_ESP_USERNAME` como identificador do ESP32. O valor deve coincidir com `MQTT_USER` em `secrets.h` (padrao `esp32`). A integracao atual tem um dispositivo configurado e um sensor fisico de fluxo nele. Cadastrar outros numeros no site nao adiciona sensores fisicos ao firmware.

Na VPS, depois de sincronizar os arquivos:

```bash
docker compose -f compose.yaml -f compose.prod.yaml up -d --force-recreate mosquitto
docker compose -f compose.yaml -f compose.prod.yaml up -d --build app
```

A migracao V3 adiciona sessoes sem alterar as leituras existentes. Nao apague volumes. Firmware antigo nao confirma a pausa; a tela permanece aguardando e nao autoriza abrir o ramal manual.

## Conexao e recuperacao

- Os comandos usam QoS 1, retain=true e uma versao monotonicamente crescente. O backend persiste a sessao e tenta publicar novamente a cada 5 segundos. O ESP persiste o ultimo comando antes de confirmar, ignora versoes antigas e nao zera o inicio ao receber repeticoes.
- A pausa manual e independente da configuracao de umidade, do comando MQTT `regaAutomatica` e da pausa da calibracao de 500 ml. O comando Serial `RETOMAR_REGA` nao consegue liberar uma sessao manual do site. Calibracao via Serial fica bloqueada durante a sessao; iniciar uma sessao cancela eventual calibracao em coleta e preserva a pausa da calibracao.
- Se a rede cair, o automatico permanece pausado. Feche a valvula manual e solicite finalizar; o site aguardara a reconexao para receber o contador final e confirmar a retomada. Nao ha retomada automatica por tempo, pois o servidor nao consegue saber se a valvula manual foi fechada.
- Se o ESP reiniciar durante a medicao, o rele inicia desligado e a pausa e recuperada da NVS. Como os pulsos durante o desligamento nao podem ser recuperados, o site preserva o ultimo volume recebido como **parcial**, sem inventar o consumo faltante. A sessao precisa ser finalizada pelo usuario para liberar a pausa.
- A tela consulta o estado a cada 2 segundos e indica falta de confirmacao recente depois de 30 segundos. O valor ao vivo depende das atualizacoes recebidas; o total final vem do contador capturado pelo ESP ao encerrar.
- O sensor continua medindo a agua dos dois ramais. A medicao manual pressupoe que o rele desligado realmente interrompe o ramal automatico. Qualquer outra saida depois do medidor tambem entra na contagem.

## Contrato MQTT

Topico de comando: `/regasync/dispositivos/{MQTT_ESP_USERNAME}/rega-manual/comando`.

```json
{"sessaoId":1,"versao":1,"sensorNumero":1,"acao":"pausar"}
```

As acoes sao `pausar`, `medir_fim` e `retomar`. Para a sessao N, as versoes sao `3*N-2`, `3*N-1` e `3*N`. Esses comandos sao gerenciados pelo backend; nao publique comandos manuais neste topico por fora do site.

Topico de confirmacao: `/regasync/dispositivos/{MQTT_ESP_USERNAME}/rega-manual/estado` (QoS 1, retido). As fases sao `pausando`, `pausada`, `medida`, `liberada`; `pausando` ainda nao autoriza rega manual.

```json
{"sessaoId":1,"versao":1,"sensorNumero":1,"fase":"pausada","automaticoPausado":true,"releLigado":false,"pulsosInicio":100,"pulsosAtuais":550,"bootId":"esp32:boot:token","interrompida":false,"medidoEm":"2026-11-05T22:00:00Z"}
```

Somente confirmacoes do dispositivo esperado, sessao/versao/numero corretos e relay desligado confirmam a pausa/finalizacao. A confirmacao de retomada exige `automaticoPausado=false`. Um heartbeat a cada 2 segundos atualiza o volume durante a sessao. O usuario ESP pode ler seus comandos e publicar seu estado; apenas o servidor pode publicar os comandos.

## Contrato HTTP autenticado

- `GET /sensores/{sensorId}/rega-manual?page=0`: painel e historico paginado (10 por pagina, mais recentes primeiro).
- `POST /sensores/{sensorId}/rega-manual/iniciar`: solicita pausa; exige CSRF e sensor de fluxo ativo.
- `POST /sensores/{sensorId}/rega-manual/{sessaoId}/finalizar`: captura final e solicita retomada; exige CSRF, proprietario ou administrador.
- `GET /api/regas-manuais/{sensorId}`: estado para polling, com `status`, `label`, `liters`, `seconds`, `canStart`, `canFinish`, `message`, `interrupted` e horarios. Nao expoe e-mail do proprietario.

Estados persistidos: `STARTING`, `RUNNING`, `STOPPING`, `RESUMING`, `COMPLETED`. Dispositivo offline mantem a operacao pendente. Timestamps sao UTC. Autorizacao e exclusao de sessoes simultaneas sao garantidas no backend e no banco.
