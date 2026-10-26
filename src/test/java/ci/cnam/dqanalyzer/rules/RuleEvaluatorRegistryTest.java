package ci.cnam.dqanalyzer.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class RuleEvaluatorRegistryTest {

    @Test
    void retrouve_un_evaluateur_par_son_type() {
        var registry = new RuleEvaluatorRegistry(List.of(fake("CMP-01"), fake("UNI-01")));

        assertThat(registry.get("UNI-01").type()).isEqualTo("UNI-01");
        assertThat(registry.types()).containsExactly("CMP-01", "UNI-01");
    }

    @Test
    void type_inconnu_refuse() {
        var registry = new RuleEvaluatorRegistry(List.of(fake("CMP-01")));

        assertThatThrownBy(() -> registry.get("XXX-99")).isInstanceOf(RuleEvaluationException.class);
    }

    @Test
    void deux_evaluateurs_du_meme_type_refuses_au_demarrage() {
        assertThatThrownBy(() -> new RuleEvaluatorRegistry(List.of(fake("CMP-01"), fake("CMP-01"))))
                .isInstanceOf(IllegalStateException.class);
    }

    private static RuleEvaluator fake(String type) {
        return new RuleEvaluator() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public Dimension dimension() {
                return Dimension.COMPLETENESS;
            }

            @Override
            public ParamSchema paramSchema() {
                return ParamSchema.singleColumn();
            }

            @Override
            public RuleResult evaluate(Dataset data, RuleDefinition rule, ReferenceProvider refs, AnomalySink sink) {
                return RuleResult.of(0, 0);
            }
        };
    }
}
