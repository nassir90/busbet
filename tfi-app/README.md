# tfi-app

A minimal Android "Hello World" project.

## Setup

1. **Update Android SDK path in `local.properties`:**
   - Open `local.properties` and set `sdk.dir` to your Android SDK location
   - On Linux: typically `/home/username/Android/Sdk`
   - On macOS: typically `/Users/username/Library/Android/sdk`
   - On Windows: typically `C:\Users\username\AppData\Local\Android\Sdk`

2. **Add launcher icon (optional but recommended):**
   - In Android Studio, go to **File → New → Image Asset**
   - Choose a name (should be `ic_launcher`) and configure your icon
   - This will automatically place assets in `app/src/main/res/mipmap-*` directories

3. **Open in Android Studio:**
   - File → Open → Select this `tfi-app` directory
   - Android Studio will sync Gradle and download dependencies

4. **Run:**
   - Connect an emulator or device
   - Click **Run** or press `Shift+F10`

## Project Structure

- `app/src/main/java/com/example/tfiapp/MainActivity.java` — Main activity
- `app/src/main/res/layout/activity_main.xml` — UI layout
- `app/build.gradle` — App-level build config
- `build.gradle` — Project-level build config
