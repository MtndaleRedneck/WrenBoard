package org.futo.inputmethod.latin.uix.actions;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
/** Removed action-key controls must not be required by strict menu projections at startup. */
public class RemovedActionSettingsContractTest {
 @Test public void layoutMenuDoesNotRequireRemovedControls() throws Exception {
  String source=new String(Files.readAllBytes(Paths.get("java/src/org/futo/inputmethod/latin/uix/settings/pages/WrenSettings.kt")), java.nio.charset.StandardCharsets.UTF_8);
  assertFalse(source.contains("R.string.action_settings_quick_option_enable_action_key"));
  assertFalse(source.contains("R.string.action_settings_quick_option_language_switch_key"));
 }
}
