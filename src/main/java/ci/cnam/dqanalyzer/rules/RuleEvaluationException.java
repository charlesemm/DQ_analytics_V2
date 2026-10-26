package ci.cnam.dqanalyzer.rules;

/** Erreur de paramétrage ou d'exécution d'une règle. Le message doit être lisible par l'analyste. */
public class RuleEvaluationException extends RuntimeException {

    public RuleEvaluationException(String message) {
        super(message);
    }

    public RuleEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}
