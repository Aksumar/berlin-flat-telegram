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

Если менялся код приложения или CSV из `src/main/resources`, сначала выполни
раздел «После изменений в коде» ниже: `docker start` запускает прежнюю сборку.

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

## После изменений в коде

Изменения Kotlin-кода и ресурсов, включая `berlin_u_s_stations_colors.csv`,
попадут в приложение после сборки нового Docker-образа и пересоздания контейнера.
`docker start` и `docker restart` сами код не пересобирают.
Запускай блоки по порядку из корня репозитория `telegram`.

**1. Собрать образ из текущих локальных файлов.** Коммит и push не нужны.
Docker сам соберёт JAR; отдельно запускать `bootJar` не требуется.

```bash
docker build -t berlin-flat-telegram:local .
```

Продолжай только после успешной сборки. При ошибке старый consumer продолжит работать.

**2. Пересоздать Telegram-consumer с новым образом.** Нужен файл `.env`
с токеном бота и ID чатов; для карт также нужен `GEOAPIFY_API_KEY`.

```bash
docker start berlin-flat-watcher-kafka-1 &&
docker stop --time 60 berlin-flat-telegram-main &&
docker rm berlin-flat-telegram-main &&
docker run -d --name berlin-flat-telegram-main \
  --restart unless-stopped \
  --stop-timeout 60 \
  --network berlin-flat-watcher_default \
  --env-file .env \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka:19092 \
  -e KAFKA_TOPIC=berlin-flat-listings-v1 \
  -e KAFKA_GROUP_ID=berlin-flat-telegram-v1 \
  berlin-flat-telegram:local
```

Это адрес Kafka внутри текущей Docker-сети; скрипт отправки через `docker exec`
по-прежнему использует `localhost:9092`. Consumer group сохранена, поэтому приложение
продолжит чтение с сохранённых в Kafka offsets. Контейнер Kafka и его данные остаются.
Новый consumer сразу начнёт обрабатывать накопившиеся объявления.

**3. Проверить запуск.** Подожди около 10 секунд и посмотри логи:

```bash
docker logs --tail 80 berlin-flat-telegram-main
```

Затем отправь тестовое объявление командой из шага 2 в начале инструкции.

Если менялся только `.env`, достаточно пересоздать контейнер с уже собранным
образом. Изменения `test-listing.json` или `send_test_listing.py` используются
при следующем запуске Python-скрипта; пересобирать consumer для них не нужно.

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
