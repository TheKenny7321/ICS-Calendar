![Version](https://img.shields.io/badge/version-3.2-blue) ![Android](https://img.shields.io/badge/Android-7.0%2B-green)

# ICS-Calendar 📅

**ICS-Calendar** est l'outil indispensable pour les utilisateurs de **RHTime**. Il vous permet de transformer votre planning professionnel (téléchargeable en `.xml` depuis RHTime) en événements de calendrier (`.ics`) parfaitement intégrés à votre smartphone.

### 🌟 Points forts
* **Intégration RHTime** : Connectez-vous et exportez votre horaire en deux clics.
* **Zéro perte de données** : Toutes vos informations (mémos, codes horaires, lieux) sont conservées.
* **Conversion automatique** : L'application détecte le XML et génère le fichier ICS instantanément.
* **Flexibilité** : Ajoutez l'événement à votre agenda personnel ou partagez-le avec vos collègues.

---

## ⚙️ Nouvelles options de personnalisation

La version 3 introduit des outils puissants dans les **Paramètres** pour nettoyer et organiser votre agenda :

* **🚫 Codes à ignorer** : Ne polluez plus votre calendrier avec des informations inutiles.
    > *Exemple : Ajoutez "F" ou "VA" à la liste pour que ces journées ne créent pas d'événements vides.*
* **✏️ Renommage de codes** : Adaptez les codes bruts de RHTime pour qu'ils soient plus explicites.
    > *Exemple : Transformez automatiquement le code "FOR" en "Formation".*
* **🗓️ Vue Aperçu** : L'application intègre désormais un **mini-calendrier** lors de la conversion. Cela vous permet de valider d'un coup d'œil que toutes vos prestations ont bien été capturées avant l'export final.

### Autres ajouts de la V3
* **🔒 Confirmation avant de quitter** : Une sécurité supplémentaire dans le navigateur intégré pour éviter de perdre votre session RHTime par erreur.

---

### 📖 Sommaire
* [📲 Installation de l'APK](#installation-de-lapk)
* [🚀 Guide de conversion RHTime](#comment-convertir-votre-horaire-rhtime) (Méthode recommandée)
* [📂 Importation locale](#importation-locale)
* [📊 Données et champs conservés](#données-et-champs-conservés)

---

## Installation de l'APK

L'application n'étant pas sur le Play Store, voici la marche à suivre :

1. Téléchargez l'APK depuis l'onglet **Releases** de ce dépôt → [Télécharger la dernière version](https://github.com/TheKenny7321/ICS-Calendar/releases/latest/download/TCS.Calendar.apk)
2. Autorisez l'installation depuis des sources inconnues dans vos paramètres Android.
3. Installez et lancez !

> [!IMPORTANT]
> **Play Protect** : Si Google Play Protect affiche un avertissement, cliquez sur "Détails" puis "Installer quand même". Le projet est open-source et ne collecte aucune donnée.

---

## Comment convertir votre horaire RHTime

L'application facilite la récupération de votre planning via un navigateur intégré.

<p align="center">
  <img src="https://github.com/user-attachments/assets/be867b80-9c06-4a0a-a835-a1df08100592" width="300">
</p>

### Étape 1 : Configuration (optionnel mais recommandé)

Pour gagner du temps, allez dans les **Paramètres** de l'application et enregistrez vos identifiants RHTime. L'application vous connectera automatiquement lors de vos prochaines sessions.

<p align="center">
  <img src="https://github.com/user-attachments/assets/ebc2935a-46a3-4f22-a1e4-c69e13d8ef35" width="300">
</p>



### Étape 2 : Récupérer l'horaire

1. Lancez la connexion à RHTime depuis l'écran principal de l'application.
2. Une fois sur votre grille horaire, effectuez un **appui long n'importe où sur l'écran**.
3. Dans le menu qui s'affiche, sélectionnez **"Exporter en XML"**.

<p align="center">
  <img src="https://github.com/user-attachments/assets/10235d0c-6b5b-4ee4-89e2-3042710c05fa" width="300">
</p>

### Étape 3 : Conversion et action

Dès que vous avez cliqué sur "Exporter", l'application prend le relais :
* Elle convertit le flux XML en format standard ICS de manière invisible.
* Vous arrivez alors sur l'écran final vous proposant :
    * **Ouverture instantanée** : Pour ajouter les horaires dans votre calendrier système (Google, Outlook, etc.).
    * **Option de partage** : Pour envoyer le fichier `.ics` via WhatsApp, Email ou le sauvegarder sur votre Cloud.

<p align="center">
  <img src="https://github.com/user-attachments/assets/df7636f8-eb5f-4d9f-983d-eae920495d7c" width="300">
</p>

---

## Importation locale

Si vous possédez déjà un fichier `.xml` exporté manuellement de RHTime :

1. Cliquez sur l'onglet **"Fichier local"** sur l'écran d'accueil.
2. Sélectionnez votre fichier dans le dossier "Téléchargements".
3. L'application traitera le fichier exactement comme s'il sortait du navigateur intégré.

<p align="center">
  <img src="https://github.com/user-attachments/assets/34d4791b-d9c6-4431-8f2c-63aac14d218d" width="300">
</p>

---

## Données et champs conservés

Contrairement à d'autres convertisseurs basiques, **ICS-Calendar** extrait et réinjecte l'intégralité des informations fournies par RHTime dans les champs correspondants du calendrier :

| Donnée RHTime | Champ dans votre agenda |
| :--- | :--- |
| **Date & Heure** | Début et fin de l'événement |
| **Lieu** | Emplacement de l'événement |
| **Code horaire** | Titre de l'événement |
| **Mémo** | Description / Notes |
| **Note** | Description / Notes complémentaires |

*Cela vous permet de retrouver vos consignes spécifiques ou vos numéros de poste directement dans la notification de votre calendrier.*

---

*Application développée avec l'assistance de [Claude](https://claude.ai) (Anthropic) — de l'idée à l'APK, entièrement par conversation.*
