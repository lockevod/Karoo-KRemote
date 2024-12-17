# KRemote  Extension

> [!WARNING]  
> This app is currently in beta stage and its main features might not work at all.


KRemote allows to use a Remote with Karoo and perform some actions with it (swipe screens, etc.)

## Requirements
- Ant+ Remote (tested with Garmin Remote but should work with others Ant+ remotes)
- Karoo (tested on last Karoo ) with version 1.524.2003 or later

## Installation

You can sideload the app using the following steps for Karoo 2

1. Download the APK from the releases .
2. Prepare your Karoo for sideloading by following the [step-by-step guide](https://www.dcrainmaker.com/2021/02/how-to-sideload-android-apps-on-your-hammerhead-karoo-1-karoo-2.html) by DC Rainmaker.
3. Install the app using the command `adb install app-release.apk`.


If you've Karoo 3 and v > 1.527 you can sideload the app using the following steps:

1. Link with apk (releases link) from your mobile ( https://github.com/lockevod/Karoo_KRemote_Extension/releases/download/latest/kremote.apk)
2. Share with Hammerhead companion app
3. Install the app using the Hammerhead companion app.

## Instructions

- Press any button in the Remote to connect, you only need to open this app the first time. 
- Kremote auto-starts when Karoo starts, then push a button in GRemote and wait until the Remote flashing green several times.

## Configuration
Apps is configured by default:

- Left Button =>> Back Button (several actions, depends on the current Karoo screen.. back, zoom level etc).
- Right Button ==> Next screen or item. 
- Upper Button ==> Right bottom button.

You can change the default configuration in the app and select different actions for each button. There is an option to use the remote in all screens or only when you're riding (show map only when riding)

## Know Bugs
- Sometimes app isn't working fine. You've to stop the app and re-start again (you can do this from karoo menu).

## Credits

- Made possible by the generous usage terms of timklge (apache 2.0). He has a great development and I use part of his code to create this extension.
  https://github.com/timklge?tab=repositories

## Links

[karoo-ext source](https://github.com/hammerheadnav/karoo-ext)
