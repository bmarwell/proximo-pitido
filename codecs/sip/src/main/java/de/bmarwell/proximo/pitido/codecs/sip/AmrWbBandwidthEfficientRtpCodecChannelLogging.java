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

import java.util.Locale;

/**
 * <!-- Dedicated channel for complex trace/debug logging of bandwidth-efficient AMR-WB codec operations.
 *
 * This class encapsulates verbose, multi-line logging that would clutter the main codec implementation.
 * Methods are designed to be called only when isLoggable() guards are true to avoid unnecessary string
 * formatting and object allocation when logging is disabled.
 *
 * All methods accept pre-computed diagnostic values and are responsible only for formatting and
 * emitting log messages.
 *
 * **To enable trace logging for this channel only, configure the logger name:**
 * ```
 * de.bmarwell.proximo.pitido.codecs.sip.AmrWbBandwidthEfficientRtpCodecChannelLogging=TRACE
 * ```
 *
 * This allows selective debugging without enabling trace on the main codec class.
 * -->
 */
public final class AmrWbBandwidthEfficientRtpCodecChannelLogging {

    private static final System.Logger LOGGER =
            System.getLogger(AmrWbBandwidthEfficientRtpCodecChannelLogging.class.getName());

    private AmrWbBandwidthEfficientRtpCodecChannelLogging() {
        // Utility class, no instances.
    }

    /**
     * <!-- Log PCM input diagnostics: sample range, first and last values.
     *
     * Called from encode() when System.Logger.Level.TRACE isLoggable().
     * -->
     */
    public static void logPcmInputDiagnostics(
            int encodingMode,
            int pcmSampleCount,
            short minSample,
            short maxSample,
            short firstSample,
            short lastSample) {

        LOGGER.log(
                System.Logger.Level.TRACE,
                "AMR-WB bandwidth-efficient encode: starting with encodingMode={0}, pcmSamples={1}, pcmRange=[{2},{3}], first={4}, last={5}",
                encodingMode,
                pcmSampleCount,
                minSample,
                maxSample,
                firstSample,
                lastSample);
    }

    /**
     * <!-- Log first three bytes of encoder output in hex format.
     *
     * Called from encode() when System.Logger.Level.TRACE isLoggable().
     * -->
     */
    public static void logEncoderOutputSample(byte firstByte, byte secondByte, byte thirdByte) {

        LOGGER.log(
                System.Logger.Level.TRACE,
                "Encoder output first 3 bytes (hex): {0} {1} {2}",
                String.format(Locale.ROOT, "%02x", firstByte & 0xFF),
                String.format(Locale.ROOT, "%02x", secondByte & 0xFF),
                String.format(Locale.ROOT, "%02x", thirdByte & 0xFF));
    }

    /**
     * <!-- Log validation of encoder output ToC byte against expected value.
     *
     * Called from encode() when encoder output does not match expected value or when
     * System.Logger.Level.TRACE isLoggable().
     * -->
     */
    public static void logToCANVersionMismatch(byte firstEncoderByte, byte expectedOctetAlignedToC, int encodingMode) {

        LOGGER.log(
                System.Logger.Level.WARNING,
                "Encoder output first byte (0x{0}) does not match expected octet-aligned ToC (0x{1}) for mode {2}; audio may be corrupt",
                String.format(Locale.ROOT, "%02x", firstEncoderByte & 0xFF),
                String.format(Locale.ROOT, "%02x", expectedOctetAlignedToC & 0xFF),
                encodingMode);
    }

    /**
     * <!-- Log successful validation of encoder output ToC byte.
     *
     * Called from encode() when System.Logger.Level.TRACE isLoggable() and encoder output
     * matches expected octet-aligned ToC.
     * -->
     */
    public static void logToCANVersionMatch(byte firstEncoderByte) {

        LOGGER.log(
                System.Logger.Level.TRACE,
                "Encoder output first byte matches expected octet-aligned ToC (0x{0})",
                String.format(Locale.ROOT, "%02x", firstEncoderByte & 0xFF));
    }

    /**
     * <!-- Log ToC conversion from octet-aligned encoder format to bandwidth-efficient RTP format.
     *
     * Called from encode() when System.Logger.Level.TRACE isLoggable().
     * Logs the transformation: encoder first byte → BW-efficient bytes 0-1 with field breakdown.
     * -->
     */
    public static void logBwEfficientToCANConversion(
            byte firstEncoderByte,
            byte bwByte0,
            byte bwByte1,
            int encodingMode,
            int fBit,
            int ftFromEncoder,
            int qFromEncoder) {

        LOGGER.log(
                System.Logger.Level.TRACE,
                "BW-efficient ToC conversion: encoder format 0x{0} → BW-efficient 0x{1}0x{2} (mode={3}, F={4}, FT={5}, Q={6})",
                String.format(Locale.ROOT, "%02x", firstEncoderByte & 0xFF),
                String.format(Locale.ROOT, "%02x", bwByte0 & 0xFF),
                String.format(Locale.ROOT, "%02x", bwByte1 & 0xFF),
                encodingMode,
                fBit,
                ftFromEncoder,
                qFromEncoder);
    }

    /**
     * <!-- Log complete payload structure: bytes, fields, and hex dump.
     *
     * Called from encode() when System.Logger.Level.TRACE isLoggable().
     * Shows CMR, F, FT, Q fields extracted from bandwidth-efficient header plus hex dump of
     * first several payload bytes.
     * -->
     */
    public static void logBwEfficientPayload(
            int encodingMode, byte[] bwEfficientPayload, int cmrBits, int fBit, int frameType, int qualityBit) {

        StringBuilder hexDump = new StringBuilder();
        int bytesToShow = Math.min(10, bwEfficientPayload.length);
        for (int i = 0; i < bytesToShow; i++) {
            if (i > 0) hexDump.append(' ');
            hexDump.append(String.format(Locale.ROOT, "%02x", bwEfficientPayload[i] & 0xFF));
        }
        if (bwEfficientPayload.length > bytesToShow) {
            hexDump.append(String.format(Locale.ROOT, " ... (%d more)", bwEfficientPayload.length - bytesToShow));
        }

        LOGGER.log(
                System.Logger.Level.TRACE,
                "AMR-WB BW-efficient payload: encodingMode={0} payloadBytes={1} CMR={2} F={3} FT={4} Q={5} hex=[{6}]",
                encodingMode,
                bwEfficientPayload.length,
                cmrBits,
                fBit,
                frameType,
                qualityBit,
                hexDump.toString());
    }

    /**
     * <!-- Log completion of encode() operation with summary statistics.
     *
     * Called from encode() when System.Logger.Level.TRACE isLoggable().
     * -->
     */
    public static void logEncodeComplete(int speechBytes, int payloadLength) {

        LOGGER.log(
                System.Logger.Level.TRACE,
                "AMR-WB bandwidth-efficient encode complete: speechBytes={0} payloadBytes={1}",
                speechBytes,
                payloadLength);
    }
}
