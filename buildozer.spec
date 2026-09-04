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
requirements = python3,kivy==2.2.1,requests,urllib3,charset-normalizer,idna,certifi,cloudscraper,dnspython

# Orientation
orientation = portrait

# Android specific
fullscreen = 0
android.api = 33
android.minapi = 21
android.ndk = 25b
android.sdk = 33
android.accept_sdk_license = True

# Permissions
android.permissions = INTERNET,ACCESS_NETWORK_STATE,WRITE_EXTERNAL_STORAGE,READ_EXTERNAL_STORAGE,VIBRATE

# Architecture
android.archs = arm64-v8a, armeabi-v7a

# Services
# android.services = 

# Add source files
# android.add_src = 

# Build options
android.gradle_dependencies = 
android.enable_androidx = True

# Icon
# android.icon = icon.png

# Presplash
# android.presplash_color = #0D1117

# Release signing (set via GitHub secrets)
# android.release_artifact = apk

[buildozer]
log_level = 2
warn_on_root = 1
