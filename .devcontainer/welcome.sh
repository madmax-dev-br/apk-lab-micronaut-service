#!/usr/bin/env bash
cat <<'TEXT'
APK Lab Micronaut service
  bash .devcontainer/runner.sh          inicia o runner (porta 8081)
  bash .devcontainer/demo.sh app.apk    sessão + print da tela pronta (arraste o APK para o explorador antes)
  ./gradlew test                        testes
Documentação: https://madmax-dev-br.github.io/apk-lab-micronaut-service/
TEXT
