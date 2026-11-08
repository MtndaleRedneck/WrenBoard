package org.futo.inputmethod.latin.uix.actions;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Processing diagnostics must not add a release WBDBG site or gate the mic-off cue. */
public class VoiceProcessingLogContractTest {
    @Test public void processingLogIsDebugOnlyAndSoundStaysOutsideGuard() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get("java/src/org/futo/inputmethod/latin/uix/actions/VoiceInputAction.kt")), StandardCharsets.UTF_8);
        int begin = source.indexOf("override fun processing() {");
        int end = source.indexOf("override fun recordingStarted", begin);
        assertTrue(begin >= 0 && end > begin);
        String processing = source.substring(begin, end);
        assertTrue(processing.contains("if (org.futo.inputmethod.latin.BuildConfig.DEBUG) {\n            android.util.Log.i(\"WBDBG\", \"WINDOW VOICE capture processing\")\n        }\n        if (shouldPlaySounds && !offTonePlayed)"));
        assertTrue(processing.contains("state.soundPlayer?.playStopSound()"));
    }
}
