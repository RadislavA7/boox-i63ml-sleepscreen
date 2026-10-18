# Сборка из исходников

Проект собирается без Android Studio и Gradle: `build.ps1` (Windows PowerShell 5.1) вызывает `aapt`, ECJ, `dx`, `zipalign` и `apksigner`. Готовый APK лежит в [Releases](../../releases); собирать самим нужно, только если вы хотите менять код.

## Что понадобится

| Что | Версия, с которой проверено | Где взять |
|---|---|---|
| Java (JRE или JDK) с `keytool` | 8 | любая сборка Java 8 или новее; `java` в PATH или параметр `-Java` |
| ECJ | 3.26.0 | https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.26.0/ecj-3.26.0.jar (SHA-1 `4837be609a3368a0f7e7cf0dc1bdbc7fe94993de`) |
| dx | 1.7 | https://repo1.maven.org/maven2/com/google/android/tools/dx/1.7/dx-1.7.jar (SHA-1 `70eab4a00d9f7ca8849e960a87f5b6736aa4ec11`) |
| android.jar API 9 | 2.3.1 | https://repo1.maven.org/maven2/com/google/android/android/2.3.1/android-2.3.1.jar (SHA-1 `839eaa713b742baed139c5f30c8b479cff48d3eb`) |
| Android SDK build-tools (`aapt.exe`, `zipalign.exe`, `lib\apksigner.jar`) | 36.1.0 | https://dl.google.com/android/repository/build-tools_r36.1_windows.zip |

Положите три `.jar` и распакованные build-tools в папку `tools\` рядом с `build.ps1` (подпапки не важны: файлы ищутся рекурсивно) или укажите свои папки параметрами `-Tools` и `-BuildTools`.

## Сборка

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
```

Результат: `out\SleepScreen.apk`. При первом запуске создаётся самоподписанный ключ `keystore\sleepscreen.jks`. **Сохраните его**: обновить установленное приложение можно только APK, подписанным тем же ключом (иначе придётся сначала удалить приложение). Подпись схемы v1, этого достаточно для Android 2.3.

Установка на подключённую книгу сразу после сборки: `build.ps1 -Install` (нужен `adb` в PATH или параметр `-Adb`).

## Проверки на компьютере

Разбор названий и обложек не зависит от Android и проверяется на ПК, например:

```powershell
# скомпилировать нужные классы вместе с тестом и запустить на папке с книгами
java -jar ecj-3.26.0.jar -source 1.6 -target 1.6 -encoding UTF-8 -d out-test ^
  app\src\ru\local\sleepscreen\BookMeta.java app\src\ru\local\sleepscreen\BookCovers.java ^
  app\src\ru\local\sleepscreen\Base64Lite.java app\test\MetaTest.java
java -cp out-test MetaTest D:\path\to\books
```

`app\test\` содержит также `CoverTest` (извлечение обложек) и `ProcTest` (обработка картинки).

## Структура

```
app/AndroidManifest.xml          манифест (разрешения: запись на карту, READ_LOGS и SYSTEM_ALERT_WINDOW для очистки экрана)
app/res/                         разметка, строки, значок
app/src/ru/local/sleepscreen/    исходники: MainActivity (экран), CoverService (служба), BookMeta/BookCovers (книги),
                                 ImageProc/ImageLoader/TitleCard (картинки), AdvtLink/RootShell (ссылка через su),
                                 EbcReset/PowerKeyWatcher/ScreenRefresher (очистка e-ink)
app/test/                        проверки на ПК
build.ps1                        сборка
docs/                            описание устройства механизма, скриншоты
```
