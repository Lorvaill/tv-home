# TV Home — version française

Ce dépôt est un fork de [tinulica/tv-home](https://github.com/tinulica/tv-home) : un écran d'accueil
façon Apple TV pour les téléviseurs TCL sous Google TV. Tout le mérite du launcher d'origine
(Liquid Glass, Centre de contrôle, mode Cinéma, notices météo, outil de nettoyage) revient à son
auteur. Ce fork le traduit en français et ajoute ce qui suit.

[English (original)](README.md) · [Română](README.ro.md)

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

Les adresses et les jetons restent dans le stockage privé du launcher, sur la télé, et ne partent que
vers ton propre serveur.

```sh
# Plex (le jeton : https://support.plex.tv/articles/204059436)
adb shell am start -n ro.tinulica.tvhome/.MainActivity \
    --es plex_url 192.168.1.10:32400 --es plex_token <jeton>

# Home Assistant (jeton longue durée, entités séparées par des virgules)
adb shell am start -n ro.tinulica.tvhome/.MainActivity \
    --es ha_url http://192.168.1.10:8123 --es ha_token <jeton> \
    --es ha_entities light.salon,switch.lampe_tv

# Fonds d'écran animés : copier les vidéos (HEVC conseillé, sans son)
adb push evening_rain.mp4 /sdcard/Android/data/ro.tinulica.tvhome/files/dynamic/
```

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

Pour l'installation de base, le nettoyage et tout le reste, voir le [README d'origine](README.md).
