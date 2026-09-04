[app]
# Title of your application
title = AScan AgenT 2.0

# Package name
package.name = ascanagent

# Package domain (needed for android/ios packaging)
package.domain = com.ascan

# Source code where the main.py live
source.dir = .

# Source files to include
source.include_exts = py,png,jpg,kv,atlas,ttf,txt

# Application versioning
version = 2.0.0

# Requirements
# Pin python3/hostpython3 to 3.11 (cgi still present; avoids 3.13+/3.14 isolated build env issues)
# Kivy 2.3.0 works better with current p4a + Cython
requirements = python3==3.11.9,hostpython3==3.11.9,kivy==2.3.0,requests,urllib3,charset-normalizer,idna,certifi,cloudscraper,dnspython

# Orientation
orientation = portrait

# Android specific
fullscreen = 0
android.api = 33
android.minapi = 21
android.ndk = 25b
android.accept_sdk_license = True

# Permissions
android.permissions = INTERNET,ACCESS_NETWORK_STATE,WRITE_EXTERNAL_STORAGE,READ_EXTERNAL_STORAGE,VIBRATE

# Architecture
android.archs = arm64-v8a,armeabi-v7a

# Enable AndroidX
android.enable_androidx = True

# Use stable p4a branch
p4a.branch = master

[buildozer]
log_level = 2
warn_on_root = 1
