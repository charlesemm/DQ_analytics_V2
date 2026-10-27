package ci.cnam.dqanalyzer.importing.domain;

import java.io.Closeable;
import java.util.List;

/**
 * Lecture d'un fichier importé sous forme tabulaire, ligne par ligne, quel que soit son format (CDC
 * §5.1 bis). Un format = une implémentation ; le chargement vers DuckDB, lui, est écrit une seule
 * fois ({@code DuckDbTableLoader}).
 *
 * <p>Le contrat est volontairement pauvre — un curseur qui avance et des champs lus par position —
 * parce que c'est tout ce qu'il faut à l'appender DuckDB, et parce qu'il interdit de construire la
 * moindre collection de l'ensemble du fichier : un fichier de 2 Go se lit avec la même empreinte
 * mémoire qu'un fichier de 2 Ko (PERF-03).
 *
 * <p>Conventions communes à tous les formats :
 *
 * <ul>
 *   <li>les colonnes sont connues dès l'ouverture, déjà normalisées par {@link HeaderNames} ;
 *   <li>{@link #rowNumber()} est le numéro de la ligne dans le fichier d'origine, celui que
 *       l'analyste retrouvera dans le rapport d'anomalies : avec en-tête, l'en-tête est la ligne 1 et
 *       la première donnée la ligne 2 ;
 *   <li>une ligne peut avoir moins ou plus de champs que de colonnes ; c'est au chargement de
 *       compléter ou de tronquer, et de compter ces lignes.
 * </ul>
 */
public interface RowSource extends Closeable {

    /** L'avancement reste sous les 100 % jusqu'à la fin du chargement, posée par l'appelant. */
    int MAX_PERCENT_BEFORE_END = 99;

    /** Colonnes du fichier, dans leur ordre d'origine, noms déjà rendus utilisables. */
    List<String> columns();

    /**
     * Avance sur la ligne de données suivante.
     *
     * @return false quand le fichier est épuisé
     * @throws ci.cnam.dqanalyzer.importing.ImportRejectedException si la ligne est illisible
     */
    boolean next();

    /** Numéro de la ligne courante dans le fichier d'origine, à partir de 1. */
    long rowNumber();

    /** Nombre de champs de la ligne courante, qui peut différer du nombre de colonnes. */
    int fieldCount();

    /**
     * Champ de la ligne courante.
     *
     * @return la valeur, ou {@code null} si le champ est absent de cette ligne ou vide : dans un
     *     fichier, rien ne distingue « vide » de « non renseigné »
     */
    String field(int index);

    /** Avancement estimé de la lecture, de 0 à {@value #MAX_PERCENT_BEFORE_END}. */
    int percent();
}
