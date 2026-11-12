package org.futo.inputmethod.latin.uix.actions;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Guards the transaction ordering that prevents async migration from replacing a completed drop. */
public class UnifiedMigrationContractTest {
    @Test public void migrationChecksCurrentValueInsideAtomicEdit() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get("java/src/org/futo/inputmethod/latin/uix/actions/UnifiedActions.kt")), StandardCharsets.UTF_8);
        int read = source.indexOf("fun Context.readUnifiedActions()");
        int edit = source.indexOf("dataStore.edit { prefs ->", read);
        // WrenBoard 1.9 (item 7): v2 key applies a one-time favorites-first reorder over a
        // saved v1 order inside the same atomic edit; v1 stays as the migration input only.
        int current = source.indexOf("prefs[UnifiedActionsKeyV2]?.decodeUnified()", edit);
        int reorder = source.indexOf("prefs[UnifiedActionsKey]?.decodeUnified()?.reorderFavoritesFirstV2()", edit);
        int legacy = source.indexOf("migrateUnifiedActionIds(", edit);
        int save = source.indexOf("fun Context.saveUnifiedActions(");
        assertTrue(edit > read && current > edit && reorder > current && legacy > reorder && legacy < save);
        assertTrue(source.contains("dataStore.edit { it[UnifiedActionsKeyV2] = safe.encode() }"));
    }
    @Test public void composableDoesNotBlockForMigration() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get("java/src/org/futo/inputmethod/latin/uix/actions/UnifiedActions.kt")), StandardCharsets.UTF_8);
        String composable = source.substring(source.indexOf("fun useUnifiedActions()"));
        assertFalse(composable.contains("remember(context) { context.readUnifiedActions() }"));
        assertTrue(composable.contains("withContext(kotlinx.coroutines.Dispatchers.IO)"));
        assertTrue(composable.contains("useDataStoreValue(ActionsSettings)"));
    }
}
