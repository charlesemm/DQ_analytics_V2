package ci.cnam.dqanalyzer.importing.domain;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Compte les octets consommés d'un flux. C'est ainsi que les lecteurs expriment leur avancement : la
 * taille à lire est la seule grandeur connue à l'avance, le nombre de lignes ne l'étant qu'à la fin.
 */
final class CountingInputStream extends FilterInputStream {

    private long bytesRead;

    CountingInputStream(InputStream in) {
        super(in);
    }

    long bytesRead() {
        return bytesRead;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value >= 0) {
            bytesRead++;
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int count = super.read(buffer, offset, length);
        if (count > 0) {
            bytesRead += count;
        }
        return count;
    }
}
