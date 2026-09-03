# DevVault

DevVault is a comprehensive personal Android utility application. It provides an extensive suite of power-user tools natively on your device, entirely offline and privacy-first.

## Features

- **File Manager**: Browse directories, manage files, zip/unzip archives, and securely move files to the encrypted Vault.
- **Code Editor**: A local editor for modifying source code and text files directly on your device.
- **APK Tools**: Inspect installed applications (including system apps), identify split APKs, and extract/share them as standalone `.apk` files.
- **Cleaner**: Analyze your storage to find duplicate files, large files (20MB+), old files (90+ days), and rarely-used applications. 
- **Notes & Contacts**: Manage local notes and manipulate the device's contacts list, including batch deletion and restoration.
- **Secure Vault**: Encrypt sensitive files and protect access behind Biometric authentication (Fingerprint/Face) and a PIN fallback.
- **Network Inspector**: View current Wi-Fi and network connectivity states.

## Build Instructions

This project is built using modern Android development practices (Kotlin, Jetpack Compose, Kotlin DSL).

### Requirements
- **Android Studio**: Ladybug (or newer recommended)
- **Android Gradle Plugin (AGP)**: 9.1.1
- **Kotlin**: 2.2.10

### SDK Configuration
- **compileSdk**: 36
- **targetSdk**: 36
- **minSdk**: 24

### 1. Opening the Project
Simply clone the repository and open it in Android Studio. The Gradle wrapper will automatically download the necessary dependencies.

### 2. Debug Builds
The project gracefully handles debug signing. If a specific `debug.keystore` is not found in the root directory, the Android Gradle Plugin (AGP) will automatically generate and use a local default keystore (usually in `~/.android/debug.keystore`). No manual setup is required for debug builds.

### 3. Release Builds
For security, the production keystore (`my-upload-key.jks`) is listed in `.gitignore` and is not committed to the repository. 
To build a signed release APK or AAB, supply your keystore and credentials using the following environment variables:
- `KEYSTORE_PATH` (defaults to `./my-upload-key.jks` if omitted)
- `STORE_PASSWORD`
- `KEY_PASSWORD`

*Note: If a keystore file is not found at the configured path, the `build.gradle.kts` file will safely bypass the custom release signing block to prevent hard build failures in CI environments or on first clone.*

## Permissions

DevVault relies on several permissions to provide its full utility suite. All functionalities execute locally.
- `READ_CONTACTS` & `WRITE_CONTACTS`: Required for the local contacts manager to read, delete, and restore contacts.
- `ACCESS_NETWORK_STATE` & `ACCESS_WIFI_STATE`: Used by the Network Inspector to display connection metadata.
- `USE_BIOMETRIC`: Grants access to the Android Biometric prompt for the Secure Vault.
- `QUERY_ALL_PACKAGES`: Required to list installed user and system applications in the APK Tools and Cleaner modules.
- `PACKAGE_USAGE_STATS`: Used to identify applications that haven't been launched recently in the Cleaner module. 
- `READ_EXTERNAL_STORAGE` & `WRITE_EXTERNAL_STORAGE` (Up to API 32/29): Legacy permissions required for extensive file browsing, zip extraction, and storage analysis.

## License

All rights reserved.
