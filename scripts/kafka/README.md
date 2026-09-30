# Тестовое объявление через Kafka

[send_test_listing.py](send_test_listing.py) публикует **одну запись** в Kafka.
Работающий Telegram-consumer получает её и отправляет уведомление во все настроенные чаты.
Скрипт не запускает consumer и не пересобирает приложение.

Команды ниже рассчитаны на текущие контейнеры на этом Mac. Выполняй их из корня
репозитория `telegram`, где лежит `gradlew`; можно запускать блоки кнопкой Play.
Нужны Docker Desktop и Python 3 без дополнительных пакетов.

## 1. Подготовить нужную версию приложения

Если менялись Kotlin-код или ресурсы, сначала выполни
[сборку образа и пересоздание consumer](../../README.md#после-изменений-в-коде).
Если менялся только `.env`, пересоздай контейнер с имеющимся образом.
`docker start` и `docker restart` не подхватывают изменения кода и `.env`.

Если изменений нет, запусти существующие контейнеры:

```bash
docker start berlin-flat-watcher-kafka-1 berlin-flat-telegram-main
```

Подожди около 10 секунд и проверь запуск:

```bash
docker logs --tail 80 berlin-flat-telegram-main
```

Для этого окружения `compose.test.yaml` запускать не нужно.

## 2. Подготовить и проверить объявление

Отредактируй [test-listing.json](test-listing.json).
В образце вымышленное объявление с реальным адресом для геокодирования.
Посмотреть запись без отправки:

```bash
python3 scripts/kafka/send_test_listing.py --dry-run
```

Скрипт проверяет, что JSON — объект с целочисленным `version: 2`
и непустыми строками `source` и `id`. Полный [контракт v2](../../docs/listing-v2.md)
проверяется приложением. Kafka-ключ `[source,id]` формируется автоматически.

## 3. Отправить в Kafka

**Каждое нажатие Play отправляет новую запись**, даже если `id` не изменился.
Consumer отправляет уведомление во все чаты из своего окружения.

```bash
python3 scripts/kafka/send_test_listing.py --container berlin-flat-watcher-kafka-1 --bootstrap-server localhost:9092 --topic berlin-flat-listings-v1
```

Скрипт вызывает `kafka-console-producer.sh` внутри указанного контейнера через
`docker exec`. Поэтому адрес брокера должен быть доступен из контейнера Kafka.
`Sent one listing` подтверждает приём Kafka, а не доставку в Telegram.
Генерация карты и доставка могут занять около 30 секунд.

Изменения JSON и Python-скрипта используются при следующем запуске;
пересобирать consumer для них не нужно.

## 4. Если уведомления нет

Проверь логи:

```bash
docker logs --tail 80 berlin-flat-telegram-main
```

Проверь отставание consumer:

```bash
docker exec berlin-flat-watcher-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group berlin-flat-telegram-v1 --describe
```

`LAG = 0` означает отсутствие отставания по сохранённым offsets, но запись могла быть
пропущена из-за ошибки. Подробная [диагностика](../../README.md#диагностика-и-остановка)
находится в основном README.

## Параметры скрипта

```bash
python3 scripts/kafka/send_test_listing.py --help
```

| Параметр | Назначение / значение по умолчанию |
| --- | --- |
| `--file` | JSON-файл; по умолчанию `test-listing.json` рядом со скриптом |
| `--container` | Имя существующего контейнера Kafka; при указании Compose не используется |
| `--bootstrap-server` | Адрес брокера из контейнера; из окружения `KAFKA_BOOTSTRAP_SERVERS` или `kafka:19092` |
| `--topic` | Топик; из окружения `KAFKA_TOPIC` или `berlin-flat-listings-v1` |
| `--dry-run` | Напечатать ключ и JSON без отправки и без обращения к Docker |
| `--compose-file` | Без `--container`: Compose-файл, по умолчанию корневой `compose.test.yaml` |
| `--service` | Без `--container`: сервис Compose, по умолчанию `kafka` |
| `--project-name` | Без `--container`: необязательное имя проекта Compose |

Явно переданные параметры имеют приоритет над переменными окружения.
Сам скрипт `.env` не читает. Вариант с Compose использует уже запущенный сервис;
создание Kafka в обязанности скрипта не входит.

## После изменений в коде

Все команды обновления находятся в одном месте:
[основной README → После изменений в коде](../../README.md#после-изменений-в-коде).
