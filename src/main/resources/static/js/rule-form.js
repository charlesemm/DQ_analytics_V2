// Éditeur de conditions et de calculs du formulaire de règle (REG-02).
//
// L'analyste ne saisit jamais d'expression libre : il compose des lignes
// « colonne · opérateur · valeur · type », et ce module en construit le JSON attendu par
// Condition (CMP-03, COH-02) ou par NumericExpression (COH-03). Le serveur revalide ce JSON :
// rien de ce qui est produit ici n'est fait confiance.
//
// Le JSON d'une condition : {"all": [{"column": "statut", "op": "eq", "value": "AD", "as": "text"}]}
// Celui d'un calcul :       {"op": "mul", "args": [{"column": "quantite"}, {"value": 2}]}

const OPERATEURS_SANS_VALEUR = ['EMPTY', 'FILLED'];

/** Les lignes d'un éditeur, relues depuis le JSON déjà enregistré. */
function lignesDuJson(editeur) {
    const valeur = editeur.querySelector('[data-valeur]').value;
    if (!valeur) {
        return [];
    }
    try {
        const json = JSON.parse(valeur);
        if (editeur.dataset.mode === 'calcul') {
            return lignesDuCalcul(json);
        }
        const liste = json.all || json.any || [];
        return liste.map((c) => ({
            column: c.column || '',
            op: (c.op || 'eq').toUpperCase(),
            value: Array.isArray(c.value) ? c.value.join('; ') : (c.value ?? ''),
            as: (c.as || 'text').toUpperCase(),
        }));
    } catch {
        return [];
    }
}

/** Déplie l'arbre d'un calcul en lignes, dans l'ordre où elles ont été saisies. */
function lignesDuCalcul(json) {
    if (!json || typeof json !== 'object') {
        return [];
    }
    if (json.args) {
        const [gauche, droite] = json.args;
        return [...lignesDuCalcul(gauche), {...lignesDuCalcul(droite)[0], op: json.op}];
    }
    return [{column: json.column || '', value: json.value ?? ''}];
}

function ajouterLigne(editeur, valeurs) {
    const selecteurModele =
        editeur.dataset.mode === 'calcul' ? '[data-modele-ligne-calcul]' : '[data-modele-ligne]';
    const modele = document.querySelector(selecteurModele);
    const ligne = modele.content.cloneNode(true).querySelector('[data-ligne]');
    if (valeurs) {
        remplir(ligne, '[data-colonne]', valeurs.column);
        remplir(ligne, '[data-operateur]', valeurs.op);
        remplir(ligne, '[data-valeur-ligne]', valeurs.value);
        remplir(ligne, '[data-type]', valeurs.as);
    }
    ligne.querySelector('[data-supprimer-ligne]').addEventListener('click', () => {
        ligne.remove();
        majJson(editeur);
    });
    ligne.querySelectorAll('select, input').forEach((champ) => {
        champ.addEventListener('change', () => majJson(editeur));
        champ.addEventListener('input', () => majJson(editeur));
    });
    editeur.querySelector('[data-lignes]').appendChild(ligne);
    majJson(editeur);
}

function remplir(ligne, selecteur, valeur) {
    const champ = ligne.querySelector(selecteur);
    if (champ && valeur !== undefined && valeur !== null) {
        champ.value = valeur;
    }
}

/** Reconstruit le JSON caché à partir des lignes affichées. */
function majJson(editeur) {
    const lignes = [...editeur.querySelectorAll('[data-ligne]')];
    const champCache = editeur.querySelector('[data-valeur]');
    if (editeur.dataset.mode === 'calcul') {
        champCache.value = lignes.length === 0 ? '' : JSON.stringify(calcul(lignes));
        return;
    }
    const conditions = lignes
        .map((ligne) => comparaison(ligne))
        .filter((condition) => condition !== null);
    if (conditions.length === 0) {
        champCache.value = '';
        return;
    }
    const jointure = editeur.querySelector('[data-jointure][value="any"]')?.checked ? 'any' : 'all';
    champCache.value = JSON.stringify({[jointure]: conditions});
}

function comparaison(ligne) {
    const colonne = ligne.querySelector('[data-colonne]').value.trim();
    if (!colonne) {
        return null;
    }
    const operateur = ligne.querySelector('[data-operateur]').value;
    const type = ligne.querySelector('[data-type]').value;
    const valeur = ligne.querySelector('[data-valeur-ligne]').value.trim();
    const condition = {column: colonne, op: operateur.toLowerCase(), as: type.toLowerCase()};
    if (OPERATEURS_SANS_VALEUR.includes(operateur)) {
        return condition;
    }
    const plusieurs = operateur === 'IN' || operateur === 'NOT_IN';
    condition.value = plusieurs ? valeur.split(/\s*;\s*/).filter(Boolean) : valeur;
    return condition;
}

/**
 * Un calcul : « quantité × prix unitaire », au besoin sur plusieurs lignes, lues de gauche à
 * droite. Chaque ligne est une colonne, ou un nombre si aucune colonne n'est choisie, et porte
 * l'opération qui la relie à ce qui précède.
 */
function calcul(lignes) {
    const operande = (ligne) => {
        const colonne = ligne.querySelector('[data-colonne]').value.trim();
        const valeur = ligne.querySelector('[data-valeur-ligne]').value.trim();
        return colonne ? {column: colonne} : {value: Number(valeur.replace(',', '.'))};
    };
    return lignes.slice(1).reduce(
        (gauche, ligne) => ({
            op: ligne.querySelector('[data-operateur]').value,
            args: [gauche, operande(ligne)],
        }),
        operande(lignes[0]));
}

function activer(editeur) {
    if (editeur.dataset.pret === 'oui') {
        return;
    }
    editeur.dataset.pret = 'oui';
    editeur.querySelector('[data-ajouter-ligne]').addEventListener('click', () => ajouterLigne(editeur, null));
    editeur.querySelectorAll('[data-jointure]').forEach((bouton) =>
        bouton.addEventListener('change', () => majJson(editeur)));
    const existantes = lignesDuJson(editeur);
    if (existantes.length === 0) {
        ajouterLigne(editeur, null);
    } else {
        existantes.forEach((valeurs) => ajouterLigne(editeur, valeurs));
    }
}

function activerAjoutDeColonne() {
    document.querySelectorAll('[data-ajouter-colonne]').forEach((bouton) => {
        if (bouton.dataset.pret === 'oui') {
            return;
        }
        bouton.dataset.pret = 'oui';
        bouton.addEventListener('click', () => {
            const modele = document.querySelector('[data-modele-colonne]');
            document.querySelector('[data-colonnes]').appendChild(modele.content.cloneNode(true));
        });
    });
}

function activerTout() {
    document.querySelectorAll('.editeur-expression').forEach(activer);
    activerAjoutDeColonne();
}

activerTout();
// Les champs de paramètres sont rechargés par HTMX quand le type de règle change.
document.body.addEventListener('htmx:afterSwap', activerTout);
