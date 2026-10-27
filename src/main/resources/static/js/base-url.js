// Préfixe de toutes les adresses construites en JavaScript.
//
// L'application est servie sous un sous-chemin du domaine d'ÉCHO
// (https://echo.ipscnam.ci/dq/). Une adresse écrite « /api/... » viserait la racine du
// domaine — donc ÉCHO, qui répondrait 404 — et non nous. Les gabarits n'ont pas le
// problème : Thymeleaf préfixe lui-même tout @{...}. Ici, le gabarit nous passe le même
// préfixe par la balise meta « base-url », et rien n'est codé en dur.

const base = (document.querySelector('meta[name="base-url"]')?.getAttribute('content') || '/')
    .replace(/\/$/, '');

/** « /api/uploads » devient « /dq/api/uploads » en production, « /api/uploads » sans sous-chemin. */
export function adresse(chemin) {
    return base + chemin;
}
