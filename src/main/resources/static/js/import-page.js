// Écran « Importer un fichier » : glisser-déposer, envoi par morceaux, aperçu, suivi du chargement.
// Aucun texte n'est écrit ici : les libellés viennent de messages.properties par les attributs
// data- du gabarit (ERG-02). Aucune bibliothèque : le navigateur suffit.

import {uploadInChunks} from './upload.js';
import {adresse} from './base-url.js';

const POLL_DELAY_MS = 700;

const textes = document.getElementById('textes').dataset;
const erreur = document.getElementById('erreur');
const etapes = {
    choix: document.getElementById('etape-choix'),
    envoi: document.getElementById('etape-envoi'),
    apercu: document.getElementById('etape-apercu'),
    chargement: document.getElementById('etape-chargement')
};

/** Fichier reçu par le serveur, et donc en attente de chargement. */
let sourceFileId = null;
let format = null;

function csrfToken() {
    return document.querySelector('meta[name="csrf-token"]').getAttribute('content');
}

function montrer(etape, visible) {
    etape.classList.toggle('d-none', !visible);
}

function direErreur(message) {
    erreur.textContent = message;
    montrer(erreur, true);
}

function effacerErreur() {
    montrer(erreur, false);
}

async function lire(response) {
    if (response.ok) {
        return response.json();
    }
    let detail = response.statusText;
    try {
        const probleme = await response.json();
        detail = probleme.detail || detail;
    } catch (ignored) {
        // réponse sans corps JSON : on garde le statut
    }
    throw new Error(detail);
}

// --- Étape 1 : choisir le fichier (IMP-01) ---

const zone = document.getElementById('zone-depot');
const champFichier = document.getElementById('fichier');

['dragenter', 'dragover'].forEach(evenement => zone.addEventListener(evenement, event => {
    event.preventDefault();
    zone.classList.add('zone-depot-active');
}));
['dragleave', 'drop'].forEach(evenement => zone.addEventListener(evenement, event => {
    event.preventDefault();
    zone.classList.remove('zone-depot-active');
}));
zone.addEventListener('drop', event => {
    const fichiers = event.dataTransfer.files;
    if (fichiers.length > 0) {
        envoyer(fichiers[0]);
    }
});
champFichier.addEventListener('change', () => {
    if (champFichier.files.length > 0) {
        envoyer(champFichier.files[0]);
    }
});

// --- Étape 2 : l'envoi par morceaux, avec reprise (IMP-01, PERF-02) ---

async function envoyer(fichier) {
    effacerErreur();
    if (fichier.size === 0) {
        direErreur(textes.vide);
        return;
    }
    const barre = document.getElementById('envoi-barre');
    document.getElementById('envoi-nom').textContent = `${fichier.name} — ${textes.envoiEnCours}`;
    montrer(etapes.choix, false);
    montrer(etapes.envoi, true);
    avancer(barre, 0);
    try {
        const recu = await uploadInChunks(fichier, pourcentage => avancer(barre, pourcentage), null);
        sourceFileId = recu.id;
        format = recu.format;
        montrer(etapes.envoi, false);
        await rafraichirApercu();
    } catch (echec) {
        montrer(etapes.envoi, false);
        montrer(etapes.choix, true);
        direErreur(echec.message);
    }
}

function avancer(barre, pourcentage) {
    barre.style.width = `${pourcentage}%`;
    barre.textContent = `${pourcentage} %`;
    barre.parentElement.setAttribute('aria-valuenow', pourcentage);
}

// --- Étape 3 : paramètres et aperçu (IMP-02, IMP-03, IMP-04, IMP-08) ---

const parametres = document.getElementById('parametres');

parametres.addEventListener('submit', event => {
    event.preventDefault();
    rafraichirApercu();
});
document.getElementById('recommencer').addEventListener('click', () => window.location.reload());
document.getElementById('charger').addEventListener('click', charger);

/** Ne montre que les champs qui veulent dire quelque chose pour ce format. */
function ajusterChamps() {
    parametres.querySelectorAll('[data-pour]').forEach(champ => {
        const formats = champ.dataset.pour.split(' ');
        champ.classList.toggle('d-none', !formats.includes(format));
    });
}

function corrections() {
    const champs = new URLSearchParams();
    parametres.querySelectorAll('[data-pour]').forEach(bloc => {
        if (bloc.classList.contains('d-none')) {
            return; // un champ caché ne corrige rien
        }
        bloc.querySelectorAll('input, select').forEach(champ => {
            if (champ.type === 'checkbox') {
                champs.set(champ.name, champ.checked);
            } else if (champ.value !== '') {
                champs.set(champ.name, champ.value);
            }
        });
    });
    return champs;
}

async function rafraichirApercu() {
    effacerErreur();
    try {
        const url = adresse(`/api/imports/${sourceFileId}/preview?${corrections()}`);
        const apercu = await lire(await fetch(url, {headers: {'X-CSRF-TOKEN': csrfToken()}}));
        format = apercu.format;
        ajusterChamps();
        remplirParametres(apercu);
        dessinerApercu(apercu);
        montrer(etapes.apercu, true);
    } catch (echec) {
        montrer(etapes.apercu, true);
        direErreur(echec.message);
    }
}

/** Remet dans le formulaire ce que le serveur a retenu : détection ou correction. */
function remplirParametres(apercu) {
    const retenus = apercu.settings;
    const feuilles = document.getElementById('sheet');
    feuilles.innerHTML = '';
    apercu.sheetNames.forEach(nom => {
        const option = document.createElement('option');
        option.value = nom;
        option.textContent = nom;
        option.selected = nom === retenus.sheetName;
        feuilles.appendChild(option);
    });
    if (retenus.charsetName) {
        document.getElementById('charset').value = retenus.charsetName;
    }
    if (retenus.headerRow !== undefined && retenus.headerRow > 0) {
        document.getElementById('headerRow').value = retenus.headerRow;
    }
    if (retenus.rowElement) {
        document.getElementById('rowElement').value = retenus.rowElement;
    }
    if (retenus.arrayPath) {
        document.getElementById('arrayPath').value = retenus.arrayPath;
    }
    document.getElementById('hasHeader').checked = apercu.settings.hasHeader !== false;
}

function dessinerApercu(apercu) {
    const entete = document.getElementById('apercu-entete');
    entete.innerHTML = '';
    entete.appendChild(cellule('th', '#'));
    apercu.columns.forEach(colonne => entete.appendChild(cellule('th', colonne)));

    const corps = document.getElementById('apercu-lignes');
    corps.innerHTML = '';
    apercu.rows.forEach(ligne => {
        const tr = document.createElement('tr');
        tr.appendChild(cellule('th', ligne.number));
        apercu.columns.forEach((colonne, index) => {
            const valeur = ligne.values[index];
            tr.appendChild(cellule('td', valeur === null || valeur === undefined ? '' : valeur));
        });
        corps.appendChild(tr);
    });
    montrer(document.getElementById('apercu-tronque'), apercu.truncated);
}

function cellule(balise, contenu) {
    const cell = document.createElement(balise);
    cell.textContent = contenu;
    return cell;
}

// --- Étape 4 : le chargement, suivi jusqu'au bout (PERF-03) ---

async function charger() {
    effacerErreur();
    const barre = document.getElementById('chargement-barre');
    montrer(etapes.chargement, true);
    document.getElementById('chargement-etat').textContent = textes.enCours;
    avancer(barre, 0);
    try {
        await lire(await fetch(adresse(`/api/imports/${sourceFileId}/load`), {
            method: 'POST',
            headers: {'X-CSRF-TOKEN': csrfToken(), 'Content-Type': 'application/x-www-form-urlencoded'},
            body: corrections()
        }));
        await suivre(barre);
    } catch (echec) {
        direErreur(echec.message);
    }
}

/** Le fichier est chargé : l'analyste enchaîne sur son profil (PRF-01 à PRF-04). */
function montrerProfil() {
    const lien = document.getElementById('voir-profil');
    lien.setAttribute('href', adresse(`/fichiers/${sourceFileId}/profil`));
    montrer(lien, true);
}

async function suivre(barre) {
    for (;;) {
        const etat = await lire(await fetch(adresse(`/api/imports/${sourceFileId}`)));
        avancer(barre, etat.percent);
        if (etat.status === 'DONE') {
            barre.classList.add('bg-success');
            document.getElementById('chargement-etat').textContent = textes.termine;
            const resume = document.getElementById('chargement-resultat');
            resume.textContent = `${etat.rowCount} ${textes.lignes}`
                + (etat.raggedRowCount > 0 ? ` — ${etat.raggedRowCount} ${textes.incompletes}` : '');
            montrer(resume, true);
            montrerProfil();
            return;
        }
        if (etat.status === 'FAILED') {
            barre.classList.add('bg-danger');
            const echec = document.getElementById('chargement-echec');
            echec.textContent = etat.message;
            montrer(echec, true);
            return;
        }
        await new Promise(resolve => setTimeout(resolve, POLL_DELAY_MS));
    }
}
