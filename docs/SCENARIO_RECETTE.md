# Scénario de recette — ce que l'application fait aujourd'hui

Parcours manuel de bout en bout des lots livrés : **import (étape 17)**, **profilage (18)**,
**règles et modèles (19)** et **tables de référence (20)**. Compter **45 minutes** la première fois.

Les jeux de données sont dans `docs/recette/`. Leurs anomalies sont connues et chaque étape donne le
résultat attendu : un écart est un défaut à signaler, pas une interprétation à faire.

- `assures.csv` — 12 lignes de données, anomalies plantées exprès
- `communes-v1.csv` et `communes-v2.csv` — deux versions d'un référentiel
- `entete-seule.csv` — fichier sans donnée, pour vérifier un refus

> **Numéros de ligne** : l'en-tête est la **ligne 1**, la première donnée la **ligne 2**, comme dans
> Excel. C'est la convention de l'application dans tous les messages d'anomalie.

## Les anomalies plantées dans `assures.csv`

| Ligne | Contenu remarquable | Ce qui doit être détecté |
|---|---|---|
| 4 | `sexe` vide | CMP-01 sur `sexe` |
| 5 et 6 | matricule `CI000004` en double | UNI-01 sur `matricule` (1 groupe, 2 lignes) |
| 7 | `sexe` = `X` | VAL-05 sur `sexe` (valeurs autorisées M; F) |
| 8 | `salaire` = `-15000` | VAL-04 sur `salaire` (minimum 0) |
| 9 | `date_naissance` = `12/12/2030` | ACT-02 sur `date_naissance` (date non future) |
| 12 | `salaire` vide, `sexe` = F | CMP-03 « si sexe = F alors salaire renseigné » |
| 3, 7, 10, 11, 13 | `commune` = `002` | INT-01 contre **communes v2**, où `002` n'existe plus |
| 8 | `commune` = `004` | INT-01 contre **communes v1**, où `004` n'existe pas encore |

## 0. Préparation

```bash
podman compose up -d postgres mailpit
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Ouvrir **http://localhost:8040/dq/**. Comptes de démonstration, mot de passe `Admin2026!` :
`admin` (ADMIN), `analyste` (ANALYST), `lecteur` (READER).

| # | Action | Attendu | Exigence |
|---|---|---|---|
| 0.1 | Ouvrir `/dq/` sans être connecté | Redirection vers la page de connexion | SEC-02 |
| 0.2 | Se connecter en `analyste` | Accueil, menu : Importer un fichier, Règles de qualité, Modèles d'analyse, Tables de référence | ERG-01 |
| 0.3 | Ouvrir `/dq/actuator/health` | `{"status":"UP"}` | EXP-01 |

## 1. Import d'un fichier (IMP-01 à IMP-09)

| # | Action | Attendu | Exigence |
|---|---|---|---|
| 1.1 | **Importer un fichier**, glisser `docs/recette/assures.csv` | Barre d'envoi, puis écran de paramètres | IMP-01 |
| 1.2 | Lire les paramètres proposés | Séparateur `;`, encodage `UTF-8`, première ligne = en-tête — **détectés, pas demandés** | IMP-07, IMP-03 |
| 1.3 | Regarder l'aperçu | Les 12 lignes avec 6 colonnes ; `KONE Aminata` et `YAO Désiré` lisibles, accents compris | IMP-04 |
| 1.4 | Lancer le chargement | Progression, puis « terminé » : **12 lignes**, 6 colonnes, 0 ligne incomplète | IMP-06 |
| 1.5 | Importer `entete-seule.csv` | Refus en français : le fichier ne contient que l'en-tête. Pas de page d'erreur technique | IMP-05, ERG-02 |
| 1.6 | Renommer un fichier quelconque en `.csv` (par exemple une image) et l'importer | Refus : le contenu ne correspond pas à l'extension (contrôle du type réel) | IMP-05 |
| 1.7 | Compresser `assures.csv` en `assures.zip` et l'importer | Accepté : l'archive à un seul fichier est extraite et chargée | IMP-09 |
| 1.8 | Ouvrir `assures.csv` dans Excel, enregistrer en `.xlsx`, l'importer | Accepté ; le choix de la feuille et de la ligne d'en-tête est proposé | IMP-02, IMP-08 |

## 2. Profil du fichier (PRF-01 à PRF-04)

À faire sur l'import de l'étape 1.4.

| # | Action | Attendu | Exigence |
|---|---|---|---|
| 2.1 | Ouvrir le profil du fichier chargé | Une ligne par colonne : remplissage, valeurs distinctes, longueurs, type probable | PRF-01 |
| 2.2 | Lire la ligne `sexe` | Remplissage **11 / 12** (une cellule vide, ligne 4) | PRF-01 |
| 2.3 | Lire la ligne `matricule` | 11 valeurs distinctes pour 12 lignes — le doublon se voit déjà | PRF-01 |
| 2.4 | Cliquer sur la ligne `commune` | Détail déplié sans rechargement de page : valeurs les plus fréquentes (`002` en tête) et motifs dominants | PRF-02, PRF-03 |
| 2.5 | Lire les règles suggérées | Au moins : UNI-01 sur `matricule`, CMP-01 sur les colonnes toujours remplies, VAL-03 sur les longueurs constantes | PRF-04 |
| 2.6 | Cliquer **Créer cette règle** sur la suggestion UNI-01 de `matricule` | Le formulaire s'ouvre **prérempli** : type UNI-01, colonne `matricule` | PRF-04, REG-01 |

## 3. Tables de référence (REF-01 à REF-03)

| # | Action | Attendu | Exigence |
|---|---|---|---|
| 3.1 | **Tables de référence** → nom `Communes`, fichier `communes-v1.csv`, charger | Aperçu de la **version 1** : 3 lignes, colonnes `code` et `nom` | REF-01 |
| 3.2 | Recharger avec le **même nom** `Communes` et `communes-v2.csv` | **Version 2** créée, 3 lignes | REF-02 |
| 3.3 | Ouvrir **Versions** | v2 marquée « Courante », v1 toujours là, chacune datée et signée | REF-02, TRA-02 |
| 3.4 | Ouvrir l'aperçu de la **v1** | `002 COCODY` **est présente** — la v1 n'a pas été modifiée par le second chargement | REF-02 |
| 3.5 | Ouvrir l'aperçu de la **v2** | `002 COCODY` **absente**, `004 MARCORY` présente | REF-01 |
| 3.6 | Charger un fichier sans donner de nom | Refus en français : « Donnez un nom au référentiel » | ERG-02 |

## 4. Règles de qualité (REG-01 à REG-06)

### 4.1 Formulaire généré et essai sur le fichier

| # | Action | Attendu | Exigence |
|---|---|---|---|
| 4.1.1 | **Règles de qualité** → **Nouvelle règle**, type **VAL-05** | Les champs changent : un seul paramètre, « Valeurs autorisées », avec son libellé français | REG-02 |
| 4.1.2 | Changer le type pour **VAL-04** | Les champs se rechargent : « Valeur minimale » et « Valeur maximale » — aucun rechargement complet de la page | REG-02 |
| 4.1.3 | Enregistrer un **VAL-04** sur `salaire` sans minimum ni maximum | Refus **avant enregistrement**, message lisible, saisie conservée à l'écran | REG-02, ERG-02 |
| 4.1.4 | Compléter : nom « Salaire positif », colonne `salaire`, minimum `0`, sévérité Majeure, propriétaire métier « Direction des prestations », puis **Essayer la règle** sur `assures.csv` | **11 contrôlées** (la ligne 12 est vide, donc hors contrôle), 10 conformes, **1 anomalie ligne 8** (`-15000`) | REG-03 |
| 4.1.5 | Enregistrer | Retour à la liste, règle numérotée `R-00xx`, version 1 | REG-01 |

### 4.2 Les autres règles à créer, avec leur résultat d'essai

Même parcours pour chacune. Le bouton **Essayer la règle** doit donner exactement ceci sur
`assures.csv` :

| Règle à créer | Paramètres | Essai attendu |
|---|---|---|
| CMP-01 sur `sexe` | aucun | 12 contrôlées, 11 conformes, 1 anomalie **ligne 4** |
| VAL-05 sur `sexe` | valeurs autorisées `M; F` | 11 contrôlées, 10 conformes, 1 anomalie **ligne 7** (`X`) |
| UNI-01 sur `matricule` | aucun | 12 contrôlées, 10 conformes, 2 anomalies **lignes 5 et 6**, 1 groupe de doublons |
| ACT-02 sur `date_naissance` | format `jj/mm/aaaa` | 12 contrôlées, 11 conformes, 1 anomalie **ligne 9** |
| CMP-03 sur `salaire` | condition : `sexe` **est égal à** `F` (texte) | 6 contrôlées, 5 conformes, 1 anomalie **ligne 12** |
| INT-01 sur `commune` | référence `Communes (v2)`, colonne de la référence `code` | 12 contrôlées, 7 conformes, **5 anomalies** lignes 3, 7, 10, 11, 13 |

> **Point de contrôle important (4.2.1)** : dupliquer la règle INT-01, pointer la **v1** à la place de
> la v2 et l'essayer → **1 seule anomalie, ligne 8** (`004`). La version du référentiel compte, et
> l'ancienne reste exploitable (REF-02, TRA-02).

> **Point de contrôle important (4.2.2)** : dans l'éditeur de condition du CMP-03, vérifier qu'on ne
> peut **rien saisir librement** : la colonne, la comparaison et le type se choisissent dans des
> listes. Aucune zone où écrire du SQL ou une formule (SEC-05).

### 4.3 Versions, liste et droits

| # | Action | Attendu | Exigence |
|---|---|---|---|
| 4.3.1 | Modifier « Salaire positif » : maximum `5000000`, **sans** motif de modification | Refus : le motif est obligatoire | REG-04 |
| 4.3.2 | Recommencer avec le motif « Ajout du plafond » | Enregistré ; la liste affiche **v2** | REG-04 |
| 4.3.3 | Ouvrir **Historique** de cette règle | **Deux versions** : v2 « Courante » et v1, avec auteur et motif. La v1 n'a pas disparu | REG-04, TRA-02 |
| 4.3.4 | Filtrer la liste par dimension, par propriétaire métier, par statut | La liste se restreint ; les compteurs suivent | REG-06 |
| 4.3.5 | **Désactiver** une règle, puis filtrer sur « Désactivées » | La règle y figure ; son historique n'a **pas** gagné de version | REG-06 |
| 4.3.6 | **Export / import** → Télécharger | Fichier `regles-dq-<date>.json` contenant les règles créées | REG-05 |
| 4.3.7 | Réimporter ce même fichier | Compte rendu : « Nouvelles versions : n », **aucun doublon** créé | REG-05, REG-04 |
| 4.3.8 | Modifier le JSON pour mettre un type inexistant sur une règle, réimporter | Cette règle est **refusée avec sa raison**, les autres passent | REG-05, ERG-02 |

## 5. Modèles d'analyse (MOD-01 à MOD-03)

| # | Action | Attendu | Exigence |
|---|---|---|---|
| 5.1 | **Modèles d'analyse** → **Nouveau modèle** : nom « Assurés — contrôles de base », colonnes attendues (une par ligne) `Matricule`, `Sexe`, `Date de naissance`, `Commune`, `Salaire`, `Numéro CNPS` | Enregistré, puis redirection vers l'association des colonnes | MOD-01 |
| 5.2 | Cocher les règles créées à l'étape 4 et régler les poids (Complétude 3, Validité 2) | Les règles retenues apparaissent dans le modèle | MOD-01 |
| 5.3 | Sur l'écran d'association, choisir le fichier `assures.csv` | `Matricule`→`matricule`, `Sexe`→`sexe`, `Date de naissance`→`date_naissance`, `Commune`→`commune`, `Salaire`→`salaire` **reconnus automatiquement** (accents, majuscules et séparateurs ignorés) | MOD-02 |
| 5.4 | Regarder `Numéro CNPS` | Marqué **« À choisir »** : aucune colonne du fichier ne lui correspond | MOD-02 |
| 5.5 | Associer `Numéro CNPS` à `matricule` à la main, enregistrer | Retour à la liste, association marquée « Enregistrée » | MOD-03 |
| 5.6 | Revenir sur l'écran d'association | Le choix manuel est **reproposé**, il n'est pas écrasé par la détection automatique | MOD-03 |

## 6. Droits (SEC-02) — à refaire en `lecteur`

Se déconnecter, se reconnecter en `lecteur` / `Admin2026!`.

| # | Action | Attendu |
|---|---|---|
| 6.1 | Menu | Pas d'entrée « Importer un fichier » |
| 6.2 | Règles de qualité | Liste et historique visibles ; **ni** « Nouvelle règle », **ni** Modifier, **ni** Activer/Désactiver |
| 6.3 | Ouvrir `/dq/regles/nouvelle` à la main dans la barre d'adresse | **Accès refusé** (403) — l'absence de bouton n'est pas la seule protection |
| 6.4 | Tables de référence | Liste, versions et aperçus visibles ; **pas** de bloc « Charger une version » |
| 6.5 | Modèles d'analyse | Liste et association visibles en lecture ; pas d'enregistrement possible |
| 6.6 | Export des règles | **Autorisé** : un export est une lecture |

## 7. Volumétrie (PERF-02, PERF-03) — optionnel, 15 minutes

| # | Action | Attendu |
|---|---|---|
| 7.1 | Fabriquer un CSV de 2 millions de lignes (copier `assures.csv` en boucle) et l'importer | Chargement progressif, mémoire de l'application stable — aucun `OutOfMemoryError` |
| 7.2 | Pendant le chargement, naviguer dans l'application | Les écrans restent utilisables : le chargement est asynchrone |
| 7.3 | Essayer une règle sur ce fichier | Résultat en quelques secondes, **au plus 50 anomalies** affichées, avec la mention qu'il y en a d'autres |

## Ce qui n'est pas encore testable

À ne pas signaler comme défaut : ces lots ne sont pas livrés.

| Attendu du CDC | Étape prévue |
|---|---|
| Lancer une analyse complète et obtenir des scores par règle, dimension, colonne et global | 21 |
| Écran de rapport, export Excel et PDF | 22 |
| Historique des analyses et comparaison de deux exécutions | 23 |
| Double authentification (TOTP, codes de secours, e-mail) | 24 |
| Journal d'audit, purge à 90 jours, LDAP | 25 |

Conséquence : les règles et les modèles se préparent et s'essaient (REG-03), mais **aucune analyse
ne peut encore être exécutée**. Les écrans de rapport n'existent pas.

## Fiche de relevé

Pour chaque point : `OK`, `KO` ou `NA`, et en cas de `KO` noter l'écran, l'action, le résultat
obtenu, le résultat attendu, et la date/heure (les logs de l'application servent ensuite à
retrouver la trace).

| Lot | Points | Résultat |
|---|---|---|
| 0. Préparation | 0.1 à 0.3 | |
| 1. Import | 1.1 à 1.8 | |
| 2. Profil | 2.1 à 2.6 | |
| 3. Références | 3.1 à 3.6 | |
| 4. Règles | 4.1.1 à 4.3.8 | |
| 5. Modèles | 5.1 à 5.6 | |
| 6. Droits | 6.1 à 6.6 | |
| 7. Volumétrie | 7.1 à 7.3 | |
