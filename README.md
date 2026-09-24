# Berlin flat Telegram — Kafka → Telegram

Читает новые объявления парсеров из Kafka. Межрепозиторное чтение файлов удалено;
`WATCHER_READ_TOKEN` больше не требуется.

## Отправка

Consumer group по умолчанию `berlin-flat-telegram-v1` (переменная KAFKA_GROUP_ID).
Автокоммит и автоматическое сохранение offsets отключены. Порядок: проверить событие →
отправить Telegram → сохранить ID в sent.json → синхронно подтвердить offset.
При ошибке Telegram/невалидном событии offset не подтверждается, процесс завершится с ошибкой.
При повторе ID из sent.json сообщение не отправляется, но offset подтверждается.
Ветка delivery-state сохраняется между Actions-запусками, Docker использует volume.
Не удаляйте sent.json и не запускайте несколько процессов с общим JSON-файлом.
Эта реализация рассчитана на один экземпляр потребителя, а не горизонтальное масштабирование.
Сбой после sendMessage до сохранения ID может привести к дублю.

## Настройка Telegram

Actions Secrets этого репозитория:
- TELEGRAM_BOT_TOKEN — токен @BotFather;
- TELEGRAM_CHAT_ID — ID чата, которому бот может писать (сначала отправьте боту /start).

Также настройте Kafka ниже. Секреты не помещайте в код и историю команд.
Если адрес Kafka или Telegram-секреты отсутствуют, workflow пропускает доставку с предупреждением.
После настройки: Actions → Telegram delivery → Run workflow.

В Actions потребитель работает 120 секунд каждый час в :27 UTC; сообщения между запусками
хранятся в Kafka. Расписание может задерживаться. Для постоянного чтения используйте Docker
или `python -m notifier.main --duration 0`. Только один production-способ запуска одновременно.
Для локального полного стека см. compose.yaml и README в соседнем berlin-flat-watcher.

```sh
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python -m unittest discover -s tests -v
# Экспортируйте переменные окружения из .env.example; Python не читает .env автоматически.
python -m notifier.main --duration 120 --state state/sent.json
```

Dockerfile запускает один ограниченный проход по умолчанию; полный Compose задаёт duration=0.
compose.test.yaml содержит только Kafka для интеграционных тестов.
При обновлении сохраните delivery-state/sent.json. Старые события из outbox парсер мигрирует
в Kafka, старые sent ID предотвращают повторную отправку. Первый consumer group читает с earliest.
При ошибке сохранения на GitHub восстановите recovery artifact; его срок хранения 30 дней.
CI проверяет реальные offsets: ошибка отправки не подтверждается, успешная — подтверждается.
## Kafka: внешний кластер

Создайте топик `berlin-flat-listings-v1` (1 partition достаточно). Для production используйте
репликацию 3/min.insync.replicas=2, если кластер это поддерживает. Retention должен превышать
максимальное время простоя потребителя; в локальном примере это 30 дней. Истёкшие по retention
события восстановить из Kafka нельзя. Не меняйте имя consumer group при обычном обновлении.

В каждом репозитории задайте Actions Secrets:
- `KAFKA_BOOTSTRAP_SERVERS` — broker addresses через запятую;
- `KAFKA_SASL_USERNAME`, `KAFKA_SASL_PASSWORD` — отдельные credentials каждого сервиса.

Actions Variables (значения по умолчанию):
- `KAFKA_SECURITY_PROTOCOL=SASL_SSL` (локально явно PLAINTEXT);
- `KAFKA_SASL_MECHANISM=PLAIN` (также SCRAM-SHA-256/SCRAM-SHA-512);
- `KAFKA_TOPIC=berlin-flat-listings-v1`.

Выдайте парсеру право записи в топик и необходимые кластеру права idempotent producer;
Telegram-сервису — чтение топика и доступ к group `berlin-flat-telegram-v1`.
Сервисы используют TLS с проверкой сертификата. При локальном запуске/в контейнере можно
задать `KAFKA_SSL_CA_LOCATION` для собственного CA (смонтируйте файл).
Broker должен быть доступен с runners GitHub Actions; localhost Docker Compose с GitHub недоступен.
Облачный кластер этим проектом не создаётся.

## Контракт события

Kafka key: UTF-8 JSON-массив `[source,id]` без пробелов, например `["allod","650.65001.2.1012"]`.
Значение — UTF-8 JSON:

```json
{"version":1,"source":"allod","id":"650.65001.2.1012","title":"Wohnung","url":"https://www.allod.de/angebote","details":"Адрес и параметры"}
```

`source` — allod/rbb/berlinhaus/inberlinwohnen; id, title, url, details — строки. RBB использует URL как ID.
Несовместимое событие останавливает потребителя без подтверждения offset: исправьте причину
перед перезапуском. Автоматического пропуска и dead-letter topic пока нет.

Доставка at-least-once: авария после внешнего эффекта, но до сохранения состояния может дать дубль.
Telegram API не поддерживает ключ идемпотентности; абсолютная гарантия exactly-once отсутствует.
