package org.futo.inputmethod.latin.uix.actions;
import org.junit.Test;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
public class Wren18ScopeContractTest {
 private String read(String p) throws Exception { return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8); }
 @Test public void oneApprovedMicrophoneDrawingEverywhere() throws Exception {
  String root="java/res/drawable/";
  String source=read(root+"wren_mic_hint.xml");
  for(String name:new String[]{"mic.xml","mic_fill.xml","wren_tb_mic.xml","wren_ic_mic_fill.xml","wren_set_ic_voice.xml"}) assertEquals(name,source,read(root+name));
 }
 @Test public void oneApprovedClipboardDrawingEverywhere() throws Exception {
  String root="java/res/drawable/";
  String source=read(root+"wren_clipboard_hint.xml");
  for(String name:new String[]{"clipboard.xml","clipboard_manager.xml","wren_tb_clipboard.xml","wren_set_ic_clipboard.xml"}) assertEquals(name,source,read(root+name));
 }
 @Test public void settingKeeps123AndDefaultsClipboardOn() throws Exception {
  String setting=read("java/src/org/futo/inputmethod/latin/settings/LongPressKeySettings.kt");
  assertTrue(setting.contains("booleanPreferencesKey(\"wren_clipboard_on_123\"), true"));
  String base=read("java/src/org/futo/inputmethod/v2keyboard/BaseKey.kt");
  assertTrue(base.contains("!params.mId.mLongPressKeySettings.clipboardOn123 && moreKeys == SymbolsMoreKeys"));
 }
 @Test public void imeDoesNotRequireAnActivityBackDispatcher() throws Exception {
  String source=read("java/src/org/futo/inputmethod/latin/uix/actions/MoreActions.kt");
  assertTrue(source.contains("if(settingsNav != null) BackHandler"));
  assertTrue(source.contains("settingsNav != null) null else LocalManager.current"));
 }
}
