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

Cadastre sensores pela tela **Sensores**. A criação e alteração da configuração exigem perfil administrador; usuários autenticados podem consultar sensores e leituras. O tópico é gerado do tipo e número: `/umidade/1` ou `/fluxo/1`. Tipo e número ficam fixos depois da criação. A desativação preserva o histórico.

Umidade exige leituras ADC seco e molhado distintas. O servidor calcula `100 × (seco − ADC) / (seco − molhado)` e limita a apresentação entre 0 e 100%. Fluxo exige pulsos por litro; publique o número de pulsos contados num intervalo em milissegundos e o servidor calcula `pulsos × 60000 / (intervaloMs × pulsosPorLitro)`. Leituras guardam valor bruto, valor convertido, instante UTC e versão da calibração.

Mensagens MQTT 3.1.1 usam JSON, QoS 1 e `retain=false`:

```json
{"id":"boot-a1:42","medidoEm":"2026-11-05T12:00:00Z","adc":2410}
```

Publique no tópico de umidade cadastrado. Para vazão use `{"id":"boot-a1:43","medidoEm":"2026-11-05T12:00:01Z","pulsos":45,"intervaloMs":1000}` no tópico cadastrado de fluxo. `id` precisa ser único por sensor, inclusive após reinicializar o ESP. `medidoEm` é UTC. O ESP deve sincronizar o relógio e autenticar com `MQTT_ESP_USERNAME` e `MQTT_ESP_PASSWORD`.

Para usar a aplicação pelo IntelliJ com o broker do Compose, mantenha `MQTT_ENABLED=true` na configuração de execução e informe `MQTT_URL=tcp://localhost:1883`, `MQTT_USERNAME` e `MQTT_PASSWORD` com as credenciais de servidor presentes no `.env`. No Compose essas variáveis já apontam à rede interna. O Mosquitto separa as credenciais do ESP (publicação nos tópicos dos sensores) das credenciais de leitura do servidor. O histórico é consultado por até 30 dias nesta tela; alterar calibração preserva os valores históricos.

A API autenticada `GET /api/sensores/atuais?ids=1,2` recebe até 50 IDs e devolve um objeto indexado pelo ID do sensor. Cada item tem `valor`, `unidade`, `medidoEm` e `recebidoEm`; sensores sem leitura recente são omitidos. A página de monitoramento usa esse contrato para atualizar os valores a cada cinco segundos.

## Compose e acesso pela internet

Para iniciar desenvolvimento local com aplicação, banco e broker:

```bash
docker compose up -d --build
```

Defina `MQTT_SERVER_PASSWORD` e `MQTT_ESP_PASSWORD` em `.env`; a aplicação no Compose usa automaticamente as credenciais de servidor. A porta 1883 e o banco ficam ligados ao loopback local para permitir desenvolvimento pelo IntelliJ. A tela abre em `http://localhost:8080/sensores` após configurar o administrador inicial e desabilitar o bootstrap depois do primeiro acesso.

Para uma VPS com IP público, configure `PUBLIC_IP` e `ACME_EMAIL` no `.env`, aponte as credenciais fortes do banco/MQTT e abra TCP 80, 443 e 8883 no firewall. O servidor precisa estar acessível publicamente para o desafio HTTP-01. Em seguida execute `sh scripts/deploy-vps.sh`: ele emite o certificado IP, configura HTTPS 443 e MQTT TLS 8883 e inicia o stack. O broker publica apenas a porta TLS 8883 externamente; a porta sem TLS 1883 e o PostgreSQL ficam internos/loopback. Não publique a porta 1883 na internet.

Os certificados IP do Let's Encrypt são de curta duração. Para instalar uma renovação automática a cada 12 horas, execute `sudo sh scripts/install-renewal-timer.sh` no diretório do projeto. O serviço renova quando necessário e recarrega Nginx e Mosquitto depois de publicar o certificado renovado. Para validar a configuração do Compose, execute `docker compose -f compose.yaml -f compose.prod.yaml config`.

```bash
npm ci
npm run check:ui
npm run build:css
./mvnw verify
```

A suíte usa Testcontainers com PostgreSQL descartável, portas dinâmicas e migrações reais. É necessário acesso ao Docker; o banco de desenvolvimento não é utilizado. O provisionamento de administrador fica desabilitado no perfil de testes.

Os testes cobrem acesso anônimo, autenticação válida/inválida, e-mail normalizado, credenciais removidas da sessão, rotação da sessão, redirecionamento após login, CSRF, logout, isolamento dos dados da conta, autorização de rotas e serviços, cadastro, e-mail duplicado, validação de senhas e provisionamento idempotente.

`npm run check:ui` verifica os templates/CSS com Prettier e faz lint do CSS com Stylelint. Não há TypeScript nesta interface. Para formatar os arquivos da interface, use `npm run format:ui`.
