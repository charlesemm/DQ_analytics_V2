package ci.cnam.dqanalyzer.security;

/**
 * Identité de l'utilisateur connecté, pour les modules qui doivent la tracer (fichier importé,
 * analyse lancée, journal d'audit). C'est le seul point d'entrée prévu : les comptes eux-mêmes
 * restent internes au module {@code security}.
 */
public interface CurrentUserProvider {

    /**
     * @return l'identifiant du compte connecté
     * @throws IllegalStateException s'il n'y a pas d'utilisateur authentifié : tout appel se fait
     *     derrière un endpoint protégé, ce cas signale une erreur de programmation
     */
    long currentUserId();
}
