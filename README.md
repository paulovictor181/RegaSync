# RegaSync

Aplicação Java 21 / Spring Boot com páginas Thymeleaf, Tailwind CSS, Spring Security e PostgreSQL.

## Executar localmente

Requisitos: Java 21, Node.js 22+, npm e Docker com Compose. O Maven é instalado pelo wrapper do projeto.

```bash
cp .env.example .env
```

Edite `.env` e preencha `BOOTSTRAP_ADMIN_PASSWORD` com uma senha de pelo menos 8 caracteres e no máximo 72 bytes. Use aspas simples nos valores com espaços ou caracteres especiais, pois o arquivo também será carregado pelo shell. Configure o nome e o e-mail do administrador inicial. O arquivo `.env` não é versionado.

```bash
set -a
. ./.env
set +a
docker compose up -d --wait db
npm ci
npm run build:css
./mvnw spring-boot:run
```

Acesse [http://localhost:8080/login](http://localhost:8080/login) com o e-mail e a senha configurados. A aplicação roda no host e o PostgreSQL no Docker. Se alterar `DATABASE_PORT` ou `DATABASE_NAME`, ajuste também `DATABASE_URL`.

O Tailwind gera `src/main/resources/static/css/app.css`, incluído no repositório para a aplicação já conter os estilos. Execute `npm run build:css` após mudar templates/classes; durante o desenvolvimento, use `npm run watch:css` em outro terminal. Para empacotar com o CSS atualizado:

```bash
npm ci
npm run build:css
./mvnw clean verify
./mvnw package -DskipTests
```

O JAR resultante em `target/` serve os estilos localmente, sem CDN ou Node.js em execução.

## Fluxo de acesso

- Login por e-mail (sem diferenciar maiúsculas/minúsculas) e senha, com sessão de 30 minutos de inatividade.
- Página inicial com os dados da própria conta e saída por formulário POST.
- O administrador vê **Criar usuário**. Informe nome, e-mail, senha e confirmação; a nova conta sempre recebe o perfil `USER` e já pode entrar.
- Usuários comuns não acessam as rotas administrativas. Não há cadastro público, gestão/listagem de contas ou recuperação de senha nesta etapa.

O provisionamento exige `BOOTSTRAP_ADMIN_ENABLED=true` e as três variáveis `BOOTSTRAP_ADMIN_NAME`, `BOOTSTRAP_ADMIN_EMAIL` e `BOOTSTRAP_ADMIN_PASSWORD`. Configuração inválida impede a inicialização sem exibir a senha. Ele cria apenas o primeiro administrador e nunca altera nomes, senhas ou perfis existentes. Se o e-mail já pertencer a um usuário comum, a inicialização falha em vez de promovê-lo. Se já houver outro administrador, nenhuma conta adicional é criada.

Após o primeiro acesso, defina `BOOTSTRAP_ADMIN_ENABLED=false`, remova `BOOTSTRAP_ADMIN_PASSWORD` do ambiente e reinicie a aplicação. A conta permanece no banco. Alterar a variável depois não troca a senha da conta existente.

## Configuração

| Variável | Uso |
| --- | --- |
| `DATABASE_URL` | URL JDBC; padrão `jdbc:postgresql://localhost:5432/regasync`. |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | Credenciais do PostgreSQL; senha obrigatória. |
| `DATABASE_NAME` / `DATABASE_PORT` | Banco e porta publicados pelo Compose. |
| `BOOTSTRAP_ADMIN_ENABLED` | Habilita o provisionamento; padrão `false`. |
| `BOOTSTRAP_ADMIN_NAME` / `BOOTSTRAP_ADMIN_EMAIL` / `BOOTSTRAP_ADMIN_PASSWORD` | Dados do primeiro administrador. |
| `SESSION_COOKIE_SECURE` | Defina `true` quando o acesso ocorrer por HTTPS; `false` permite desenvolvimento HTTP local. |

O banco utiliza migrações Flyway e `ddl-auto=validate`; não há recriação automática do esquema. O volume do Compose preserva os dados entre reinicializações. Use `docker compose stop db` para parar o banco preservando o volume.

Todos os formulários usam CSRF, senhas são armazenadas com BCrypt e autorização é verificada no backend. Cookies de sessão usam `HttpOnly`, `SameSite=Lax` e exclusivamente o mecanismo de cookies para rastrear sessões. Em produção, sirva por HTTPS com `SESSION_COOKIE_SECURE=true`.

## Validação

## Sensores e MQTT

Os valores ADC seco/molhado salvos no site agora sao enviados ao ESP32, com confirmacao de aplicacao na tela. Consulte [docs/humidity-calibration.md](docs/humidity-calibration.md) para atualizar servidor/broker/firmware e usar os comandos Serial `STATUS_REDE` e `STATUS_UMIDADE`.

Para medir a agua do ramal manual com pausa do automatico, use **Rega manual** no card de fluxo. O fluxo completo e os passos de atualizacao estao em [docs/manual-irrigation.md](docs/manual-irrigation.md).

O firmware integrado ao contrato do sistema e os comandos para configurar o ESP32 via MQTT estao em [firmware/README.md](firmware/README.md).

Cadastre sensores pela tela **Sensores**. A criação e alteração da configuração exigem perfil administrador; usuários autenticados podem consultar sensores e leituras. O tópico é gerado do tipo e número: `/umidade/1` ou `/fluxo/1`. Tipo e número ficam fixos depois da criação. A desativação preserva o histórico.

Umidade exige leituras ADC seco e molhado distintas. O servidor calcula `100 × (seco − ADC) / (seco − molhado)` e limita a apresentação entre 0 e 100%. Fluxo exige pulsos por litro; publique o número de pulsos contados num intervalo em milissegundos e o servidor calcula `pulsos × 60000 / (intervaloMs × pulsosPorLitro)`. Leituras guardam valor bruto, valor convertido, instante UTC e versão da calibração.

Mensagens MQTT 3.1.1 usam JSON, QoS 1 e `retain=false`:

```json
{"id":"boot-a1:42","medidoEm":"2026-11-05T12:00:00Z","adc":2410}
```

Publique no tópico de umidade cadastrado. Para vazão use `{"id":"boot-a1:43","medidoEm":"2026-11-05T12:00:01Z","pulsos":45,"intervaloMs":1000}` no tópico cadastrado de fluxo. `id` precisa ser único por sensor, inclusive após reinicializar o ESP. `medidoEm` é UTC. O ESP deve sincronizar o relógio e autenticar com `MQTT_ESP_USERNAME` e `MQTT_ESP_PASSWORD`.

Para usar a aplicação pelo IntelliJ com o broker do Compose, mantenha `MQTT_ENABLED=true` na configuração de execução e informe `MQTT_URL=tcp://localhost:1883`, `MQTT_USERNAME` e `MQTT_PASSWORD` com as credenciais de servidor presentes no `.env`. No Compose essas variáveis já apontam à rede interna. O Mosquitto separa as credenciais do ESP (publicação nos tópicos dos sensores) das credenciais do servidor, que lê os sensores e pode publicar configurações de dispositivos. O histórico abre nos últimos 30 dias e permite selecionar outro período, ordenar os valores e paginar no banco; alterar calibração preserva os valores históricos.

A API autenticada `GET /api/sensores/atuais?ids=1,2` recebe até 50 IDs e devolve um objeto indexado pelo ID do sensor. Cada item tem `valor`, `unidade`, `medidoEm` e `recebidoEm`; sensores sem leitura recente são omitidos. A página de monitoramento usa esse contrato para atualizar os valores a cada cinco segundos. Os gráficos seguem o período selecionado, com médias por hora até sete dias e por dia em períodos maiores, calculadas no banco e atualizadas ao aplicar o filtro. Intervalos sem leituras aparecem como lacunas. A data de cadastro aparece na listagem de sensores e no detalhe. As datas na interface seguem `dd/MM/aaaa HH:mm:ss` no fuso `America/Sao_Paulo` (horário de Brasília); os instantes no banco e na API continuam em UTC.

## Compose e acesso pela internet

Para iniciar desenvolvimento local com aplicação, banco e broker:

```bash
docker compose up -d --build
```

Defina `MQTT_SERVER_PASSWORD` e `MQTT_ESP_PASSWORD` em `.env`; a aplicação no Compose usa automaticamente as credenciais de servidor. A porta 1883 e o banco ficam ligados ao loopback local para permitir desenvolvimento pelo IntelliJ. A tela abre em `http://localhost:8080/sensores` após configurar o administrador inicial e desabilitar o bootstrap depois do primeiro acesso.

Para uma VPS com IP público, configure `PUBLIC_IP` e `ACME_EMAIL` no `.env`, aponte as credenciais fortes do banco/MQTT e abra TCP 80, 443 e 8883 no firewall. O servidor precisa estar acessível publicamente para o desafio HTTP-01. Em seguida execute `sh scripts/deploy-vps.sh`: ele emite o certificado IP, configura HTTPS 443 e MQTT TLS 8883 e inicia o stack. O broker publica apenas a porta TLS 8883 externamente; a porta sem TLS 1883 e o PostgreSQL ficam internos/loopback. Não publique a porta 1883 na internet.

Os certificados IP do Let's Encrypt são de curta duração. Para instalar uma renovação automática a cada 12 horas, execute `sudo sh scripts/install-renewal-timer.sh` no diretório do projeto. O serviço renova quando necessário e recarrega Nginx e Mosquitto depois de publicar o certificado renovado. Para validar a configuração do Compose, execute `docker compose -f compose.yaml -f compose.prod.yaml config`.

### Primeiro deploy na VPS

Os comandos abaixo pressupõem uma VPS Linux com Docker Engine, o plugin Docker Compose e Git instalados. Confirme com `docker --version` e `docker compose version`. O deploy também usa `envsubst` (pacote `gettext-base` em Debian/Ubuntu).

```bash
sudo apt update
sudo apt install -y git gettext-base
git clone <URL_DO_REPOSITORIO> regasync
cd regasync
cp .env.example .env
nano .env
```

No `.env`, troque `DATABASE_PASSWORD`, `MQTT_SERVER_PASSWORD` e `MQTT_ESP_PASSWORD` por segredos fortes e diferentes. Defina o IP público em `PUBLIC_IP`, seu e-mail em `ACME_EMAIL` e os dados de acesso inicial em `BOOTSTRAP_ADMIN_NAME`, `BOOTSTRAP_ADMIN_EMAIL` e `BOOTSTRAP_ADMIN_PASSWORD`. Mantenha `BOOTSTRAP_ADMIN_ENABLED=true` durante o primeiro deploy. Para gerar um segredo aleatório sem caracteres especiais, use `openssl rand -hex 32` e copie um valor diferente para cada senha.

Libere as portas TCP 80 e 443 para o site e TCP 8883 para o MQTT TLS no firewall da VPS e no painel do provedor. Se for usar UFW, autorize também sua porta SSH antes de ativá-lo. Não libere 1883 nem 5432 publicamente.

```bash
sh scripts/deploy-vps.sh
```

Quando o comando terminar, acesse `https://SEU_IP/login` e use o e-mail e a senha de administrador configurados. Depois de confirmar o login, edite `.env`, defina `BOOTSTRAP_ADMIN_ENABLED=false` e apague o valor de `BOOTSTRAP_ADMIN_PASSWORD`; aplique a mudança com:

```bash
docker compose -f compose.yaml -f compose.prod.yaml up -d --force-recreate app
sudo sh scripts/install-renewal-timer.sh
```

Para atualizar o projeto no futuro, faça `git pull` e rode `sh scripts/deploy-vps.sh` novamente. A renovação automática usa o timer instalado e não exige repetir o deploy manualmente.

```bash
npm ci
npm run check:ui
npm run build:css
./mvnw verify
```

A suíte usa Testcontainers com PostgreSQL descartável, portas dinâmicas e migrações reais. É necessário acesso ao Docker; o banco de desenvolvimento não é utilizado. O provisionamento de administrador fica desabilitado no perfil de testes.

Os testes cobrem acesso anônimo, autenticação válida/inválida, e-mail normalizado, credenciais removidas da sessão, rotação da sessão, redirecionamento após login, CSRF, logout, isolamento dos dados da conta, autorização de rotas e serviços, cadastro, e-mail duplicado, validação de senhas e provisionamento idempotente.

`npm run check:ui` verifica os templates/CSS com Prettier e faz lint do CSS com Stylelint. Não há TypeScript nesta interface. Para formatar os arquivos da interface, use `npm run format:ui`.

### Histórico de acionamentos da bomba

O dashboard mostra o último evento da bomba do ESP32 configurado em `MQTT_ESP_USERNAME`. A tela `/bomba/historico` lista as mudanças de `releLigado` recebidas em `/regasync/dispositivos/{usuario}/estado`, em páginas de dez eventos. Mensagens repetidas, fora de ordem, inválidas ou de outro dispositivo são ignoradas. O primeiro estado é marcado como inicial, pois não confirma o instante de um acionamento. O horário exibido é o informado pelo dispositivo, convertido para Brasília. O histórico começa a ser registrado após esta atualização; períodos sem conexão podem perder mudanças. O estado do relé indica o comando da bomba, sem confirmar mecanicamente o funcionamento.

O firmware passa a publicar o estado também ao detectar mudança do relé. Atualize o ESP32 para reduzir o atraso de detecção; versões anteriores enviam o estado periodicamente (a cada dez segundos) e podem omitir acionamentos curtos.

### Dashboard e cadastro de sensores

O menu **Dashboard** (`/dashboard`) abre a visão geral da irrigação, com totais de sensores, último evento da bomba, últimas leituras e gráficos dos sensores ativos. O monitoramento mostra seis sensores por página. O menu **Sensores** (`/sensores`) abre a tabela de cadastros, com busca, filtros, ordenação, paginação e acesso a detalhes, configuração e rega manual. Os gráficos individuais continuam disponíveis no detalhe do sensor.

### Consumo por modo no dashboard

O filtro de datas em `/dashboard` seleciona o período dos potes e dos gráficos, usando dias completos no horário de Brasília. O detalhe do sensor usa as mesmas datas para o gráfico e a tabela, independentemente da ordenação e da página do histórico. Os links de paginação preservam o período.

Os dois potes exibem os volumes absolutos em litros. O preenchimento representa `volume do modo / (automático + manual) × 100`; os dois ficam vazios quando o total é zero. O total de fluxo vem dos pulsos de cada amostra e da calibração vigente na medição, preservada em `volume_liters`. Dados antigos são migrados pela vazão histórica × duração da amostra. O manual vem dos volumes confirmados pelo ESP32 nas sessões do dispositivo configurado, incluindo volumes parciais já recebidos. Estes indicadores são agregados do sistema e não exibem proprietário ou detalhes privados das sessões.

Como o sensor de fluxo está antes da bifurcação, o automático é o fluxo total menos o volume manual, limitado a zero. Se o manual exceder as amostras de fluxo recebidas, o dashboard informa a inconsistência, pois falta telemetria para calcular o automático. Medições e sessões que atravessam o limite do período são rateadas proporcionalmente ao tempo, supondo vazão uniforme dentro do intervalo. A calibração atual não recalcula volumes de amostras antigas. A finalização manual congela o volume antes da retomada automática.

### Demonstração local sem seed

Defina `DEMO_ENABLED=true` no `.env` e reinicie a aplicação. No dashboard, clique em **Testar com dados fictícios** ou acesse `/dashboard/demo`. O ambiente de produção força a opção como desabilitada.

A demonstração só fica disponível quando não existem leituras, sessões manuais, estados ou eventos da bomba. Usuários e cadastros de sensores podem existir, pois o modo não os altera. São gerados em memória dois sensores, gráficos para o período selecionado e consumo de 100 L por dia (75 L automático e 25 L manual). A tela informa explicitamente que os dados são fictícios, desabilita ações sobre dispositivos e não consulta a API de leituras em tempo real. `/dashboard` continua mostrando exclusivamente os dados reais. Não existe seed, inserção automática ou necessidade de apagar registros.
