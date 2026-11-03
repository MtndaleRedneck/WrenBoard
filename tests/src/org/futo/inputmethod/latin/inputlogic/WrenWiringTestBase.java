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
import android.view.inputmethod.EditorInfo;

import org.futo.inputmethod.engine.IMEManager;
import org.futo.inputmethod.engine.general.GeneralIME;
import org.futo.inputmethod.latin.Dictionary;
import org.futo.inputmethod.latin.DictionaryFacilitator;
import org.futo.inputmethod.latin.DictionaryFacilitatorImpl;
import org.futo.inputmethod.latin.ExpandableBinaryDictionary;
import org.futo.inputmethod.latin.InputTestsBase;
import org.futo.inputmethod.latin.LatinIMELegacy;
import org.futo.inputmethod.latin.NgramContext;
import org.futo.inputmethod.latin.RichInputConnection;
import org.futo.inputmethod.latin.settings.Settings;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

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
        mRecorder = new RecordingFacilitator();
        mGeneralFacilitatorField = getField(GeneralIME.class, "dictionaryFacilitator");
        mOriginalGeneralFacilitator = (DictionaryFacilitator) mGeneralFacilitatorField.get(mGeneralIme);
        mGeneralFacilitatorField.set(mGeneralIme, mRecorder);
        mLogicFacilitatorField = getField(InputLogic.class, "mDictionaryFacilitator");
        mOriginalLogicFacilitator = (DictionaryFacilitator) mLogicFacilitatorField.get(mInputLogic);
        mLogicFacilitatorField.set(mInputLogic, mRecorder);
        // (Re)load dictionaries against the recorder and start from a clean history.
        changeLanguage("en_US");
    }

    @Override
    protected void tearDown() throws Exception {
        if (mGeneralFacilitatorField != null && mGeneralIme != null) {
            mGeneralFacilitatorField.set(mGeneralIme, mOriginalGeneralFacilitator);
        }
        if (mLogicFacilitatorField != null && mInputLogic != null) {
            mLogicFacilitatorField.set(mInputLogic, mOriginalLogicFacilitator);
        }
        super.tearDown();
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
}
