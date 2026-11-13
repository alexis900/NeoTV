# NeoTV

NeoTV is a small native home screen for Android TV, built for sets with 1 GB of RAM. It is called
**Home** on the TV and ships as `com.gathin.tvhome`: plain Java, no blur, no video, no background
services.

Its one shelf is Continue watching, read from a Jellyfin server and opened in whichever Jellyfin
client is installed. The dock and the hidden apps come from a theme, so the same build can move to
another TV without being rebuilt.

## Continue watching

`continue_watching` is on by default. The row is read from a Jellyfin server with three keys, all set
in `config.xml` or, better, in a theme's `config.json`:

- `jellyfin_url`: the server, for example `http://192.168.1.50:8096`.
- `jellyfin_api_key`: a key from Jellyfin's Dashboard → API Keys → +. It travels in the
  `Authorization` header (and `X-Emby-Token`), never in a URL or a log line.
- `jellyfin_user`: the account to read, by name; empty takes the server's first user.

Keep the key out of the APK and out of the repository: the live theme on the author's machine sits
in `~/.cleanyourtv/theme-jellyfin/config.json` with mode 600 and is pushed with
`tools/theme.sh`. A key pasted into `config.xml` is compiled into the build. Either way it can be
revoked in Jellyfin whenever you like. With `jellyfin_url` or `jellyfin_api_key` empty the row stays
hidden.

The remote's **Menu** key cycles the list the row reads: Continue watching (`/Items/Resume`),
Recently added (`/Items/Latest`) and Next up (`/Shows/NextUp`). It works on Jellyfin's own dock tile
(instead of the app menu) and on a card inside the row, and the choice is remembered across
restarts. The same selector works with the D-pad alone: **Left** on the row's first card goes to the
previous list and **Right** on its last card to the next, and the caption shows `‹ … ›` as the hint.
An empty list steps to the next one in the direction moved, so Next up stays out of the way until a
series has an unwatched episode.

The row appears only while Jellyfin's tile has focus and hides again when the owner moves to another
app, so the shelf is not offered over Netflix or YouTube. While it is on screen it takes the space
of the dock's big title, which makes the cards about 1.7 times larger than a plain row's. It shows
the ten newest items under 95%, one card per show, with the series' poster for an episode.

A card opens the item in the installed client: Jellyfin for Android TV takes the id in
`StartupActivity`'s `ItemId` extra and Wholphin in `wholphin://view?itemId=`; Jellyfin for Android
and Findroid have no such entry point, so their cards open the app. `http://` needs the cleartext
flag NeoTV sets; `https://` works when the TV trusts the certificate.

At boot the TV can reach Home before its Wi-Fi or before the Jellyfin server is ready. A failed read
is retried after 3, 6, 12, 24 and 48 seconds, and again as soon as the network comes up or Jellyfin's
tile regains focus, so the first screen of the day does not need an app switch to show the row.

## Dock and hidden apps

The dock holds Jellyfin, YouTube, Atresplayer and Tivify. Netflix, Prime Video, the Xiaomi
preloaded apps (PatchWall, Gallery, MiTV Plus, Dream and SmartShare), YouTube Music and a
button-mapper app are hidden. This is theme configuration, not a change to the launcher's defaults.

## Permissions

The manifest asks for three permissions, and the installer grants the third only when asked:

| Permission | Why |
|---|---|
| `INTERNET` | The Jellyfin row and its posters. |
| `ACCESS_NETWORK_STATE` | The network pill's Wi-Fi/Ethernet and online/offline state. |
| `WRITE_SECURE_SETTINGS` | Optional; only granted by the install script with `RESTORE_KEY_SERVICES=1`. |

It does not request `READ_TV_LISTINGS`, `ACCESS_WIFI_STATE`, `ACCESS_FINE_LOCATION` (or the
`ACCESS_COARSE_LOCATION` Android implies with it), the Yandex TV source permission, or the GPS
feature. There are no runtime permissions to accept on the TV. The accepted tradeoff is that the
network pill says "Wi-Fi" instead of the SSID: Android 10 and newer need fine location to read the
network name. The pill still shows Wi-Fi/Ethernet and whether the TV is online.

## Size

The release APK is about 584 KB (598,345 B). `.github/workflows/ci.yml` checks it against 589,824 B
(576 KB) and will fail: raise that limit in the workflow, or build locally. The Jellyfin row and its
HTTP client are the difference from a plain launcher build.

## Build and install

It needs JDK 17 and Android SDK platform 34, as [the launcher page](docs/launcher.md) says. From a
checkout:

```sh
TV_HOST=192.168.1.50 launcher/build-and-install.sh
```

The script builds a shrunk release, installs it, switches Home and rolls back if Home does not land
on it. Then push the theme that holds the Jellyfin keys and the dock:

```sh
TV_HOST=192.168.1.50 tools/theme.sh <theme folder>
```

`tools/theme.sh --reset` removes the overlay and returns the launcher to the built-in `config.xml`.
With the built-in defaults the Jellyfin row is on but has empty keys, so it stays hidden until a
theme supplies them.

## Tested on

A Xiaomi MiTV-AYFR0 (Android 11, API 30) with Jellyfin for Android TV, Wholphin, YouTube,
Atresplayer and Tivify. The row, the list selector, the D-pad path into the row, the focus rules and
the permission set were checked there. Other models are untested; [the device notes](docs/devices/)
are the place to look before assuming a behaviour transfers.

## Credits

NeoTV's launcher began from [CleanYourTV](https://github.com/Gathin23/cleanyourtv), whose device
notes and troubleshooting pages remain useful background. NeoTV is maintained separately, and its
changes are not offered to that project.
