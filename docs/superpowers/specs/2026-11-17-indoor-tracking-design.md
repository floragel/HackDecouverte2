# Suivi intérieur v1 : trouver et suivre l'utilisateur sans infrastructure

- Date : 2026-11-17
- Statut : brouillon, à valider. Révisé le même jour après ton plan (carte précise, zone GPS,
  carte des directions, score de confiance) et l'essai d'extraction automatique des murs (§7.1).
- Point de départ : branche `lync-algorythme` (Android, Kotlin) ; portage 1:1 sur la branche `ios` (Swift)

## 1. Intention

Le suivi pas à pas actuel marche bien **si** on connaît le point de départ et la direction
initiale : toute erreur au départ se retrouve dans tout le trajet. Aujourd'hui, le départ vient
soit d'une salle choisie à la main (l'erreur peut atteindre la taille de la salle), soit de la
dernière position GPS dehors (seulement si on arrive de l'extérieur). La direction vient de la
boussole, fausse de 15 à 30° dans un bâtiment.

La v1 doit trouver l'utilisateur **où qu'il soit dans le bâtiment** (couloir, devant une porte,
dans une salle, en sortant d'un ascenseur), **sans qu'il ait rien à faire**, puis le suivre
jusqu'à destination, y compris écran éteint et téléphone dans la poche.

Pourquoi sans infrastructure : à terme, l'app doit couvrir d'autres bâtiments (tout Concordia,
Polytechnique, d'autres grands bâtiments où l'on se perd). Ajouter un bâtiment ne doit coûter
que **son plan** : rien à installer, aucun relevé sur place.

## 2. Objectifs, non-objectifs, anti-objectifs

### Objectifs
- **O1.** Départ n'importe où, sans action : une zone probable s'affiche tout de suite, se
  déplace avec l'utilisateur et rétrécit quand il marche.
- **O2.** Itinéraire lançable immédiatement depuis la zone la plus probable, recalculé quand
  l'estimation s'affine ou change de zone.
- **O3.** Une fois la position trouvée : environ 1 à 2 m d'erreur, bonne porte à l'arrivée.
- **O4.** Bon étage, y compris après un trajet en ascenseur.
- **O5.** Suivi qui continue en arrière-plan pendant un trajet, écran éteint, téléphone en
  main, en poche ou dans un sac.
- **O6.** À la réouverture de l'app, la position reprend là où elle était, ajustée de ce qui
  s'est passé entre-temps, sans aucun calcul en arrière-plan.
- **O7.** Même comportement sur Android et iPhone.
- **O8.** Batterie et chaleur maîtrisées.

### Non-objectifs v1
- **Précision de 10 cm.** Aucun système sans matériel installé ne l'atteint ; l'état de l'art
  est de 1 à 2 m, ce qui suffit pour le bon couloir et la bonne porte.
- **Scanner le Wi-Fi nous-mêmes** (impossible sur iPhone, limité à 4 scans par 2 minutes sur
  Android) : la position fournie par le système utilise déjà le Wi-Fi, sur les deux plateformes.
- **Lire les plaques de porte à la caméra** : gardé pour plus tard, comme raccourci facultatif.
- **Consignes de guidage en arrière-plan** (vibration ou notification à chaque virage).
- **Plusieurs bâtiments et tunnels entre bâtiments** : le modèle doit le permettre, la v1 se
  limite au MB.
- **Partage de données entre utilisateurs** (aucun serveur).

### Anti-objectifs (échec, même si ça « marche »)
- Exiger des balises, autocollants, QR codes ou une intervention de l'université.
- Exiger de parcourir chaque bâtiment pour le cartographier (Wi-Fi, champ magnétique).
- Afficher une position précise qui est fausse : quand on ne sait pas, on le montre (grand
  cercle, ou deux zones).
- Bien marcher sur un système et mal sur l'autre.
- Vider la batterie ou faire chauffer le téléphone quand aucun trajet n'est en cours.
- Envoyer la position de l'utilisateur à un serveur.

## 3. Contraintes

- **Code.** Android en Kotlin (`com.hackdecouverte`), iOS en Swift (portage 1:1 sur la
  branche `ios`). Le cœur reste du code pur, sans dépendance au système, testé sur JVM et
  XCTest.
- **iPhone.** Aucune app ne peut scanner le Wi-Fi. L'arrière-plan continu exige le mode
  « localisation » (pastille bleue).
- **Android.** L'arrière-plan continu exige un service de premier plan avec sa notification.
  Beaucoup de téléphones d'entrée et de milieu de gamme n'ont pas de baromètre.
- **Données par bâtiment.** Le graphe de chaque étage (`floors/floorplan-MB-1..7.json`) :
  couloirs, portes, escaliers, ascenseurs, sorties. Chaque nœud « room » est une **porte**
  rattachée au couloir, et une salle peut en avoir plusieurs (MB-2.210 en a 3). Il y a aussi le
  géoréférencement et les SVG ; les murs des SVG sont mêlés au texte (calque
  `walls-and-text`) : bons pour l'affichage, trop bruités pour décider « ce pas traverse un mur ».
- **Taille du MB.** Étages de 94 × 74 m ; 140 à 214 m de couloirs par étage ; 4 à 12
  intersections par étage ; étages très semblables entre eux.
- **Repère.** Le plan est en mètres, est/nord vrai, centré sur le noyau d'ascenseurs du MB
  (`GeoReference`). La position du système, convertie, est dans le même repère.
- **Règles du moteur PDR (AGENTS.md).** Corrections bornées par pas, tracé brut jamais réécrit,
  une seule horloge (temps depuis le démarrage du téléphone).

## 4. Approches envisagées

### 4.1 Comment trouver le départ (choix : B, par toi)

| | Idée | Pour | Contre |
|---|---|---|---|
| A | Viser une plaque de porte avec la caméra | instantané, ~0,5 m, direction juste | une action ; il faut une plaque visible |
| **B** | **Le plan trouve la position pendant que l'utilisateur marche, aidé par les indices du téléphone** | **aucune action ; corrige aussi la dérive tout le long** | **il faut marcher, avec des virages** |
| C | A + B | départ immédiat et robuste | une action |

B, parce que l'utilisateur ne doit rien faire. A reste possible plus tard en raccourci facultatif.

### 4.2 Le moteur qui garde plusieurs hypothèses (mon choix)

- **Filtre à particules limité à l'espace libre de la carte (retenu).** Des milliers
  d'hypothèses (position, étage, angle entre le téléphone et la direction de marche, échelle du
  pas) avancent à chaque pas ; celles qui traversent un mur perdent leur poids. C'est l'approche de Zee (Microsoft) :
  départ inconnu sur un étage de 65 × 35 m, erreur sous 1 m après 80 à 100 pas et un virage
  décisif. Retenu parce qu'il apprend tout seul l'angle du téléphone (direction de départ
  inconnue, téléphone en poche) et la longueur du pas.
- **Grille sur le réseau + chemin le plus probable** (MapCraft, Oxford). Très économe, converge
  à 3 m près en moins de 50 m dans 97 % des cas, en 2 à 4 m avec un indice Wi-Fi ; mais l'angle
  du téléphone et le pas doivent être gérés à part. Gardé en solution de repli si le filtre ne
  converge pas assez bien.
- **Garder le suivi actuel à une seule hypothèse, avec recalage.** Ne peut pas partir d'une
  position inconnue.

Défaut connu des filtres à particules : ils peuvent ne jamais converger quand on part de tout
l'étage (MapCraft le constate même avec 100 000 particules). Ici, on part rarement de tout
l'étage : position du système, dernière zone connue, entrée depuis la rue (§6.4). On ajoute
aussi des particules « de secours » (§6.3).

### 4.3 L'étage (option C, mon choix, non contesté)

- A : chercher sur tous les étages à la fois. Lent et ambigu dans le MB.
- B : demander l'étage à chaque fois. Une action.
- **C : deviner avec une chaîne d'indices (§6.5), afficher « Étage 2 ? » déjà rempli,
  corrigeable d'un tap.**

### 4.4 L'arrière-plan (ton idée, ma mise en forme)

Les réveils périodiques sont écartés : iOS les déclenche quand il veut (quelques fois par jour),
Android au mieux toutes les 15 minutes. On retient deux régimes (§6.7) : **veille** (rien ne
tourne, bilan au retour) et **trajet actif** (suivi continu, économe).

### 4.5 La carte (ton plan)

- Garder seulement le graphe et en déduire des bandes de couloir de 2 m (ma première version) :
  rien à produire, mais on ne sait pas où l'on est dans une salle, et les halls, les largeurs
  réelles et les portes sont approximatifs.
- **Faire une carte précise des murs, des salles et des portes (retenu, ton choix)** : les
  hypothèses ne peuvent passer que par les portes, et Zee fonctionne comme dans l'article, avec
  de vrais murs. Le coût : produire cette carte devient le vrai prix d'ajout d'un bâtiment.
  L'essai sur le MB montre qu'on peut en faire automatiquement la plus grande partie (§7.1).

Ton plan complet, dans l'ordre : carte précise → zone probable grâce au GPS → carte des
directions probables → élimination à la Zee → score de confiance selon que la personne suit la
direction probable. Le design ci-dessous le suit, avec deux nuances : la zone GPS pondère sans
jamais couper (§6.4), et le score de confiance entre dans le poids de chaque hypothèse au lieu de
s'ajouter à la confiance affichée (§6.3).

## 5. Vue d'ensemble

```
 Capteurs du téléphone                      Indices « absolus » (faibles)
 ─────────────────────                      ─────────────────────────────
 pas, orientation ─────► Moteur PDR         position du système (Wi-Fi, GPS)
 (existant : pdr/)        pas + cap relatif  baromètre (étage)
                                │            historique pas/distance du système
                                │            boussole (indice très faible)
                                ▼                       │
                     ┌──────────────────────────────────▼─┐
  Carte précise ───► │  Filtre de localisation (nouveau,   │
  murs, salles,      │  loc/) : particules dans l'espace   │
  portes + graphe    │  libre, sur tous les étages         │
  + carte des        │                                     │
    directions ────► │                                     │
                     └─────────────────┬───────────────────┘
                                       ▼
                Zone(s) probable(s), étage + confiance,
                position et cap sur le plan une fois trouvés
                                       ▼
                Backend existant : itinéraire, carte, destinations
```

Trois états visibles :
- **Recherche** : grand cercle, ou deux, « Vous êtes probablement ici ».
- **Approximatif** : cercle de 3 à 8 m qui suit l'utilisateur.
- **Trouvé** : point + direction, cercle de moins de 3 m ; le tracé apparaît.

Quand le filtre tourne : dès que l'écran de navigation intérieure est ouvert, et pendant tout
trajet actif (y compris en arrière-plan). Dehors, la navigation extérieure existante s'applique ;
le passage à l'intérieur se fait à la porte (§6.4, cas 1).

## 6. Composants

### 6.1 Moteur PDR (existant, `pdr/`)

Il reste la source des pas et du cap relatif : détection des pas (capteur matériel, ou
accéléromètre avec seuil adaptatif), cap Swing-Twist, longueur de pas dynamique, anti-secousse.

Il **démarre tout seul** quand le filtre tourne (longueur de pas de départ : §6.8). Il n'y a plus
de bouton pour lancer le suivi ; « Démarrer » ne sert plus qu'à lancer un itinéraire, qui ouvre
le trajet actif (§6.7).

Quand le filtre est actif, on **coupe les corrections du PDR qui utilisent le plan ou le GPS** :
rappel latéral vers le couloir, recalage aux intersections (`JunctionMatcher`), rappel vers la
position externe. Le filtre fait ce travail, et les deux s'additionneraient. La stabilisation du
cap sur des axes appris en marchant reste active.

Le tracé brut du PDR n'est jamais réécrit (règle conservée). Le tracé affiché vient du filtre
(§6.9).

### 6.2 Carte précise (nouveau, ton plan)

**Contenu, par étage** (modèle inspiré d'IMDF, le format d'Apple devenu standard OGC, pour
pouvoir importer plus tard les bâtiments déjà décrits en IMDF ou dans OpenStreetMap) :
- **Murs** : segments ou polygones, avec la façade fermée.
- **Portes** : des ouvertures dans les murs, chacune reliée à sa salle et au nœud « porte » du
  graphe. Ce sont les seuls passages entre une salle et un couloir.
- **Salles** : polygones avec leur numéro (MB-2.210) et leur type.
- **Zones ouvertes** : halls, atriums, paliers.
- **Mobilier** : rangées de sièges, tables fixes. Ce sont des obstacles « franchissables » : ils
  ne doivent pas couper une salle en morceaux.
- **Escaliers et ascenseurs** : emprises au sol, reliées par `vertical_group_id`.
- **Sorties** vers l'extérieur.
- Le **graphe** existant reste pour les itinéraires, recalé pour être cohérent avec les murs :
  aucune arête ne traverse un mur, et chaque nœud « porte » est dans son ouverture.

**Forme utilisée par le filtre** (calculée une fois au chargement, environ 1 Mo par étage) :
- une grille de 0,1 m « libre / mur / porte / obstacle » ;
- la distance au mur le plus proche ;
- la carte des directions (§6.2 bis) ;
- pour chaque case libre, la salle ou la zone à laquelle elle appartient.

### 6.2 bis Carte des directions probables (nouveau, ton plan)

Pour chaque case libre, on précalcule à partir des murs seuls (sans le graphe, donc valable
pour tout bâtiment) :
- **l'axe local** : la direction du couloir, sans sens (aller vers le nord ou vers le sud compte
  pareil). Calculé à partir des variations de la distance aux murs, lissées sur environ 1,6 m ;
- **sa netteté**, entre 0 et 1 : forte dans un couloir, faible dans un hall, une salle ou à une
  intersection ;
- **la proximité d'une porte** : devant une porte, marcher en travers du couloir est normal, donc
  la netteté y est réduite.

L'essai sur le MB valide la méthode : sur les étages 2 à 6, l'axe est net sur 91 à 99 % des
points de couloir, à 1° près en médiane de la direction réelle (§7.1).

Deux usages dans le filtre (§6.3) :
1. **À chaque pas, un facteur de poids** entre `1 − β` et 1 : 1 quand la marche de l'hypothèse
   suit l'axe ou que l'axe n'est pas net, `1 − β` quand elle va en travers d'un couloir net. β est
   petit (de l'ordre de 0,1 à 0,3) et se règle sur des trajets enregistrés. Une hypothèse qui se
   dirige vers un mur perd du poids avant même de le toucher.
2. **Au départ, pour l'angle téléphone→marche.** Quand l'angle est inconnu, la première ligne
   droite d'au moins 5 pas est supposée suivre l'axe local. Chaque hypothèse ne garde donc que 2
   angles possibles (4 aux intersections) au lieu de 360°. 10 % des hypothèses gardent un angle
   quelconque, pour les zones ouvertes et les cas où la personne ne suit pas le couloir.

À ne pas faire : utiliser l'itinéraire comme direction probable. Il est calculé à partir de
notre propre estimation : s'en servir pour la confirmer serait circulaire, et cacherait un
mauvais virage.

### 6.3 Filtre de localisation (nouveau, paquet `loc/`, pur Kotlin, testé sur JVM)

**Chaque particule porte :** l'étage ; une position (x, y) dans l'espace libre de la carte
(couloir, hall ou salle) ; l'angle entre le téléphone et la direction de marche ; une échelle de
pas ; un poids.

**À chaque pas accepté par le PDR** (longueur L, cap relatif θ) :
1. Chaque particule avance de `L × échelle` dans la direction `θ + angle`, avec un petit bruit
   (environ 3° et 5 %). L'angle et l'échelle dérivent un peu à chaque pas.
2. **Élimination à la Zee :** si le segment parcouru traverse un mur, le poids est fortement
   réduit, mais pas annulé (la carte peut être imparfaite). Traverser une porte ou le mobilier
   franchissable est normal.
3. **Carte des directions :** le poids est multiplié par le facteur d'alignement (§6.2 bis).

**Angle de départ :** si le PDR est déjà aligné sur le nord par la boussole, l'angle est tiré
autour de 0 avec ±30° (la boussole se trompe de ±15° dans 90 % des endroits, jusqu'à 30°).
Sinon, il est fixé par la première ligne droite, qui est supposée suivre le couloir (§6.2 bis).

**Mesures** (elles multiplient les poids) :
- **Position du système** : vraisemblance tolérante, c'est-à-dire une gaussienne élargie plus
  une part réservée aux valeurs aberrantes, car elle peut être fausse de 20 à 50 m à l'intérieur.
  Jamais une coupure nette.
- **Baromètre** : un changement d'étage n'est possible que pour les particules proches d'un
  escalier ou d'un ascenseur.
- **Boussole** : seulement comme indice de départ sur l'angle, jamais comme vérité.

**Rééchantillonnage** quand le poids se concentre sur trop peu de particules. À chaque fois, 2 %
de particules « de secours » sont réinjectées dans la zone plausible (15 m autour de la zone
principale, plus le cercle de la position du système), pour se rattraper si le filtre s'est
trompé. Si toutes les particules deviennent improbables, le filtre repart de cette zone
plausible et l'état repasse à « Recherche ».

**Sorties :** les groupes de particules (zones), chacun avec son centre, son rayon (environ 95 %
des particules) et son poids ; l'étage et sa confiance ; l'état (Recherche, Approximatif, ou
Trouvé quand la zone principale porte plus de 90 % du poids avec un rayon de moins de 3 m).

**Score de confiance (ton plan) :** le « suit-il la direction probable ? » de chaque hypothèse
est déjà compté dans son poids (étape 3). La confiance affichée vient donc seulement de la
concentration des poids ; on n'y ajoute pas de bonus, sinon la même preuve compterait deux fois
et l'app afficherait trop vite « Trouvé » au mauvais endroit. Ton idée sert en plus de **signal
d'alarme** : si la zone principale va en travers des couloirs ou heurte des murs plusieurs pas de
suite, on baisse la confiance et on réinjecte des particules de secours.

**Budget :** environ 2 000 particules au départ, 300 une fois la position trouvée ; au plus
2 ms par pas sur un téléphone moyen (à 2 pas par seconde, c'est négligeable).

**Repère d'orientation remis à zéro** (le capteur a été réenregistré) : plus besoin de démarrer
un « nouveau trajet ». L'angle de chaque particule redevient inconnu et se réapprend.

### 6.4 Zone de départ (ton idée de propagation, adoptée)

Du cas le plus précis au moins précis :

1. **Entrée depuis la rue** (existant, `enterBuilding`). Dernière position GPS dehors et
   direction de marche GPS : quelques mètres autour de la porte, angle connu. La position est
   trouvée presque tout de suite.
2. **Réouverture après une veille.** On part de la dernière zone et on l'étend par propagation :
   - budget de distance `D' = D × 1,15 + rayon de la dernière zone + 2 m`, où D vient de
     l'historique du système (distance, ou pas × foulée) ;
   - propagation **dans l'espace libre de la carte** (plus court chemin limité par D', à travers
     les portes), jamais à vol d'oiseau : on ne traverse pas les murs ;
   - passer un escalier ou un ascenseur coûte la **distance réellement marchée** (environ 10 m
     par étage d'escalier, environ 5 m pour un ascenseur), pas le coût utilisé pour les
     itinéraires. Ces passages ne sont ouverts que si le baromètre, ou le podomètre de l'iPhone,
     indique un changement d'étage ;
   - les salles sont atteintes par leurs portes, comme dans la réalité ;
   - sans autre information, les hypothèses sont réparties uniformément sur toute la surface
     libre atteignable ;
   - cette répartition est multipliée par la vraisemblance tolérante de la position du système
     (jamais une intersection stricte) ;
   - aucun pas et aucun écart de pression : la zone ne bouge pas ;
   - si D' couvre presque tout l'étage (au-delà d'environ 60 m dans le MB) ou si l'absence
     dépasse 2 heures, on passe au cas 3.
3. **Départ à froid.** Tout l'espace libre de l'étage deviné (§6.5), pondéré par la position du
   système. **Zone GPS (ton plan) :** sur un très grand étage, on ne place des hypothèses que
   dans un disque de rayon « précision annoncée × 2, au moins 30 m » autour de la position du
   système, plus 10 % ailleurs sur l'étage au cas où elle se tromperait. C'est ce qui garde le
   nombre d'hypothèses raisonnable. Dans le MB (environ 1 000 m² libres par étage), tout l'étage
   tient dans le budget.
4. **Raccourci facultatif.** « Je suis à la salle X » : la zone devient la salle X et ses portes.
   On garde `setStartPose`, mais comme indice et non plus comme certitude.

### 6.5 Étage

- **Pendant un trajet :** transitions détectées par le baromètre (existant). Les études
  publiées donnent environ 99,5 % de réussite pour détecter un changement d'un étage.
- **Au retour d'une veille :** écart de pression depuis la dernière mesure. C'est le même
  téléphone, donc son décalage propre s'annule ; il reste la météo. Environ 0,47 hPa par étage
  de 4 m. Au-delà d'environ 20 minutes d'absence, la confiance baisse avec le temps (correction
  par une station météo : plus tard). Sur iPhone, les étages montés ou descendus à pied viennent
  en complément.
- **Chaîne de l'option C** quand on n'a rien de récent :
  1. le dernier étage connu, s'il est récent ;
  2. le baromètre recalé : chaque fois que l'étage est certain, l'app mémorise le décalage de ce
     téléphone, ce qui permet ensuite d'en déduire l'étage absolu (idée à valider : la météo et
     la ventilation du bâtiment peuvent fausser) ;
  3. l'altitude donnée par le système (faible : 5 à 7 m d'erreur moyenne) ;
  4. le rez-de-chaussée.
- **Affichage :** « Étage N ? » tant que la confiance est sous 90 %, corrigeable d'un tap.
- **Confiance faible :** les particules se répartissent sur les deux étages les plus probables.
- **Sans baromètre :** chaîne C sans le point 2. Les changements d'étage ne viennent que des
  escaliers ou ascenseurs traversés et des corrections de l'utilisateur.

### 6.6 Modes de port (en main, en poche, dans un sac, en appel)

- **Détection d'un changement de port :** la gravité change de direction dans le repère du
  téléphone (plus de 45°, puis stable), sans pas pendant le changement ; sur Android, le capteur
  de proximité en appoint.
- **À chaque changement :** l'angle téléphone→marche de toutes les particules redevient incertain
  et se réapprend.
- **Hors de la main :** le cap d'un pas est la moyenne sur un cycle de marche complet (2 pas,
  environ 1 s) au lieu de 150 ms, pour annuler le balancement de la cuisse ou du bras.
- **Limite :** un téléphone qui change souvent de position ralentit la convergence.

### 6.7 Arrière-plan

**Veille** (aucun trajet en cours) :
- En passant en arrière-plan, on enregistre sur le disque l'heure, la pression, l'étage et sa
  confiance, et les trois zones les plus probables (centre, rayon, poids), même si la position
  n'était pas encore trouvée. Puis on coupe tous les capteurs. Le bilan marche donc même si le système
  a fermé l'app entre-temps.
- **Android :** abonnement unique à l'API Recording de Google (pas et distance en continu, sur
  l'appareil, gardés 10 jours, permission « activité physique »). Sans Google Play services, il
  n'y a pas d'historique : la zone de retour est plus large.
- **iPhone :** rien à faire ; l'historique du podomètre et des activités (7 jours) existe déjà.
- **Au retour :** bilan, puis propagation (§6.4), puis le filtre reprend.
- **Coût :** aucun réveil.

**Trajet actif** (de « Démarrer » jusqu'à 2 minutes après l'arrivée, ou jusqu'à l'arrêt par
l'utilisateur ; arrivée = à moins de 2,5 m de la porte de destination) :
- **Android :** service de premier plan de type « localisation », démarré depuis l'écran, avec
  une notification « Navigation vers X » et un bouton Arrêter.
- **iPhone :** mode d'arrière-plan « localisation » et session d'arrière-plan (iOS 17+), lancés
  pendant que l'app est ouverte ; la pastille bleue s'affiche.
- **Économies :**
  - rien à dessiner écran éteint (l'écran et le GPS sont ce qui consomme et chauffe le plus) ;
  - mesures regroupées par la puce capteurs, avec un réveil du processeur toutes les 1 à 2 s
    (compatible avec le tampon d'orientation de 2,5 s) ;
  - capteur d'orientation **jamais coupé pendant un trajet**, parce que le rallumer remet son
    repère à zéro ; fréquence réduite à environ 50 Hz ;
  - baromètre à 1 mesure par seconde ;
  - position du système en basse précision (Wi-Fi et antennes) toutes les 30 à 60 s, jamais le
    GPS à pleine puissance à l'intérieur.
- **Chaleur :** si le téléphone signale qu'il chauffe, on réduit (25 Hz, position toutes les
  60 s, moitié moins de particules).

### 6.8 Longueur du pas

Valeur de départ : le pas calibré si l'utilisateur l'a mesuré, sinon la foulée personnelle
fournie par le système (iPhone : podomètre ; Android : distance ÷ pas de l'API Recording),
sinon 0,74 m. Le filtre l'affine ensuite (Zee arrive à environ 5 cm d'erreur). La calibration
sur 5 m devient facultative.

### 6.9 Affichage et guidage

- **Recherche / Approximatif :** cercle(s) translucide(s). Si deux zones restent possibles, les
  deux sont montrées ; seule la principale porte l'itinéraire.
- **Itinéraire :** calculé depuis le centre de la zone principale, ramené sur le graphe ;
  recalculé quand ce centre bouge de plus de 3 m ou quand la zone principale change.
- **Trouvé :** point et cône de direction. **Le tracé n'apparaît qu'une fois la position
  trouvée**, redessiné à partir des hypothèses qui ont survécu (le tracé brut du PDR, lui, n'est
  pas modifié).
- **Mouvement du point :** il glisse en douceur, sauf quand l'estimation change de zone ; il
  saute alors, avec une animation, plutôt que de glisser à travers les murs.
- **Étage :** pastille « Étage 2 ? » tant que la confiance est sous 90 %.
- **Branchement au backend :** quand le filtre est actif, `mapPositionM` et `mapHeadingRad` du
  `NavigationSnapshot` viennent du filtre, et le snapshot publie aussi les zones, l'état et la
  confiance de l'étage. L'ancien mode « point de départ choisi à la main » reste disponible dans
  l'écran développeur, pour comparer.
- **Écran développeur :** particules, zones, étage, état, coût par pas.

### 6.10 Android et iOS

Le cœur pur est porté 1:1, comme aujourd'hui. La parité est garantie par des **traces de
référence partagées** : des sessions enregistrées, rejouées dans les tests Kotlin et Swift, qui
doivent donner les mêmes résultats à une tolérance près. Passer à un code partagé (Kotlin
Multiplatform) est une option pour plus tard, pas pour la v1.

## 7. Ajouter un bâtiment

- **Nécessaire :** un plan de chaque étage (image ou vecteur) aligné et géoréférencé, la hauteur
  des étages et le rez-de-chaussée. À partir de là, on produit la carte précise (§6.2) et le
  graphe.
- **Chaîne de production :**
  1. **Automatique :** retirer le texte (petites formes), garder les murs, fermer la façade,
     découper les salles, calculer la carte des directions.
  2. **À la main, dans l'éditeur** (l'éditeur de graphe existant, `floor-plans/MB/editor.html`,
     étendu) : ouvrir chaque porte d'un clic, marquer le mobilier comme franchissable, nommer
     les salles qui n'ont pas reçu leur numéro, recaler le graphe sur les murs.
  3. **Contrôles automatiques** avant publication : aucune arête du graphe ne traverse un mur,
     chaque nœud « porte » est dans une ouverture, chaque salle est atteignable depuis un
     couloir, la façade est fermée.
- **Pas nécessaire :** relevé Wi-Fi ou magnétique, balises, intervention du gestionnaire.
- **Plus tard :** détecter automatiquement les arcs de porte ; importer directement les
  bâtiments déjà décrits en IMDF ou dans OpenStreetMap.

### 7.1 Essai d'extraction automatique sur le MB (2026-11-17)

Code jetable, hors du dépôt. Moins d'une seconde par étage, grille de 0,1 m.

| Constat | Résultat |
|---|---|
| Le SVG | une image redessinée : 1 928 formes pleines sur le MB-2 |
| Texte retiré (formes de moins de 75 cm) | 1 307 formes ; les murs restent propres |
| Façade | trouée ; fermée automatiquement en bouchant les ouvertures de moins de 1,6 m |
| Nœuds de couloir | à 0,6–1 m du mur le plus proche en médiane : bien centrés |
| Carte des directions, étages 2 à 6 | axe net sur 91–99 % des points de couloir ; écart médian ≈ 1°, 90 % sous 7–11° |
| Carte des directions, étages 1 et 7 | 79 % de points nets ; 10 % des écarts au-delà de 42° (graphe mal aligné, grands halls) |
| Salles découpées | 255 numéros de salle sur 300 (85 %) tombent dans une région fermée |
| Portes | dessinées fermées (battant + arc) : les salles sont scellées |
| Ouvrir les portes aux nœuds du graphe | insuffisant : 4 à 19 nœuds « porte » sont dans un mur selon l'étage, 5 à 18 liaisons porte–couloir traversent encore un mur |
| Graphe vs murs | 6 à 18 arêtes de couloir traversent un mur selon l'étage |
| Mobilier | dessiné comme des murs ; les rangées de sièges coupent les amphithéâtres en bandes |

Conclusion : la partie automatique couvre l'essentiel ; il reste une passe manuelle d'environ
1 à 2 heures par étage (estimation grossière), surtout pour les portes.

## 8. Limites connues

1. **Précision :** environ 1 à 2 m une fois la position trouvée ; avant, une zone de quelques
   mètres à environ 50 m.
2. **Il faut marcher, avec des virages.** À l'arrêt, ou dans un long couloir droit d'un étage
   symétrique, deux zones peuvent rester possibles un moment.
3. **Dans une grande salle**, la position se précise moins vite que dans un couloir : il y a peu
   de murs et pas de direction probable. Elle se précise dès qu'on en sort.
4. **La position du système à l'intérieur est grossière** (médiane d'environ 8 m sur Android et
   23 m sur iPhone dans l'étude du NIST de 2019) et parfois biaisée.
5. **Sans baromètre** (fréquent sur Android), l'étage repose sur la chaîne C et sur
   l'utilisateur. Un ascenseur pris hors trajet n'est vu que par l'écart de pression au retour,
   donc pas du tout sans baromètre.
6. **Une erreur de carte** (porte oubliée, mur en trop) rend la vraie position « impossible » :
   le filtre passe en mode secours et le cercle grandit. Les travaux, les portes verrouillées et
   le mobilier déplacé ne sont pas connus. La qualité de la carte compte donc beaucoup : d'où les
   contrôles automatiques (§7).
7. **En poche ou dans un sac :** convergence plus lente et un peu moins précise.
8. **Arrière-plan :** certains Android (Xiaomi, Huawei, parfois Samsung) arrêtent quand même le
   service ; des développeurs signalent des coupures sur les iOS récents. À tester tôt.
9. **Batterie :** la veille ne coûte rien ; un trajet actif coûte (capteurs et position basse
   précision), à mesurer.
10. **Boussole peu fiable à l'intérieur :** jamais utilisée pour la position.
11. **Escaliers mécaniques, rampes et tunnels entre bâtiments** ne sont pas dans le graphe v1.
12. **Confidentialité :** tout reste sur le téléphone. La seule requête externe éventuelle
    (météo, plus tard) ne contiendrait que la position du bâtiment.
13. **Carte des directions :** une personne qui traverse un hall en diagonale, ou qui va vers une
    fontaine ou un banc, est légèrement pénalisée. D'où un facteur faible et borné, neutre dans
    les zones ouvertes.
14. **Coût d'un nouveau bâtiment :** une passe manuelle par étage (estimée à 1–2 h dans le MB),
    tant que la détection automatique des portes n'existe pas.

## 9. Vérification

- **Enregistreur de session** (écran développeur) : tous les événements des capteurs, positions
  du système, pressions et pas dans un fichier, avec des « points de contrôle » tapés par le
  testeur (« je suis à la porte MB-2.255 ») qui ne servent qu'à l'évaluation.
- **Rejeu :** les sessions passent dans le moteur en tests JVM (puis XCTest), qui calculent les
  mesures ci-dessous.
- **Réglage de la carte des directions :** le facteur β (§6.2 bis) est choisi en rejouant les
  sessions avec plusieurs valeurs, y compris 0, ce qui donne le gain réel de l'idée.
- **Qualité de la carte :** les contrôles automatiques du §7 doivent tous passer sur les 7
  étages du MB.
- **Protocole :** au moins 20 trajets sur au moins 3 étages du MB ; départs dans un couloir,
  devant une porte, dans une salle, en sortie d'ascenseur ; en main, en poche, dans un sac ; au
  moins un Android avec baromètre, un sans si possible, et un iPhone.
- **Cibles (mes propositions) :**

| Mesure | Cible |
|---|---|
| Distance marchée avant « Trouvé », avec position du système | médiane ≤ 25 m |
| Idem, sans position du système | médiane ≤ 50 m |
| Erreur une fois trouvé, en main | médiane ≤ 1,5 m ; 90 % ≤ 3 m |
| Erreur une fois trouvé, en poche ou dans un sac | médiane ≤ 2,5 m ; 90 % ≤ 5 m |
| Bonne porte à l'arrivée | ≥ 90 % |
| Bon étage (avec baromètre, après la première certitude) | ≥ 95 % |
| « Trouvé » au mauvais endroit (plus de 5 m d'erreur) | ≤ 5 % des trajets |
| Batterie pendant un trajet écran éteint | ≤ 5 % par heure |
| Veille | aucun réveil ajouté |
| Chaleur sur 30 minutes de trajet | pas au-delà de l'état « léger » signalé par le système |

## 10. Étapes de réalisation

0. Enregistreur, rejeu et calcul des mesures (Android), avec un format de fichier commun aux
   deux plateformes.
1. Carte précise : chaîne automatique (en partant de l'essai du §7.1), extension de l'éditeur
   (ouvrir les portes, marquer le mobilier, recaler le graphe), contrôles automatiques ; carte
   du MB-2 d'abord, puis des 6 autres étages.
2. Filtre (pur Kotlin) avec la carte et la carte des directions ; tests synthétiques (couloir en
   L, coin réel du MB-2) ; premier plan seulement, étage connu.
3. Zone de départ : position du système (zone GPS), propagation depuis la dernière zone, entrée
   depuis la rue, raccourci salle ; affichage des cercles ; itinéraire depuis la zone principale.
4. Étage : écart de pression au retour, chaîne C, apprentissage du décalage du baromètre,
   particules sur deux étages, passages par escaliers et ascenseurs.
5. Téléphone en poche ou dans un sac.
6. Arrière-plan Android : veille (API Recording), service de trajet, économies ; mesures de
   batterie et de chaleur.
7. Portage Swift 1:1, traces de référence partagées, arrière-plan iOS.

Plus tard : correction météo, Wi-Fi Android appris automatiquement, plaques de porte, consignes
en arrière-plan, autres bâtiments, code partagé.

## 11. Décisions

**Les tiennes :** pas d'infrastructure ni de relevé ; le plan et les capteurs du téléphone ;
Android et iPhone ; départ n'importe où ; le plan trouve la position pendant la marche, aidé par
les indices du téléphone ; cercle qui rétrécit et itinéraire lancé tout de suite ; arrière-plan
économe en batterie et en chaleur ; téléphone en poche dès la v1 ; propagation de la distance
marchée croisée avec le GPS ; carte précise des murs, des salles et des portes ; zone probable
grâce au GPS ; carte des directions probables ; élimination à la Zee ; confiance plus forte quand
la personne suit la direction probable.

**Les miennes (à vérifier) :**
- filtre à particules plutôt qu'une grille ;
- option C pour l'étage ;
- position du système comme seule source Wi-Fi ;
- boussole comme simple indice ;
- corrections du PDR liées au plan coupées quand le filtre tourne ;
- tracé affiché seulement une fois la position trouvée ;
- cibles chiffrées du §9 ;
- pas de consignes en arrière-plan en v1 ;
- trajet actif jusqu'à 2 minutes après l'arrivée ;
- portage 1:1 plutôt que code partagé ;
- caméra hors v1 ;
- carte au format inspiré d'IMDF ;
- carte des directions calculée depuis les murs seuls ;
- la zone GPS pondère, elle ne coupe jamais ;
- le score de confiance entre dans le poids des hypothèses (pas de bonus sur la confiance
  affichée) et sert de signal d'alarme ;
- la première ligne droite fixe l'angle de départ ;
- pas d'itinéraire comme direction probable.

## 12. Laissé au développeur

Taille des cellules de propagation et nombre exact de particules, valeurs de bruit, méthode de
regroupement des particules, réglage fin des seuils (dans le respect des cibles), format des
fichiers d'enregistrement, structure du code (nouveau paquet pur `com.hackdecouverte.loc`, miroir
`ios/Lync/Navigation/Loc/`), animations.

## 13. Risques à mesurer tôt

- **Coupures d'arrière-plan** sur les iOS récents et sur les Android agressifs : faire un essai
  jetable dès le début, avant l'étape 5.
- **Salles sans géométrie intérieure :** l'approximation est peut-être trop grossière pour les
  grands amphithéâtres ; utiliser les murs des SVG plus tard si besoin.
- **Ventilation du MB :** mesurer la pression dehors et dedans aux entrées pendant la
  vérification.
- **Distance de l'API Recording :** mesurer sa qualité.
- **Coût du filtre sur un Android d'entrée de gamme :** mesurer dès l'étape 1.
- **AGENTS.md** dit aujourd'hui « Foreground only » : à mettre à jour quand l'arrière-plan
  arrive.
- **Temps de la passe manuelle sur la carte :** le mesurer sur le MB-2 avant de faire les 6
  autres étages.
- **Étages 1 et 7 :** le graphe y est nettement moins bien aligné sur les murs (§7.1) ; le
  recaler en premier.

## Sources

- Zee, Microsoft Research (MobiCom 2012) :
  https://www.microsoft.com/en-us/research/publication/zee-zero-effort-crowdsourcing-for-indoor-localization
- MapCraft, Oxford (IPSN 2014) : https://www.cs.ox.ac.uk/files/6677/MapCraftPublishedIPSN14.pdf
- NIST, précision intérieure de la position Android et iPhone (WCNC 2019) :
  https://www.nist.gov/document/indoor-localization-accuracy-major-smartphone-location-apps
- Barometric Phone Sensors – More Hype Than Hope! (HotMobile 2014) :
  https://microsoft.com/en-us/research/wp-content/uploads/2016/02/hotmobile14b.pdf
- Apple TN3111, API Wi-Fi sur iOS :
  https://developer.apple.com/documentation/technotes/tn3111-ios-wifi-api-overview.md
- Apple, CMPedometerData : https://developer.apple.com/documentation/coremotion/cmpedometerdata.md
- Android, Recording API on mobile :
  https://developer.android.com/health-and-fitness/guides/recording-api
- Android, regroupement des mesures capteurs :
  https://source.android.com/docs/core/interaction/sensors/batching
- Apple Developer Forums, coupures de localisation en arrière-plan sur iOS 27 :
  https://developer.apple.com/forums/thread/844579
- Résultats IPIN 2020 (catégorie smartphone) :
  https://evaal.isti.cnr.it/fileadmin/assets/Previous_competition/2020/Presentation/t3-2020-track.pdf
