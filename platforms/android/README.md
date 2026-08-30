# NodeFuel for Android

A Kotlin + Jetpack Compose app - genuinely built and verified with
`./gradlew assembleDebug` (unlike the Windows build, which could only be
validated headlessly), but the compiled APK hasn't been run on a real
device yet.

## Scope of this first pass

- **API key mode works fully** - real `$` balance for Anthropic/OpenAI,
  same REST calls the desktop backend makes.
- **No CodexBar-equivalent exists for Android** - there's no CLI to shell
  out to for real session/weekly usage %. Subscription-only accounts (no
  API key) get the same plain "active" badge the desktop version falls
  back to when CodexBar itself is missing.
- **No Cyber Mode yet** - the desktop version's 3D orb/lightning effect
  would need a from-scratch rewrite against Compose's Canvas API. The ring
  here is the same decorative ~88% sweep the desktop uses for api/
  subscription modes (no live % to draw either way).
- **No background sync yet** - the app only fetches when opened / when you
  tap refresh. A `WorkManager` periodic job (Android caps these at a
  15-minute minimum interval - an OS restriction, not a choice) is a
  natural next step.
- Settings save to `SharedPreferences`, not a JSON file - there's no
  equivalent of the desktop's `~/.config/nodefuel/config.json` on Android.

## Building it yourself

```bash
cd platforms/android
./gradlew assembleDebug
# APK lands at app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

Standard MVVM, from the official `empty-activity` (Compose) template:

```
data/DataRepository.kt        - SharedPreferences config + HTTPS balance fetches
                                 (mirrors backend/main.py's fetch_anthropic_balance/
                                 fetch_openai_balance)
ui/main/MainScreenViewModel.kt - StateFlow wiring
ui/main/MainScreen.kt          - the dashboard + inline settings section
```
