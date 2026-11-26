# MeetingLock (Pixel 7a Prototype)

Complete Android Kotlin prototype that restricts a Pixel 7a to **Phone (Calls)** and **Messages (SMS)** during a QR-initiated meeting session, unlocking via the Host's Unlock QR code or automatically when the timer expires.

## How to Get the APK

### Option A: Cloud Build via GitHub (No Android Studio needed)
1. Create a new GitHub repository (public or private).
2. Upload all files from this folder (including the `.github` folder!).
3. Open the **Actions** tab on GitHub -> wait ~2 minutes for **Build MeetingLock APK** to turn green.
4. Click the completed run -> download **MeetingLock-Pixel7a-APK** under Artifacts -> extract `app-debug.apk`.

### Option B: Build Locally in Android Studio
1. Open Android Studio -> **Open** -> select this folder.
2. Wait for Gradle Sync to finish.
3. Plug in your Pixel 7a (USB Debugging ON) and click **Run**, OR go to **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

## Installing & Testing on Pixel 7a
1. Transfer `app-debug.apk` to your Pixel 7a and tap to install.
2. Open **MeetingLock**.
3. Tap **1. Enable Accessibility Service** -> **MeetingLock**.
   - *Pixel 7a Note (Android 13/14/15/16):* If the toggle is greyed out ("Restricted setting"), go to **Phone Settings > Apps > See all apps > MeetingLock > tap the 3-dot menu (top right) > Allow restricted settings**, then return to Accessibility and toggle it ON.
4. Tap **2. Allow Display Over Other Apps** -> toggle ON for MeetingLock.
5. **Testing with just 1 phone:** Open `host_qr_generator.html` on your computer browser to act as the Meeting Host, or tap **Quick 60-Second Self-Test** inside the app.
