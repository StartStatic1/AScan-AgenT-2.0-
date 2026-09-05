[app]
title = AScan AgenT 2.0
package.name = ascanagent
package.domain = com.ascan
source.dir = .
source.include_exts = py,png,jpg,kv,atlas,ttf,txt
version = 2.0.3

requirements = python3==3.11.9,hostpython3==3.11.9,kivy==2.3.0,requests,urllib3,charset-normalizer,idna,certifi,dnspython

orientation = portrait
fullscreen = 0

android.api = 33
android.minapi = 21
android.ndk = 25b
android.accept_sdk_license = True
android.permissions = INTERNET,ACCESS_NETWORK_STATE,WRITE_EXTERNAL_STORAGE,READ_EXTERNAL_STORAGE,VIBRATE
android.archs = arm64-v8a
android.enable_androidx = True
android.manifest.orientation = portrait

[buildozer]
log_level = 2
warn_on_root = 1
