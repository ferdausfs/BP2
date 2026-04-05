# 🔒 Content Blocker — BDB v3.2

Android Accessibility Service দিয়ে তৈরি content blocker।
Screen text, URL, video metadata real-time monitor করে adult ও suggestive content block করে।

## ✨ Features

| Feature | বিবরণ |
|---|---|
| 🔞 Hard Adult Block | Porn, xxx, sex video keyword ও domain |
| 🌶️ Soft Adult Block | Hot girl, item song, sexy dance (browser + video) |
| 🎬 Video Metadata Check | Play অবস্থায় title/tag/hashtag — প্রতি 3 সেকেন্ডে scan |
| 🔒 Uninstall Protection | Device Admin — Settings থেকে manually বন্ধ না করলে uninstall হবে না |
| 📊 Block Statistics | কতবার কোন app block হলো |
| 🔐 PIN Lock | Settings 4-digit PIN দিয়ে protect |
| 🚫 Custom Keywords | নিজের keyword যোগ, JSON import/export |
| ✅ Whitelist | নির্দিষ্ট app-কে bypass দাও |

## 🚀 Setup

1. APK install করো
2. **Settings → Accessibility → Content Blocker** চালু করো
3. App খুলে **Device Admin চালু করো** (uninstall protect)
4. সব feature default-এ চালু থাকে

## 🏗️ Build

GitHub-এ push করলেই GitHub Actions automatically debug APK build করবে।
Actions tab → সর্বশেষ run → Artifacts থেকে APK download করো।

Manual build:
```bash
./gradlew assembleDebug
```

## 🔒 Privacy

- কোনো internet permission নেই
- সব data device-এ locally stored (SharedPreferences)
- কোনো server-এ কিছু যায় না
