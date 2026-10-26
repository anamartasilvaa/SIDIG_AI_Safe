# Airports & Routes Service

## Gerar o certificado HTTPS

A partir da pasta `airports-routes-service`, execute:

```powershell
keytool -genkeypair -alias aisafe-key -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore src/main/resources/keystore.p12 -storepass password -keypass password -validity 3650 -dname "CN=localhost, OU=AiSafe, O=ISEP, L=Porto, ST=Porto, C=PT" -ext "SAN=dns:localhost,ip:127.0.0.1"
```

O certificado é autoassinado para desenvolvimento local. O ficheiro `keystore.p12` é ignorado pelo Git.

## Executar as três instâncias

Abra três terminais na pasta do serviço:

```powershell
$env:KEYSTORE_PASSWORD="password"
$env:JWT_SECRET="change-this-development-secret-key-with-at-least-32-bytes"
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=inst1
```

```powershell
$env:KEYSTORE_PASSWORD="password"
$env:JWT_SECRET="change-this-development-secret-key-with-at-least-32-bytes"
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=inst2
```

```powershell
$env:KEYSTORE_PASSWORD="password"
$env:JWT_SECRET="change-this-development-secret-key-with-at-least-32-bytes"
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=inst3
```

As instâncias usam as portas 8081, 8082 e 8083, respetivamente. Os perfis configuram os peers HTTPS de cada instância.

## Variáveis de ambiente

- `KEYSTORE_PASSWORD`: password do keystore PKCS12 usado pelo servidor e pelo cliente HTTPS.
- `JWT_SECRET`: segredo partilhado para validar e gerar tokens JWT HS256; deve ter pelo menos 32 bytes UTF-8.

Ambas têm defaults apenas para desenvolvimento local. Em ambientes reais, defina sempre as variáveis de ambiente e não reutilize os valores de exemplo.

## Limitações conhecidas

- O upload de imagem de um aeroporto não é replicado entre as instâncias.
- A base de dados H2 é em memória e perde os dados quando a instância reinicia.
- A outbox de replicação é persistida em H2 em memória e perde as tarefas pendentes quando a instância reinicia.
