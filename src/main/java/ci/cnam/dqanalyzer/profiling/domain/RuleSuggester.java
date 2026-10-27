package ci.cnam.dqanalyzer.profiling.domain;

import ci.cnam.dqanalyzer.profiling.ColumnProfile;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Propose des règles à partir du profil (PRF-04). Ce ne sont que des suggestions : l'analyste coche ce qu'il garde. */
@Component
public class RuleSuggester {

    public record Suggestion(String column, String ruleType, String reason) {}

    public List<Suggestion> suggest(List<ColumnProfile> profiles) {
        var result = new ArrayList<Suggestion>();
        for (var p : profiles) {
            if (p.filled() == 0) {
                continue;
            }
            if (p.empty() == 0) {
                result.add(new Suggestion(p.column(), "CMP-01", "Colonne toujours renseignée"));
            }
            if (p.distinctValues() == p.filled() && p.filled() > 1) {
                result.add(new Suggestion(p.column(), "UNI-01", "Toutes les valeurs sont différentes"));
            }
            switch (p.detectedType()) {
                case "ENTIER" ->
                    result.add(new Suggestion(p.column(), "VAL-01", "Presque toutes les valeurs sont des entiers"));
                case "DECIMAL" ->
                    result.add(new Suggestion(p.column(), "VAL-01", "Presque toutes les valeurs sont des nombres"));
                case "DATE" ->
                    result.add(new Suggestion(p.column(), "VAL-06", "Presque toutes les valeurs sont des dates"));
                default -> {
                    if (p.distinctValues() > 0 && p.distinctValues() <= 10 && p.filled() >= 20) {
                        result.add(new Suggestion(
                                p.column(), "VAL-05", "Peu de valeurs différentes : liste fermée probable"));
                    }
                }
            }
            if (p.minLength() != null && p.minLength().equals(p.maxLength()) && p.minLength() > 1) {
                result.add(new Suggestion(
                        p.column(), "VAL-03", "Toutes les valeurs font " + p.minLength() + " caractères"));
            }
        }
        return result;
    }
}
