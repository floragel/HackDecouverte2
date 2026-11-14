# Lync — Charte graphique

> L'app compagnon des étudiants : rencontrer ses camarades, retrouver ses amis, vivre son campus.
> Ce document décrit **tous les éléments visuels et la logique graphique** de Lync.
> Il fait foi pour Android (Kotlin / Compose) et iOS (Swift / SwiftUI) : les deux apps doivent être identiques.

Fichiers sources :
- Logo, icône, photo, polices : dossier [`design/`](.)
- Valeurs dans le code Android : `app/src/main/java/com/lync/app/ui/theme/Tokens.kt`, `Type.kt`, `ui/motion/`

---

## 1. L'idée

**Lync** = *link* + *sync* : relier les gens d'un même campus.

Trois principes guident tout le design :

1. **Neutre, sauf une couleur.** L'interface est en noir, blanc et gris bleutés. La seule couleur vive est **celle de l'université** de l'étudiant. Quand un étudiant choisit Concordia, toute l'app passe au bordeaux Concordia ; une autre université aura ses propres couleurs.
2. **Une seule chose spectaculaire : la carte Lync.** La carte étudiante holographique est l'élément signature. Tout le reste est calme, épuré, discret.
3. **Tout répond au doigt.** Chaque élément touchable réagit (léger rebond, vibration). Pas d'animation décorative qui bouge toute seule.

Cible : étudiants de 16 à 24 ans. Ton : simple, direct, chaleureux, jamais corporate.

---

## 2. Le logo

### Le symbole
Deux maillons de chaîne arrondis qui s'emboîtent pour former un **L**. Ils représentent deux personnes qui se connectent.

- Fichier vectoriel : [`lync-mark.svg`](lync-mark.svg) (Android : `res/drawable/ic_lync_mark.xml`)
- Il est composé de **deux formes séparées** (le maillon vertical et le maillon horizontal) : c'est ce qui permet de l'animer (les maillons se séparent puis s'emboîtent).
- Grille : carré de 1024 × 1024, symbole centré.

### Le logotype
Symbole + le mot **« Lync »** en Bricolage Grotesque Bold, espacement 6 dp entre les deux.

| Variante | Usage |
|---|---|
| Symbole + « Lync » couleur encre (`#1A1D29`) | en-tête de l'accueil, documents clairs |
| Symbole + « Lync » blanc | sur photo, sur fond bleu nuit |
| Symbole seul, couleur de l'université | dos de la carte Lync |
| Symbole seul, blanc 8 % d'opacité, très grand | filigrane sur la carte Lync |

### Icône d'application
- Fichier : [`app-icon.svg`](app-icon.svg)
- Symbole **blanc** sur fond **bleu nuit `#1A1D29`**, symbole à ~62 % de la largeur.
- Android : icône adaptative + version monochrome (thème d'icônes Android 13).

### Règles
- Taille minimum : 16 px (le symbole reste lisible).
- Zone de protection : la moitié de la largeur d'un maillon tout autour.
- Interdit : le déformer, le mettre en dégradé, ajouter une ombre, le contour seul, le faire pivoter, changer l'écart entre les maillons (sauf dans l'animation).

---

## 3. Les couleurs

### Couleurs de l'interface

| Rôle | Nom | Clair | Sombre | Usage |
|---|---|---|---|---|
| Fond | Paper | `#FFFFFF` | `#10121A` | fond de tous les écrans |
| Surface | Mist | `#F3F4F7` | `#1A1D29` | champs, puces, tuiles, cartes de sondage/plan |
| Surface 2 | Fog | `#E8EAF0` | `#252938` | boutons secondaires, piste des sélecteurs |
| Trait | Line | `#E3E5EB` | `#2A2E3D` | séparateurs fins (0,5 dp), contours |
| Texte | Night | `#1A1D29` | `#FFFFFF` | texte principal, icônes |
| Texte secondaire | Slate | `#6B7081` | `#9398A8` | légendes, dates, descriptions |

> Le noir de Lync n'est pas noir : c'est un **bleu nuit `#1A1D29`**. C'est la couleur de marque (icône, écran de lancement, texte).

### Couleur d'accent = l'université

| Rôle | Concordia | Logique |
|---|---|---|
| `accent` | `#912338` (bordeaux) | boutons principaux, likes, liens, anneau des stories, pins « amis » |
| `accent2` | `#D4A12A` (or) | pins « camarades de cours » sur la carte |
| `accentSoft` | accent à 9 % (clair) / 20 % (sombre) | fonds légers : puce « 3 cours en commun », tuile sélectionnée |
| `onAccent` | blanc | texte posé sur l'accent. Si l'accent est clair (luminance > 50 %), on passe en `#1A1D29` |

Les couleurs viennent de la base de données (`universities.primary_color` / `secondary_color`). Le changement de couleur est **animé en 600 ms** quand on choisit son université.

### Couleurs spéciales

| Élément | Couleurs |
|---|---|
| Scanner, code détecté | vert `#2FD07A` |
| Erreur, « Se déconnecter » | rouge du système (Material `error`) |
| Film holographique de la carte | `#FF6EC7` rose, `#7DF9FF` cyan, `#B388FF` lavande, `#FFF07D` jaune, `#7DFFB2` menthe |

### Dégradés des publications texte et des stories
Toujours en diagonale (haut-gauche vers bas-droite), texte blanc dessus.

| # | Nom | De | À |
|---|---|---|---|
| 0 | Night | `#1A1D29` | `#3A3F55` |
| 1 | Sunset | `#FF6B6B` | `#FFB86B` |
| 2 | Dusk | `#5B8CFF` | `#9B6BFF` |
| 3 | Mint | `#1FB58F` | `#9BE15D` |
| 4 | Honey | `#FFD166` | `#FF9F1C` |

---

## 4. La typographie

Deux polices, **incluses dans l'app** (pas celles du système), pour un rendu identique sur Android et iPhone. Les deux sont gratuites (licence OFL), fichiers dans [`fonts/`](fonts/).

- **Bricolage Grotesque** : la voix de Lync. Titres, noms, chiffres, le mot « Lync ». Réglages : largeur 96, taille optique adaptée au corps.
- **Figtree** : tout ce qu'on lit ou touche (texte, boutons, légendes).

| Style | Police | Taille / interligne | Graisse | Approche | Exemples |
|---|---|---|---|---|---|
| Display L | Bricolage | 44 / 44 | 800 | −1,4 | « Your campus, in your pocket. » |
| Display S | Bricolage | 34 / 36 | 700 | −0,9 | titres d'écran : « Network », titre d'un événement |
| Headline | Bricolage | 28 / 32 | 700 | −0,6 | chiffres du profil, mot « Lync », nom dans la célébration |
| Title L | Bricolage | 21 / 26 | 600 | −0,3 | titres de section, question d'un sondage, @pseudo |
| Title M | Figtree | 16 / 21 | 600 | 0 | texte des gros boutons |
| Title S | Figtree | 14 / 19 | 600 | 0 | noms dans les listes, options |
| Body L | Figtree | 16 / 23 | 400 | 0 | descriptions, champs |
| Body M | Figtree | 14 / 20 | 400 | 0 | légendes de posts, commentaires |
| Body S | Figtree | 12 / 16 | 400 | 0 | dates, programmes, compteurs |
| Label L | Figtree | 14 / 18 | 600 | 0 | texte des pilules (boutons arrondis) |
| Label M | Figtree | 12 / 16 | 500 | 0 | étiquettes, « Tu es ici » |
| Label S | Figtree | 11 / 13 | 600 | 0 | onglets du bas, badge « Démo » |

Règles :
- **Jamais de majuscules partout** (pas de « EVENTS », « STUDENT »). Toujours en casse de phrase.
- Pas de mot isolé en gras ou en couleur dans un titre.
- Titres **alignés à gauche**. Centré seulement pour : texte sur dégradé, écran de célébration, scanner.

---

## 5. Espacements et grille

- Grille de **4 dp** : 4 · 8 · 12 · 16 · 24 · 32.
- **Marge latérale de tous les écrans : 20 dp.**
- Entre une section et son titre : 22 dp au-dessus, 10 dp en dessous.
- Listes horizontales (stories, clubs, camarades) : 10 à 14 dp entre les éléments, et elles **débordent du bord droit** pour montrer qu'on peut faire défiler.

## 6. Arrondis

L'arrondi dépend de l'importance de l'élément : on n'utilise pas le même arrondi partout.

| Élément | Rayon |
|---|---|
| Carte Lync, feuilles du bas | **28 dp** |
| Photos, cartes de sondage, de plan, d'événement, tuiles de camarades | **22 dp** |
| Champs, boutons principaux, tuiles, groupes de réglages | **16 dp** |
| Petites tuiles (date d'un événement, emoji de groupe) | 12 à 18 dp |
| Pilules, puces, avatars, boutons ronds | **complètement rond** |

## 7. Ombres et profondeur

- **Presque pas d'ombres** : la profondeur vient des surfaces (`Mist` sur `Paper`).
- Ombres seulement sur ce qui **flotte au-dessus d'une carte géographique** (boutons et puces de la carte : 6 à 8 dp), sur les **pins** (6 dp) et sur la **carte Lync** (24 à 28 dp).

---

## 8. Iconographie

- Jeu d'icônes : **Material Symbols**. Version *Outlined* par défaut, version *Rounded pleine* pour l'état actif (onglet sélectionné, cœur liké).
- Tailles : 26 dp (barre d'onglets, cœur), 24 dp (actions), 20 dp (dans un bouton rond), 16 dp (dans une pilule).
- Couleur : `Night` / `Slate`. L'accent seulement pour un état actif ou important (cœur liké, badge vérifié, lieu d'un plan).
- iOS : équivalents **SF Symbols** (`heart`, `heart.fill`, `bubble.right`, `square.and.arrow.up`, `qrcode.viewfinder`…).

### Emojis (font partie du système visuel)

| Statuts | Activités de plan | Catégories d'événements |
|---|---|---|
| ☕ Partant·e pour un café | 🏋️ Gym · 📚 Biblio · ☕ Café · 🍜 Dîner | 🎉 Social · 🎓 Études |
| 📚 Cherche un partenaire d'étude | ✏️ Étude · ⚽ Sport · 🎉 Party · ✨ Autre | 🏀 Sport · 💼 Carrière |
| 🍜 Je mange à la cafét | | 🎭 Culture · 💻 Tech |
| 🎧 Occupé·e, en cours | | |

---

## 9. Images

- **Photo d'accueil** : [`onboarding_hero.jpg`](onboarding_hero.jpg). Style photo argentique 35 mm, lumière dorée d'automne, étudiants qui rient, vrai campus montréalais, ciel calme en haut pour le texte. C'est la direction pour toutes les photos de com.
- Sur une photo, le texte blanc est toujours posé sur un **dégradé bleu nuit** (transparent en haut, 96 % en bas).
- **Photos des posts** : format **4:5**, rayon 22 dp, 12 dp de marge sur les côtés.
- **Avatars** : toujours ronds. Sans photo : initiales en couleur d'accent sur fond `accentSoft`.
  - Tailles : 24 (piles), 30 (participants), 38 (auteur d'un post), 48 (listes), 66 (stories), 84 (scanner), 104 (modifier le profil).
  - Anneau de story : 2 dp, accent si non vue, gris (`Line`) si vue.
- Pendant le chargement : bloc gris `Mist` (jamais de spinner sur une image).

---

## 10. Composants

### Boutons
| Composant | Spécifications |
|---|---|
| **Bouton principal** | pleine largeur, hauteur 54 dp, rayon 16, fond accent, texte Title M blanc. Désactivé : fond `Fog`, texte `Slate`. Sur photo : fond blanc, texte bleu nuit. |
| **Pilule pleine** | rond, padding 16 × 9 dp, fond accent, texte Label L. Action principale d'une ligne (« Connecter », « Je viens », « Rejoindre »). |
| **Pilule tonale** | fond `Fog`, texte `Night`. Action secondaire (« Modifier le profil », « En attente »). |
| **Pilule contour** | transparente, contour 1 dp `Line`. État « déjà fait » (« Connecté·e », « Abonné·e », « J'y vais ✓ »). |
| **Bouton rond** | 40 dp, fond `Mist`, icône 20 dp. Version pleine (fond bleu nuit, icône blanche) pour l'action phare d'un écran (le scanner). |

### Champs et sélecteurs
- **Champ** : fond `Mist`, rayon 16, padding 14 × 13 dp, texte Body L, indication en `Slate`. Pas de bordure.
- **Recherche** : hauteur 46, icône loupe 20 dp.
- **Sélecteur à onglets** (Personnes / Événements / Groupes) : piste ronde `Mist`, segment actif blanc avec texte `Night`.
- **Puces / tags** : rondes, fond `Mist`, padding 14 × 8, Label L.
- **Interrupteur** : piste bleu nuit quand activé, piste `Line` quand désactivé.
- **Choix unique (réglages)** : rond de 22 dp, plein bleu nuit avec ✓ blanc quand sélectionné.

### Structure des écrans
- **En-tête** : pas de barre du haut classique. Un **grand titre à gauche** (Display S, hauteur 64 dp) avec 1 ou 2 boutons ronds à droite.
- **Barre d'onglets** : 4 onglets (Accueil, Réseau, Carte, Profil), hauteur 60 dp, séparateur 0,5 dp au-dessus. Icône 26 dp + libellé Label S. Onglet actif : icône pleine, texte `Night`, et un **trait accent de 16 × 4 dp** dessous. Inactif : icône contour, `Slate`.
- **Feuilles du bas** (commentaires, programmes, créer un groupe) : fond `Paper`, rayon 28, titre Title L.

### Cartes de contenu

**Post photo** : avatar 38 + nom (Title S) + @pseudo (Body S `Slate`), date à droite. Photo 4:5. Actions : ♡ commentaire partager (zones de 44 dp). Puis « 48 j'aime » (Title S), légende avec le pseudo en gras.

**Post texte** : bloc dégradé (ratio 1,25), texte Bricolage 26 sp blanc, centré.

**Sondage** : carte `Mist` rayon 22, padding 18. Question en Title L. Options : barres de 46 dp, rayon 14, fond blanc, contour `Line`. Après le vote : barre de remplissage + pourcentage, l'option choisie a un contour accent et un ✓. En bas : « 112 votes, encore 19 h ».

**Plan** (« qui vient ? ») : carte `Mist` rayon 22. Tuile emoji 56 dp blanche (rayon 18) + activité (Title L) + horaire avec horloge. Lieu avec un pin accent. Pile d'avatars des participants (chevauchement −10 dp) + « 1 sur 4 » + bouton « Je viens » (pilule pleine) / « J'y vais ✓ » (contour).

**Événement (liste)** : tuile date 58 dp `Mist` (mois en accent Label M, jour en Headline), titre, horaire, lieu, amis qui y vont (mini-avatars 20 dp + texte accent), pilule « J'y vais ? ».

**Événement à la une** : carte photo 300 dp, ratio 1,35, dégradé noir en bas, étiquette « À la une » en verre dépoli blanc, titre blanc Title L.

**Groupe** : tuile emoji 52 dp `Mist` rayon 16 + nom + description + nombre de membres + « Rejoindre ». Page du groupe : tuile 76 dp rayon 24, nom en Display S.

**« Qui est là ? »** : sur l'accueil, bloc `Mist` rayon 22 avec une ligne par bâtiment (avatars + prénom + bâtiment en `Slate`). Sur la carte : cartes **inversées** (fond bleu nuit, texte blanc), 210 dp de large, « Tu es ici » ou « Qui est là ? » au-dessus du nom du bâtiment.

---

## 11. La carte Lync (élément signature)

C'est la carte étudiante numérique. Elle apparaît **en tête du profil** et **en plein écran dès qu'on tourne le téléphone**, depuis n'importe quel écran.

### Format
- **Ratio 1,586** (format d'une vraie carte bancaire / ID-1), rayon 28 dp.
- Toutes les mesures sont proportionnelles à la largeur : `k = largeur / 340`. La carte est donc identique en petit (profil) et en grand (plein écran).

### Recto
- Fond : **dégradé diagonal de l'accent** (couleur de l'université) vers l'accent assombri à 45 %.
- En haut à gauche : nom court de l'université (« Concordia ») en Bricolage 17k, blanc. En haut à droite : symbole Lync 20k.
- **Filigrane** : symbole Lync de 210k, blanc à 8 %, coupé dans le coin bas-droite.
- En bas : photo (70k, carré arrondi 16k dans un cadre blanc à 22 %), puis à droite : pilule de statut (verre blanc 18 %), **nom** en Bricolage 22k, programme et année en Figtree 13k (blanc 78 %).

### Verso
- Fond blanc. À gauche, le **code Lync** (voir section 12). À droite : université, « Scanne pour te connecter » (Bricolage 20k), « Ou colle vos téléphones dos à dos », @pseudo.

### Effet holographique (gyroscope)
Quand on penche le téléphone :
1. la carte pivote en 3D (jusqu'à ±14° sur un axe, ±12° sur l'autre) ;
2. un **film arc-en-ciel** (rose, cyan, lavande, jaune, menthe) glisse et tourne selon l'angle ;
3. un **reflet** blanc traverse la carte ;
4. des **étincelles** s'allument à certains angles seulement ;
5. le filigrane bouge dans le sens opposé (effet de profondeur).

On **touche la carte pour la retourner** (rotation 3D avec rebond).
En plein écran : fond bleu nuit `#10121A` avec un halo de la couleur de l'université derrière la carte.

---

## 12. Le code Lync

Notre propre code à scanner (ce n'est **pas un QR code**). Il contient le numéro unique de l'étudiant (32 bits).

```
        ┌──────────────── anneau extérieur plein (encre)
        │   ┌──────────── anneau de données 1 (32 segments)
        │   │   ┌──────── anneau de données 2 (32 segments)
        │   │   │   ┌──── symbole Lync au centre
        ◯   ◯   ◯   L
```

| Élément | Taille (en fraction du rayon) | Couleur |
|---|---|---|
| Anneau extérieur | de 0,90 à 1,00 | encre `#1A1D29` |
| Anneau de données 1 | rayon 0,76 | accent de l'université |
| Anneau de données 2 | rayon 0,58 | accent de l'université |
| Épaisseur des arcs | 0,12 | |
| « 1 » | arc arrondi (plusieurs « 1 » d'affilée se fondent en un seul arc) | |
| « 0 » | petit point de rayon 0,025 | |
| Symbole central | 42 % de la largeur | encre |

Il doit toujours être posé sur **fond blanc**, avec une marge blanche autour, sinon il n'est plus scannable. Ne jamais le redessiner à la main : il est généré par le code (`code/LyncCode.kt`).

---

## 13. La carte géographique

- Fond de carte **OpenFreeMap « Positron »** (clair) ou **« Dark »** (sombre) : gris très doux, pour que les avatars ressortent.
- **Pins** : avatar 47 dp dans un cercle blanc, anneau 2 dp **accent** pour un ami, **accent2 (or)** pour un camarade de cours. Petite pastille emoji du statut en haut à droite. Le pin sélectionné grossit (×1,18).
- **Moi** : point accent de 18 dp cerclé de blanc, avec une onde qui pulse. En mode fantôme, le point devient gris et l'onde s'arrête.
- Boutons flottants : pilules blanches avec ombre 8 dp. Le bouton « mode fantôme » s'inverse (fond bleu nuit) quand il est activé.

---

## 14. Le mouvement (motion design)

**Règle d'or : un seul moment spectaculaire, tout le reste répond à un geste.** Rien ne dure plus de 300 ms, sauf le moment signature. Si l'utilisateur a désactivé les animations sur son téléphone, tout s'affiche directement.

### Ressorts (même valeurs sur iOS)
| Nom | Valeur | Usage |
|---|---|---|
| Appui | amortissement 0,6, raideur 600 | tout ce qui se touche : léger rétrécissement à **96 %** |
| Standard | 0,8 / 400 | mouvements d'interface |
| Rebond | moyennement rebondissant / 380 | likes, avatars qui arrivent, scanner |
| Durées | 180 ms (fondus) · 280 ms (écrans) · 70 ms (décalage entre éléments) | |

### Les moments clés
| Moment | Ce qui se passe |
|---|---|
| **Lancement** | le symbole blanc sur bleu nuit (même place que l'écran de démarrage du système), les deux maillons s'écartent puis s'emboîtent avec un « clic », puis « Lync » apparaît. ~550 ms. |
| **Connexion (signature)** | les deux avatars arrivent des côtés, les deux maillons du logo s'emboîtent entre eux, un anneau éclate en couleur de l'université, double vibration, puis « Toi et Maya êtes connecté·e·s · 3 cours en commun ». ~1,5 s. |
| Scanner | le cercle respire doucement ; quand un code est lu, il se resserre et devient vert. |
| Transitions | la photo d'un événement s'agrandit en en-tête de sa page ; l'emoji d'un groupe glisse jusqu'à la page du groupe ; les écrans de détail arrivent par la droite, la publication par le bas. |
| Like | le cœur rebondit, le chiffre roule vers le haut ; double-tap sur une photo = gros cœur blanc qui éclate. |
| Sondage | les barres se remplissent l'une après l'autre. |
| « Je viens » | ton avatar « saute » dans la pile. |
| Carte Lync | retournement 3D au toucher, inclinaison et reflets en continu avec le gyroscope. |

### Vibrations
| Type | Quand |
|---|---|
| Tic léger | like, changement d'onglet, suivre, retourner la carte |
| Confirmation | voter, « Je viens », s'inscrire à un événement, rejoindre un groupe, se connecter |
| Succès (double) | uniquement quand deux personnes se connectent |

---

## 15. Le ton et les textes

- **Tutoiement**, phrases courtes, verbes simples. Bilingue : anglais par défaut, français complet.
- Écriture inclusive avec le point médian en français : « connecté·e », « participant·e·s ».
- Un bouton dit exactement ce qu'il fait : « Publier », « Je viens », « Rejoindre », « Scanne pour te connecter ».
- Écrans vides = une invitation à agir : « Ta première publication apparaîtra ici » + bouton.
- Erreurs : dire ce qui s'est passé et quoi faire, sans s'excuser.
- Exemples de voix : « Ton campus, dans ta poche. » · « Qui est là ? » · « Tes amis te voient » · « Tu es invisible ».

---

## 16. Mode sombre

Toutes les couleurs ont leur version sombre (section 3). L'accent de l'université reste le même ; seul `accentSoft` est plus présent (20 %). La carte Lync, le scanner, les stories et la célébration sont **toujours** sur fond sombre, quel que soit le mode.

## 17. Accessibilité

- Zones touchables de **44 dp minimum**.
- Contraste : texte principal `#1A1D29` sur blanc = 16:1 ; texte secondaire `#6B7081` sur blanc ≈ 5:1 (AA).
- Les tailles de texte suivent le réglage du téléphone.
- Toutes les icônes d'action ont une description pour les lecteurs d'écran.
- Respect du réglage « réduire les animations ».

---

## 18. À faire / à ne pas faire

| ✅ À faire | ❌ À ne pas faire |
|---|---|
| Une seule couleur vive : celle de l'université | Ajouter des couleurs « pour égayer » |
| Grands titres à gauche, beaucoup d'air | Barres du haut chargées, titres centrés partout |
| Des lignes simples dans les listes | Mettre chaque élément dans sa propre carte avec ombre |
| Arrondis selon l'importance (28 / 22 / 16 / rond) | Le même arrondi partout |
| Bricolage pour les titres, Figtree pour le texte | Police système, ou une 3ᵉ police |
| Casse de phrase | MAJUSCULES pour les étiquettes |
| Animations qui répondent à un geste | Éléments qui bougent tout seuls pour décorer |
| Code Lync sur fond blanc | Code Lync sur photo ou couleur |

---

## 19. Fichiers

| Fichier | Contenu |
|---|---|
| [`lync-mark.svg`](lync-mark.svg) | symbole du logo (vectoriel) |
| [`app-icon.svg`](app-icon.svg) | icône d'application 1024 × 1024 |
| [`onboarding_hero.jpg`](onboarding_hero.jpg) | photo d'accueil |
| [`fonts/bricolage.ttf`](fonts/bricolage.ttf), [`fonts/figtree.ttf`](fonts/figtree.ttf) | polices (variables, OFL) |
| [`README.md`](README.md) | version technique : correspondance Android ⇄ iOS de chaque composant |
