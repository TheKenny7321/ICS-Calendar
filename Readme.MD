# Bienvenue sur le Wiki de ICS-Calendar 📅

**ICS-Calendar** est l'outil indispensable pour les utilisateurs de **RHTime**. Il vous permet de transformer votre planning professionnel (Téléchargeable en .xml depuis RHTime) en événements de calendrier (`.ics`) parfaitement intégrés à votre smartphone.

### 🌟 Points forts
* **Intégration RHTime** : Connectez-vous et exportez votre horaire en deux clics.
* **Zéro perte de données** : Toutes vos informations (Mémos, codes horaires, lieux) sont conservées.
* **Conversion Automatique** : L'application détecte le XML et génère le fichier ICS instantanément.
* **Flexibilité** : Ajoutez l'événement à votre agenda personnel ou partagez-le avec vos collègues.

---

### 📖 Sommaire
* [📲 Installation de l'APK](#installation-de-lapk)
* [🚀 Guide de Conversion RHTime](#comment-convertir-votre-horaire-rhtime) (Méthode recommandée)
* [📂 Importation Locale](#importation-locale)
* [📊 Données et champs conservés](#donnees-et-champs-conserves)

---

## Installation de l'APK

L'application n'étant pas sur le Play Store, voici la marche à suivre :

1. Téléchargez l'APK depuis l'onglet **Releases** de ce dépôt. -> [Télécharger Release 2.1](https://github.com/TheKenny7321/ICS-Calendar/releases/download/Release_2.1/TCS.Calendar.apk)
2. Autorisez l'installation depuis des sources inconnues dans vos paramètres Android.
3. Installez et lancez !

> [!IMPORTANT]
> **Play Protect** : Si Google Play Protect affiche un avertissement, cliquez sur "Détails" puis "Installer quand même". Le projet est open-source et ne collecte aucune donnée.

---

## Comment convertir votre horaire RHTime

L'application facilite la récupération de votre planning via un navigateur intégré.

### Étape 1 : Configuration (Optionnel mais recommandé)
Pour gagner du temps, allez dans les **Paramètres** de l'application et enregistrez vos identifiants RHTime. L'application vous connectera automatiquement lors de vos prochaines sessions.

### Étape 2 : Récupérer l'horaire
1. Lancez la connexion à RHTime depuis l'écran principal de l'application.
2. Une fois sur votre grille horaire, effectuez un **appui long n'importe où sur l'écran**.
3. Dans le menu qui s'affiche, sélectionnez **"Exporter en XML"**.

### Étape 3 : Conversion et Action
Dès que vous avez cliqué sur "Exporter", l'application prend le relais :
* Elle convertit le flux XML en format standard ICS de manière invisible.
* Vous arrivez alors sur l'écran final vous proposant :
    * **Ouverture instantanée** : Pour ajouter les horaires dans votre calendrier système (Google, Outlook, etc.).
    * **Option de partage** : Pour envoyer le fichier `.ics` via WhatsApp, Email ou le sauvegarder sur votre Cloud.

---

## Importation Locale

Si vous possédez déjà un fichier `.xml` exporté manuellement de RHTime :

1. Cliquez sur **"Importer un fichier local"** sur l'écran d'accueil.
2. Sélectionnez votre fichier dans le dossier "Téléchargements".
3. L'application traitera le fichier exactement comme s'il sortait du navigateur intégré.

---

## Données et champs conservés

Contrairement à d'autres convertisseurs basiques, **ICS-Calendar** extrait et réinjecte l'intégralité des informations fournies par RHTime dans les champs correspondants du calendrier :

| Donnée RHTime | Champ dans votre Agenda |
| :--- | :--- |
| **Date & Heure** | Début et fin de l'événement |
| **Lieu** | Emplacement de l'événement |
| **Code Horaire** | Titre ou préfixe de l'événement |
| **Mémo** | Description / Notes |
| **Note** | Description / Notes complémentaires |

*Cela vous permet de retrouver vos consignes spécifiques ou vos numéros de poste directement dans la notification de votre calendrier.*

---
