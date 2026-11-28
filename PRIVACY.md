# Privacy policy of Ember AirReceiver

**Last updated:** 2026-11-28
**Applies to:** Ember AirReceiver for Google TV and Fire TV on the beta branch

Ember AirReceiver receives media and screen mirroring on your television.

## 0. Where you can read this

* **In the app:** Settings → About → **Privacy policy**. The entry opens this
  document; on a television without a browser the address is shown instead, so
  you can read it on your phone.
* **On the web:** <https://github.com/PrimeN/Ember-AirReceiver/blob/beta/PRIVACY.md>

## 1. The short version

* Ember uses your local network for discovery and direct mirroring.
* When a sender requests playback from a media URL, Ember may contact the host serving the media. That host can be on your local network or on the internet.
* Ember keeps receiver settings and pairing information on the TV. The app has no account system, advertising, or analytics.

## 2. Network activity

| What | Transport | Who can see it |
|---|---|---|
| The name of the TV (your device name, or the name you set in Settings) | mDNS service name and TXT record, DLNA `friendlyName` | Every device in the same Wi-Fi that listens for AirPlay/UPnP |
| The MAC address of the TV | mDNS TXT record (`deviceid`), `GET /info`, `GET /server-info` | The same — the AirPlay protocol requires it |
| A random identifier of the TV (`pi`, and the DLNA `UDN`) | mDNS TXT record, DLNA device description | The same |
| The public key of the TV | `GET /info` (`pk`) | The same |
| The mirrored picture and sound | AirPlay (TCP 7000, UDP 6001/6002) and Miracast (TCP 7236) | The sender that started the session |
| The title, artist and album of the playing track, and its cover image | AirPlay `SET_PARAMETER` | The sender that started the session |
| The picture you share with "AirPlay photo" | AirPlay `PUT /photo` | The sender that started the session |
| The volume you set on the TV remote | AirPlay `SET_PARAMETER`, and the reverse DACP channel back to the sender | The sender that started the session |
| The TV's IP address in the LAN | SSDP `LOCATION`, the DLNA description URL, the reverse DACP/NTP connections | Every device in the same Wi-Fi |
| A media URL supplied for playback | HTTP or HTTPS, including media playlists and segments where applicable | The media host receives the request, including the public-facing IP address or local address used by the TV, the requested path and query, and ordinary request headers. Sender-mediated playback can fetch playlists through the sender. |
| A library request initiated from Ember | Local device discovery, library description and browse requests | Local media servers receive the browse request and can see the TV’s network address. If you select a media item, its host receives the playback request. |

Use the receiver tiles or Settings to stop advertising a particular service. Stop all receivers to end Ember's receiver advertisements. Opening the Privacy policy link hands the web address to your browser.

## 3. What is stored on the television, and where

All of it lives in the private storage of the application
(`/data/data/app.ember.airreceiver.<flavour>/`). No other application can read it, and
because `android:allowBackup="false"` is set, **none of it is included in the
Android cloud backup**.

**And none of it leaves the device in any other way either.** `android:allowBackup
="false"` alone is not enough for that: on Android 12 and newer the *device-to-device
transfer* — the migration of the app's data onto a newly set-up device — follows the
data extraction rules, and the platform documentation says that on devices of some
manufacturers it happens even when the backup is switched off. Ember AirReceiver therefore
names two rule files in the manifest (`res/xml/data_extraction_rules.xml` for Android
12 and newer, `res/xml/backup_rules.xml` for Android 11 and older), and both exclude
every domain. The identity of the television and the keys of the paired controllers
therefore stay on the television that holds them.

| What | Where | Why |
|---|---|---|
| Your settings, including the receiver name, protocol switches, PIN policy, startup and playback preferences | `datastore/airreceiver_settings.preferences_pb` | The settings belong to you and must survive a restart. |
| A random identifier for the TV (`airreceiver_device_uuid`) and the 32-byte seed of the TV's Ed25519 key pair (`pairing_ed25519_seed`) | `shared_prefs/airreceiver_prefs.xml` | macOS and iOS recognise the receiver by these two values. Without them the TV would appear as a new device after every restart. |
| The public keys of the controllers that completed the PIN pairing, and the number of failed pairing attempts | `shared_prefs/airreceiver_pairings.xml` | So a paired Mac does not have to enter the PIN again, and so that a wrong PIN cannot be guessed endlessly. |
| The mirrored picture, the shared photo, the album cover | **nowhere** — only in memory, while it is on screen | A stream is not a file. Nothing is written to the disk. |
| The system log | **nowhere** — a release build writes no log at all (see §5) | — |

Nothing else is stored. There is no cache directory, no download directory, no
"recent files" list, and no crash dump on the disk.

### How to remove it

* **Everything at once:** Android → *Settings* → *Apps* → *Ember AirReceiver* →
  *Storage* → *Clear storage*. The TV forgets its identity and appears in the
  AirPlay menu as a new device; every paired Mac has to pair again.
* **Only the pairing:** clear the storage as above — the paired controllers are
  part of it.
* **Everything, including the app:** uninstall Ember AirReceiver. Android removes the
  private storage with it.

## 4. The permissions Ember AirReceiver asks for, and what it does with them

| Permission | Why it is needed | Is it privacy relevant? |
|---|---|---|
| `INTERNET` | Allows receiver sockets and HTTP or HTTPS playback requests. | The media host can see the request details described above. |
| `CHANGE_WIFI_MULTICAST_STATE` | The multicast lock that keeps mDNS (and therefore the AirPlay entry in the sender's menu) alive. | No. |
| `ACCESS_WIFI_STATE` | Required by Wi-Fi Direct (Miracast) and by the Google Cast SDK. It is **not** used to read the IP or MAC address — those are read with `java.net.NetworkInterface`, which needs no permission. | No. |
| `ACCESS_NETWORK_STATE` | Detects that the TV came back from standby or changed Wi-Fi, so the advertisement can be repeated. | No. |
| `CHANGE_WIFI_STATE` | Required by the Wi-Fi Direct framework (Miracast). | No. |
| `NEARBY_WIFI_DEVICES` | Android 13+ replaced the location permission for Wi-Fi Direct with this one. The app declares it with `neverForLocation`: it never derives a location from a Wi-Fi result. | No — the flag promises exactly that. |
| `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` | Android asks for a location permission for Wi-Fi Direct up to Android 12. Both are capped at API 32, so a current TV does not receive them at all. | Ember can request these permissions when you enable wireless display on those versions. Ember uses them for Wi-Fi Direct discovery. |
| `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE` | The receiver must keep running while the app is in the background. | No. |
| `RECEIVE_BOOT_COMPLETED` | The optional "start on boot" switch. | No. |
| `SYSTEM_ALERT_WINDOW` | A *special app access*, not a runtime permission, and not granted at install time. It is used for one thing only: to bring the streaming screen to the front when a mirroring session starts while the app is in the background. The app never draws an overlay. Without the grant, the app simply does not come forward on its own. | Low. It would allow drawing over other apps in principle; the app does not do that. |
| `POST_NOTIFICATIONS` | The foreground service notification. Requested at runtime, and it can be declined. | No. |

| `ACCESS_LOCAL_NETWORK` | Allows discovery, receiver connections and local library browsing when required by the Android version and app target. | Android may prompt when you start a network feature. |
| `CONFIGURE_WIFI_DISPLAY` | Lets eligible firmware expose the TV’s optional wireless-display role. | The platform grants this privileged permission on supported devices. The app does not display a runtime prompt for it. |

## 5. The logs

* A **release build writes nothing to the system log.** No logging backend is
  installed, so there is no log file to collect, and no log that could be read
  by anyone.
* A **debug build** writes to the Android system log, as every development
  build does. Before a message is written, it passes through a central
  reduction (`app.ember.airreceiver.util.LogRedactor`): the host part of an IP address,
  the device-specific half of a MAC address, complete UUIDs, and the
  credentials and query string of a URL are replaced by placeholders. The
  reduction is on by default in every build, so a log excerpt that you paste
  into a public issue tracker does not carry the addresses of your network.
* The reduction cannot recognise free text. A device name, the name of the
  sender, and the title of a playing track are logged as they are.
* The **diagnostics report** that you can copy out of the app is reduced a second
  time, and more strictly. The report on the screen names the television and its
  address, because that is what you need in order to find the device. The **copied**
  text does not: it carries no IP address, no MAC address, no UUID, neither the name
  of the television nor the name of the sender. What stays is what makes a bug report
  useful — the version, the platform, the state of every protocol, the ports, the
  result of the self test and the resource measurement.

## 6. What happens when you enable wireless display

Android 13 and later can prompt for nearby Wi-Fi devices. Android 12 and earlier can prompt for location access to allow Wi-Fi Direct discovery. If you decline, other receivers can still be used with their required permissions.

Miracast uses a wireless display control connection to negotiate the stream. The picture and sound use the media transport agreed by the TV and sender.

## 7. The children

Ember AirReceiver is a tool for a television. It collects nothing, shows no advertising
and asks for no account, so there is nothing it would collect from a child
either.

## 8. The source code

The source of Ember AirReceiver is open, and the source is the proof: every statement
in this document can be checked against the code. The manifest that the
permissions come from is
`app/src/main/AndroidManifest.xml`, the log reduction is
`app/src/main/kotlin/app/ember/airreceiver/util/LogRedactor.kt`.

## 9. The changes of this policy

Every change of this policy is a change of the repository, with the date in the
header of this file.

## 10. The contact

Questions about this policy or about the privacy of a mirroring session belong
into the issue tracker of the project:
<https://github.com/PrimeN/Ember-AirReceiver/issues> — every question and every report
about the privacy of Ember AirReceiver belongs there, and the report is public.
