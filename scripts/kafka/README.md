# Отправить тестовое сообщение кнопкой Play

Команды готовы для текущего проекта на этом Mac. Ничего заменять не нужно:
запускай блоки кнопкой Play из корня репозитория `telegram` (папки с `gradlew`).
Нужны запущенный Docker Desktop и Python 3; Python-пакеты устанавливать не требуется.

## 1. Запустить Kafka и Telegram-consumer

```bash
docker start berlin-flat-watcher-kafka-1 berlin-flat-telegram-main
```

Если контейнеры уже работают, команда оставит их работающими.
После запуска подожди около 10 секунд перед отправкой.
Эти контейнеры уже созданы; `compose.test.yaml` для этого сценария запускать не нужно.

## 2. Отправить тестовое объявление

```bash
python3 scripts/kafka/send_test_listing.py --container berlin-flat-watcher-kafka-1 --bootstrap-server localhost:9092 --topic berlin-flat-listings-v1
```

Каждое нажатие Play публикует **одно новое сообщение**, даже если `id` остался прежним.
Consumer отправляет его во все чаты, настроенные при создании контейнера.
На генерацию карты и доставку может потребоваться около 30 секунд.
`Sent one listing` подтверждает приём Kafka, а не доставку в Telegram.

Содержимое объявления редактируется в [test-listing.json](test-listing.json).
Это вымышленное объявление с реальным адресом для проверки геокодирования.
Ключ `[source,id]` скрипт формирует автоматически из файла.

## Посмотреть сообщение без отправки

```bash
python3 scripts/kafka/send_test_listing.py --dry-run
```

## Если сообщение не пришло

Посмотреть состояние контейнеров:

```bash
docker ps -a --filter name=berlin-flat --format 'table {{.Names}}\t{{.Status}}'
```

Посмотреть последние логи Telegram-consumer:

```bash
docker logs --tail 80 berlin-flat-telegram-main
```

Проверить очередь:

```bash
docker exec berlin-flat-watcher-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group berlin-flat-telegram-v1 --describe
```

`LAG = 0` означает, что consumer обработал все записи; если уведомления нет,
проверь логи на ошибки или пропущенные записи.

## Настройки

В командах выше явно указаны текущие контейнер, брокер и топик, поэтому переменные
окружения терминала их не переопределяют. Скрипт не загружает `.env` автоматически.

Контейнер `berlin-flat-telegram-main` использует креды из `.env`, сохранённые при
его создании. После изменения `.env` контейнер нужно пересоздать: `docker start`
и `docker restart` не обновляют его окружение. Для карты необходим
`GEOAPIFY_API_KEY`; без ключа или при ошибке провайдера придёт только текст.

Старый контейнер `berlin-flat-watcher-telegram-1` оставлен остановленным:
он содержит прежний Python-consumer. Для текущего приложения используется
`berlin-flat-telegram-main`.
