# Berlin flat Telegram

Отдельный отправитель уведомлений для
[berlin-flat-watcher](https://github.com/Aksumar/berlin-flat-watcher).
Python 3.12, только стандартная библиотека, установка зависимостей не нужна.

## Настройка

Settings → Secrets and variables → Actions: добавьте **в этот репозиторий**:

- `TELEGRAM_BOT_TOKEN`: токен от @BotFather. Отправьте своему боту `/start`.
- `TELEGRAM_CHAT_ID`: ID чата, например `message.chat.id` из Telegram Bot API getUpdates.
- `WATCHER_READ_TOKEN`: GitHub fine-grained personal access token. Resource owner: Aksumar;
  Repository access: Only select repositories → berlin-flat-watcher;
  Repository permissions: Contents → Read-only (Metadata Read-only добавится автоматически).
  Выберите срок действия и обновляйте секрет до истечения токена.

Создание токена: https://github.com/settings/personal-access-tokens/new
Секреты: https://github.com/Aksumar/berlin-flat-telegram/settings/secrets/actions

Обычный GITHUB_TOKEN ограничен текущим репозиторием и не читает другой приватный репозиторий.
Токен чтения не должен иметь доступа на запись. Реальные токены не коммитить и не вставлять в issues.
Можно использовать `gh secret set NAME --repo Aksumar/berlin-flat-telegram` с интерактивным вводом.
Старые Telegram-секреты в watcher больше не используются.

После настройки: Actions → Telegram delivery → Run workflow, main.
Без любого из трёх секретов отправка пропускается с предупреждением; очередь не теряется.

## Доставка и состояние

Каждый час в :27 UTC и вручную. Парсер обычно работает в :17; независимые расписания могут
задерживаться, тогда доставка произойдёт в следующем цикле. Первый запуск отправителя отправит
все накопившиеся новые события; первоначальный baseline парсер никогда не кладёт в очередь.

Читает `seen.json` из ветки `watcher-state` репозитория парсеров.
Доставленные ID хранит в `sent.json` отдельной ветки `delivery-state` этого репозитория.
Ветка создаётся автоматически, коммиты только при изменениях. Очередь не удаляет.
Состояние записывается после каждого подтверждённого сообщения; при сбое следующий запуск
повторяет только недоставленные события. Ошибки не выводят токены в лог.

Семантика at-least-once: сбой между Telegram и сохранением на GitHub/неоднозначный тайм-аут
может дать дубль. При ошибке push восстановите recovery artifact (30 дней хранения)
в `delivery-state` до следующего запуска. Не удаляйте ветку доставки — это вызовет повтор
всей накопленной очереди. Не редактируйте состояние одновременно с workflow.
Workflow имеет contents:write только в этом репозитории. Для источника используется read-only token.

## Проверка

```sh
python -m unittest discover -s tests -v
python -m notifier.main --queue /path/to/seen.json --state state/sent.json
```

Локально Telegram-секреты передаются через окружение. Не запускайте параллельные копии
с одним файлом sent.json. Тесты не отправляют сообщения.
