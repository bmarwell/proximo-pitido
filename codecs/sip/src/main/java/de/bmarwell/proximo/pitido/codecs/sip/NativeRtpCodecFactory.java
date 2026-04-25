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
package de.bmarwell.proximo.pitido.codecs.sip;

import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;

/**
 * Base class for native codec factories that require dynamic library probing.
 *
 * <p>Subclasses call {@link #probeLibrary(String, String)} in their constructor
 * to check library availability and set the {@code available} flag.
 * Probing occurs at construction time; availability cannot change during runtime.
 */
public abstract class NativeRtpCodecFactory implements RtpCodecFactory {

    private static final System.Logger LOGGER = System.getLogger(NativeRtpCodecFactory.class.getName());

    protected boolean available = false;

    @Override
    public boolean isAvailable() {
        return this.available;
    }

    /**
     * Probes for a native library and sets {@code available} flag.
     *
     * <p>Called from subclass constructors.
     * If the library is found, uses {@link Arena#global()} to keep it loaded for the JVM lifetime.
     * Logs availability status at INFO or WARNING level.
     *
     * @param libraryName the native library name (e.g. {@code "libopus.so.0"})
     * @param codecName   the human-readable codec name for logging (e.g. {@code "Opus RTP codec"})
     */
    protected void probeLibrary(String libraryName, String codecName) {
        try {
            SymbolLookup _ = SymbolLookup.libraryLookup(libraryName, Arena.global());
            Linker _ = Linker.nativeLinker();

            this.available = true;
            LOGGER.log(System.Logger.Level.INFO, "{0} library detected — {1} available", libraryName, codecName);

        } catch (IllegalArgumentException illegalArgumentException) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "{0} not found — {1} disabled: {2}",
                    libraryName,
                    codecName,
                    illegalArgumentException.getMessage());
        }
    }
}
