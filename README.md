# TV Home — version française

Ce dépôt est un fork de [tinulica/tv-home](https://github.com/tinulica/tv-home). Un écran d'accueil
façon Apple TV pour les téléviseurs TCL sous Google TV. Tout le mérite du launcher d'origine
(Liquid Glass, Centre de contrôle, mode Cinéma, notices météo, outil de nettoyage) revient à son
auteur. Ce fork le traduit en français et ajoute ce qui suit.

[English (original)](README.en.md) · [Română](README.ro.md)

## Ce que ce fork ajoute

- **TRAD en FR**
- **Gestion Plex** : les tuiles de l'app sur la home affiche les films et séries "Reprendre/Films/Séries dans le style Apple TV ainsi que les affiches.
- **Mode Cinéma amélioré** : Ajout de Plex pour le mode de cinéma qui prend donc ses sources dans votre bibliothèque, on peut toujours basculer sur le mode original dans les réglages + en entrant dans le mode Cinéma, les lumières choisies dans Home
  Assistant s'éteignent, puis se rallument en sortant (seulement celles qui étaient allumées) voir "Configuration" plus bas.
- **Fonds d'écran animés selon l'heure et la météo** : le launcher choisit celle qui
  correspond au moment de la journée (heures réelles du lever et du coucher du soleil) et au ciel.
  *Vidéos non fournies* Voir "Configuration" plus bas
- **Écran de veille** : le fond animé en plein écran, l'heure en grand et la météo en dessous.
- **Micro de la télécommande** : une bulle d'écoute par-dessus n'importe quelle appli.
  « Active le mode cinéma », « Ouvre Plex », « Quel temps fait-il ? », « Réglages »… et tout le
  reste devient une recherche.
- **Pas de mise en veille en jeu** : tant qu'une source HDMI (console, box) ou l'antenne est à
  l'écran, la télé ne lance plus l'écran de veille et ne s'éteint plus pour inactivité.
- **Réglages TCL par-dessus les applis** : la touche engrenage ouvre le panneau rapide comme une overlay au lieu
  de quitter l'appli ou la source en cours.

## Configuration (depuis l'ordinateur, une seule fois)
*Tout se règle depuis un ordinateur relié à la télé par adb (voir l'installation dans le README d'origine : python tvtool.py connect <IP de la télé> s'en occupe). Les adresses et les jetons restent dans le stockage privé du launcher, sur la télé, et ne partent que vers ton propre serveur.*

Pour les lumières de HA :
1. Créer un jeton dans HA en allant dans --> Profil (nom en bas a gauche) --> Sécurité --> Jetons d'accès longue durée (section en bas) --> Créer un jeton (attention il s'affiche qu'une fois)
2. Trouver les lumières que vous voulez connecter au mode cinéma. Aperçu (tableau de bord) --> Cliquer sur un appareil --> Engrenage --> Case "id de l'entité" ex : "switch.prise"
3. Envoyer sur la tv les infos avec les commandes ci dessous depuis un cmd avec adb. 

```sh
adb shell am start -n ro.tinulica.tvhome/.MainActivity \
    --es ha_url http://ip-de-ha --es ha_token <jeton> \
    --es ha_entities light.salon,switch.prise
```

Pour configurer Plex avec le mode cinéma :
1. Obtenir le jeton --> cliquer sur n'importe quel film → Obtenir des infos → Afficher le XML --> Dans l'URL de la page qui s'ouvre tout à la fin : X-Plex-Token= (une vingtaine de caractères).

```sh
adb shell am start -n ro.tinulica.tvhome/.MainActivity        --es plex_url 192.168.1.10:32400 --es plex_token <jeton>
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
Fond d'écrans animés :

1. Préparer vos fonds d'écrans en les nomants de cette manière : <moment>[_<météo>].mp4
Il y a plusieurs "<moment>" : morning (matin), day (journée), evening (soir), night (nuit), late night (fin de nuit) et plusieurs "<météo>"
Exemple : day_rain.mp4, evening_snow.mp4 mais cela peut aussi être que un moment sans être lié obligatoirement a une météo ex : late night.mp4, day.mp4
*Il ne faut pas nécessairement avoir une vidéo pour chaque cas, 3 vidéos suffisent rien que pour commencer. Le système prendra une vidéo qui s'en rapproche le plus*

Conseils : 
- Vidéo en H.265
- Max 4K 60 img/s
- Sans son
- Loop propre a la fin
- Chercher sur Youtube/ Wallpaper Engine

2.Les envoyer sur la TV :

```sh
adb push day_rain.mp4 /sdcard/Android/data/ro.tinulica.tvhome/files/dynamic/
```

4. Facultatif (affichage du fond dans les réglages) :

```sh
ffmpeg -ss 1 -i day_rain.mp4 -frames:v 1 -vf scale=1280:-2 day_rain.jpg
adb push day_rain.jpg /sdcard/Android/data/ro.tinulica.tvhome/files/dynamic/stills/
```

3.Pour les utiliser en écran de veille

```sh
adb shell settings put secure screensaver_components ro.tinulica.tvhome/.WallpaperDream
adb shell settings put secure screensaver_enabled 1
```

Pour l'installation de base, le nettoyage et tout le reste, voir le [README d'origine](README.en.md).
