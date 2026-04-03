# TCS Calendar — APK Android

Application Android native qui permet d'exporter votre horaire TCS (eqso.be)
directement vers Google Calendar via un fichier .ics.

---

## 🚀 Générer l'APK via GitHub Actions (sans Android Studio)

### Étape 1 — Préparer le projet

Après avoir cloné/téléchargé ce projet, il faut ajouter le fichier
`gradle/wrapper/gradle-wrapper.jar` qui manque (non inclus dans le ZIP car binaire).

**Téléchargez-le ici** (copier le fichier dans `gradle/wrapper/`) :
```
https://github.com/gradle/gradle/raw/v8.4.0/gradle/wrapper/gradle-wrapper.jar
```

### Étape 2 — Créer le repo GitHub

1. Allez sur https://github.com → **New repository**
2. Nommez-le `tcs-calendar` → **Public** → **Create**
3. Uploadez **tous les fichiers** de ce projet (drag & drop dans l'interface GitHub)
   - Veillez à respecter l'arborescence exacte des dossiers
4. Commitez

### Étape 3 — Lancer le build

1. Dans votre repo GitHub → onglet **Actions**
2. Le workflow **"Build APK"** se lance automatiquement
3. Attendez ~5 minutes
4. Allez dans **Actions** → cliquez sur le build terminé → section **Artifacts**
5. Téléchargez **`tcs-calendar-debug`** → c'est votre APK !

### Étape 4 — Installer l'APK sur Android

1. Transférez l'APK sur votre téléphone (câble USB, Google Drive, email…)
2. Sur Android : **Paramètres** → **Applications** → **Sources inconnues** → Activer
   (ou : Paramètres → Sécurité → Installer des apps inconnues)
3. Ouvrez le fichier `.apk` → **Installer**

---

## 📱 Fonctionnement de l'app

| Étape | Action |
|-------|--------|
| 1 | Appuyez **"Ouvrir tcs.eqso.be"** → connectez-vous dans le mini-navigateur |
| 2 | Naviguez vers votre planning → clic long → **Exporter en XML** |
| 3 | Fermez le navigateur → **"Choisir le fichier XML exporté"** |
| 4 | Aperçu du planning s'affiche avec tous les événements |
| 5 | **📅 Google Calendar** → ouvre directement dans votre appli calendrier |
| 5b | **↑** → partage le fichier .ics (Drive, WhatsApp, email…) |

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
│   ├── gradle-wrapper.jar          ← À télécharger (voir Étape 1)
│   └── gradle-wrapper.properties
├── build.gradle
├── settings.gradle
└── gradlew
```

---

## 🔧 Compiler localement (Android Studio)

1. Ouvrez Android Studio → **Open** → sélectionnez le dossier `tcs-apk`
2. Attendez la synchronisation Gradle
3. **Build** → **Build Bundle(s) / APK(s)** → **Build APK(s)**
4. L'APK est dans `app/build/outputs/apk/debug/`
