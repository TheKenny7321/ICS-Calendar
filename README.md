# TCS Calendar — APK Android

Application Android native qui permet d'exporter votre horaire TCS (eqso.be)
directement vers Google Calendar via un fichier .ics.

---


### Installer l'APK sur Android

1. Télécharger l'APK sur votre téléphone, ou le transférer (câble USB, Google Drive, email…)
2. Sur Android : **Paramètres** → **Applications** → **Sources inconnues** → Activer
   (ou : Paramètres → Sécurité → Installer des apps inconnues)
3. Ouvrez le fichier `.apk` → **Installer**

---

## 📱 Fonctionnement de l'app

| Étape | Action |
|-------|--------|
| 1 | Appuyez **"Importer depuis RHTime"** → connectez-vous dans le mini-navigateur |
| 1b | Appuyez **"⚙️"** → entrez vos identifiants pour les enregistrer et ne plus devoir les renseigner |
| 2 | Naviguez vers votre planning → clic long → **Exporter en XML** |
| 3 | L'aperçu du planning s'affiche avec tous les événements |
| 4 | **📅 Ouvrir l'ICS** → ouvre directement dans votre appli calendrier |
| 4b | **↑** → partage le fichier ICS (Drive, WhatsApp, email…) |

---

## 🏗 Structure du projet

```
tcs-apk/
├── .github/workflows/build.yml     ← GitHub Actions (build auto)
├── app/
│   ├── build.gradle
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── assets/
│   │   │   └── index.html          ← Interface HTML/JS de l'app
│   │   ├── java/be/eqso/tcs/
│   │   │   └── MainActivity.java   ← Bridge Android ↔ JavaScript
│   │   └── res/
│   │       ├── layout/activity_main.xml
│   │       ├── values/{strings,themes}.xml
│   │       └── xml/file_paths.xml
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
├── build.gradle
├── settings.gradle
└── gradlew
```

---
