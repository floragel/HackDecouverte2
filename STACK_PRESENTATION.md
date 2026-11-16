# Lync, le stack en 2 minutes

## L'idée
Une app pour étudiants : **réseau social du campus** + **navigation jusqu'à la salle**.
Tu choisis un cours dans ton calendrier, Lync te guide dans la rue, puis à l'intérieur du bâtiment, jusqu'à la porte de la salle.

## L'app
- **Android**, écrit en **Kotlin**
- Interface en **Jetpack Compose** (l'outil moderne de Google pour les écrans)
- Bilingue **français / anglais**

## Les données
- **Supabase** : la base de données en ligne (comptes, publications, amis, positions en direct)
- **Mode démo** : l'app marche aussi sans internet, avec des fausses données Concordia

## Dehors : comme Google Maps
- **Carte** : MapLibre + OpenFreeMap (gratuit, données **OpenStreetMap**)
- **Itinéraire à pied** : OSRM (service de calcul d'itinéraire gratuit, basé sur OpenStreetMap)
- **Position** : GPS du téléphone

## Dedans : là où le GPS ne marche plus
Le GPS ne passe pas à travers le béton. On utilise les **capteurs du téléphone** :

| Capteur | À quoi il sert |
|---|---|
| Podomètre | Compter les pas |
| Boussole / gyroscope | Savoir dans quelle direction tu marches |
| Baromètre | Mesurer l'altitude, donc **l'étage** |

**Le principe :** point de départ + nombre de pas × longueur du pas × direction = ta position.
C'est ce qu'on appelle le **PDR** (*Pedestrian Dead Reckoning*, l'estime du piéton).

**L'étage :** on mesure la pression de l'air dans la rue, puis à l'intérieur. La différence donne la hauteur, donc l'étage.

## Les plans du bâtiment (fait maison)
1. Photos des plans d'évacuation du pavillon John Molson (MB), 7 étages
2. Transformés en **plans vectoriels** (SVG), alignés sur le vrai nord
3. Un **graphe** : 761 points, environ 300 salles, escaliers et ascenseurs reliés
4. Le plus court chemin est calculé avec l'**algorithme de Dijkstra** (option sans escaliers pour les fauteuils roulants)

## Les petits plus
- **Carte étudiante** holographique (gyroscope) + **code Lync** maison
- Connexion entre amis en collant deux téléphones (**NFC**)
- Scan du code avec la caméra (**CameraX**)
- Import de l'horaire de cours (fichier **.ics**)

## En une phrase
> **Kotlin + Compose** pour l'app, **Supabase** pour les données, **OpenStreetMap** dehors, et **les capteurs du téléphone** (pas, boussole, baromètre) dedans, là où le GPS ne marche plus.
