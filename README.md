# 📍 Mateshwari Field Tracker

Field employees ki live location aur din bhar ka route.

| Kya | Link |
|---|---|
| **Admin Panel** | https://mateshwariautomobiles1-wq.github.io/mateshwari-field-tracker/ |
| **Employee APK** | https://github.com/mateshwariautomobiles1-wq/mateshwari-field-tracker/releases/latest/download/FieldTracker.apk |
| Firebase project | `mateshwari-field-tracker` (Mumbai) |
| Admin login | `mateshwariautomobiles1@gmail.com` (Firebase Authentication > Users) |

`main` branch par push karte hi GitHub Actions APK (Releases) aur Admin Panel (GitHub Pages) bana deta hai. Firestore rules badlein to Firebase Console > Firestore > Rules me paste karein.

## Ek baar ka setup (ho chuka / baaki)
- [x] Firebase project, Email/Password + Anonymous login, Firestore (asia-south1), rules
- [x] Android + Web app registered, config code me daala
- [ ] Firebase > Authentication > Users > Add user: admin email + apna password

## Employee phone par
1. APK link kholein → install (Unknown apps allow).
2. Mobile + PIN (Admin Panel se) → Login.
3. Saare laal button ✓ karein — khaas kar **Location: Allow all the time** aur **Battery: No restrictions**.
4. Xiaomi/Redmi/POCO: Autostart ON. Vivo/Oppo/Realme: Allow background activity. Samsung: Never sleeping apps.

## Admin Panel
🟢 Online · 🟠 GPS OFF · ⚪ Offline (20 min se update nahi). Employee par click → route, km, stops, timeline, CSV.

## Limitations
Android kisi app ko zabardasti GPS ON karne ya uninstall rokne nahi deta. GPS band → turant alert + Admin me 🟠. Employees ko pehle bata kar consent lein.

## Files
`android/` app · `admin/` panel · `firestore.rules` · `.github/workflows/build-apk.yml`

## App ko uninstall-proof banana (company phones)

App me do level ka protection hai:

**Level 1 — Device Admin (koi setup nahi, har phone par):** App me login ke baad "Uninstall protection" button tap karein. Ab employee seedhe uninstall nahi kar payega — pehle Settings me ye band karna padega, aur jaise hi wo koshish karega, Admin Panel me us employee par 🚨 alert aa jayega.

**Level 2 — Device Owner (app bilkul uninstall nahi hoti):** Sirf company phone par, ek baar ka setup:
1. Phone ko factory reset karein (ya naya phone lein). Setup me koi Google account add na karein.
2. Phone par Developer Options > USB debugging ON karein.
3. App install karein (APK link se).
4. Phone ko computer se USB se jodein aur ye command chalayein:
   ```
   adb shell dpm set-device-owner com.fieldtrack.app/.AdminReceiver
   ```
5. Ho gaya. Ab app ka uninstall button hi kaam nahi karega, aur factory reset bhi block ho jayega.

Admin Panel me har employee ke neeche dikhta hai: 🔒 Protected (locked), 🛡️ Protection ON, ya ⚠️ Protection OFF.
