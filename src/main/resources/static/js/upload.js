// Envoi d'un fichier par morceaux, avec reprise après une coupure réseau (IMP-01, PERF-02).
// Le fichier n'est jamais lu en entier : Blob.slice ne découpe que la tranche qu'on envoie.
// Aucune dépendance : le navigateur sait tout faire, et la page ne charge pas de bibliothèque.

import {adresse} from './base-url.js';

const CSRF_HEADER = 'X-CSRF-TOKEN';
const RETRIES_PER_CHUNK = 3;
const RETRY_DELAY_MS = 2000;

/** Jeton anti-rejeu posé dans la page par Thymeleaf. */
function csrfToken() {
    const meta = document.querySelector('meta[name="csrf-token"]');
    return meta ? meta.getAttribute('content') : '';
}

function headers(extra) {
    return Object.assign({[CSRF_HEADER]: csrfToken()}, extra || {});
}

async function postForm(url, fields) {
    const body = new URLSearchParams(fields);
    const response = await fetch(url, {
        method: 'POST',
        headers: headers({'Content-Type': 'application/x-www-form-urlencoded'}),
        body
    });
    if (!response.ok) {
        throw await readError(response);
    }
    return response.json();
}

/** Le serveur répond par un ProblemDetail : son champ « detail » est le message à montrer. */
async function readError(response) {
    try {
        const problem = await response.json();
        return new Error(problem.detail || response.statusText);
    } catch (ignored) {
        return new Error(response.statusText);
    }
}

function delay(milliseconds) {
    return new Promise(resolve => setTimeout(resolve, milliseconds));
}

/**
 * Envoie un fichier par morceaux et renvoie la réponse de l'assemblage.
 *
 * @param file          le fichier choisi par l'analyste
 * @param onProgress    appelé avec un pourcentage de 0 à 100
 * @param uploadId      identifiant d'un envoi à reprendre, ou null pour en ouvrir un nouveau
 */
export async function uploadInChunks(file, onProgress, uploadId) {
    let upload;
    if (uploadId) {
        const response = await fetch(adresse(`/api/uploads/${uploadId}`), {headers: headers()});
        if (!response.ok) {
            throw await readError(response);
        }
        upload = await response.json();
    } else {
        upload = await postForm(adresse('/api/uploads'), {fileName: file.name, totalSize: file.size});
    }

    const chunkSize = upload.chunkSize;
    const chunks = Math.max(1, Math.ceil(file.size / chunkSize));
    // Les morceaux déjà reçus ne sont pas renvoyés : c'est tout l'intérêt de la reprise.
    const received = new Set(upload.receivedChunks);

    for (let number = 0; number < chunks; number++) {
        if (received.has(number)) {
            onProgress(Math.round(((number + 1) / chunks) * 100));
            continue;
        }
        const start = number * chunkSize;
        const slice = file.slice(start, Math.min(start + chunkSize, file.size));
        await sendChunk(upload.uploadId, number, slice);
        onProgress(Math.round(((number + 1) / chunks) * 100));
    }
    return postForm(adresse(`/api/uploads/${upload.uploadId}/complete`), {});
}

/**
 * Pousse un morceau, en réessayant : une coupure réseau est la règle, pas l'exception, et renvoyer
 * un morceau déjà reçu est sans conséquence côté serveur.
 */
async function sendChunk(uploadId, number, slice) {
    let lastError;
    for (let attempt = 1; attempt <= RETRIES_PER_CHUNK; attempt++) {
        try {
            const response = await fetch(adresse(`/api/uploads/${uploadId}/chunks/${number}`), {
                method: 'PUT',
                headers: headers({'Content-Type': 'application/octet-stream'}),
                body: slice
            });
            if (response.ok) {
                return;
            }
            lastError = await readError(response);
            if (response.status >= 400 && response.status < 500) {
                throw lastError; // fichier refusé ou envoi inconnu : réessayer n'y changera rien
            }
        } catch (error) {
            lastError = error;
        }
        if (attempt < RETRIES_PER_CHUNK) {
            await delay(RETRY_DELAY_MS * attempt);
        }
    }
    throw lastError;
}
