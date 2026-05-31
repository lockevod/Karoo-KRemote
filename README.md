# KRemote Extension

KRemote allows to use a Remote with Karoo and perform some actions with it (swipe screens, Bell, zoom in, zoom out, etc.)

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

## Important Notice

⚠️ **If you see a warning that the extension is not available**, this can happen when:
- Installing the extension for the first time
- Reinstalling the extension
- System loading issues

**Solution:** Simply restart your Karoo device. The extension will load correctly after the restart.

## Instructions

- This release adds the possibility to add multiple remotes.
- It's mandatory to scan your remote first. When you've the remote added (Remotes screen -> Scan -> push over your remote), you can scan for commands from your remote.
- If you have a garmin remote, you don't need to scan/learn commands (if you use other Ant remote it's mandatory)
- Then you need to go to Conf screen, select your remote and map Remotes commands to Karoo actions.
- Kremote auto-starts when Karoo starts, then push a button in remote and wait until the Remote flashing green several times.
- Kremote works (if only when riding option is enabled) only when you're in Ride app. Please if you want kremote works in all screens, uncheck this option. If you detect some lags, sometimes you can uncheck this option and try if it's work better.

## Configuration
- You need to configure the remote buttons to perform some actions in Karoo. There is a default configuration if you've a Garmin Remote (or compatible).
- You can configure double tap option. This permits to use double press and use more options with the remote (for example, zoom in double tap in left button, etc). Double tap works fine but introduces a delay (it's necessary to catch double press). The default delay is 1200 ms.
- You can map several actions (mark lap, swipe between screens, zoom in etc and use your Karoo as a BELL)
- If you have karoo > 1.535 you can use showmap option also
- **Bypass mute on bells** (Conf screen, Global settings): when enabled, bells are played through the Karoo's physical buzzer even if you have muted the device's audio alerts, so you stay audible to pedestrians/traffic. It is OFF by default and only affects bell actions. Use the **Test buzzer** button to check it works on your firmware (see below).

## Enroll new remote
- If you want to enroll a new remote, you can do it in the Remotes screen
- You can add multiple remotes, but only one remote can be active at the same time
- To enroll a new remote, go to the Remotes screen and select "Scan". Then, press any button on your remote to start the scanning process. Once detected,add the remote.
- After adding, if you have a Garmin remote, you can skip the command learning step. For other remotes, you need to learn the commands by pressing the buttons on your remote while the app is in learning mode.
- You can learn commands if you go to remote configure screen (push wheel in active remote. It's near trash symbol). 
- Then you have to select "Learn Commands" and press the buttons on your remote to map them to Karoo actions.
- You can also delete all learned commands and start again if you want to reset the remote configuration.
- Then you have to go to Map Screen and select the remote you want to use (only if you have multiple remotes added).
- Then you can map actions with the remote buttons and active some options (like double tap, only with riding, etc).
- If you have problems you can go to Debug screen and enable debug logging. This will help you to see what is happening in the app and if there are any issues with the remote connection or commands.Please repeat the process and send me the log.

## New Features & Performance Improvements

### 🔔 Mute-Bypass Bells
- **Stay audible when muted**: Optional toggle (Conf screen → Global settings → *Bypass mute on bells?*) that routes KRemote's bells through the Karoo's physical buzzer, bypassing the device's audio-alerts mute. Useful so a mapped bell button still warns pedestrians/traffic when you have silenced the Karoo.
- **Opt-in and safe by default**: OFF unless you enable it, and it only affects bell actions — every other action is unchanged.
- **Resilient**: Uses a private Karoo service. If it isn't available (e.g. a future Karoo update blocks it), bells automatically fall back to the normal beep that respects mute — they never go silent.
- **Test buzzer button**: Next to the toggle. Plays a short test tone and shows the result (`SUCCESS`, or a diagnostic such as `GATED_BY_SECURITY` / `BIND_FAILED…`) so you can verify the feature on your firmware without needing logs.

### 🚀 Intelligent Heartbeat System
- **Automatic Riding Detection**: KRemote automatically detects when you start/stop a ride on your Karoo
- **Adaptive Connection Monitoring**:
  - During riding: More frequent connection checks (every 5 minutes) for critical reliability
  - Normal use: Relaxed monitoring (every 10-15 minutes) to preserve battery
- **Real-time ANT+ Events**: Instant detection of connection/disconnection events from the hardware
- **Smart Reconnection**: Automatic reconnection with exponential backoff if connection is lost

### 🔧 Advanced Debug System
- **Debug Screen**: Access via the main menu to monitor connection status and system performance
- **Intelligent Logging**:
  - Automatic timeout after 24 hours to protect performance
  - Persistent between app restarts (with auto-disable protection)
  - Smart log rotation to prevent large files (5MB limit with backup)
- **Real-time Connection Monitoring**: See live status of all connected ANT+ devices
- **Performance Optimization Controls**: Enable/disable various optimizations and view cache statistics

### 📁 Debug Log Files
Debug logs are saved directly in the app's root directory:
- **Location**: `/Android/data/com.enderthor.kremote/files/kremote_debug.log`
- **Backup**: Previous session logs are preserved as `kremote_debug_previous.log`
- **Access**: Files can be accessed via file manager or ADB for troubleshooting

### 🛡️ Performance Optimizations
- **Smart Caching**: Reduces redundant connection checks and ANT+ operations
- **Memory Management**: Automatic cleanup of unused resources and expired data
- **Battery Optimization**: Intelligent intervals based on usage context (riding vs. idle)
- **Connection State Tracking**: Maintains device state without constant polling

### 🔄 Enhanced Reconnection Management
- **Visual Feedback**: Debug screen shows connection status with color-coded indicators:
  - 🟢 Green: Connected and working
  - 🟡 Orange: Reconnecting (shows attempt number)
  - 🔴 Red: Disconnected (shows last error)
- **Manual Override**: Force reconnection button available during reconnection attempts
- **Detailed Error Information**: Shows specific connection errors for troubleshooting

### 📊 Debug Screen Features
To access the Debug Screen: **Main Menu → Debug**

**Performance Optimizations Section** (Always visible):
- Toggle performance optimizations on/off
- View cache statistics and memory usage
- Clear caches manually
- Monitor coroutine pool status

**Debug Logging Section**:
- Enable/disable debug logging with 24-hour auto-timeout
- View remaining time until auto-disable
- Performance impact warnings
- Clear log files
- Real-time debug information display

**Connection Status Section** (When debug enabled):
- Live monitoring of all ANT+ devices
- Connection state with timestamps
- Error messages and reconnection attempts
- Force reconnection controls
- System diagnostics information

### 🎯 Smart Connection Management
- **Context-Aware**: Different behavior during rides vs. normal use
- **Event-Driven**: Responds instantly to real ANT+ hardware events
- **Predictive**: Uses activity patterns to optimize checking intervals
- **Self-Healing**: Automatic recovery from connection issues without user intervention

## Troubleshooting

### Connection Issues
1. **Check Debug Screen**: Go to Main Menu → Debug to see real-time connection status
2. **Enable Debug Logging**: Turn on debug logging to capture detailed connection information
3. **Review Log Files**: Access log files in `/Android/data/com.enderthor.kremote/files/`
You can use ADB to pull logs for analysis (or a file manager)
4. **Force Reconnection**: Use the "Force Reconnection" button in Debug screen if needed

### Performance Issues
1. **Check Optimizations**: Ensure Performance Optimizations are enabled in Debug screen
2. **Review Cache Usage**: Monitor cache statistics to ensure proper memory management
3. **Disable Debug Logging**: Turn off debug logging if not needed (impacts performance)
4. **Clear Caches**: Use "Clear Caches" button to free up memory

### Debug Log Analysis
Debug logs contain detailed information about:
- Connection events and state changes
- ANT+ hardware events and responses
- Reconnection attempts and results
- Performance optimization activities
- Error messages with timestamps

## Know Bugs
- Sometimes app isn't working fine, this new release has better support and it's working fine, but you need to know, Karoo function have priority over kremote actions.

## Technical Notes
- **Automatic Riding Detection**: Uses Karoo's native riding state broadcasts
- **Debug Auto-Timeout**: Debug logging automatically disables after 24 hours to prevent performance impact
- **Connection Monitoring**: Adaptive intervals based on riding state and recent device activity
- **Log Rotation**: Automatic backup and rotation of log files to prevent storage issues
