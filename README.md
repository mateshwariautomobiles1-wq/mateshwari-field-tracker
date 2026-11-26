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
