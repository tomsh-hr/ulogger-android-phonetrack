# ![ulogger_logo_small](https://cloud.githubusercontent.com/assets/3366666/24080878/0288f046-0ca8-11e7-9ffd-753e5c417756.png) μlogger [![Build Status](https://travis-ci.com/bfabiszewski/ulogger-android.svg?branch=master)](https://travis-ci.com/bfabiszewski/ulogger-android) [![Coverity Status](https://scan.coverity.com/projects/12109/badge.svg)](https://scan.coverity.com/projects/bfabiszewski-ulogger-android)

μlogger [*micro-logger*] is an android application for continuous logging of location coordinates, designed to record hiking, biking tracks and other outdoor activities. 
Application works in background. Track points are saved automatically at chosen intervals or manually and may be uploaded to dedicated server in real time.
This client works with [μlogger web server](https://github.com/bfabiszewski/ulogger-server). 
Together they make a complete self owned and controlled client–server solution.

## Download
[![Download from f-droid](https://img.shields.io/f-droid/v/net.tomsh.phonetracklogger.svg?color=green)](https://f-droid.org/app/net.tomsh.phonetracklogger)

## Features
- meant to be simple and small (*μ*)
- low memory and battery impact
- focus on privacy, doesn't use Google Play services, logs to self-owned server
- uses GPS or network based location data
- synchronizes location with web server in real time, in case of problems keeps retrying
- alternatively works in offline mode; positions may be uploaded to the servers manually
- configurable tracking settings
- export to GPX format
- self-check screen for basic diagnostics
- automation

## Screenshots
<img alt="main" src="fastlane/metadata/android/en-US/images/phoneScreenshots/screenshot1.png" width="30%"> <img alt="waypoint" src="fastlane/metadata/android/en-US/images/phoneScreenshots/screenshot2.png" width="30%"> <img alt="settings" src="fastlane/metadata/android/en-US/images/phoneScreenshots/screenshot3.png" width="30%">

## Self-check
In case of problems, you may go to Self-check menu. It will check whether all necessary permissions are granted and all settings are properly configured.

<img alt="self-check" src="fastlane/metadata/android/en-US/images/phoneScreenshots/screenshot4.png" width="30%">

## Help
- μlogger's current status is shown by two leds, one for location tracking and one for web synchronization: 

| led                                                | tracking                        | synchronization       |
|----------------------------------------------------|---------------------------------|-----------------------|
| ![status green](https://dummyimage.com/10/00ff00)  | on, recently updated            | synchronized          |
| ![status yellow](https://dummyimage.com/10/ffe600) | on, long time since last update | synchronization delay |
| ![status red](https://dummyimage.com/10/ff0000)    | off                             | synchronization error |

- clicking on current track's name will show track statistics

## Location permissions
Starting with Android 11, if you want to use the application without user interaction (automating, autostart on boot), it is necessary to grant application background location permission ("Allow all the time" option).
In case of automation the controlling application must also have the same background location permission granted.
In all other cases, when you start tracking from app screen, it is enough to grant "Allow only while using the app" option.

## Battery optimization
For reliable work battery optimization should be turned off for µlogger. Otherwise location service working in the background may be stopped by the operating system.
On Android 12+ the application will refuse to start from background without user interaction (automation, autostart) with battery optimization turned on.

## App settings guidelines
Finding the optimized settings for your practice can be a bit complex and may require you to do a lot of testing.
As a first approach, here are some parameters that offer a good compromise between precision and the number of points acquired by your server.

| Activity           | Time       | Distance | Accuracy | Provider      |
|--------------------|------------|----------|----------|---------------|
| **hiking/cycling** | 30 seconds | 100m     | 100m     | GPS + Network |
| **motorbiking**    | 1 minute   | 500m     | 50m      | GPS + Network |

They may not be optimal, depending on your feelings, and you will have to adapt them.

## Contribute translations
[![Translate with transifex](https://img.shields.io/badge/translate-transifex-green.svg)](https://www.transifex.com/bfabiszewski/ulogger/)

## Donate
[![Donate paypal](https://img.shields.io/badge/donate-paypal-green.svg)](https://www.paypal.me/bfabiszewski)  
![Donate bitcoin](https://img.shields.io/badge/donate-bitcoin-green.svg) `bc1qt3uwhze9x8tj6v73c587gprhufg9uur0rzxhvh`  
[![Donate dash](https://img.shields.io/badge/donate-dash-green.svg)](https://explorer.mydashwallet.org/address/Xb6X3zwLMgc3QQDNbeYmsqSwn2pofH2vXT) `Xb6X3zwLMgc3QQDNbeYmsqSwn2pofH2vXT`  

## License
[![License: GPL 3.0](https://img.shields.io/badge/license-GPL--3.0-green.svg)](https://www.gnu.org/licenses/gpl-3.0)  
