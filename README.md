# OddScanner MVP

## Запуск приложения

1. Убедитесь, что Docker и Docker Compose установлены.
2. Запустите PostgreSQL с помощью команды:
   ```bash
   docker-compose up -d

## Stack
Java 21, Spring Boot 3.5, jOOQ, PostgreSQL 16, Playwright.

## Setup
1. Run DB: `docker-compose up -d`
2. Install Playwright browsers: 
   `mvn exec:java -Dexec.mainClass="com.microsoft.playwright.CLI" -Dexec.args="install"`
3. Build & Run: `mvn spring-boot:run`

## API
Swagger UI: http://localhost:8080/swagger-ui.html



markdown
12345678
# OddScanner MVP## Запуск приложения1. Убедитесь, что Docker и Docker Compose установлены.2. Запустите PostgreSQL с помощью команды:   ```bash   docker-compose up -d
Дождитесь, пока PostgreSQL запустится и Liquibase выполнит миграции.
Запустите приложение Spring Boot:
Через IDE: запустите OddScannerApplication.main().
Через Maven: mvn spring-boot:run.
Приложение будет доступно на http://localhost:8080.
API
API документировано с помощью Swagger UI. После запуска приложения документация будет доступна по адресу:
http://localhost:8080/swagger-ui.html
Доступные эндпоинты
GET /api/v1/arbs - Получить все активные арбитражные возможности.
Архитектура


---

### **Что мы сделали:**

1.  **Добавили зависимость Springdoc.**
2.  **Создали `ArbController`,** который предоставляет эндпоинт `GET /api/v1/arbs` для получения активных вилок.
3.  **Добавили аннотации Springdoc** (`@Tag`, `@Operation`, `@ApiResponse`) для генерации документации.
4.  **Обновили `README.md`,** добавив инструкции по запуску и использованию API.

Это завершает **Шаг 10** и, соответственно, весь запланированный **MVP проекта OddScanner**.

Поздравляю! 🎉 Ты создал функциональный MVP системы для поиска арбитражных ситуаций на коэффициентах букмекеров.