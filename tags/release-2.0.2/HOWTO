Description:

AziLink is an application that allows USB tethering for Android-based phones, without requiring root access.  It works by using a Java-based NAT that communicates with OpenVPN on the host computer.  It's been tested on MacOS, Windows, and Linux.  The connection will be forwarded over the phone's active network service, which can be either WiFi or 3G/EDGE.

Required files:
  * ADB from the 1.1 or 1.5 Android SDK or from http://lfx.org/azilink/adb.zip
  * OpenVPN 2.1 (not 2.0) from http://openvpn.net/index.php/downloads.html
  * AziLink.apk from the download section or from http://lfx.org/azilink/azilink.apk
  * AziLink.ovpn from the download section or from http://lfx.org/azilink/azilink.ovpn

Installation:

1) Install OpenVPN on the host. I use version 2.1_rc15, but any version should work. Apparently if you use version 2.0 you'll need to remove the NO_DELAY option from the AziLink.ovpn configuration file. You can find OpenVPN at:
http://openvpn.net/index.php/downloads.html

2) Enable USB debugging on the phone. From the home screen, this is under
Settings>Applications>Development>USB debugging.

3) Install the Android USB driver (if you don't already have one installed).
See http://developer.android.com/guide/developing/device.html for more information.  The driver is included in the ADB download if you don't want to get the full SDK.

4) Install the program. You can either use ADB to install by typing
"adb install azilink.apk" with the file in the current directory, or you can browse (on the phone!) to: http://lfx.org/azilink/azilink.apk

Either way you might need to allow installation from unknown sources
under Settings>Applications>Unknown Sources.

Configuration steps:

1) On the host, run "adb forward tcp:41927 tcp:41927" to set up port forwarding. Be sure to use adb from the Android 1.1 or 1.5 SDK! The version from 1.0 will lock up under heavy load. If you don't want to download the entire SDK, you can get a copy of ADB+drivers from http://lfx.org/azilink/adb.zip

2) On the phone, run AziLink and make sure "Service active" is checked.

3) Right click AziLink.ovpn on the host (not in the web browser!) and select "Start OpenVPN on this configuration file." You can find this file at: http://lfx.org/azilink/azilink.ovpn. If you're using Linux or, god forbid, MacOS, you'll also need to manually set the nameserver to 192.168.56.1 (the phone's NAT IP address).  This address is automatically forwarded to the phone's current DNS server.

