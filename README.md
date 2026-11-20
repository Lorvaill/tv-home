# TV Home — version française

Ce dépôt est un fork de [tinulica/tv-home](https://github.com/tinulica/tv-home) : un écran d'accueil
façon Apple TV pour les téléviseurs TCL sous Google TV. Tout le mérite du launcher d'origine
(Liquid Glass, Centre de contrôle, mode Cinéma, notices météo, outil de nettoyage) revient à son
auteur. Ce fork le traduit en français et ajoute ce qui suit.

[English (original)](README.en.md) · [Română](README.ro.md)

## Ce que ce fork ajoute

- **Tout en français** : l'interface, les réglages, la météo (départements français à partir du code
  postal), les vigilances.
- **Plex** : si un serveur Plex est configuré, la tuile Plex de l'accueil affiche « Reprendre » et
  les ajouts récents avec les visuels du serveur (fond + logo du titre, comme sur Apple TV). OK lance
  directement la lecture dans l'appli Plex.
- **Mode Cinéma sur ta bibliothèque Plex** : les mêmes questions (pour qui, film ou série, humeur,
  durée), mais les propositions viennent de ta bibliothèque, avec une ligne « Reprendre et ajouts
  récents ». Le mode streaming d'origine (TMDB + plateformes) reste disponible : un réglage
  « Mode Cinéma avec Plex » passe de l'un à l'autre.
- **Lumières de la pièce** : en entrant dans le mode Cinéma, les lumières choisies dans Home
  Assistant s'éteignent, puis se rallument en sortant (seulement celles qui étaient allumées).
- **Fonds d'écran animés selon l'heure et la météo** : des vidéos nommées
  `<moment>[_<météo>]` (par exemple `evening_rain.mp4`, `night.mp4`) ; le launcher choisit celle qui
  correspond au moment de la journée (heures réelles du lever et du coucher du soleil) et au ciel.
  Aucune vidéo n'est fournie : mets les tiennes.
- **Écran de veille** : le fond animé en plein écran, l'heure en grand et la météo en dessous.
- **Micro de la télécommande** : une bulle d'écoute par-dessus n'importe quelle appli.
  « Active le mode cinéma », « Ouvre Plex », « Quel temps fait-il ? », « Réglages »… et tout le
  reste devient une recherche.
- **Pas de mise en veille en jeu** : tant qu'une source HDMI (console, box) ou l'antenne est à
  l'écran, la télé ne lance plus l'écran de veille et ne s'éteint plus pour inactivité.
- **Réglages TCL par-dessus les applis** : la touche engrenage ouvre le panneau rapide de TCL au lieu
  de quitter l'appli ou la source en cours.

## Configuration (depuis l'ordinateur, une seule fois)

Tout se règle depuis un ordinateur relié à la télé par `adb` (voir l'installation dans le
[README d'origine](README.en.md) : `python tvtool.py connect <IP de la télé>` s'en occupe). Les
adresses et les jetons restent dans le stockage privé du launcher, sur la télé, et ne partent que
vers ton propre serveur.

### Plex

Il faut l'adresse de ton serveur Plex sur le réseau local et un jeton Plex.

1. **L'adresse du serveur** : l'IP de la machine où tourne Plex Media Server, avec le port `32400`
   (par exemple `192.168.1.10:32400`). Dans Plex : Paramètres → ton serveur → Accès à distance, ou
   dans les réglages de ta box (appareils connectés).
2. **Le jeton** : sur l'ordinateur, ouvre l'appli web Plex (`http://<adresse>:32400/web`), va sur
   n'importe quel film, menu **⋯ → Obtenir des infos → Afficher le XML**. Dans l'adresse de la page
   qui s'ouvre, copie ce qui suit `X-Plex-Token=` (une vingtaine de caractères). Détails :
   [support.plex.tv/articles/204059436](https://support.plex.tv/articles/204059436).
3. **Envoyer les deux à la télé** :

   ```sh
   adb shell am start -n ro.tinulica.tvhome/.MainActivity        --es plex_url 192.168.1.10:32400 --es plex_token <jeton>
   ```

La tuile Plex de l'accueil affiche alors « Reprendre » et les ajouts récents, et le mode Cinéma
propose ta bibliothèque. Pour revenir au mode Cinéma « streaming » (TMDB + plateformes) :
Réglages → TV Home → **Mode Cinéma avec Plex**.

Conseil : dans l'appli Plex de la télé, désactive le choix du profil au démarrage, sinon OK sur
une carte ouvre d'abord l'écran « Qui regarde ? ».

### Lumières avec Home Assistant

1. **Un jeton** : dans Home Assistant, clic sur ton profil (en bas à gauche) → onglet
   **Sécurité** → **Jetons d'accès longue durée** → **Créer un jeton**. Copie-le tout de suite, il
   ne s'affiche qu'une fois.
2. **Les lumières** : Paramètres → Appareils et services → **Entités**, cherche tes lampes et note
   leur identifiant, du type `light.salon` ou `switch.lampe_tv`.
3. **Envoyer à la télé** (entités séparées par des virgules, sans espace) :

   ```sh
   adb shell am start -n ro.tinulica.tvhome/.MainActivity        --es ha_url http://192.168.1.10:8123 --es ha_token <jeton>        --es ha_entities light.salon,switch.lampe_tv
   ```

En entrant dans le mode Cinéma, les lumières de la liste qui étaient allumées s'éteignent ; en
sortant, ces mêmes lumières se rallument (celles déjà éteintes ne bougent pas).

### Fonds d'écran animés

Ce sont tes propres vidéos : le launcher n'en fournit aucune. Il choisit celle qui correspond au
moment de la journée (d'après les vraies heures de lever et de coucher du soleil chez toi) puis à
la météo, et s'en sert aussi pour l'écran de veille.

1. **Nommer les vidéos** `<moment>[_<météo>].mp4` :

   | Moment | Météo (facultative) |
   |---|---|
   | `morning` (matin), `day` (journée), `evening` (soir), `night` (nuit), `late night` (fin de nuit) | rien ou `sun` (beau temps), `cloud`, `fog`, `wind`, `rain`, `thunder`, `snow` |

   Exemples : `day.mp4`, `day_rain.mp4`, `evening_snow.mp4`, `night.mp4`, `late night.mp4`.
   Les noms en français marchent aussi (`soir_pluie.mp4`, `nuit.mp4`). Pas besoin d'avoir toutes
   les combinaisons : s'il n'y a pas de `evening_rain`, la télé prend la plus proche (`evening`,
   puis un autre moment avec de la pluie…). Deux ou trois vidéos suffisent pour commencer.
2. **Le format** : de la vidéo **HEVC (H.265)**, au plus 4K et 60 images/s, **sans son**, et qui
   boucle proprement (la fin raccorde avec le début). Les TCL refusent le H.264 en 4K à 60 images/s.
   Pour convertir avec [ffmpeg](https://ffmpeg.org) :

   ```sh
   ffmpeg -i original.mp4 -an -c:v libx265 -crf 22 -vf "scale='min(3840,iw)':-2" -fpsmax 60 day_rain.mp4
   ```

3. **Les copier sur la télé** :

   ```sh
   adb push day_rain.mp4 /sdcard/Android/data/ro.tinulica.tvhome/files/dynamic/
   ```

   Dès qu'il y a au moins une vidéo dans ce dossier, elles remplacent les photos de fond. Pour
   revenir aux photos, vide le dossier.
4. **Facultatif, l'aperçu dans les Réglages** : la télé ne sait pas extraire une image d'une vidéo
   4K HEVC. Pour que l'écran des Réglages montre ton fond, ajoute une image fixe du même nom dans
   `dynamic/stills/` :

   ```sh
   ffmpeg -ss 1 -i day_rain.mp4 -frames:v 1 -vf scale=1280:-2 day_rain.jpg
   adb push day_rain.jpg /sdcard/Android/data/ro.tinulica.tvhome/files/dynamic/stills/
   ```

5. **Écran de veille** : pour utiliser ces vidéos comme écran de veille (heure en grand, météo en
   dessous) :

   ```sh
   adb shell settings put secure screensaver_components ro.tinulica.tvhome/.WallpaperDream
   adb shell settings put secure screensaver_enabled 1
   ```

### Micro et service du launcher

Le micro a besoin de l'autorisation d'enregistrer et, sur les TCL où l'assistant Google a été retiré,
du rôle d'assistant :

```sh
adb shell pm grant ro.tinulica.tvhome android.permission.RECORD_AUDIO
adb shell cmd role add-role-holder android.app.role.ASSISTANT ro.tinulica.tvhome
```

Sur les firmwares TCL récents, le système peut empêcher le service du launcher de redémarrer tout
seul (notices, volume, engrenage qui ne marchent plus après une mise à jour). Dans ce cas :

```sh
adb shell appops set ro.tinulica.tvhome APP_AUTO_START allow
```

Pour l'installation de base, le nettoyage et tout le reste, voir le [README d'origine](README.en.md).
