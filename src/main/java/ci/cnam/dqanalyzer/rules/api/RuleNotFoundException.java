package ci.cnam.dqanalyzer.rules.api;

/** Règle demandée par un écran alors qu'elle n'existe pas : l'URL a été saisie à la main. */
class RuleNotFoundException extends RuntimeException {

    RuleNotFoundException(String ruleCode) {
        super("Règle inconnue : " + ruleCode);
    }
}
