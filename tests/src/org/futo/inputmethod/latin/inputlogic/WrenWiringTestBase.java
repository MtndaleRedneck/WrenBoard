/*
 * Wiring-test machinery for the abandoned auto-correct commit learning path.
 *
 * These are EXECUTED instrumented tests (real LatinIME service, real EditText and
 * InputConnection from InputTestsBase) that drive the actual production learning path
 * - the package-private InputLogic.learnAbandonedTypedWord seam and the normal
 * separator commit - with a recording DictionaryFacilitator swapped over the shared
 * facilitator instance, and then verify that the known synthetic fixture word is
 * actually stored in the on-device user history dictionary. Word identity is asserted
 * inside the test process and comes out as PASS/FAIL only; nothing here makes the app
 * log typed or learned text.
 */
package org.futo.inputmethod.latin.inputlogic;

import android.os.SystemClock;

import org.futo.inputmethod.engine.IMEManager;
import org.futo.inputmethod.engine.general.GeneralIME;
import org.futo.inputmethod.latin.Dictionary;
import org.futo.inputmethod.latin.DictionaryFacilitator;
import org.futo.inputmethod.latin.DictionaryFacilitatorImpl;
import org.futo.inputmethod.latin.ExpandableBinaryDictionary;
import org.futo.inputmethod.latin.InputTestsBase;
import org.futo.inputmethod.latin.LatinIME;
import org.futo.inputmethod.latin.LatinIMELegacy;
import org.futo.inputmethod.latin.NgramContext;
import org.futo.inputmethod.latin.RichInputConnection;
import org.futo.inputmethod.latin.SuggestedWords;
import org.futo.inputmethod.latin.settings.Settings;
import org.futo.inputmethod.latin.utils.ExecutorUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import android.os.SystemClock;

import androidx.test.InstrumentationRegistry;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

public class WrenWiringTestBase extends InputTestsBase {

    /** One recorded learning call: the word and the n-gram context it was learned with. */
    static final class LearnRecord {
        final String word;
        final NgramContext context;
        LearnRecord(final String word, final NgramContext context) {
            this.word = word;
            this.context = context;
        }
    }

    /**
     * Delegates everything to the real implementation (so writes really happen) and
     * records the two calls that make up a learning event, in order. Swapped over the
     * one facilitator instance shared by GeneralIME and InputLogic.
     */
    static class RecordingFacilitator extends DictionaryFacilitatorImpl {
        // Ordered event log: "commit:<word>" then "learn:<word>" proves pipeline order.
        final List<String> events = new ArrayList<>();
        final List<LearnRecord> learns = new ArrayList<>();

        @Override
        public synchronized void onWordCommitted(final String suggestion) {
            events.add("commit:" + suggestion);
            super.onWordCommitted(suggestion);
        }

        @Override
        public void addToUserHistory(final String suggestion, final boolean wasAutoCapitalized,
                @Nonnull final NgramContext ngramContext, final long timeStampInSeconds,
                final boolean blockPotentiallyOffensive) {
            synchronized (this) {
                events.add("learn:" + suggestion);
                learns.add(new LearnRecord(suggestion, ngramContext));
            }
            super.addToUserHistory(suggestion, wasAutoCapitalized, ngramContext,
                    timeStampInSeconds, blockPotentiallyOffensive);
        }

        synchronized List<LearnRecord> learnsFor(final String word) {
            final List<LearnRecord> out = new ArrayList<>();
            for (final LearnRecord r : learns) {
                if (word.equals(r.word)) out.add(r);
            }
            return out;
        }

        synchronized int totalLearnCount() {
            return learns.size();
        }

        synchronized List<String> eventsSnapshot() {
            return new ArrayList<>(events);
        }

        synchronized void clearRecords() {
            events.clear();
            learns.clear();
        }
    }

    protected Settings mSettings;
    protected GeneralIME mGeneralIme;
    protected InputLogic mInputLogic;
    protected RecordingFacilitator mRecorder;
    private Field mGeneralFacilitatorField;
    private Field mLogicFacilitatorField;
    private DictionaryFacilitator mOriginalGeneralFacilitator;
    private DictionaryFacilitator mOriginalLogicFacilitator;

    static Field getField(final Class<?> klass, final String name) throws Exception {
        Class<?> k = klass;
        while (k != null) {
            try {
                final Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (final NoSuchFieldException e) {
                k = k.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " on " + klass.getName());
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mSettings = Settings.getInstance();
        // Reach the live engine instance that actually processes key events:
        // LatinIMELegacy.mImeManager -> getActiveIME(current settings) -> GeneralIME,
        // then its private inputLogic (the package these tests live in).
        final Field mgrField = getField(LatinIMELegacy.class, "mImeManager");
        final IMEManager mgr = (IMEManager) mgrField.get(mLatinIMELegacy);
        mGeneralIme = (GeneralIME) mgr.getActiveIME(mSettings.getCurrent());
        final Field logicField = getField(GeneralIME.class, "inputLogic");
        mInputLogic = (InputLogic) logicField.get(mGeneralIme);
        // GeneralIME constructs InputLogic with the one shared facilitator instance;
        // swap both references to the same recorder so the whole path is observed and
        // writes still delegate to the real dictionaries.
        mGeneralFacilitatorField = getField(GeneralIME.class, "dictionaryFacilitator");
        mOriginalGeneralFacilitator = (DictionaryFacilitator) mGeneralFacilitatorField.get(mGeneralIme);
        mLogicFacilitatorField = getField(InputLogic.class, "mDictionaryFacilitator");
        mOriginalLogicFacilitator = (DictionaryFacilitator) mLogicFacilitatorField.get(mInputLogic);
        // Clean-history reset, fail-closed:
        // 1) LatinIMELegacy.clearPersonalizedDictionariesForTest() is an empty stub in this
        //    tree (LatinIMELegacy.java:1006-1008, body is a TODO), so use the real mechanism
        //    on the original facilitator: clearUserHistoryDictionary deletes the on-disk
        //    history file (ExpandableBinaryDictionary.clear -> removeBinaryDictionaryLocked).
        // 2) The deletion runs on the dictionary write queue - await the actual deletion by
        //    polling the file itself; a fixed sleep is not proof. Fails the test if the file
        //    survives the deadline.
        // 3) Only then is the recorder constructed and loaded: a fresh load against an
        //    absent file is a genuinely empty history. The real-wire test additionally
        //    asserts dynamic absence of the fixture word before learning.
        mOriginalGeneralFacilitator.clearUserHistoryDictionary(getContext());
        // Completion proof, not a sleep: the clear's deletion runs on the single-threaded
        // KEYBOARD executor (ExecutorUtils.newSingleThreadScheduledExecutor), so a barrier
        // submitted to the same executor completes only after the clear task has run.
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD)
                .submit(new Runnable() { @Override public void run() { } })
                .get(10, TimeUnit.SECONDS);
        // Dynamic observable on top: the on-disk file must actually be gone.
        awaitUserHistoryFileAbsent(Locale.US, 10_000);
        mRecorder = new RecordingFacilitator();
        mGeneralFacilitatorField.set(mGeneralIme, mRecorder);
        mLogicFacilitatorField.set(mInputLogic, mRecorder);
        changeLanguage("en_US");
        // changeLanguage resets the legacy-side facilitator, not the recorder; load the
        // recorder's dictionaries explicitly against the just-cleared file state.
        resetFacilitatorLocales(Collections.singletonList(Locale.US));
    }

    @Override
    protected void tearDown() throws Exception {
        // Capture the service BEFORE super.tearDown(): ServiceTestCase.tearDown nulls
        // mService after shutdownService(), so getService() afterwards returns null.
        final LatinIME service = getService();
        try {
            if (mGeneralFacilitatorField != null && mGeneralIme != null) {
                mGeneralFacilitatorField.set(mGeneralIme, mOriginalGeneralFacilitator);
            }
            if (mLogicFacilitatorField != null && mInputLogic != null) {
                mLogicFacilitatorField.set(mInputLogic, mOriginalLogicFacilitator);
            }
            // Recorder ownership: the engine no longer references it and nothing else
            // closes it, so close its dictionaries/queues explicitly.
            if (mRecorder != null) {
                mRecorder.closeDictionaries();
            }
            super.tearDown();
        } finally {
            // ServiceTestCase never destroys the service here: startService/bindService
            // are never called, so mServiceCreated stays false and shutdownService() is a
            // no-op (AOSP frameworks/base ServiceTestCase source). Destroy it explicitly -
            // on the main thread because onDestroy mutates the LifecycleRegistry - so the
            // coroutine jobs and receivers registered in onCreate do not leak across tests.
            if (service != null) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
                    @Override public void run() {
                        service.onDestroy();
                    }
                });
            }
        }
    }

    /** The exact call the abandon branch of commitCurrentAutoCorrection makes. */
    protected void callAbandonedLearn() {
        mInputLogic.learnAbandonedTypedWord(mSettings.getCurrent());
    }

    protected static int countOf(final List<String> haystack, final String needle) {
        int n = 0;
        for (final String s : haystack) {
            if (needle.equals(s)) n++;
        }
        return n;
    }

    /** Polls until the word is actually retrievable from the live user history dict. */
    protected boolean waitForHistoryWord(final String word, final long timeoutMs) {
        final long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            final ExpandableBinaryDictionary d =
                    mRecorder.getSubDictForTesting(Dictionary.TYPE_USER_HISTORY);
            if (d != null && d.isInDictionary(word)) return true;
            sleep(100);
        }
        return false;
    }

    /** Forces the real hasSlowInputConnection() state on the live connection. */
    protected void forceSlowInputConnection() throws Exception {
        final Field f = getField(RichInputConnection.class, "mLastSlowInputConnectionTime");
        f.setLong(mInputLogic.mConnection, SystemClock.uptimeMillis());
    }

    protected void resetSlowInputConnection() {
        mInputLogic.mConnection.onStartInput();
    }

    /**
     * Invokes the production starter-words function on the live engine and returns the
     * actual strip words it would show for the current (empty) field. This is the real
     * output path GeneralIME.showSuggestionStrip feeds, called in-process so the word
     * identity comes out as PASS/FAIL only.
     */
    protected List<String> invokeStarterWordsIfNeeded(final SuggestedWords input) throws Exception {
        final Method m = GeneralIME.class.getDeclaredMethod(
                "starterWordsIfNeeded", SuggestedWords.class, int.class, int.class);
        m.setAccessible(true);
        final SuggestedWords out = (SuggestedWords) m.invoke(mGeneralIme, input,
                SuggestedWords.INPUT_STYLE_TYPING, 1);
        final List<String> words = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) {
            words.add(out.getWord(i));
        }
        return words;
    }

    /** A SuggestedWords shaped the way the empty-field prediction feeds the starter path. */
    protected SuggestedWords predictionWithHistoryWord(final String word) {
        final ExpandableBinaryDictionary historyDict =
                mRecorder.getSubDictForTesting(Dictionary.TYPE_USER_HISTORY);
        final ArrayList<SuggestedWords.SuggestedWordInfo> infos = new ArrayList<>();
        infos.add(new SuggestedWords.SuggestedWordInfo(word, "" /* prevWordsContext */,
                SuggestedWords.SuggestedWordInfo.MAX_SCORE,
                SuggestedWords.SuggestedWordInfo.KIND_PREDICTION, historyDict,
                SuggestedWords.SuggestedWordInfo.NOT_AN_INDEX,
                SuggestedWords.SuggestedWordInfo.NOT_A_CONFIDENCE));
        return new SuggestedWords(infos, null /* rawSuggestions */, null /* typedWordInfo */,
                false, false, false, SuggestedWords.INPUT_STYLE_TYPING, 1,
                null /* highlightedCandidate */);
    }

    /** Resets the recorder's dictionaries to hold several locale groups AT ONCE. */
    protected void resetFacilitatorLocales(final List<Locale> locales) throws Exception {
        mRecorder.resetDictionaries(getContext(), locales,
                false /* useContactsDict */, true /* usePersonalizedDicts */,
                true /* forceReloadAllDictionaries */, null /* account */,
                "" /* dictNamePrefix, same as the app */, null /* listener */);
        mRecorder.waitForLoadingDictionariesForTesting(5, TimeUnit.SECONDS);
    }

    /** Queries the user-history sub-dictionary of ONE specific locale group. */
    protected boolean isInLocaleGroupHistory(final Locale locale, final String word)
            throws Exception {
        final Field groupsF = getField(DictionaryFacilitatorImpl.class, "mDictionaryGroups");
        final List<?> groups = (List<?>) groupsF.get(mRecorder);
        for (final Object g : groups) {
            final Field localeF = getField(g.getClass(), "mLocale");
            if (locale.equals(localeF.get(g))) {
                final Method getSub = g.getClass().getDeclaredMethod("getSubDict", String.class);
                getSub.setAccessible(true);
                final ExpandableBinaryDictionary d =
                        (ExpandableBinaryDictionary) getSub.invoke(g, Dictionary.TYPE_USER_HISTORY);
                return d != null && d.isInDictionary(word);
            }
        }
        return false;
    }

    /**
     * Fails closed: waits until the on-disk user-history file for the locale is actually
     * gone, i.e. the clear's queued deletion has run. The file name mirrors
     * UserHistoryDictionary.getUserHistoryDictName + ExpandableBinaryDictionary.getDictFile
     * ("UserHistoryDictionary.<locale>.dict" in filesDir).
     */
    protected void awaitUserHistoryFileAbsent(final Locale locale, final long timeoutMs) {
        final File f = new File(getContext().getFilesDir(),
                "UserHistoryDictionary." + locale.toString() + ".dict");
        final long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (f.exists() && SystemClock.uptimeMillis() < deadline) {
            sleep(50);
        }
        if (f.exists()) {
            fail("user-history file still present " + timeoutMs + "ms after clear: "
                    + f.getName());
        }
    }
}
