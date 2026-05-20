# WIP: Переход на транзитивные зависимости (ветка `refactor/transitive-deps`)

## Цель

Убрать паттерн `<exclusion><groupId>*</groupId><artifactId>*</artifactId></exclusion>` из `Nbm/pom.xml` —
он блокирует все транзитивные зависимости, что приводит к хрупкости: каждая новая нужная библиотека
должна быть прописана вручную. Вместо этого использовать транзитивные зависимости там, где это возможно,
и целевые эксклюзии (только конкретные конфликты) — там, где нет.

## Что уже сделано

### Bump версии
- `0.8.6-SNAPSHOT` → `0.8.7-SNAPSHOT` во всех модулях.

### Удалены FE10 зависимости
Из `Nbm/pom.xml` полностью удалены (плагин K2-only с C10, классы не используются в `src/main/java`):
- `base-fe10-analysis:231-1.9.20-506-IJ8109.175`
- `base-fe10-code-insight:231-1.9.20-506-IJ8109.175`
- `base-fe10-obsolete-compat:231-1.9.20-506-IJ8109.175`
- `base-psi:231-1.9.20-506-IJ8109.175`
- `analysis-api-fe10-for-ide:2.3.21`

Из `pom.xml` (root `dependencyManagement`) удалены те же артефакты.

Удалён репозиторий `jetbrains-kotlin-ki` (`packages.jetbrains.team/maven/p/ki/maven/`) — он был нужен только для тонких `base-fe10-*` JAR-ов.

### Убраны `*:*` эксклюзии с `-for-ide` артефактов
В `Nbm/pom.xml` убраны `<exclusion>*:*</exclusion>` со следующих зависимостей:
- `analysis-api-for-ide`
- `analysis-api-standalone-for-ide`
- `analysis-api-k2-for-ide`
- `analysis-api-impl-base-for-ide`
- `analysis-api-platform-interface-for-ide`
- `low-level-api-fir-for-ide`
- `symbol-light-classes-for-ide`
- `kotlin-compiler-common-for-ide`
- `kotlin-compiler-ir-for-ide`
- `formatter`

## Текущая проблема (не решена)

При сборке Maven требует транзитивные нон-for-ide артефакты, которые эти `-for-ide` POM-ы объявляют
как прямые зависимости:

```
org.jetbrains.kotlin:analysis-api:2.3.21
org.jetbrains.kotlin:analysis-api-standalone-base:2.3.21
org.jetbrains.kotlin:analysis-api-fir-standalone-base:2.3.21
org.jetbrains.kotlin:analysis-api-standalone:2.3.21
org.jetbrains.kotlin:analysis-api-fir:2.3.21
org.jetbrains.kotlin:analysis-api-impl-base:2.3.21
org.jetbrains.kotlin:analysis-internal-utils:2.3.21
org.jetbrains.kotlin:analysis-api-platform-interface:2.3.21
org.jetbrains.kotlin:low-level-api-fir:2.3.21
org.jetbrains.kotlin:symbol-light-classes:2.3.21
```

Также из `formatter` POM-а:
```
org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.20-506  (runtime)
org.jetbrains.kotlin:base-frontend-agnostic:231-1.9.20-506-IJ8109.175  (runtime)
```

Эти нон-for-ide артефакты **не опубликованы публично** — они JetBrains-internal.
Проверены репозитории: Maven Central, jetbrains-intellij-releases, kotlin-ide Space,
packages.jetbrains.team/maven/p/ki/maven, packages.jetbrains.team/maven/p/kotlin/kotlin-ide-plugin-dependencies —
везде 404.

### Предположение пользователя
Артефакты должны быть доступны для скачивания через curl. Нужно посмотреть логи CI-сборки
https://github.com/nbplugins/NetbeansPluginKotlin/actions/runs/26172414487 — в них видно
откуда именно Maven скачивал эти артефакты на CI.

## Следующие шаги

1. **Изучить логи CI** (ссылка выше): найти строки `Downloading from X: https://...`
   для нон-for-ide артефактов 2.3.21 и `kotlin-stdlib-jdk8:1.9.20-506`.
   Это покажет правильный репозиторий.

2. **Скачать нон-for-ide JAR+POM** через curl+SOCKS5 proxy (`router.oleghome:11337`)
   в `~/.m2/repository/org/jetbrains/kotlin/<artifact>/2.3.21/`.
   Шаблон curl из CLAUDE.md:
   ```bash
   curl -L --proxy socks5://router.oleghome:11337 \
     "https://<repo-url>/org/jetbrains/kotlin/<artifact>/2.3.21/<artifact>-2.3.21.jar" \
     -o ~/.m2/repository/org/jetbrains/kotlin/<artifact>/2.3.21/<artifact>-2.3.21.jar
   # то же для .pom
   ```

3. **Запустить полную сборку** (сначала через реактор — нужны bundled-jars):
   ```bash
   mvn package -DskipTests
   mvn test -pl Nbm
   ```

4. **Разобраться с `formatter`**: его POM объявляет `kotlin-stdlib-jdk8:1.9.20-506` и
   `base-frontend-agnostic:231-*`. Варианты:
   - Скачать их тоже, если найдены в логах CI
   - Или переустановить `formatter` с минимальным POM (без deps) через `mvn install:install-file`

5. **После зелёной сборки**: убрать из `Nbm/pom.xml` явные зависимости, которые теперь
   приходят транзитивно:
   - `kotlinx-collections-immutable-jvm`
   - `caffeine`
   - `kotlinx-serialization-core-jvm`
   - `intellij-deps-fastutil`
   - `asm-tree`, `asm-util`
   - `org.jetbrains:annotations`

## Локальный контекст

- Ветка: `refactor/transitive-deps`
- Maven mirror в `~/.m2/settings.xml` блокирует все внешние репозитории через
  корпоративный Artifactory (`openintegration.inc`), который не имеет JetBrains-internal артефактов.
  Поэтому все JetBrains артефакты скачиваются вручную через curl+SOCKS5.
- SOCKS5 proxy: `router.oleghome:11337`
- Локальный Maven repo: `~/.m2/repository/`
