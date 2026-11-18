# Protocole des marches d'entraînement

Trois marches de 5 minutes suffisent pour entraîner et valider les réglages, à condition que chacune
couvre ce que les autres ne couvrent pas. Le Pixel 9 a servi pour la première marche : garder le même
téléphone, ou le noter dans le nom de la marche.

## Avant chaque marche

- « Limitation de la recherche Wi-Fi » désactivée (Options pour les développeurs).
- Localisation activée.
- App installée depuis la branche `lync-algorythme`.
- Noter le nom de la marche : il s'affiche après « Arrêter ».

## W1 : en main, puis en poche (5 min)

1. Départ debout dans le couloir ; toucher la carte à l'endroit où tu es (repère).
2. Marcher jusqu'au bout sud-est du couloir, téléphone en main ; repère au bout.
3. Mettre le téléphone dans la poche (étiquette « Poche »).
4. Revenir jusqu'au bout nord-ouest et faire demi-tour (étiquette « Virage »).
5. Revenir au départ.

## W2 : salles et pivots (5 min), téléphone en poche

1. Entrer dans 3 salles différentes. Si tu sors le téléphone, étiquette « Porte » en entrant ; sinon,
   libellé `porte` sur ce point dans le tracé.
2. Deux pivots volontaires sur place (libellé `pivot` dans le tracé).
3. Finir au point de départ.

## W3 : départ à froid dans une salle, main qui balance, écran éteint (5 min)

1. Ouvrir l'app assis dans une salle.
2. Marcher main qui balance (étiquette), puis téléphone en poche, écran éteint.
3. Prendre un parcours différent de W1 et de W2.

## Après chaque marche

1. `tools/pull-walks.sh` : récupère la marche dans `recordings/` (ignoré par git : bornes Wi-Fi, trajets).
2. Tracer le trajet dans `tools/trace-STAN-1.html`, avec au moins 3 instants (lus dans `report.txt` ou
   au chronomètre).
3. « Télécharger » → enregistrer sous `recordings/<nom>.truth.json`.

## Contrôle

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest --tests '*RecordingReplayTest*'
```

La marche doit être listée, et `app/build/replay/<nom>/analysis.txt` doit montrer une vérité recalée
(« pseudo truth vs markers » ou le recalage du tracé dans `truth-eval.txt`), sans erreur.

## Entraîner puis accepter

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest --tests '*TrainModel*' -Dtrain=true
```

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:testDebugUnitTest --tests '*AcceptanceTest*' -Daccept=true
```

Le rapport est écrit dans `docs/training/`. Si la porte dit REJECT, la marche tenue à l'écart qui
empire dit quel type de marche manque : en refaire une de ce type et relancer.
