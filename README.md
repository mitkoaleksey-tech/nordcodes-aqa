# NordCodes AQA

Тесты API на Java 17, JUnit 5, WireMock и Allure.
Проверяются вход, действие, выход, валидация параметров, API-ключ и ошибки внешнего сервиса.

Для запуска нужны JDK 17 и Maven. Положите `internal-0.0.1-SNAPSHOT.jar` из задания рядом с `pom.xml`.

```shell
mvn clean test
```

Приложение и WireMock запускаются и останавливаются автоматически. Лог приложения — `target/app.log`.

Открыть отчёт после запуска:

```shell
mvn allure:serve
```

Результаты проверки — в [RESULTS.md](RESULTS.md).
