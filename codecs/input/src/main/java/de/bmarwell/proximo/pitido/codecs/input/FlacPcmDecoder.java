/*
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by the European Commission - subsequent
 * versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * ${PROJECT_HOME}/LICENSE
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the Licence is
 * distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and limitations under the Licence.
 */
package de.bmarwell.proximo.pitido.codecs.input;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import javax.enterprise.context.ApplicationScoped;
import org.apache.tika.mime.MediaType;

/**
 * Stub FLAC decoder. FLAC support has not yet been implemented.
 *
 * <p>To add FLAC support, add the following dependency and implement this class:
 * <pre>
 *   &lt;dependency&gt;
 *     &lt;groupId&gt;org.jflac&lt;/groupId&gt;
 *     &lt;artifactId&gt;jflac-codec&lt;/artifactId&gt;
 *     &lt;version&gt;1.5.2&lt;/version&gt;
 *   &lt;/dependency&gt;
 * </pre>
 */
@ApplicationScoped
public class FlacPcmDecoder implements PcmDecoder {

    /** CDI no-args constructor. */
    public FlacPcmDecoder() {}

    @Override
    public boolean supports(String resourcePath, MediaType mimeType) {
        String lower = resourcePath.toLowerCase(Locale.ROOT);

        if (lower.endsWith(".flac")) {
            return true;
        }

        return MediaType.audio("flac").equals(mimeType)
                || MediaType.audio("x-flac").equals(mimeType);
    }

    @Override
    public PcmStream open(InputStream in) throws IOException {
        throw new UnsupportedOperationException("FLAC decoding is not yet implemented. "
                + "Add org.jflac:jflac-codec:1.5.2 and implement FlacPcmDecoder.");
    }
}
