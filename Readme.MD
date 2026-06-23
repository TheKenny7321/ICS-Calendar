![Version](https://img.shields.io/badge/version-6.0.4-blue) ![Android](https://img.shields.io/badge/Android-7.0%2B-green) ![Windows](https://img.shields.io/badge/Windows-10%2B-0078D6?logo=windows&logoColor=white)


### À propos du projet
Cette application est issue de l'extension Google Chrome originale [TCS Schedule → ICS Exporter](https://chromewebstore.google.com/detail/bdlodfffneekfjheedoeedgknacbfogn?utm_source=item-share-cb). Développée par le même auteur, elle reprend le concept du projet initial tout en l'étendant sous la forme d'une application autonome avec de nouvelles fonctionnalités et améliorations.


# ICS-Calendar 📅
> Convertissez automatiquement votre planning RHTime en agenda sur smartphone et Windows

**ICS-Calendar** est l'outil indispensable pour les utilisateurs de **RHTime**. Il vous permet de transformer votre planning professionnel (téléchargeable en `.xml` depuis RHTime) en événements de calendrier (`.ics`) parfaitement intégrés à votre smartphone.

## 🌟 Points forts
* **Intégration RHTime** : Connectez-vous et exportez votre horaire en deux clics.
* **Zéro perte de données** : Toutes vos informations (mémos, codes horaires, lieux) sont conservées.
* **Conversion automatique** : L'application détecte automatiquement le fichier XML et génère instantanément le fichier ICS.
* **Flexibilité** : Ajoutez l'événement à votre agenda personnel ou partagez-le avec vos collègues.
* **Agenda sur mesure** : Ignorez les codes inutiles (congés, vacances…) et renommez les codes bruts en libellés clairs directement dans votre calendrier.

## ❓ Pourquoi utiliser ICS-Calendar ?

- Automatisation complète (zéro manipulation répétitive)
- Compatible RHTime directement
- Aucune perte de données
- 100% local (respect de la vie privée)

---

## ⚙️ Nouvelles options de personnalisation

### La version 3 introduit des outils puissants dans les **Paramètres** pour nettoyer et organiser votre agenda :

* **🚫 Codes à ignorer** : Ne polluez plus votre calendrier avec des informations inutiles.
    > *Exemple : Ajoutez "F" ou "VA" à la liste pour que ces journées (congés, vacances, etc.) ne créent pas d'événements dans votre agenda.*
* **✏️ Renommage de codes** : Adaptez les codes bruts de RHTime pour qu'ils soient plus explicites dans votre calendrier.
    > *Exemple : Transformez automatiquement le code "FOR" en "Formation", ou "NUI" en "Nuit".*
* **🗓️ Vue Aperçu** : L'application intègre désormais un **mini-calendrier** lors de la conversion. Cela vous permet de valider d'un coup d'œil que toutes vos prestations ont bien été capturées avant l'export final.
* **🔒 Confirmation avant de quitter** : Une sécurité supplémentaire dans le navigateur intégré pour éviter de perdre votre session RHTime par erreur.

---

### 🚀 Nouveautés de la version 4

La version 4 marque une évolution majeure en simplifiant encore davantage l'expérience utilisateur et en rendant l'application plus autonome :

* **🔄 Mise à jour simplifiée**
  * Recevez désormais une **notification automatique** lorsqu'une nouvelle version est disponible.
  * Les mises à jour peuvent être installées **sans désinstaller l'application existante**.
  > *Un gain de temps et une transition fluide vers les nouvelles versions.*

* **📤 Export XML intégré**
  * Un **bouton d'export dédié** apparaît directement dans votre session RHTime.
  * Plus besoin de manipulations complexes : l'export de votre horaire se fait en **un seul geste**.
  > *Une expérience plus rapide, plus intuitive et sans friction.*

---

### ✨ Nouveautés de la version 5

La version 5 pousse encore plus loin l'automatisation et le confort d'utilisation, en réduisant les frictions dès le premier lancement :

* **💬 Assistant de première connexion**
  * Lors de la **toute première utilisation**, une **fenêtre contextuelle (pop-up)** invite l'utilisateur à saisir ses identifiants RHTime directement, sans avoir à passer par les paramètres.
  * Une fois enregistrés, ces identifiants sont réutilisés automatiquement à chaque session.
  > *Fini la configuration laborieuse : l'application vous guide dès le départ.*

* **📊 Barre de progression**
  * Une **barre de progression visuelle** accompagne désormais chaque étape du processus de conversion.
  * Vous savez à tout moment où en est le traitement, de la récupération du XML jusqu'à la génération du fichier ICS.
  > *Plus de doute sur ce qui se passe en arrière-plan.*

* **📅 Sélection automatique du mois et de l'année**
  * L'application **détecte et sélectionne automatiquement** le mois et l'année courants lors de l'export depuis RHTime.
  * Il reste bien sûr possible de **modifier manuellement** la période souhaitée si nécessaire.
  > *L'export du mois en cours ne demande plus aucune manipulation supplémentaire.*


### 💡 Améliorations globales

* Interface encore plus fluide et logique
* Réduction des étapes nécessaires à l'export
* Meilleure intégration avec le navigateur embarqué
* Prise en main immédiate dès le premier lancement

---

## 🆕 Nouveautés de la Version 6
### ✨ Ajouts
- 🔔 **Rappel mensuel** : activez une notification mensuelle dans les paramètres
  pour ne plus oublier d'importer votre horaire. La date de rappel est entièrement
  personnalisable.
- 📋 **Fiche détail** : appuyez sur n'importe quel événement — aussi bien en **vue
  liste** qu'en **vue calendrier** — pour afficher l'intégralité de ses informations
  (lieu complet, notes, mémos) dans une fenêtre dédiée.
- 📱 **Amélioration générale de l'interface**: Uniformisation de l'interface par rapport aux nouvelles fonctionnalités ajoutées.
  Ajout d'une option **sécurisée** permettant d'afficher son mot de passe ainsi qu'une **détection d'identifiants incorrects**.

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

## 🔐 Confidentialité

- Aucune donnée envoyée à un serveur externe
- Identifiants stockés localement uniquement
- Projet open-source et vérifiable

> [!IMPORTANT]
> **Play Protect** : Si Google Play Protect affiche un avertissement, cliquez sur "Détails" puis "Installer quand même". Le projet est open-source et ne collecte aucune donnée.

---

## Comment convertir votre horaire RHTime

L'application facilite la récupération de votre planning via un navigateur intégré.

### Étape 1 : Configuration (obligatoire)

Lors du **premier lancement**, un **pop-up** vous invite automatiquement à saisir vos identifiants RHTime. Cette étape est indispensable pour permettre à l'application de se connecter et d'importer votre horaire de manière entièrement automatique.

> *Une fois enregistrés, vos identifiants sont réutilisés à chaque session sans aucune intervention de votre part.*

### Étape 2 : Récupérer l'horaire

1. Depuis l'écran principal, sélectionnez le **mois** et l'**année** souhaités — le mois en cours est présélectionné automatiquement.
2. Appuyez sur **"Importer depuis RHTime"**.
3. L'application se connecte, récupère et convertit votre horaire de façon entièrement automatique et invisible, une **barre de progression** vous indiquant l'avancement en temps réel.

### Étape 3 : Conversion et action

Dès que vous avez cliqué sur **"Importer depuis RHTime"**, l'application prend le relais et vous arrivez sur l'écran final vous proposant :
* **Ouverture instantanée** : Pour ajouter les horaires dans votre calendrier système (Google, Outlook, etc.).
* **Option de partage** : Pour envoyer le fichier `.ics` via WhatsApp, Email ou le sauvegarder sur votre Cloud.

---

## Importation locale

Si vous possédez déjà un fichier `.xml` exporté manuellement de RHTime :

1. Cliquez sur l'onglet **"Fichier local"** sur l'écran d'accueil.
2. Sélectionnez votre fichier dans le dossier "Téléchargements".
3. L'application traitera le fichier exactement comme s'il sortait du navigateur intégré.

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

*Application conçue et développée par Skrabel Kenny, avec assistance de [Claude](https://claude.ai) (Anthropic) pour certaines phases de développement et de débogage.*
