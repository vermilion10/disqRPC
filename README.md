# disqRPC

Show what you're playing on your Android phone as a Discord Rich Presence.

disqRPC runs in the background, notices when one of your games is open, and puts it on your Discord profile, the same way desktop Discord shows the game you're playing. Games that Discord already knows are shown under their official Discord application with the official name and icon.

> [!WARNING]
> disqRPC signs in to Discord with your **user token**. Automating a user account is against [Discord's Terms of Service](https://discord.com/terms) and can get the account disabled. Use it at your own risk, and never share your token.

## Features

- **Automatic game detection.** Uses Android's usage statistics to see which app is open and updates your presence on its own.
- **Official game matching.** Games in Discord's [detectable applications](https://discord.com/api/v10/applications/detectable) list (about 1,700 Android packages) appear with their official name and icon, like a game detected by desktop Discord.
- **Games Discord doesn't list.** Link any game to a Discord game by name or application ID to use its official name and icon. Games with no link are shown under your own application with their Google Play icon.
- **One-tap adding.** When you open a new app, a notification asks whether to add it. You can also add games from your installed apps or by package name.
- **Stable elapsed time.** Switching away from a game for less than 30 seconds keeps its timer; leaving for longer clears the presence.
- **Custom status.** Set the activity type, name, details and state (each with an optional link), images with hover text, a timestamp, party size and two buttons, or pick any of the ~25,000 games Discord lists to use its official name and icon. Save statuses as presets and reuse them with one tap. A custom status stays on until you clear it, then game presence takes over again.
- **In-app sign-in.** Log in on Discord's own page inside the app, which keeps only the session token. Pasting a token still works as a fallback.
- **Reliable connection.** Resumes the Discord session after short drops instead of signing in again, waits for the network instead of retrying while offline (VPN-aware), and reconnects after Android restarts the app.
- **Private by default.** Your token is stored encrypted with the Android Keystore. Images from your phone are only uploaded when you turn that on.
- **Material You design.** Dynamic color, light and dark themes, a navigation rail on tablets and foldables, and a themed launcher icon.
- **Log console.** See connection and presence events as they happen, with optional debug detail.

## Requirements

- Android 10 (API 29) or later
- A Discord account and its user token
- A Discord application ID, used for apps that aren't in Discord's game list and for custom statuses

## Installation

Build the APK from source (see [Building](#building)) and install it on your phone, for example with `adb install app/build/outputs/apk/debug/app-debug.apk`.

## Setup

1. **Create a Discord application.** In the [Discord Developer Portal](https://discord.com/developers/applications), create an application and copy its **Application ID**. Its name is what Discord shows for apps that aren't in the game list.
2. **Sign in.** Open **Settings** and tap **Log in with Discord**. You sign in on Discord's own page inside the app, which keeps only the session token, encrypted on the device. If a sign-in method doesn't work there, you can paste a token instead. Set the application ID under **Fallback application** and tap **Save application ID**.
3. **Grant permissions.** The Settings screen shows what is missing:
   - **Notifications**: the connection status and the "Add game?" prompt.
   - **Usage access**: required to detect which game is open.
   - **Unrestricted battery**: keeps the connection alive in the background.
   - **Notification access** (optional): detects games from their notifications when usage access is off.
4. **Connect.** On the **Home** tab, tap **Connect**.

## Usage

### Showing a game

Open a game while connected. The first time, tap **Add** on the "Add to Rich Presence?" notification. From then on, the game appears on your profile whenever it is open and disappears 30 seconds after you leave it.

You can also tap **Add game** on the Home tab and choose from your installed apps or type a package name such as `com.mojang.minecraftpe`.

Tap a game in the list to rename it, set details, state, an image link or the account status to use while playing, or to remove it. The switch turns a game on or off without removing it.

### Games that aren't matched automatically

Some games aren't in Discord's detectable list, or are listed without their Android package. Open the game, tap **Choose** under **Official game**, and search for it by name. Discord has game pages for more games than the list contains; if search doesn't find one, paste its Discord application ID into the search box instead.

A game that isn't linked is shown under your fallback application with its Google Play icon. For games that aren't on Google Play, the installed app's icon is uploaded instead when **Upload images from this phone** is on in Settings.

### Custom status

The **Status** tab sets a presence by hand. Fill in the fields, preview it, and tap **Set status**. **Clear** removes it.

To appear as playing a specific game, tap **Choose** under **Official game** and search Discord's game list. The status is then shown under that game's official Discord application with its icon; details, state and images can still be added.

Discord can only show images that are online. Paste an image link, or pick an image from your phone after turning on **Upload images from this phone** in Settings. Picked images are then uploaded to [catbox.moe](https://catbox.moe), a public file host where anyone with the link can see them.

## How it works

disqRPC keeps one connection to the Discord gateway in a foreground service:

1. It signs in, sends heartbeats, and resumes the same session after a disconnect when Discord allows it.
2. A detector reads Android usage events every few seconds to find the app in the foreground. Overlays drawn on top of a running game, such as floating windows, don't count as leaving it.
3. A presence engine decides what to show (custom status, then the open game, then nothing) and only sends an update when something changes.
4. Discord's detectable applications list is cached on the device and refreshed weekly, so matching a package to its official game, or searching it for a custom status, needs no network request.

## Building

Requirements: JDK 17 and the Android SDK (Android Studio's bundled JDK works).

```bash
git clone https://github.com/vermilion10/disqRPC.git
cd disqRPC
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. Run the unit tests with:

```bash
./gradlew testDebugUnitTest
```

## Tech stack

| Area | Technology |
| --- | --- |
| Language | Kotlin, coroutines |
| UI | Jetpack Compose, Material 3, adaptive navigation suite, Coil |
| Networking | OkHttp (WebSocket and HTTP), Gson streaming parser |
| Storage | Room, EncryptedSharedPreferences |

## Project structure

```
app/src/main/java/com/github/vermilion10/disqrpc/
├── gateway/     Discord gateway session: handshake, heartbeats, resume, reconnect
├── presence/    Presence engine and the game source built on saved games
├── data/
│   ├── catalog/ Discord detectable-applications catalog
│   └── local/   Room database and saved game configs
├── service/     Foreground service, app detector, notification listener
├── ui/          Compose screens, components and theme
└── util/        Credentials, settings, image handling, logging
```

## Permissions

| Permission | Why |
| --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Connect to Discord and notice when the network comes back |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the connection running in the background |
| `POST_NOTIFICATIONS` | Connection status and the add-game prompt |
| `PACKAGE_USAGE_STATS` | Detect which app is open |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Optional detection from game notifications |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Ask to keep running with battery optimization off |

## Disclaimer

disqRPC is an independent project and is not affiliated with or endorsed by Discord Inc. Discord is a trademark of Discord Inc.
