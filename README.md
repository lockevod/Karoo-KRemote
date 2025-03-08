# KRemote  Extension

KRemote allows to use a Remote with Karoo and perform some actions with it (swipe screens, etc.)

<a href="https://www.buymeacoffee.com/enderthor" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/default-orange.png" alt="Buy Me A Coffee" height="41" width="174"></a>

## Requirements
- Ant+ Remote (tested with Garmin Remote but should work with others Ant+ remotes)
- Karoo (tested on last Karoo ) with version 1.524.2003 or later

## Installation

You can sideload the app using the following steps for Karoo 2

1. Download the APK from the releases .
2. Prepare your Karoo for sideloading by following the [step-by-step guide](https://www.dcrainmaker.com/2021/02/how-to-sideload-android-apps-on-your-hammerhead-karoo-1-karoo-2.html) by DC Rainmaker.
3. Install the app using the command `adb install app-release.apk`.


If you've Karoo 3 and v > 1.527 you can sideload the app using the following steps:

1. Link with apk (releases link) from your mobile ( https://github.com/lockevod/Karoo-KRemote/releases/latest/download/kremote.apk )
2. Share with Hammerhead companion app
3. Install the app using the Hammerhead companion app.

**It's mandatory to reset the Karoo after the installation (shutdown and start again).**

## Instructions

- This release adds the possibility to add multiple remotes. 
- It's mandatory to scan your remote first. When you've the remote added (Remotes screen -> Scan -> push over your remote), you can scan for commands from your remote.
- If you have a garmin remote, you don't need to scan/learn commands (if you use other Ant remote it's mandatory)
- Then you need to go to Conf screen, select your remote and map Remotes commands to Karoo actions.
- Kremote auto-starts when Karoo starts, then push a button in remote and wait until the Remote flashing green several times.
- Kremote works (if only when riding option is enabled) only when you're in Ride app. Please if you want kremote works in all screens, uncheck this option. If you detect some lags, sometimes you can uncheck this option and try if it's work better.

## Configuration
You need to configure the remote buttons to perform some actions in Karoo. There is a default configuration if you've a Garmin Remote (or compatible).
You can configure double tap option. This permits to use double press and use more options with the remote (for example, zoom in double tap in left button, etc). Double tap works fine but introduces a delay (it's necessary to catch double press). The default delay is 1200 ms.
If you have karoo > 1.535 you can use showmap option also

## Know Bugs
- Sometimes app isn't working fine, this new release has better support and it's working fine, but you need to know, Karoo function have priority oven kremote actions.
- Sometimes app can't detect the remote, you need to restart the app.. or the Karoo. Restart karoo solve most of the problems (with this app and with anything.. sometimes karoo is slowly if you have several custom apps)

## Credits, license and  privacy

- Made possible by the generous usage terms of timklge (apache 2.0). He has a great development and I use part of his code to create this extension.
  https://github.com/timklge?tab=repositories
- This app use ANT library from ANT+ (please review their copyright and conditions). If you want to compile you need to download antpluginlib_3-9-0.aar  and place in lib directory. You can download from ANT+ repositories
- SRAM and Hammerhead coypyright are describer in Karoo file.
- Credits and copyright. Please respect license and specific parts licencsers (icons, etc). If you use this app you're agree.
- KRemote doesn't save or share any information for it's use, but it use firebase crashlytics service only for crashes in app (and firebase use this crash information). I only use this information to prevent new crashes in the app. Please if you isn't agree with Firebase use (this conditions are in firebase web and can change, please read it), please you cannot use app. If you use it you are agree with all conditions and copyrights.

## Links

[karoo-ext source](https://github.com/hammerheadnav/karoo-ext)
