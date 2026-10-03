# iPodify

A click-wheel iPod that floats over any music app on Android, and controls whatever is playing:
Spotify, YouTube Music, Apple Music, SoundCloud, a podcast app, anything with a media session.

- **Floating bubble.** Drag it around and fling it to an edge, where it can half-hide. Drag it to the bottom of the screen to close it.
- **iPod window.** Tap the bubble to open the iPod.
  - Spin the wheel to move through the queue.
  - Press the centre button to play the selected track.
  - MENU switches between the playlist and Now Playing.
  - ⏮ ⏭ ⏯ do what they say.
  - On Now Playing, the wheel changes the volume. Press the centre button there to scrub through the track.
- **Cover Flow** for the queue, with reflections and the current cover blurred behind it.
- **Resize** the iPod by dragging either bottom corner. Make it small enough and it shrinks back into the bubble.
- **Lock screen.** While the iPod is open, locking the phone shows it full screen. Swipe up from the bottom to unlock as usual. You can switch this off in the app.
- **Haptics** on the wheel, which play even when touch vibration is off.

## How it works

Android gives every music app a *media session*, which is what its notification and lock-screen controls talk to.
iPodify reads these sessions through **notification access**. That is the only way Android lets one app see and control another app's playback.

From the session that is playing, or the one that played most recently, iPodify mirrors:

- the track
- the artwork
- the progress
- the play state
- the queue, when the app shares it

Every control on the iPod is sent back to that app.

How much comes through depends on each app.

- **Every app:** the current track and play/pause/skip/seek.
- **Some apps:** the queue, or the artwork for the tracks in it. When an app doesn't share its queue, Cover Flow shows just the current track.

iPodify doesn't read your notifications, has no account, and sends nothing anywhere.

## Permissions

| Permission | What it's for |
| --- | --- |
| Notification access | To read and control other apps' media sessions. |
| Display over other apps | For the floating bubble and window. |
| Vibrate | For click-wheel haptics. |
| Internet | Only to load artwork that a player shares as a web link. |

On Android 13 and later, a sideloaded app's notification access can be greyed out as a *restricted setting*. To allow it, open iPodify's **App info**, tap **⋮**, choose **Allow restricted settings**, and then allow notification access.

## Building

Open the project in Android Studio, or run:

```
./gradlew installDebug
```

The minimum Android version is 8.0 (API 26).

## License

GPL-3.0. See [LICENSE](LICENSE). The iPod interface started out in [BitChord](https://github.com/kushagrasinghx/BitChord), a GPL-3.0 music player.

iPod is a trademark of Apple Inc. iPodify is an independent project and isn't affiliated with or endorsed by Apple.
